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
import logging
import math
import shutil
import subprocess
import sys
import tempfile
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
MAX_TOTAL_DURATION_SEC = 75
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


def prepare_mp3_and_wav16k(
    voice_input: Path,
    tmp_dir: Path,
    max_duration_sec: int,
    sample_rate_for_pcm: int = 44100,
) -> tuple[Path, Path, int]:
    master_wav = tmp_dir / "voice_master.wav"
    _, total_ms = _prepare_canonical_master_wav(
        voice_input,
        master_wav,
        max_duration_sec=max_duration_sec,
        sample_rate_for_pcm=sample_rate_for_pcm,
    )
    mp3_path = tmp_dir / "voice_input.mp3"
    wav16_path = tmp_dir / "voice_input_16k.wav"

    _run_subprocess(
        [
            "ffmpeg",
            "-y",
            "-i",
            str(master_wav),
            "-ac",
            "1",
            "-ar",
            "44100",
            "-b:a",
            "128k",
            str(mp3_path),
        ],
        timeout=FFMPEG_TIMEOUT_SEC,
        label="ffmpeg encode mp3",
    )
    _run_subprocess(
        [
            "ffmpeg",
            "-y",
            "-i",
            str(master_wav),
            "-ac",
            "1",
            "-ar",
            "16000",
            "-c:a",
            "pcm_s16le",
            str(wav16_path),
        ],
        timeout=FFMPEG_TIMEOUT_SEC,
        label="ffmpeg resample 16k",
    )

    actual_dur = total_ms / 1000.0
    duration_int = max(MIN_WAN_BEAT_SEC, min(MAX_WAN_BEAT_SEC, int(math.ceil(actual_dur))))
    return mp3_path, wav16_path, duration_int


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


def _transcribe_words_whisper_ojf(
    wav_path: Path,
    whisper_model: Path,
    tmp_dir: Path,
) -> list[dict[str, Any]]:
    whisper_bin = shutil.which("whisper-cli")
    if not whisper_bin or not whisper_model.exists():
        return []
    wav16 = tmp_dir / "whisper_align_16k.wav"
    _run_subprocess(
        [
            "ffmpeg",
            "-y",
            "-i",
            str(wav_path),
            "-ac",
            "1",
            "-ar",
            "16000",
            "-c:a",
            "pcm_s16le",
            str(wav16),
        ],
        timeout=FFMPEG_TIMEOUT_SEC,
        label="ffmpeg wav16 for whisper-ojf",
    )
    out_prefix = tmp_dir / "whisper_ojf"
    _run_subprocess(
        [
            whisper_bin,
            "-m",
            str(whisper_model),
            "-l",
            "en",
            "-f",
            str(wav16),
            "-ojf",
            "-of",
            str(out_prefix),
            "-np",
        ],
        timeout=WHISPER_TIMEOUT_SEC,
        label="whisper-cli ojf",
    )
    json_file = Path(f"{out_prefix}.json")
    if not json_file.is_file():
        return []
    try:
        data = json.loads(json_file.read_text(encoding="utf-8"))
    except Exception:
        return []
    words: list[dict[str, Any]] = []
    cur_text = ""
    cur_start: int | None = None
    cur_end: int | None = None
    for seg in data.get("transcription") or []:
        for tok in seg.get("tokens") or []:
            raw_txt = str(tok.get("text") or "")
            if not raw_txt or raw_txt.startswith("[_"):
                continue
            offsets = tok.get("offsets") or {}
            t0 = int(offsets.get("from") or 0)
            t1 = max(t0, int(offsets.get("to") or t0))
            if raw_txt[0].isspace() and cur_text:
                clean = cur_text.strip()
                if clean and any(ch.isalnum() for ch in clean):
                    words.append({"word": clean, "start_ms": cur_start or 0, "end_ms": cur_end or 0})
                cur_text = raw_txt.lstrip()
                cur_start = t0
                cur_end = t1
            else:
                if cur_start is None:
                    cur_start = t0
                cur_text += raw_txt
                cur_end = t1
    if cur_text:
        clean = cur_text.strip()
        if clean and any(ch.isalnum() for ch in clean):
            words.append({"word": clean, "start_ms": cur_start or 0, "end_ms": cur_end or 0})
    return words


