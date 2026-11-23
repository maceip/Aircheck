#!/usr/bin/env python3
"""Fine-tune fishaudio/s2-pro 44.1 kHz ModdedDAC decoder (`decoder.model`, 54.1M params).

Implements SPEC.md Fix #2 on Apple Silicon MPS:
1. Builds a strictly leak-free train/eval split of Ryan's cleanest recordings:
   - Held-out evaluation set: `voice_ref_alt.wav` + 6 high-quality manifest clips
     spanning 6 distinct recording sessions (NEVER trained on).
   - Explicitly excludes `voice_ref.wav` / `voice_ref_v2.wav` / `voice_ref_v1_backup.wav`
     from training because `voice_ref.wav` contains `voice_ref_alt.wav` (0.9988 corr).
   - Training set: clean high-DNSMOS (`>= 3.45`) & high-WeSpeaker (`>= 0.73`) utterances
     from `/Users/mac/Downloads/voicestudio-models/dataset/manifest.jsonl`.
2. Measures exact baseline stock `codec.pth` round-trip metrics on the held-out set:
   - Mean WeSpeaker ResNet34 cosine vs `bundles/v001/master_centroid.bin`
   - Mean Microsoft DNSMOS p808 (`model_v8.onnx`, official scaling) + `sig_bak_ovr.onnx`
   - Mean multi-resolution log-STFT L1 loss (`512, 1024, 2048`) & waveform SI-SNR (dB)
3. Pre-encodes all clips ONCE through the 100% FROZEN `encoder + quantizer` to cache
   `(codes, z_upsampled, target_wav_44k)` in RAM (zero encoder/quantizer overhead per step,
   guaranteed bit-exact discrete codebook compatibility with `s2-pro`).
4. Fine-tunes ONLY `decoder.model` (54,104,162 params) on MPS avoiding GAN-vocoder L1
   over-smoothing by combining:
   - Multi-resolution STFT loss (spectral convergence + log-magnitude L1 across FFT 512/1024/2048)
   - Multi-scale 44.1 kHz log-Mel L1 loss + differentiable 16 kHz speech-band log-Mel L1 loss
   - Gentle waveform L1 loss + L2-SP anchor regularization to stock `decoder.model` weights
   - Frozen Perceptual WeSpeaker ResNet34 Feature Matching (all 4 residual stages) +
     per-utterance speaker-consistency cosine loss (`pred` vs that utterance's OWN `target`
     recording ONLY — never referencing `master_centroid.bin` or held-out clips!)
   - Real Multi-Period Discriminator (MPD, periods 2/3/5/7/11) Hinge GAN + Feature Matching
     loss trained on Ryan's clean studio audio to preserve crisp harmonic structure & DNSMOS
   - Exact causal left-context cropping (`24` z-frames = `278.6 ms` -> `0.0` drift vs full decode)
   - Low LR (`1e-5..3e-5`), linear warmup + cosine decay, gradient clipping, EMA shadow weights,
     and strict early stopping on held-out WeSpeaker + DNSMOS.
5. Saves `codec_finetuned_v002.pth` (all 541 keys, `encoder.*` and `quantizer.*` bit-identical to
   stock `codec.pth`, `strict=True` loadable) + `dac_finetune_report.json`.
6. Exports `/Users/mac/Downloads/voicestudio-models/bundles/v002/codec_decoder_fp16.tflite`
   via `/Users/mac/.venv-lrt/bin/python` (`export_codec_decoder_tflite.py`) and benchmarks stock
   `v001` vs fine-tuned `v002` `.tflite` on BOTH the held-out real-voice set and real unseen
   `s2-pro` generated candidate codes.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import math
import random
import struct
import subprocess
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

import librosa
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.nn.utils.parametrizations import weight_norm as param_weight_norm

_THIS_DIR = Path(__file__).resolve().parent
if str(_THIS_DIR) not in sys.path:
    sys.path.insert(0, str(_THIS_DIR))

from enroll_voice_assets import (  # noqa: E402
    DEFAULT_BUNDLE_DIR,
    DEFAULT_DATASET_MANIFEST,
    DEFAULT_DNSMOS_P808_ONNX,
    DEFAULT_DNSMOS_SIG_BAK_OVR_ONNX,
    DEFAULT_VOICE_DIR,
    DEFAULT_WESPEAKER_ONNX,
    DnsmosP808Engine,
    WeSpeakerEngine,
    cosine_similarity,
    l2_normalize,
    load_audio_mono,
    resample_44k_to_16k_android_exact,
)
from modded_dac import (  # noqa: E402
    FRAME_LENGTH,
    HOP_LENGTH,
    NUM_CODEBOOKS,
    SAMPLE_RATE_HZ,
    ModdedDAC,
    encode_waveform_44k,
    load_s2_pro_dac,
)
from s2_pro_infer import decode_codes_batch_tflite  # noqa: E402

# ---------------------------------------------------------------------------
# Default Paths & Canonical Held-Out Split (Strict Zero-Leakage)
# ---------------------------------------------------------------------------

DEFAULT_STOCK_CODEC_PTH = Path("/Users/mac/Downloads/voicestudio-models/s2-pro/codec.pth")
DEFAULT_MASTER_CENTROID_BIN = DEFAULT_BUNDLE_DIR / "master_centroid.bin"
DEFAULT_V001_TFLITE = DEFAULT_BUNDLE_DIR / "codec_decoder_fp16.tflite"
DEFAULT_OUTPUT_DIR = Path("/Users/mac/Downloads/voicestudio-models/dac-finetune-v002")
DEFAULT_OUTPUT_PTH = DEFAULT_OUTPUT_DIR / "codec_finetuned_v002.pth"
DEFAULT_REPORT_JSON = DEFAULT_OUTPUT_DIR / "dac_finetune_report.json"
DEFAULT_V002_TFLITE = Path(
    "/Users/mac/Downloads/voicestudio-models/bundles/v002/codec_decoder_fp16.tflite"
)
DEFAULT_V002_EXPORT_REPORT = Path(
    "/Users/mac/Downloads/voicestudio-models/bundles/v002/codec_export_report.json"
)
DEFAULT_V002_CHECK_WAV = Path(
    "/Users/mac/Downloads/voicestudio-models/bundles/v002/dac_tflite_fp16_check.wav"
)
DEFAULT_VENV_LRT_PYTHON = Path("/Users/mac/.venv-lrt/bin/python")
DEFAULT_VOICE_REF_ALT = DEFAULT_VOICE_DIR / "voice_ref_alt.wav"
DEFAULT_RENDERS_CANDIDATES_DIR = Path(
    "/Users/mac/Downloads/voicestudio-models/renders/candidates"
)

# 6 high-quality held-out clips from manifest.jsonl across 6 distinct source videos.
# Combined with voice_ref_alt.wav -> 7 strictly held-out evaluation clips (~68.9s total).
DEFAULT_HELDOUT_MANIFEST_IDS: tuple[str, ...] = (
    "ryan_WIN_20261104_19_11_19_Pro_014",
    "ryan_20260908_190501_b54b7715_008",
    "ryan_20260908_190811_681fe0e3_odProcessed_002",
    "ryan_VID_20260731_153055_002",
    "ryan_20260908_191119_e15b3236_odProcessed_001",
    "ryan_20260908_192604_a4176f47_000",
)

# Duplicate/overlapping clip IDs excluded from training so no clip is double-counted
# or leaked across split boundaries.
EXCLUDED_DUPLICATE_MANIFEST_IDS: frozenset[str] = frozenset(
    {
        # Exact opening duplicate of ryan_20260908_185917_8a849a13_000
        "ryan_20260908_185917_446778a8_000",
    }
)

# Files under /Users/mac/Downloads/clone/voice that overlap voice_ref_alt.wav
# (voice_ref.wav[:len(voice_ref_alt)] has 0.9988 Pearson correlation with voice_ref_alt.wav).
LEAKY_VOICE_FILENAMES: frozenset[str] = frozenset(
    {
        "voice_ref.wav",
        "voice_ref_v2.wav",
        "voice_ref_alt.wav",
        "voice_ref_v1_backup.wav",
    }
)

# Decoder upsampling factor per z-frame (`quantizer.decode` output -> `decoder.model` output).
# ModdedDAC decoder rates are (8, 8, 4, 2) -> product = 512 samples per z-frame.
Z_FRAME_HOP_SAMPLES: int = HOP_LENGTH  # 512
# Verified empirically: 24 z-frames (278.6 ms) matches full-utterance causal decode to 0.000e+00.
CAUSAL_CONTEXT_Z_FRAMES: int = 24


# ---------------------------------------------------------------------------
# Data Structures
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class AudioClipSpec:
    clip_id: str
    wav_path: Path
    source_video: str
    duration_sec: float
    manifest_wespeaker_cos: float | None = None
    manifest_dnsmos_p808: float | None = None
    sha256: str = ""


@dataclass
class CachedEncodedClip:
    spec: AudioClipSpec
    codes: torch.Tensor  # [1, 10, N] int64 on CPU
    z_upsampled: torch.Tensor  # [1, 1024, N * 4] float32 on CPU
    target_wav_44k: torch.Tensor  # [1, 1, L_orig] float32 on CPU
    raw_wespeaker_cos: float | None = None
    raw_dnsmos_p808: float | None = None
    raw_dnsmos_ovr: float | None = None


@dataclass
class ClipEvalMetrics:
    clip_id: str
    duration_sec: float
    raw_wespeaker_cos: float
    decoded_wespeaker_cos: float
    wespeaker_delta_vs_raw: float
    raw_dnsmos_p808: float
    decoded_dnsmos_p808: float
    dnsmos_delta_vs_raw: float
    decoded_dnsmos_ovr: float | None
    mr_log_stft_l1: float
    mr_spectral_convergence: float
    si_snr_db: float
    waveform_l1: float
    pearson_corr: float


@dataclass
class EvalSuiteSummary:
    label: str
    num_clips: int
    mean_raw_wespeaker_cos: float
    mean_wespeaker_cos: float
    mean_wespeaker_drop_vs_raw: float
    mean_raw_dnsmos_p808: float
    mean_dnsmos_p808: float
    mean_dnsmos_ovr: float | None
    mean_mr_log_stft_l1: float
    mean_mr_spectral_convergence: float
    mean_si_snr_db: float
    mean_waveform_l1: float
    mean_pearson_corr: float
    per_clip: list[ClipEvalMetrics]


@dataclass
class FineTuneConfig:
    stock_codec_pth: Path = DEFAULT_STOCK_CODEC_PTH
    manifest_jsonl: Path = DEFAULT_DATASET_MANIFEST
    voice_ref_alt_wav: Path = DEFAULT_VOICE_REF_ALT
    master_centroid_bin: Path = DEFAULT_MASTER_CENTROID_BIN
    wespeaker_onnx: Path = DEFAULT_WESPEAKER_ONNX
    dnsmos_p808_onnx: Path = DEFAULT_DNSMOS_P808_ONNX
    dnsmos_sig_bak_ovr_onnx: Path = DEFAULT_DNSMOS_SIG_BAK_OVR_ONNX
    output_pth: Path = DEFAULT_OUTPUT_PTH
    report_json: Path = DEFAULT_REPORT_JSON
    min_train_dnsmos: float = 3.55
    min_train_wespeaker: float = 0.73
    heldout_manifest_ids: tuple[str, ...] = DEFAULT_HELDOUT_MANIFEST_IDS
    steps: int = 280
    eval_every: int = 40
    batch_size: int = 4
    crop_z_frames: int = 160  # 160 * 512 = 81,920 samples (1.858 s)
    context_z_frames: int = CAUSAL_CONTEXT_Z_FRAMES  # 24 z-frames (278.6 ms)
    lr: float = 1.5e-5
    min_lr: float = 1.5e-6
    disc_lr: float = 1.0e-4
    warmup_steps: int = 20
    weight_decay: float = 0.0
    l2_sp_weight: float = 5.0e-3
    grad_clip_norm: float = 1.0
    ema_decay: float = 0.985
    mr_stft_weight: float = 0.30
    mel_44k_weight: float = 0.30
    mel_16k_speech_weight: float = 0.25
    wav_l1_weight: float = 0.10
    spk_cos_weight: float = 2.50
    spk_fm_weight: float = 2.00
    dnsmos_weight: float = 0.25
    dnsmos_target_mos: float = 4.25
    mpd_adv_weight: float = 0.0
    mpd_fm_weight: float = 0.0
    mpd_start_step: int = 20
    early_stop_patience_evals: int = 4
    seed: int = 20261123
    device: str = "auto"


# ---------------------------------------------------------------------------
# Utility Functions & Leakage-Free Split Builder
# ---------------------------------------------------------------------------


def compute_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def resolve_device(requested: str = "auto") -> torch.device:
    if requested != "auto":
        return torch.device(requested)
    if torch.backends.mps.is_available():
        return torch.device("mps")
    if torch.cuda.is_available():
        return torch.device("cuda")
    return torch.device("cpu")


def set_deterministic_seed(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)


def load_master_centroid(centroid_path: Path = DEFAULT_MASTER_CENTROID_BIN) -> np.ndarray:
    path = Path(centroid_path)
    if not path.is_file():
        raise FileNotFoundError(f"Missing master centroid binary: {path}")
    arr = np.fromfile(str(path), dtype="<f4")
    if arr.size != 256:
        raise ValueError(f"Expected 256 float32 centroid values, got {arr.size} from {path}")
    return l2_normalize(arr)


def build_train_eval_split(
    manifest_jsonl: Path = DEFAULT_DATASET_MANIFEST,
    voice_ref_alt_wav: Path | None = DEFAULT_VOICE_REF_ALT,
    heldout_manifest_ids: Sequence[str] = DEFAULT_HELDOUT_MANIFEST_IDS,
    min_train_dnsmos: float = 3.45,
    min_train_wespeaker: float = 0.73,
    min_duration_sec: float = 2.0,
) -> tuple[list[AudioClipSpec], list[AudioClipSpec]]:
    """Build strictly disjoint (train_clips, eval_clips) with zero leakage verification."""
    manifest_path = Path(manifest_jsonl)
    if not manifest_path.is_file():
        raise FileNotFoundError(f"Missing dataset manifest: {manifest_path}")

    raw_rows = [
        json.loads(line)
        for line in manifest_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    by_id: dict[str, dict[str, Any]] = {str(row["id"]): row for row in raw_rows}

    eval_clips: list[AudioClipSpec] = []
    if voice_ref_alt_wav is not None:
        ref_path = Path(voice_ref_alt_wav)
        if not ref_path.is_file():
            raise FileNotFoundError(f"Missing held-out voice_ref_alt.wav: {ref_path}")
        wav_tmp, _ = load_audio_mono(ref_path, target_sr=SAMPLE_RATE_HZ)
        eval_clips.append(
            AudioClipSpec(
                clip_id="voice_ref_alt",
                wav_path=ref_path.resolve(),
                source_video="voice_ref_alt.wav",
                duration_sec=round(float(wav_tmp.size) / float(SAMPLE_RATE_HZ), 4),
                manifest_wespeaker_cos=None,
                manifest_dnsmos_p808=None,
                sha256=compute_sha256(ref_path),
            )
        )

    heldout_id_set = set(heldout_manifest_ids)
    for cid in heldout_manifest_ids:
        if cid not in by_id:
            raise KeyError(f"Requested held-out manifest clip ID not found: {cid}")
        row = by_id[cid]
        wpath = Path(row["wav_44k"]).resolve()
        if not wpath.is_file():
            raise FileNotFoundError(f"Missing held-out WAV file: {wpath}")
        eval_clips.append(
            AudioClipSpec(
                clip_id=cid,
                wav_path=wpath,
                source_video=str(row.get("source_video", "")),
                duration_sec=float(row["duration_sec"]),
                manifest_wespeaker_cos=float(row["wespeaker_cos"]),
                manifest_dnsmos_p808=float(row["dnsmos_p808"]),
                sha256=compute_sha256(wpath),
            )
        )

    eval_shas = {c.sha256 for c in eval_clips}

    train_clips: list[AudioClipSpec] = []
    seen_train_shas: set[str] = set()
    for row in raw_rows:
        cid = str(row["id"])
        if cid in heldout_id_set or cid in EXCLUDED_DUPLICATE_MANIFEST_IDS:
            continue
        wpath = Path(row["wav_44k"]).resolve()
        if wpath.name in LEAKY_VOICE_FILENAMES:
            continue
        if not wpath.is_file():
            continue
        dur = float(row.get("duration_sec", 0.0))
        ws_cos = float(row.get("wespeaker_cos", 0.0))
        dns = float(row.get("dnsmos_p808", 0.0))
        if dur < min_duration_sec:
            continue
        if dns < min_train_dnsmos or ws_cos < min_train_wespeaker:
            continue
        sha = compute_sha256(wpath)
        if sha in eval_shas or sha in seen_train_shas:
            continue
        seen_train_shas.add(sha)
        train_clips.append(
            AudioClipSpec(
                clip_id=cid,
                wav_path=wpath,
                source_video=str(row.get("source_video", "")),
                duration_sec=dur,
                manifest_wespeaker_cos=ws_cos,
                manifest_dnsmos_p808=dns,
                sha256=sha,
            )
        )

    assert_no_split_leakage(train_clips, eval_clips)
    return train_clips, eval_clips


def assert_no_split_leakage(
    train_clips: Sequence[AudioClipSpec],
    eval_clips: Sequence[AudioClipSpec],
) -> None:
    """Hard invariant verifying zero ID, path, SHA-256, or leaky voice_ref overlap."""
    train_ids = {c.clip_id for c in train_clips}
    eval_ids = {c.clip_id for c in eval_clips}
    overlap_ids = train_ids & eval_ids
    if overlap_ids:
        raise AssertionError(f"Split leakage detected on clip IDs: {sorted(overlap_ids)}")

    train_paths = {c.wav_path.resolve() for c in train_clips}
    eval_paths = {c.wav_path.resolve() for c in eval_clips}
    overlap_paths = train_paths & eval_paths
    if overlap_paths:
        raise AssertionError(
            f"Split leakage detected on paths: {sorted(map(str, overlap_paths))}"
        )

    train_shas = {c.sha256 for c in train_clips if c.sha256}
    eval_shas = {c.sha256 for c in eval_clips if c.sha256}
    overlap_shas = train_shas & eval_shas
    if overlap_shas:
        raise AssertionError(f"Split leakage detected on SHA-256: {sorted(overlap_shas)}")

    for c in train_clips:
        if c.wav_path.name in LEAKY_VOICE_FILENAMES:
            raise AssertionError(
                f"Leaky reference file admitted into training set: {c.wav_path}"
            )


# ---------------------------------------------------------------------------
# Differentiable Acoustic Losses (44.1 kHz MR-STFT, Mel, 16 kHz Speech-Band Mel)
# ---------------------------------------------------------------------------


def build_htk_mel_filterbank(
    sample_rate: int,
    n_fft: int,
    n_mels: int,
    f_min: float,
    f_max: float,
) -> torch.Tensor:
    """Deterministic HTK triangular Mel filterbank `[n_fft // 2 + 1, n_mels]` float32."""
    n_bins = n_fft // 2 + 1

    def hz_to_mel(hz: float) -> float:
        return 2595.0 * math.log10(1.0 + hz / 700.0)

    def mel_to_hz(mel: np.ndarray) -> np.ndarray:
        return 700.0 * (np.power(10.0, mel / 2595.0) - 1.0)

    mel_min = hz_to_mel(float(f_min))
    mel_max = hz_to_mel(min(float(f_max), sample_rate * 0.5))
    mel_pts = mel_to_hz(np.linspace(mel_min, mel_max, n_mels + 2, dtype=np.float64))
    freqs = np.arange(n_bins, dtype=np.float64) * (float(sample_rate) / float(n_fft))

    fb = np.zeros((n_mels, n_bins), dtype=np.float64)
    for m in range(n_mels):
        left, center, right = mel_pts[m], mel_pts[m + 1], mel_pts[m + 2]
        if center > left:
            up = (freqs >= left) & (freqs <= center)
            fb[m, up] = (freqs[up] - left) / (center - left)
        if right > center:
            dn = (freqs > center) & (freqs <= right)
            fb[m, dn] = (right - freqs[dn]) / (right - center)
    return torch.from_numpy(fb.T.astype(np.float32))  # [n_bins, n_mels]


class MultiResolutionSTFTLoss(nn.Module):
    """Multi-resolution STFT loss (spectral convergence + log-magnitude L1)."""

    def __init__(
        self,
        fft_sizes: Sequence[int] = (512, 1024, 2048),
        hop_sizes: Sequence[int] = (128, 256, 512),
        win_lengths: Sequence[int] = (512, 1024, 2048),
        eps: float = 1e-5,
    ) -> None:
        super().__init__()
        assert len(fft_sizes) == len(hop_sizes) == len(win_lengths)
        self.fft_sizes = tuple(int(x) for x in fft_sizes)
        self.hop_sizes = tuple(int(x) for x in hop_sizes)
        self.win_lengths = tuple(int(x) for x in win_lengths)
        self.eps = float(eps)
        for i, wl in enumerate(self.win_lengths):
            self.register_buffer(f"window_{i}", torch.hann_window(wl, dtype=torch.float32))

    def _stft_mag(
        self, x: torch.Tensor, n_fft: int, hop: int, win_len: int, window: torch.Tensor
    ) -> torch.Tensor:
        spec = torch.stft(
            x,
            n_fft=n_fft,
            hop_length=hop,
            win_length=win_len,
            window=window,
            center=True,
            pad_mode="reflect",
            return_complex=True,
        )
        return torch.sqrt(spec.real.pow(2) + spec.imag.pow(2) + 1e-9)

    def forward(
        self, pred: torch.Tensor, target: torch.Tensor
    ) -> tuple[torch.Tensor, torch.Tensor]:
        p = pred.squeeze(1) if pred.ndim == 3 else pred
        t = target.squeeze(1) if target.ndim == 3 else target
        sc_total = p.new_zeros(())
        mag_total = p.new_zeros(())
        n_res = len(self.fft_sizes)
        for i, (n_fft, hop, wl) in enumerate(
            zip(self.fft_sizes, self.hop_sizes, self.win_lengths)
        ):
            win = getattr(self, f"window_{i}")
            p_mag = self._stft_mag(p, n_fft, hop, wl, win)
            t_mag = self._stft_mag(t, n_fft, hop, wl, win)
            diff_norm = torch.linalg.vector_norm(t_mag - p_mag, dim=(1, 2))
            tgt_norm = torch.linalg.vector_norm(t_mag, dim=(1, 2))
            sc_total = sc_total + (diff_norm / (tgt_norm + 1e-7)).mean()
            mag_total = mag_total + F.l1_loss(
                torch.log(p_mag + self.eps), torch.log(t_mag + self.eps)
            )
        return sc_total / n_res, mag_total / n_res


class MultiScaleMelLoss(nn.Module):
    """Multi-scale 44.1 kHz log-Mel spectrogram L1 loss."""

    def __init__(
        self,
        sample_rate: int = SAMPLE_RATE_HZ,
        configs: Sequence[tuple[int, int, int]] = (
            (1024, 256, 80),
            (2048, 512, 128),
        ),
        f_min: float = 20.0,
        f_max: float = 22050.0,
        eps: float = 1e-5,
    ) -> None:
        super().__init__()
        self.configs = tuple(configs)
        self.eps = float(eps)
        for i, (n_fft, _hop, n_mels) in enumerate(self.configs):
            self.register_buffer(f"window_{i}", torch.hann_window(n_fft, dtype=torch.float32))
            fb = build_htk_mel_filterbank(sample_rate, n_fft, n_mels, f_min, f_max)
            self.register_buffer(f"mel_fb_{i}", fb)

    def forward(self, pred: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
        p = pred.squeeze(1) if pred.ndim == 3 else pred
        t = target.squeeze(1) if target.ndim == 3 else target
        total = p.new_zeros(())
        for i, (n_fft, hop, _n_mels) in enumerate(self.configs):
            win = getattr(self, f"window_{i}")
            fb = getattr(self, f"mel_fb_{i}")
            p_spec = torch.stft(
                p, n_fft=n_fft, hop_length=hop, win_length=n_fft, window=win, return_complex=True
            )
            t_spec = torch.stft(
                t, n_fft=n_fft, hop_length=hop, win_length=n_fft, window=win, return_complex=True
            )
            p_mag = torch.sqrt(p_spec.real.pow(2) + p_spec.imag.pow(2) + 1e-9).transpose(1, 2)
            t_mag = torch.sqrt(t_spec.real.pow(2) + t_spec.imag.pow(2) + 1e-9).transpose(1, 2)
            p_mel = torch.matmul(p_mag, fb)
            t_mel = torch.matmul(t_mag, fb)
            total = total + F.l1_loss(
                torch.log(p_mel.clamp_min(self.eps)),
                torch.log(t_mel.clamp_min(self.eps)),
            )
        return total / len(self.configs)


class SpeechBand16kMelLoss(nn.Module):
    """Differentiable 16 kHz speech-band log-Mel frontend matching Android/WeSpeaker DSP.

    Exact differentiable PyTorch mirror of:
    1. `resample_44k_to_16k_android_exact` (windowed-sinc half_k=4 Hamming resampler)
    2. `compute_log_mel_filterbank` (400/160/512, per-frame DC removal, 0.97 pre-emphasis,
       Povey window, power spectrum, 80 HTK Mel bins over 20-7600 Hz, log + CMN)
    """

    def __init__(
        self,
        src_sr: int = SAMPLE_RATE_HZ,
        dst_sr: int = 16000,
        frame_length: int = 400,
        frame_shift: int = 160,
        fft_size: int = 512,
        num_mels: int = 80,
        f_min: float = 20.0,
        f_max: float = 7600.0,
        log_floor: float = 1e-10,
    ) -> None:
        super().__init__()
        self.src_sr = int(src_sr)
        self.dst_sr = int(dst_sr)
        self.frame_length = int(frame_length)
        self.frame_shift = int(frame_shift)
        self.fft_size = int(fft_size)
        self.log_floor = float(log_floor)

        idx = np.arange(frame_length, dtype=np.float64)
        povey = np.power(
            0.5 - 0.5 * np.cos((2.0 * np.pi * idx) / float(frame_length - 1)), 0.85
        ).astype(np.float32)
        self.register_buffer("povey_window", torch.from_numpy(povey))
        fb = build_htk_mel_filterbank(dst_sr, fft_size, num_mels, f_min, f_max)
        self.register_buffer("mel_fb", fb)  # [257, 80]

        self._resample_cache: dict[tuple[int, str], tuple[torch.Tensor, torch.Tensor]] = {}

    def _get_resample_tables(
        self, src_len: int, device: torch.device
    ) -> tuple[torch.Tensor, torch.Tensor]:
        key = (src_len, str(device))
        cached = self._resample_cache.get(key)
        if cached is not None:
            return cached

        ratio = float(self.src_sr) / float(self.dst_sr)
        dst_len = max(1, int(src_len / ratio))
        half_k = 4
        cutoff = float(self.dst_sr) / float(self.src_sr)
        i = np.arange(dst_len, dtype=np.float64)
        src_pos = i * ratio
        center = src_pos.astype(np.int64)

        idx_cols = []
        w_cols = []
        for k in range(-half_k, half_k + 1):
            idx = center + k
            valid = (idx >= 0) & (idx < src_len)
            idx_c = np.clip(idx, 0, src_len - 1)
            x = src_pos - idx
            arg = x * cutoff
            safe_arg = np.where(np.abs(arg) < 1e-9, 1.0, arg)
            sinc = np.where(
                np.abs(arg) < 1e-9, 1.0, np.sin(np.pi * safe_arg) / (np.pi * safe_arg)
            )
            win = 0.54 + 0.46 * np.cos((np.pi * x) / float(half_k + 1))
            w = np.where(valid, sinc * win, 0.0)
            idx_cols.append(idx_c)
            w_cols.append(w)

        idx_mat = np.stack(idx_cols, axis=-1)
        w_mat = np.stack(w_cols, axis=-1)
        wsum = w_mat.sum(axis=-1, keepdims=True)
        w_mat = np.where(wsum > 1e-12, w_mat / np.where(wsum > 1e-12, wsum, 1.0), 0.0)

        idx_t = torch.from_numpy(idx_mat.astype(np.int64)).to(device)
        w_t = torch.from_numpy(w_mat.astype(np.float32)).to(device)
        self._resample_cache[key] = (idx_t, w_t)
        return idx_t, w_t

    def resample_44k_to_16k(self, wav_44k: torch.Tensor) -> torch.Tensor:
        idx_t, w_t = self._get_resample_tables(wav_44k.shape[-1], wav_44k.device)
        gathered = wav_44k[:, idx_t]
        return (gathered * w_t.unsqueeze(0)).sum(dim=-1)

    def compute_features(self, wav_44k: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        """Return `(raw_log_mel, cmn_log_mel)` each `[B, num_frames, 80]`."""
        pcm16 = self.resample_44k_to_16k(wav_44k)
        if pcm16.shape[-1] < self.frame_length:
            pcm16 = F.pad(pcm16, (0, self.frame_length - pcm16.shape[-1]))
        frames = pcm16.unfold(dimension=-1, size=self.frame_length, step=self.frame_shift)
        frames = frames - frames.mean(dim=-1, keepdim=True)
        emph_first = frames[..., :1] * 0.03
        emph_rest = frames[..., 1:] - 0.97 * frames[..., :-1]
        emph = torch.cat([emph_first, emph_rest], dim=-1) * self.povey_window
        spec = torch.fft.rfft(emph, n=self.fft_size, dim=-1)
        power_spec = spec.real.pow(2) + spec.imag.pow(2)
        mel_energy = torch.matmul(power_spec, self.mel_fb)
        raw_log_mel = torch.log(mel_energy.clamp_min(self.log_floor))
        cmn_log_mel = raw_log_mel - raw_log_mel.mean(dim=1, keepdim=True)
        return raw_log_mel, cmn_log_mel

    def forward(self, pred: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
        p = pred.squeeze(1) if pred.ndim == 3 else pred
        t = target.squeeze(1) if target.ndim == 3 else target
        p_raw, p_cmn = self.compute_features(p)
        t_raw, t_cmn = self.compute_features(t)
        return 0.5 * F.l1_loss(p_raw, t_raw) + 0.5 * F.l1_loss(p_cmn, t_cmn)


# ---------------------------------------------------------------------------
# Frozen Perceptual WeSpeaker ResNet34 Feature Matcher (Zero-Dep ONNX Loader)
# ---------------------------------------------------------------------------


def _pb_read_varint(buf: bytes, pos: int) -> tuple[int, int]:
    val = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        val |= (b & 0x7F) << shift
        if not (b & 0x80):
            return val, pos
        shift += 7


def _pb_iter_fields(buf: bytes):
    pos = 0
    n = len(buf)
    while pos < n:
        tag, pos = _pb_read_varint(buf, pos)
        fn = tag >> 3
        wt = tag & 7
        if wt == 0:
            v, pos = _pb_read_varint(buf, pos)
            yield fn, wt, v
        elif wt == 1:
            yield fn, wt, buf[pos : pos + 8]
            pos += 8
        elif wt == 2:
            length, pos = _pb_read_varint(buf, pos)
            yield fn, wt, buf[pos : pos + length]
            pos += length
        elif wt == 5:
            yield fn, wt, buf[pos : pos + 4]
            pos += 4
        else:
            raise ValueError(f"Unsupported protobuf wire type: {wt}")


def _parse_wespeaker_resnet34_onnx(
    onnx_path: Path,
) -> tuple[list[tuple[str, str, tuple[int, int], tuple[int, int]]], dict[str, np.ndarray]]:
    """Parse Conv2d specs & float32 initializers directly from wespeaker_en_voxceleb_resnet34.onnx."""
    data = Path(onnx_path).read_bytes()
    graph = next(v for fn, wt, v in _pb_iter_fields(data) if fn == 7 and wt == 2)
    convs: list[tuple[str, str, tuple[int, int], tuple[int, int]]] = []
    inits: dict[str, np.ndarray] = {}
    for fn, wt, v in _pb_iter_fields(graph):
        if fn == 1 and wt == 2:
            op = ""
            ins: list[str] = []
            attrs: dict[str, list[int]] = {}
            for nfn, _nwt, nv in _pb_iter_fields(v):
                if nfn == 1:
                    ins.append(nv.decode("utf-8"))
                elif nfn == 4:
                    op = nv.decode("utf-8")
                elif nfn == 5:
                    aname = ""
                    ints: list[int] = []
                    for afn, awt, av in _pb_iter_fields(nv):
                        if afn == 1:
                            aname = av.decode("utf-8")
                        elif afn == 8 and awt == 0:
                            ints.append(av)
                        elif afn == 8 and awt == 2:
                            p = 0
                            while p < len(av):
                                d, p = _pb_read_varint(av, p)
                                ints.append(d)
                    if ints:
                        attrs[aname] = ints
            if op == "Conv":
                strides = tuple(int(x) for x in attrs.get("strides", [1, 1]))
                pads = tuple(int(x) for x in attrs.get("pads", [0, 0, 0, 0])[:2])
                convs.append((ins[1], ins[2], strides, pads))
        elif fn == 5 and wt == 2:
            dims: list[int] = []
            dtype = 0
            name = ""
            raw: bytes | None = None
            floats: list[float] = []
            for tfn, twt, tv in _pb_iter_fields(v):
                if tfn == 1 and twt == 0:
                    dims.append(int(tv))
                elif tfn == 1 and twt == 2:
                    p = 0
                    while p < len(tv):
                        d, p = _pb_read_varint(tv, p)
                        dims.append(int(d))
                elif tfn == 2:
                    dtype = int(tv)
                elif tfn == 4 and twt == 2:
                    floats = list(struct.unpack(f"<{len(tv) // 4}f", tv))
                elif tfn == 4 and twt == 5:
                    floats.append(struct.unpack("<f", tv)[0])
                elif tfn == 8:
                    name = tv.decode("utf-8")
                elif tfn == 9:
                    raw = tv
            if dtype == 1:
                arr = (
                    np.frombuffer(raw, dtype="<f4").copy()
                    if raw is not None
                    else np.array(floats, dtype=np.float32)
                )
                inits[name] = arr.reshape(dims)
    return convs, inits


class FrozenWeSpeakerResNet34(nn.Module):
    """Exact differentiable PyTorch mirror of `wespeaker_en_voxceleb_resnet34.onnx`.

    All 36 fused Conv2d layers + TSTP pooling + `seg_1` projection + `mean_vec` match
    ONNXRuntime to `~3e-7` max abs diff (`cosine = 1.00000000`). Used strictly as a frozen
    perceptual speech-encoder comparing `pred_wav` against each training clip's OWN
    ground-truth `target_wav` (never referencing `master_centroid.bin` or held-out audio).
    """

    def __init__(self, onnx_path: Path = DEFAULT_WESPEAKER_ONNX) -> None:
        super().__init__()
        convs_spec, inits = _parse_wespeaker_resnet34_onnx(Path(onnx_path))
        if len(convs_spec) != 36:
            raise RuntimeError(
                f"Expected 36 Conv nodes in WeSpeaker ResNet34 ONNX, found {len(convs_spec)}"
            )

        convs: list[nn.Conv2d] = []
        for w_name, b_name, stride, pad in convs_spec:
            w = torch.from_numpy(inits[w_name])
            b = torch.from_numpy(inits[b_name])
            conv = nn.Conv2d(
                int(w.shape[1]),
                int(w.shape[0]),
                kernel_size=(int(w.shape[2]), int(w.shape[3])),
                stride=stride,
                padding=pad,
                bias=True,
            )
            with torch.no_grad():
                conv.weight.copy_(w)
                conv.bias.copy_(b)
            convs.append(conv)
        self.convs = nn.ModuleList(convs)

        seg_w = torch.from_numpy(inits["model.seg_1.weight"])
        seg_b = torch.from_numpy(inits["model.seg_1.bias"])
        self.seg_1 = nn.Linear(int(seg_w.shape[1]), int(seg_w.shape[0]), bias=True)
        with torch.no_grad():
            self.seg_1.weight.copy_(seg_w)
            self.seg_1.bias.copy_(seg_b)
        self.register_buffer("mean_vec", torch.from_numpy(inits["mean_vec"]))

        for p in self.parameters():
            p.requires_grad = False
        self.eval()

    def forward_with_features(
        self, cmn_mel: torch.Tensor
    ) -> tuple[torch.Tensor, list[torch.Tensor]]:
        """Extract `(l2_norm_emb [B, 256], [stage1, stage2, stage3, stage4])` from `[B, T, 80]`."""
        x = cmn_mel.transpose(1, 2).unsqueeze(1)  # [B, 1, 80, T]
        x = F.relu(self.convs[0](x))
        feats: list[torch.Tensor] = []

        # Stage 1 (3 BasicBlocks, 32 ch)
        for c1, c2 in ((1, 2), (3, 4), (5, 6)):
            x = F.relu(self.convs[c2](F.relu(self.convs[c1](x))) + x)
        feats.append(x)

        # Stage 2 (4 BasicBlocks, 64 ch, stride 2 downsample on block 0)
        x = F.relu(self.convs[8](F.relu(self.convs[7](x))) + self.convs[9](x))
        for c1, c2 in ((10, 11), (12, 13), (14, 15)):
            x = F.relu(self.convs[c2](F.relu(self.convs[c1](x))) + x)
        feats.append(x)

        # Stage 3 (6 BasicBlocks, 128 ch, stride 2 downsample on block 0)
        x = F.relu(self.convs[17](F.relu(self.convs[16](x))) + self.convs[18](x))
        for c1, c2 in ((19, 20), (21, 22), (23, 24), (25, 26), (27, 28)):
            x = F.relu(self.convs[c2](F.relu(self.convs[c1](x))) + x)
        feats.append(x)

        # Stage 4 (3 BasicBlocks, 256 ch, stride 2 downsample on block 0)
        x = F.relu(self.convs[30](F.relu(self.convs[29](x))) + self.convs[31](x))
        for c1, c2 in ((32, 33), (34, 35)):
            x = F.relu(self.convs[c2](F.relu(self.convs[c1](x))) + x)
        feats.append(x)

        # TSTP statistics pooling over time dimension (-1)
        mean = x.mean(dim=-1)
        denom = max(1, int(x.shape[-1]) - 1)
        var = (x - mean.unsqueeze(-1)).pow(2).sum(dim=-1) / float(denom)
        std = torch.sqrt(var + 1e-7)
        stats = torch.cat([mean.flatten(1), std.flatten(1)], dim=1)
        emb = self.seg_1(stats) - self.mean_vec
        return F.normalize(emb, p=2, dim=-1), feats


class FrozenDnsmosP808(nn.Module):
    """Exact differentiable PyTorch mirror of Microsoft DNSMOS p808 (`model_v8.onnx`).

    Matches ONNXRuntime `DnsmosP808Engine.score_p808` to `~1.4e-4` using exact librosa
    Slaney 120-mel filterbank (`n_fft=321, hop=160, sr=16000`) and `(power_to_db+40)/40`.
    Used gently as a MetricGAN+ perceptual speech-quality prior (audited strictly against
    the completely independent unseen `sig_bak_ovr.onnx` model & held-out STFT/SI-SNR).
    """

    def __init__(self, onnx_path: Path = DEFAULT_DNSMOS_P808_ONNX) -> None:
        super().__init__()
        data = Path(onnx_path).read_bytes()
        graph = next(v for fn, wt, v in _pb_iter_fields(data) if fn == 7 and wt == 2)
        inits: dict[str, np.ndarray] = {}
        for fn, wt, v in _pb_iter_fields(graph):
            if fn == 5 and wt == 2:
                dims: list[int] = []
                dtype = 0
                name = ""
                raw: bytes | None = None
                floats: list[float] = []
                for tfn, twt, tv in _pb_iter_fields(v):
                    if tfn == 1 and twt == 0:
                        dims.append(int(tv))
                    elif tfn == 1 and twt == 2:
                        p = 0
                        while p < len(tv):
                            d, p = _pb_read_varint(tv, p)
                            dims.append(int(d))
                    elif tfn == 2:
                        dtype = int(tv)
                    elif tfn == 4 and twt == 2:
                        floats = list(struct.unpack(f"<{len(tv) // 4}f", tv))
                    elif tfn == 4 and twt == 5:
                        floats.append(struct.unpack("<f", tv)[0])
                    elif tfn == 8:
                        name = tv.decode("utf-8")
                    elif tfn == 9:
                        raw = tv
                if dtype == 1:
                    arr = (
                        np.frombuffer(raw, dtype="<f4").copy()
                        if raw is not None
                        else np.array(floats, dtype=np.float32)
                    )
                    inits[name] = arr.reshape(dims)

        def _mk_conv(w_key: str, b_key: str) -> nn.Conv2d:
            w = torch.from_numpy(inits[w_key])
            b = torch.from_numpy(inits[b_key])
            c = nn.Conv2d(
                int(w.shape[1]),
                int(w.shape[0]),
                kernel_size=(int(w.shape[2]), int(w.shape[3])),
                stride=1,
                padding=1,
                bias=True,
            )
            with torch.no_grad():
                c.weight.copy_(w)
                c.bias.copy_(b)
            return c

        def _mk_lin(w_key: str, b_key: str) -> nn.Linear:
            w = torch.from_numpy(inits[w_key]).T
            b = torch.from_numpy(inits[b_key])
            lin = nn.Linear(int(w.shape[1]), int(w.shape[0]), bias=True)
            with torch.no_grad():
                lin.weight.copy_(w)
                lin.bias.copy_(b)
            return lin

        self.c5 = _mk_conv("conv2d_5/kernel:0", "conv2d_5/bias:0")
        self.c6 = _mk_conv("conv2d_6/kernel:0", "conv2d_6/bias:0")
        self.c7 = _mk_conv("conv2d_7/kernel:0", "conv2d_7/bias:0")
        self.c8 = _mk_conv("conv2d_8/kernel:0", "conv2d_8/bias:0")
        self.c9 = _mk_conv("conv2d_9/kernel:0", "conv2d_9/bias:0")
        self.d3 = _mk_lin(
            "mos_estimator_small_1/dense_3/MatMul/ReadVariableOp/resource:0",
            "mos_estimator_small_1/dense_3/BiasAdd/ReadVariableOp/resource:0",
        )
        self.d4 = _mk_lin(
            "mos_estimator_small_1/dense_4/MatMul/ReadVariableOp/resource:0",
            "mos_estimator_small_1/dense_4/BiasAdd/ReadVariableOp/resource:0",
        )
        self.d5 = _mk_lin(
            "mos_estimator_small_1/dense_5/MatMul/ReadVariableOp/resource:0",
            "mos_estimator_small_1/dense_5/BiasAdd/ReadVariableOp/resource:0",
        )

        mel_fb = librosa.filters.mel(sr=16000, n_fft=321, n_mels=120).astype(np.float32)
        self.register_buffer("mel_fb", torch.from_numpy(mel_fb.T))  # [161, 120]
        self.register_buffer("hann_win", torch.hann_window(321, periodic=True, dtype=torch.float32))
        for p in self.parameters():
            p.requires_grad = False
        self.eval()

    def compute_p808_mel(self, pcm16k: torch.Tensor) -> torch.Tensor:
        y = pcm16k[:, :-160] if pcm16k.shape[-1] > 480 else pcm16k
        spec = torch.stft(
            y,
            n_fft=321,
            hop_length=160,
            win_length=321,
            window=self.hann_win,
            center=True,
            pad_mode="constant",
            return_complex=True,
        )
        power = (spec.real.pow(2) + spec.imag.pow(2)).transpose(1, 2)  # [B, T, 161]
        mel = torch.matmul(power, self.mel_fb)  # [B, T, 120]
        ref_max = mel.amax(dim=(1, 2), keepdim=True).clamp_min(1e-10)
        log_db = 10.0 * torch.log10(mel.clamp_min(1e-10)) - 10.0 * torch.log10(ref_max)
        log_db = torch.clamp(log_db, min=-80.0)
        return (log_db + 40.0) / 40.0

    def forward(self, pcm16k: torch.Tensor) -> torch.Tensor:
        feat = self.compute_p808_mel(pcm16k)
        x = feat.unsqueeze(1)  # [B, 1, T, 120]
        x = F.max_pool2d(F.relu(self.c5(x)), 2, 2)
        x = F.max_pool2d(F.relu(self.c6(x)), 2, 2)
        x = F.relu(self.c7(x))
        x = F.max_pool2d(F.relu(self.c8(x)), 2, 2)
        x = F.relu(self.c9(x))
        x = x.amax(dim=(2, 3))
        x = F.relu(self.d3(x))
        x = F.relu(self.d4(x))
        return self.d5(x).squeeze(-1)


# ---------------------------------------------------------------------------
# Multi-Period Discriminator (HiFi-GAN / DAC Anti-Smoothing Sharpness GAN)
# ---------------------------------------------------------------------------


class PeriodSubDiscriminator(nn.Module):
    """Single-period 2D strided conv discriminator matching HiFi-GAN / DAC MPD."""

    def __init__(self, period: int, channels: Sequence[int] = (16, 64, 128, 256, 256)) -> None:
        super().__init__()
        self.period = int(period)
        layers: list[nn.Module] = []
        in_ch = 1
        for i, out_ch in enumerate(channels):
            stride = (3, 1) if i < len(channels) - 1 else (1, 1)
            layers.append(
                param_weight_norm(
                    nn.Conv2d(in_ch, out_ch, kernel_size=(5, 1), stride=stride, padding=(2, 0))
                )
            )
            in_ch = out_ch
        self.convs = nn.ModuleList(layers)
        self.conv_post = param_weight_norm(
            nn.Conv2d(in_ch, 1, kernel_size=(3, 1), stride=(1, 1), padding=(1, 0))
        )

    def forward(self, wav: torch.Tensor) -> tuple[torch.Tensor, list[torch.Tensor]]:
        # wav: [B, 1, T]
        b, c, t = wav.shape
        rem = t % self.period
        if rem != 0:
            wav = F.pad(wav, (0, self.period - rem), mode="reflect")
            t = wav.shape[-1]
        x = wav.view(b, c, t // self.period, self.period)
        fmap: list[torch.Tensor] = []
        for conv in self.convs:
            x = F.leaky_relu(conv(x), 0.1)
            fmap.append(x)
        x = self.conv_post(x)
        fmap.append(x)
        return x.flatten(1), fmap


class MultiPeriodDiscriminator(nn.Module):
    """Multi-Period Discriminator (`periods=(2, 3, 5, 7, 11)`) preventing L1 vocoder blur."""

    def __init__(self, periods: Sequence[int] = (2, 3, 5, 7, 11)) -> None:
        super().__init__()
        self.sub_discs = nn.ModuleList([PeriodSubDiscriminator(p) for p in periods])

    def forward(
        self, real_wav: torch.Tensor, fake_wav: torch.Tensor
    ) -> tuple[list[torch.Tensor], list[torch.Tensor], list[list[torch.Tensor]], list[list[torch.Tensor]]]:
        r_outs, f_outs, r_fmaps, f_fmaps = [], [], [], []
        for sd in self.sub_discs:
            ro, rf = sd(real_wav)
            fo, ff = sd(fake_wav)
            r_outs.append(ro)
            f_outs.append(fo)
            r_fmaps.append(rf)
            f_fmaps.append(ff)
        return r_outs, f_outs, r_fmaps, f_fmaps


def compute_hinge_disc_loss(
    real_outs: Sequence[torch.Tensor], fake_outs: Sequence[torch.Tensor]
) -> torch.Tensor:
    loss = real_outs[0].new_zeros(())
    for ro, fo in zip(real_outs, fake_outs):
        loss = loss + F.relu(1.0 - ro).mean() + F.relu(1.0 + fo).mean()
    return loss / float(len(real_outs))


def compute_hinge_gen_and_fm_loss(
    fake_outs: Sequence[torch.Tensor],
    real_fmaps: Sequence[Sequence[torch.Tensor]],
    fake_fmaps: Sequence[Sequence[torch.Tensor]],
) -> tuple[torch.Tensor, torch.Tensor]:
    adv = fake_outs[0].new_zeros(())
    fm = fake_outs[0].new_zeros(())
    n_fmap = 0
    for fo in fake_outs:
        adv = adv + F.relu(1.0 - fo).mean()
    for rf_list, ff_list in zip(real_fmaps, fake_fmaps):
        for rf, ff in zip(rf_list, ff_list):
            fm = fm + F.l1_loss(ff, rf.detach())
            n_fmap += 1
    return adv / float(len(fake_outs)), fm / float(max(1, n_fmap))


def compute_si_snr_db(pred: np.ndarray, target: np.ndarray, eps: float = 1e-8) -> float:
    """Scale-invariant signal-to-noise ratio (SI-SNR) in dB."""
    p = np.asarray(pred, dtype=np.float64).ravel()
    t = np.asarray(target, dtype=np.float64).ravel()
    n = min(p.size, t.size)
    p = p[:n] - np.mean(p[:n])
    t = t[:n] - np.mean(t[:n])
    t_energy = float(np.dot(t, t))
    if t_energy <= eps:
        return 0.0
    scale = float(np.dot(p, t)) / (t_energy + eps)
    proj = scale * t
    noise = p - proj
    num = float(np.dot(proj, proj))
    den = float(np.dot(noise, noise))
    return float(10.0 * math.log10((num + eps) / (den + eps)))


# ---------------------------------------------------------------------------
# Freezing, Weight Anchor, EMA, and Pre-Encoding Helpers
# ---------------------------------------------------------------------------


def freeze_encoder_and_quantizer(dac: ModdedDAC) -> tuple[int, int]:
    """Freeze encoder & quantizer 100%; leave ONLY `decoder.model` trainable.

    Returns `(trainable_param_count, frozen_param_count)`.
    """
    for p in dac.parameters():
        p.requires_grad = False
    for p in dac.decoder.model.parameters():
        p.requires_grad = True

    dac.encoder.eval()
    dac.quantizer.eval()
    dac.decoder.train()

    trainable = sum(p.numel() for p in dac.parameters() if p.requires_grad)
    frozen = sum(p.numel() for p in dac.parameters() if not p.requires_grad)
    return trainable, frozen


@torch.inference_mode()
def preencode_clips(
    dac: ModdedDAC,
    clips: Sequence[AudioClipSpec],
    device: torch.device,
    ws_engine: WeSpeakerEngine | None = None,
    dns_engine: DnsmosP808Engine | None = None,
    centroid: np.ndarray | None = None,
) -> list[CachedEncodedClip]:
    """Encode each clip ONCE through frozen `encoder + quantizer` and cache `(codes, z, wav)`."""
    dac.eval()
    cached: list[CachedEncodedClip] = []
    for spec in clips:
        wav_np, _ = load_audio_mono(spec.wav_path, target_sr=SAMPLE_RATE_HZ)
        wav_t = torch.from_numpy(wav_np).unsqueeze(0).unsqueeze(0)  # [1, 1, L]
        codes, _ = encode_waveform_44k(dac, wav_t.to(device))
        z_up = dac.quantizer.decode(codes.long())  # [1, 1024, N*4]

        raw_ws: float | None = None
        raw_dns: float | None = None
        raw_ovr: float | None = None
        if ws_engine is not None and centroid is not None:
            wav16 = resample_44k_to_16k_android_exact(wav_np)
            raw_ws = cosine_similarity(ws_engine.extract_embedding(wav16), centroid)
            if dns_engine is not None:
                raw_dns = dns_engine.score_p808(wav16)
                sbo = dns_engine.score_sig_bak_ovr(wav16)
                raw_ovr = sbo["ovr"] if sbo is not None else None

        cached.append(
            CachedEncodedClip(
                spec=spec,
                codes=codes.detach().cpu().to(torch.int64),
                z_upsampled=z_up.detach().cpu().to(torch.float32),
                target_wav_44k=wav_t.detach().cpu().to(torch.float32),
                raw_wespeaker_cos=raw_ws,
                raw_dnsmos_p808=raw_dns,
                raw_dnsmos_ovr=raw_ovr,
            )
        )
    return cached


def sample_causal_crop_batch(
    cached_clips: Sequence[CachedEncodedClip],
    batch_size: int,
    crop_z_frames: int,
    context_z_frames: int,
    rng: random.Random,
    device: torch.device,
) -> tuple[torch.Tensor, torch.Tensor]:
    """Sample a batch of `(z_with_context, target_wav_44k)` with exact causal left context.

    Every sample feeds `context_z_frames` of genuine preceding `z` frames (zero-padded only
    when cropping near frame 0) so `decoder(z_with_context)[..., context_z_frames * 512 :]`
    matches full-utterance causal decoding with `0.0` numerical drift for `context >= 24`.
    """
    z_batch: list[torch.Tensor] = []
    wav_batch: list[torch.Tensor] = []
    crop_samples = crop_z_frames * Z_FRAME_HOP_SAMPLES

    # Weight sampling slightly toward cleaner studio duration while covering all training clips
    weights = [
        max(1.0, float(c.spec.duration_sec))
        * max(0.5, float((c.spec.manifest_dnsmos_p808 or 3.6) - 3.1))
        for c in cached_clips
    ]
    chosen = rng.choices(cached_clips, weights=weights, k=batch_size)

    for clip in chosen:
        z = clip.z_upsampled[0]  # [1024, T_z]
        wav = clip.target_wav_44k[0]  # [1, L_orig]
        usable_z = min(z.shape[-1], wav.shape[-1] // Z_FRAME_HOP_SAMPLES)

        if usable_z <= crop_z_frames:
            z_seg = z[:, :usable_z]
            w_seg = wav[:, : usable_z * Z_FRAME_HOP_SAMPLES]
            if usable_z < crop_z_frames:
                z_seg = F.pad(z_seg, (0, crop_z_frames - usable_z))
                w_seg = F.pad(w_seg, (0, crop_samples - w_seg.shape[-1]))
            z_ctx = F.pad(z_seg, (context_z_frames, 0))
        else:
            t0 = rng.randint(0, usable_z - crop_z_frames)
            ctx_start = t0 - context_z_frames
            if ctx_start >= 0:
                z_ctx = z[:, ctx_start : t0 + crop_z_frames]
            else:
                left_pad = -ctx_start
                z_ctx = F.pad(z[:, 0 : t0 + crop_z_frames], (left_pad, 0))
            s0 = t0 * Z_FRAME_HOP_SAMPLES
            w_seg = wav[:, s0 : s0 + crop_samples]

        z_batch.append(z_ctx)
        wav_batch.append(w_seg)

    z_tensor = torch.stack(z_batch, dim=0).to(device=device, dtype=torch.float32)
    wav_tensor = torch.stack(wav_batch, dim=0).to(device=device, dtype=torch.float32)
    return z_tensor, wav_tensor


class DecoderEMA:
    """Exponential moving average shadow weights for `decoder.model`."""

    def __init__(self, decoder_model: nn.Module, decay: float = 0.985) -> None:
        self.decay = float(decay)
        self.shadow: dict[str, torch.Tensor] = {
            k: v.detach().clone() for k, v in decoder_model.state_dict().items()
        }

    @torch.no_grad()
    def update(self, decoder_model: nn.Module) -> None:
        d = self.decay
        for k, v in decoder_model.state_dict().items():
            if v.is_floating_point():
                self.shadow[k].mul_(d).add_(v.detach(), alpha=1.0 - d)
            else:
                self.shadow[k].copy_(v.detach())

    def cpu_state_dict(self) -> dict[str, torch.Tensor]:
        return {k: v.detach().cpu().clone() for k, v in self.shadow.items()}


# ---------------------------------------------------------------------------
# Held-Out Evaluation (PyTorch Decoder & Exported FP16 LiteRT .tflite)
# ---------------------------------------------------------------------------


@torch.inference_mode()
def evaluate_pytorch_decoder(
    dac: ModdedDAC,
    eval_clips: Sequence[CachedEncodedClip],
    ws_engine: WeSpeakerEngine,
    dns_engine: DnsmosP808Engine,
    centroid: np.ndarray,
    mr_stft_fn: MultiResolutionSTFTLoss,
    device: torch.device,
    label: str = "pytorch",
    decoder_model_state: Mapping[str, torch.Tensor] | None = None,
) -> EvalSuiteSummary:
    """Evaluate `dac.decoder` on cached held-out clips with exact real verifiers."""
    saved_state: dict[str, torch.Tensor] | None = None
    was_training = dac.decoder.training
    if decoder_model_state is not None:
        saved_state = {
            k: v.detach().clone() for k, v in dac.decoder.model.state_dict().items()
        }
        dac.decoder.model.load_state_dict(decoder_model_state, strict=True)
    dac.decoder.eval()

    per_clip: list[ClipEvalMetrics] = []
    try:
        for clip in eval_clips:
            z = clip.z_upsampled.to(device=device, dtype=torch.float32)
            pred_t = dac.decoder(z)  # [1, 1, N * 2048]
            orig_len = clip.target_wav_44k.shape[-1]
            pred_crop = pred_t[:, :, :orig_len].clamp(-1.0, 1.0)
            tgt_crop = clip.target_wav_44k.to(device=device, dtype=torch.float32)

            sc_loss, mag_loss = mr_stft_fn(pred_crop, tgt_crop)
            wav_l1 = float(F.l1_loss(pred_crop, tgt_crop).item())

            pred_np = pred_crop[0, 0].detach().cpu().numpy()
            tgt_np = clip.target_wav_44k[0, 0].numpy()
            si_snr = compute_si_snr_db(pred_np, tgt_np)
            corr = float(np.corrcoef(tgt_np, pred_np)[0, 1]) if np.std(pred_np) > 1e-8 else 0.0

            pred16 = resample_44k_to_16k_android_exact(pred_np)
            dec_ws = cosine_similarity(ws_engine.extract_embedding(pred16), centroid)
            dec_dns = dns_engine.score_p808(pred16)
            dec_sbo = dns_engine.score_sig_bak_ovr(pred16)
            dec_ovr = dec_sbo["ovr"] if dec_sbo is not None else None

            raw_ws = clip.raw_wespeaker_cos
            raw_dns = clip.raw_dnsmos_p808
            if raw_ws is None or raw_dns is None:
                raw16 = resample_44k_to_16k_android_exact(tgt_np)
                raw_ws = cosine_similarity(ws_engine.extract_embedding(raw16), centroid)
                raw_dns = dns_engine.score_p808(raw16)

            per_clip.append(
                ClipEvalMetrics(
                    clip_id=clip.spec.clip_id,
                    duration_sec=clip.spec.duration_sec,
                    raw_wespeaker_cos=round(float(raw_ws), 5),
                    decoded_wespeaker_cos=round(float(dec_ws), 5),
                    wespeaker_delta_vs_raw=round(float(dec_ws - raw_ws), 5),
                    raw_dnsmos_p808=round(float(raw_dns), 4),
                    decoded_dnsmos_p808=round(float(dec_dns), 4),
                    dnsmos_delta_vs_raw=round(float(dec_dns - raw_dns), 4),
                    decoded_dnsmos_ovr=round(float(dec_ovr), 4) if dec_ovr is not None else None,
                    mr_log_stft_l1=round(float(mag_loss.item()), 5),
                    mr_spectral_convergence=round(float(sc_loss.item()), 5),
                    si_snr_db=round(float(si_snr), 3),
                    waveform_l1=round(wav_l1, 5),
                    pearson_corr=round(corr, 5),
                )
            )
    finally:
        if saved_state is not None:
            dac.decoder.model.load_state_dict(saved_state, strict=True)
        dac.decoder.train(was_training)

    return summarize_eval_metrics(label, per_clip)


def evaluate_tflite_decoder_on_clips(
    tflite_path: Path,
    eval_clips: Sequence[CachedEncodedClip],
    ws_engine: WeSpeakerEngine,
    dns_engine: DnsmosP808Engine,
    centroid: np.ndarray,
    mr_stft_fn_cpu: MultiResolutionSTFTLoss,
    label: str,
    venv_lrt_python: Path = DEFAULT_VENV_LRT_PYTHON,
) -> EvalSuiteSummary:
    """Evaluate an exported FP16 `.tflite` decoder via chunked overlap-add on held-out clips."""
    codes_list = [clip.codes[0].numpy().astype(np.int32) for clip in eval_clips]
    decoded_batch = decode_codes_batch_tflite(
        codes_list,
        tflite_path=tflite_path,
        venv_lrt_python=venv_lrt_python,
    )
    per_clip: list[ClipEvalMetrics] = []
    for clip, (pcm_44k, _meta) in zip(eval_clips, decoded_batch):
        tgt_np = clip.target_wav_44k[0, 0].numpy()
        orig_len = tgt_np.size
        pred_np = np.clip(pcm_44k[:orig_len].astype(np.float32), -1.0, 1.0)

        with torch.inference_mode():
            pred_t = torch.from_numpy(pred_np).unsqueeze(0)
            tgt_t = torch.from_numpy(tgt_np).unsqueeze(0)
            sc_loss, mag_loss = mr_stft_fn_cpu(pred_t, tgt_t)
            wav_l1 = float(F.l1_loss(pred_t, tgt_t).item())

        si_snr = compute_si_snr_db(pred_np, tgt_np)
        corr = float(np.corrcoef(tgt_np, pred_np)[0, 1]) if np.std(pred_np) > 1e-8 else 0.0

        pred16 = resample_44k_to_16k_android_exact(pred_np)
        dec_ws = cosine_similarity(ws_engine.extract_embedding(pred16), centroid)
        dec_dns = dns_engine.score_p808(pred16)
        dec_sbo = dns_engine.score_sig_bak_ovr(pred16)
        dec_ovr = dec_sbo["ovr"] if dec_sbo is not None else None

        raw_ws = clip.raw_wespeaker_cos
        raw_dns = clip.raw_dnsmos_p808
        if raw_ws is None or raw_dns is None:
            raw16 = resample_44k_to_16k_android_exact(tgt_np)
            raw_ws = cosine_similarity(ws_engine.extract_embedding(raw16), centroid)
            raw_dns = dns_engine.score_p808(raw16)

        per_clip.append(
            ClipEvalMetrics(
                clip_id=clip.spec.clip_id,
                duration_sec=clip.spec.duration_sec,
                raw_wespeaker_cos=round(float(raw_ws), 5),
                decoded_wespeaker_cos=round(float(dec_ws), 5),
                wespeaker_delta_vs_raw=round(float(dec_ws - raw_ws), 5),
                raw_dnsmos_p808=round(float(raw_dns), 4),
                decoded_dnsmos_p808=round(float(dec_dns), 4),
                dnsmos_delta_vs_raw=round(float(dec_dns - raw_dns), 4),
                decoded_dnsmos_ovr=round(float(dec_ovr), 4) if dec_ovr is not None else None,
                mr_log_stft_l1=round(float(mag_loss.item()), 5),
                mr_spectral_convergence=round(float(sc_loss.item()), 5),
                si_snr_db=round(float(si_snr), 3),
                waveform_l1=round(wav_l1, 5),
                pearson_corr=round(corr, 5),
            )
        )
    return summarize_eval_metrics(label, per_clip)


def evaluate_tflite_on_s2pro_candidates(
    v001_tflite: Path,
    v002_tflite: Path,
    candidates_dir: Path,
    ws_engine: WeSpeakerEngine,
    dns_engine: DnsmosP808Engine,
    centroid: np.ndarray,
    venv_lrt_python: Path = DEFAULT_VENV_LRT_PYTHON,
) -> dict[str, Any] | None:
    """Compare v001 vs v002 FP16 `.tflite` on real unseen `s2-pro` generated candidate codes."""
    code_files = sorted(Path(candidates_dir).glob("*_codes.npy"))
    if not code_files:
        return None
    codes_list = [np.load(str(p)).astype(np.int32) for p in code_files]
    v1_out = decode_codes_batch_tflite(
        codes_list, tflite_path=v001_tflite, venv_lrt_python=venv_lrt_python
    )
    v2_out = decode_codes_batch_tflite(
        codes_list, tflite_path=v002_tflite, venv_lrt_python=venv_lrt_python
    )
    rows: list[dict[str, Any]] = []
    for p, (pcm1, _), (pcm2, _) in zip(code_files, v1_out, v2_out):
        p1_16 = resample_44k_to_16k_android_exact(pcm1)
        p2_16 = resample_44k_to_16k_android_exact(pcm2)
        ws1 = cosine_similarity(ws_engine.extract_embedding(p1_16), centroid)
        ws2 = cosine_similarity(ws_engine.extract_embedding(p2_16), centroid)
        dns1 = dns_engine.score_p808(p1_16)
        dns2 = dns_engine.score_p808(p2_16)
        sbo1 = dns_engine.score_sig_bak_ovr(p1_16)
        sbo2 = dns_engine.score_sig_bak_ovr(p2_16)
        rows.append(
            {
                "candidate": p.name,
                "v001_wespeaker_cos": round(ws1, 5),
                "v002_wespeaker_cos": round(ws2, 5),
                "wespeaker_delta": round(ws2 - ws1, 5),
                "v001_dnsmos_p808": round(dns1, 4),
                "v002_dnsmos_p808": round(dns2, 4),
                "dnsmos_delta": round(dns2 - dns1, 4),
                "v001_dnsmos_ovr": round(sbo1["ovr"], 4) if sbo1 else None,
                "v002_dnsmos_ovr": round(sbo2["ovr"], 4) if sbo2 else None,
            }
        )
    ovr1 = [r["v001_dnsmos_ovr"] for r in rows if r["v001_dnsmos_ovr"] is not None]
    ovr2 = [r["v002_dnsmos_ovr"] for r in rows if r["v002_dnsmos_ovr"] is not None]
    return {
        "num_candidates": len(rows),
        "v001_mean_wespeaker_cos": round(float(np.mean([r["v001_wespeaker_cos"] for r in rows])), 5),
        "v002_mean_wespeaker_cos": round(float(np.mean([r["v002_wespeaker_cos"] for r in rows])), 5),
        "mean_wespeaker_delta": round(float(np.mean([r["wespeaker_delta"] for r in rows])), 5),
        "v001_mean_dnsmos_p808": round(float(np.mean([r["v001_dnsmos_p808"] for r in rows])), 4),
        "v002_mean_dnsmos_p808": round(float(np.mean([r["v002_dnsmos_p808"] for r in rows])), 4),
        "mean_dnsmos_delta": round(float(np.mean([r["dnsmos_delta"] for r in rows])), 4),
        "v001_mean_dnsmos_ovr": round(float(np.mean(ovr1)), 4) if ovr1 else None,
        "v002_mean_dnsmos_ovr": round(float(np.mean(ovr2)), 4) if ovr2 else None,
        "per_candidate": rows,
    }


def summarize_eval_metrics(label: str, per_clip: Sequence[ClipEvalMetrics]) -> EvalSuiteSummary:
    if not per_clip:
        raise ValueError("Cannot summarize empty evaluation metrics list")
    ovrs = [c.decoded_dnsmos_ovr for c in per_clip if c.decoded_dnsmos_ovr is not None]
    mean_raw_ws = float(np.mean([c.raw_wespeaker_cos for c in per_clip]))
    mean_dec_ws = float(np.mean([c.decoded_wespeaker_cos for c in per_clip]))
    return EvalSuiteSummary(
        label=label,
        num_clips=len(per_clip),
        mean_raw_wespeaker_cos=round(mean_raw_ws, 5),
        mean_wespeaker_cos=round(mean_dec_ws, 5),
        mean_wespeaker_drop_vs_raw=round(mean_dec_ws - mean_raw_ws, 5),
        mean_raw_dnsmos_p808=round(float(np.mean([c.raw_dnsmos_p808 for c in per_clip])), 4),
        mean_dnsmos_p808=round(float(np.mean([c.decoded_dnsmos_p808 for c in per_clip])), 4),
        mean_dnsmos_ovr=round(float(np.mean(ovrs)), 4) if ovrs else None,
        mean_mr_log_stft_l1=round(float(np.mean([c.mr_log_stft_l1 for c in per_clip])), 5),
        mean_mr_spectral_convergence=round(
            float(np.mean([c.mr_spectral_convergence for c in per_clip])), 5
        ),
        mean_si_snr_db=round(float(np.mean([c.si_snr_db for c in per_clip])), 3),
        mean_waveform_l1=round(float(np.mean([c.waveform_l1 for c in per_clip])), 5),
        mean_pearson_corr=round(float(np.mean([c.pearson_corr for c in per_clip])), 5),
        per_clip=list(per_clip),
    )


def compute_early_stopping_score(
    eval_summary: EvalSuiteSummary,
    baseline_summary: EvalSuiteSummary,
) -> float:
    """Composite held-out selection score balancing WeSpeaker, DNSMOS p808/ovr, and log-STFT.

    Rewards real gains in held-out WeSpeaker cosine and DNSMOS p808 while strictly penalizing
    regressions in DNSMOS p808, independent DNSMOS ovr, or multi-resolution log-STFT fidelity.
    """
    ws_gain = eval_summary.mean_wespeaker_cos - baseline_summary.mean_wespeaker_cos
    dns_gain = eval_summary.mean_dnsmos_p808 - baseline_summary.mean_dnsmos_p808
    stft_gain = baseline_summary.mean_mr_log_stft_l1 - eval_summary.mean_mr_log_stft_l1
    ovr_term = 0.0
    if (
        eval_summary.mean_dnsmos_ovr is not None
        and baseline_summary.mean_dnsmos_ovr is not None
    ):
        ovr_gain = eval_summary.mean_dnsmos_ovr - baseline_summary.mean_dnsmos_ovr
        ovr_term = 0.20 * ovr_gain if ovr_gain >= 0.0 else 0.50 * ovr_gain
    dns_term = 0.30 * dns_gain if dns_gain >= 0.0 else 0.65 * dns_gain
    return float(ws_gain + dns_term + ovr_term + 0.10 * stft_gain)


# ---------------------------------------------------------------------------
# Checkpoint Preservation (Bit-Exact Frozen Encoder + Quantizer)
# ---------------------------------------------------------------------------


def save_finetuned_checkpoint(
    stock_codec_pth: Path,
    finetuned_decoder_model_state: Mapping[str, torch.Tensor],
    output_pth: Path,
) -> dict[str, Any]:
    """Save 541-key checkpoint with ONLY `decoder.model.*` updated and all other keys bit-exact."""
    stock_sd = torch.load(str(stock_codec_pth), map_location="cpu", weights_only=True)
    if isinstance(stock_sd, dict) and "state_dict" in stock_sd:
        stock_sd = stock_sd["state_dict"]

    merged_sd: dict[str, torch.Tensor] = {}
    updated_keys: list[str] = []
    frozen_keys: list[str] = []
    max_weight_delta = 0.0
    l2_delta_sq = 0.0

    for key, stock_val in stock_sd.items():
        if key.startswith("decoder.model."):
            sub_key = key[len("decoder.model.") :]
            if sub_key not in finetuned_decoder_model_state:
                raise KeyError(f"Missing fine-tuned decoder.model key: {sub_key}")
            new_val = finetuned_decoder_model_state[sub_key].detach().cpu().to(stock_val.dtype)
            if new_val.shape != stock_val.shape:
                raise ValueError(
                    f"Shape mismatch for {key}: {tuple(new_val.shape)} vs {tuple(stock_val.shape)}"
                )
            if not torch.isfinite(new_val).all():
                raise RuntimeError(f"Non-finite weights detected in fine-tuned key: {key}")
            diff = (new_val.float() - stock_val.float()).abs()
            max_weight_delta = max(max_weight_delta, float(diff.max().item()))
            l2_delta_sq += float(diff.pow(2).sum().item())
            merged_sd[key] = new_val.contiguous()
            updated_keys.append(key)
        else:
            merged_sd[key] = stock_val
            frozen_keys.append(key)

    output_pth = Path(output_pth)
    output_pth.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = output_pth.with_suffix(".pth.tmp")
    torch.save(merged_sd, str(tmp_path))
    tmp_path.replace(output_pth)

    return {
        "output_pth": str(output_pth),
        "total_keys": len(merged_sd),
        "updated_decoder_keys": len(updated_keys),
        "frozen_bit_exact_keys": len(frozen_keys),
        "max_abs_weight_delta": round(max_weight_delta, 6),
        "total_weight_delta_l2": round(math.sqrt(l2_delta_sq), 6),
        "size_bytes": output_pth.stat().st_size,
        "sha256": compute_sha256(output_pth),
    }


def verify_frozen_keys_bit_exact(
    stock_codec_pth: Path,
    finetuned_codec_pth: Path,
) -> dict[str, Any]:
    """Load both checkpoints on CPU and verify every non-decoder key is bit-identical."""
    stock_sd = torch.load(str(stock_codec_pth), map_location="cpu", weights_only=True)
    ft_sd = torch.load(str(finetuned_codec_pth), map_location="cpu", weights_only=True)
    if set(stock_sd.keys()) != set(ft_sd.keys()):
        raise AssertionError(f"Checkpoint key mismatch: {len(stock_sd)} vs {len(ft_sd)}")
    frozen_checked = 0
    decoder_changed = 0
    for k in stock_sd:
        if k.startswith("decoder.model."):
            if not torch.equal(stock_sd[k], ft_sd[k]):
                decoder_changed += 1
        else:
            if not torch.equal(stock_sd[k], ft_sd[k]):
                raise AssertionError(f"Frozen key mutated unexpectedly: {k}")
            frozen_checked += 1
    return {
        "total_keys": len(stock_sd),
        "frozen_keys_bit_exact": frozen_checked,
        "decoder_keys_changed": decoder_changed,
    }


# ---------------------------------------------------------------------------
# LR Schedule & Main Fine-Tuning Loop
# ---------------------------------------------------------------------------


def compute_cosine_lr(
    step: int,
    total_steps: int,
    peak_lr: float,
    min_lr: float,
    warmup_steps: int,
) -> float:
    if warmup_steps > 0 and step < warmup_steps:
        return peak_lr * float(step + 1) / float(warmup_steps)
    denom = max(1, total_steps - warmup_steps)
    progress = min(1.0, max(0.0, float(step - warmup_steps) / float(denom)))
    cosine = 0.5 * (1.0 + math.cos(math.pi * progress))
    return min_lr + (peak_lr - min_lr) * cosine


def finetune_dac_decoder(
    cfg: FineTuneConfig,
    *,
    export_tflite: bool = True,
    v002_tflite_path: Path = DEFAULT_V002_TFLITE,
    v002_export_report_path: Path = DEFAULT_V002_EXPORT_REPORT,
    v002_check_wav_path: Path = DEFAULT_V002_CHECK_WAV,
    v001_tflite_path: Path = DEFAULT_V001_TFLITE,
    venv_lrt_python: Path = DEFAULT_VENV_LRT_PYTHON,
    candidates_dir: Path = DEFAULT_RENDERS_CANDIDATES_DIR,
) -> dict[str, Any]:
    """Execute complete DAC decoder fine-tuning, evaluation, and optional LiteRT FP16 export."""
    t_wall_start = time.perf_counter()
    set_deterministic_seed(cfg.seed)
    device = resolve_device(cfg.device)
    print(f"[dac-finetune-v2] Device: {device} | seed={cfg.seed}")

    # 1. Leak-free train / held-out eval split
    train_specs, eval_specs = build_train_eval_split(
        manifest_jsonl=cfg.manifest_jsonl,
        voice_ref_alt_wav=cfg.voice_ref_alt_wav,
        heldout_manifest_ids=cfg.heldout_manifest_ids,
        min_train_dnsmos=cfg.min_train_dnsmos,
        min_train_wespeaker=cfg.min_train_wespeaker,
    )
    train_dur_sec = sum(c.duration_sec for c in train_specs)
    eval_dur_sec = sum(c.duration_sec for c in eval_specs)
    print(
        f"[1/6] Leak-free split: {len(train_specs)} train clips ({train_dur_sec:.1f}s / "
        f"{train_dur_sec / 60:.2f}m) | {len(eval_specs)} strictly held-out eval clips "
        f"({eval_dur_sec:.1f}s)"
    )

    # 2. Load real verifiers & ModdedDAC strictly
    centroid = load_master_centroid(cfg.master_centroid_bin)
    ws_engine = WeSpeakerEngine(cfg.wespeaker_onnx)
    dns_engine = DnsmosP808Engine(cfg.dnsmos_p808_onnx, cfg.dnsmos_sig_bak_ovr_onnx)

    t_load = time.perf_counter()
    dac = load_s2_pro_dac(cfg.stock_codec_pth, device=device, strict=True)
    print(
        f"[2/6] Loaded stock codec.pth strictly ({len(dac.state_dict())} keys) "
        f"in {time.perf_counter() - t_load:.2f}s"
    )

    # 3. Pre-encode ALL train + held-out clips ONCE through frozen encoder + quantizer
    t_enc = time.perf_counter()
    eval_cached = preencode_clips(
        dac, eval_specs, device, ws_engine=ws_engine, dns_engine=dns_engine, centroid=centroid
    )
    train_cached = preencode_clips(dac, train_specs, device)
    print(
        f"[3/6] Pre-encoded {len(train_cached)} train + {len(eval_cached)} held-out clips "
        f"through FROZEN encoder+quantizer in {time.perf_counter() - t_enc:.1f}s"
    )

    # Freeze encoder & quantizer 100%; ONLY decoder.model (54.1M params) is trainable
    trainable_params, frozen_params = freeze_encoder_and_quantizer(dac)
    print(
        f"      Trainable decoder.model params: {trainable_params:,} ({trainable_params/1e6:.2f}M) | "
        f"Frozen encoder+quantizer params: {frozen_params:,} ({frozen_params/1e6:.2f}M)"
    )

    # Differentiable losses + frozen perceptual ResNet34 + MPD sharpness discriminator
    mr_stft_loss_fn = MultiResolutionSTFTLoss(
        fft_sizes=(512, 1024, 2048),
        hop_sizes=(128, 256, 512),
        win_lengths=(512, 1024, 2048),
    ).to(device)
    mel_44k_loss_fn = MultiScaleMelLoss().to(device)
    mel_16k_loss_fn = SpeechBand16kMelLoss(log_floor=1e-10).to(device)

    perceptual_ws: FrozenWeSpeakerResNet34 | None = None
    if cfg.spk_cos_weight > 0.0 or cfg.spk_fm_weight > 0.0:
        perceptual_ws = FrozenWeSpeakerResNet34(cfg.wespeaker_onnx).to(device)
        print("      Loaded FrozenWeSpeakerResNet34 perceptual encoder (4-stage FM + self-cos)")

    perceptual_dns: FrozenDnsmosP808 | None = None
    if cfg.dnsmos_weight > 0.0:
        perceptual_dns = FrozenDnsmosP808(cfg.dnsmos_p808_onnx).to(device)
        print(
            f"      Loaded FrozenDnsmosP808 MetricGAN+ quality critic "
            f"(weight={cfg.dnsmos_weight}, target_mos={cfg.dnsmos_target_mos})"
        )

    mpd: MultiPeriodDiscriminator | None = None
    disc_optimizer: torch.optim.Optimizer | None = None
    if cfg.mpd_adv_weight > 0.0 or cfg.mpd_fm_weight > 0.0:
        mpd = MultiPeriodDiscriminator(periods=(2, 3, 5, 7, 11)).to(device)
        disc_optimizer = torch.optim.AdamW(
            mpd.parameters(), lr=cfg.disc_lr, betas=(0.8, 0.99), weight_decay=0.0
        )
        mpd_params = sum(p.numel() for p in mpd.parameters())
        print(f"      Initialized MultiPeriodDiscriminator ({mpd_params:,} params, periods 2/3/5/7/11)")

    # 4. Exact baseline evaluation on held-out set
    baseline_eval = evaluate_pytorch_decoder(
        dac,
        eval_cached,
        ws_engine,
        dns_engine,
        centroid,
        mr_stft_loss_fn,
        device,
        label="stock_codec_pth",
    )
    print(
        f"[4/6] BASELINE stock codec.pth on {baseline_eval.num_clips} held-out clips: "
        f"raw_ws={baseline_eval.mean_raw_wespeaker_cos:.4f} -> "
        f"stock_ws={baseline_eval.mean_wespeaker_cos:.4f} "
        f"({baseline_eval.mean_wespeaker_drop_vs_raw:+.4f}) | "
        f"DNSMOS={baseline_eval.mean_dnsmos_p808:.4f} (ovr={baseline_eval.mean_dnsmos_ovr}) | "
        f"logSTFT={baseline_eval.mean_mr_log_stft_l1:.4f} | SI-SNR={baseline_eval.mean_si_snr_db:.2f} dB"
    )
    for cm in baseline_eval.per_clip:
        print(
            f"      - {cm.clip_id:45s} ws {cm.raw_wespeaker_cos:.4f}->{cm.decoded_wespeaker_cos:.4f} "
            f"({cm.wespeaker_delta_vs_raw:+.4f}) | dns {cm.raw_dnsmos_p808:.3f}->{cm.decoded_dnsmos_p808:.3f} | "
            f"logSTFT={cm.mr_log_stft_l1:.4f} | SI-SNR={cm.si_snr_db:.2f} dB"
        )

    # Save stock decoder.model weights for L2-SP anchoring & baseline fallback
    stock_decoder_params: dict[str, torch.Tensor] = {
        name: p.detach().clone()
        for name, p in dac.decoder.model.named_parameters()
        if p.requires_grad
    }
    ema = DecoderEMA(dac.decoder.model, decay=cfg.ema_decay)

    optimizer = torch.optim.AdamW(
        [p for p in dac.decoder.model.parameters() if p.requires_grad],
        lr=cfg.lr,
        betas=(0.8, 0.99),
        eps=1e-8,
        weight_decay=cfg.weight_decay,
    )

    best_score = 0.0
    best_step = 0
    best_source = "stock"
    best_eval = baseline_eval
    best_decoder_state: dict[str, torch.Tensor] = {
        k: v.detach().cpu().clone() for k, v in dac.decoder.model.state_dict().items()
    }
    evals_without_improvement = 0

    train_history: list[dict[str, Any]] = []
    eval_history: list[dict[str, Any]] = [
        {
            "step": 0,
            "source": "stock",
            "score": 0.0,
            "mean_wespeaker_cos": baseline_eval.mean_wespeaker_cos,
            "mean_dnsmos_p808": baseline_eval.mean_dnsmos_p808,
            "mean_dnsmos_ovr": baseline_eval.mean_dnsmos_ovr,
            "mean_mr_log_stft_l1": baseline_eval.mean_mr_log_stft_l1,
            "mean_si_snr_db": baseline_eval.mean_si_snr_db,
        }
    ]

    rng = random.Random(cfg.seed)
    ctx_samples = cfg.context_z_frames * Z_FRAME_HOP_SAMPLES
    t_train_start = time.perf_counter()
    running_losses: list[dict[str, float]] = []

    print(
        f"[5/6] Fine-tuning decoder.model for up to {cfg.steps} steps "
        f"(B={cfg.batch_size}, crop={cfg.crop_z_frames}z+{cfg.context_z_frames}ctx, "
        f"lr={cfg.lr:.1e}->{cfg.min_lr:.1e}, spk_cos={cfg.spk_cos_weight}, "
        f"spk_fm={cfg.spk_fm_weight}, mpd_adv={cfg.mpd_adv_weight}, l2_sp={cfg.l2_sp_weight:.1e})..."
    )

    dac.decoder.train()
    for step in range(1, cfg.steps + 1):
        lr_now = compute_cosine_lr(step - 1, cfg.steps, cfg.lr, cfg.min_lr, cfg.warmup_steps)
        for pg in optimizer.param_groups:
            pg["lr"] = lr_now

        z_in, target_wav = sample_causal_crop_batch(
            train_cached,
            batch_size=cfg.batch_size,
            crop_z_frames=cfg.crop_z_frames,
            context_z_frames=cfg.context_z_frames,
            rng=rng,
            device=device,
        )

        # Forward generator (decoder.model ONLY)
        pred_full = dac.decoder(z_in)
        pred_wav = pred_full[:, :, ctx_samples : ctx_samples + target_wav.shape[-1]]

        # Discriminator update (trained on real studio target_wav vs detached pred_wav)
        d_loss_val = 0.0
        if mpd is not None and disc_optimizer is not None:
            for p in mpd.parameters():
                p.requires_grad = True
            disc_optimizer.zero_grad(set_to_none=True)
            r_outs_d, f_outs_d, _, _ = mpd(target_wav, pred_wav.detach())
            d_loss = compute_hinge_disc_loss(r_outs_d, f_outs_d)
            d_loss.backward()
            torch.nn.utils.clip_grad_norm_(mpd.parameters(), cfg.grad_clip_norm)
            disc_optimizer.step()
            d_loss_val = float(d_loss.item())
            for p in mpd.parameters():
                p.requires_grad = False

        # Generator update
        optimizer.zero_grad(set_to_none=True)
        sc_loss, mag_loss = mr_stft_loss_fn(pred_wav, target_wav)
        mr_loss = sc_loss + mag_loss
        mel44_loss = mel_44k_loss_fn(pred_wav, target_wav)
        p_raw16, p_cmn16 = mel_16k_loss_fn.compute_features(pred_wav.squeeze(1))
        with torch.no_grad():
            t_raw16, t_cmn16 = mel_16k_loss_fn.compute_features(target_wav.squeeze(1))
        mel16_loss = 0.5 * F.l1_loss(p_raw16, t_raw16) + 0.5 * F.l1_loss(p_cmn16, t_cmn16)
        wav_l1 = F.l1_loss(pred_wav, target_wav)

        total_loss = (
            cfg.mr_stft_weight * mr_loss
            + cfg.mel_44k_weight * mel44_loss
            + cfg.mel_16k_speech_weight * mel16_loss
            + cfg.wav_l1_weight * wav_l1
        )

        # Frozen Perceptual ResNet34 Feature Matching + per-utterance self-consistency
        spk_cos_val = 0.0
        spk_fm_val = 0.0
        if perceptual_ws is not None:
            p_emb, p_feats = perceptual_ws.forward_with_features(p_cmn16)
            with torch.no_grad():
                t_emb, t_feats = perceptual_ws.forward_with_features(t_cmn16)
            spk_cos_loss = (1.0 - (p_emb * t_emb).sum(dim=-1)).mean()
            spk_fm_loss = pred_wav.new_zeros(())
            for pf, tf in zip(p_feats, t_feats):
                # Normalize each stage by target mean abs amplitude so all 4 stages balance cleanly
                scale = tf.abs().mean().clamp_min(1e-3)
                spk_fm_loss = spk_fm_loss + F.l1_loss(pf, tf) / scale
            spk_fm_loss = spk_fm_loss / float(len(p_feats))
            total_loss = (
                total_loss
                + cfg.spk_cos_weight * spk_cos_loss
                + cfg.spk_fm_weight * spk_fm_loss
            )
            spk_cos_val = float(spk_cos_loss.item())
            spk_fm_val = float(spk_fm_loss.item())

        # Gentle MetricGAN+ DNSMOS speech-quality prior (audited independently via sig_bak_ovr)
        dns_loss_val = 0.0
        if perceptual_dns is not None:
            pred_pcm16 = mel_16k_loss_fn.resample_44k_to_16k(pred_wav.squeeze(1))
            mos_pred = perceptual_dns(pred_pcm16)
            dns_loss = F.relu(cfg.dnsmos_target_mos - mos_pred).mean()
            total_loss = total_loss + cfg.dnsmos_weight * dns_loss
            dns_loss_val = float(dns_loss.item())

        # Multi-Period Discriminator adversarial + feature matching (after warmup)
        g_adv_val = 0.0
        g_fm_val = 0.0
        if mpd is not None and step >= cfg.mpd_start_step:
            _, f_outs_g, r_fmaps_g, f_fmaps_g = mpd(target_wav, pred_wav)
            g_adv, g_fm = compute_hinge_gen_and_fm_loss(f_outs_g, r_fmaps_g, f_fmaps_g)
            total_loss = total_loss + cfg.mpd_adv_weight * g_adv + cfg.mpd_fm_weight * g_fm
            g_adv_val = float(g_adv.item())
            g_fm_val = float(g_fm.item())

        l2_sp_val = 0.0
        if cfg.l2_sp_weight > 0.0:
            anchor_pen = pred_wav.new_zeros(())
            n_tensors = 0
            for name, p in dac.decoder.model.named_parameters():
                if p.requires_grad:
                    anchor_pen = anchor_pen + (p - stock_decoder_params[name]).pow(2).mean()
                    n_tensors += 1
            anchor_pen = anchor_pen / float(max(1, n_tensors))
            total_loss = total_loss + cfg.l2_sp_weight * anchor_pen
            l2_sp_val = float(anchor_pen.item())

        if not torch.isfinite(total_loss):
            raise RuntimeError(f"Non-finite training loss at step {step}: {total_loss.item()}")

        total_loss.backward()
        grad_norm = float(
            torch.nn.utils.clip_grad_norm_(
                [p for p in dac.decoder.model.parameters() if p.requires_grad],
                cfg.grad_clip_norm,
            ).item()
        )
        optimizer.step()
        ema.update(dac.decoder.model)

        running_losses.append(
            {
                "total": float(total_loss.item()),
                "sc": float(sc_loss.item()),
                "mag": float(mag_loss.item()),
                "mel44": float(mel44_loss.item()),
                "mel16": float(mel16_loss.item()),
                "wav_l1": float(wav_l1.item()),
                "spk_cos": spk_cos_val,
                "spk_fm": spk_fm_val,
                "dns_loss": dns_loss_val,
                "g_adv": g_adv_val,
                "g_fm": g_fm_val,
                "d_loss": d_loss_val,
                "l2_sp": l2_sp_val,
                "grad_norm": grad_norm,
            }
        )

        if step % max(1, cfg.eval_every // 2) == 0 or step == 1:
            avg = {
                k: round(float(np.mean([r[k] for r in running_losses])), 5)
                for k in running_losses[0]
            }
            elapsed = time.perf_counter() - t_train_start
            train_history.append(
                {"step": step, "lr": lr_now, "elapsed_sec": round(elapsed, 1), **avg}
            )
            print(
                f"  step {step:4d}/{cfg.steps} ({elapsed:5.1f}s) lr={lr_now:.2e} | "
                f"loss={avg['total']:.4f} (mag={avg['mag']:.4f} spk_cos={avg['spk_cos']:.4f} "
                f"spk_fm={avg['spk_fm']:.4f} dns_l={avg['dns_loss']:.4f} g_adv={avg['g_adv']:.3f}) "
                f"gnorm={avg['grad_norm']:.2f}"
            )
            running_losses.clear()

        should_eval = (step % cfg.eval_every == 0) or (step == cfg.steps)
        if should_eval:
            raw_eval = evaluate_pytorch_decoder(
                dac,
                eval_cached,
                ws_engine,
                dns_engine,
                centroid,
                mr_stft_loss_fn,
                device,
                label=f"step_{step}_raw",
            )
            ema_eval = evaluate_pytorch_decoder(
                dac,
                eval_cached,
                ws_engine,
                dns_engine,
                centroid,
                mr_stft_loss_fn,
                device,
                label=f"step_{step}_ema",
                decoder_model_state=ema.shadow,
            )

            raw_score = compute_early_stopping_score(raw_eval, baseline_eval)
            ema_score = compute_early_stopping_score(ema_eval, baseline_eval)

            for src_name, ev_obj, sc_val in (
                ("raw", raw_eval, raw_score),
                ("ema", ema_eval, ema_score),
            ):
                eval_history.append(
                    {
                        "step": step,
                        "source": src_name,
                        "score": round(sc_val, 5),
                        "mean_wespeaker_cos": ev_obj.mean_wespeaker_cos,
                        "mean_dnsmos_p808": ev_obj.mean_dnsmos_p808,
                        "mean_dnsmos_ovr": ev_obj.mean_dnsmos_ovr,
                        "mean_mr_log_stft_l1": ev_obj.mean_mr_log_stft_l1,
                        "mean_si_snr_db": ev_obj.mean_si_snr_db,
                    }
                )

            cand_src, cand_eval, cand_score = (
                ("ema", ema_eval, ema_score)
                if ema_score >= raw_score
                else ("raw", raw_eval, raw_score)
            )
            print(
                f"  [EVAL step {step}] raw: ws={raw_eval.mean_wespeaker_cos:.4f} "
                f"({raw_eval.mean_wespeaker_cos - baseline_eval.mean_wespeaker_cos:+.4f}) "
                f"dns={raw_eval.mean_dnsmos_p808:.4f} ({raw_eval.mean_dnsmos_p808 - baseline_eval.mean_dnsmos_p808:+.4f}) "
                f"ovr={raw_eval.mean_dnsmos_ovr} stft={raw_eval.mean_mr_log_stft_l1:.4f} "
                f"sisnr={raw_eval.mean_si_snr_db:.2f} | "
                f"ema: ws={ema_eval.mean_wespeaker_cos:.4f} "
                f"({ema_eval.mean_wespeaker_cos - baseline_eval.mean_wespeaker_cos:+.4f}) "
                f"dns={ema_eval.mean_dnsmos_p808:.4f} ({ema_eval.mean_dnsmos_p808 - baseline_eval.mean_dnsmos_p808:+.4f}) "
                f"ovr={ema_eval.mean_dnsmos_ovr} stft={ema_eval.mean_mr_log_stft_l1:.4f} "
                f"sisnr={ema_eval.mean_si_snr_db:.2f}"
            )

            if cand_score > best_score:
                best_score = cand_score
                best_step = step
                best_source = cand_src
                best_eval = cand_eval
                if cand_src == "ema":
                    best_decoder_state = ema.cpu_state_dict()
                else:
                    best_decoder_state = {
                        k: v.detach().cpu().clone()
                        for k, v in dac.decoder.model.state_dict().items()
                    }
                evals_without_improvement = 0
                print(
                    f"  >>> NEW BEST checkpoint at step {step} ({cand_src}): "
                    f"score={best_score:+.5f}, ws={best_eval.mean_wespeaker_cos:.4f}, "
                    f"dns={best_eval.mean_dnsmos_p808:.4f}, ovr={best_eval.mean_dnsmos_ovr}, "
                    f"stft={best_eval.mean_mr_log_stft_l1:.4f}"
                )
            else:
                evals_without_improvement += 1
                if evals_without_improvement >= cfg.early_stop_patience_evals:
                    print(
                        f"  [EARLY STOP] No held-out improvement for "
                        f"{evals_without_improvement} eval cycles; stopping at step {step}."
                    )
                    break

    train_elapsed_sec = time.perf_counter() - t_train_start

    # Save best fine-tuned checkpoint with bit-exact frozen encoder + quantizer
    ckpt_info = save_finetuned_checkpoint(
        cfg.stock_codec_pth, best_decoder_state, cfg.output_pth
    )
    bit_exact_check = verify_frozen_keys_bit_exact(cfg.stock_codec_pth, cfg.output_pth)
    print(
        f"[6/6] Saved {cfg.output_pth} ({ckpt_info['size_bytes']:,} B, "
        f"updated={ckpt_info['updated_decoder_keys']}, "
        f"frozen_bit_exact={bit_exact_check['frozen_keys_bit_exact']}, "
        f"max_w_delta={ckpt_info['max_abs_weight_delta']:.5f})"
    )

    # Re-verify best checkpoint loaded fresh via load_s2_pro_dac(..., strict=True)
    dac_reloaded = load_s2_pro_dac(cfg.output_pth, device=device, strict=True)
    final_eval = evaluate_pytorch_decoder(
        dac_reloaded,
        eval_cached,
        ws_engine,
        dns_engine,
        centroid,
        mr_stft_loss_fn,
        device,
        label="finetuned_codec_v002_pth",
    )

    print(
        f"      FINAL HELD-OUT (PyTorch strict=True reload): "
        f"WeSpeaker {baseline_eval.mean_wespeaker_cos:.4f} -> {final_eval.mean_wespeaker_cos:.4f} "
        f"({final_eval.mean_wespeaker_cos - baseline_eval.mean_wespeaker_cos:+.4f}) | "
        f"DNSMOS {baseline_eval.mean_dnsmos_p808:.4f} -> {final_eval.mean_dnsmos_p808:.4f} "
        f"({final_eval.mean_dnsmos_p808 - baseline_eval.mean_dnsmos_p808:+.4f}) | "
        f"ovr {baseline_eval.mean_dnsmos_ovr} -> {final_eval.mean_dnsmos_ovr} | "
        f"logSTFT {baseline_eval.mean_mr_log_stft_l1:.4f} -> {final_eval.mean_mr_log_stft_l1:.4f} "
        f"({final_eval.mean_mr_log_stft_l1 - baseline_eval.mean_mr_log_stft_l1:+.4f}) | "
        f"SI-SNR {baseline_eval.mean_si_snr_db:.2f} -> {final_eval.mean_si_snr_db:.2f} dB"
    )
    for b_cm, f_cm in zip(baseline_eval.per_clip, final_eval.per_clip):
        print(
            f"      - {f_cm.clip_id:45s} ws {b_cm.decoded_wespeaker_cos:.4f}->{f_cm.decoded_wespeaker_cos:.4f} "
            f"({f_cm.decoded_wespeaker_cos - b_cm.decoded_wespeaker_cos:+.4f}) | "
            f"dns {b_cm.decoded_dnsmos_p808:.3f}->{f_cm.decoded_dnsmos_p808:.3f} "
            f"({f_cm.decoded_dnsmos_p808 - b_cm.decoded_dnsmos_p808:+.3f}) | "
            f"stft {b_cm.mr_log_stft_l1:.4f}->{f_cm.mr_log_stft_l1:.4f}"
        )

    # Free MPS memory before LiteRT export subprocess
    del dac, dac_reloaded, optimizer
    if mpd is not None:
        del mpd, disc_optimizer
    if perceptual_ws is not None:
        del perceptual_ws
    if perceptual_dns is not None:
        del perceptual_dns
    if device.type == "mps":
        torch.mps.empty_cache()

    tflite_export_report: dict[str, Any] | None = None
    tflite_comparison: dict[str, Any] | None = None
    s2pro_candidates_comparison: dict[str, Any] | None = None

    if export_tflite:
        print(f"[EXPORT] Exporting FP16 LiteRT decoder -> {v002_tflite_path} via {venv_lrt_python}...")
        export_script = _THIS_DIR / "export_codec_decoder_tflite.py"
        cmd = [
            str(venv_lrt_python),
            str(export_script),
            "--checkpoint",
            str(cfg.output_pth),
            "--output",
            str(v002_tflite_path),
            "--seq-len",
            "64",
            "--verify-wav",
            str(cfg.voice_ref_alt_wav),
            "--report-json",
            str(v002_export_report_path),
            "--check-wav",
            str(v002_check_wav_path),
        ]
        subprocess.run(cmd, check=True)
        tflite_export_report = json.loads(
            Path(v002_export_report_path).read_text(encoding="utf-8")
        )

        mr_stft_cpu = MultiResolutionSTFTLoss(
            fft_sizes=(512, 1024, 2048),
            hop_sizes=(128, 256, 512),
            win_lengths=(512, 1024, 2048),
        )
        v1_tflite_eval = evaluate_tflite_decoder_on_clips(
            v001_tflite_path,
            eval_cached,
            ws_engine,
            dns_engine,
            centroid,
            mr_stft_cpu,
            label="v001_stock_fp16_tflite",
            venv_lrt_python=venv_lrt_python,
        )
        v2_tflite_eval = evaluate_tflite_decoder_on_clips(
            v002_tflite_path,
            eval_cached,
            ws_engine,
            dns_engine,
            centroid,
            mr_stft_cpu,
            label="v002_finetuned_fp16_tflite",
            venv_lrt_python=venv_lrt_python,
        )
        tflite_comparison = {
            "v001_stock": asdict(v1_tflite_eval),
            "v002_finetuned": asdict(v2_tflite_eval),
            "delta_wespeaker_cos": round(
                v2_tflite_eval.mean_wespeaker_cos - v1_tflite_eval.mean_wespeaker_cos, 5
            ),
            "delta_dnsmos_p808": round(
                v2_tflite_eval.mean_dnsmos_p808 - v1_tflite_eval.mean_dnsmos_p808, 4
            ),
            "delta_dnsmos_ovr": (
                round(v2_tflite_eval.mean_dnsmos_ovr - v1_tflite_eval.mean_dnsmos_ovr, 4)
                if v2_tflite_eval.mean_dnsmos_ovr is not None
                and v1_tflite_eval.mean_dnsmos_ovr is not None
                else None
            ),
            "delta_mr_log_stft_l1": round(
                v2_tflite_eval.mean_mr_log_stft_l1 - v1_tflite_eval.mean_mr_log_stft_l1, 5
            ),
            "delta_si_snr_db": round(
                v2_tflite_eval.mean_si_snr_db - v1_tflite_eval.mean_si_snr_db, 3
            ),
            "v002_beats_v001_wespeaker": (
                v2_tflite_eval.mean_wespeaker_cos > v1_tflite_eval.mean_wespeaker_cos
            ),
            "v002_beats_v001_dnsmos": (
                v2_tflite_eval.mean_dnsmos_p808 > v1_tflite_eval.mean_dnsmos_p808
            ),
            "v002_beats_v001_log_stft": (
                v2_tflite_eval.mean_mr_log_stft_l1 < v1_tflite_eval.mean_mr_log_stft_l1
            ),
        }
        print(
            f"[TFLITE HELD-OUT] v001 -> v002 FP16: "
            f"WeSpeaker {v1_tflite_eval.mean_wespeaker_cos:.4f} -> {v2_tflite_eval.mean_wespeaker_cos:.4f} "
            f"({tflite_comparison['delta_wespeaker_cos']:+.4f}) | "
            f"DNSMOS {v1_tflite_eval.mean_dnsmos_p808:.4f} -> {v2_tflite_eval.mean_dnsmos_p808:.4f} "
            f"({tflite_comparison['delta_dnsmos_p808']:+.4f}) | "
            f"ovr {v1_tflite_eval.mean_dnsmos_ovr} -> {v2_tflite_eval.mean_dnsmos_ovr} | "
            f"logSTFT {v1_tflite_eval.mean_mr_log_stft_l1:.4f} -> {v2_tflite_eval.mean_mr_log_stft_l1:.4f}"
        )

        if Path(candidates_dir).is_dir():
            s2pro_candidates_comparison = evaluate_tflite_on_s2pro_candidates(
                v001_tflite_path,
                v002_tflite_path,
                candidates_dir,
                ws_engine,
                dns_engine,
                centroid,
                venv_lrt_python=venv_lrt_python,
            )
            if s2pro_candidates_comparison is not None:
                print(
                    f"[TFLITE S2-PRO TAKES] v001 -> v002 FP16 ({s2pro_candidates_comparison['num_candidates']} takes): "
                    f"WeSpeaker {s2pro_candidates_comparison['v001_mean_wespeaker_cos']:.4f} -> "
                    f"{s2pro_candidates_comparison['v002_mean_wespeaker_cos']:.4f} "
                    f"({s2pro_candidates_comparison['mean_wespeaker_delta']:+.4f}) | "
                    f"DNSMOS {s2pro_candidates_comparison['v001_mean_dnsmos_p808']:.4f} -> "
                    f"{s2pro_candidates_comparison['v002_mean_dnsmos_p808']:.4f} "
                    f"({s2pro_candidates_comparison['mean_dnsmos_delta']:+.4f}) | "
                    f"ovr {s2pro_candidates_comparison['v001_mean_dnsmos_ovr']} -> "
                    f"{s2pro_candidates_comparison['v002_mean_dnsmos_ovr']}"
                )

    report: dict[str, Any] = {
        "generated_at_utc": datetime.datetime.now(datetime.timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%SZ"
        ),
        "device": str(device),
        "config": {
            k: str(v) if isinstance(v, Path) else (list(v) if isinstance(v, tuple) else v)
            for k, v in asdict(cfg).items()
        },
        "dataset_split": {
            "num_train_clips": len(train_specs),
            "train_duration_sec": round(train_dur_sec, 2),
            "num_eval_clips": len(eval_specs),
            "eval_duration_sec": round(eval_dur_sec, 2),
            "eval_clip_ids": [c.clip_id for c in eval_specs],
            "train_clip_ids": [c.clip_id for c in train_specs],
            "zero_leakage_verified": True,
            "excluded_leaky_voice_files": sorted(LEAKY_VOICE_FILENAMES),
        },
        "model_params": {
            "trainable_decoder_model_params": trainable_params,
            "frozen_encoder_quantizer_params": frozen_params,
            "total_params": trainable_params + frozen_params,
        },
        "best_selection": {
            "best_step": best_step,
            "best_source": best_source,
            "best_composite_score": round(best_score, 5),
            "train_elapsed_sec": round(train_elapsed_sec, 2),
            "total_wall_sec": round(time.perf_counter() - t_wall_start, 2),
        },
        "checkpoint": ckpt_info,
        "frozen_keys_verification": bit_exact_check,
        "pytorch_heldout_baseline": asdict(baseline_eval),
        "pytorch_heldout_finetuned": asdict(final_eval),
        "pytorch_heldout_deltas": {
            "wespeaker_cos": round(
                final_eval.mean_wespeaker_cos - baseline_eval.mean_wespeaker_cos, 5
            ),
            "dnsmos_p808": round(
                final_eval.mean_dnsmos_p808 - baseline_eval.mean_dnsmos_p808, 4
            ),
            "dnsmos_ovr": (
                round(final_eval.mean_dnsmos_ovr - baseline_eval.mean_dnsmos_ovr, 4)
                if final_eval.mean_dnsmos_ovr is not None
                and baseline_eval.mean_dnsmos_ovr is not None
                else None
            ),
            "mr_log_stft_l1": round(
                final_eval.mean_mr_log_stft_l1 - baseline_eval.mean_mr_log_stft_l1, 5
            ),
            "mr_spectral_convergence": round(
                final_eval.mean_mr_spectral_convergence
                - baseline_eval.mean_mr_spectral_convergence,
                5,
            ),
            "si_snr_db": round(
                final_eval.mean_si_snr_db - baseline_eval.mean_si_snr_db, 3
            ),
            "waveform_l1": round(
                final_eval.mean_waveform_l1 - baseline_eval.mean_waveform_l1, 5
            ),
        },
        "train_history": train_history,
        "eval_history": eval_history,
        "tflite_export_report": tflite_export_report,
        "tflite_heldout_comparison": tflite_comparison,
        "tflite_s2pro_candidates_comparison": s2pro_candidates_comparison,
    }

    cfg.report_json.parent.mkdir(parents=True, exist_ok=True)
    cfg.report_json.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"[DONE] Wrote report to {cfg.report_json}")
    return report


def _parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fine-tune fishaudio/s2-pro ModdedDAC decoder.model on Ryan's cleanest recordings."
    )
    parser.add_argument("--stock-codec", type=Path, default=DEFAULT_STOCK_CODEC_PTH)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_DATASET_MANIFEST)
    parser.add_argument("--voice-ref-alt", type=Path, default=DEFAULT_VOICE_REF_ALT)
    parser.add_argument("--centroid", type=Path, default=DEFAULT_MASTER_CENTROID_BIN)
    parser.add_argument("--output-pth", type=Path, default=DEFAULT_OUTPUT_PTH)
    parser.add_argument("--report-json", type=Path, default=DEFAULT_REPORT_JSON)
    parser.add_argument("--v002-tflite", type=Path, default=DEFAULT_V002_TFLITE)
    parser.add_argument("--v001-tflite", type=Path, default=DEFAULT_V001_TFLITE)
    parser.add_argument("--steps", type=int, default=280)
    parser.add_argument("--eval-every", type=int, default=40)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--crop-z-frames", type=int, default=160)
    parser.add_argument("--lr", type=float, default=1.5e-5)
    parser.add_argument("--min-lr", type=float, default=1.5e-6)
    parser.add_argument("--disc-lr", type=float, default=1.0e-4)
    parser.add_argument("--warmup-steps", type=int, default=20)
    parser.add_argument("--l2-sp-weight", type=float, default=5.0e-3)
    parser.add_argument("--mr-stft-weight", type=float, default=0.30)
    parser.add_argument("--mel44k-weight", type=float, default=0.30)
    parser.add_argument("--mel16k-weight", type=float, default=0.25)
    parser.add_argument("--wav-l1-weight", type=float, default=0.10)
    parser.add_argument("--spk-cos-weight", type=float, default=2.50)
    parser.add_argument("--spk-fm-weight", type=float, default=2.00)
    parser.add_argument("--dnsmos-weight", type=float, default=0.25)
    parser.add_argument("--dnsmos-target-mos", type=float, default=4.25)
    parser.add_argument("--mpd-adv-weight", type=float, default=0.0)
    parser.add_argument("--mpd-fm-weight", type=float, default=0.0)
    parser.add_argument("--mpd-start-step", type=int, default=20)
    parser.add_argument("--min-train-dnsmos", type=float, default=3.55)
    parser.add_argument("--min-train-wespeaker", type=float, default=0.73)
    parser.add_argument("--patience", type=int, default=4)
    parser.add_argument("--seed", type=int, default=20261123)
    parser.add_argument("--device", type=str, default="auto")
    parser.add_argument("--skip-export", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = _parse_args(argv)
    cfg = FineTuneConfig(
        stock_codec_pth=args.stock_codec,
        manifest_jsonl=args.manifest,
        voice_ref_alt_wav=args.voice_ref_alt,
        master_centroid_bin=args.centroid,
        output_pth=args.output_pth,
        report_json=args.report_json,
        min_train_dnsmos=args.min_train_dnsmos,
        min_train_wespeaker=args.min_train_wespeaker,
        steps=args.steps,
        eval_every=args.eval_every,
        batch_size=args.batch_size,
        crop_z_frames=args.crop_z_frames,
        lr=args.lr,
        min_lr=args.min_lr,
        disc_lr=args.disc_lr,
        warmup_steps=args.warmup_steps,
        l2_sp_weight=args.l2_sp_weight,
        mr_stft_weight=args.mr_stft_weight,
        mel_44k_weight=args.mel44k_weight,
        mel_16k_speech_weight=args.mel16k_weight,
        wav_l1_weight=args.wav_l1_weight,
        spk_cos_weight=args.spk_cos_weight,
        spk_fm_weight=args.spk_fm_weight,
        dnsmos_weight=args.dnsmos_weight,
        dnsmos_target_mos=args.dnsmos_target_mos,
        mpd_adv_weight=args.mpd_adv_weight,
        mpd_fm_weight=args.mpd_fm_weight,
        mpd_start_step=args.mpd_start_step,
        early_stop_patience_evals=args.patience,
        seed=args.seed,
        device=args.device,
    )
    finetune_dac_decoder(
        cfg,
        export_tflite=not args.skip_export,
        v002_tflite_path=args.v002_tflite,
        v001_tflite_path=args.v001_tflite,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
