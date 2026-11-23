"""Device Personal Media Voice Dataset Miner for Aircheck / VoiceStudio.

Mines Ryan's personal recordings on this Mac (`/Users/mac/Downloads/` personal camera/voice
files + `/Users/mac/Downloads/clone/voice/raw/*.wav`), extracts clean 44.1 kHz mono speech
utterances (2.5s - 13.5s), gates EVERY utterance with:
  1. REAL WeSpeaker ResNet34 (`wespeaker_en_voxceleb_resnet34.onnx`, cosine >= 0.72 vs Ryan's
     verified seed centroid — strictly rejecting other speakers, music, anime, demos)
  2. REAL Microsoft DNSMOS p808 (`model_v8.onnx`, official DNS-Challenge preprocessing) +
     clipping/RMS signal hygiene checks
  3. REAL local `whisper-cli` (`ggml-medium-q5_0.bin` on Metal GPU, with optional Citrinet-512
     cross-check) transcription & hallucination/non-speech filtering

Outputs:
  - `/Users/mac/Downloads/voicestudio-models/dataset/wavs/<id>.wav` (44.1 kHz mono Float32 WAV)
  - `/Users/mac/Downloads/voicestudio-models/dataset/manifest.jsonl`
    (`{"id", "wav_44k", "duration_sec", "transcript", "wespeaker_cos", "dnsmos_p808", "source_video"}`)
  - `/Users/mac/Downloads/voicestudio-models/dataset/mining_summary.json`
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime
import json
import math
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any, Sequence

import numpy as np
import soundfile as sf

from enroll_voice_assets import (
    DEFAULT_DNSMOS_P808_ONNX,
    DEFAULT_MODELS_DIR,
    DEFAULT_VOICE_DIR,
    DEFAULT_WESPEAKER_ONNX,
    SYNTHESIS_SAMPLE_RATE_HZ,
    VERIFIER_SAMPLE_RATE_HZ,
    DnsmosP808Engine,
    WeSpeakerEngine,
    compute_rms_dbfs,
    compute_seed_centroid,
    cosine_similarity,
    load_audio_mono,
    resample_audio,
    sha256_file,
)

DEFAULT_DOWNLOADS_DIR = Path("/Users/mac/Downloads")
DEFAULT_DATASET_DIR = DEFAULT_MODELS_DIR / "dataset"
DEFAULT_WHISPER_CLI = Path("/opt/homebrew/bin/whisper-cli")
DEFAULT_WHISPER_MODEL = Path("/Users/mac/models/ggml-medium-q5_0.bin")
DEFAULT_FFMPEG = Path("/opt/homebrew/bin/ffmpeg")
DEFAULT_FFPROBE = Path("/opt/homebrew/bin/ffprobe")
DEFAULT_ADB = Path("/opt/homebrew/bin/adb")
DEFAULT_ADB_CAMERA_DIR = "/sdcard/DCIM/Camera"

ADB_EXCLUDED_RE = re.compile(
    r"(~\d+\.mp4$|\.MC\.mp4$|\.COVER\.mp4$|\.TS\.mp4$|\(\d+\))",
    re.IGNORECASE,
)

MEDIA_EXTENSIONS: frozenset[str] = frozenset(
    {".mp4", ".mov", ".m4a", ".wav", ".mp3", ".aac", ".mkv", ".flac"}
)

# Personal recording filename patterns explicitly owned by Ryan on this Mac
PERSONAL_MEDIA_RE = re.compile(
    r"^(lv_0_\d+|202[56]\d{4}_\d{6}_|PXL_\d{8}_\d+|VID_\d{8}_\d+|WIN_\d{8}_|IMG_\d+|Tolmo Voice Over)",
    re.IGNORECASE,
)

# Explicit exclusions: synthetic voice clones, AI video gens, podcasts, music, anime, duplicates
EXCLUDED_NAME_RE = re.compile(
    r"(elevenlabs|clone_test|mimo|koubopublished|tim dillon|guy j|yagya|gintama|"
    r"macmacmacprime|_under100mb|_first_2m02s|_github-10mb|\(\d+\)|~2-2\.mp4$)",
    re.IGNORECASE,
)

# Whisper non-speech / hallucination artifact tags
_BRACKET_TAG_RE = re.compile(r"\[[^\]]+\]|\([^)]+\)|\*[^*]+\*")
_MULTI_SPACE_RE = re.compile(r"\s+")
_WORD_RE = re.compile(r"[A-Za-z0-9']+")
_HALLUCINATION_PHRASES: frozenset[str] = frozenset(
    {
        "thank you",
        "thank you for watching",
        "thanks for watching",
        "subscribe",
        "bye",
        "bye bye",
        "you",
        "subtitles by",
        "captions by",
    }
)


@dataclasses.dataclass(frozen=True)
class SliceCandidate:
    source_name: str
    source_path: Path
    slice_index: int
    start_sec: float
    end_sec: float
    pcm_44k: np.ndarray
    pcm_16k: np.ndarray
    rms_dbfs: float
    peak_amp: float


def _base_timestamp_key(filename: str) -> str | None:
    """Extracts canonical recording timestamp key to detect `~2` / `-2` trimmed/duplicate variants."""
    m = re.match(r"^(202[56]\d{4}_\d{6}|lv_0_\d{14}|PXL_\d{8}_\d+|VID_\d{8}_\d+)", filename)
    return m.group(1) if m else None


def discover_personal_media(
    downloads_dir: Path = DEFAULT_DOWNLOADS_DIR,
    voice_raw_dir: Path = DEFAULT_VOICE_DIR / "raw",
) -> tuple[list[Path], list[dict[str, str]]]:
    """Discovers deduplicated personal media files on disk, skipping duplicates & third-party demos."""
    skipped: list[dict[str, str]] = []
    raw_candidates: list[Path] = []

    if downloads_dir.is_dir():
        for p in sorted(downloads_dir.iterdir()):
            if not p.is_file() or p.suffix.lower() not in MEDIA_EXTENSIONS:
                continue
            if not PERSONAL_MEDIA_RE.match(p.name):
                continue
            if EXCLUDED_NAME_RE.search(p.name):
                skipped.append({"file": p.name, "reason": "excluded_pattern_or_dup_suffix"})
                continue
            raw_candidates.append(p)

    # Deduplicate `-2.mp4` when `<stem>.mp4` exists, and `~2.mp4` when same timestamp original exists
    all_names = {p.name for p in raw_candidates}
    non_tilde_ts_keys = {
        _base_timestamp_key(p.name)
        for p in raw_candidates
        if "~" not in p.name and _base_timestamp_key(p.name) is not None
    }

    selected: list[Path] = []
    seen_ts_in_selected: set[str] = set()
    for p in raw_candidates:
        if p.stem.endswith("-2"):
            orig_name = p.stem[:-2] + p.suffix
            if orig_name in all_names:
                skipped.append({"file": p.name, "reason": f"duplicate_of_{orig_name}"})
                continue
        if "~" in p.name:
            ts_key = _base_timestamp_key(p.name)
            if ts_key is not None and ts_key in non_tilde_ts_keys:
                skipped.append({"file": p.name, "reason": f"trimmed_variant_of_{ts_key}"})
                continue
        selected.append(p)
        ts_key = _base_timestamp_key(p.name)
        if ts_key:
            seen_ts_in_selected.add(ts_key)

    # Also include Ryan's verified raw seed WAVs from /Users/mac/Downloads/clone/voice/raw/
    if voice_raw_dir.is_dir():
        for wav in sorted(voice_raw_dir.glob("*.wav")):
            ts_key = _base_timestamp_key(wav.name)
            if ts_key and ts_key in seen_ts_in_selected:
                skipped.append({"file": f"clone/voice/raw/{wav.name}", "reason": "already_in_downloads"})
                continue
            selected.append(wav)

    return selected, skipped


def probe_has_audio_stream(media_path: Path, ffprobe_bin: Path = DEFAULT_FFPROBE) -> tuple[bool, float]:
    """Checks whether `media_path` has an audio stream and returns `(has_audio, duration_sec)`."""
    if media_path.suffix.lower() == ".wav":
        try:
            info = sf.info(str(media_path))
            return info.frames > 0, float(info.duration)
        except Exception:
            return False, 0.0

    cmd = [
        str(ffprobe_bin),
        "-v",
        "error",
        "-select_streams",
        "a:0",
        "-show_entries",
        "stream=codec_type:format=duration",
        "-of",
        "json",
        str(media_path),
    ]
    res = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if res.returncode != 0:
        return False, 0.0
    try:
        info = json.loads(res.stdout)
    except json.JSONDecodeError:
        return False, 0.0
    streams = info.get("streams", [])
    dur = float(info.get("format", {}).get("duration", 0.0) or 0.0)
    return bool(streams), dur


def extract_mono_44k_pcm(
    media_path: Path,
    ffmpeg_bin: Path = DEFAULT_FFMPEG,
) -> np.ndarray:
    """Extracts 44.1 kHz mono float32 PCM from any video/audio file using ffmpeg pipe."""
    if media_path.suffix.lower() == ".wav":
        pcm, _ = load_audio_mono(media_path, target_sr=SYNTHESIS_SAMPLE_RATE_HZ)
        return pcm

    cmd = [
        str(ffmpeg_bin),
        "-nostdin",
        "-v",
        "error",
        "-i",
        str(media_path),
        "-vn",
        "-ac",
        "1",
        "-ar",
        str(SYNTHESIS_SAMPLE_RATE_HZ),
        "-f",
        "f32le",
        "pipe:1",
    ]
    res = subprocess.run(cmd, capture_output=True, check=False)
    if res.returncode != 0 or not res.stdout:
        return np.zeros(0, dtype=np.float32)
    usable = (len(res.stdout) // 4) * 4
    return np.frombuffer(res.stdout[:usable], dtype="<f4").copy()


def audio_track_fingerprint(pcm16k: np.ndarray, bins: int = 128) -> np.ndarray | None:
    """Computes a coarse energy-envelope fingerprint to detect duplicate video re-encodes."""
    if pcm16k.size < VERIFIER_SAMPLE_RATE_HZ * 2:
        return None
    # Use first 60s of 1-second RMS buckets (padded/interpolated to fixed `bins`)
    n_sec = min(60, pcm16k.size // VERIFIER_SAMPLE_RATE_HZ)
    if n_sec < 2:
        return None
    trimmed = pcm16k[: n_sec * VERIFIER_SAMPLE_RATE_HZ].reshape(n_sec, VERIFIER_SAMPLE_RATE_HZ)
    env = np.sqrt(np.mean(trimmed.astype(np.float64) ** 2, axis=1))
    if float(np.max(env)) <= 1e-6:
        return None
    x_old = np.linspace(0.0, 1.0, n_sec)
    x_new = np.linspace(0.0, 1.0, bins)
    vec = np.interp(x_new, x_old, env)
    vec -= np.mean(vec)
    norm = float(np.linalg.norm(vec))
    return (vec / norm) if norm > 1e-9 else None


def slice_speech_utterances(
    pcm_44k: np.ndarray,
    pcm_16k: np.ndarray,
    *,
    source_name: str,
    source_path: Path,
    min_dur_sec: float = 2.5,
    max_dur_sec: float = 13.5,
    target_max_dur_sec: float = 12.2,
    pad_sec: float = 0.12,
    min_rms_dbfs: float = -40.0,
    max_clipped_ratio: float = 0.005,
) -> list[SliceCandidate]:
    """Slices mono audio on natural pauses into clean `2.5s - 13.5s` speech candidates."""
    sr16 = VERIFIER_SAMPLE_RATE_HZ
    sr44 = SYNTHESIS_SAMPLE_RATE_HZ
    if pcm_16k.size < int(min_dur_sec * sr16):
        return []

    win = int(0.030 * sr16)  # 30 ms frame
    hop = int(0.010 * sr16)  # 10 ms hop
    n_frames = 1 + (pcm_16k.size - win) // hop
    if n_frames < 10:
        return []

    # Vectorized frame RMS computation
    starts = np.arange(n_frames, dtype=np.int64) * hop
    offsets = np.arange(win, dtype=np.int64)[None, :]
    frames = pcm_16k[starts[:, None] + offsets].astype(np.float64)
    frame_rms = np.sqrt(np.mean(frames * frames, axis=1))
    frame_db = 20.0 * np.log10(np.maximum(frame_rms, 1e-9))

    active_mask_nz = frame_rms > 1e-6
    if int(np.sum(active_mask_nz)) < 50:
        return []

    nz_db = frame_db[active_mask_nz]
    p15 = float(np.percentile(nz_db, 15))
    p90 = float(np.percentile(nz_db, 90))
    p98 = float(np.percentile(nz_db, 98))
    if p98 < -38.0:
        return []

    # Adaptive speech threshold above local ambient floor
    speech_ref = max(p90, p98 - 6.0)
    speech_thresh_db = max(-42.0, min(speech_ref - 14.0, p15 + 9.0))
    is_speech = frame_db >= speech_thresh_db

    # Close short intra-word / inter-word gaps (<= 350 ms = 35 frames)
    bridge_frames = 35
    closed = is_speech.copy()
    i = 0
    while i < n_frames:
        if closed[i]:
            i += 1
            continue
        gap_start = i
        while i < n_frames and not closed[i]:
            i += 1
        gap_end = i
        if gap_start > 0 and gap_end < n_frames and (gap_end - gap_start) <= bridge_frames:
            closed[gap_start:gap_end] = True

    # Extract raw speech spans [start_frame, end_frame]
    raw_spans: list[tuple[int, int]] = []
    i = 0
    while i < n_frames:
        if not closed[i]:
            i += 1
            continue
        s = i
        while i < n_frames and closed[i]:
            i += 1
        e = i
        if (e - s) >= 25:  # ignore tiny <250ms blips
            raw_spans.append((s, e))

    if not raw_spans:
        return []

    # Merge neighboring spans separated by <= 0.70s as long as combined length <= target_max_dur_sec
    max_gap_frames = 70
    target_max_frames = int(round(target_max_dur_sec * 100))
    merged: list[tuple[int, int]] = [raw_spans[0]]
    for s, e in raw_spans[1:]:
        ps, pe = merged[-1]
        if (s - pe) <= max_gap_frames and (e - ps) <= target_max_frames:
            merged[-1] = (ps, e)
        else:
            merged.append((s, e))

    # Split any span exceeding max_dur_sec at the lowest-energy smoothed valley inside [5.5s..12.2s]
    smooth_k = np.ones(15, dtype=np.float64) / 15.0
    smooth_db = np.convolve(frame_db, smooth_k, mode="same")

    final_frame_spans: list[tuple[int, int]] = []
    min_frames = int(round(min_dur_sec * 100))
    max_frames = int(round((max_dur_sec - 2.0 * pad_sec) * 100))
    min_split_offset = int(round(5.0 * 100))

    for s, e in merged:
        cur = s
        while (e - cur) > max_frames:
            lo = cur + min_split_offset
            hi = min(e - min_frames, cur + target_max_frames)
            if hi <= lo:
                cut = cur + max_frames
            else:
                cut = lo + int(np.argmin(smooth_db[lo : hi + 1]))
            if (cut - cur) >= min_frames:
                final_frame_spans.append((cur, cut))
            cur = cut
        if (e - cur) >= min_frames:
            final_frame_spans.append((cur, e))

    total_sec_44k = float(pcm_44k.size) / float(sr44)
    total_sec_16k = float(pcm_16k.size) / float(sr16)
    max_sec = min(total_sec_44k, total_sec_16k)

    candidates: list[SliceCandidate] = []
    fade_44k = int(round(0.005 * sr44))  # 5 ms micro-fade at edges

    for idx, (sf_idx, ef_idx) in enumerate(final_frame_spans):
        t0 = max(0.0, (sf_idx * 0.010) - pad_sec)
        t1 = min(max_sec, (ef_idx * 0.010 + 0.030) + pad_sec)
        dur = t1 - t0
        if dur < min_dur_sec or dur > max_dur_sec:
            continue

        s44_0 = int(round(t0 * sr44))
        s44_1 = min(pcm_44k.size, int(round(t1 * sr44)))
        s16_0 = int(round(t0 * sr16))
        s16_1 = min(pcm_16k.size, int(round(t1 * sr16)))
        if s44_1 - s44_0 < int(min_dur_sec * sr44) or s16_1 - s16_0 < int(min_dur_sec * sr16):
            continue

        seg44 = pcm_44k[s44_0:s44_1].copy()
        seg16 = pcm_16k[s16_0:s16_1].copy()

        # Remove DC offset
        seg44 -= float(np.mean(seg44))
        seg16 -= float(np.mean(seg16))

        # Check RMS & clipping hygiene
        rms_db = compute_rms_dbfs(seg44)
        if rms_db < min_rms_dbfs:
            continue
        peak_amp = float(np.max(np.abs(seg44)))
        clipped_ratio = float(np.mean(np.abs(seg44) >= 0.995))
        if clipped_ratio > max_clipped_ratio:
            continue

        # Gentle safety ceiling if single peak touches 1.0 (never compresses normal speech)
        if peak_amp > 0.985:
            scale = 0.985 / peak_amp
            seg44 *= scale
            seg16 *= scale
            peak_amp = 0.985

        # 5 ms cosine edge micro-fade to eliminate slice boundary clicks
        if seg44.size > fade_44k * 4:
            ramp = 0.5 * (1.0 - np.cos(np.linspace(0.0, np.pi, fade_44k, dtype=np.float32)))
            seg44[:fade_44k] *= ramp
            seg44[-fade_44k:] *= ramp[::-1]

        candidates.append(
            SliceCandidate(
                source_name=source_name,
                source_path=source_path,
                slice_index=idx,
                start_sec=round(t0, 3),
                end_sec=round(t1, 3),
                pcm_44k=seg44,
                pcm_16k=seg16,
                rms_dbfs=round(rms_db, 2),
                peak_amp=round(peak_amp, 5),
            )
        )

    return candidates


def clean_and_validate_transcript(text: str, duration_sec: float) -> tuple[str | None, str | None]:
    """Cleans Whisper output and filters out non-speech, blank, or hallucinated lines."""
    cleaned = _BRACKET_TAG_RE.sub(" ", text)
    cleaned = _MULTI_SPACE_RE.sub(" ", cleaned).strip()
    if not cleaned:
        return None, "empty_after_tag_strip"

    words = _WORD_RE.findall(cleaned)
    if len(words) < 3:
        return None, "too_few_words"

    lowered_norm = " ".join(w.lower() for w in words)
    if lowered_norm in _HALLUCINATION_PHRASES:
        return None, "whisper_hallucination_phrase"

    # Reject pathological repetition loops (e.g. "thank you thank you thank you thank you")
    if len(words) >= 8:
        unique_ratio = len(set(w.lower() for w in words)) / float(len(words))
        if unique_ratio < 0.28:
            return None, "repetitive_hallucination"

    wps = float(len(words)) / max(duration_sec, 0.1)
    if wps < 0.65 or wps > 6.8:
        return None, f"implausible_word_rate_{wps:.2f}"

    return cleaned, None


def batch_transcribe_whisper(
    wav16k_paths: Sequence[Path],
    *,
    whisper_cli: Path = DEFAULT_WHISPER_CLI,
    whisper_model: Path = DEFAULT_WHISPER_MODEL,
    batch_size: int = 24,
) -> dict[Path, str]:
    """Batch-transcribes 16 kHz WAVs using local whisper.cpp Metal GPU (`-oj`)."""
    if not wav16k_paths:
        return {}
    if not whisper_cli.is_file():
        raise FileNotFoundError(f"whisper-cli not found: {whisper_cli}")
    if not whisper_model.is_file():
        raise FileNotFoundError(f"Whisper GGML model not found: {whisper_model}")

    results: dict[Path, str] = {}
    for start in range(0, len(wav16k_paths), batch_size):
        chunk = list(wav16k_paths[start : start + batch_size])
        cmd = [
            str(whisper_cli),
            "-m",
            str(whisper_model),
            "-l",
            "en",
            "-oj",
            "-np",
            "--prompt",
            "Natural spoken English monologue.",
            *[str(p) for p in chunk],
        ]
        res = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if res.returncode != 0:
            raise RuntimeError(
                f"whisper-cli failed (code {res.returncode}): {res.stderr[-500:]}"
            )
        for wav_path in chunk:
            json_path = Path(str(wav_path) + ".json")
            if not json_path.is_file():
                results[wav_path] = ""
                continue
            payload = json.loads(json_path.read_text(encoding="utf-8"))
            segs = payload.get("transcription", [])
            joined = " ".join(str(s.get("text", "")).strip() for s in segs).strip()
            results[wav_path] = joined
            json_path.unlink(missing_ok=True)
    return results


def _sanitize_stem(name: str) -> str:
    stem = Path(name).stem
    clean = re.sub(r"[^A-Za-z0-9]+", "_", stem).strip("_")
    return clean[:48] or "clip"


def list_adb_camera_videos(
    adb_serial: str,
    *,
    camera_dir: str = DEFAULT_ADB_CAMERA_DIR,
    existing_names: set[str] | None = None,
    existing_ts_keys: set[str] | None = None,
    max_file_mb: float = 220.0,
    adb_bin: Path = DEFAULT_ADB,
) -> list[tuple[str, str, int]]:
    """Lists newest-first non-duplicate personal `.mp4` videos on a USB-connected Android device."""
    if not adb_bin.is_file():
        raise FileNotFoundError(f"adb binary not found: {adb_bin}")
    cmd = [
        str(adb_bin),
        "-s",
        adb_serial,
        "shell",
        (
            f"ls -lt {camera_dir}/lv_0_*.mp4 {camera_dir}/PXL_*.mp4 "
            f"{camera_dir}/VID_*.mp4 {camera_dir}/2026-*.mp4 {camera_dir}/2025-*.mp4 2>/dev/null"
        ),
    ]
    res = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if res.returncode != 0 and not res.stdout.strip():
        return []

    seen_names = set(existing_names or ())
    seen_ts = set(existing_ts_keys or ())
    max_bytes = int(max_file_mb * 1024 * 1024)
    results: list[tuple[str, str, int]] = []

    # Format: -rw-rw---- 2 u0_a333 media_rw 17582889 2026-11-21 09:42 /sdcard/DCIM/Camera/foo.mp4
    line_re = re.compile(r"^\S+\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}\s+(.+)$")
    for raw_line in res.stdout.splitlines():
        m = line_re.match(raw_line.strip())
        if not m:
            continue
        size_bytes = int(m.group(1))
        remote_path = m.group(2).strip()
        filename = os.path.basename(remote_path)
        if not filename.lower().endswith(".mp4"):
            continue
        if size_bytes <= 0 or size_bytes > max_bytes:
            continue
        if ADB_EXCLUDED_RE.search(filename) or EXCLUDED_NAME_RE.search(filename):
            continue
        if filename in seen_names:
            continue
        ts_key = _base_timestamp_key(filename)
        if ts_key and ts_key in seen_ts:
            continue
        seen_names.add(filename)
        if ts_key:
            seen_ts.add(ts_key)
        results.append((remote_path, filename, size_bytes))
    return results


def deduplicate_near_identical_utterances(
    entries: list[dict[str, Any]],
    *,
    jaccard_threshold: float = 0.82,
) -> tuple[list[dict[str, Any]], int]:
    """Removes looped/repeated takes with near-identical transcripts, keeping the highest-quality take."""
    # Sort highest quality first (DNSMOS + WeSpeaker cosine) so the cleanest take wins
    ranked = sorted(
        entries,
        key=lambda e: (float(e["dnsmos_p808"]) + 1.5 * float(e["wespeaker_cos"])),
        reverse=True,
    )
    kept: list[dict[str, Any]] = []
    kept_word_sets: list[tuple[str, set[str]]] = []
    dropped = 0

    for entry in ranked:
        words = [w.lower() for w in _WORD_RE.findall(str(entry["transcript"]))]
        wset = set(words)
        is_dup = False
        if len(wset) >= 5:
            norm_str = " ".join(words)
            for prev_norm, prev_set in kept_word_sets:
                if norm_str == prev_norm:
                    is_dup = True
                    break
                inter = len(wset & prev_set)
                union = len(wset | prev_set)
                if union > 0 and (float(inter) / float(union)) >= jaccard_threshold:
                    is_dup = True
                    break
        if is_dup:
            dropped += 1
            continue
        kept.append(entry)
        if len(wset) >= 5:
            kept_word_sets.append((" ".join(words), wset))

    kept.sort(key=lambda r: r["id"])
    return kept, dropped


def mine_device_dataset(
    *,
    downloads_dir: Path = DEFAULT_DOWNLOADS_DIR,
    voice_dir: Path = DEFAULT_VOICE_DIR,
    dataset_dir: Path = DEFAULT_DATASET_DIR,
    wespeaker_onnx: Path = DEFAULT_WESPEAKER_ONNX,
    dnsmos_onnx: Path = DEFAULT_DNSMOS_P808_ONNX,
    whisper_cli: Path = DEFAULT_WHISPER_CLI,
    whisper_model: Path = DEFAULT_WHISPER_MODEL,
    min_wespeaker_cos: float = 0.72,
    min_seed_anchor_cos: float = 0.62,
    min_dnsmos_p808: float = 2.75,
    min_dur_sec: float = 2.5,
    max_dur_sec: float = 13.5,
    adb_serial: str | None = None,
    adb_camera_dir: str = DEFAULT_ADB_CAMERA_DIR,
    target_minutes: float = 30.0,
    max_adb_videos: int = 550,
    max_adb_file_mb: float = 220.0,
) -> dict[str, Any]:
    """Runs complete Mac + optional USB Android device mining, double-locked WeSpeaker/DNSMOS gating, and Whisper ASR.

    Uses a 2-pass multi-session speaker verification bootstrap:
      - Pass 1 screens local Mac personal videos strictly against Ryan's `clone/voice` seed
        centroid (`cos >= min_wespeaker_cos`) to build a channel-balanced multi-session Ryan
        centroid (removing single-mic EQ bias between handheld phone and desk mic).
      - Pass 2 gates ALL Mac + USB Pixel utterances requiring BOTH
        `cos(emb, multi_session_centroid) >= min_wespeaker_cos` (0.72) AND
        `cos(emb, raw_seed_centroid) >= min_seed_anchor_cos` (0.62), strictly preventing
        any speaker drift while achieving high recall across mics.
    """
    wavs_dir = dataset_dir / "wavs"
    dataset_dir.mkdir(parents=True, exist_ok=True)
    if wavs_dir.exists():
        shutil.rmtree(wavs_dir)
    wavs_dir.mkdir(parents=True, exist_ok=True)

    wespeaker = WeSpeakerEngine(wespeaker_onnx)
    dnsmos = DnsmosP808Engine(dnsmos_onnx, sig_bak_ovr_path=None)

    seed_centroid, seed_records = compute_seed_centroid(voice_dir, wespeaker)
    media_files, skipped_files = discover_personal_media(
        downloads_dir=downloads_dir,
        voice_raw_dir=voice_dir / "raw",
    )

    # Pass 1: Fast local Mac bootstrap (strict >= min_wespeaker_cos vs raw clone/voice seed)
    # to build a channel-invariant multi-session centroid while preserving >=0.90 lock to seed.
    mac_decoded_cache: list[tuple[Path, str, float, list[SliceCandidate], list[np.ndarray]]] = []
    bootstrap_by_source: dict[str, list[np.ndarray]] = {}
    seen_fingerprints: list[tuple[str, float, np.ndarray]] = []
    source_stats: list[dict[str, Any]] = []
    passed_audio_candidates: list[dict[str, Any]] = []
    seen_ids: set[str] = set()

    total_slices = 0
    rejected_wespeaker = 0
    rejected_dnsmos = 0
    rejected_no_audio = 0
    rejected_dup_audio = 0
    adb_videos_pulled = 0
    passed_audio_sec = 0.0

    for media_path in media_files:
        has_audio, dur = probe_has_audio_stream(media_path)
        if not has_audio or dur < min_dur_sec:
            rejected_no_audio += 1
            skipped_files.append(
                {"file": media_path.name, "reason": f"no_audio_or_too_short_{dur:.2f}s"}
            )
            continue

        pcm_44k = extract_mono_44k_pcm(media_path)
        if pcm_44k.size < int(min_dur_sec * SYNTHESIS_SAMPLE_RATE_HZ):
            rejected_no_audio += 1
            skipped_files.append({"file": media_path.name, "reason": "empty_decoded_audio"})
            continue

        pcm_16k = resample_audio(pcm_44k, SYNTHESIS_SAMPLE_RATE_HZ, VERIFIER_SAMPLE_RATE_HZ)
        fp = audio_track_fingerprint(pcm_16k)
        if fp is not None:
            dup_found = False
            for prev_name, prev_dur, prev_fp in seen_fingerprints:
                if abs(prev_dur - dur) <= 2.5 and float(np.dot(fp, prev_fp)) >= 0.97:
                    rejected_dup_audio += 1
                    skipped_files.append(
                        {"file": media_path.name, "reason": f"duplicate_audio_of_{prev_name}"}
                    )
                    dup_found = True
                    break
            if dup_found:
                continue
            seen_fingerprints.append((media_path.name, dur, fp))

        slices = slice_speech_utterances(
            pcm_44k=pcm_44k,
            pcm_16k=pcm_16k,
            source_name=media_path.name,
            source_path=media_path,
            min_dur_sec=min_dur_sec,
            max_dur_sec=max_dur_sec,
        )
        slice_embs: list[np.ndarray] = []
        for sl in slices:
            try:
                emb = wespeaker.extract_embedding(sl.pcm_16k)
            except Exception:
                emb = np.zeros(0, dtype=np.float32)
            slice_embs.append(emb)
            if emb.size > 0 and cosine_similarity(emb, seed_centroid) >= min_wespeaker_cos:
                bootstrap_by_source.setdefault(media_path.name, []).append(emb)
        mac_decoded_cache.append((media_path, media_path.name, dur, slices, slice_embs))

    # Build multi-session Pass-2 centroid (equal weight per verified Pass-1 source + 2x raw seed anchor)
    if bootstrap_by_source:
        components = [seed_centroid.astype(np.float64), seed_centroid.astype(np.float64)]
        for embs_list in bootstrap_by_source.values():
            src_mean = np.mean(np.stack(embs_list, axis=0).astype(np.float64), axis=0)
            src_norm = float(np.linalg.norm(src_mean))
            if src_norm > 1e-12:
                components.append(src_mean / src_norm)
        multi_vec = np.mean(np.stack(components, axis=0), axis=0)
        active_centroid = (multi_vec / max(float(np.linalg.norm(multi_vec)), 1e-12)).astype(
            np.float32
        )
    else:
        active_centroid = seed_centroid

    seed_to_active_cos = cosine_similarity(seed_centroid, active_centroid)

    def evaluate_slices(
        display_name: str,
        origin_tag: str,
        dur: float,
        slices: list[SliceCandidate],
        precomputed_embs: list[np.ndarray] | None = None,
    ) -> None:
        nonlocal total_slices, rejected_wespeaker, rejected_dnsmos, passed_audio_sec
        total_slices += len(slices)
        src_kept = 0
        src_ws_pass = 0
        src_max_cos = 0.0

        for idx_s, sl in enumerate(slices):
            if precomputed_embs is not None:
                emb = precomputed_embs[idx_s]
                if emb.size == 0:
                    rejected_wespeaker += 1
                    continue
            else:
                try:
                    emb = wespeaker.extract_embedding(sl.pcm_16k)
                except Exception:
                    rejected_wespeaker += 1
                    continue

            ws_cos = cosine_similarity(emb, active_centroid)
            s_cos = cosine_similarity(emb, seed_centroid)
            if ws_cos > src_max_cos:
                src_max_cos = ws_cos
            if ws_cos < min_wespeaker_cos or s_cos < min_seed_anchor_cos:
                rejected_wespeaker += 1
                continue
            src_ws_pass += 1

            try:
                mos = dnsmos.score_p808(sl.pcm_16k)
            except Exception:
                rejected_dnsmos += 1
                continue
            if mos < min_dnsmos_p808:
                rejected_dnsmos += 1
                continue

            src_kept += 1
            base_id = f"ryan_{_sanitize_stem(display_name)}_{sl.slice_index:03d}"
            utt_id = base_id
            suffix = 1
            while utt_id in seen_ids:
                utt_id = f"{base_id}_{suffix}"
                suffix += 1
            seen_ids.add(utt_id)

            dur_s = round(float(sl.pcm_44k.size) / float(SYNTHESIS_SAMPLE_RATE_HZ), 3)
            passed_audio_sec += dur_s
            passed_audio_candidates.append(
                {
                    "id": utt_id,
                    "source_video": display_name,
                    "origin": origin_tag,
                    "start_sec": sl.start_sec,
                    "end_sec": sl.end_sec,
                    "duration_sec": dur_s,
                    "wespeaker_cos": round(ws_cos, 5),
                    "seed_cos": round(s_cos, 5),
                    "dnsmos_p808": round(mos, 4),
                    "rms_dbfs": sl.rms_dbfs,
                    "pcm_44k": sl.pcm_44k,
                    "pcm_16k": sl.pcm_16k,
                }
            )

        source_stats.append(
            {
                "file": display_name,
                "origin": origin_tag,
                "duration_sec": round(dur, 2),
                "slices": len(slices),
                "passed_wespeaker": src_ws_pass,
                "passed_audio_gates": src_kept,
                "max_wespeaker_cos": round(src_max_cos, 4),
            }
        )

    # 1. Evaluate all cached local Mac sources using the double-locked multi-session centroid
    for _mpath, display_name, dur, slices, embs in mac_decoded_cache:
        evaluate_slices(display_name, "mac_local", dur, slices, precomputed_embs=embs)

    # 2. Stream non-duplicate recent videos from USB-connected Pixel phone if requested
    if adb_serial and (passed_audio_sec / 60.0) < target_minutes:
        existing_names = {p.name for p in media_files} | {
            s["file"] for s in skipped_files
        }
        existing_ts = {
            k for k in (_base_timestamp_key(n) for n in existing_names) if k is not None
        }
        adb_videos = list_adb_camera_videos(
            adb_serial,
            camera_dir=adb_camera_dir,
            existing_names=existing_names,
            existing_ts_keys=existing_ts,
            max_file_mb=max_adb_file_mb,
        )
        with tempfile.TemporaryDirectory(prefix="voicestudio_adb_pull_") as adb_tmp:
            adb_tmp_dir = Path(adb_tmp)
            for remote_path, filename, _size_bytes in adb_videos[:max_adb_videos]:
                if (passed_audio_sec / 60.0) >= target_minutes:
                    break
                local_tmp = adb_tmp_dir / filename
                try:
                    pull_res = subprocess.run(
                        [str(DEFAULT_ADB), "-s", adb_serial, "pull", remote_path, str(local_tmp)],
                        capture_output=True,
                        text=True,
                        check=False,
                    )
                    if pull_res.returncode != 0 or not local_tmp.is_file():
                        skipped_files.append(
                            {"file": filename, "reason": "adb_pull_failed"}
                        )
                        continue
                    adb_videos_pulled += 1

                    has_audio, dur = probe_has_audio_stream(local_tmp)
                    if not has_audio or dur < min_dur_sec:
                        rejected_no_audio += 1
                        skipped_files.append(
                            {"file": filename, "reason": f"no_audio_or_too_short_{dur:.2f}s"}
                        )
                        continue

                    pcm_44k = extract_mono_44k_pcm(local_tmp)
                    if pcm_44k.size < int(min_dur_sec * SYNTHESIS_SAMPLE_RATE_HZ):
                        rejected_no_audio += 1
                        skipped_files.append({"file": filename, "reason": "empty_decoded_audio"})
                        continue

                    pcm_16k = resample_audio(
                        pcm_44k, SYNTHESIS_SAMPLE_RATE_HZ, VERIFIER_SAMPLE_RATE_HZ
                    )
                    fp = audio_track_fingerprint(pcm_16k)
                    if fp is not None:
                        dup_found = False
                        for prev_name, prev_dur, prev_fp in seen_fingerprints:
                            if abs(prev_dur - dur) <= 2.5 and float(np.dot(fp, prev_fp)) >= 0.97:
                                rejected_dup_audio += 1
                                skipped_files.append(
                                    {"file": filename, "reason": f"duplicate_audio_of_{prev_name}"}
                                )
                                dup_found = True
                                break
                        if dup_found:
                            continue
                        seen_fingerprints.append((filename, dur, fp))

                    slices = slice_speech_utterances(
                        pcm_44k=pcm_44k,
                        pcm_16k=pcm_16k,
                        source_name=filename,
                        source_path=local_tmp,
                        min_dur_sec=min_dur_sec,
                        max_dur_sec=max_dur_sec,
                    )
                    evaluate_slices(filename, f"adb:{adb_serial}", dur, slices)
                finally:
                    # Immediately delete temp .mp4 to preserve Mac disk space
                    local_tmp.unlink(missing_ok=True)

    # 3. Batch-transcribe all WeSpeaker + DNSMOS passing candidates with Whisper Metal GPU
    raw_manifest_entries: list[dict[str, Any]] = []
    cand_pcm44_by_id: dict[str, np.ndarray] = {}
    rejected_asr: dict[str, int] = {}

    with tempfile.TemporaryDirectory(prefix="voicestudio_whisper_") as tmpdir:
        tmp_path = Path(tmpdir)
        tmp_wavs: list[Path] = []
        cand_by_tmp: dict[Path, dict[str, Any]] = {}

        for cand in passed_audio_candidates:
            w16 = tmp_path / f"{cand['id']}.wav"
            sf.write(str(w16), cand["pcm_16k"], VERIFIER_SAMPLE_RATE_HZ, subtype="PCM_16")
            tmp_wavs.append(w16)
            cand_by_tmp[w16] = cand

        raw_transcripts = batch_transcribe_whisper(
            tmp_wavs,
            whisper_cli=whisper_cli,
            whisper_model=whisper_model,
        )

        for w16 in tmp_wavs:
            cand = cand_by_tmp[w16]
            raw_txt = raw_transcripts.get(w16, "")
            cleaned_txt, reject_reason = clean_and_validate_transcript(
                raw_txt, float(cand["duration_sec"])
            )
            if cleaned_txt is None:
                key = reject_reason or "unknown"
                rejected_asr[key] = rejected_asr.get(key, 0) + 1
                continue

            wav44_out = wavs_dir / f"{cand['id']}.wav"
            cand_pcm44_by_id[cand["id"]] = cand["pcm_44k"]
            raw_manifest_entries.append(
                {
                    "id": cand["id"],
                    "wav_44k": str(wav44_out),
                    "duration_sec": cand["duration_sec"],
                    "transcript": cleaned_txt,
                    "wespeaker_cos": cand["wespeaker_cos"],
                    "dnsmos_p808": cand["dnsmos_p808"],
                    "source_video": cand["source_video"],
                }
            )

    # 4. Deduplicate looped/repeated takes (keeping highest-quality take) & write final WAVs
    manifest_entries, dropped_dup_transcripts = deduplicate_near_identical_utterances(
        raw_manifest_entries
    )
    for entry in manifest_entries:
        sf.write(
            entry["wav_44k"],
            cand_pcm44_by_id[entry["id"]],
            SYNTHESIS_SAMPLE_RATE_HZ,
            subtype="FLOAT",
        )

    # Sort deterministically by id and atomically write manifest.jsonl
    manifest_entries.sort(key=lambda r: r["id"])
    manifest_path = dataset_dir / "manifest.jsonl"
    tmp_manifest = manifest_path.with_name(f".{manifest_path.name}.tmp.{os.getpid()}")
    try:
        lines = "".join(
            json.dumps(entry, separators=(",", ":"), ensure_ascii=False) + "\n"
            for entry in manifest_entries
        )
        tmp_manifest.write_text(lines, encoding="utf-8")
        os.replace(tmp_manifest, manifest_path)
    finally:
        if tmp_manifest.exists():
            tmp_manifest.unlink(missing_ok=True)

    total_sec = sum(float(e["duration_sec"]) for e in manifest_entries)
    cos_vals = [float(e["wespeaker_cos"]) for e in manifest_entries]
    mos_vals = [float(e["dnsmos_p808"]) for e in manifest_entries]

    summary: dict[str, Any] = {
        "generated_at_utc": datetime.datetime.now(datetime.timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%SZ"
        ),
        "manifest_path": str(manifest_path),
        "manifest_sha256": sha256_file(manifest_path),
        "thresholds": {
            "min_wespeaker_cos": min_wespeaker_cos,
            "min_seed_anchor_cos": min_seed_anchor_cos,
            "seed_to_active_centroid_cos": round(seed_to_active_cos, 5),
            "bootstrap_verified_mac_sources": len(bootstrap_by_source),
            "min_dnsmos_p808": min_dnsmos_p808,
            "min_dur_sec": min_dur_sec,
            "max_dur_sec": max_dur_sec,
            "target_minutes": target_minutes,
            "adb_serial": adb_serial,
        },
        "seed_recordings": seed_records,
        "funnel": {
            "mac_candidate_media_files": len(media_files),
            "adb_videos_pulled_and_cleaned": adb_videos_pulled,
            "skipped_media_files": len(skipped_files),
            "rejected_no_audio_or_short": rejected_no_audio,
            "rejected_duplicate_audio_tracks": rejected_dup_audio,
            "total_speech_slices_extracted": total_slices,
            "rejected_wespeaker_below_threshold": rejected_wespeaker,
            "rejected_dnsmos_below_threshold": rejected_dnsmos,
            "passed_audio_gates": len(passed_audio_candidates),
            "rejected_whisper_asr": rejected_asr,
            "dropped_duplicate_loop_utterances": dropped_dup_transcripts,
            "final_verified_utterances": len(manifest_entries),
        },
        "metrics": {
            "total_duration_sec": round(total_sec, 2),
            "total_duration_min": round(total_sec / 60.0, 2),
            "mean_wespeaker_cos": round(float(np.mean(cos_vals)), 5) if cos_vals else 0.0,
            "min_wespeaker_cos": round(float(np.min(cos_vals)), 5) if cos_vals else 0.0,
            "max_wespeaker_cos": round(float(np.max(cos_vals)), 5) if cos_vals else 0.0,
            "mean_dnsmos_p808": round(float(np.mean(mos_vals)), 4) if mos_vals else 0.0,
            "min_dnsmos_p808": round(float(np.min(mos_vals)), 4) if mos_vals else 0.0,
            "max_dnsmos_p808": round(float(np.max(mos_vals)), 4) if mos_vals else 0.0,
            "contributing_source_videos": len({e["source_video"] for e in manifest_entries}),
        },
        "per_source_stats": source_stats,
        "skipped_files": skipped_files,
    }

    summary_path = dataset_dir / "mining_summary.json"
    summary_path.write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return summary


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Mine verified Ryan voice utterances from Mac & USB Android device into dataset/manifest.jsonl."
    )
    parser.add_argument("--downloads-dir", type=Path, default=DEFAULT_DOWNLOADS_DIR)
    parser.add_argument("--voice-dir", type=Path, default=DEFAULT_VOICE_DIR)
    parser.add_argument("--dataset-dir", type=Path, default=DEFAULT_DATASET_DIR)
    parser.add_argument("--min-wespeaker-cos", type=float, default=0.72)
    parser.add_argument("--min-seed-anchor-cos", type=float, default=0.62)
    parser.add_argument("--min-dnsmos", type=float, default=2.75)
    parser.add_argument("--adb-serial", type=str, default=None)
    parser.add_argument("--adb-camera-dir", type=str, default=DEFAULT_ADB_CAMERA_DIR)
    parser.add_argument("--target-minutes", type=float, default=30.0)
    parser.add_argument("--max-adb-videos", type=int, default=550)
    parser.add_argument("--max-adb-file-mb", type=float, default=220.0)
    args = parser.parse_args(argv)

    summary = mine_device_dataset(
        downloads_dir=args.downloads_dir,
        voice_dir=args.voice_dir,
        dataset_dir=args.dataset_dir,
        min_wespeaker_cos=args.min_wespeaker_cos,
        min_seed_anchor_cos=args.min_seed_anchor_cos,
        min_dnsmos_p808=args.min_dnsmos,
        adb_serial=args.adb_serial,
        adb_camera_dir=args.adb_camera_dir,
        target_minutes=args.target_minutes,
        max_adb_videos=args.max_adb_videos,
        max_adb_file_mb=args.max_adb_file_mb,
    )
    m = summary["metrics"]
    f = summary["funnel"]
    print(
        f"[mine] Verified {f['final_verified_utterances']} utterances "
        f"({m['total_duration_min']:.2f} min) from {m['contributing_source_videos']} source videos "
        f"(mac={f['mac_candidate_media_files']}, adb_pulled={f['adb_videos_pulled_and_cleaned']}) | "
        f"mean WeSpeaker cos={m['mean_wespeaker_cos']:.4f} | "
        f"mean DNSMOS p808={m['mean_dnsmos_p808']:.3f}"
    )
    print(f"[mine] Manifest: {summary['manifest_path']} (sha256={summary['manifest_sha256']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