def _detect_silences_ffmpeg(wav_path: Path, total_ms: int) -> list[tuple[int, int]]:
    if not wav_path.is_file() or not shutil.which("ffmpeg"):
        return []
    try:
        proc = subprocess.run(
            [
                "ffmpeg",
                "-hide_banner",
                "-i",
                str(wav_path),
                "-af",
                "silencedetect=noise=-35dB:d=0.20",
                "-f",
                "null",
                "-",
            ],
            capture_output=True,
            text=True,
            timeout=FFMPEG_TIMEOUT_SEC,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"ffmpeg silencedetect timed out after {FFMPEG_TIMEOUT_SEC}s") from exc
    log_text = (proc.stderr or "") + "\n" + (proc.stdout or "")
    gaps: list[tuple[int, int]] = []
    cur_start_ms: int | None = None
    for line in log_text.splitlines():
        if _SILENCE_START_RE_PREFIX in line:
            try:
                val = float(line.split(_SILENCE_START_RE_PREFIX, 1)[1].strip().split()[0])
                cur_start_ms = max(0, int(round(val * 1000.0)))
            except (ValueError, IndexError):
                cur_start_ms = None
        elif _SILENCE_END_RE_PREFIX in line and cur_start_ms is not None:
            try:
                val = float(line.split(_SILENCE_END_RE_PREFIX, 1)[1].strip().split()[0])
                end_ms = min(total_ms, int(round(val * 1000.0)))
                if end_ms > cur_start_ms:
                    gaps.append((cur_start_ms, end_ms))
            except (ValueError, IndexError):
                pass
            cur_start_ms = None
    return gaps


def _find_rms_minima_ms(wav_path: Path, total_ms: int, window_ms: int = 50) -> list[int]:
    if not wav_path.is_file():
        return []
    try:
        with wave.open(str(wav_path), "rb") as wf:
            if wf.getsampwidth() != 2:
                return []
            sr = max(1, wf.getframerate())
            ch = max(1, wf.getnchannels())
            raw = wf.readframes(wf.getnframes())
    except Exception:
        return []
    if len(raw) < 2:
        return []
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    if ch > 1:
        samples = samples.reshape(-1, ch).mean(axis=1)
    step = max(1, int(round(sr * window_ms / 1000.0)))
    n_win = len(samples) // step
    if n_win < 3:
        return []
    trimmed = samples[: n_win * step].reshape(n_win, step)
    rms = np.sqrt(np.mean(trimmed * trimmed, axis=1))
    minima: list[int] = []
    for i in range(1, len(rms) - 1):
        if rms[i] <= rms[i - 1] and rms[i] <= rms[i + 1]:
            t_ms = int(round((i + 0.5) * window_ms))
            if 0 < t_ms < total_ms:
                minima.append(t_ms)
    return minima


def _extract_ms(item: Mapping[str, Any], snake_key: str, camel_key: str) -> int | None:
    val = item.get(snake_key)
    if val is None:
        val = item.get(camel_key)
    if val is None:
        return None
    try:
        return int(round(float(val)))
    except (TypeError, ValueError):
        return None


def _normalize_timing_items(
    items: Sequence[Mapping[str, Any]] | None,
    *,
    text_keys: tuple[str, ...],
) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    if not items:
        return out
    for raw in items:
        if not isinstance(raw, Mapping):
            continue
        s_ms = _extract_ms(raw, "start_ms", "startMs")
        e_ms = _extract_ms(raw, "end_ms", "endMs")
        if s_ms is None or e_ms is None or e_ms < s_ms:
            continue
        txt = ""
        for k in text_keys:
            if raw.get(k):
                txt = str(raw[k]).strip()
                if txt:
                    break
        out.append({"start_ms": max(0, s_ms), "end_ms": max(0, e_ms), "text": txt})
    out.sort(key=lambda d: (d["start_ms"], d["end_ms"]))
    return out


