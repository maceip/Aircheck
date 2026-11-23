#!/usr/bin/env python3
"""Complete end-to-end local Text -> S2-Pro + LoRA -> LiteRT FP16 -> 4-Gate Verifier -> Talking-Head Video.

Executes the full real Aircheck / VoiceStudio pipeline on Apple Silicon (M5 Max MPS BF16):
1. Encodes Ryan's real reference audio (`voice_ref_alt.wav`) to `[10, T_ref]` discrete ModdedDAC codes.
2. Runs `fishaudio/s2-pro` Dual-AR (36-layer Slow AR + Ryan's trained Rank-32 PEFT LoRA from
   `lora-work-v001/` + 4-layer Fast AR audio decoder) to synthesize `N = 4` candidate takes.
3. Decodes all candidates via the real exported FP16 LiteRT decoder (`codec_decoder_fp16.tflite`)
   using causal chunked overlap-add and measures exact parity vs PyTorch `ModdedDAC.from_indices`.
4. Evaluates every candidate through the real 4-Gate Verifier:
   - Gate 1: Offline `whisper-cli` (`ggml-medium-q5_0.bin`) ASR transcript & exact Levenshtein CER
   - Gate 2: Real ONNX `WeSpeaker ResNet34` L2-normalized cosine vs `master_centroid.bin`
   - Gate 3: Real ONNX Microsoft `DNSMOS p808` (`model_v8.onnx`, official scaling) + 80 ms tail RMS dBFS
   - Gate 4: Deterministic English syllable cadence (syllables/sec over active speech span)
5. Applies Aircheck studio mastering (`room_ir_44k.pcm` 12 ms convolution + ITU-R BS.1770-4
   `-16.0 LUFS` normalization + soft limiter + `-58 dBFS` `room_tone_44k.pcm` bed) to the winning
   synthesized take, re-verifies it through all 4 gates, and writes `ryan_s2pro_lora_synth.wav` +
   `synth_verification_report.json`.
6. Feeds the 100% locally synthesized WAV into `generate_talking_head.py` (`wan3.0-video`) and
   extracts a 6-frame horizontal inspection contact strip.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime
import json
import math
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Sequence

import numpy as np
import scipy.signal
import soundfile as sf

_THIS_DIR = Path(__file__).resolve().parent
if str(_THIS_DIR) not in sys.path:
    sys.path.insert(0, str(_THIS_DIR))

import enroll_voice_assets as eva  # noqa: E402
import generate_talking_head as gth  # noqa: E402
import s2_pro_infer  # noqa: E402

SAMPLE_RATE_HZ: int = 44_100
VERIFIER_SR_HZ: int = 16_000

DEFAULT_TARGET_TEXT: str = (
    "We ripped out the cloud wrapper and ran the entire voice studio bare-metal right on the Pixel."
)
DEFAULT_REF_WAV = Path("/Users/mac/Downloads/clone/voice/voice_ref_alt.wav")
DEFAULT_REF_TEXT: str = (
    "This message is for Matthew, Swish, and Ranya. "
    "Ranya, I've been singing J. Cole all day, thank you. "
    "And Matt, hope you're feeling better, bro. "
    "I'm leaving your mail right there."
)
DEFAULT_EXTRA_REF_WAV = Path(
    "/Users/mac/Downloads/voicestudio-models/dataset/wavs/ryan_20260908_190501_b54b7715_004.wav"
)
DEFAULT_EXTRA_REF_TEXT: str = (
    "the model for the specific hardware, like this Dimensity chip in a Huawei 15T "
    "is obviously different than the Pixel Titan chips, which we don't know anything about anyways."
)
DEFAULT_SEEDS: tuple[int, ...] = (948101, 948102, 948103, 948104)

DEFAULT_BUNDLE_DIR = Path("/Users/mac/Downloads/voicestudio-models/bundles/v001")
DEFAULT_RENDERS_DIR = Path("/Users/mac/Downloads/voicestudio-models/renders")
DEFAULT_MASTER_CENTROID = DEFAULT_BUNDLE_DIR / "master_centroid.bin"
DEFAULT_ROOM_IR = DEFAULT_BUNDLE_DIR / "room_ir_44k.pcm"
DEFAULT_ROOM_TONE = DEFAULT_BUNDLE_DIR / "room_tone_44k.pcm"
DEFAULT_OUTPUT_WAV = DEFAULT_RENDERS_DIR / "ryan_s2pro_lora_synth.wav"
DEFAULT_REPORT_JSON = DEFAULT_RENDERS_DIR / "synth_verification_report.json"
DEFAULT_OUTPUT_MP4 = DEFAULT_RENDERS_DIR / "ryan_synth_talking_head_v1.mp4"
DEFAULT_STRIP_JPG = Path(
    "/Users/mac/.gemini/antigravity/brain/e8d92277-7081-4761-bd20-5b9124c64828/talking_head_synth_v1_strip.jpg"
)
DEFAULT_WHISPER_BIN = Path("/opt/homebrew/bin/whisper-cli")
DEFAULT_WHISPER_MODEL = Path("/Users/mac/models/ggml-medium-q5_0.bin")

# Canonical Aircheck / VoiceStudio 4-Gate thresholds (matching VerificationGateThresholds)
GATE_MAX_CER: float = 0.0
GATE_MIN_SPEAKER_COS: float = 0.86
GATE_MIN_DNSMOS_P808: float = 4.10
GATE_MAX_TAIL_RMS_DBFS: float = -45.0
GATE_MIN_SYLLABLES_PER_SEC: float = 4.2
GATE_MAX_SYLLABLES_PER_SEC: float = 6.1


# ---------------------------------------------------------------------------
# Deterministic DSP Parity (Syllables, Tail RMS, ITU-R BS.1770-4 LUFS, Room IR)
# ---------------------------------------------------------------------------


def _is_ascii_vowel(ch: str) -> bool:
    return ch in "aeiouy"


def count_word_syllables(word: str) -> int:
    """Exact Python mirror of `AudioDspMetrics.countWordSyllables`."""
    clean = "".join(c.lower() for c in word if ("A" <= c <= "Z") or ("a" <= c <= "z"))
    if not clean:
        return 0
    if len(clean) <= 2:
        return 1

    ends_consonant_le = (
        len(clean) >= 3
        and clean[-2] == "l"
        and clean[-1] == "e"
        and not _is_ascii_vowel(clean[-3])
    )
    if len(clean) > 3 and clean.endswith("ed"):
        before = clean[-3]
        stem = clean[:-2] if before not in ("t", "d") else clean
    elif len(clean) > 3 and clean.endswith("es"):
        before = clean[-3]
        sibilant = before in ("s", "x", "z", "c", "g") or (
            len(clean) > 4 and before == "h" and clean[-4] in ("c", "s")
        )
        stem = clean[:-2] if not sibilant else clean
    elif len(clean) > 2 and clean.endswith("e") and not ends_consonant_le:
        stem = clean[:-1]
    else:
        stem = clean

    syllables = 0
    prev_vowel = False
    for ch in stem:
        is_v = _is_ascii_vowel(ch)
        if is_v and not prev_vowel:
            syllables += 1
        prev_vowel = is_v

    for i in range(len(stem) - 1):
        a, b = stem[i], stem[i + 1]
        is_hiatus = (
            (a == "i" and b in ("a", "o", "u"))
            or (a == "e" and (b == "o" or (b == "a" and i + 2 == len(stem))))
            or (a == "u" and b == "a")
        )
        if not is_hiatus:
            continue
        is_tion = i >= 1 and stem[i - 1] in ("t", "s", "c") and a == "i" and b == "o"
        if not is_tion:
            syllables += 1

    return max(1, syllables)


def count_transcript_syllables(text: str) -> int:
    norm = eva.normalize_transcript(text)
    if not norm:
        return 0
    return sum(count_word_syllables(tok) for tok in norm.split(" ") if tok)


def measure_active_speech_samples(
    pcm44k: np.ndarray,
    *,
    sample_rate_hz: int = SAMPLE_RATE_HZ,
    threshold_dbfs: float = -42.0,
) -> tuple[int, int, int]:
    """Locate active speech start/end samples using 20 ms frame energy."""
    arr = np.asarray(pcm44k, dtype=np.float32).reshape(-1)
    if arr.size == 0:
        return 0, 0, 0
    win = max(64, int(round(0.020 * sample_rate_hz)))
    hop = max(32, win // 2)
    if arr.size <= win:
        return 0, int(arr.size), int(arr.size)

    thresh_lin = 10.0 ** (threshold_dbfs / 20.0)
    active_starts: list[int] = []
    for pos in range(0, arr.size - win + 1, hop):
        rms = float(np.sqrt(np.mean(arr[pos : pos + win].astype(np.float64) ** 2)))
        if rms >= thresh_lin:
            active_starts.append(pos)
    if not active_starts:
        return 0, int(arr.size), int(arr.size)
    s0 = active_starts[0]
    s1 = min(int(arr.size), active_starts[-1] + win)
    return s0, s1, max(1, s1 - s0)


def compute_tail_rms_dbfs(
    pcm44k: np.ndarray,
    *,
    tail_ms: int = 80,
    sample_rate_hz: int = SAMPLE_RATE_HZ,
) -> float:
    """Exact Python mirror of `AudioDspMetrics.computeTailRmsDb`."""
    arr = np.asarray(pcm44k, dtype=np.float64).reshape(-1)
    if arr.size == 0 or tail_ms <= 0 or sample_rate_hz <= 0:
        return -120.0
    win = min(arr.size, int((sample_rate_hz * tail_ms) // 1000))
    if win <= 0:
        return -120.0
    seg = arr[-win:]
    rms = math.sqrt(float(np.mean(seg * seg)))
    return -120.0 if rms <= 1e-6 else float(20.0 * math.log10(rms))


def _k_weighting_sos(sample_rate_hz: int) -> np.ndarray:
    """Exact ITU-R BS.1770-4 two-stage K-weighting biquad cascade matching `dsp_mastering.cc`."""
    fs = float(max(8000, sample_rate_hz))

    # Stage 1: acoustic head-effect high-shelf pre-filter
    f0 = 1681.974450955533
    g_db = 3.999843853973347
    q = 0.7071752369554196
    k = math.tan(math.pi * f0 / fs)
    vh = 10.0 ** (g_db / 20.0)
    vb = vh ** 0.4996667741545416
    k2 = k * k
    a0 = 1.0 + k / q + k2
    s1 = [
        (vh + vb * k / q + k2) / a0,
        2.0 * (k2 - vh) / a0,
        (vh - vb * k / q + k2) / a0,
        1.0,
        2.0 * (k2 - 1.0) / a0,
        (1.0 - k / q + k2) / a0,
    ]

    # Stage 2: RLB high-pass filter
    f0_2 = 38.13547087602444
    q_2 = 0.5003270373238773
    k_2 = math.tan(math.pi * f0_2 / fs)
    k2_2 = k_2 * k_2
    a0_2 = 1.0 + k_2 / q_2 + k2_2
    s2 = [
        1.0 / a0_2,
        -2.0 / a0_2,
        1.0 / a0_2,
        1.0,
        2.0 * (k2_2 - 1.0) / a0_2,
        (1.0 - k_2 / q_2 + k2_2) / a0_2,
    ]
    return np.asarray([s1, s2], dtype=np.float64)


def measure_integrated_lufs(pcm: np.ndarray, sample_rate_hz: int = SAMPLE_RATE_HZ) -> float:
    """Exact ITU-R BS.1770-4 gated integrated loudness mirroring `dsp_mastering.cc`."""
    arr = np.asarray(pcm, dtype=np.float64).reshape(-1)
    if arr.size == 0:
        return -120.0
    arr = np.where(np.isfinite(arr), arr, 0.0)
    sos = _k_weighting_sos(sample_rate_hz)
    y = scipy.signal.sosfilt(sos, arr)
    k_sq = y * y

    block_len = max(1, (sample_rate_hz * 4) // 10)  # 400 ms
    hop_len = max(1, sample_rate_hz // 10)  # 100 ms
    if arr.size < block_len:
        mean_sq = float(np.mean(k_sq))
        return -120.0 if mean_sq <= 1e-15 else float(-0.691 + 10.0 * math.log10(mean_sq))

    prefix = np.concatenate([[0.0], np.cumsum(k_sq)])
    starts = np.arange(0, arr.size - block_len + 1, hop_len, dtype=np.int64)
    block_z = (prefix[starts + block_len] - prefix[starts]) / float(block_len)

    valid = block_z > 1e-15
    if not np.any(valid):
        return -120.0
    block_l = -0.691 + 10.0 * np.log10(np.maximum(block_z, 1e-30))
    abs_mask = valid & (block_l > -70.0)
    if not np.any(abs_mask):
        return -120.0
    z_abs_mean = float(np.mean(block_z[abs_mask]))
    rel_thresh = -0.691 + 10.0 * math.log10(z_abs_mean) - 10.0

    rel_mask = abs_mask & (block_l > rel_thresh)
    if not np.any(rel_mask):
        return float(-0.691 + 10.0 * math.log10(z_abs_mean))
    z_rel_mean = float(np.mean(block_z[rel_mask]))
    return float(-0.691 + 10.0 * math.log10(z_rel_mean))


def apply_gain_and_soft_limiter(
    pcm: np.ndarray,
    linear_gain: float,
    *,
    knee: float = 0.90,
    ceiling: float = 0.99,
) -> np.ndarray:
    """Exact mirror of `ApplyGainAndSoftLimiter` in `dsp_mastering.cc`."""
    x = np.asarray(pcm, dtype=np.float64) * float(linear_gain)
    x = np.where(np.isfinite(x), x, 0.0)
    ax = np.abs(x)
    headroom = max(1e-5, float(ceiling - knee))
    over = ax > knee
    if np.any(over):
        limited = knee + headroom * np.tanh((ax[over] - knee) / headroom)
        limited = np.minimum(limited, ceiling)
        x[over] = np.copysign(limited, x[over])
    return x.astype(np.float32)


def master_synthesized_pcm(
    pcm44k: np.ndarray,
    *,
    room_ir_path: Path | None = DEFAULT_ROOM_IR,
    room_tone_path: Path | None = DEFAULT_ROOM_TONE,
    target_lufs: float = -16.0,
    noise_floor_dbfs: float = -58.0,
    sample_rate_hz: int = SAMPLE_RATE_HZ,
) -> tuple[np.ndarray, dict[str, Any]]:
    """Apply exact Aircheck mastering: 12 ms room IR -> -16 LUFS -> -58 dBFS room tone."""
    out = np.asarray(pcm44k, dtype=np.float32).reshape(-1).copy()
    raw_lufs = measure_integrated_lufs(out, sample_rate_hz)

    ir_applied = False
    ir_taps = 0
    if room_ir_path is not None and Path(room_ir_path).is_file():
        ir = eva.read_pcm_44k(Path(room_ir_path))
        if ir.size > 0:
            conv = scipy.signal.fftconvolve(out.astype(np.float64), ir.astype(np.float64), mode="full")
            out = conv[: out.size].astype(np.float32)
            ir_applied = True
            ir_taps = int(ir.size)

    # Two-pass ITU-R BS.1770-4 normalization with soft true-peak limiter
    for pass_idx in range(2):
        cur_lufs = measure_integrated_lufs(out, sample_rate_hz)
        if cur_lufs <= -100.0:
            break
        delta_db = target_lufs - cur_lufs
        if pass_idx > 0 and abs(delta_db) <= 0.15:
            break
        clamped_db = max(-36.0, min(36.0, delta_db))
        out = apply_gain_and_soft_limiter(out, 10.0 ** (clamped_db / 20.0))
        if abs(delta_db) <= 0.15:
            break

    # Mix subtle -58 dBFS real room tone bed (skipping if none provided)
    room_tone_applied = False
    if room_tone_path is not None and Path(room_tone_path).is_file():
        bed = eva.read_pcm_44k(Path(room_tone_path))
        bed_rms = float(np.sqrt(np.mean(bed.astype(np.float64) ** 2))) if bed.size > 0 else 0.0
        if bed.size > 0 and bed_rms > 1e-12:
            target_rms = 10.0 ** (noise_floor_dbfs / 20.0)
            scale = target_rms / bed_rms
            reps = (out.size + bed.size - 1) // bed.size
            tiled = np.tile(bed, reps)[: out.size]
            out = np.clip(out + tiled * float(scale), -0.99, 0.99).astype(np.float32)
            room_tone_applied = True

    final_lufs = measure_integrated_lufs(out, sample_rate_hz)
    peak_amp = float(np.max(np.abs(out))) if out.size > 0 else 0.0
    peak_dbfs = -120.0 if peak_amp <= 1e-6 else float(20.0 * math.log10(peak_amp))
    return out, {
        "raw_lufs": round(raw_lufs, 3),
        "mastered_lufs": round(final_lufs, 3),
        "target_lufs": float(target_lufs),
        "peak_amplitude": round(peak_amp, 5),
        "peak_dbfs": round(peak_dbfs, 2),
        "room_ir_applied": ir_applied,
        "room_ir_taps": ir_taps,
        "room_tone_applied": room_tone_applied,
        "noise_floor_dbfs": float(noise_floor_dbfs),
    }


# ---------------------------------------------------------------------------
# Real 4-Gate Verifier (Offline whisper-cli + WeSpeaker ONNX + DNSMOS ONNX)
# ---------------------------------------------------------------------------


def transcribe_pcm16k_whisper(
    pcm16k: np.ndarray,
    *,
    whisper_bin: Path = DEFAULT_WHISPER_BIN,
    whisper_model: Path = DEFAULT_WHISPER_MODEL,
) -> str:
    """Transcribe 16 kHz mono float32 audio offline with local `whisper-cli`."""
    bin_path = Path(whisper_bin)
    if not bin_path.is_file():
        found = shutil.which("whisper-cli")
        if not found:
            raise FileNotFoundError(f"whisper-cli not found at {whisper_bin} or on PATH")
        bin_path = Path(found)
    if not Path(whisper_model).is_file():
        raise FileNotFoundError(f"Missing whisper model: {whisper_model}")

    with tempfile.TemporaryDirectory(prefix="s2pro_whisper_") as tmp_str:
        tmp_dir = Path(tmp_str)
        wav16 = tmp_dir / "cand_16k.wav"
        out_prefix = tmp_dir / "transcript"
        sf.write(str(wav16), np.clip(pcm16k, -1.0, 1.0), VERIFIER_SR_HZ, subtype="PCM_16")
        proc = subprocess.run(
            [
                str(bin_path),
                "-m",
                str(whisper_model),
                "-l",
                "en",
                "-f",
                str(wav16),
                "-otxt",
                "-of",
                str(out_prefix),
                "-np",
            ],
            capture_output=True,
            text=True,
        )
        if proc.returncode != 0:
            raise RuntimeError(
                f"whisper-cli failed (rc={proc.returncode}): {proc.stderr.strip()}"
            )
        txt_file = Path(f"{out_prefix}.txt")
        if not txt_file.is_file():
            return ""
        raw = txt_file.read_text(encoding="utf-8")
        cleaned = s2_pro_infer.clean_text_for_s2_pro(raw)
        return cleaned


def load_master_centroid_bin(path: Path) -> np.ndarray:
    raw = Path(path).read_bytes()
    if len(raw) != eva.SPEAKER_CENTROID_BYTES:
        raise ValueError(
            f"Expected {eva.SPEAKER_CENTROID_BYTES} bytes in {path}, got {len(raw)}"
        )
    vec = np.frombuffer(raw, dtype="<f4").copy()
    return eva.l2_normalize(vec)


def evaluate_candidate_4gates(
    pcm44k: np.ndarray,
    expected_text: str,
    *,
    master_centroid: np.ndarray,
    wespeaker: eva.WeSpeakerEngine,
    dnsmos: eva.DnsmosP808Engine,
    whisper_bin: Path = DEFAULT_WHISPER_BIN,
    whisper_model: Path = DEFAULT_WHISPER_MODEL,
) -> dict[str, Any]:
    """Run all 4 real Aircheck verification gates on a 44.1 kHz Float32 waveform."""
    arr44 = np.asarray(pcm44k, dtype=np.float32).reshape(-1)
    pcm16k = eva.resample_44k_to_16k_android_exact(arr44, SAMPLE_RATE_HZ, VERIFIER_SR_HZ)

    # Gate 1: Offline Whisper ASR + Levenshtein CER
    transcript = transcribe_pcm16k_whisper(
        pcm16k, whisper_bin=whisper_bin, whisper_model=whisper_model
    )
    cer = float(eva.compute_cer(expected_text, transcript))

    # Gate 2: Real WeSpeaker ResNet34 cosine vs master_centroid.bin
    emb = wespeaker.extract_embedding(pcm16k)
    speaker_cos = float(eva.cosine_similarity(emb, master_centroid))

    # Gate 3: Real Microsoft DNSMOS p808 (+ sig/bak/ovr) + 80 ms tail RMS dBFS
    dnsmos_p808 = float(dnsmos.score_p808(pcm16k))
    sig_bak_ovr = dnsmos.score_sig_bak_ovr(pcm16k)
    tail_rms_db = compute_tail_rms_dbfs(arr44, tail_ms=80, sample_rate_hz=SAMPLE_RATE_HZ)

    # Gate 4: English syllable cadence over active speech span
    s0, s1, active_samples = measure_active_speech_samples(arr44, sample_rate_hz=SAMPLE_RATE_HZ)
    total_syllables = count_transcript_syllables(expected_text)
    active_dur_sec = float(active_samples) / float(SAMPLE_RATE_HZ)
    syllables_per_sec = float(total_syllables) / max(active_dur_sec, 1e-6)

    # Strict canonical gate checks (never fabricated)
    failures: list[str] = []
    if cer > GATE_MAX_CER:
        failures.append("CER_EXCEEDED")
    if speaker_cos < GATE_MIN_SPEAKER_COS:
        failures.append("SPEAKER_SIMILARITY_LOW")
    if dnsmos_p808 < GATE_MIN_DNSMOS_P808:
        failures.append("DNSMOS_LOW")
    if tail_rms_db > GATE_MAX_TAIL_RMS_DBFS:
        failures.append("TAIL_RMS_EXCEEDED")
    if not (GATE_MIN_SYLLABLES_PER_SEC <= syllables_per_sec <= GATE_MAX_SYLLABLES_PER_SEC):
        failures.append("CADENCE_OUT_OF_BAND")

    passed_all = len(failures) == 0
    # Transparent composite score prioritizing intelligibility (low CER), speaker identity, and MOS
    norm_mos = max(0.0, min(1.0, dnsmos_p808 / 5.0))
    ranking_score = 1.5 * speaker_cos + 0.5 * norm_mos - 3.0 * cer

    return {
        "recognized_text": transcript,
        "cer": round(cer, 5),
        "wespeaker_cosine": round(speaker_cos, 5),
        "dnsmos_p808": round(dnsmos_p808, 4),
        "dnsmos_sig_bak_ovr": (
            {k: round(v, 4) for k, v in sig_bak_ovr.items()} if sig_bak_ovr is not None else None
        ),
        "tail_rms_dbfs": round(tail_rms_db, 2),
        "syllable_count": int(total_syllables),
        "active_speech_sec": round(active_dur_sec, 3),
        "active_span_samples": [int(s0), int(s1)],
        "total_duration_sec": round(float(arr44.size) / float(SAMPLE_RATE_HZ), 3),
        "syllables_per_sec": round(syllables_per_sec, 3),
        "passed_all_strict_gates": passed_all,
        "strict_gate_failures": failures,
        "ranking_score": round(ranking_score, 5),
    }


def select_best_candidate(candidates: Sequence[dict[str, Any]]) -> dict[str, Any]:
    """Select the highest-scoring candidate take (strict 4-gate passers first, then composite)."""
    if not candidates:
        raise ValueError("Cannot select best candidate from empty list")

    def _sort_key(c: dict[str, Any]) -> tuple[int, float, float, float, float]:
        g = c["gates"]
        return (
            1 if g["passed_all_strict_gates"] else 0,
            float(g["ranking_score"]),
            -float(g["cer"]),
            float(g["wespeaker_cosine"]),
            float(g["dnsmos_p808"]),
        )

    return max(candidates, key=_sort_key)


def extract_contact_strip(video_mp4: Path, strip_jpg: Path, num_frames: int = 6) -> Path:
    """Extract `num_frames` evenly spaced frames tiled horizontally into `strip_jpg`."""
    dur = gth.probe_duration_sec(video_mp4)
    fps = float(num_frames) / max(dur, 0.5)
    strip_jpg.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        "ffmpeg",
        "-y",
        "-i",
        str(video_mp4),
        "-vf",
        f"fps={fps:.4f},scale=-1:480,tile={num_frames}x1",
        "-frames:v",
        "1",
        "-q:v",
        "2",
        str(strip_jpg),
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0 or not strip_jpg.is_file() or strip_jpg.stat().st_size == 0:
        raise RuntimeError(
            f"ffmpeg strip extraction failed (rc={proc.returncode}): {proc.stderr.strip()}"
        )
    return strip_jpg


def run_end_to_end_pipeline(
    *,
    target_text: str = DEFAULT_TARGET_TEXT,
    ref_wav: Path = DEFAULT_REF_WAV,
    ref_text: str | None = DEFAULT_REF_TEXT,
    extra_ref_wav: Path | None = DEFAULT_EXTRA_REF_WAV,
    extra_ref_text: str | None = DEFAULT_EXTRA_REF_TEXT,
    prompt_style: str = "multiturn",
    seeds: Sequence[int] = DEFAULT_SEEDS,
    temperature: float = 0.72,
    top_p: float = 0.80,
    top_k: int = 30,
    max_new_tokens: int = 320,
    s2_pro_dir: Path = s2_pro_infer.DEFAULT_S2_PRO_HF_DIR,
    lora_dir: Path | None = s2_pro_infer.DEFAULT_LORA_DIR,
    lora_scale: float = 1.0,
    codec_pth: Path = s2_pro_infer.DEFAULT_CODEC_PTH,
    codec_tflite: Path = s2_pro_infer.DEFAULT_CODEC_TFLITE,
    master_centroid_path: Path = DEFAULT_MASTER_CENTROID,
    room_ir_path: Path = DEFAULT_ROOM_IR,
    room_tone_path: Path = DEFAULT_ROOM_TONE,
    wespeaker_onnx: Path = eva.DEFAULT_WESPEAKER_ONNX,
    dnsmos_onnx: Path = eva.DEFAULT_DNSMOS_P808_ONNX,
    whisper_bin: Path = DEFAULT_WHISPER_BIN,
    whisper_model: Path = DEFAULT_WHISPER_MODEL,
    output_wav: Path = DEFAULT_OUTPUT_WAV,
    report_json: Path = DEFAULT_REPORT_JSON,
    avatar_path: Path = gth.DEFAULT_AVATAR,
    output_mp4: Path = DEFAULT_OUTPUT_MP4,
    strip_jpg: Path = DEFAULT_STRIP_JPG,
    device: str = "mps",
    skip_video: bool = False,
    candidates_dir: Path | None = None,
) -> dict[str, Any]:
    """Run complete local synthesis, LiteRT FP16 decode, 4-gate scoring, mastering, and video."""
    output_wav = Path(output_wav)
    report_json = Path(report_json)
    output_wav.parent.mkdir(parents=True, exist_ok=True)
    report_json.parent.mkdir(parents=True, exist_ok=True)
    cand_dir = Path(candidates_dir) if candidates_dir else (output_wav.parent / "candidates")
    cand_dir.mkdir(parents=True, exist_ok=True)

    # Step 1: Encode Ryan's real reference audio(s) via ModdedDAC
    ref_specs: list[tuple[Path, str | None]] = [(Path(ref_wav), ref_text)]
    if extra_ref_wav is not None and str(extra_ref_wav).strip() and Path(extra_ref_wav).is_file():
        ref_specs.append((Path(extra_ref_wav), extra_ref_text))

    ref_turns: list[tuple[str, np.ndarray]] = []
    ref_metadata: list[dict[str, Any]] = []
    for r_idx, (r_wav, r_txt) in enumerate(ref_specs):
        print(
            f"[1/6] Encoding reference [{r_idx + 1}/{len(ref_specs)}] {r_wav} via ModdedDAC...",
            flush=True,
        )
        r_codes, r_dur = s2_pro_infer.encode_reference_audio_to_codes(
            r_wav, codec_pth=Path(codec_pth), device=device
        )
        resolved_txt = (r_txt or "").strip()
        if not resolved_txt:
            r_pcm16, _ = eva.load_audio_mono(r_wav, target_sr=VERIFIER_SR_HZ)
            resolved_txt = transcribe_pcm16k_whisper(
                r_pcm16, whisper_bin=whisper_bin, whisper_model=whisper_model
            )
        ref_turns.append((resolved_txt, r_codes))
        ref_metadata.append(
            {
                "wav_path": str(r_wav),
                "duration_sec": round(r_dur, 3),
                "codes_shape": list(r_codes.shape),
                "transcript": resolved_txt,
            }
        )
        print(
            f"      ref[{r_idx}] dur={r_dur:.2f}s codes={r_codes.shape} text={resolved_txt!r}",
            flush=True,
        )

    # Step 2: Load S2-Pro Dual-AR (36L Slow + 4L Fast) + merge Ryan's trained Rank-32 PEFT LoRA
    print(
        f"[2/6] Loading s2-pro Dual-AR on {device} (lora_dir={lora_dir}, lora_scale={lora_scale})...",
        flush=True,
    )
    effective_lora_dir = None if (lora_dir is None or lora_scale == 0.0) else Path(lora_dir)
    model, tokenizer, lora_stats = s2_pro_infer.load_s2_pro_dual_ar(
        Path(s2_pro_dir),
        lora_dir=effective_lora_dir,
        lora_scale=lora_scale,
        device=device,
    )
    if lora_stats is not None:
        print(
            f"      Merged LoRA r={lora_stats.lora_rank} alpha={lora_stats.lora_alpha} "
            f"scale={lora_stats.lora_scale} ({lora_stats.consumed_tensors} tensors, "
            f"max_delta={lora_stats.max_delta_abs})",
            flush=True,
        )

    prompt = s2_pro_infer.build_s2_pro_prompt_tensor(
        tokenizer,
        target_text,
        ref_turns=ref_turns,
        prompt_style=prompt_style,
    )
    print(
        f"      Built Dual-AR prompt tensor ({prompt_style}, {len(ref_turns)} ref turns): shape={tuple(prompt.shape)}",
        flush=True,
    )

    # Generate N candidates
    print(
        f"[3/6] Synthesizing {len(seeds)} candidates "
        f"(seeds={list(seeds)}, temp={temperature}, top_p={top_p}, top_k={top_k})...",
        flush=True,
    )
    gen_results: list[s2_pro_infer.GenerationResult] = []
    for seed in seeds:
        res = s2_pro_infer.generate_dual_ar_codes(
            model,
            prompt,
            seed=int(seed),
            max_new_tokens=max_new_tokens,
            temperature=temperature,
            top_p=top_p,
            top_k=top_k,
        )
        print(
            f"      seed={res.seed}: frames={res.generated_frames} "
            f"({res.generated_frames * 2048 / SAMPLE_RATE_HZ:.2f}s) "
            f"hit_eos={res.hit_eos} elapsed={res.elapsed_sec}s ({res.frames_per_sec} fps)",
            flush=True,
        )
        gen_results.append(res)

    # Free MPS memory from 4.5B Dual-AR before decoding & ONNX verification
    del model
    import torch

    if device == "mps" and torch.backends.mps.is_available():
        torch.mps.empty_cache()

    # Step 3: Decode all candidates via REAL exported FP16 LiteRT codec decoder
    print(f"[4/6] Decoding {len(gen_results)} takes via {codec_tflite}...", flush=True)
    decoded_batch = s2_pro_infer.decode_codes_batch_tflite(
        [g.codes for g in gen_results],
        tflite_path=Path(codec_tflite),
    )

    # Step 4: Score all candidates through REAL 4-Gate Verifier
    print("[5/6] Scoring all candidates with 4-Gate Verifier (whisper-cli + WeSpeaker + DNSMOS)...", flush=True)
    master_centroid = load_master_centroid_bin(Path(master_centroid_path))
    wespeaker = eva.WeSpeakerEngine(Path(wespeaker_onnx))
    dnsmos = eva.DnsmosP808Engine(Path(dnsmos_onnx))

    candidate_records: list[dict[str, Any]] = []
    candidate_pcms: dict[int, np.ndarray] = {}
    candidate_codes: dict[int, np.ndarray] = {}

    for idx, (gen_res, (pcm_raw, dec_meta)) in enumerate(zip(gen_results, decoded_batch)):
        take_wav = cand_dir / f"take_{idx}_seed_{gen_res.seed}.wav"
        take_codes = cand_dir / f"take_{idx}_seed_{gen_res.seed}_codes.npy"
        sf.write(str(take_wav), np.clip(pcm_raw, -1.0, 1.0), SAMPLE_RATE_HZ, subtype="PCM_16")
        np.save(take_codes, gen_res.codes)

        gates = evaluate_candidate_4gates(
            pcm_raw,
            target_text,
            master_centroid=master_centroid,
            wespeaker=wespeaker,
            dnsmos=dnsmos,
            whisper_bin=whisper_bin,
            whisper_model=whisper_model,
        )
        rec = {
            "take_index": idx,
            "seed": gen_res.seed,
            "wav_path": str(take_wav),
            "codes_npy": str(take_codes),
            "generation": {
                "generated_frames": gen_res.generated_frames,
                "hit_eos": gen_res.hit_eos,
                "elapsed_sec": gen_res.elapsed_sec,
                "frames_per_sec": gen_res.frames_per_sec,
                "temperature": gen_res.temperature,
                "top_p": gen_res.top_p,
                "top_k": gen_res.top_k,
                "residual_clamped_count": gen_res.residual_clamped_count,
            },
            "decode": dec_meta,
            "gates": gates,
        }
        candidate_records.append(rec)
        candidate_pcms[idx] = pcm_raw
        candidate_codes[idx] = gen_res.codes
        print(
            f"      take_{idx} (seed={gen_res.seed}): CER={gates['cer']:.4f} "
            f"WeSpeaker={gates['wespeaker_cosine']:.4f} DNSMOS={gates['dnsmos_p808']:.3f} "
            f"cadence={gates['syllables_per_sec']:.2f} syl/s tail={gates['tail_rms_dbfs']:.1f} dBFS "
            f"text={gates['recognized_text']!r}",
            flush=True,
        )

    winner = select_best_candidate(candidate_records)
    win_idx = int(winner["take_index"])
    win_raw_pcm = candidate_pcms[win_idx]
    win_codes = candidate_codes[win_idx]

    # Measure real PyTorch ModdedDAC vs codec_decoder_fp16.tflite parity on the winning synthesized codes
    pt_win_pcm = s2_pro_infer.decode_codes_pytorch_dac(
        win_codes, codec_pth=Path(codec_pth), device="cpu"
    )
    tflite_vs_pytorch_parity = s2_pro_infer.compute_waveform_parity(pt_win_pcm, win_raw_pcm)
    print(
        f"      Winning take={win_idx} (seed={winner['seed']}) LiteRT vs PyTorch DAC parity: "
        f"SNR={tflite_vs_pytorch_parity['snr_db']} dB, r={tflite_vs_pytorch_parity['pearson_corr']}",
        flush=True,
    )

    # Apply studio mastering (12 ms room IR + -16 LUFS + -58 dBFS room tone) and save final WAV
    mastered_pcm, mastering_meta = master_synthesized_pcm(
        win_raw_pcm,
        room_ir_path=Path(room_ir_path),
        room_tone_path=Path(room_tone_path),
        target_lufs=-16.0,
        noise_floor_dbfs=-58.0,
    )
    sf.write(str(output_wav), np.clip(mastered_pcm, -1.0, 1.0), SAMPLE_RATE_HZ, subtype="PCM_16")
    mastered_gates = evaluate_candidate_4gates(
        mastered_pcm,
        target_text,
        master_centroid=master_centroid,
        wespeaker=wespeaker,
        dnsmos=dnsmos,
        whisper_bin=whisper_bin,
        whisper_model=whisper_model,
    )
    print(
        f"      Mastered {output_wav}: LUFS={mastering_meta['mastered_lufs']} "
        f"CER={mastered_gates['cer']:.4f} WeSpeaker={mastered_gates['wespeaker_cosine']:.4f} "
        f"DNSMOS={mastered_gates['dnsmos_p808']:.3f} cadence={mastered_gates['syllables_per_sec']:.2f} syl/s "
        f"tail={mastered_gates['tail_rms_dbfs']:.1f} dBFS",
        flush=True,
    )

    video_result: dict[str, Any] | None = None
    if not skip_video:
        print(f"[6/6] Rendering talking-head video via generate_talking_head.py -> {output_mp4}...", flush=True)
        video_result = gth.generate_talking_head(
            voice_input=output_wav,
            avatar_path=Path(avatar_path),
            output_mp4=Path(output_mp4),
            transcript=target_text,
            resolution="720P",
            ratio="9:16",
            max_duration=10,
            replace_audio_with_input=True,
        )
        exact_mp4 = Path(video_result["exact_input_voice_mp4"])
        extracted = extract_contact_strip(exact_mp4, Path(strip_jpg), num_frames=6)
        video_result["contact_strip_jpg"] = str(extracted)
        print(
            f"      Rendered {output_mp4} + {exact_mp4} + strip {extracted}",
            flush=True,
        )

    report = {
        "generated_at_utc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "target_text": target_text,
        "prompt_style": prompt_style,
        "references": ref_metadata,
        "model": {
            "s2_pro_hf_dir": str(s2_pro_dir),
            "device": device,
            "lora": dataclasses.asdict(lora_stats) if lora_stats is not None else None,
            "codec_tflite": str(codec_tflite),
            "codec_tflite_sha256": eva.sha256_file(Path(codec_tflite)),
        },
        "sampling": {
            "seeds": [int(s) for s in seeds],
            "temperature": float(temperature),
            "top_p": float(top_p),
            "top_k": int(top_k),
            "max_new_tokens": int(max_new_tokens),
        },
        "candidates": candidate_records,
        "selected_take": {
            "take_index": win_idx,
            "seed": winner["seed"],
            "raw_gates": winner["gates"],
            "tflite_vs_pytorch_dac_parity": tflite_vs_pytorch_parity,
            "mastering": mastering_meta,
            "mastered_wav_path": str(output_wav),
            "mastered_wav_sha256": eva.sha256_file(output_wav),
            "mastered_gates": mastered_gates,
        },
        "video": video_result,
    }
    report_json.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"[done] Wrote verification report -> {report_json}", flush=True)
    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="End-to-end local S2-Pro + LoRA -> LiteRT FP16 -> 4-Gate Verifier -> Talking-Head Video"
    )
    parser.add_argument("--target-text", type=str, default=DEFAULT_TARGET_TEXT)
    parser.add_argument("--ref-wav", type=Path, default=DEFAULT_REF_WAV)
    parser.add_argument("--ref-text", type=str, default=DEFAULT_REF_TEXT)
    parser.add_argument("--extra-ref-wav", type=str, default=str(DEFAULT_EXTRA_REF_WAV))
    parser.add_argument("--extra-ref-text", type=str, default=DEFAULT_EXTRA_REF_TEXT)
    parser.add_argument("--prompt-style", choices=["multiturn", "system"], default="multiturn")
    parser.add_argument("--seeds", type=str, default=",".join(str(s) for s in DEFAULT_SEEDS))
    parser.add_argument("--temperature", type=float, default=0.72)
    parser.add_argument("--top-p", type=float, default=0.80)
    parser.add_argument("--top-k", type=int, default=30)
    parser.add_argument("--max-new-tokens", type=int, default=320)
    parser.add_argument("--s2-pro-dir", type=Path, default=s2_pro_infer.DEFAULT_S2_PRO_HF_DIR)
    parser.add_argument("--lora-dir", type=Path, default=s2_pro_infer.DEFAULT_LORA_DIR)
    parser.add_argument("--lora-scale", type=float, default=1.0)
    parser.add_argument("--codec-pth", type=Path, default=s2_pro_infer.DEFAULT_CODEC_PTH)
    parser.add_argument("--codec-tflite", type=Path, default=s2_pro_infer.DEFAULT_CODEC_TFLITE)
    parser.add_argument("--master-centroid", type=Path, default=DEFAULT_MASTER_CENTROID)
    parser.add_argument("--room-ir", type=Path, default=DEFAULT_ROOM_IR)
    parser.add_argument("--room-tone", type=Path, default=DEFAULT_ROOM_TONE)
    parser.add_argument("--output-wav", type=Path, default=DEFAULT_OUTPUT_WAV)
    parser.add_argument("--report-json", type=Path, default=DEFAULT_REPORT_JSON)
    parser.add_argument("--avatar", type=Path, default=gth.DEFAULT_AVATAR)
    parser.add_argument("--output-mp4", type=Path, default=DEFAULT_OUTPUT_MP4)
    parser.add_argument("--strip-jpg", type=Path, default=DEFAULT_STRIP_JPG)
    parser.add_argument("--device", type=str, default="mps")
    parser.add_argument("--skip-video", action="store_true")
    args = parser.parse_args(argv)

    seeds = [int(s.strip()) for s in args.seeds.split(",") if s.strip()]
    extra_wav = Path(args.extra_ref_wav) if args.extra_ref_wav.strip() else None
    run_end_to_end_pipeline(
        target_text=args.target_text,
        ref_wav=args.ref_wav,
        ref_text=args.ref_text,
        extra_ref_wav=extra_wav,
        extra_ref_text=args.extra_ref_text,
        prompt_style=args.prompt_style,
        seeds=seeds,
        temperature=args.temperature,
        top_p=args.top_p,
        top_k=args.top_k,
        max_new_tokens=args.max_new_tokens,
        s2_pro_dir=args.s2_pro_dir,
        lora_dir=args.lora_dir,
        lora_scale=args.lora_scale,
        codec_pth=args.codec_pth,
        codec_tflite=args.codec_tflite,
        master_centroid_path=args.master_centroid,
        room_ir_path=args.room_ir,
        room_tone_path=args.room_tone,
        output_wav=args.output_wav,
        report_json=args.report_json,
        avatar_path=args.avatar,
        output_mp4=args.output_mp4,
        strip_jpg=args.strip_jpg,
        device=args.device,
        skip_video=args.skip_video,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
