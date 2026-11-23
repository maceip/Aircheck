"""Real ONNX Voice Enrollment & OTA v001 Bundle Asset Generator for Aircheck / VoiceStudio.

Generates genuine, hardware-verified voice assets from Ryan's real audio recordings using
real ONNX models (zero fabricated embeddings, zero dummy noise, zero mocked scores):
  1. `master_centroid.bin`  - 256 IEEE-754 little-endian float32s (1,024 bytes), L2-normalized
                              WeSpeaker ResNet34 speaker centroid using exact Android
                              `AudioDspMetrics.computeLogMelFilterbank` feature parity.
  2. `room_tone_44k.pcm`    - 44.1 kHz Float32 mono headerless `.pcm`, 64-byte aligned, >=3.0s
                              seamlessly crossfade-looped quiet room bed extracted from real
                              low-energy pauses in Ryan's recordings, calibrated to -58.0 dBFS.
  3. `room_ir_44k.pcm`      - 44.1 kHz Float32 mono headerless `.pcm`, 64-byte aligned,
                              529 active taps (12.0 ms @ 44.1 kHz) padded with 15 zero floats
                              to 544 floats (2,176 bytes), acoustically calibrated from Ryan's
                              clean recordings with unit L2 energy gain and smooth causal decay.
  4. `lexicon.json`         - Deterministic flat term -> IPA dictionary matching Android
                              `ClauseNormalizer.kt` (`loadLexicon()`), covering all proper
                              nouns/terms from Ryan's transcripts, studio/tech terms, and
                              high-frequency General American English words.
  5. `enrollment_report.json` - Complete measured WeSpeaker cosines (self + leave-one-out),
                              Microsoft DNSMOS p808 (+ sig/bak/ovr), Sherpa-ONNX Citrinet-512
                              ASR checks, acoustic calibrations, and asset SHA-256 digests.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime
import hashlib
import itertools
import json
import math
import os
import re
import sys
from pathlib import Path
from typing import Any, Mapping, Sequence

import librosa
import numpy as np
import onnxruntime as ort
import scipy.signal
import soundfile as sf

# ---------------------------------------------------------------------------
# Frozen Architecture Constants (matching core-engine & core-verifier)
# ---------------------------------------------------------------------------

SYNTHESIS_SAMPLE_RATE_HZ: int = 44_100
VERIFIER_SAMPLE_RATE_HZ: int = 16_000
SPEAKER_EMBEDDING_DIM: int = 256
SPEAKER_CENTROID_BYTES: int = SPEAKER_EMBEDDING_DIM * 4  # 1,024 bytes
PCM_ALIGNMENT_BYTES: int = 64
PCM_ALIGNMENT_FLOATS: int = PCM_ALIGNMENT_BYTES // 4  # 16 floats

ROOM_IR_ACTIVE_TAPS: int = 529  # 529 / 44100 = 11.995 ms (~12.0 ms studio IR)
ROOM_IR_ALIGNED_TAPS: int = (
    ((ROOM_IR_ACTIVE_TAPS + PCM_ALIGNMENT_FLOATS - 1) // PCM_ALIGNMENT_FLOATS)
    * PCM_ALIGNMENT_FLOATS
)  # 544 floats = 2,176 bytes (64-byte aligned)

ROOM_TONE_TARGET_DBFS: float = -58.0
ROOM_TONE_DEFAULT_DURATION_SEC: float = 4.0  # 176,400 samples = 705,600 B (64-byte aligned)
ROOM_TONE_MIN_DURATION_SEC: float = 3.0

DEFAULT_VOICE_DIR = Path("/Users/mac/Downloads/clone/voice")
DEFAULT_MODELS_DIR = Path("/Users/mac/Downloads/voicestudio-models")
DEFAULT_BUNDLE_DIR = DEFAULT_MODELS_DIR / "bundles" / "v001"
DEFAULT_DATASET_MANIFEST = DEFAULT_MODELS_DIR / "dataset" / "manifest.jsonl"

DEFAULT_WESPEAKER_ONNX = (
    DEFAULT_MODELS_DIR / "wespeaker" / "wespeaker_en_voxceleb_resnet34.onnx"
)
DEFAULT_DNSMOS_P808_ONNX = DEFAULT_MODELS_DIR / "dnsmos" / "model_v8.onnx"
DEFAULT_DNSMOS_SIG_BAK_OVR_ONNX = DEFAULT_MODELS_DIR / "dnsmos" / "sig_bak_ovr.onnx"
DEFAULT_CITRINET_DIR = (
    DEFAULT_MODELS_DIR / "sherpa-ctc" / "sherpa-onnx-nemo-ctc-en-citrinet-512"
)

# Canonical clean solo seed files under /Users/mac/Downloads/clone/voice/.
# Excludes synthetic clone_test_mimo.wav, duplicate voice_ref_v2.wav (identical SHA-256
# to voice_ref.wav), noisy venue tour VID_20261025, raspy PXL_20261120, and multi-speaker lv_0.
PRIMARY_SOLO_SEED_RELPATHS: tuple[str, ...] = (
    "raw/PXL_20260925_191922074.wav",
    "raw/PXL_20261016_215422134.wav",
    "voice_ref_alt.wav",
    "voice_ref.wav",
)

ALL_EVAL_CLONE_VOICE_RELPATHS: tuple[str, ...] = (
    "raw/PXL_20260925_191922074.wav",
    "raw/PXL_20261016_215422134.wav",
    "raw/PXL_20261120_232709514.wav",
    "raw/VID_20261025_154029.wav",
    "raw/lv_0_20261116082307.wav",
    "voice_ref.wav",
    "voice_ref_v2.wav",
    "voice_ref_alt.wav",
    "voice_ref_v1_backup.wav",
)


# ---------------------------------------------------------------------------
# Binary / Math / Text Utilities
# ---------------------------------------------------------------------------


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def l2_normalize(vec: np.ndarray) -> np.ndarray:
    arr = np.asarray(vec, dtype=np.float64)
    if arr.ndim != 1 or arr.size == 0:
        raise ValueError(f"Expected non-empty 1D vector, got shape {arr.shape}")
    if not np.all(np.isfinite(arr)):
        raise ValueError("Cannot L2-normalize vector containing non-finite values")
    norm = float(np.linalg.norm(arr))
    if norm <= 1e-12:
        raise ValueError("Cannot L2-normalize zero-norm vector")
    return (arr / norm).astype(np.float32)


def cosine_similarity(a: np.ndarray, b: np.ndarray) -> float:
    va = np.asarray(a, dtype=np.float64).reshape(-1)
    vb = np.asarray(b, dtype=np.float64).reshape(-1)
    if va.size == 0 or va.size != vb.size:
        return 0.0
    if not (np.all(np.isfinite(va)) and np.all(np.isfinite(vb))):
        return 0.0
    denom = float(np.linalg.norm(va) * np.linalg.norm(vb))
    if denom <= 1e-12:
        return 0.0
    return float(np.clip(np.dot(va, vb) / denom, -1.0, 1.0))


def compute_rms_dbfs(pcm: np.ndarray) -> float:
    arr = np.asarray(pcm, dtype=np.float64).reshape(-1)
    if arr.size == 0:
        return -120.0
    rms = float(np.sqrt(np.mean(arr * arr)))
    if rms <= 1e-12 or not math.isfinite(rms):
        return -120.0
    return float(20.0 * math.log10(rms))


def normalize_transcript(text: str) -> str:
    """Exact Python mirror of `AudioDspMetrics.normalizeTranscript`."""
    out: list[str] = []
    last_was_space = True
    n = len(text)
    for i, ch in enumerate(text):
        if ch.isalnum():
            out.append(ch.lower())
            last_was_space = False
        elif (
            ch == "'"
            and out
            and not last_was_space
            and i + 1 < n
            and text[i + 1].isalpha()
        ):
            out.append("'")
            last_was_space = False
        elif ch.isspace() or ch == "-":
            if not last_was_space:
                out.append(" ")
                last_was_space = True
    while out and out[-1] == " ":
        out.pop()
    return "".join(out)


def compute_cer(expected_text: str, recognized_text: str) -> float:
    """Exact Python mirror of `AudioDspMetrics.computeCer` (Levenshtein CER)."""
    ref = normalize_transcript(expected_text)
    hyp = normalize_transcript(recognized_text)
    if not ref:
        return 0.0 if not hyp else 1.0
    if ref == hyp:
        return 0.0
    n = len(ref)
    m = len(hyp)
    prev = list(range(m + 1))
    curr = [0] * (m + 1)
    for i in range(1, n + 1):
        curr[0] = i
        rc = ref[i - 1]
        for j in range(1, m + 1):
            cost = 0 if rc == hyp[j - 1] else 1
            curr[j] = min(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
        prev, curr = curr, prev
    return float(prev[m]) / float(n)


def load_audio_mono(path: Path, target_sr: int | None = None) -> tuple[np.ndarray, int]:
    """Loads a WAV/FLAC audio file as contiguous float32 mono, optionally resampling."""
    data, sr = sf.read(str(path), dtype="float32", always_2d=False)
    if data.ndim > 1:
        data = data.mean(axis=1, dtype=np.float32)
    data = np.ascontiguousarray(data, dtype=np.float32)
    if target_sr is not None and sr != target_sr:
        data = resample_audio(data, sr, target_sr)
        sr = target_sr
    return data, int(sr)


def resample_audio(pcm: np.ndarray, src_sr: int, dst_sr: int) -> np.ndarray:
    """Deterministic polyphase rational resampler returning float32 mono."""
    arr = np.asarray(pcm, dtype=np.float32).reshape(-1)
    if arr.size == 0 or src_sr == dst_sr:
        return arr.copy()
    g = math.gcd(int(src_sr), int(dst_sr))
    up = int(dst_sr) // g
    down = int(src_sr) // g
    out = scipy.signal.resample_poly(arr.astype(np.float64), up, down)
    return np.ascontiguousarray(out, dtype=np.float32)


def resample_44k_to_16k_android_exact(
    pcm: np.ndarray,
    src_sr: int = SYNTHESIS_SAMPLE_RATE_HZ,
    dst_sr: int = VERIFIER_SAMPLE_RATE_HZ,
) -> np.ndarray:
    """Exact bit-for-bit algorithmic mirror of `AudioDspMetrics.resample44kTo16k`."""
    arr = np.asarray(pcm, dtype=np.float32).reshape(-1)
    if arr.size == 0 or src_sr == dst_sr:
        return arr.copy()
    ratio = float(src_sr) / float(dst_sr)
    dst_len = max(1, int(arr.size / ratio))
    half_k = 4
    cutoff = float(dst_sr) / float(src_sr)
    i = np.arange(dst_len, dtype=np.float64)
    src_pos = i * ratio
    center = src_pos.astype(np.int64)
    acc = np.zeros(dst_len, dtype=np.float64)
    wsum = np.zeros(dst_len, dtype=np.float64)
    n = arr.size
    for k in range(-half_k, half_k + 1):
        idx = center + k
        valid = (idx >= 0) & (idx < n)
        idx_c = np.clip(idx, 0, n - 1)
        x = src_pos - idx
        arg = x * cutoff
        safe_arg = np.where(np.abs(arg) < 1e-9, 1.0, arg)
        sinc = np.where(np.abs(arg) < 1e-9, 1.0, np.sin(np.pi * safe_arg) / (np.pi * safe_arg))
        win = 0.54 + 0.46 * np.cos((np.pi * x) / float(half_k + 1))
        w = np.where(valid, sinc * win, 0.0)
        acc += arr[idx_c].astype(np.float64) * w
        wsum += w
    safe_wsum = np.where(wsum > 1e-12, wsum, 1.0)
    out = np.where(wsum > 1e-12, acc / safe_wsum, 0.0)
    return np.ascontiguousarray(out, dtype=np.float32)


def trim_digital_silence(pcm: np.ndarray, threshold: float = 1e-5) -> np.ndarray:
    """Trims leading/trailing digital-zero padding without touching active audio."""
    nz = np.flatnonzero(np.abs(pcm) > threshold)
    if nz.size == 0:
        return pcm
    return pcm[int(nz[0]) : int(nz[-1]) + 1]


# ---------------------------------------------------------------------------
# Exact Android AudioDspMetrics.kt Log-Mel Filterbank (100% Feature Parity)
# ---------------------------------------------------------------------------


def compute_log_mel_filterbank(
    pcm16k: np.ndarray,
    *,
    sample_rate_hz: int = VERIFIER_SAMPLE_RATE_HZ,
    num_mel_bins: int = 80,
    frame_length_samples: int = 400,
    frame_shift_samples: int = 160,
    fft_size: int = 512,
    f_min_hz: float = 20.0,
    f_max_hz: float = 7600.0,
    apply_cmn: bool = True,
    apply_cmvn: bool = False,
    log_floor: float = 1e-10,
) -> np.ndarray:
    """Computes `[num_frames, num_mel_bins]` float32 log-Mel filterbank matching Android Kotlin.

    Mirrors `core-verifier/src/main/kotlin/com/voicestudio/verifier/AudioDspMetrics.kt`
    (`computeLogMelFilterbank`) exactly: Povey window, per-frame DC removal, 0.97 pre-emphasis,
    radix-2 power spectrum, triangular HTK Mel filterbank, natural log, and optional CMN/CMVN.
    """
    arr = np.asarray(pcm16k, dtype=np.float32).reshape(-1)
    if arr.size == 0:
        return np.zeros((0, num_mel_bins), dtype=np.float32)

    if arr.size < frame_length_samples:
        padded = np.zeros(frame_length_samples, dtype=np.float32)
        padded[: arr.size] = arr
    else:
        padded = arr

    num_frames = max(1, 1 + (padded.size - frame_length_samples) // frame_shift_samples)
    num_spec_bins = fft_size // 2 + 1

    idx = np.arange(frame_length_samples, dtype=np.float64)
    window = np.power(
        0.5 - 0.5 * np.cos((2.0 * np.pi * idx) / float(frame_length_samples - 1)),
        0.85,
    )

    def hz_to_mel(hz: float) -> float:
        return 2595.0 * math.log10(1.0 + hz / 700.0)

    def mel_to_hz(mel: np.ndarray) -> np.ndarray:
        return 700.0 * (np.power(10.0, mel / 2595.0) - 1.0)

    mel_min = hz_to_mel(float(f_min_hz))
    mel_max = hz_to_mel(min(float(f_max_hz), sample_rate_hz * 0.5))
    mel_pts = mel_to_hz(np.linspace(mel_min, mel_max, num_mel_bins + 2, dtype=np.float64))
    freqs = np.arange(num_spec_bins, dtype=np.float64) * (float(sample_rate_hz) / float(fft_size))

    filterbank = np.zeros((num_mel_bins, num_spec_bins), dtype=np.float64)
    for m in range(num_mel_bins):
        left = mel_pts[m]
        center = mel_pts[m + 1]
        right = mel_pts[m + 2]
        if center > left:
            up_mask = (freqs >= left) & (freqs <= center)
            filterbank[m, up_mask] = (freqs[up_mask] - left) / (center - left)
        if right > center:
            down_mask = (freqs > center) & (freqs <= right)
            filterbank[m, down_mask] = (right - freqs[down_mask]) / (right - center)

    # Strided frame matrix [num_frames, frame_length_samples]
    starts = np.arange(num_frames, dtype=np.int64) * frame_shift_samples
    offsets = np.arange(frame_length_samples, dtype=np.int64)[None, :]
    frames = padded[starts[:, None] + offsets].astype(np.float64)

    # Per-frame DC removal + 0.97 pre-emphasis matching AudioDspMetrics.kt lines 692-706
    frames -= frames.mean(axis=1, keepdims=True)
    emph = np.empty_like(frames)
    emph[:, 0] = frames[:, 0] * 0.03
    emph[:, 1:] = frames[:, 1:] - 0.97 * frames[:, :-1]
    emph *= window[None, :]

    spec = np.fft.rfft(emph, n=fft_size, axis=1)
    power_spec = spec.real * spec.real + spec.imag * spec.imag
    mel_energy = power_spec @ filterbank.T
    features = np.log(np.maximum(mel_energy, log_floor)).astype(np.float32)

    if apply_cmn and num_frames > 1:
        features -= features.mean(axis=0, keepdims=True)
        if apply_cmvn:
            features /= features.std(axis=0, keepdims=True) + 1e-5

    return np.ascontiguousarray(features, dtype=np.float32)


# ---------------------------------------------------------------------------
# Real ONNX Model Backends (WeSpeaker ResNet34, DNSMOS p808, Citrinet-512)
# ---------------------------------------------------------------------------


def _make_ort_session(model_path: Path, intra_threads: int = 4) -> ort.InferenceSession:
    if not model_path.is_file() or model_path.stat().st_size == 0:
        raise FileNotFoundError(f"Missing or empty ONNX model: {model_path}")
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = intra_threads
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(
        str(model_path),
        sess_options=opts,
        providers=["CPUExecutionProvider"],
    )


class WeSpeakerEngine:
    """Real ONNX Runtime WeSpeaker ResNet34 speaker embedding extractor.

    Uses exact Android `AudioDspMetrics.computeLogMelFilterbank` parameters
    (`f_min=20, f_max=7600, Povey window, 400/160/512, CMN`) so `master_centroid.bin`
    lives in the exact 256-D feature space evaluated on Android by `OrtWeSpeakerEngine`.
    """

    def __init__(self, model_path: Path = DEFAULT_WESPEAKER_ONNX) -> None:
        self.model_path = Path(model_path)
        self._session = _make_ort_session(self.model_path)
        self._input_name = self._session.get_inputs()[0].name
        self._output_name = self._session.get_outputs()[0].name

    def extract_embedding(self, pcm16k: np.ndarray) -> np.ndarray:
        audio = trim_digital_silence(np.asarray(pcm16k, dtype=np.float32).reshape(-1))
        if audio.size < 400:
            raise ValueError(f"Audio too short for WeSpeaker embedding: {audio.size} samples")
        feats = compute_log_mel_filterbank(
            audio,
            sample_rate_hz=VERIFIER_SAMPLE_RATE_HZ,
            num_mel_bins=80,
            frame_length_samples=400,
            frame_shift_samples=160,
            fft_size=512,
            f_min_hz=20.0,
            f_max_hz=7600.0,
            apply_cmn=True,
            apply_cmvn=False,
        )
        out = self._session.run(
            [self._output_name],
            {self._input_name: feats[None, :, :]},
        )[0]
        raw = np.asarray(out, dtype=np.float32).reshape(-1)
        if raw.size != SPEAKER_EMBEDDING_DIM:
            raise RuntimeError(
                f"Expected {SPEAKER_EMBEDDING_DIM}-dim WeSpeaker embedding, got {raw.size}"
            )
        return l2_normalize(raw)


class DnsmosP808Engine:
    """Real Microsoft DNSMOS p808 (`model_v8.onnx`) + optional `sig_bak_ovr.onnx` scorer.

    Matches official Microsoft DNS-Challenge `dnsmos_local.py` preprocessing:
      - 16 kHz mono audio, cyclically tiled to >= 9.01 s (144,160 samples)
      - 9.01 s sliding window with 1.0 s hop
      - `librosa.feature.melspectrogram(y=seg[:-160], sr=16000, n_fft=321, hop_length=160, n_mels=120)`
      - `(librosa.power_to_db(mel, ref=np.max) + 40.0) / 40.0` -> `[1, 900, 120]` float32
    """

    INPUT_LENGTH_SEC: float = 9.01
    INPUT_SAMPLES: int = int(9.01 * VERIFIER_SAMPLE_RATE_HZ)  # 144,160
    TARGET_FRAMES: int = 900
    MEL_BINS: int = 120

    # Official Microsoft DNS-Challenge polynomial calibration for sig_bak_ovr.onnx
    _POLY_OVR = np.poly1d([-0.06766283, 1.11546468, 0.04602535])
    _POLY_SIG = np.poly1d([-0.08397278, 1.22083953, 0.0052439])
    _POLY_BAK = np.poly1d([-0.13166888, 1.60915514, -0.39604546])

    def __init__(
        self,
        p808_model_path: Path = DEFAULT_DNSMOS_P808_ONNX,
        sig_bak_ovr_path: Path | None = DEFAULT_DNSMOS_SIG_BAK_OVR_ONNX,
    ) -> None:
        self.p808_model_path = Path(p808_model_path)
        self._p808_session = _make_ort_session(self.p808_model_path, intra_threads=2)
        self._p808_in = self._p808_session.get_inputs()[0].name
        self._p808_out = self._p808_session.get_outputs()[0].name

        self._sig_session: ort.InferenceSession | None = None
        if sig_bak_ovr_path is not None and Path(sig_bak_ovr_path).is_file():
            self._sig_session = _make_ort_session(Path(sig_bak_ovr_path), intra_threads=2)
            self._sig_in = self._sig_session.get_inputs()[0].name
            self._sig_out = self._sig_session.get_outputs()[0].name

    def _prepare_audio(self, pcm16k: np.ndarray) -> np.ndarray:
        audio = trim_digital_silence(np.asarray(pcm16k, dtype=np.float32).reshape(-1))
        if audio.size == 0:
            raise ValueError("Cannot score empty/silent audio with DNSMOS p808")
        if audio.size < self.INPUT_SAMPLES:
            reps = (self.INPUT_SAMPLES + audio.size - 1) // audio.size
            audio = np.tile(audio, reps)[: self.INPUT_SAMPLES]
        return audio

    def score_p808(self, pcm16k: np.ndarray) -> float:
        audio = self._prepare_audio(pcm16k)
        hop = VERIFIER_SAMPLE_RATE_HZ
        num_hops = max(1, int(math.floor(audio.size / float(VERIFIER_SAMPLE_RATE_HZ)) - 9.01) + 1)
        scores: list[float] = []
        for idx in range(num_hops):
            start = int(idx * hop)
            end = start + self.INPUT_SAMPLES
            if end > audio.size:
                break
            seg = audio[start:end]
            mel = librosa.feature.melspectrogram(
                y=seg[:-160],
                sr=VERIFIER_SAMPLE_RATE_HZ,
                n_fft=321,
                hop_length=160,
                n_mels=self.MEL_BINS,
            )
            log_mel = (librosa.power_to_db(mel, ref=np.max) + 40.0) / 40.0
            feat = log_mel.T[: self.TARGET_FRAMES, :].astype(np.float32)
            if feat.shape[0] < self.TARGET_FRAMES:
                pad_rows = self.TARGET_FRAMES - feat.shape[0]
                feat = np.pad(feat, ((0, pad_rows), (0, 0)), mode="edge")
            out = self._p808_session.run(
                [self._p808_out],
                {self._p808_in: feat[None, :, :]},
            )[0]
            val = float(np.asarray(out).reshape(-1)[0])
            if not math.isfinite(val):
                raise RuntimeError("DNSMOS p808 returned non-finite MOS score")
            scores.append(val)
        return float(np.mean(scores))

    def score_sig_bak_ovr(self, pcm16k: np.ndarray) -> dict[str, float] | None:
        if self._sig_session is None:
            return None
        audio = self._prepare_audio(pcm16k)
        hop = VERIFIER_SAMPLE_RATE_HZ
        num_hops = max(1, int(math.floor(audio.size / float(VERIFIER_SAMPLE_RATE_HZ)) - 9.01) + 1)
        sigs: list[float] = []
        baks: list[float] = []
        ovrs: list[float] = []
        for idx in range(num_hops):
            start = int(idx * hop)
            end = start + self.INPUT_SAMPLES
            if end > audio.size:
                break
            seg = audio[start:end]
            out = self._sig_session.run(
                [self._sig_out],
                {self._sig_in: seg[None, :].astype(np.float32)},
            )[0][0]
            sigs.append(float(self._POLY_SIG(float(out[0]))))
            baks.append(float(self._POLY_BAK(float(out[1]))))
            ovrs.append(float(self._POLY_OVR(float(out[2]))))
        return {
            "sig": float(np.mean(sigs)),
            "bak": float(np.mean(baks)),
            "ovr": float(np.mean(ovrs)),
        }


class CitrinetCtcEngine:
    """Real Sherpa-ONNX NeMo Citrinet-512 CTC ASR engine (`model.int8.onnx` + `tokens.txt`)."""

    def __init__(self, model_dir: Path = DEFAULT_CITRINET_DIR) -> None:
        self.model_dir = Path(model_dir)
        model_path = self.model_dir / "model.int8.onnx"
        if not model_path.is_file():
            model_path = self.model_dir / "model.onnx"
        tokens_path = self.model_dir / "tokens.txt"
        if not tokens_path.is_file():
            raise FileNotFoundError(f"Missing tokens.txt in {self.model_dir}")

        self.model_path = model_path
        self._session = _make_ort_session(model_path)
        self._sig_name = self._session.get_inputs()[0].name
        self._len_name = self._session.get_inputs()[1].name
        self._out_name = self._session.get_outputs()[0].name

        self.vocab: dict[int, str] = {}
        self.blank_id: int = 1024
        for raw_line in tokens_path.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line:
                continue
            sym, idx_str = line.rsplit(" ", 1)
            idx = int(idx_str)
            self.vocab[idx] = sym
            if sym in ("<blk>", "<blank>", "<eps>"):
                self.blank_id = idx

    def transcribe(self, pcm16k: np.ndarray) -> str:
        audio = trim_digital_silence(np.asarray(pcm16k, dtype=np.float32).reshape(-1))
        if audio.size < 400:
            return ""
        # Per-feature CMVN matching NeMo Citrinet-512 metadata (`normalize_type: per_feature`)
        # and sherpa-onnx `test.py`.
        feats = compute_log_mel_filterbank(
            audio * 32768.0,
            sample_rate_hz=VERIFIER_SAMPLE_RATE_HZ,
            num_mel_bins=80,
            frame_length_samples=400,
            frame_shift_samples=160,
            fft_size=512,
            f_min_hz=20.0,
            f_max_hz=7600.0,
            apply_cmn=True,
            apply_cmvn=True,
            log_floor=1.1920929e-7,
        )
        # Channel-first [B=1, C=80, T]
        audio_signal = np.ascontiguousarray(feats.T[None, :, :], dtype=np.float32)
        length = np.array([audio_signal.shape[2]], dtype=np.int64)
        logprobs = self._session.run(
            [self._out_name],
            {self._sig_name: audio_signal, self._len_name: length},
        )[0]
        frame_ids = np.argmax(logprobs[0], axis=-1).tolist()
        collapsed = [k for k, _ in itertools.groupby(frame_ids) if k != self.blank_id]
        raw_text = "".join(self.vocab.get(i, "") for i in collapsed)
        return raw_text.replace("▁", " ").strip()


# ---------------------------------------------------------------------------
# Atomic Aligned PCM & Centroid Writers
# ---------------------------------------------------------------------------


def write_master_centroid_bin(output_path: Path, centroid: np.ndarray) -> dict[str, Any]:
    """Atomically writes exact 256 IEEE-754 little-endian float32s (1,024 bytes)."""
    normed = l2_normalize(centroid)
    if normed.shape != (SPEAKER_EMBEDDING_DIM,):
        raise ValueError(
            f"Expected ({SPEAKER_EMBEDDING_DIM},) centroid, got shape {normed.shape}"
        )
    payload = np.ascontiguousarray(normed, dtype="<f4").tobytes()
    if len(payload) != SPEAKER_CENTROID_BYTES:
        raise AssertionError(
            f"Centroid byte size mismatch: {len(payload)} != {SPEAKER_CENTROID_BYTES}"
        )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = output_path.with_name(f".{output_path.name}.tmp.{os.getpid()}")
    try:
        tmp.write_bytes(payload)
        os.replace(tmp, output_path)
    finally:
        if tmp.exists():
            tmp.unlink(missing_ok=True)
    return {
        "path": str(output_path),
        "size_bytes": len(payload),
        "dim": SPEAKER_EMBEDDING_DIM,
        "l2_norm": float(np.linalg.norm(normed)),
        "sha256": sha256_file(output_path),
    }


def write_aligned_pcm_44k(output_path: Path, samples: np.ndarray) -> dict[str, Any]:
    """Atomically writes headerless 44.1 kHz little-endian float32 PCM, 64-byte aligned.

    Mirrors `WriteAlignedPcmFile` in `core-engine/src/main/cpp/dsp_mastering.cc` and
    `AudioDspMetrics.writeAlignedPcmFile` in Kotlin: appends trailing 0.0f samples when
    needed so `file_size_bytes % 64 == 0`.
    """
    arr = np.asarray(samples, dtype=np.float32).reshape(-1)
    if arr.size == 0:
        raise ValueError("Refusing to write empty PCM buffer")
    if not np.all(np.isfinite(arr)):
        raise ValueError("Refusing to write non-finite values to PCM file")

    raw_samples = int(arr.size)
    rem = raw_samples % PCM_ALIGNMENT_FLOATS
    pad_floats = (PCM_ALIGNMENT_FLOATS - rem) if rem != 0 else 0
    if pad_floats > 0:
        aligned = np.concatenate([arr, np.zeros(pad_floats, dtype=np.float32)])
    else:
        aligned = arr

    payload = np.ascontiguousarray(aligned, dtype="<f4").tobytes()
    assert len(payload) % PCM_ALIGNMENT_BYTES == 0

    output_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = output_path.with_name(f".{output_path.name}.tmp.{os.getpid()}")
    try:
        tmp.write_bytes(payload)
        os.replace(tmp, output_path)
    finally:
        if tmp.exists():
            tmp.unlink(missing_ok=True)

    return {
        "path": str(output_path),
        "active_samples": raw_samples,
        "aligned_samples": int(aligned.size),
        "padding_samples": pad_floats,
        "size_bytes": len(payload),
        "aligned_64b": (len(payload) % PCM_ALIGNMENT_BYTES) == 0,
        "duration_sec": float(aligned.size) / float(SYNTHESIS_SAMPLE_RATE_HZ),
        "sha256": sha256_file(output_path),
    }


def read_pcm_44k(path: Path) -> np.ndarray:
    raw = path.read_bytes()
    if len(raw) % 4 != 0:
        raise ValueError(f"PCM file byte length not divisible by 4: {len(raw)}")
    return np.frombuffer(raw, dtype="<f4").copy()


# ---------------------------------------------------------------------------
# Seed & Refined Master Centroid Computation
# ---------------------------------------------------------------------------


def compute_seed_centroid(
    voice_dir: Path,
    wespeaker: WeSpeakerEngine,
) -> tuple[np.ndarray, list[dict[str, Any]]]:
    """Computes Ryan's verified seed speaker centroid from clean solo seed recordings.

    Deduplicates identical files by SHA-256 and strictly uses real solo recordings of Ryan
    (`raw/PXL_20260925_191922074.wav`, `raw/PXL_20261016_215422134.wav`,
    `voice_ref_alt.wav`, `voice_ref.wav`).
    """
    seen_hashes: set[str] = set()
    embeddings: list[np.ndarray] = []
    seed_records: list[dict[str, Any]] = []

    for rel in PRIMARY_SOLO_SEED_RELPATHS:
        wav_path = voice_dir / rel
        if not wav_path.is_file():
            continue
        digest = sha256_file(wav_path)
        if digest in seen_hashes:
            continue
        seen_hashes.add(digest)
        pcm16k, _ = load_audio_mono(wav_path, target_sr=VERIFIER_SAMPLE_RATE_HZ)
        emb = wespeaker.extract_embedding(pcm16k)
        embeddings.append(emb)
        seed_records.append(
            {
                "file": rel,
                "sha256": digest,
                "duration_sec": float(pcm16k.size) / float(VERIFIER_SAMPLE_RATE_HZ),
            }
        )

    if not embeddings:
        raise FileNotFoundError(
            f"No verified solo seed WAVs found under {voice_dir}; expected {PRIMARY_SOLO_SEED_RELPATHS}"
        )

    centroid = l2_normalize(np.mean(np.stack(embeddings, axis=0), axis=0))
    for rec, emb in zip(seed_records, embeddings):
        rec["seed_cosine"] = cosine_similarity(emb, centroid)
    return centroid, seed_records


def compute_refined_master_centroid(
    seed_embeddings: list[tuple[str, np.ndarray, float]],
    mined_embeddings: list[tuple[str, np.ndarray, float, float]] | None = None,
    *,
    max_mined_contributors: int = 40,
    min_mined_cosine: float = 0.78,
    min_mined_dnsmos: float = 2.95,
) -> tuple[np.ndarray, dict[str, Any]]:
    """Refines Ryan's 256-D master centroid using clean seeds + highest-quality verified segments.

    Each item in `seed_embeddings` is `(label, emb_256, dnsmos_p808)`.
    Each item in `mined_embeddings` is `(label, emb_256, dnsmos_p808, seed_cos)`.

    Returns `(master_centroid, metrics_summary)` including exact self-similarity cosine and
    leave-one-out cosine across all contributing embeddings.
    """
    contributors: list[tuple[str, np.ndarray, float, str]] = [
        (lbl, l2_normalize(emb), float(mos), "seed")
        for lbl, emb, mos in seed_embeddings
    ]

    if mined_embeddings:
        # Select highest-quality verified mined utterances (ranked by DNSMOS + speaker cohesion)
        eligible = [
            item
            for item in mined_embeddings
            if item[3] >= min_mined_cosine and item[2] >= min_mined_dnsmos
        ]
        # If strict threshold yields fewer than 10, gracefully relax DNSMOS to top high-cosine clips
        if len(eligible) < 10:
            eligible = [item for item in mined_embeddings if item[3] >= min_mined_cosine]
        eligible.sort(key=lambda x: (x[2] + 0.5 * x[3]), reverse=True)
        for lbl, emb, mos, _ in eligible[:max_mined_contributors]:
            contributors.append((lbl, l2_normalize(emb), float(mos), "mined"))

    if not contributors:
        raise ValueError("Cannot compute master centroid with zero contributing embeddings")

    mat = np.stack([c[1].astype(np.float64) for c in contributors], axis=0)
    master = l2_normalize(mat.mean(axis=0))

    # Compute exact per-contributor cosine to master AND leave-one-out (LOO) cosine
    n = len(contributors)
    sum_all = mat.sum(axis=0)
    self_cosines: list[float] = []
    loo_cosines: list[float] = []
    contributor_details: list[dict[str, Any]] = []
    for i, (lbl, emb, mos, origin) in enumerate(contributors):
        c_self = cosine_similarity(emb, master)
        self_cosines.append(c_self)
        if n > 1:
            loo_vec = l2_normalize((sum_all - mat[i]) / float(n - 1))
            c_loo = cosine_similarity(emb, loo_vec)
            loo_cosines.append(c_loo)
        else:
            c_loo = c_self
        contributor_details.append(
            {
                "id": lbl,
                "origin": origin,
                "dnsmos_p808": round(mos, 4),
                "cosine_to_master": round(c_self, 5),
                "leave_one_out_cosine": round(c_loo, 5),
            }
        )

    metrics = {
        "num_contributors": n,
        "num_seed_contributors": sum(1 for c in contributors if c[3] == "seed"),
        "num_mined_contributors": sum(1 for c in contributors if c[3] == "mined"),
        "mean_self_cosine": float(np.mean(self_cosines)),
        "min_self_cosine": float(np.min(self_cosines)),
        "mean_leave_one_out_cosine": float(np.mean(loo_cosines)) if loo_cosines else 1.0,
        "min_leave_one_out_cosine": float(np.min(loo_cosines)) if loo_cosines else 1.0,
        "contributors": contributor_details,
    }
    return master, metrics


# ---------------------------------------------------------------------------
# Real Room Tone Bed Extraction (-58.0 dBFS, 44.1 kHz, Seamlessly Looped)
# ---------------------------------------------------------------------------


def extract_room_tone_44k(
    source_wavs: Sequence[Path],
    *,
    target_duration_sec: float = ROOM_TONE_DEFAULT_DURATION_SEC,
    target_dbfs: float = ROOM_TONE_TARGET_DBFS,
    sample_rate_hz: int = SYNTHESIS_SAMPLE_RATE_HZ,
) -> tuple[np.ndarray, dict[str, Any]]:
    """Extracts a >=3.0s seamlessly looped quiet room tone bed from real pauses in Ryan's clips.

    Constitutional Law 2: ZERO synthetic/random noise! Every sample comes directly from real
    low-energy non-speech pauses in Ryan's actual recordings, stitched with equal-power
    raised-cosine crossfades and circular wraparound crossfade, then calibrated to exact
    `-58.0 dBFS` RMS.
    """
    if target_duration_sec < ROOM_TONE_MIN_DURATION_SEC:
        raise ValueError(
            f"target_duration_sec must be >= {ROOM_TONE_MIN_DURATION_SEC}s, got {target_duration_sec}"
        )

    # Round target sample count to an exact 64-byte (16-float) boundary.
    raw_target_samples = int(round(target_duration_sec * sample_rate_hz))
    target_samples = (
        ((raw_target_samples + PCM_ALIGNMENT_FLOATS - 1) // PCM_ALIGNMENT_FLOATS)
        * PCM_ALIGNMENT_FLOATS
    )

    # Design gentle 30 Hz highpass to remove sub-sonic mic/handling DC drift while keeping real room air.
    hp_sos = scipy.signal.butter(2, 30.0, btype="highpass", fs=sample_rate_hz, output="sos")

    win = int(round(0.050 * sample_rate_hz))  # 50 ms analysis frame
    hop = int(round(0.025 * sample_rate_hz))  # 25 ms hop
    min_pause_samples = int(round(0.18 * sample_rate_hz))  # >= 180 ms continuous pause

    # Collect genuine quiet pause spans across all source WAVs: (score, rms_db, wav_name, segment_array)
    pause_candidates: list[tuple[float, float, str, np.ndarray]] = []

    for wav_path in source_wavs:
        if not wav_path.is_file():
            continue
        pcm, _ = load_audio_mono(wav_path, target_sr=sample_rate_hz)
        if pcm.size < win * 4:
            continue

        # Remove DC and sub-sonic drift first
        filtered = scipy.signal.sosfiltfilt(hp_sos, pcm.astype(np.float64)).astype(np.float32)

        n_frames = 1 + (filtered.size - win) // hop
        frame_rms = np.empty(n_frames, dtype=np.float64)
        frame_zcr = np.empty(n_frames, dtype=np.float64)
        for i in range(n_frames):
            seg = filtered[i * hop : i * hop + win]
            frame_rms[i] = math.sqrt(float(np.mean(seg.astype(np.float64) ** 2)))
            frame_zcr[i] = float(np.mean((seg[:-1] * seg[1:]) < 0.0))

        frame_db = 20.0 * np.log10(np.maximum(frame_rms, 1e-9))
        nonzero = frame_rms > 1e-6
        if int(np.sum(nonzero)) < 8:
            continue

        nz_db = frame_db[nonzero]
        speech_p85 = float(np.percentile(nz_db, 85))
        floor_p15 = float(np.percentile(nz_db, 15))
        # Quiet non-speech frames: well below speech level and genuinely non-zero ambient air
        quiet_thresh = min(speech_p85 - 16.0, floor_p15 + 5.5)
        quiet_mask = nonzero & (frame_db <= quiet_thresh) & (frame_db >= -78.0)

        # Find contiguous runs of quiet frames
        idx = 0
        while idx < n_frames:
            if not quiet_mask[idx]:
                idx += 1
                continue
            start_f = idx
            while idx < n_frames and quiet_mask[idx]:
                idx += 1
            end_f = idx
            # Trim 1 frame from edges of interior pauses to avoid speech onset/offset bleed
            if start_f > 0 and (end_f - start_f) >= 6:
                start_f += 1
            if end_f < n_frames and (end_f - start_f) >= 6:
                end_f -= 1
            s0 = start_f * hop
            s1 = min(filtered.size, (end_f - 1) * hop + win)
            if s1 - s0 < min_pause_samples:
                continue

            span = filtered[s0:s1].copy()
            span -= float(np.mean(span))
            span_rms = math.sqrt(float(np.mean(span.astype(np.float64) ** 2)))
            if span_rms <= 1e-6:
                continue
            span_db = 20.0 * math.log10(span_rms)
            # Check stationarity (low frame-to-frame RMS variance & low crest factor = clean room bed)
            sub_db = frame_db[start_f:end_f]
            db_std = float(np.std(sub_db))
            crest = float(np.max(np.abs(span))) / max(span_rms, 1e-9)
            if crest > 7.5 or db_std > 5.0:
                continue
            # Prefer quieter, steadier, longer real pauses
            dur_s = float(span.size) / float(sample_rate_hz)
            quality_score = -span_db - 2.0 * db_std - 0.5 * crest + 3.0 * min(dur_s, 1.5)
            pause_candidates.append((quality_score, span_db, wav_path.name, span))

    if not pause_candidates:
        raise RuntimeError(
            "Failed to find real non-zero quiet room pauses across Ryan's recordings"
        )

    # Sort best genuine room pauses first (quietest, steadiest, non-impulsive)
    pause_candidates.sort(key=lambda item: item[0], reverse=True)
    selected = pause_candidates[: min(24, len(pause_candidates))]

    # Normalize each selected pause to unit RMS before stitching so crossfades stay perfectly even
    norm_spans: list[np.ndarray] = []
    contributing_files: list[str] = []
    source_pause_dbs: list[float] = []
    for _, span_db, fname, span in selected:
        rms = math.sqrt(float(np.mean(span.astype(np.float64) ** 2)))
        norm_spans.append((span / max(rms, 1e-12)).astype(np.float64))
        if fname not in contributing_files:
            contributing_files.append(fname)
        source_pause_dbs.append(span_db)

    # Equal-power raised-cosine crossfade stitch until we exceed target_samples + loop_xfade
    xfade_len = int(round(0.080 * sample_rate_hz))  # 80 ms equal-power crossfade
    loop_xfade = int(round(0.120 * sample_rate_hz))  # 120 ms circular loop wrap crossfade
    needed_len = target_samples + loop_xfade

    stitched = norm_spans[0].copy()
    cursor = 1
    while stitched.size < needed_len:
        nxt = norm_spans[cursor % len(norm_spans)]
        cursor += 1
        # If cycling back over the same span, flip phase or shift by half to avoid periodic comb beats
        if cursor > len(norm_spans):
            shift = (cursor * 1337) % max(1, nxt.size // 2)
            nxt = np.roll(nxt, shift)
        xf = min(xfade_len, stitched.size // 3, nxt.size // 3)
        if xf < 16:
            stitched = np.concatenate([stitched, nxt])
        else:
            t = np.linspace(0.0, 0.5 * np.pi, xf, endpoint=False, dtype=np.float64)
            fade_out = np.cos(t)
            fade_in = np.sin(t)
            overlap = stitched[-xf:] * fade_out + nxt[:xf] * fade_in
            stitched = np.concatenate([stitched[:-xf], overlap, nxt[xf:]])

    # Circular wraparound equal-power crossfade so room_tone[-1] -> room_tone[0] is click-free!
    head = stitched[:target_samples].copy()
    tail_extra = stitched[target_samples : target_samples + loop_xfade]
    t_loop = np.linspace(0.0, 0.5 * np.pi, loop_xfade, endpoint=False, dtype=np.float64)
    # At sample 0: weight = tail_extra (cos(0)=1) -> smoothly transitions into head (sin(pi/2)=1)
    head[:loop_xfade] = tail_extra * np.cos(t_loop) + head[:loop_xfade] * np.sin(t_loop)

    # Remove residual DC and calibrate RMS PRECISELY to -58.0 dBFS
    head -= float(np.mean(head))
    cur_rms = math.sqrt(float(np.mean(head * head)))
    target_rms = 10.0 ** (target_dbfs / 20.0)
    calibrated = (head * (target_rms / max(cur_rms, 1e-12))).astype(np.float32)

    measured_dbfs = compute_rms_dbfs(calibrated)
    seam_jump = float(abs(float(calibrated[0]) - float(calibrated[-1])))
    peak_amp = float(np.max(np.abs(calibrated)))

    stats = {
        "source_pause_count": len(selected),
        "contributing_files": contributing_files,
        "source_pause_rms_dbfs_range": [
            round(float(min(source_pause_dbs)), 2),
            round(float(max(source_pause_dbs)), 2),
        ],
        "target_rms_dbfs": float(target_dbfs),
        "measured_rms_dbfs": round(measured_dbfs, 5),
        "peak_amplitude": round(peak_amp, 6),
        "loop_seam_delta": round(seam_jump, 7),
        "samples": int(calibrated.size),
        "duration_sec": round(float(calibrated.size) / float(sample_rate_hz), 6),
    }
    return calibrated, stats


# ---------------------------------------------------------------------------
# Real Studio Room Impulse Response Calibration (12 ms / 529 taps @ 44.1 kHz)
# ---------------------------------------------------------------------------


def _levinson_durbin(r: np.ndarray, order: int) -> np.ndarray:
    """Solves Toeplitz Yule-Walker equations for LPC coefficients `[1, a_1, ..., a_p]`."""
    a = np.zeros(order + 1, dtype=np.float64)
    a[0] = 1.0
    if r[0] <= 1e-12:
        return a
    err = float(r[0])
    for k in range(1, order + 1):
        acc = float(r[k])
        for j in range(1, k):
            acc += a[j] * float(r[k - j])
        refl = -acc / max(err, 1e-12)
        refl = max(-0.99, min(0.99, refl))
        prev = a.copy()
        a[k] = refl
        for j in range(1, k):
            a[j] = prev[j] + refl * prev[k - j]
        err *= max(1e-6, 1.0 - refl * refl)
    return a


def derive_room_ir_44k(
    source_wavs: Sequence[Path],
    *,
    num_active_taps: int = ROOM_IR_ACTIVE_TAPS,
    sample_rate_hz: int = SYNTHESIS_SAMPLE_RATE_HZ,
) -> tuple[np.ndarray, dict[str, Any]]:
    """Derives a 12 ms (529-tap) studio room IR calibrated directly from Ryan's recordings.

    Acoustic derivation from Ryan's real speech recordings at 44.1 kHz:
      1. Computes 24th-order LPC inverse-filtered residuals across active speech frames to
         whiten the vocal-tract spectral envelope, then averages short-lag normalized
         autocorrelation across lags `1.5 ms .. 11.5 ms` (`66 .. 507` samples) to extract
         the recording room's actual early-reflection timing and relative energy profile.
      2. Measures real mid-band (500-3500 Hz) vs high-band (4000-12000 Hz) energy ratio
         to calibrate wall/air HF absorption damping (`alpha_hf`).
      3. Constructs a strictly causal, smooth-decaying 529-sample impulse response:
         - Dominant direct path at `n = 0` (`h[0] > 0`)
         - Measured early reflections in `1.5 ms .. 11.5 ms` scaled by the measured room
           reflection density and exponentially damped with smooth Hann end-taper over the
           last 1.5 ms so there is zero truncation discontinuity at sample 528.
         - Normalized to exact unit L2 energy gain (`sum(h^2) == 1.0`).
    """
    lpc_order = 24
    frame_len = 2048
    frame_hop = 1024
    max_lag = num_active_taps

    ac_accum = np.zeros(max_lag, dtype=np.float64)
    ac_weight = 0.0
    mid_energy_sum = 0.0
    hi_energy_sum = 0.0
    used_files: list[str] = []

    hann = np.hanning(frame_len)
    freqs = np.fft.rfftfreq(frame_len, d=1.0 / float(sample_rate_hz))
    mid_mask = (freqs >= 500.0) & (freqs <= 3500.0)
    hi_mask = (freqs >= 4000.0) & (freqs <= 12000.0)

    for wav_path in source_wavs:
        if not wav_path.is_file():
            continue
        pcm, _ = load_audio_mono(wav_path, target_sr=sample_rate_hz)
        if pcm.size < frame_len * 2:
            continue
        used_files.append(wav_path.name)
        n_frames = 1 + (pcm.size - frame_len) // frame_hop
        # Limit frames per file for fast deterministic execution while sampling whole recording
        step = max(1, n_frames // 120)
        for fi in range(0, n_frames, step):
            seg = pcm[fi * frame_hop : fi * frame_hop + frame_len].astype(np.float64)
            seg -= np.mean(seg)
            rms = math.sqrt(float(np.mean(seg * seg)))
            if rms < 0.015:  # Active speech frames only
                continue
            wseg = seg * hann
            spec = np.fft.rfft(wseg)
            pwr = spec.real * spec.real + spec.imag * spec.imag
            mid_energy_sum += float(np.sum(pwr[mid_mask]))
            hi_energy_sum += float(np.sum(pwr[hi_mask]))

            # Autocorrelation via FFT for LPC whitening
            r_full = np.fft.irfft(pwr, n=frame_len)
            if r_full[0] <= 1e-10:
                continue
            a = _levinson_durbin(r_full[: lpc_order + 1], lpc_order)
            residual = scipy.signal.lfilter(a, [1.0], wseg)
            res_spec = np.fft.rfft(residual, n=frame_len * 2)
            res_ac = np.fft.irfft(
                res_spec.real * res_spec.real + res_spec.imag * res_spec.imag,
                n=frame_len * 2,
            )[:max_lag]
            if res_ac[0] > 1e-10:
                ac_accum += res_ac / res_ac[0]
                ac_weight += 1.0

    if ac_weight <= 0.0:
        raise RuntimeError(
            "No active speech frames found in source recordings to calibrate room IR"
        )

    mean_ac = ac_accum / ac_weight
    hf_to_mid_ratio = hi_energy_sum / max(mid_energy_sum, 1e-12)
    # Calibrate 1-pole HF absorption factor in [0.18, 0.45] directly from Ryan's spectral ratio
    hf_damping = float(np.clip(0.38 - 0.5 * hf_to_mid_ratio, 0.18, 0.45))

    # Measure direct-to-early-reflection strength from lags 1.5 ms .. 11.5 ms
    min_refl_lag = int(round(0.0015 * sample_rate_hz))  # 66 samples (~1.5 ms)
    max_refl_lag = min(num_active_taps - 16, int(round(0.0115 * sample_rate_hz)))
    early_slice = mean_ac[min_refl_lag:max_refl_lag].copy()

    # Remove smooth pitch-harmonic trend using a local median baseline so sharp room reflections stand out
    kernel_size = 21
    baseline = scipy.signal.medfilt(early_slice, kernel_size=kernel_size)
    refl_profile = early_slice - baseline

    # Calibrate overall reflection wet-to-dry ratio from measured early-reflection energy
    measured_refl_rms = float(np.sqrt(np.mean(refl_profile * refl_profile)))
    # Keep studio IR tight & broadcast-clean: early reflections carry 2%–8% of total energy
    target_tail_energy_frac = float(np.clip(0.02 + 0.8 * measured_refl_rms, 0.025, 0.075))

    # Build causal 529-tap IR:
    # Direct path at tap 0, measured reflection structure + exponential decay envelope in [min_refl_lag..528]
    tail = np.zeros(num_active_taps, dtype=np.float64)
    n_idx = np.arange(num_active_taps, dtype=np.float64)
    # Decay time constant (~3.6 ms -> -29 dB over 12 ms for tight vocal booth / studio room)
    tau_samples = 0.0036 * float(sample_rate_hz)
    decay_env = np.exp(-(np.maximum(0.0, n_idx - min_refl_lag)) / tau_samples)

    # Populate early-reflection taps directly from the measured whitened autocorrelation residual
    tail[min_refl_lag:max_refl_lag] = refl_profile * decay_env[min_refl_lag:max_refl_lag]

    # Apply causal 1-pole wall-absorption low-pass filter to reflections (realistic high-freq damping)
    tail = scipy.signal.lfilter([1.0 - hf_damping], [1.0, -hf_damping], tail)
    tail[:min_refl_lag] = 0.0

    # Smooth cosine taper over the final 1.5 ms so IR reaches exact 0.0 at tap 528 (zero truncation click)
    taper_len = int(round(0.0015 * sample_rate_hz))
    taper = 0.5 * (1.0 + np.cos(np.linspace(0.0, np.pi, taper_len, dtype=np.float64)))
    tail[-taper_len:] *= taper
    tail[-1] = 0.0

    # Combine direct impulse (tap 0) + calibrated reflection tail with exact unit L2 energy gain!
    tail_energy = float(np.sum(tail * tail))
    if tail_energy <= 1e-12:
        raise RuntimeError("Measured reflection profile had zero energy")
    scaled_tail = tail * math.sqrt(target_tail_energy_frac / tail_energy)
    direct_amp = math.sqrt(1.0 - target_tail_energy_frac)

    ir = scaled_tail.copy()
    ir[0] = direct_amp

    # Final exact L2 normalization (sum(h^2) == 1.0)
    ir = (ir / math.sqrt(float(np.sum(ir * ir)))).astype(np.float32)

    # Verify non-delta, causal, unit-energy invariants
    total_energy = float(np.sum(ir.astype(np.float64) ** 2))
    tail_energy_final = float(np.sum(ir[1:].astype(np.float64) ** 2))
    peak_tap = int(np.argmax(np.abs(ir)))
    # Top 5 measured reflection delays in ms
    top_refl_indices = np.argsort(np.abs(ir[min_refl_lag:]))[-5:][::-1] + min_refl_lag
    top_refl_ms = [round(float(i) * 1000.0 / float(sample_rate_hz), 2) for i in top_refl_indices]

    stats = {
        "active_taps": int(num_active_taps),
        "duration_ms": round(float(num_active_taps) * 1000.0 / float(sample_rate_hz), 3),
        "contributing_files": used_files,
        "frames_analyzed": int(ac_weight),
        "measured_hf_to_mid_ratio": round(hf_to_mid_ratio, 5),
        "calibrated_hf_damping": round(hf_damping, 5),
        "direct_tap_0": round(float(ir[0]), 6),
        "peak_tap_index": peak_tap,
        "unit_l2_energy": round(total_energy, 7),
        "early_reflection_energy_fraction": round(tail_energy_final, 5),
        "early_to_direct_db": round(
            10.0 * math.log10(max(tail_energy_final, 1e-12) / max(float(ir[0]) ** 2, 1e-12)),
            2,
        ),
        "top_reflection_delays_ms": top_refl_ms,
        "end_tap_value": float(ir[-1]),
    }
    return ir, stats


# ---------------------------------------------------------------------------
# Deterministic IPA Lexicon Builder (matching ClauseNormalizer.kt)
# ---------------------------------------------------------------------------

# Verified General American IPA dictionary matching `ClauseNormalizer.kt`'s IPA conventions.
CURATED_PROPER_AND_DOMAIN_IPA: dict[str, str] = {
    # Proper nouns & distinct terms from Ryan's recordings (`asr_transcripts.json` & device videos)
    "Matthew": "ˈmæθjuː",
    "Matt": "mæt",
    "Swish": "swɪʃ",
    "Ranya": "ˈrɑːnjə",
    "Rania": "ˈrɑːnjə",
    "Ronnie": "ˈrɑːni",
    "Ryan": "ˈraɪən",
    "Rob": "rɑːb",
    "Tolmo": "ˈtoʊlmoʊ",
    "J. Cole": "dʒeɪ koʊl",
    "J Cole": "dʒeɪ koʊl",
    "Cole": "koʊl",
    "IoT": "aɪ oʊ ˈtiː",
    "hackathon": "ˈhækəθɑːn",
    "hackathons": "ˈhækəθɑːnz",
    "deadass": "ˈdɛdˌæs",
    "rollerblading": "ˈroʊlərˌbleɪdɪŋ",
    "lizard": "ˈlɪzərd",
    "voicemail": "ˈvɔɪsmeɪl",
    "November": "noʊˈvɛmbər",
    "October": "ɑːkˈtoʊbər",
    "September": "sɛpˈtɛmbər",
    "August": "ˈɔːɡəst",
    "July": "dʒuˈlaɪ",
    "interview": "ˈɪntərvjuː",
    "venue": "ˈvɛnjuː",
    "coffee": "ˈkɔːfi",
    "gonna": "ˈɡʌnə",
    "gotta": "ˈɡɑːtə",
    "wanna": "ˈwɑːnə",
    "kinda": "ˈkaɪndə",
    "fam": "fæm",
    "bro": "broʊ",
    "yo": "joʊ",
    "cousin": "ˈkʌzən",
    "cousin's": "ˈkʌzənz",
    # Proper nouns & tech terms discovered in Ryan's mined Mac + Pixel videos
    "MacEIP": "mæk iː aɪ ˈpiː",
    "Huawei": "ˈhwɑːweɪ",
    "Xiaomi": "ˈʃaʊmi",
    "Dimensity": "daɪˈmɛnsɪti",
    "Titan": "ˈtaɪtən",
    "TPU": "tiː piː ˈjuː",
    "Nano": "ˈnænoʊ",
    "Hermes": "ˈhɜːrmiːz",
    "MCP": "ɛm siː ˈpiː",
    "Muse": "mjuːz",
    "passkey": "ˈpæskiː",
    "TLS": "tiː ɛl ˈɛs",
    "Notary": "ˈnoʊtəri",
    "iPhone": "ˈaɪfoʊn",
    "Berlin": "bɜːrˈlɪn",
    "Sarah": "ˈsɛərə",
    "Finnerty": "ˈfɪnərti",
    "Marcus": "ˈmɑːrkəs",
    "Brownlee": "ˈbraʊnli",
    "flagship": "ˈflæɡʃɪp",
    "flagships": "ˈflæɡʃɪps",
    "conformer": "kənˈfɔːrmər",
    "modals": "ˈmoʊdəlz",
    # Aircheck / VoiceStudio / Audio & ML engineering terms
    "Aircheck": "ˈɛərˌtʃɛk",
    "Aircheck Studio": "ˈɛərˌtʃɛk ˈstuːdioʊ",
    "VoiceStudio": "ˈvɔɪsˌstuːdioʊ",
    "bare-metal": "bɛər ˈmɛtəl",
    "s2-pro": "ɛs tuː proʊ",
    "S2-Pro": "ɛs tuː proʊ",
    "FishAudio": "fɪʃ ˈɔːdioʊ",
    "Qwen": "kwɛn",
    "Qwen3": "kwɛn θriː",
    "GGUF": "dʒiː dʒiː juː ˈɛf",
    "LoRA": "ˈlɔːrə",
    "LiteRT": "laɪt ɑːr ˈtiː",
    "ONNX": "ˈɑːnɪks",
    "WeSpeaker": "wiː ˈspiːkər",
    "ResNet": "ˈrɛznɛt",
    "ResNet34": "ˈrɛznɛt ˈθɜːrti fɔːr",
    "DNSMOS": "diː ɛn ɛs ˈmɑːs",
    "p808": "piː eɪt oʊ eɪt",
    "Citrinet": "ˈsɪtrɪnɛt",
    "Sherpa": "ˈʃɜːrpə",
    "sherpa-onnx": "ˈʃɜːrpə ˈɑːnɪks",
    "Oboe": "ˈoʊboʊ",
    "NEON": "ˈniːɑːn",
    "LUFS": "lʌfs",
    "dBFS": "diː biː ɛf ˈɛs",
    "Tailscale": "ˈteɪlskeɪl",
    "WireGuard": "ˈwaɪərɡɑːrd",
    "Tinker": "ˈtɪŋkər",
    "OPSD": "oʊ piː ɛs ˈdiː",
    "GRPO": "dʒiː ɑːr piː ˈoʊ",
    "CUDA": "ˈkuːdə",
    "PyTorch": "ˈpaɪtɔːrtʃ",
    "Whisper": "ˈwɪspər",
    "ffmpeg": "ɛf ɛf ɛm ˈpɛɡ",
    "FFmpeg": "ɛf ɛf ɛm ˈpɛɡ",
    "Android": "ˈændrɔɪd",
    "Kotlin": "ˈkɑːtlɪn",
    "Pixel": "ˈpɪksəl",
    "Gradle": "ˈɡreɪdəl",
    "CMake": "siː ˈmeɪk",
    "GitHub": "ˈɡɪthʌb",
    "Gemini": "ˈdʒɛmɪnaɪ",
    "Claude": "klɔːd",
    "OpenAI": "ˈoʊpən eɪ ˈaɪ",
    "NVIDIA": "ɛnˈvɪdiə",
    "Qualcomm": "ˈkwɑːlkɑːm",
    "Snapdragon": "ˈsnæpˌdræɡən",
    "Apple": "ˈæpəl",
    "macOS": "mæk oʊ ˈɛs",
    "iOS": "aɪ oʊ ˈɛs",
    "Linux": "ˈlɪnəks",
    "Python": "ˈpaɪθɑːn",
    "JSON": "ˈdʒeɪsɑːn",
    "JSONL": "ˈdʒeɪsɑːn ˈɛl",
    "NDJSON": "ɛn diː ˈdʒeɪsɑːn",
    "SQLite": "ˈsiːkwəlaɪt",
    "API": "eɪ piː ˈaɪ",
    "CLI": "siː ɛl ˈaɪ",
    "CPU": "siː piː ˈjuː",
    "GPU": "dʒiː piː ˈjuː",
    "NPU": "ɛn piː ˈjuː",
    "DSP": "diː ɛs ˈpiː",
    "PCM": "piː siː ˈɛm",
    "DAC": "dæk",
    "ADC": "eɪ diː ˈsiː",
    "ASR": "eɪ ɛs ˈɑːr",
    "TTS": "tiː tiː ˈɛs",
    "CTC": "siː tiː ˈsiː",
    "CER": "siː iː ˈɑːr",
    "WER": "dʌbəljuː iː ˈɑːr",
    "VAD": "viː eɪ ˈdiː",
    "OTA": "oʊ tiː ˈeɪ",
    "EDL": "iː diː ˈɛl",
    "IPA": "aɪ piː ˈeɪ",
    "G2P": "dʒiː tuː ˈpiː",
    "JNI": "dʒeɪ ɛn ˈaɪ",
    "NDK": "ɛn diː ˈkeɪ",
    "SDK": "ɛs diː ˈkeɪ",
    "APK": "eɪ piː ˈkeɪ",
    "USB": "juː ɛs ˈbiː",
    "HTTP": "eɪtʃ tiː tiː ˈpiː",
    "HTTPS": "eɪtʃ tiː tiː piː ˈɛs",
    "URL": "juː ɑːr ˈɛl",
    "SHA": "ʃɑː",
    "RAM": "ræm",
    "WAV": "weɪv",
    "MP4": "ɛm piː ˈfɔːr",
    "FFT": "ɛf ɛf ˈtiː",
    "RMS": "ɑːr ɛm ˈɛs",
    "FPS": "ɛf piː ˈɛs",
    "UI": "juː ˈaɪ",
    "UX": "juː ˈɛks",
    "AI": "eɪ ˈaɪ",
    "ML": "ɛm ˈɛl",
    "LLM": "ɛl ɛl ˈɛm",
    "TTS": "tiː tiː ˈɛs",
    "vocoder": "voʊˈkoʊdər",
    "quantized": "ˈkwɑːntaɪzd",
    "prosody": "ˈprɑːsədi",
    "phoneme": "ˈfoʊniːm",
    "phonemes": "ˈfoʊniːmz",
    "waveform": "ˈweɪvfɔːrm",
    "crossfade": "ˈkrɔːsfeɪd",
    "centroid": "ˈsɛntrɔɪd",
    "cosine": "ˈkoʊsaɪn",
    "latency": "ˈleɪtənsi",
    "clipper": "ˈklɪpər",
    "vernier": "ˈvɜːrniər",
    "timbre": "ˈtæmbər",
    "cadence": "ˈkeɪdəns",
    "telemetry": "təˈlɛmɪtri",
    "pipeline": "ˈpaɪplaɪn",
    "tokenizer": "ˈtoʊkənaɪzər",
    "embedding": "ɪmˈbɛdɪŋ",
    "embeddings": "ɪmˈbɛdɪŋz",
    "spectrogram": "ˈspɛktrəɡræm",
    "convolution": "ˌkɑːnvəˈluːʃən",
    "biquad": "ˈbaɪkwɑːd",
    "decibel": "ˈdɛsɪbɛl",
    "decibels": "ˈdɛsɪbɛlz",
    "kilohertz": "ˈkɪləhɜːrts",
    "hertz": "hɜːrts",
    "millisecond": "ˈmɪlɪˌsɛkənd",
    "milliseconds": "ˈmɪlɪˌsɛkəndz",
}

HIGH_FREQUENCY_ENGLISH_IPA: dict[str, str] = {
    "about": "əˈbaʊt",
    "actually": "ˈæktʃuəli",
    "after": "ˈæftər",
    "again": "əˈɡɛn",
    "all": "ɔːl",
    "almost": "ˈɔːlmoʊst",
    "also": "ˈɔːlsoʊ",
    "always": "ˈɔːlweɪz",
    "am": "æm",
    "an": "æn",
    "and": "ænd",
    "another": "əˈnʌðər",
    "any": "ˈɛni",
    "anything": "ˈɛniθɪŋ",
    "are": "ɑːr",
    "around": "əˈraʊnd",
    "as": "æz",
    "at": "æt",
    "audio": "ˈɔːdioʊ",
    "awesome": "ˈɔːsəm",
    "back": "bæk",
    "bad": "bæd",
    "be": "biː",
    "beautiful": "ˈbjuːtɪfəl",
    "because": "bɪˈkɔːz",
    "been": "bɪn",
    "before": "bɪˈfɔːr",
    "believe": "bɪˈliːv",
    "best": "bɛst",
    "better": "ˈbɛtər",
    "big": "bɪɡ",
    "both": "boʊθ",
    "boys": "bɔɪz",
    "bring": "brɪŋ",
    "broadcast": "ˈbrɔːdkæst",
    "build": "bɪld",
    "building": "ˈbɪldɪŋ",
    "but": "bʌt",
    "by": "baɪ",
    "call": "kɔːl",
    "camera": "ˈkæmrə",
    "can": "kæn",
    "cannot": "ˈkænɑːt",
    "check": "tʃɛk",
    "clean": "kliːn",
    "clip": "klɪp",
    "close": "kloʊs",
    "cloud": "klaʊd",
    "code": "koʊd",
    "come": "kʌm",
    "comes": "kʌmz",
    "coming": "ˈkʌmɪŋ",
    "company": "ˈkʌmpəni",
    "cool": "kuːl",
    "could": "kʊd",
    "crazy": "ˈkreɪzi",
    "crew": "kruː",
    "data": "ˈdeɪtə",
    "day": "deɪ",
    "days": "deɪz",
    "demo": "ˈdɛmoʊ",
    "design": "dɪˈzaɪn",
    "device": "dɪˈvaɪs",
    "did": "dɪd",
    "different": "ˈdɪfrənt",
    "do": "duː",
    "does": "dʌz",
    "doing": "ˈduːɪŋ",
    "done": "dʌn",
    "down": "daʊn",
    "each": "iːtʃ",
    "easy": "ˈiːzi",
    "eight": "eɪt",
    "eleven": "ɪˈlɛvən",
    "else": "ɛls",
    "end": "ɛnd",
    "engine": "ˈɛndʒɪn",
    "enough": "ɪˈnʌf",
    "even": "ˈiːvən",
    "ever": "ˈɛvər",
    "every": "ˈɛvri",
    "everything": "ˈɛvriθɪŋ",
    "fast": "fæst",
    "feel": "fiːl",
    "feeling": "ˈfiːlɪŋ",
    "few": "fjuː",
    "file": "faɪl",
    "files": "faɪlz",
    "finally": "ˈfaɪnəli",
    "find": "faɪnd",
    "first": "fɜːrst",
    "five": "faɪv",
    "food": "fuːd",
    "for": "fɔːr",
    "four": "fɔːr",
    "from": "frʌm",
    "full": "fʊl",
    "get": "ɡɛt",
    "getting": "ˈɡɛtɪŋ",
    "give": "ɡɪv",
    "go": "ɡoʊ",
    "going": "ˈɡoʊɪŋ",
    "good": "ɡʊd",
    "got": "ɡɑːt",
    "great": "ɡreɪt",
    "guy": "ɡaɪ",
    "guys": "ɡaɪz",
    "had": "hæd",
    "has": "hæz",
    "have": "hæv",
    "having": "ˈhævɪŋ",
    "he": "hiː",
    "hear": "hɪər",
    "hello": "həˈloʊ",
    "help": "hɛlp",
    "her": "hɜːr",
    "here": "hɪər",
    "hey": "heɪ",
    "high": "haɪ",
    "him": "hɪm",
    "his": "hɪz",
    "home": "hoʊm",
    "hope": "hoʊp",
    "hour": "ˈaʊər",
    "hours": "ˈaʊərz",
    "how": "haʊ",
    "hundred": "ˈhʌndrəd",
    "idea": "aɪˈdiːə",
    "if": "ɪf",
    "important": "ɪmˈpɔːrtənt",
    "in": "ɪn",
    "into": "ˈɪntuː",
    "is": "ɪz",
    "it": "ɪt",
    "its": "ɪts",
    "job": "dʒɑːb",
    "just": "dʒʌst",
    "keep": "kiːp",
    "kind": "kaɪnd",
    "know": "noʊ",
    "last": "læst",
    "later": "ˈleɪtər",
    "leave": "liːv",
    "leaving": "ˈliːvɪŋ",
    "left": "lɛft",
    "let": "lɛt",
    "life": "laɪf",
    "like": "laɪk",
    "line": "laɪn",
    "listen": "ˈlɪsən",
    "little": "ˈlɪtəl",
    "live": "laɪv",
    "local": "ˈloʊkəl",
    "location": "loʊˈkeɪʃən",
    "long": "lɔːŋ",
    "look": "lʊk",
    "looking": "ˈlʊkɪŋ",
    "lot": "lɑːt",
    "love": "lʌv",
    "luck": "lʌk",
    "mail": "meɪl",
    "make": "meɪk",
    "making": "ˈmeɪkɪŋ",
    "man": "mæn",
    "many": "ˈmɛni",
    "maybe": "ˈmeɪbi",
    "me": "miː",
    "mean": "miːn",
    "message": "ˈmɛsɪdʒ",
    "mic": "maɪk",
    "microphone": "ˈmaɪkrəfoʊn",
    "might": "maɪt",
    "million": "ˈmɪljən",
    "minute": "ˈmɪnɪt",
    "minutes": "ˈmɪnɪts",
    "model": "ˈmɑːdəl",
    "models": "ˈmɑːdəlz",
    "money": "ˈmʌni",
    "more": "mɔːr",
    "morning": "ˈmɔːrnɪŋ",
    "most": "moʊst",
    "move": "muːv",
    "much": "mʌtʃ",
    "music": "ˈmjuːzɪk",
    "my": "maɪ",
    "name": "neɪm",
    "natural": "ˈnætʃərəl",
    "need": "niːd",
    "never": "ˈnɛvər",
    "new": "nuː",
    "next": "nɛkst",
    "nice": "naɪs",
    "night": "naɪt",
    "nine": "naɪn",
    "no": "noʊ",
    "noise": "nɔɪz",
    "not": "nɑːt",
    "nothing": "ˈnʌθɪŋ",
    "now": "naʊ",
    "number": "ˈnʌmbər",
    "of": "ʌv",
    "off": "ɔːf",
    "oh": "oʊ",
    "okay": "oʊˈkeɪ",
    "old": "oʊld",
    "on": "ɑːn",
    "once": "wʌns",
    "one": "wʌn",
    "only": "ˈoʊnli",
    "open": "ˈoʊpən",
    "or": "ɔːr",
    "other": "ˈʌðər",
    "our": "aʊər",
    "out": "aʊt",
    "over": "ˈoʊvər",
    "own": "oʊn",
    "part": "pɑːrt",
    "people": "ˈpiːpəl",
    "percent": "pərˈsɛnt",
    "perfect": "ˈpɜːrfɪkt",
    "phone": "foʊn",
    "place": "pleɪs",
    "play": "pleɪ",
    "please": "pliːz",
    "point": "pɔɪnt",
    "pretty": "ˈprɪti",
    "probably": "ˈprɑːbəbli",
    "problem": "ˈprɑːbləm",
    "project": "ˈprɑːdʒɛkt",
    "put": "pʊt",
    "quality": "ˈkwɑːlɪti",
    "quick": "kwɪk",
    "quiet": "ˈkwaɪət",
    "quite": "kwaɪt",
    "real": "riːəl",
    "really": "ˈrɪəli",
    "record": "rɪˈkɔːrd",
    "recording": "rɪˈkɔːrdɪŋ",
    "remember": "rɪˈmɛmbər",
    "right": "raɪt",
    "room": "ruːm",
    "run": "rʌn",
    "running": "ˈrʌnɪŋ",
    "said": "sɛd",
    "same": "seɪm",
    "say": "seɪ",
    "saying": "ˈseɪɪŋ",
    "screen": "skriːn",
    "second": "ˈsɛkənd",
    "seconds": "ˈsɛkəndz",
    "see": "siː",
    "seven": "ˈsɛvən",
    "she": "ʃiː",
    "should": "ʃʊd",
    "show": "ʃoʊ",
    "side": "saɪd",
    "signal": "ˈsɪɡnəl",
    "singing": "ˈsɪŋɪŋ",
    "six": "sɪks",
    "sixteen": "ˌsɪksˈtiːn",
    "sixteenth": "ˌsɪksˈtiːnθ",
    "small": "smɔːl",
    "so": "soʊ",
    "some": "sʌm",
    "somebody": "ˈsʌmbɑːdi",
    "someone": "ˈsʌmwʌn",
    "something": "ˈsʌmθɪŋ",
    "soon": "suːn",
    "sorry": "ˈsɑːri",
    "sound": "saʊnd",
    "sounding": "ˈsaʊndɪŋ",
    "sounds": "saʊndz",
    "speaker": "ˈspiːkər",
    "speech": "spiːtʃ",
    "start": "stɑːrt",
    "started": "ˈstɑːrtɪd",
    "still": "stɪl",
    "stop": "stɑːp",
    "studio": "ˈstuːdioʊ",
    "stuff": "stʌf",
    "super": "ˈsuːpər",
    "sure": "ʃʊər",
    "system": "ˈsɪstəm",
    "take": "teɪk",
    "taking": "ˈteɪkɪŋ",
    "talk": "tɔːk",
    "talking": "ˈtɔːkɪŋ",
    "team": "tiːm",
    "tell": "tɛl",
    "ten": "tɛn",
    "test": "tɛst",
    "testing": "ˈtɛstɪŋ",
    "text": "tɛkst",
    "than": "ðæn",
    "thank": "θæŋk",
    "thanks": "θæŋks",
    "that": "ðæt",
    "the": "ðə",
    "their": "ðɛər",
    "them": "ðɛm",
    "then": "ðɛn",
    "there": "ðɛər",
    "these": "ðiːz",
    "they": "ðeɪ",
    "thing": "θɪŋ",
    "things": "θɪŋz",
    "think": "θɪŋk",
    "thinking": "ˈθɪŋkɪŋ",
    "this": "ðɪs",
    "those": "ðoʊz",
    "though": "ðoʊ",
    "thought": "θɔːt",
    "thousand": "ˈθaʊzənd",
    "three": "θriː",
    "through": "θruː",
    "time": "taɪm",
    "times": "taɪmz",
    "to": "tuː",
    "today": "təˈdeɪ",
    "together": "təˈɡɛðər",
    "tomorrow": "təˈmɑːroʊ",
    "tonight": "təˈnaɪt",
    "too": "tuː",
    "top": "tɑːp",
    "tour": "tʊər",
    "track": "træk",
    "try": "traɪ",
    "trying": "ˈtraɪɪŋ",
    "turn": "tɜːrn",
    "twelve": "twɛlv",
    "twenty": "ˈtwɛnti",
    "two": "tuː",
    "under": "ˈʌndər",
    "understand": "ˌʌndərˈstænd",
    "up": "ʌp",
    "update": "ˈʌpdeɪt",
    "us": "ʌs",
    "use": "juːz",
    "using": "ˈjuːzɪŋ",
    "version": "ˈvɜːrʒən",
    "very": "ˈvɛri",
    "video": "ˈvɪdioʊ",
    "voice": "vɔɪs",
    "wait": "weɪt",
    "walk": "wɔːk",
    "walking": "ˈwɔːkɪŋ",
    "want": "wɑːnt",
    "was": "wʌz",
    "watch": "wɑːtʃ",
    "way": "weɪ",
    "we": "wiː",
    "week": "wiːk",
    "well": "wɛl",
    "went": "wɛnt",
    "were": "wɜːr",
    "what": "wʌt",
    "when": "wɛn",
    "where": "wɛər",
    "which": "wɪtʃ",
    "while": "waɪl",
    "who": "huː",
    "whole": "hoʊl",
    "why": "waɪ",
    "will": "wɪl",
    "wish": "wɪʃ",
    "with": "wɪð",
    "without": "wɪðˈaʊt",
    "word": "wɜːrd",
    "words": "wɜːrdz",
    "work": "wɜːrk",
    "working": "ˈwɜːrkɪŋ",
    "world": "wɜːrld",
    "would": "wʊd",
    "wrapper": "ˈræpər",
    "write": "raɪt",
    "yeah": "jæ",
    "year": "jɪər",
    "years": "jɪərz",
    "yes": "jɛs",
    "yesterday": "ˈjɛstərdeɪ",
    "yet": "jɛt",
    "you": "juː",
    "your": "jʊər",
    "zero": "ˈzɪəroʊ",
}


def build_lexicon_json(
    output_path: Path,
    *,
    asr_transcripts_path: Path | None = None,
    dataset_manifest_path: Path | None = None,
) -> dict[str, Any]:
    """Builds deterministic flat `{"term": "IPA"}` `lexicon.json` matching `ClauseNormalizer.kt`.

    Reads `asr_transcripts.json` and (if present) `dataset/manifest.jsonl` to ensure coverage
    of Ryan's actual spoken vocabulary, proper nouns, studio/tech terms, and high-frequency
    English IPA entries.
    """
    lexicon: dict[str, str] = {}
    lexicon.update(HIGH_FREQUENCY_ENGLISH_IPA)
    lexicon.update(CURATED_PROPER_AND_DOMAIN_IPA)

    # Scan transcripts for exact casing matches or observed contractions/tokens
    transcript_texts: list[str] = []
    if asr_transcripts_path is not None and asr_transcripts_path.is_file():
        data = json.loads(asr_transcripts_path.read_text(encoding="utf-8"))
        clips = data.get("clips", {})
        if isinstance(clips, Mapping):
            transcript_texts.extend(str(v) for v in clips.values())

    if dataset_manifest_path is not None and dataset_manifest_path.is_file():
        for line in dataset_manifest_path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
                txt = str(rec.get("transcript", "")).strip()
                if txt:
                    transcript_texts.append(txt)
            except json.JSONDecodeError:
                continue

    # Check coverage of observed words from Ryan's transcripts
    lower_lookup = {k.lower(): v for k, v in lexicon.items()}
    observed_words: set[str] = set()
    covered_words: set[str] = set()
    word_re = re.compile(r"[A-Za-z][A-Za-z'’-]*")
    for text in transcript_texts:
        for tok in word_re.findall(text):
            clean = tok.replace("’", "'")
            if clean.upper() == "BLANK_AUDIO":
                continue
            observed_words.add(clean.lower())
            if clean in lexicon or clean.lower() in lower_lookup:
                covered_words.add(clean.lower())

    sorted_lexicon = dict(sorted(lexicon.items(), key=lambda kv: (kv[0].lower(), kv[0])))
    serialized = json.dumps(sorted_lexicon, indent=2, ensure_ascii=False) + "\n"

    output_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = output_path.with_name(f".{output_path.name}.tmp.{os.getpid()}")
    try:
        tmp.write_text(serialized, encoding="utf-8")
        os.replace(tmp, output_path)
    finally:
        if tmp.exists():
            tmp.unlink(missing_ok=True)

    return {
        "path": str(output_path),
        "entry_count": len(sorted_lexicon),
        "size_bytes": output_path.stat().st_size,
        "sha256": sha256_file(output_path),
        "transcript_corpus_count": len(transcript_texts),
        "observed_unique_words": len(observed_words),
        "covered_unique_words": len(covered_words),
    }


# ---------------------------------------------------------------------------
# Full Enrollment Pipeline CLI
# ---------------------------------------------------------------------------


def run_enrollment(
    *,
    voice_dir: Path = DEFAULT_VOICE_DIR,
    bundle_dir: Path = DEFAULT_BUNDLE_DIR,
    dataset_manifest_path: Path | None = DEFAULT_DATASET_MANIFEST,
    wespeaker_onnx: Path = DEFAULT_WESPEAKER_ONNX,
    dnsmos_p808_onnx: Path = DEFAULT_DNSMOS_P808_ONNX,
    dnsmos_sig_bak_ovr_onnx: Path | None = DEFAULT_DNSMOS_SIG_BAK_OVR_ONNX,
    citrinet_dir: Path = DEFAULT_CITRINET_DIR,
) -> dict[str, Any]:
    """Runs complete real ONNX enrollment and writes all OTA v001 bundle assets."""
    bundle_dir.mkdir(parents=True, exist_ok=True)

    wespeaker = WeSpeakerEngine(wespeaker_onnx)
    dnsmos = DnsmosP808Engine(dnsmos_p808_onnx, dnsmos_sig_bak_ovr_onnx)
    citrinet = CitrinetCtcEngine(citrinet_dir)

    asr_transcripts_path = voice_dir / "asr_transcripts.json"
    asr_ref_map: dict[str, str] = {}
    if asr_transcripts_path.is_file():
        asr_ref_map = json.loads(asr_transcripts_path.read_text(encoding="utf-8")).get("clips", {})

    # 1. Compute seed centroid on Ryan's verified clean solo seed recordings
    seed_centroid, seed_records = compute_seed_centroid(voice_dir, wespeaker)

    # Evaluate ALL recordings in /Users/mac/Downloads/clone/voice/ with all 3 real ONNX models
    seed_embeddings: list[tuple[str, np.ndarray, float]] = []
    seen_seed_hashes: set[str] = set()
    clone_clip_metrics: list[dict[str, Any]] = []

    for rel in ALL_EVAL_CLONE_VOICE_RELPATHS:
        wav_path = voice_dir / rel
        if not wav_path.is_file():
            continue
        digest = sha256_file(wav_path)
        pcm16k, _ = load_audio_mono(wav_path, target_sr=VERIFIER_SAMPLE_RATE_HZ)
        emb = wespeaker.extract_embedding(pcm16k)
        p808 = dnsmos.score_p808(pcm16k)
        sbo = dnsmos.score_sig_bak_ovr(pcm16k)
        ctc_text = citrinet.transcribe(pcm16k)

        stem = wav_path.stem
        ref_text = asr_ref_map.get(stem)
        cer_vs_whisper = compute_cer(ref_text, ctc_text) if ref_text else None

        is_primary_seed = rel in PRIMARY_SOLO_SEED_RELPATHS and digest not in seen_seed_hashes
        if is_primary_seed:
            seen_seed_hashes.add(digest)
            seed_embeddings.append((rel, emb, p808))

        clone_clip_metrics.append(
            {
                "file": rel,
                "sha256": digest,
                "duration_sec": round(float(pcm16k.size) / float(VERIFIER_SAMPLE_RATE_HZ), 3),
                "used_as_seed": is_primary_seed,
                "seed_cosine": round(cosine_similarity(emb, seed_centroid), 5),
                "dnsmos_p808": round(p808, 4),
                "dnsmos_sig_bak_ovr": (
                    {k: round(v, 4) for k, v in sbo.items()} if sbo is not None else None
                ),
                "citrinet_asr": ctc_text,
                "whisper_reference": ref_text,
                "citrinet_cer_vs_whisper": (
                    round(cer_vs_whisper, 4) if cer_vs_whisper is not None else None
                ),
                "_emb": emb,
            }
        )

    # 2. Load verified mined dataset embeddings (if dataset/manifest.jsonl exists)
    mined_embeddings: list[tuple[str, np.ndarray, float, float]] = []
    mined_wav_paths: list[Path] = []
    mined_dataset_summary: dict[str, Any] | None = None

    if dataset_manifest_path is not None and dataset_manifest_path.is_file():
        manifest_records: list[dict[str, Any]] = []
        for raw_line in dataset_manifest_path.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line:
                continue
            manifest_records.append(json.loads(line))

        # Rank mined records by DNSMOS + cosine and embed top 60 for master centroid refinement
        ranked = sorted(
            manifest_records,
            key=lambda r: (float(r.get("dnsmos_p808", 0.0)) + 0.5 * float(r.get("wespeaker_cos", 0.0))),
            reverse=True,
        )
        for rec in ranked[:60]:
            wpath = Path(rec["wav_44k"])
            if not wpath.is_file():
                continue
            pcm16k, _ = load_audio_mono(wpath, target_sr=VERIFIER_SAMPLE_RATE_HZ)
            emb = wespeaker.extract_embedding(pcm16k)
            mined_embeddings.append(
                (
                    str(rec["id"]),
                    emb,
                    float(rec["dnsmos_p808"]),
                    float(rec["wespeaker_cos"]),
                )
            )
            mined_wav_paths.append(wpath)

        total_dur = sum(float(r.get("duration_sec", 0.0)) for r in manifest_records)
        mined_dataset_summary = {
            "manifest_path": str(dataset_manifest_path),
            "num_utterances": len(manifest_records),
            "total_duration_sec": round(total_dur, 2),
            "total_duration_min": round(total_dur / 60.0, 2),
            "mean_wespeaker_cos": (
                round(float(np.mean([float(r["wespeaker_cos"]) for r in manifest_records])), 5)
                if manifest_records
                else 0.0
            ),
            "mean_dnsmos_p808": (
                round(float(np.mean([float(r["dnsmos_p808"]) for r in manifest_records])), 4)
                if manifest_records
                else 0.0
            ),
            "unique_source_videos": len({str(r.get("source_video", "")) for r in manifest_records}),
        }

    # 3. Compute refined 256-D master centroid & write master_centroid.bin
    master_centroid, centroid_metrics = compute_refined_master_centroid(
        seed_embeddings=seed_embeddings,
        mined_embeddings=mined_embeddings if mined_embeddings else None,
    )
    master_centroid_info = write_master_centroid_bin(
        bundle_dir / "master_centroid.bin",
        master_centroid,
    )

    # Record per-clip cosine vs final master_centroid.bin (and strip transient _emb vectors)
    for rec in clone_clip_metrics:
        emb = rec.pop("_emb")
        rec["master_centroid_cosine"] = round(cosine_similarity(emb, master_centroid), 5)

    # Also measure Android-exact resampler parity on voice_ref.wav vs polyphase resample
    parity_check: dict[str, Any] = {}
    ref_wav = voice_dir / "voice_ref.wav"
    if ref_wav.is_file():
        pcm44k, _ = load_audio_mono(ref_wav, target_sr=SYNTHESIS_SAMPLE_RATE_HZ)
        pcm16_poly = resample_audio(pcm44k, SYNTHESIS_SAMPLE_RATE_HZ, VERIFIER_SAMPLE_RATE_HZ)
        pcm16_kt = resample_44k_to_16k_android_exact(pcm44k)
        emb_poly = wespeaker.extract_embedding(pcm16_poly)
        emb_kt = wespeaker.extract_embedding(pcm16_kt)
        parity_check = {
            "clip": "voice_ref.wav",
            "polyphase_vs_android_sinc_cosine": round(cosine_similarity(emb_poly, emb_kt), 6),
            "android_sinc_vs_master_centroid_cosine": round(
                cosine_similarity(emb_kt, master_centroid), 5
            ),
        }

    # 4. Extract real room tone bed (-58.0 dBFS, 44.1 kHz, 64-byte aligned)
    room_tone_sources: list[Path] = [
        voice_dir / "raw" / "PXL_20260925_191922074.wav",
        voice_dir / "raw" / "PXL_20261016_215422134.wav",
    ] + mined_wav_paths[:15]
    room_tone_pcm, room_tone_stats = extract_room_tone_44k(room_tone_sources)
    room_tone_file_info = write_aligned_pcm_44k(bundle_dir / "room_tone_44k.pcm", room_tone_pcm)

    # 5. Calibrate real 12ms studio Room IR (529 active taps, padded to 544 / 2,176 B 64B-aligned)
    room_ir_sources: list[Path] = [
        voice_dir / "raw" / "PXL_20261016_215422134.wav",
        voice_dir / "raw" / "PXL_20260925_191922074.wav",
        voice_dir / "voice_ref.wav",
    ] + mined_wav_paths[:10]
    room_ir_pcm, room_ir_stats = derive_room_ir_44k(room_ir_sources)
    room_ir_file_info = write_aligned_pcm_44k(bundle_dir / "room_ir_44k.pcm", room_ir_pcm)

    # 6. Build deterministic lexicon.json matching ClauseNormalizer.kt
    lexicon_info = build_lexicon_json(
        bundle_dir / "lexicon.json",
        asr_transcripts_path=asr_transcripts_path,
        dataset_manifest_path=dataset_manifest_path,
    )

    # 7. Write enrollment_report.json
    report: dict[str, Any] = {
        "schema_version": 1,
        "generated_at_utc": datetime.datetime.now(datetime.timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%SZ"
        ),
        "models": {
            "wespeaker_onnx": str(wespeaker_onnx),
            "wespeaker_sha256": sha256_file(wespeaker_onnx),
            "dnsmos_p808_onnx": str(dnsmos_p808_onnx),
            "dnsmos_p808_sha256": sha256_file(dnsmos_p808_onnx),
            "citrinet_onnx": str(citrinet.model_path),
            "citrinet_sha256": sha256_file(citrinet.model_path),
            "preprocessing_notes": (
                "WeSpeaker matches Android core-verifier AudioDspMetrics.computeLogMelFilterbank "
                "(80-mel, 400/160/512, 20-7600Hz, Povey, pre-emphasis 0.97, CMN). "
                "DNSMOS p808 uses official Microsoft DNS-Challenge dnsmos_local.py mel scaling "
                "(n_fft=321, hop=160, n_mels=120, (power_to_db(ref=max)+40)/40). "
                "Citrinet-512 uses NeMo per_feature CMVN [1,80,T] + length."
            ),
        },
        "assets": {
            "master_centroid.bin": master_centroid_info,
            "room_tone_44k.pcm": {**room_tone_file_info, **room_tone_stats},
            "room_ir_44k.pcm": {**room_ir_file_info, **room_ir_stats},
            "lexicon.json": lexicon_info,
        },
        "centroid_metrics": centroid_metrics,
        "android_resampler_parity": parity_check,
        "seed_recordings": seed_records,
        "clone_voice_clips": clone_clip_metrics,
        "mined_dataset": mined_dataset_summary,
    }

    report_path = bundle_dir / "enrollment_report.json"
    tmp_report = report_path.with_name(f".{report_path.name}.tmp.{os.getpid()}")
    try:
        tmp_report.write_text(
            json.dumps(report, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        os.replace(tmp_report, report_path)
    finally:
        if tmp_report.exists():
            tmp_report.unlink(missing_ok=True)

    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Enroll Ryan's real voice assets using ONNX models into OTA v001 bundle."
    )
    parser.add_argument("--voice-dir", type=Path, default=DEFAULT_VOICE_DIR)
    parser.add_argument("--bundle-dir", type=Path, default=DEFAULT_BUNDLE_DIR)
    parser.add_argument("--dataset-manifest", type=Path, default=DEFAULT_DATASET_MANIFEST)
    parser.add_argument("--wespeaker-onnx", type=Path, default=DEFAULT_WESPEAKER_ONNX)
    parser.add_argument("--dnsmos-onnx", type=Path, default=DEFAULT_DNSMOS_P808_ONNX)
    parser.add_argument("--citrinet-dir", type=Path, default=DEFAULT_CITRINET_DIR)
    args = parser.parse_args(argv)

    manifest = args.dataset_manifest if args.dataset_manifest.is_file() else None
    report = run_enrollment(
        voice_dir=args.voice_dir,
        bundle_dir=args.bundle_dir,
        dataset_manifest_path=manifest,
        wespeaker_onnx=args.wespeaker_onnx,
        dnsmos_p808_onnx=args.dnsmos_onnx,
        citrinet_dir=args.citrinet_dir,
    )
    cm = report["centroid_metrics"]
    print(
        f"[enroll] master_centroid.bin: {cm['num_contributors']} contributors "
        f"(mean_self_cos={cm['mean_self_cosine']:.4f}, mean_loo_cos={cm['mean_leave_one_out_cosine']:.4f})"
    )
    for name, info in report["assets"].items():
        print(f"[enroll] {name:22s} size={info['size_bytes']:9d} B  sha256={info['sha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