def plan_talking_head_beats(
    total_ms: int,
    *,
    wav_path: Path | None = None,
    clauses: Sequence[Mapping[str, Any]] | None = None,
    words: Sequence[Mapping[str, Any]] | None = None,
    whisper_model: Path = DEFAULT_WHISPER_MODEL,
) -> list[dict[str, Any]]:
    """Plan coherent 4-10s talking-head beats covering [0, total_ms] with zero <4s tails."""
    total_ms_int = int(round(total_ms))
    if total_ms_int <= 0:
        raise ValueError(f"total_ms must be > 0, got {total_ms}")

    min_ms = MIN_WAN_BEAT_SEC * 1000
    max_ms = MAX_WAN_BEAT_SEC * 1000

    if total_ms_int <= max_ms:
        return [
            {
                "index": 0,
                "start_ms": 0,
                "end_ms": total_ms_int,
                "duration_ms": total_ms_int,
                "wan_duration_sec": max(MIN_WAN_BEAT_SEC, min(MAX_WAN_BEAT_SEC, int(math.ceil(total_ms_int / 1000.0)))),
            }
        ]

    if total_ms_int > MAX_TOTAL_DURATION_SEC * 1000:
        raise ValueError(
            f"total_ms={total_ms_int} exceeds MAX_TOTAL_DURATION_SEC={MAX_TOTAL_DURATION_SEC * 1000}ms"
        )

    norm_clauses = _normalize_timing_items(clauses, text_keys=("spoken_text", "spokenText", "text"))
    norm_words = _normalize_timing_items(words, text_keys=("word", "text"))

    if not norm_clauses and not norm_words and wav_path is not None and wav_path.is_file():
        with tempfile.TemporaryDirectory(prefix="aircheck_beat_whisper_") as w_tmp:
            inferred_words = _transcribe_words_whisper_ojf(wav_path, whisper_model, Path(w_tmp))
            norm_words = _normalize_timing_items(inferred_words, text_keys=("word", "text"))

    # Candidate cut positions -> lowest boundary penalty (lower is better).
    candidate_costs: dict[int, float] = {}

    def _add_candidate(ms: int, cost: float) -> None:
        if min_ms <= ms <= total_ms_int - min_ms:
            prev = candidate_costs.get(ms)
            if prev is None or cost < prev:
                candidate_costs[ms] = cost

    # 1. Clause boundaries (highest priority)
    for idx, cl in enumerate(norm_clauses):
        end_i = cl["end_ms"]
        if idx + 1 < len(norm_clauses):
            next_start = norm_clauses[idx + 1]["start_ms"]
            cut_ms = (end_i + next_start) // 2 if next_start >= end_i else end_i
        else:
            cut_ms = end_i
        _add_candidate(cut_ms, 0.0)

    # 2. Word boundaries (pauses >= 150ms preferred over tight word boundaries)
    for idx in range(len(norm_words) - 1):
        end_i = norm_words[idx]["end_ms"]
        next_start = norm_words[idx + 1]["start_ms"]
        gap_ms = max(0, next_start - end_i)
        cut_ms = (end_i + next_start) // 2 if next_start >= end_i else end_i
        _add_candidate(cut_ms, 1.0 if gap_ms >= 150 else 3.5)

    # 3. ffmpeg silencedetect gap midpoints
    if wav_path is not None and wav_path.is_file():
        for s_ms, e_ms in _detect_silences_ffmpeg(wav_path, total_ms_int):
            _add_candidate((s_ms + e_ms) // 2, 4.0)
        # 4. Local RMS energy minima
        for r_ms in _find_rms_minima_ms(wav_path, total_ms_int):
            _add_candidate(r_ms, 8.0)

    # 5. Dense feasible fallback grid + balanced target cuts to guarantee DP reachability
    n_target = max(2, int(math.ceil(total_ms_int / 8500.0)))
    target_beat_ms = float(total_ms_int) / float(n_target)
    for k in range(1, n_target):
        _add_candidate(int(round(k * target_beat_ms)), 18.0)
    for ms in range(min_ms, total_ms_int - min_ms + 1, 250):
        _add_candidate(ms, 20.0)

    # Prune near-duplicate candidates (within 40ms) keeping lowest cost so DP stays compact.
    sorted_raw = sorted(candidate_costs.items(), key=lambda kv: (kv[0], kv[1]))
    pruned: list[tuple[int, float]] = []
    for ms, cost in sorted_raw:
        if pruned and (ms - pruned[-1][0]) < 40:
            if cost < pruned[-1][1]:
                pruned[-1] = (ms, cost)
        else:
            pruned.append((ms, cost))

    nodes: list[tuple[int, float]] = [(0, 0.0), *pruned, (total_ms_int, 0.0)]
    n = len(nodes)
    dp = [math.inf] * n
    parent = [-1] * n
    dp[0] = 0.0

    for i in range(n - 1):
        if not math.isfinite(dp[i]):
            continue
        start_i = nodes[i][0]
        lo = start_i + min_ms
        hi = min(start_i + max_ms, total_ms_int - min_ms)
        for j in range(i + 1, n):
            pos_j, b_cost = nodes[j]
            dur = pos_j - start_i
            if dur < min_ms:
                continue
            if dur > max_ms:
                break
            if pos_j != total_ms_int and not (lo <= pos_j <= hi):
                continue
            cand_cost = dp[i] + b_cost + 0.0005 * abs(float(dur) - target_beat_ms)
            if cand_cost < dp[j]:
                dp[j] = cand_cost
                parent[j] = i

    if parent[n - 1] == -1:
        raise RuntimeError(f"Failed to plan feasible 4-10s beats for total_ms={total_ms_int}")

    boundaries: list[int] = []
    cur = n - 1
    while cur != -1:
        boundaries.append(nodes[cur][0])
        cur = parent[cur]
    boundaries.reverse()

    beats: list[dict[str, Any]] = []
    for idx in range(len(boundaries) - 1):
        s_ms = boundaries[idx]
        e_ms = boundaries[idx + 1]
        dur_ms = e_ms - s_ms
        beats.append(
            {
                "index": idx,
                "start_ms": s_ms,
                "end_ms": e_ms,
                "duration_ms": dur_ms,
                "wan_duration_sec": max(MIN_WAN_BEAT_SEC, min(MAX_WAN_BEAT_SEC, int(math.ceil(dur_ms / 1000.0)))),
            }
        )
    return beats


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


def _beat_transcript_slice(
    beat: Mapping[str, Any],
    *,
    total_beats: int,
    full_transcript: str,
    norm_words: list[dict[str, Any]],
    norm_clauses: list[dict[str, Any]],
    beat_wav: Path,
    whisper_model: Path,
    tmp_dir: Path,
) -> str:
    s_ms = int(beat["start_ms"])
    e_ms = int(beat["end_ms"])
    if norm_words:
        picked = [
            w["text"]
            for w in norm_words
            if w["text"] and s_ms <= ((w["start_ms"] + w["end_ms"]) // 2) < e_ms
        ]
        if picked:
            return " ".join(picked)
    if norm_clauses:
        picked_c = [
            c["text"]
            for c in norm_clauses
            if c["text"] and max(s_ms, c["start_ms"]) < min(e_ms, c["end_ms"])
        ]
        if picked_c:
            return " ".join(picked_c)
    if total_beats == 1 and full_transcript:
        return full_transcript

    wav16 = tmp_dir / f"beat_{beat['index']}_16k.wav"
    _run_subprocess(
        [
            "ffmpeg",
            "-y",
            "-i",
            str(beat_wav),
            "-ac",
            "1",
            "-ar",
            "16000",
            "-c:a",
            "pcm_s16le",
            str(wav16),
        ],
        timeout=FFMPEG_TIMEOUT_SEC,
        label=f"ffmpeg beat {beat['index']} 16k",
    )
    transcribed = transcribe_offline_whisper(wav16, whisper_model, tmp_dir / f"w_{beat['index']}")
    if transcribed:
        return transcribed
    return full_transcript if total_beats == 1 else ""


def generate_talking_head(
    voice_input: Path,
    avatar_path: Path,
    output_mp4: Path,
    *,
    transcript: str | None = None,
    resolution: str = "720P",
    ratio: str = "9:16",
    max_duration: int = 10,
    style_prompt: str | None = None,
    watermark: bool = False,
    dry_run: bool = False,
    replace_audio_with_input: bool = True,
    bl_bin: Path = DEFAULT_BL_BIN,
    whisper_model: Path = DEFAULT_WHISPER_MODEL,
    clauses: Sequence[Mapping[str, Any]] | None = None,
    words: Sequence[Mapping[str, Any]] | None = None,
    seed: int = 42,
    bl_timeout_sec: int = BL_BEAT_TIMEOUT_SEC,
) -> dict:
    norm_resolution = normalize_talking_head_resolution(resolution)
    norm_ratio = _validate_ratio_and_duration(ratio, max_duration)
    target_w, target_h = _RESOLUTION_DIMENSIONS[(norm_resolution, norm_ratio)]

    if not voice_input.exists():
        raise FileNotFoundError(f"Voice input not found: {voice_input}")
    if not avatar_path.exists():
        raise FileNotFoundError(f"Avatar image not found: {avatar_path}")
    if not bl_bin.exists():
        found = shutil.which("bl")
        if not found:
            raise RuntimeError("Bailian CLI `bl` not found on PATH or at /Users/mac/.local/bin/bl")
        bl_bin = Path(found)

    output_mp4.parent.mkdir(parents=True, exist_ok=True)
    raw_video_mp4 = output_mp4.with_name(f"{output_mp4.stem}_raw_wan.mp4")

    mime_img = "image/png" if avatar_path.suffix.lower() == ".png" else "image/jpeg"
    img_b64 = base64.b64encode(avatar_path.read_bytes()).decode("ascii")
    scene = style_prompt or (
        "Image 1 speaks directly to the camera in a natural, conversational tone with crisp, "
        "accurate lip-sync matching Audio 1, natural blinking, subtle head movement, and "
        "clean studio lighting in a single unbroken static shot."
    )

    norm_words = _normalize_timing_items(words, text_keys=("word", "text"))
    norm_clauses = _normalize_timing_items(clauses, text_keys=("spoken_text", "spokenText", "text"))
    full_transcript = (transcript or "").strip()

    with tempfile.TemporaryDirectory(prefix="aircheck_head_") as tmp_str:
        tmp_dir = Path(tmp_str)
        master_wav = tmp_dir / "master_voice.wav"
        _, total_ms = _prepare_canonical_master_wav(
            voice_input,
            master_wav,
            max_duration_sec=max_duration,
        )
        beats = plan_talking_head_beats(
            total_ms,
            wav_path=master_wav,
            clauses=clauses,
            words=words,
            whisper_model=whisper_model,
        )

        # Slice per-beat WAV + MP3 and compute per-beat transcript
        beat_jobs: list[dict[str, Any]] = []
        for b in beats:
            idx = int(b["index"])
            s_sec = b["start_ms"] / 1000.0
            d_sec = b["duration_ms"] / 1000.0
            beat_wav = tmp_dir / f"beat_{idx}.wav"
            beat_mp3 = tmp_dir / f"beat_{idx}.mp3"
            beat_raw_mp4 = raw_video_mp4 if len(beats) == 1 else (tmp_dir / f"beat_{idx}_raw_wan.mp4")
            (tmp_dir / f"w_{idx}").mkdir(parents=True, exist_ok=True)

            if len(beats) == 1:
                shutil.copy2(master_wav, beat_wav)
            else:
                _run_subprocess(
                    [
                        "ffmpeg",
                        "-y",
                        "-i",
                        str(master_wav),
                        "-ss",
                        f"{s_sec:.6f}",
                        "-t",
                        f"{d_sec:.6f}",
                        "-ac",
                        "1",
                        "-ar",
                        "44100",
                        "-c:a",
                        "pcm_s16le",
                        str(beat_wav),
                    ],
                    timeout=FFMPEG_TIMEOUT_SEC,
                    label=f"ffmpeg slice beat {idx} wav",
                )

            _run_subprocess(
                [
                    "ffmpeg",
                    "-y",
                    "-i",
                    str(beat_wav),
                    "-ac",
                    "1",
                    "-ar",
                    "44100",
                    "-b:a",
                    "128k",
                    str(beat_mp3),
                ],
                timeout=FFMPEG_TIMEOUT_SEC,
                label=f"ffmpeg encode beat {idx} mp3",
            )

            beat_text = _beat_transcript_slice(
                b,
                total_beats=len(beats),
                full_transcript=full_transcript,
                norm_words=norm_words,
                norm_clauses=norm_clauses,
                beat_wav=beat_wav,
                whisper_model=whisper_model,
                tmp_dir=tmp_dir,
            )
            b["transcript"] = beat_text
            beat_jobs.append(
                {
                    "beat": b,
                    "beat_mp3": beat_mp3,
                    "beat_raw_mp4": beat_raw_mp4,
                    "beat_text": beat_text,
                }
            )

        def _invoke_bl_beat(item: dict[str, Any]) -> str:
            b = item["beat"]
            mp3_b64 = base64.b64encode(item["beat_mp3"].read_bytes()).decode("ascii")
            beat_text = item["beat_text"]
            prompt = f'{scene} Spoken dialogue: "{beat_text}"' if beat_text else scene
            cmd = [
                str(bl_bin),
                "video",
                "ref",
                "--model",
                "wan3.0-video",
                "--image",
                f"data:{mime_img};base64,{img_b64}",
                "--image-voice",
                f"data:audio/mpeg;base64,{mp3_b64}",
                "--prompt",
                prompt,
                "--resolution",
                norm_resolution,
                "--ratio",
                norm_ratio,
                "--duration",
                str(b["wan_duration_sec"]),
                "--seed",
                str(seed),
                "--watermark",
                "true" if watermark else "false",
                "--output",
                "json",
            ]
            if dry_run:
                cmd.append("--dry-run")
            else:
                cmd.extend(["--download", str(item["beat_raw_mp4"])])
            proc = _run_subprocess(cmd, timeout=bl_timeout_sec, label=f"bl video ref beat {b['index']}")
            return proc.stdout.strip()

        max_workers = max(1, min(4, len(beat_jobs)))
        with ThreadPoolExecutor(max_workers=max_workers) as pool:
            bl_stdouts = list(pool.map(_invoke_bl_beat, beat_jobs))

        joined_transcript = full_transcript or " ".join(
            j["beat_text"] for j in beat_jobs if j["beat_text"]
        ).strip()

        total_dur_sec = total_ms / 1000.0
        result: dict[str, Any] = {
            "voice_input": str(voice_input),
            "avatar_path": str(avatar_path),
            "transcript": joined_transcript,
            "duration_sec": round(total_dur_sec, 3),
            "duration_ms": total_ms,
            "resolution": norm_resolution,
            "ratio": norm_ratio,
            "beat_count": len(beats),
            "beats": beats,
            "dry_run": dry_run,
            "bl_stdout": "\n".join(s for s in bl_stdouts if s),
        }

        if not dry_run:
            beat_raw_paths: list[Path] = []
            for item in beat_jobs:
                p: Path = item["beat_raw_mp4"]
                if not p.exists() or p.stat().st_size == 0:
                    raise RuntimeError(f"Expected downloaded video at {p}, but file is missing/empty.")
                beat_raw_paths.append(p)

            if len(beat_raw_paths) == 1:
                shutil.copy2(beat_raw_paths[0], output_mp4)
            else:
                shutil.copy2(beat_raw_paths[0], raw_video_mp4)
                # Build stitched Wan video at output_mp4 for compatibility
                has_audio_flags: list[bool] = []
                for p in beat_raw_paths:
                    aproc = _run_subprocess(
                        [
                            "ffprobe",
                            "-v",
                            "error",
                            "-select_streams",
                            "a:0",
                            "-show_entries",
                            "stream=codec_type",
                            "-of",
                            "default=noprint_wrappers=1:nokey=1",
                            str(p),
                        ],
                        timeout=FFPROBE_TIMEOUT_SEC,
                        label="ffprobe beat audio check",
                    )
                    has_audio_flags.append(bool((aproc.stdout or "").strip()))

                wan_cmd = ["ffmpeg", "-y"]
                for p in beat_raw_paths:
                    wan_cmd.extend(["-i", str(p)])
                fc_parts: list[str] = []
                cat_inputs: list[str] = []
                for idx, item in enumerate(beat_jobs):
                    dur_s = item["beat"]["duration_ms"] / 1000.0
                    fc_parts.append(
                        f"[{idx}:v]setpts=PTS-STARTPTS,scale={target_w}:{target_h}:force_original_aspect_ratio=increase,"
                        f"crop={target_w}:{target_h},fps=30,setsar=1,"
                        f"tpad=stop_mode=clone:stop_duration=10,trim=duration={dur_s:.6f},setpts=PTS-STARTPTS[wv{idx}]"
                    )
                    if has_audio_flags[idx]:
                        fc_parts.append(
                            f"[{idx}:a]aresample=44100,aformat=channel_layouts=mono,"
                            f"apad,atrim=duration={dur_s:.6f},asetpts=PTS-STARTPTS[wa{idx}]"
                        )
                    else:
                        fc_parts.append(
                            f"anullsrc=r=44100:cl=mono,atrim=duration={dur_s:.6f},asetpts=PTS-STARTPTS[wa{idx}]"
                        )
                    cat_inputs.append(f"[wv{idx}][wa{idx}]")
                fc_parts.append(f"{''.join(cat_inputs)}concat=n={len(beat_jobs)}:v=1:a=1[wvo][wao]")
                wan_cmd.extend(
                    [
                        "-filter_complex",
                        ";".join(fc_parts),
                        "-map",
                        "[wvo]",
                        "-map",
                        "[wao]",
                        "-c:v",
                        "libx264",
                        "-preset",
                        "fast",
                        "-crf",
                        "18",
                        "-pix_fmt",
                        "yuv420p",
                        "-c:a",
                        "aac",
                        "-b:a",
                        "192k",
                        "-ar",
                        "44100",
                        "-ac",
                        "1",
                        "-t",
                        f"{total_dur_sec:.6f}",
                        "-movflags",
                        "+faststart",
                        str(output_mp4),
                    ]
                )
                _run_subprocess(wan_cmd, timeout=FFMPEG_TIMEOUT_SEC, label="ffmpeg concat wan beats")

            result["raw_wan_mp4"] = str(raw_video_mp4)
            result["output_mp4"] = str(output_mp4)
            result["output_bytes"] = output_mp4.stat().st_size

            if replace_audio_with_input:
                orig_audio_mp4 = output_mp4.with_name(f"{output_mp4.stem}_exact_input_voice.mp4")
                mux_cmd = ["ffmpeg", "-y"]
                for p in beat_raw_paths:
                    mux_cmd.extend(["-i", str(p)])
                mux_cmd.extend(["-i", str(master_wav)])

                filter_parts: list[str] = []
                v_labels: list[str] = []
                for idx, item in enumerate(beat_jobs):
                    dur_s = item["beat"]["duration_ms"] / 1000.0
                    filter_parts.append(
                        f"[{idx}:v]setpts=PTS-STARTPTS,scale={target_w}:{target_h}:force_original_aspect_ratio=increase,"
                        f"crop={target_w}:{target_h},fps=30,setsar=1,"
                        f"tpad=stop_mode=clone:stop_duration=10,trim=duration={dur_s:.6f},setpts=PTS-STARTPTS[v{idx}]"
                    )
                    v_labels.append(f"[v{idx}]")

                if len(v_labels) == 1:
                    video_out_label = "[v0]"
                else:
                    filter_parts.append(f"{''.join(v_labels)}concat=n={len(v_labels)}:v=1:a=0[vcat]")
                    video_out_label = "[vcat]"

                wav_input_idx = len(beat_raw_paths)
                mux_cmd.extend(
                    [
                        "-filter_complex",
                        ";".join(filter_parts),
                        "-map",
                        video_out_label,
                        "-map",
                        f"{wav_input_idx}:a:0",
                        "-c:v",
                        "libx264",
                        "-preset",
                        "fast",
                        "-crf",
                        "18",
                        "-pix_fmt",
                        "yuv420p",
                        "-c:a",
                        "aac",
                        "-b:a",
                        "192k",
                        "-ar",
                        "44100",
                        "-ac",
                        "1",
                        "-t",
                        f"{total_dur_sec:.6f}",
                        "-movflags",
                        "+faststart",
                        str(orig_audio_mp4),
                    ]
                )
                _run_subprocess(
                    mux_cmd,
                    timeout=FFMPEG_TIMEOUT_SEC,
                    label="ffmpeg exact master wav mux",
                )
                qa_target = orig_audio_mp4
                result["exact_input_voice_mp4"] = str(orig_audio_mp4)
            else:
                qa_target = output_mp4

            qa = verify_av_sync_ffprobe(
                qa_target,
                expected_audio_ms=total_ms,
                expected_width=target_w if replace_audio_with_input or len(beats) > 1 else None,
                expected_height=target_h if replace_audio_with_input or len(beats) > 1 else None,
            )
            result["ffprobe_qa"] = qa
            result["av_sync_drift_ms"] = qa["av_sync_drift_ms"]
            result["video_duration_ms"] = qa["video_duration_ms"]
            result["audio_duration_ms"] = qa["audio_duration_ms"]

        return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate a talking-head MP4 from any voice audio + portrait.")
    parser.add_argument("--voice-wav", required=True, type=Path, help="Input voice audio (.wav/.mp3/.m4a/.pcm)")
    parser.add_argument("--avatar", type=Path, default=DEFAULT_AVATAR, help="Portrait image path")
    parser.add_argument("--output", required=True, type=Path, help="Output talking-head MP4 path")
    parser.add_argument("--transcript", default=None, help="Optional spoken transcript (auto-transcribed via whisper-cli if omitted)")
    parser.add_argument("--resolution", default="720P", choices=["480P", "720P", "1080P"])
    parser.add_argument("--ratio", default="9:16", choices=["9:16", "16:9", "1:1"])
    parser.add_argument("--max-duration", type=int, default=10, help="Max clip duration in seconds (2..75, default 10)")
    parser.add_argument("--style-prompt", default=None, help="Custom scene/camera prompt")
    parser.add_argument("--clauses-json", type=Path, default=None, help="Optional JSON file of timed clauses")
    parser.add_argument("--words-json", type=Path, default=None, help="Optional JSON file of timed words")
    parser.add_argument("--seed", type=int, default=42, help="Deterministic seed across multi-beat clips")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    clauses = None
    if args.clauses_json is not None:
        clauses = json.loads(args.clauses_json.read_text(encoding="utf-8"))
    words = None
    if args.words_json is not None:
        words = json.loads(args.words_json.read_text(encoding="utf-8"))

    res = generate_talking_head(
        voice_input=args.voice_wav,
        avatar_path=args.avatar,
        output_mp4=args.output,
        transcript=args.transcript,
        resolution=normalize_talking_head_resolution(args.resolution),
        ratio=args.ratio,
        max_duration=args.max_duration,
        style_prompt=args.style_prompt,
        dry_run=args.dry_run,
        clauses=clauses,
        words=words,
        seed=args.seed,
    )
    print(json.dumps(res, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
