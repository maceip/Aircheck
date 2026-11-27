#!/usr/bin/env python3
"""
Direct Voice-to-Talking-Head CLI for Aircheck / VoiceStudio.

Takes ANY real voice audio input (.wav/.mp3/.m4a/.pcm) + portrait image (defaulting to
Ryan's canonical `/Users/mac/Downloads/clone/images/avatar_ref.jpg`), optionally transcribes
the voice locally via offline `whisper-cli` (`/Users/mac/models/ggml-medium-q5_0.bin`),
segments >10s audio into coherent 4-10s beats with zero <4s tails, invokes
`bl video ref --model wan3.0-video` with consistent anchor portrait + camera prompt,
normalizes/concatenates beat video streams in a single ffmpeg pass, muxes the exact
lossless master WAV audio once, and verifies A/V sync via ffprobe.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import base64
import json
import time
import logging
import math
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import warnings
import wave
from pathlib import Path
from typing import Any, Mapping, Sequence

import numpy as np

LOGGER = logging.getLogger("voicestudio.generate_talking_head")

DEFAULT_AVATAR = Path("/Users/mac/Downloads/clone/images/avatar_ref.jpg")
DEFAULT_WHISPER_MODEL = Path("/Users/mac/models/ggml-medium-q5_0.bin")
DEFAULT_BL_BIN = Path("/Users/mac/.local/bin/bl")

VALID_RESOLUTIONS = ("720P", "1080P")
VALID_RATIOS = ("9:16", "16:9", "1:1")
MIN_WAN_BEAT_SEC = 4
MAX_WAN_BEAT_SEC = 10
MAX_TOTAL_DURATION_SEC = 120
MAX_AV_SYNC_DRIFT_MS = 120.0

FFPROBE_TIMEOUT_SEC = 30
FFMPEG_TIMEOUT_SEC = 300
WHISPER_TIMEOUT_SEC = 120
BL_BEAT_TIMEOUT_SEC = 600

_RESOLUTION_DIMENSIONS: dict[tuple[str, str], tuple[int, int]] = {
    ("720P", "9:16"): (720, 1280),
    ("720P", "16:9"): (1280, 720),
    ("720P", "1:1"): (720, 720),
    ("1080P", "9:16"): (1080, 1920),
    ("1080P", "16:9"): (1920, 1080),
    ("1080P", "1:1"): (1080, 1080),
}

_SILENCE_START_RE_PREFIX = "silence_start:"
_SILENCE_END_RE_PREFIX = "silence_end:"


def normalize_talking_head_resolution(resolution: str) -> str:
    """Validate resolution and clamp legacy '480P' -> '720P' for wan3.0-video."""
    norm = str(resolution or "").strip().upper()
    if norm == "480P":
        msg = "Clamping unsupported wan3.0-video resolution '480P' -> '720P'"
        LOGGER.warning(msg)
        warnings.warn(msg, UserWarning, stacklevel=2)
        return "720P"
    if norm in VALID_RESOLUTIONS:
        return norm
    raise ValueError(
        f"Unsupported resolution {resolution!r}; must be one of {VALID_RESOLUTIONS} (or '480P' clamped to '720P')."
    )


def _validate_ratio_and_duration(ratio: str, max_duration: int) -> str:
    norm_ratio = str(ratio or "").strip()
    if norm_ratio not in VALID_RATIOS:
        raise ValueError(f"Unsupported ratio {ratio!r}; must be one of {VALID_RATIOS}.")
    if isinstance(max_duration, bool) or not isinstance(max_duration, int):
        raise ValueError(f"max_duration must be an integer between 2 and {MAX_TOTAL_DURATION_SEC}.")
    if not (2 <= max_duration <= MAX_TOTAL_DURATION_SEC):
        raise ValueError(
            f"max_duration must be between 2 and {MAX_TOTAL_DURATION_SEC} seconds, got {max_duration}."
        )
    return norm_ratio


def _summarize_proc_error(label: str, proc: subprocess.CompletedProcess[str]) -> str:
    stderr = (proc.stderr or "").strip()
    stdout = (proc.stdout or "").strip()
    detail = stderr if stderr else stdout
    if len(detail) > 900:
        detail = "..." + detail[-900:]
    return f"{label} failed (rc={proc.returncode}): {detail}" if detail else f"{label} failed (rc={proc.returncode})"


def _run_subprocess(
    cmd: Sequence[str],
    *,
    timeout: int,
    label: str,
) -> subprocess.CompletedProcess[str]:
    try:
        proc = subprocess.run(
            list(cmd),
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"{label} timed out after {timeout}s") from exc
    if proc.returncode != 0:
        raise RuntimeError(_summarize_proc_error(label, proc))
    return proc


def probe_duration_sec(audio_path: Path, *, timeout_sec: int = FFPROBE_TIMEOUT_SEC) -> float:
    proc = _run_subprocess(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(audio_path),
        ],
        timeout=timeout_sec,
        label="ffprobe duration",
    )
    raw = (proc.stdout or "").strip()
    if not raw:
        raise RuntimeError(f"ffprobe returned empty duration for {audio_path}")
    try:
        dur = float(raw)
    except ValueError as exc:
        raise RuntimeError(f"ffprobe returned non-numeric duration {raw!r} for {audio_path}") from exc
    if not math.isfinite(dur) or dur <= 0.0:
        raise RuntimeError(f"ffprobe returned invalid duration {dur} for {audio_path}")
    return dur


def _prepare_canonical_master_wav(
    voice_input: Path,
    out_wav: Path,
    *,
    max_duration_sec: int,
    sample_rate_for_pcm: int = 44100,
) -> tuple[Path, int]:
    """Convert any supported audio (.wav/.mp3/.m4a/.pcm) to 44.1kHz mono PCM16 WAV.

    Only trims to `max_duration_sec` when the input audio exceeds `max_duration_sec`.
    """
    input_args: list[str] = []
    if voice_input.suffix.lower() == ".pcm":
        input_args = ["-f", "f32le", "-ar", str(sample_rate_for_pcm), "-ac", "1"]

    raw_wav = out_wav.with_name(f"{out_wav.stem}_raw_full.wav")
    _run_subprocess(
        [
            "ffmpeg",
            "-y",
            *input_args,
            "-i",
            str(voice_input),
            "-ac",
            "1",
            "-ar",
            "44100",
            "-c:a",
            "pcm_s16le",
            str(raw_wav),
        ],
        timeout=FFMPEG_TIMEOUT_SEC,
        label="ffmpeg canonical wav",
    )
    raw_ms = max(1, int(round(probe_duration_sec(raw_wav) * 1000.0)))
    max_ms = max_duration_sec * 1000

    if raw_ms > max_ms:
        _run_subprocess(
            [
                "ffmpeg",
                "-y",
                "-i",
                str(raw_wav),
                "-t",
                f"{max_duration_sec:.6f}",
                "-ac",
                "1",
                "-ar",
                "44100",
                "-c:a",
                "pcm_s16le",
                str(out_wav),
            ],
            timeout=FFMPEG_TIMEOUT_SEC,
            label="ffmpeg trim master wav",
        )
        raw_wav.unlink(missing_ok=True)
        total_ms = max(1, int(round(probe_duration_sec(out_wav) * 1000.0)))
    else:
        raw_wav.replace(out_wav)
        total_ms = raw_ms

    return out_wav, total_ms




def transcribe_offline_whisper(wav16_path: Path, whisper_model: Path, tmp_dir: Path) -> str:
    whisper_bin = shutil.which("whisper-cli")
    if not whisper_bin or not whisper_model.exists():
        return ""
    out_prefix = tmp_dir / "whisper_out"
    _run_subprocess(
        [
            whisper_bin,
            "-m",
            str(whisper_model),
            "-l",
            "en",
            "-f",
            str(wav16_path),
            "-otxt",
            "-of",
            str(out_prefix),
            "-np",
        ],
        timeout=WHISPER_TIMEOUT_SEC,
        label="whisper-cli txt",
    )
    txt_file = Path(f"{out_prefix}.txt")
    if not txt_file.exists():
        return ""
    return " ".join(txt_file.read_text(encoding="utf-8").split())














def verify_av_sync_ffprobe(
    mp4_path: Path,
    *,
    expected_audio_ms: int | None = None,
    expected_width: int | None = None,
    expected_height: int | None = None,
    max_drift_ms: float = MAX_AV_SYNC_DRIFT_MS,
    timeout_sec: int = FFPROBE_TIMEOUT_SEC,
) -> dict[str, Any]:
    """Verify MP4 has valid video & audio streams and A/V sync drift <= max_drift_ms."""
    if not mp4_path.is_file() or mp4_path.stat().st_size == 0:
        raise RuntimeError(f"Rendered MP4 missing or empty for ffprobe QA: {mp4_path}")

    proc = _run_subprocess(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_format",
            "-show_streams",
            "-of",
            "json",
            str(mp4_path),
        ],
        timeout=timeout_sec,
        label="ffprobe A/V sync QA",
    )
    try:
        meta = json.loads(proc.stdout or "{}")
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"ffprobe returned invalid JSON for {mp4_path}: {exc}") from exc

    streams = meta.get("streams") or []
    fmt = meta.get("format") or {}
    v_stream = next((s for s in streams if s.get("codec_type") == "video"), None)
    a_stream = next((s for s in streams if s.get("codec_type") == "audio"), None)

    if v_stream is None:
        raise RuntimeError(f"Rendered MP4 missing video stream: {mp4_path}")
    if a_stream is None:
        raise RuntimeError(f"Rendered MP4 missing audio stream: {mp4_path}")

    width = int(v_stream.get("width") or 0)
    height = int(v_stream.get("height") or 0)
    if width <= 0 or height <= 0:
        raise RuntimeError(f"Rendered MP4 has invalid video dimensions {width}x{height}: {mp4_path}")
    if expected_width is not None and width != expected_width:
        raise RuntimeError(f"Rendered MP4 width {width} != expected {expected_width}: {mp4_path}")
    if expected_height is not None and height != expected_height:
        raise RuntimeError(f"Rendered MP4 height {height} != expected {expected_height}: {mp4_path}")

    sample_rate = int(a_stream.get("sample_rate") or 0)
    if sample_rate <= 0:
        raise RuntimeError(f"Rendered MP4 audio stream has invalid sample_rate={sample_rate}: {mp4_path}")

    fmt_dur_s = float(fmt.get("duration") or 0.0)
    v_dur_s = float(v_stream.get("duration") or fmt_dur_s or 0.0)
    a_dur_s = float(a_stream.get("duration") or fmt_dur_s or 0.0)
    if v_dur_s <= 0.0 or a_dur_s <= 0.0:
        raise RuntimeError(
            f"Rendered MP4 has non-positive stream duration (video={v_dur_s}s, audio={a_dur_s}s): {mp4_path}"
        )

    v_dur_ms = v_dur_s * 1000.0
    a_dur_ms = a_dur_s * 1000.0
    v_start_ms = float(v_stream.get("start_time") or 0.0) * 1000.0
    a_start_ms = float(a_stream.get("start_time") or 0.0) * 1000.0

    drift_terms = [
        abs(v_dur_ms - a_dur_ms),
        abs(v_start_ms - a_start_ms),
    ]
    if expected_audio_ms is not None and expected_audio_ms > 0:
        drift_terms.append(abs(a_dur_ms - float(expected_audio_ms)))
    av_sync_drift_ms = round(max(drift_terms), 2)

    if av_sync_drift_ms > max_drift_ms:
        raise RuntimeError(
            f"A/V sync drift {av_sync_drift_ms:.2f}ms exceeds {max_drift_ms:.1f}ms limit "
            f"(video={v_dur_ms:.1f}ms, audio={a_dur_ms:.1f}ms, expected={expected_audio_ms}ms)"
        )

    return {
        "av_sync_drift_ms": av_sync_drift_ms,
        "video_duration_ms": int(round(v_dur_ms)),
        "audio_duration_ms": int(round(a_dur_ms)),
        "width": width,
        "height": height,
        "sample_rate": sample_rate,
    }





# HeyGen is the single talking-head engine (Ryan, 2026-11-25: "Just yes, use it"; bake-off winner
# was HeyGen Avatar 6). Renders Ryan's approved photo-avatar look lip-synced to the exact voice wav.
HEYGEN_BIN = Path(os.environ.get("AIRCHECK_HEYGEN_BIN", str(Path.home() / ".local/bin/heygen")))
HEYGEN_LOOK_ID = os.environ.get("AIRCHECK_HEYGEN_LOOK_ID", "28bbc1a0f7217675473054ab02d759d9")
HEYGEN_ENGINE = os.environ.get("AIRCHECK_HEYGEN_ENGINE", "heygen-avatar-6")
HEYGEN_POLL_SEC = 10


def _heygen_json(args: list[str], timeout: int = 180) -> dict[str, Any]:
    proc = subprocess.run([str(HEYGEN_BIN), *args], capture_output=True, text=True, timeout=timeout, check=False)
    if proc.returncode != 0:
        raise RuntimeError(f"heygen {args[0]} {args[1]} failed: {(proc.stderr or proc.stdout).strip()[:600]}")
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"heygen {args[0]} {args[1]} returned non-JSON: {proc.stdout[:300]}") from exc


def _invoke_heygen_avatar(
    *,
    master_wav: Path,
    avatar_path: Path,
    use_custom_photo: bool,
    output_mp4: Path,
    ratio: str,
    resolution: str,
    timeout_sec: int,
) -> dict[str, Any]:
    if not HEYGEN_BIN.is_file():
        raise RuntimeError(f"HeyGen CLI not found at {HEYGEN_BIN}")
    asset = _heygen_json(["asset", "create", "--file", str(master_wav)])["data"]
    if use_custom_photo and avatar_path.is_file():
        img_asset = _heygen_json(["asset", "create", "--file", str(avatar_path)])["data"]
        body: dict[str, Any] = {
            "type": "image",
            "image": {"type": "asset_id", "asset_id": img_asset["asset_id"]},
            "audio_asset_id": asset["asset_id"],
            "aspect_ratio": ratio,
            "resolution": "1080p",
        }
    else:
        body = {
            "type": "avatar",
            "avatar_id": HEYGEN_LOOK_ID,
            "audio_asset_id": asset["asset_id"],
            "engine": {"type": HEYGEN_ENGINE},
            "aspect_ratio": ratio,
            "resolution": "1080p",  # Avatar 6 accepts only 1080p or 2k
        }
    created = _heygen_json(["video", "create", "-d", json.dumps(body)])["data"]
    video_id = created.get("video_id") or created.get("id")
    if not video_id:
        raise RuntimeError(f"heygen video create returned no video id: {created}")
    deadline = time.time() + timeout_sec
    status = "pending"
    info: dict[str, Any] = {}
    while time.time() < deadline:
        info = _heygen_json(["video", "get", str(video_id)])["data"]
        status = str(info.get("status") or "")
        if status == "completed":
            break
        if status == "failed":
            raise RuntimeError(f"HeyGen render failed: {info.get('failure_message') or info.get('error') or info}")
        time.sleep(HEYGEN_POLL_SEC)
    if status != "completed":
        raise RuntimeError(f"HeyGen render {video_id} still '{status}' after {timeout_sec}s")
    output_mp4.parent.mkdir(parents=True, exist_ok=True)
    proc = subprocess.run(
        [str(HEYGEN_BIN), "video", "download", str(video_id), "--output-path", str(output_mp4), "--force"],
        capture_output=True, text=True, timeout=600, check=False,
    )
    if proc.returncode != 0 or not output_mp4.is_file():
        raise RuntimeError(f"heygen video download failed: {(proc.stderr or proc.stdout).strip()[:400]}")
    return {
        "heygen_video_id": video_id,
        "heygen_look_id": "custom_photo" if use_custom_photo else HEYGEN_LOOK_ID,
        "heygen_engine": HEYGEN_ENGINE,
        "used_custom_photo": use_custom_photo,
    }


def generate_talking_head(
    voice_input: Path,
    avatar_path: Path,
    output_mp4: Path,
    *,
    transcript: str | None = None,
    resolution: str = "1080P",
    ratio: str = "9:16",
    max_duration: int = 10,
    replace_audio_with_input: bool = True,
    whisper_model: Path = DEFAULT_WHISPER_MODEL,
    bl_timeout_sec: int = BL_BEAT_TIMEOUT_SEC,
    use_custom_photo: bool = False,
    media_config_path: Path | None = None,
) -> dict:
    from media_providers.registry import load_config

    media_config = load_config(media_config_path)
    norm_resolution = normalize_talking_head_resolution(resolution)
    norm_ratio = _validate_ratio_and_duration(ratio, max_duration)
    target_w, target_h = _RESOLUTION_DIMENSIONS[(norm_resolution, norm_ratio)]

    if not voice_input.exists():
        raise FileNotFoundError(f"Voice input not found: {voice_input}")
    if not avatar_path.exists():
        raise FileNotFoundError(f"Avatar image not found: {avatar_path}")

    output_mp4.parent.mkdir(parents=True, exist_ok=True)

    full_transcript = (transcript or "").strip()
    with tempfile.TemporaryDirectory(prefix="aircheck_head_") as tmp_str:
        tmp_dir = Path(tmp_str)
        master_wav = tmp_dir / "master_voice.wav"
        _, total_ms = _prepare_canonical_master_wav(
            voice_input,
            master_wav,
            max_duration_sec=max_duration,
        )
        if media_config.video_backend == "musetalk":
            from media_providers.musetalk import render

            return render(
                media_config.musetalk or {}, master_wav=master_wav, avatar=avatar_path,
                output=output_mp4, width=target_w, height=target_h, duration_ms=total_ms,
            )
        if not full_transcript and whisper_model.is_file():
            wav16 = tmp_dir / "full_16k.wav"
            _run_subprocess(
                [
                    "ffmpeg", "-y", "-i", str(master_wav),
                    "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(wav16),
                ],
                timeout=FFMPEG_TIMEOUT_SEC,
                label="ffmpeg full 16k whisper",
            )
            full_transcript = transcribe_offline_whisper(wav16, whisper_model, tmp_dir / "w_full")

        remote_meta = _invoke_heygen_avatar(
            master_wav=master_wav,
            avatar_path=avatar_path,
            use_custom_photo=use_custom_photo,
            output_mp4=output_mp4,
            ratio=norm_ratio,
            resolution=norm_resolution,
            timeout_sec=max(bl_timeout_sec, 1800),
        )
        qa_target = output_mp4
        result_lambda: dict[str, Any] = {
            "voice_input": str(voice_input),
            "avatar_path": str(avatar_path),
            "transcript": full_transcript,
            "duration_sec": round(total_ms / 1000.0, 3),
            "duration_ms": total_ms,
            "resolution": norm_resolution,
            "ratio": norm_ratio,
            "beat_count": 1,
            "beats": [
                {
                    "index": 0,
                    "start_ms": 0,
                    "end_ms": total_ms,
                    "duration_ms": total_ms,
                    "transcript": full_transcript,
                }
            ],
            "dry_run": False,
            "engine": f"heygen:{remote_meta['heygen_engine']}",
            "heygen": remote_meta,
            "output_mp4": str(output_mp4),
            "output_bytes": output_mp4.stat().st_size,
        }
        if replace_audio_with_input:
            orig_audio_mp4 = output_mp4.with_name(f"{output_mp4.stem}_exact_input_voice.mp4")
            # Mux the canonical master voice WAV onto HeyGen's video stream (copy video, no re-encode)
            # so the deliverable carries the exact input voice, not HeyGen's re-encoded audio track.
            _run_subprocess(
                [
                    "ffmpeg",
                    "-y",
                    "-i", str(output_mp4),
                    "-i", str(master_wav),
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-c:v", "copy",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-ar", "44100",
                    "-shortest",
                    "-movflags", "+faststart",
                    str(orig_audio_mp4),
                ],
                timeout=FFMPEG_TIMEOUT_SEC,
                label="ffmpeg mux exact input voice",
            )
            qa_target = orig_audio_mp4
            result_lambda["exact_input_voice_mp4"] = str(orig_audio_mp4)

        qa = verify_av_sync_ffprobe(
            qa_target,
            expected_audio_ms=total_ms,
            expected_width=target_w,
            expected_height=target_h,
        )
        result_lambda["ffprobe_qa"] = qa
        result_lambda["av_sync_drift_ms"] = qa["av_sync_drift_ms"]
        result_lambda["video_duration_ms"] = qa["video_duration_ms"]
        result_lambda["audio_duration_ms"] = qa["audio_duration_ms"]
        return result_lambda


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate a talking-head MP4 from any voice audio + portrait.")
    parser.add_argument("--voice-wav", required=True, type=Path, help="Input voice audio (.wav/.mp3/.m4a/.pcm)")
    parser.add_argument("--avatar", type=Path, default=DEFAULT_AVATAR, help="Portrait image path")
    parser.add_argument("--output", required=True, type=Path, help="Output talking-head MP4 path")
    parser.add_argument("--transcript", default=None, help="Optional spoken transcript (auto-transcribed via whisper-cli if omitted)")
    parser.add_argument("--resolution", default="720P", choices=["480P", "720P", "1080P"])
    parser.add_argument("--ratio", default="9:16", choices=["9:16", "16:9", "1:1"])
    parser.add_argument("--max-duration", type=int, default=10, help="Max clip duration in seconds (2..120, default 10)")
    parser.add_argument("--media-config", type=Path, default=None, help="Opt-in provider configuration; omitted keeps HeyGen")
    parser.add_argument("--custom-photo", action="store_true", help="Animate the supplied --avatar image instead of the default HeyGen look")
    args = parser.parse_args()

    res = generate_talking_head(
        voice_input=args.voice_wav,
        avatar_path=args.avatar,
        output_mp4=args.output,
        transcript=args.transcript,
        resolution=normalize_talking_head_resolution(args.resolution),
        ratio=args.ratio,
        max_duration=args.max_duration,
        use_custom_photo=bool(args.custom_photo),
        media_config_path=args.media_config,
    )
    print(json.dumps(res, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
