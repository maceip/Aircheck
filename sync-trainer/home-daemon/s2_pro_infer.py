#!/usr/bin/env python3
"""Complete local Apple Silicon (MPS BF16) fishaudio/s2-pro Dual-AR + LoRA inference engine.

Implements the exact `fish_qwen3_omni` Dual-AR architecture from `fishaudio/s2-pro`:
1. Slow AR (36-layer `fish_qwen3` with interleaved RoPE, Q/K RMSNorm, GQA 32/8,
   tied embeddings, codebook embedding sum scaled by `1/sqrt(11)`, and `norm_fastlayer_input=True`)
   with exact RoPE-unpermuted Rank-32 PEFT LoRA merging (`lora-work-v001/peft_lora_adapter/`).
2. Fast AR (4-layer `fish_qwen3_audio_decoder`, 10 codebooks, `dim=2560`, `n_head=32`,
   `n_local_heads=8`, `attention_qk_norm=False`) predicting residual codebooks `1..9`
   per frame conditioned on Slow AR's post-norm hidden state + codebook 0.
3. Exact S2-Pro reference-conditioned prompt formatting (matching upstream `fish-speech`
   `Conversation` / `ContentSequence` + `chat_template.jinja` delimiters) with ModdedDAC
   44.1 kHz `[10, T_ref]` reference audio codes.
4. Chunked causal overlap-add decoding through the real exported FP16 LiteRT codec decoder
   (`codec_decoder_fp16.tflite`, `[1, 10, 64] -> [1, 1, 131072]`) with optional PyTorch
   `ModdedDAC.from_indices` numerical parity verification.
"""

from __future__ import annotations

import argparse
import importlib
import importlib.util
import json
import math
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

import numpy as np


def _disable_incompatible_torch_extras() -> None:
    """Guard transformers imports against optional packages built for a different torch ABI."""
    broken: list[str] = []
    for pkg, fn_name in (
        ("torchao", "is_torchao_available"),
        ("torchaudio", "is_torchaudio_available"),
        ("torchvision", "is_torchvision_available"),
    ):
        if importlib.util.find_spec(pkg) is None:
            continue
        try:
            importlib.import_module(pkg)
        except Exception:
            broken.append(fn_name)

    if not broken:
        return

    import transformers.utils as _tu
    import transformers.utils.import_utils as _tiu

    for fn_name in broken:
        setattr(_tiu, fn_name, lambda *a, **k: False)
        setattr(_tu, fn_name, lambda *a, **k: False)


# Canonical fishaudio/s2-pro token IDs & codec constants
SEMANTIC_START_TOKEN_ID: int = 151678
SEMANTIC_END_TOKEN_ID: int = 155773
NUM_SEMANTIC_TOKENS: int = 4096
RESIDUAL_CODEBOOK_SIZE: int = 1024
NUM_CODEBOOKS: int = 10
IM_START_TOKEN_ID: int = 151644
IM_END_TOKEN_ID: int = 151645
VOICE_TOKEN_ID: int = 151673
PAD_TOKEN_ID: int = 151669
SAMPLE_RATE_HZ: int = 44100
FRAME_LENGTH_SAMPLES: int = 2048
TFLITE_CHUNK_FRAMES: int = 64

# Repetition Aware Sampling (RAS) constants matching upstream fish-speech
RAS_WIN_SIZE: int = 10
RAS_HIGH_TEMP: float = 1.0
RAS_HIGH_TOP_P: float = 0.9

DEFAULT_S2_PRO_HF_DIR = Path("/Users/mac/Downloads/voicestudio-models/s2-pro-hf")
DEFAULT_LORA_DIR = Path("/Users/mac/Downloads/voicestudio-models/lora-work-v001/peft_lora_adapter")
DEFAULT_CODEC_PTH = Path("/Users/mac/Downloads/voicestudio-models/s2-pro/codec.pth")
DEFAULT_FINETUNED_CODEC_PTH = Path(
    "/Users/mac/Downloads/voicestudio-models/dac-finetune-v002/codec_finetuned_v002.pth"
)
DEFAULT_CODEC_TFLITE = Path("/Users/mac/Downloads/voicestudio-models/bundles/v001/codec_decoder_fp16.tflite")
DEFAULT_VENV_LRT_PYTHON = Path("/Users/mac/.venv-lrt/bin/python")

_TFLITE_INTERPRETER_CACHE_LOCK = threading.Lock()
_TFLITE_INTERPRETER_CACHE: dict[
    tuple[str, int],
    tuple[Any, int, int, tuple[int, ...], threading.Lock],
] = {}
_REF_CODES_CACHE_LOCK = threading.Lock()
_REF_CODES_CACHE: dict[tuple[str, int, int, str, float | None], tuple[np.ndarray, float]] = {}


_BUNDLE_VER_RE = re.compile(r"(?:^|[_-])v(\d+)(?:$|[._-])", flags=re.IGNORECASE)


def _extract_custom_models_root(tflite_path: Path) -> Path | None:
    """Return custom `models_root` if `tflite_path` is inside a non-default `bundles/<ver>/` tree."""
    p = Path(tflite_path)
    if len(p.parents) >= 3 and p.parents[1].name == "bundles":
        models_root = p.parents[2]
        if models_root.resolve() != DEFAULT_CODEC_PTH.parents[1].resolve():
            return models_root
    return None


def _is_v002_tflite_bundle(tflite_path: Path) -> tuple[bool, Path | None]:
    """Detect whether `tflite_path` targets a v002+ fine-tuned DAC bundle and locate its `.pth`."""
    p = Path(tflite_path)
    parent = p.parent
    ver_match = _BUNDLE_VER_RE.search(parent.name) or _BUNDLE_VER_RE.search(p.name)
    version_num = int(ver_match.group(1)) if ver_match else 0
    is_v2 = version_num >= 2

    export_report = parent / "codec_export_report.json"
    if export_report.is_file():
        try:
            rep = json.loads(export_report.read_text(encoding="utf-8"))
            ckpt_raw = str(rep.get("checkpoint_path") or "").strip()
            if ckpt_raw:
                ckpt_path = Path(ckpt_raw)
                if "v002" in ckpt_raw.lower() or "dac-finetune" in ckpt_raw.lower():
                    is_v2 = True
                    if ckpt_path.is_file():
                        return True, ckpt_path
        except Exception:
            pass

    bundle_manifest = parent / "bundle_manifest.json"
    if bundle_manifest.is_file():
        try:
            man = json.loads(bundle_manifest.read_text(encoding="utf-8"))
            bver = str(man.get("bundle_version") or "")
            lver = int(man.get("lora_version") or 0)
            bver_match = _BUNDLE_VER_RE.search(bver)
            bver_num = int(bver_match.group(1)) if bver_match else 0
            if lver >= 2 or bver_num >= 2 or "v002" in bver.lower():
                is_v2 = True
                version_num = max(version_num, lver, bver_num, 2)
        except Exception:
            pass

    if not is_v2:
        return False, None

    custom_root = _extract_custom_models_root(p)
    # Derive `<models_root>/dac-finetune-v002/codec_finetuned_v002.pth`
    candidates: list[Path] = []
    if len(p.parents) >= 3 and p.parents[1].name == "bundles":
        models_root = p.parents[2]
        if version_num >= 2:
            candidates.append(
                models_root / f"dac-finetune-v{version_num:03d}" / f"codec_finetuned_v{version_num:03d}.pth"
            )
        candidates.append(models_root / "dac-finetune-v002" / "codec_finetuned_v002.pth")
    elif len(p.parents) >= 2:
        candidates.append(p.parents[1] / "dac-finetune-v002" / "codec_finetuned_v002.pth")
    if custom_root is None:
        candidates.append(DEFAULT_FINETUNED_CODEC_PTH)

    for cand in candidates:
        if cand.is_file():
            return True, cand
    return True, None


def resolve_pytorch_dac_checkpoint(
    codec_pth: Path | None = None,
    *,
    tflite_path: Path | None = None,
) -> Path:
    """Resolve the PyTorch ModdedDAC checkpoint matching the active TFLite bundle.

    - Explicit `codec_pth` != `DEFAULT_CODEC_PTH` is honored.
    - When `tflite_path` points to a `v002` bundle and `codec_pth` is None or `DEFAULT_CODEC_PTH`,
      prefer `<models_root>/dac-finetune-v002/codec_finetuned_v002.pth`. If `tflite_path` lives in
      a custom `bundles/<ver>/` root lacking `dac-finetune-v002`, check `<models_root>/s2-pro/codec.pth`
      and never leak to host-global `DEFAULT_FINETUNED_CODEC_PTH`.
    - When both `codec_pth` and `tflite_path` are None, check `DEFAULT_CODEC_TFLITE`.
    """
    if codec_pth is not None and Path(codec_pth) != DEFAULT_CODEC_PTH:
        return Path(codec_pth)

    if tflite_path is not None:
        tp = Path(tflite_path)
        custom_root = _extract_custom_models_root(tp)
        is_v2, matched_pth = _is_v002_tflite_bundle(tp)
        if is_v2:
            if matched_pth is not None and matched_pth.is_file():
                return matched_pth
            if custom_root is None and DEFAULT_FINETUNED_CODEC_PTH.is_file():
                return DEFAULT_FINETUNED_CODEC_PTH
        if custom_root is not None:
            custom_base = custom_root / "s2-pro" / "codec.pth"
            if custom_base.is_file():
                return custom_base
        return Path(codec_pth) if codec_pth is not None else DEFAULT_CODEC_PTH

    if codec_pth is not None:
        return Path(codec_pth)

    is_v2_default, matched_default = _is_v002_tflite_bundle(DEFAULT_CODEC_TFLITE)
    if is_v2_default:
        if matched_default is not None and matched_default.is_file():
            return matched_default
        if DEFAULT_FINETUNED_CODEC_PTH.is_file():
            return DEFAULT_FINETUNED_CODEC_PTH
    return DEFAULT_CODEC_PTH


@dataclass(frozen=True)
class DualARConfig:
    """Exact configuration for fishaudio/s2-pro (`fish_qwen3_omni`) Dual-AR."""

    vocab_size: int = 155776
    n_layer: int = 36
    n_head: int = 32
    n_local_heads: int = 8
    head_dim: int = 128
    dim: int = 2560
    intermediate_size: int = 9728
    rope_base: float = 1_000_000.0
    norm_eps: float = 1e-6
    max_seq_len: int = 4096
    tie_word_embeddings: bool = True
    attention_qkv_bias: bool = False
    attention_o_bias: bool = False
    attention_qk_norm: bool = True

    semantic_begin_id: int = SEMANTIC_START_TOKEN_ID
    semantic_end_id: int = SEMANTIC_END_TOKEN_ID
    im_end_id: int = IM_END_TOKEN_ID
    scale_codebook_embeddings: bool = True
    norm_fastlayer_input: bool = True

    codebook_size: int = NUM_SEMANTIC_TOKENS
    residual_codebook_size: int = RESIDUAL_CODEBOOK_SIZE
    num_codebooks: int = NUM_CODEBOOKS
    n_fast_layer: int = 4
    fast_dim: int = 2560
    fast_n_head: int = 32
    fast_n_local_heads: int = 8
    fast_head_dim: int = 128
    fast_intermediate_size: int = 9728
    fast_rope_base: float = 1_000_000.0
    fast_norm_eps: float = 1e-6
    fast_attention_qkv_bias: bool = False
    fast_attention_o_bias: bool = False
    fast_attention_qk_norm: bool = False

    @classmethod
    def from_s2_pro_config(
        cls, config_data: Mapping[str, Any], max_seq_len: int = 4096
    ) -> "DualARConfig":
        tc = config_data["text_config"]
        adc = config_data["audio_decoder_config"]
        return cls(
            vocab_size=int(tc["vocab_size"]),
            n_layer=int(tc["n_layer"]),
            n_head=int(tc["n_head"]),
            n_local_heads=int(tc.get("n_local_heads", tc["n_head"])),
            head_dim=int(tc["head_dim"]),
            dim=int(tc["dim"]),
            intermediate_size=int(tc["intermediate_size"]),
            rope_base=float(tc.get("rope_base", 1_000_000.0)),
            norm_eps=float(tc.get("norm_eps", 1e-6)),
            max_seq_len=min(int(max_seq_len), int(tc.get("max_seq_len", 32768))),
            tie_word_embeddings=bool(tc.get("tie_word_embeddings", True)),
            attention_qkv_bias=bool(tc.get("attention_qkv_bias", False)),
            attention_o_bias=bool(tc.get("attention_o_bias", False)),
            attention_qk_norm=bool(tc.get("attention_qk_norm", True)),
            semantic_begin_id=int(config_data.get("semantic_start_token_id", SEMANTIC_START_TOKEN_ID)),
            semantic_end_id=int(config_data.get("semantic_end_token_id", SEMANTIC_END_TOKEN_ID)),
            im_end_id=int(config_data.get("eos_token_id", IM_END_TOKEN_ID)),
            scale_codebook_embeddings=True,
            norm_fastlayer_input=True,
            codebook_size=int(adc["vocab_size"]),
            residual_codebook_size=RESIDUAL_CODEBOOK_SIZE,
            num_codebooks=int(adc["num_codebooks"]),
            n_fast_layer=int(adc["n_layer"]),
            fast_dim=int(adc["dim"]),
            fast_n_head=int(adc["n_head"]),
            fast_n_local_heads=int(adc.get("n_local_heads", adc["n_head"])),
            fast_head_dim=int(adc["head_dim"]),
            fast_intermediate_size=int(adc["intermediate_size"]),
            fast_rope_base=float(adc.get("rope_base", tc.get("rope_base", 1_000_000.0))),
            fast_norm_eps=float(adc.get("norm_eps", tc.get("norm_eps", 1e-6))),
            fast_attention_qkv_bias=bool(adc.get("attention_qkv_bias", False)),
            fast_attention_o_bias=bool(adc.get("attention_o_bias", False)),
            fast_attention_qk_norm=bool(adc.get("attention_qk_norm", False)),
        )

    @classmethod
    def from_pretrained(cls, s2_pro_hf_dir: Path, max_seq_len: int = 4096) -> "DualARConfig":
        cfg_path = Path(s2_pro_hf_dir) / "config.json"
        if not cfg_path.is_file():
            raise FileNotFoundError(f"Missing s2-pro config.json: {cfg_path}")
        return cls.from_s2_pro_config(
            json.loads(cfg_path.read_text(encoding="utf-8")),
            max_seq_len=max_seq_len,
        )


@dataclass
class LoRAMergeStats:
    """Empirical diagnostics proving Ryan's Rank-32 PEFT LoRA was merged cleanly."""

    adapter_dir: str
    lora_rank: int
    lora_alpha: float
    lora_scale: float
    effective_scale: float
    merged_modules: int
    consumed_tensors: int
    max_delta_abs: float
    mean_delta_l2: float


@dataclass
class GenerationResult:
    """Output of a single Dual-AR synthesis take."""

    codes: np.ndarray  # int32 [10, T_gen]
    seed: int
    temperature: float
    top_p: float
    top_k: int
    prompt_tokens: int
    generated_frames: int
    hit_eos: bool
    elapsed_sec: float
    frames_per_sec: float
    residual_clamped_count: int


# ---------------------------------------------------------------------------
# LiteRT FP16 Chunked Overlap-Add Decoder (works in BOTH .venv and .venv-lrt!)
# ---------------------------------------------------------------------------


def plan_chunked_decode_windows(
    total_frames: int,
    chunk_frames: int = TFLITE_CHUNK_FRAMES,
    overlap_frames: int = 32,
    xfade_frames: int = 1,
) -> list[tuple[int, int, int, int]]:
    """Compute causal sliding windows for fixed-length LiteRT decoding.

    Returns a list of `(start_frame, valid_frames, keep_local_start, xfade_local_len)` tuples:
    - `start_frame`: global start index in `codes` (`[10, total_frames]`)
    - `valid_frames`: number of genuine non-padded frames in this chunk (`<= chunk_frames`)
    - `keep_local_start`: local frame index where crossfade begins (0 for first chunk,
      `overlap_frames - xfade_frames` for subsequent chunks)
    - `xfade_local_len`: number of frames to raised-cosine crossfade with previous output
      (0 for first chunk, `xfade_frames` for subsequent chunks)
    """
    if total_frames <= 0:
        raise ValueError(f"total_frames must be positive, got {total_frames}")
    if chunk_frames <= 0:
        raise ValueError(f"chunk_frames must be positive, got {chunk_frames}")
    if overlap_frames < 0 or overlap_frames >= chunk_frames:
        raise ValueError(f"overlap_frames ({overlap_frames}) must be in [0, {chunk_frames - 1}]")
    if xfade_frames < 0 or xfade_frames > overlap_frames:
        raise ValueError(f"xfade_frames ({xfade_frames}) must be in [0, {overlap_frames}]")

    if total_frames <= chunk_frames:
        return [(0, total_frames, 0, 0)]

    windows: list[tuple[int, int, int, int]] = [(0, chunk_frames, 0, 0)]
    covered = chunk_frames
    while covered < total_frames:
        start = covered - overlap_frames
        valid = min(chunk_frames, total_frames - start)
        keep_local_start = overlap_frames - xfade_frames
        windows.append((start, valid, keep_local_start, xfade_frames))
        covered = start + valid
    return windows


def _stitch_decoded_windows(
    window_pcms: Sequence[np.ndarray],
    windows: Sequence[tuple[int, int, int, int]],
    total_frames: int,
    frame_length_samples: int = FRAME_LENGTH_SAMPLES,
) -> np.ndarray:
    """Stitch causal decoded chunk waveforms via raised-cosine overlap-add."""
    out = np.zeros(total_frames * frame_length_samples, dtype=np.float32)
    out_cursor = 0

    for pcm_chunk, (start, valid, keep_local_start, xfade_local_len) in zip(window_pcms, windows):
        flat = np.asarray(pcm_chunk, dtype=np.float32).reshape(-1)
        if xfade_local_len == 0:
            n_samp = valid * frame_length_samples
            out[0:n_samp] = flat[0:n_samp]
            out_cursor = n_samp
            continue

        xfade_samples = xfade_local_len * frame_length_samples
        local_xfade_start = keep_local_start * frame_length_samples
        local_valid_end = valid * frame_length_samples

        # Raised-cosine fade-in weight [0 -> 1] across overlap junction
        t = np.linspace(0.0, math.pi, xfade_samples, endpoint=False, dtype=np.float64)
        fade_in = (0.5 * (1.0 - np.cos(t))).astype(np.float32)
        fade_out = (1.0 - fade_in).astype(np.float32)

        global_xfade_start = out_cursor - xfade_samples
        out[global_xfade_start:out_cursor] = (
            out[global_xfade_start:out_cursor] * fade_out
            + flat[local_xfade_start : local_xfade_start + xfade_samples] * fade_in
        )

        new_local_start = local_xfade_start + xfade_samples
        new_samples = local_valid_end - new_local_start
        if new_samples > 0:
            out[out_cursor : out_cursor + new_samples] = flat[new_local_start:local_valid_end]
            out_cursor += new_samples

    assert out_cursor == total_frames * frame_length_samples, (
        f"Stitched sample count mismatch: {out_cursor} != {total_frames * frame_length_samples}"
    )
    return out


def _get_cached_tflite_interpreter(
    model_path: Path,
    *,
    num_threads: int,
) -> tuple[Any, int, int, tuple[int, ...], threading.Lock]:
    from ai_edge_litert.interpreter import Interpreter

    cache_key = (str(model_path.resolve()), int(num_threads))
    with _TFLITE_INTERPRETER_CACHE_LOCK:
        cached = _TFLITE_INTERPRETER_CACHE.get(cache_key)
        if cached is not None:
            return cached
        interp = Interpreter(model_path=str(model_path), num_threads=int(num_threads))
        in_details = interp.get_input_details()
        out_details = interp.get_output_details()
        interp.allocate_tensors()
        in_idx = int(in_details[0]["index"])
        out_idx = int(out_details[0]["index"])
        expected_shape = tuple(int(d) for d in in_details[0]["shape"])
        entry = (interp, in_idx, out_idx, expected_shape, threading.Lock())
        _TFLITE_INTERPRETER_CACHE[cache_key] = entry
        return entry


def decode_codes_tflite_inprocess(
    codes_list: Sequence[np.ndarray],
    tflite_path: Path = DEFAULT_CODEC_TFLITE,
    *,
    chunk_frames: int = TFLITE_CHUNK_FRAMES,
    overlap_frames: int = 32,
    xfade_frames: int = 1,
    num_threads: int = 4,
) -> list[tuple[np.ndarray, dict[str, Any]]]:
    """Decode one or more `[10, T]` code arrays in-process via `ai_edge_litert.interpreter`.

    Caches the loaded `Interpreter` thread-safely by `(resolved_path, num_threads)` and
    guards invocation with a per-interpreter `threading.Lock`.
    """
    model_path = Path(tflite_path)
    if not model_path.is_file():
        raise FileNotFoundError(f"Missing codec TFLite model: {model_path}")

    interp, in_idx, out_idx, expected_shape, interp_lock = _get_cached_tflite_interpreter(
        model_path, num_threads=num_threads
    )
    if expected_shape != (1, NUM_CODEBOOKS, chunk_frames):
        raise ValueError(
            f"Unexpected TFLite input shape {expected_shape}, expected (1, {NUM_CODEBOOKS}, {chunk_frames})"
        )

    results: list[tuple[np.ndarray, dict[str, Any]]] = []
    with interp_lock:
        for raw_codes in codes_list:
            codes = np.asarray(raw_codes, dtype=np.int32)
            if codes.ndim == 3:
                codes = codes[0]
            if codes.ndim != 2 or codes.shape[0] != NUM_CODEBOOKS:
                raise ValueError(f"Expected codes shape [10, T], got {codes.shape}")

            # Clamp strictly to valid ModdedDAC codebook ranges before TFLite embedding lookup
            clamped = codes.copy()
            clamped[0] = np.clip(clamped[0], 0, NUM_SEMANTIC_TOKENS - 1)
            clamped[1:] = np.clip(clamped[1:], 0, RESIDUAL_CODEBOOK_SIZE - 1)

            total_frames = int(clamped.shape[1])
            windows = plan_chunked_decode_windows(
                total_frames,
                chunk_frames=chunk_frames,
                overlap_frames=overlap_frames,
                xfade_frames=xfade_frames,
            )

            window_pcms: list[np.ndarray] = []
            t0 = time.perf_counter()
            for start, valid, _, _ in windows:
                chunk = np.zeros((1, NUM_CODEBOOKS, chunk_frames), dtype=np.int32)
                seg = clamped[:, start : start + valid]
                chunk[0, :, :valid] = seg
                if valid < chunk_frames:
                    # Edge-pad trailing frames with the final valid frame so causal convs stay smooth
                    chunk[0, :, valid:] = seg[:, -1:]
                interp.set_tensor(in_idx, np.ascontiguousarray(chunk, dtype=np.int32))
                interp.invoke()
                pcm_out = np.array(interp.get_tensor(out_idx), copy=True, dtype=np.float32).reshape(-1)
                window_pcms.append(pcm_out)

            elapsed_ms = (time.perf_counter() - t0) * 1000.0
            stitched = _stitch_decoded_windows(
                window_pcms,
                windows,
                total_frames=total_frames,
                frame_length_samples=FRAME_LENGTH_SAMPLES,
            )
            meta = {
                "decoder": "ai_edge_litert_fp16",
                "tflite_path": str(model_path),
                "total_frames": total_frames,
                "num_chunks": len(windows),
                "chunk_frames": chunk_frames,
                "overlap_frames": overlap_frames,
                "xfade_frames": xfade_frames,
                "output_samples": int(stitched.size),
                "duration_sec": round(float(stitched.size) / float(SAMPLE_RATE_HZ), 4),
                "decode_ms": round(elapsed_ms, 2),
            }
            results.append((stitched, meta))

    return results


def decode_codes_batch_tflite(
    codes_list: Sequence[np.ndarray],
    tflite_path: Path = DEFAULT_CODEC_TFLITE,
    *,
    venv_lrt_python: Path = DEFAULT_VENV_LRT_PYTHON,
    chunk_frames: int = TFLITE_CHUNK_FRAMES,
    overlap_frames: int = 32,
    xfade_frames: int = 1,
    num_threads: int = 4,
) -> list[tuple[np.ndarray, dict[str, Any]]]:
    """Decode one or more `[10, T]` code arrays using `codec_decoder_fp16.tflite`.

    Runs in-process when `ai_edge_litert` is available in the current interpreter, or
    cleanly delegates a single batch invocation to `venv_lrt_python` (`/Users/mac/.venv-lrt/bin/python`).
    """
    try:
        import ai_edge_litert  # noqa: F401

        return decode_codes_tflite_inprocess(
            codes_list,
            tflite_path=tflite_path,
            chunk_frames=chunk_frames,
            overlap_frames=overlap_frames,
            xfade_frames=xfade_frames,
            num_threads=num_threads,
        )
    except ImportError:
        pass

    py_bin = Path(venv_lrt_python)
    if not py_bin.is_file():
        raise FileNotFoundError(
            f"ai_edge_litert not in current Python and venv-lrt missing: {py_bin}"
        )

    with tempfile.TemporaryDirectory(prefix="s2pro_lrt_decode_") as tmp_str:
        tmp_dir = Path(tmp_str)
        job_items: list[dict[str, str]] = []
        for idx, codes in enumerate(codes_list):
            c_path = tmp_dir / f"codes_{idx}.npy"
            p_path = tmp_dir / f"pcm_{idx}.npy"
            np.save(c_path, np.asarray(codes, dtype=np.int32))
            job_items.append({"codes_npy": str(c_path), "pcm_npy": str(p_path)})

        manifest_in = tmp_dir / "batch_in.json"
        manifest_out = tmp_dir / "batch_out.json"
        manifest_in.write_text(
            json.dumps(
                {
                    "tflite_path": str(tflite_path),
                    "chunk_frames": chunk_frames,
                    "overlap_frames": overlap_frames,
                    "xfade_frames": xfade_frames,
                    "num_threads": num_threads,
                    "items": job_items,
                },
                indent=2,
            ),
            encoding="utf-8",
        )

        cmd = [
            str(py_bin),
            str(Path(__file__).resolve()),
            "--decode-tflite-batch",
            str(manifest_in),
            "--decode-tflite-out",
            str(manifest_out),
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        if proc.returncode != 0:
            raise RuntimeError(
                f"LiteRT batch decode failed (rc={proc.returncode}):\n"
                f"STDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
            )

        metas = json.loads(manifest_out.read_text(encoding="utf-8"))
        out: list[tuple[np.ndarray, dict[str, Any]]] = []
        for item, meta in zip(job_items, metas):
            pcm = np.load(item["pcm_npy"]).astype(np.float32, copy=False)
            out.append((pcm, meta))
        return out


# ---------------------------------------------------------------------------
# PyTorch Dual-AR Architecture + LoRA RoPE-Inverse Merge
# ---------------------------------------------------------------------------


def _run_decode_tflite_batch_cli(manifest_in: Path, manifest_out: Path) -> int:
    payload = json.loads(manifest_in.read_text(encoding="utf-8"))
    items = payload["items"]
    codes_list = [np.load(it["codes_npy"]) for it in items]
    decoded = decode_codes_tflite_inprocess(
        codes_list,
        tflite_path=Path(payload["tflite_path"]),
        chunk_frames=int(payload.get("chunk_frames", TFLITE_CHUNK_FRAMES)),
        overlap_frames=int(payload.get("overlap_frames", 32)),
        xfade_frames=int(payload.get("xfade_frames", 1)),
        num_threads=int(payload.get("num_threads", 4)),
    )
    metas: list[dict[str, Any]] = []
    for it, (pcm, meta) in zip(items, decoded):
        np.save(it["pcm_npy"], np.ascontiguousarray(pcm, dtype=np.float32))
        metas.append(meta)
    manifest_out.write_text(json.dumps(metas, indent=2), encoding="utf-8")
    return 0


# Defer torch / transformers imports when running inside `.venv-lrt` for `--decode-tflite-batch`
if "--decode-tflite-batch" not in sys.argv:
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
    from safetensors import safe_open


def permute_neox_to_interleaved_rope_weight(
    weight: "torch.Tensor", n_heads: int, head_dim: int
) -> "torch.Tensor":
    """Exact inverse of `train_initial_lora.unpermute_interleaved_rope_weight`.

    Converts a split-half NeoX Q/K projection or LoRA delta `[n_heads * head_dim, in_dim]`
    back into `fish_qwen3`'s interleaved RoPE row layout `[H, D//2, 2, in_dim]`.
    """
    expected_rows = n_heads * head_dim
    if weight.ndim != 2 or weight.shape[0] != expected_rows:
        raise ValueError(
            f"Expected 2D weight with {expected_rows} rows, got shape {tuple(weight.shape)}"
        )
    if head_dim % 2 != 0:
        raise ValueError(f"head_dim must be even, got {head_dim}")
    in_dim = weight.shape[1]
    return (
        weight.view(n_heads, 2, head_dim // 2, in_dim)
        .transpose(1, 2)
        .contiguous()
        .view(expected_rows, in_dim)
    )


def precompute_freqs_cis(seq_len: int, head_dim: int, base: float = 1_000_000.0) -> "torch.Tensor":
    """Precompute float32 interleaved RoPE cos/sin cache `[seq_len, head_dim // 2, 2]`."""
    freqs = 1.0 / (
        base ** (torch.arange(0, head_dim, 2, dtype=torch.float32)[: head_dim // 2] / float(head_dim))
    )
    t = torch.arange(seq_len, dtype=torch.float32)
    angles = torch.outer(t, freqs)
    return torch.stack([torch.cos(angles), torch.sin(angles)], dim=-1).contiguous()


def apply_rotary_emb(x: "torch.Tensor", freqs_cis: "torch.Tensor") -> "torch.Tensor":
    """Apply interleaved rotary embeddings matching upstream `fish-speech/llama.py`."""
    xshaped = x.float().reshape(*x.shape[:-1], -1, 2)
    fc = freqs_cis.view(1, xshaped.size(1), 1, xshaped.size(3), 2)
    x_out = torch.stack(
        [
            xshaped[..., 0] * fc[..., 0] - xshaped[..., 1] * fc[..., 1],
            xshaped[..., 1] * fc[..., 0] + xshaped[..., 0] * fc[..., 1],
        ],
        dim=-1,
    )
    return x_out.flatten(3).type_as(x)


def remap_fish_qwen3_omni_key(key: str) -> str:
    """Map raw `fish_qwen3_omni` safetensors key to `S2ProDualAR` state_dict key."""
    if key.startswith("text_model.model."):
        return key[len("text_model.model.") :]
    if key.startswith("audio_decoder."):
        suffix = key[len("audio_decoder.") :]
        if suffix.startswith("codebook_embeddings."):
            return suffix
        return "fast_" + suffix
    return key


if "--decode-tflite-batch" not in sys.argv:

    class RMSNorm(nn.Module):
        def __init__(self, dim: int, eps: float = 1e-6) -> None:
            super().__init__()
            self.eps = eps
            self.weight = nn.Parameter(torch.ones(dim))

        def forward(self, x: torch.Tensor) -> torch.Tensor:
            xf = x.float()
            normed = xf * torch.rsqrt(xf.pow(2).mean(dim=-1, keepdim=True) + self.eps)
            return normed.type_as(x) * self.weight

    class KVCache(nn.Module):
        def __init__(
            self,
            max_batch_size: int,
            max_seq_len: int,
            n_kv_heads: int,
            head_dim: int,
            dtype: torch.dtype = torch.bfloat16,
            device: str | torch.device = "cpu",
        ) -> None:
            super().__init__()
            shape = (max_batch_size, n_kv_heads, max_seq_len, head_dim)
            self.register_buffer("k_cache", torch.zeros(shape, dtype=dtype, device=device), persistent=False)
            self.register_buffer("v_cache", torch.zeros(shape, dtype=dtype, device=device), persistent=False)

        def update_slice(
            self, start_pos: int, k_val: torch.Tensor, v_val: torch.Tensor
        ) -> tuple[torch.Tensor, torch.Tensor]:
            end_pos = start_pos + k_val.size(2)
            self.k_cache[:, :, start_pos:end_pos, :] = k_val
            self.v_cache[:, :, start_pos:end_pos, :] = v_val
            return self.k_cache[:, :, :end_pos, :], self.v_cache[:, :, :end_pos, :]

    class DualARAttention(nn.Module):
        def __init__(
            self,
            dim: int,
            n_head: int,
            n_local_heads: int,
            head_dim: int,
            norm_eps: float,
            qkv_bias: bool = False,
            o_bias: bool = False,
            qk_norm: bool = False,
            use_sdpa: bool = True,
        ) -> None:
            super().__init__()
            self.dim = dim
            self.n_head = n_head
            self.n_local_heads = n_local_heads
            self.head_dim = head_dim
            self.qk_norm = qk_norm
            self.use_sdpa = use_sdpa
            self.repeat_factor = n_head // n_local_heads

            total_qkv = (n_head + 2 * n_local_heads) * head_dim
            self.wqkv = nn.Linear(dim, total_qkv, bias=qkv_bias)
            self.wo = nn.Linear(n_head * head_dim, dim, bias=o_bias)
            if qk_norm:
                self.q_norm = RMSNorm(head_dim, eps=norm_eps)
                self.k_norm = RMSNorm(head_dim, eps=norm_eps)
            self.kv_cache: KVCache | None = None

        def forward(
            self,
            x: torch.Tensor,
            freqs_cis: torch.Tensor,
            start_pos: int = 0,
            use_cache: bool = True,
        ) -> torch.Tensor:
            bsz, seqlen, _ = x.shape
            q_size = self.n_head * self.head_dim
            kv_size = self.n_local_heads * self.head_dim
            q, k, v = self.wqkv(x).split([q_size, kv_size, kv_size], dim=-1)

            q = q.view(bsz, seqlen, self.n_head, self.head_dim)
            k = k.view(bsz, seqlen, self.n_local_heads, self.head_dim)
            v = v.view(bsz, seqlen, self.n_local_heads, self.head_dim)

            if self.qk_norm:
                q = self.q_norm(q)
                k = self.k_norm(k)

            q = apply_rotary_emb(q, freqs_cis)
            k = apply_rotary_emb(k, freqs_cis)

            q = q.transpose(1, 2)
            k = k.transpose(1, 2)
            v = v.transpose(1, 2)

            if use_cache and self.kv_cache is not None:
                k, v = self.kv_cache.update_slice(start_pos, k, v)

            if self.repeat_factor > 1:
                k = k.repeat_interleave(self.repeat_factor, dim=1)
                v = v.repeat_interleave(self.repeat_factor, dim=1)

            if seqlen == 1:
                # Single-token decode attends to every cached prefix key [0..end_pos)
                if self.use_sdpa:
                    y = F.scaled_dot_product_attention(q, k, v, is_causal=False)
                else:
                    scale = 1.0 / math.sqrt(self.head_dim)
                    att = (q @ k.transpose(-2, -1)) * scale
                    y = torch.softmax(att.float(), dim=-1).type_as(q) @ v
            elif start_pos == 0:
                if self.use_sdpa:
                    y = F.scaled_dot_product_attention(q, k, v, is_causal=True)
                else:
                    scale = 1.0 / math.sqrt(self.head_dim)
                    att = (q @ k.transpose(-2, -1)) * scale
                    causal = torch.tril(
                        torch.ones(seqlen, k.size(2), dtype=torch.bool, device=q.device)
                    )[None, None]
                    att = att.masked_fill(~causal, float("-inf"))
                    y = torch.softmax(att.float(), dim=-1).type_as(q) @ v
            else:
                # Chunked continuation (start_pos > 0, seqlen > 1)
                kv_len = k.size(2)
                q_pos = torch.arange(start_pos, start_pos + seqlen, device=q.device)[:, None]
                k_pos = torch.arange(0, kv_len, device=q.device)[None, :]
                causal = (k_pos <= q_pos)[None, None]
                if self.use_sdpa:
                    y = F.scaled_dot_product_attention(q, k, v, attn_mask=causal)
                else:
                    scale = 1.0 / math.sqrt(self.head_dim)
                    att = (q @ k.transpose(-2, -1)) * scale
                    att = att.masked_fill(~causal, float("-inf"))
                    y = torch.softmax(att.float(), dim=-1).type_as(q) @ v

            y = y.transpose(1, 2).contiguous().view(bsz, seqlen, q_size)
            return self.wo(y)

    class FeedForward(nn.Module):
        def __init__(self, dim: int, intermediate_size: int) -> None:
            super().__init__()
            self.w1 = nn.Linear(dim, intermediate_size, bias=False)
            self.w3 = nn.Linear(dim, intermediate_size, bias=False)
            self.w2 = nn.Linear(intermediate_size, dim, bias=False)

        def forward(self, x: torch.Tensor) -> torch.Tensor:
            return self.w2(F.silu(self.w1(x)) * self.w3(x))

    class TransformerBlock(nn.Module):
        def __init__(
            self,
            dim: int,
            n_head: int,
            n_local_heads: int,
            head_dim: int,
            intermediate_size: int,
            norm_eps: float,
            qkv_bias: bool = False,
            o_bias: bool = False,
            qk_norm: bool = False,
            use_sdpa: bool = True,
        ) -> None:
            super().__init__()
            self.attention = DualARAttention(
                dim=dim,
                n_head=n_head,
                n_local_heads=n_local_heads,
                head_dim=head_dim,
                norm_eps=norm_eps,
                qkv_bias=qkv_bias,
                o_bias=o_bias,
                qk_norm=qk_norm,
                use_sdpa=use_sdpa,
            )
            self.feed_forward = FeedForward(dim, intermediate_size)
            self.attention_norm = RMSNorm(dim, eps=norm_eps)
            self.ffn_norm = RMSNorm(dim, eps=norm_eps)

        def forward(
            self,
            x: torch.Tensor,
            freqs_cis: torch.Tensor,
            start_pos: int = 0,
            use_cache: bool = True,
        ) -> torch.Tensor:
            h = x + self.attention(
                self.attention_norm(x), freqs_cis, start_pos=start_pos, use_cache=use_cache
            )
            return h + self.feed_forward(self.ffn_norm(h))

    class S2ProDualAR(nn.Module):
        """Complete `fishaudio/s2-pro` Dual-AR Transformer (Slow AR 36L + Fast AR 4L)."""

        def __init__(self, config: DualARConfig, use_sdpa: bool = False) -> None:
            super().__init__()
            self.config = config

            # Slow AR (36-layer fish_qwen3)
            self.embeddings = nn.Embedding(config.vocab_size, config.dim)
            self.codebook_embeddings = nn.Embedding(
                config.codebook_size * config.num_codebooks, config.dim
            )
            self.layers = nn.ModuleList(
                [
                    TransformerBlock(
                        dim=config.dim,
                        n_head=config.n_head,
                        n_local_heads=config.n_local_heads,
                        head_dim=config.head_dim,
                        intermediate_size=config.intermediate_size,
                        norm_eps=config.norm_eps,
                        qkv_bias=config.attention_qkv_bias,
                        o_bias=config.attention_o_bias,
                        qk_norm=config.attention_qk_norm,
                        use_sdpa=use_sdpa,
                    )
                    for _ in range(config.n_layer)
                ]
            )
            self.norm = RMSNorm(config.dim, eps=config.norm_eps)
            if not config.tie_word_embeddings:
                self.output = nn.Linear(config.dim, config.vocab_size, bias=False)

            # Fast AR (4-layer fish_qwen3_audio_decoder)
            if config.fast_dim != config.dim:
                self.fast_project_in: nn.Module = nn.Linear(config.dim, config.fast_dim)
            else:
                self.fast_project_in = nn.Identity()

            self.fast_embeddings = nn.Embedding(config.codebook_size, config.fast_dim)
            self.fast_layers = nn.ModuleList(
                [
                    TransformerBlock(
                        dim=config.fast_dim,
                        n_head=config.fast_n_head,
                        n_local_heads=config.fast_n_local_heads,
                        head_dim=config.fast_head_dim,
                        intermediate_size=config.fast_intermediate_size,
                        norm_eps=config.fast_norm_eps,
                        qkv_bias=config.fast_attention_qkv_bias,
                        o_bias=config.fast_attention_o_bias,
                        qk_norm=config.fast_attention_qk_norm,
                        use_sdpa=False,
                    )
                    for _ in range(config.n_fast_layer)
                ]
            )
            self.fast_norm = RMSNorm(config.fast_dim, eps=config.fast_norm_eps)
            self.fast_output = nn.Linear(config.fast_dim, config.codebook_size, bias=False)

            self.register_buffer(
                "freqs_cis",
                precompute_freqs_cis(config.max_seq_len, config.head_dim, config.rope_base),
                persistent=False,
            )
            self.register_buffer(
                "fast_freqs_cis",
                precompute_freqs_cis(
                    config.num_codebooks + 1, config.fast_head_dim, config.fast_rope_base
                ),
                persistent=False,
            )
            self._slow_cache_len = 0

        def init_rope_buffers(self, device: str | torch.device) -> None:
            """Materialize non-persistent float32 RoPE buffers on target device after meta init."""
            self.freqs_cis = precompute_freqs_cis(
                self.config.max_seq_len, self.config.head_dim, self.config.rope_base
            ).to(device=device, dtype=torch.float32)
            self.fast_freqs_cis = precompute_freqs_cis(
                self.config.num_codebooks + 1, self.config.fast_head_dim, self.config.fast_rope_base
            ).to(device=device, dtype=torch.float32)

        def setup_caches(
            self,
            max_batch_size: int = 1,
            max_seq_len: int | None = None,
            dtype: torch.dtype | None = None,
        ) -> None:
            device = self.embeddings.weight.device
            cache_dtype = dtype or self.embeddings.weight.dtype
            slow_len = int(max_seq_len or self.config.max_seq_len)
            if slow_len > self.freqs_cis.size(0):
                self.freqs_cis = precompute_freqs_cis(
                    slow_len, self.config.head_dim, self.config.rope_base
                ).to(device=device, dtype=torch.float32)
            self._slow_cache_len = slow_len

            for layer in self.layers:
                layer.attention.kv_cache = KVCache(
                    max_batch_size=max_batch_size,
                    max_seq_len=slow_len,
                    n_kv_heads=self.config.n_local_heads,
                    head_dim=self.config.head_dim,
                    dtype=cache_dtype,
                    device=device,
                )
            for layer in self.fast_layers:
                layer.attention.kv_cache = KVCache(
                    max_batch_size=max_batch_size,
                    max_seq_len=self.config.num_codebooks + 1,
                    n_kv_heads=self.config.fast_n_local_heads,
                    head_dim=self.config.fast_head_dim,
                    dtype=cache_dtype,
                    device=device,
                )

        def embed_slow_input(self, inp: torch.Tensor) -> torch.Tensor:
            """Embed `[B, num_codebooks + 1, T]` matching `fish-speech` `BaseTransformer.forward_generate`.

            Row 0 holds text/semantic vocab IDs; rows `1..10` hold codebooks `0..9`.
            For semantic positions, sums `embeddings(row0)` + all 10 `codebook_embeddings`
            and divides by `sqrt(11)` (`scale_codebook_embeddings=True`).
            """
            main_ids = inp[:, 0]  # [B, T]
            x = self.embeddings(main_ids)  # [B, T, D]

            vq_mask = (main_ids >= self.config.semantic_begin_id) & (
                main_ids <= self.config.semantic_end_id
            )
            if bool(vq_mask.any()):
                offsets = (
                    torch.arange(self.config.num_codebooks, device=inp.device, dtype=torch.long)
                    * self.config.codebook_size
                ).view(1, self.config.num_codebooks, 1)
                cb_indices = inp[:, 1 : 1 + self.config.num_codebooks].long() + offsets
                vq_sum = self.codebook_embeddings(cb_indices).sum(dim=1)  # [B, T, D]
                vq_sum = vq_sum * vq_mask.unsqueeze(-1).to(vq_sum.dtype)
                x = x + vq_sum
                if self.config.scale_codebook_embeddings:
                    scale = 1.0 / math.sqrt(float(self.config.num_codebooks + 1))
                    x = torch.where(vq_mask.unsqueeze(-1), x * scale, x)
            return x

        def forward_slow(
            self,
            inp: torch.Tensor,
            start_pos: int = 0,
            use_cache: bool = True,
            return_all: bool = False,
        ) -> tuple[torch.Tensor, torch.Tensor]:
            """Run Slow AR over `[B, 11, T]` -> `(token_logits, fast_input_hidden)`."""
            x = self.embed_slow_input(inp)
            seqlen = x.size(1)
            freqs = self.freqs_cis[start_pos : start_pos + seqlen]

            for layer in self.layers:
                x = layer(x, freqs, start_pos=start_pos, use_cache=use_cache)

            if seqlen > 1 and not return_all:
                x = x[:, -1:, :]

            slow_out = self.norm(x)
            if self.config.tie_word_embeddings:
                logits = F.linear(slow_out, self.embeddings.weight)
            else:
                logits = self.output(slow_out)

            hidden_for_fast = slow_out if self.config.norm_fastlayer_input else x
            hidden_for_fast = self.fast_project_in(hidden_for_fast)
            return logits, hidden_for_fast

        def forward_fast(self, x: torch.Tensor, pos: int) -> torch.Tensor:
            """Run one Fast AR step at codebook position `pos` (`0..num_codebooks-1`)."""
            h = x.view(x.size(0), 1, self.config.fast_dim)
            freqs = self.fast_freqs_cis[pos : pos + 1]
            for layer in self.fast_layers:
                h = layer(h, freqs, start_pos=pos, use_cache=True)
            out = self.fast_norm(h)
            return self.fast_output(out)


def _resolve_lora_adapter_dir(lora_path: Path) -> Path:
    p = Path(lora_path)
    if (p / "adapter_model.safetensors").is_file() and (p / "adapter_config.json").is_file():
        return p
    nested = p / "peft_lora_adapter"
    if (nested / "adapter_model.safetensors").is_file() and (nested / "adapter_config.json").is_file():
        return nested
    raise FileNotFoundError(
        f"Could not find PEFT adapter_model.safetensors + adapter_config.json in {p} or {nested}"
    )


def merge_peft_lora_into_dual_ar_state_dict(
    state_dict: dict[str, "torch.Tensor"],
    config: DualARConfig,
    lora_dir: Path,
    lora_scale: float = 1.0,
) -> LoRAMergeStats:
    """Merge Ryan's trained Rank-32 PEFT Qwen3 LoRA into `S2ProDualAR` weights in-place.

    Applies exact inverse NeoX->interleaved RoPE permutation to `q_proj` and `k_proj` LoRA
    deltas so the merged `fish_qwen3` Slow AR is mathematically identical to `Qwen3ForCausalLM`
    + `PeftModel`.
    """
    adapter_dir = _resolve_lora_adapter_dir(lora_dir)
    adapter_cfg = json.loads((adapter_dir / "adapter_config.json").read_text(encoding="utf-8"))
    r = int(adapter_cfg["r"])
    alpha = float(adapter_cfg["lora_alpha"])
    eff_scale = float(lora_scale) * (alpha / float(r))

    st_path = adapter_dir / "adapter_model.safetensors"
    q_dim = config.n_head * config.head_dim
    kv_dim = config.n_local_heads * config.head_dim

    # Group lora_A / lora_B by (layer_idx, module_name)
    pairs: dict[tuple[int, str], dict[str, torch.Tensor]] = {}
    total_tensors = 0
    pat = re.compile(
        r"^(?:base_model\.model\.)?(?:model\.)?layers\.(\d+)\.(?:self_attn|mlp)\.([a-z_]+)\.(lora_[AB])\.weight$"
    )
    with safe_open(str(st_path), framework="pt", device="cpu") as f:
        for key in f.keys():
            m = pat.match(key)
            if not m:
                raise ValueError(f"Unrecognized PEFT LoRA key: {key}")
            layer_idx = int(m.group(1))
            mod_name = m.group(2)
            ab = m.group(3)
            pairs.setdefault((layer_idx, mod_name), {})[ab] = f.get_tensor(key)
            total_tensors += 1

    if total_tensors == 0:
        raise RuntimeError(f"No LoRA tensors found in {st_path}")

    max_delta_abs = 0.0
    delta_l2s: list[float] = []
    merged_modules = 0

    for layer_idx in range(config.n_layer):
        # 1. Attention Q/K/V merged into fused wqkv.weight
        wqkv_key = f"layers.{layer_idx}.attention.wqkv.weight"
        wqkv = state_dict[wqkv_key]
        orig_dtype = wqkv.dtype
        wqkv_f32 = wqkv.float().clone()
        qkv_touched = False

        for mod_name, row_start, row_end, n_h in (
            ("q_proj", 0, q_dim, config.n_head),
            ("k_proj", q_dim, q_dim + kv_dim, config.n_local_heads),
            ("v_proj", q_dim + kv_dim, q_dim + 2 * kv_dim, None),
        ):
            ab = pairs.pop((layer_idx, mod_name), None)
            if ab is None:
                continue
            delta = (ab["lora_B"].float() @ ab["lora_A"].float()) * eff_scale
            if n_h is not None:
                delta = permute_neox_to_interleaved_rope_weight(delta, n_h, config.head_dim)
            wqkv_f32[row_start:row_end, :] += delta
            qkv_touched = True
            merged_modules += 1
            max_delta_abs = max(max_delta_abs, float(delta.abs().max().item()))
            delta_l2s.append(float(torch.linalg.norm(delta).item()))

        if qkv_touched:
            state_dict[wqkv_key] = wqkv_f32.to(dtype=orig_dtype)

        # 2. Attention output & MLP projections (1:1 row layout, no RoPE permutation)
        direct_map = (
            ("o_proj", f"layers.{layer_idx}.attention.wo.weight"),
            ("gate_proj", f"layers.{layer_idx}.feed_forward.w1.weight"),
            ("up_proj", f"layers.{layer_idx}.feed_forward.w3.weight"),
            ("down_proj", f"layers.{layer_idx}.feed_forward.w2.weight"),
        )
        for mod_name, target_key in direct_map:
            ab = pairs.pop((layer_idx, mod_name), None)
            if ab is None:
                continue
            delta = (ab["lora_B"].float() @ ab["lora_A"].float()) * eff_scale
            base_w = state_dict[target_key]
            state_dict[target_key] = (base_w.float() + delta).to(dtype=base_w.dtype)
            merged_modules += 1
            max_delta_abs = max(max_delta_abs, float(delta.abs().max().item()))
            delta_l2s.append(float(torch.linalg.norm(delta).item()))

    if pairs:
        raise RuntimeError(f"Unconsumed PEFT LoRA modules remaining: {sorted(pairs.keys())[:10]}")

    return LoRAMergeStats(
        adapter_dir=str(adapter_dir),
        lora_rank=r,
        lora_alpha=alpha,
        lora_scale=float(lora_scale),
        effective_scale=eff_scale,
        merged_modules=merged_modules,
        consumed_tensors=total_tensors,
        max_delta_abs=round(max_delta_abs, 6),
        mean_delta_l2=round(float(np.mean(delta_l2s)) if delta_l2s else 0.0, 6),
    )


def load_s2_pro_dual_ar(
    s2_pro_hf_dir: Path = DEFAULT_S2_PRO_HF_DIR,
    *,
    lora_dir: Path | None = DEFAULT_LORA_DIR,
    lora_scale: float = 1.0,
    dtype: "torch.dtype" = None,
    device: str = "mps",
    max_seq_len: int = 2048,
) -> tuple["S2ProDualAR", Any, LoRAMergeStats | None]:
    """Load complete `fishaudio/s2-pro` Dual-AR (36L Slow + 4L Fast, strict=True) + optional LoRA."""
    _disable_incompatible_torch_extras()
    from transformers import AutoTokenizer

    if dtype is None:
        dtype = torch.bfloat16 if device != "cpu" else torch.float32

    hf_dir = Path(s2_pro_hf_dir)
    config = DualARConfig.from_pretrained(hf_dir, max_seq_len=max_seq_len)
    index_path = hf_dir / "model.safetensors.index.json"
    if not index_path.is_file():
        raise FileNotFoundError(f"Missing safetensors index: {index_path}")

    index_data = json.loads(index_path.read_text(encoding="utf-8"))
    weight_map: dict[str, str] = index_data["weight_map"]
    shard_to_keys: dict[str, list[str]] = {}
    for raw_key, shard_name in weight_map.items():
        shard_to_keys.setdefault(shard_name, []).append(raw_key)

    remapped: dict[str, torch.Tensor] = {}
    for shard_name, raw_keys in sorted(shard_to_keys.items()):
        shard_path = hf_dir / shard_name
        if not shard_path.is_file():
            raise FileNotFoundError(f"Missing safetensors shard: {shard_path}")
        with safe_open(str(shard_path), framework="pt", device="cpu") as f:
            for raw_key in raw_keys:
                remapped[remap_fish_qwen3_omni_key(raw_key)] = f.get_tensor(raw_key).to(dtype=dtype)

    lora_stats: LoRAMergeStats | None = None
    if lora_dir is not None and lora_scale != 0.0:
        lora_stats = merge_peft_lora_into_dual_ar_state_dict(
            remapped, config=config, lora_dir=Path(lora_dir), lora_scale=lora_scale
        )

    # PyTorch 2.6 MPS BF16 SDPA accumulates up to ~11.0 logit drift across 36 causal layers;
    # eager float32-softmax attention is numerically exact on MPS/CPU, SDPA safe on CUDA.
    use_sdpa = str(device).startswith("cuda")
    with torch.device("meta"):
        model = S2ProDualAR(config, use_sdpa=use_sdpa)
    model.load_state_dict(remapped, strict=True, assign=True)
    # Materialize non-persistent RoPE buffers off meta before `.to(device)`
    model.init_rope_buffers(device="cpu")
    model.to(device=device, dtype=dtype)
    # Restore float32 precision on RoPE buffers after casting model weights to BF16
    model.init_rope_buffers(device=device)
    model.eval()

    tokenizer = AutoTokenizer.from_pretrained(str(hf_dir))
    return model, tokenizer, lora_stats


class WarmS2ProEngine:
    """Thread-safe warm holder for S2ProDualAR + LoRA weights across multi-clause & sequential TTS jobs."""

    def __init__(self) -> None:
        self._load_lock = threading.Lock()
        self._infer_lock = threading.RLock()
        self._key: tuple[Any, ...] | None = None
        self._model: "S2ProDualAR | None" = None
        self._tokenizer: Any = None
        self._lora_stats: LoRAMergeStats | None = None

    @property
    def lock(self) -> threading.RLock:
        return self._infer_lock

    def get_or_load(
        self,
        s2_pro_hf_dir: Path = DEFAULT_S2_PRO_HF_DIR,
        *,
        lora_dir: Path | None = DEFAULT_LORA_DIR,
        lora_scale: float = 1.0,
        dtype: "torch.dtype" = None,
        device: str = "mps",
        max_seq_len: int = 2048,
    ) -> tuple["S2ProDualAR", Any, LoRAMergeStats | None]:
        eff_lora = None if (lora_dir is None or float(lora_scale) == 0.0) else str(Path(lora_dir).resolve())
        key = (
            str(Path(s2_pro_hf_dir).resolve()),
            eff_lora,
            round(float(lora_scale), 6),
            str(device),
            int(max_seq_len),
            str(dtype) if dtype is not None else "auto",
        )
        with self._infer_lock:
            with self._load_lock:
                if self._key == key and self._model is not None and self._tokenizer is not None:
                    return self._model, self._tokenizer, self._lora_stats
                if self._model is not None:
                    del self._model
                    self._model = None
                    self._tokenizer = None
                    self._lora_stats = None
                    self._key = None
                    if "torch" in globals() and torch.backends.mps.is_available():
                        torch.mps.empty_cache()
                model, tokenizer, lora_stats = load_s2_pro_dual_ar(
                    Path(s2_pro_hf_dir),
                    lora_dir=lora_dir,
                    lora_scale=lora_scale,
                    dtype=dtype,
                    device=device,
                    max_seq_len=max_seq_len,
                )
                self._model = model
                self._tokenizer = tokenizer
                self._lora_stats = lora_stats
                self._key = key
                return model, tokenizer, lora_stats

    def unload(self) -> None:
        with self._infer_lock:
            with self._load_lock:
                if self._model is not None:
                    del self._model
                self._model = None
                self._tokenizer = None
                self._lora_stats = None
                self._key = None
                if "torch" in globals() and torch.backends.mps.is_available():
                    torch.mps.empty_cache()


_SHARED_WARM_ENGINE_LOCK = threading.Lock()
_SHARED_WARM_ENGINE: WarmS2ProEngine | None = None


def get_shared_warm_engine() -> WarmS2ProEngine:
    """Return the process-wide thread-safe `WarmS2ProEngine` singleton."""
    global _SHARED_WARM_ENGINE
    with _SHARED_WARM_ENGINE_LOCK:
        if _SHARED_WARM_ENGINE is None:
            _SHARED_WARM_ENGINE = WarmS2ProEngine()
        return _SHARED_WARM_ENGINE


# ---------------------------------------------------------------------------
# Prompt Conditioning & Sampling (Exact Upstream fish-speech S2-Pro Format)
# ---------------------------------------------------------------------------


def clean_text_for_s2_pro(text: str) -> str:
    cleaned = re.sub(r"\[BLANK_AUDIO\]", " ", text, flags=re.IGNORECASE)
    return re.sub(r"\s+", " ", cleaned).strip()


def build_s2_pro_prompt_tensor(
    tokenizer: Any,
    target_text: str,
    *,
    ref_text: str | None = None,
    ref_codes: np.ndarray | "torch.Tensor" | None = None,
    ref_turns: Sequence[tuple[str, np.ndarray | "torch.Tensor"]] | None = None,
    prompt_style: str = "multiturn",
    num_codebooks: int = NUM_CODEBOOKS,
    semantic_begin_id: int = SEMANTIC_START_TOKEN_ID,
    speaker_id: int = 0,
) -> "torch.Tensor":
    """Construct `[num_codebooks + 1, L_prompt]` int64 prompt for `fishaudio/s2-pro`.

    Encodes each `TextPart` independently (matching upstream `fish-speech` `ContentSequence`
    so BPE never merges tokens across turn/header delimiters).

    Supports two conditioning layouts:
    - `prompt_style="multiturn"` (default, matching `chat_template.jinja` and `train_initial_lora.py`):
      `<|im_start|>system\\nconvert the provided text to speech<|im_end|>\\n`
      followed by one or more `(user: <|speaker:0|>{ref_text}, assistant: <|voice|>[ref_codes])`
      reference turns, then the target user turn and `<|im_start|>assistant\\n<|voice|>`.
    - `prompt_style="system"`: upstream single system-turn reference block.
    """
    target_clean = clean_text_for_s2_pro(target_text)
    if not target_clean:
        raise ValueError("target_text must not be empty")
    if not re.search(r"<\|speaker:\d+\|>", target_clean):
        target_tagged = f"<|speaker:{speaker_id}|>{target_clean}"
    else:
        target_tagged = target_clean

    def _enc(s: str) -> torch.Tensor:
        ids = tokenizer.encode(s, add_special_tokens=False)
        return torch.tensor(ids, dtype=torch.long)

    def _norm_codes(c_in: np.ndarray | torch.Tensor) -> torch.Tensor:
        c = (
            c_in.detach().cpu().long()
            if isinstance(c_in, torch.Tensor)
            else torch.as_tensor(c_in, dtype=torch.long)
        )
        if c.ndim == 3:
            c = c[0]
        if c.ndim != 2 or c.shape[0] != num_codebooks:
            raise ValueError(f"Expected ref_codes shape [{num_codebooks}, T], got {tuple(c.shape)}")
        return c

    turns: list[tuple[str, torch.Tensor]] = []
    if ref_turns:
        for rt, rc in ref_turns:
            turns.append((clean_text_for_s2_pro(rt), _norm_codes(rc)))
    elif ref_text is not None and ref_codes is not None:
        turns.append((clean_text_for_s2_pro(ref_text), _norm_codes(ref_codes)))

    segments: list[tuple[torch.Tensor, torch.Tensor | None]] = []

    if not turns:
        segments.extend(
            [
                (_enc("<|im_start|>system\n"), None),
                (_enc("convert the provided text to speech"), None),
                (_enc("<|im_end|>\n"), None),
            ]
        )
    elif prompt_style == "system":
        tagged_refs = [
            rt if re.search(r"<\|speaker:\d+\|>", rt) else f"<|speaker:{speaker_id}|>{rt}"
            for rt, _ in turns
        ]
        all_codes = torch.cat([rc for _, rc in turns], dim=1)
        segments.extend(
            [
                (_enc("<|im_start|>system\n"), None),
                (_enc("convert the provided text to speech reference to the following:\n\nText:\n"), None),
                (_enc("\n".join(tagged_refs)), None),
                (_enc("\n\nSpeech:\n"), None),
                (all_codes[0] + semantic_begin_id, all_codes),
                (_enc("<|im_end|>\n"), None),
            ]
        )
    elif prompt_style == "multiturn":
        segments.extend(
            [
                (_enc("<|im_start|>system\n"), None),
                (_enc("convert the provided text to speech"), None),
                (_enc("<|im_end|>\n"), None),
            ]
        )
        for rt, rc in turns:
            rt_tagged = rt if re.search(r"<\|speaker:\d+\|>", rt) else f"<|speaker:{speaker_id}|>{rt}"
            segments.extend(
                [
                    (_enc("<|im_start|>user\n"), None),
                    (_enc(rt_tagged), None),
                    (_enc("<|im_end|>\n"), None),
                    (_enc("<|im_start|>assistant\n<|voice|>"), None),
                    (rc[0] + semantic_begin_id, rc),
                    (_enc("<|im_end|>\n"), None),
                ]
            )
    else:
        raise ValueError(f"Unsupported prompt_style: {prompt_style!r} (expected 'multiturn' or 'system')")

    segments.extend(
        [
            (_enc("<|im_start|>user\n"), None),
            (_enc(target_tagged), None),
            (_enc("<|im_end|>\n"), None),
            (_enc("<|im_start|>assistant\n<|voice|>"), None),
        ]
    )

    total_len = sum(int(toks.numel()) for toks, _ in segments)
    values = torch.zeros((num_codebooks + 1, total_len), dtype=torch.long)
    cursor = 0
    for toks, vq in segments:
        seg_len = int(toks.numel())
        values[0, cursor : cursor + seg_len] = toks
        if vq is not None:
            values[1:, cursor : cursor + seg_len] = vq
        cursor += seg_len

    return values


def sample_token_from_logits(
    logits_1d: "torch.Tensor",
    *,
    temperature: float,
    top_p: float,
    top_k: int,
    generator: "torch.Generator | None" = None,
) -> int:
    """Exact upstream `fish-speech` `logits_to_probs` + deterministic multinomial sampling."""
    x = logits_1d.detach().float().cpu()
    if temperature <= 1e-5:
        return int(torch.argmax(x).item())

    # Softmax over all valid logits BEFORE top_k | (cum_probs > top_p), matching upstream inference.py
    full_probs = F.softmax(x, dim=-1)
    k = min(int(top_k), x.numel()) if top_k > 0 else x.numel()
    top_vals, top_idx = torch.topk(x, k=k, largest=True, sorted=True)
    top_probs = full_probs[top_idx]
    cum_probs = torch.cumsum(top_probs, dim=-1)

    remove_mask = cum_probs > float(top_p)
    remove_mask[0] = False  # Always retain top-1 candidate
    filtered = torch.where(remove_mask, torch.tensor(float("-inf")), top_vals)

    scaled = filtered / max(float(temperature), 1e-5)
    probs = F.softmax(scaled, dim=-1)
    if not bool(torch.isfinite(probs).all()) or float(probs.sum().item()) <= 0.0:
        return int(top_idx[0].item())

    choice = int(torch.multinomial(probs, num_samples=1, generator=generator).item())
    return int(top_idx[choice].item())


def generate_dual_ar_codes(
    model: "S2ProDualAR",
    prompt: "torch.Tensor",
    *,
    seed: int = 948101,
    max_new_tokens: int = 384,
    temperature: float = 0.72,
    top_p: float = 0.80,
    top_k: int = 30,
    min_new_tokens: int = 8,
    constrain_residual_vocab: bool = True,
) -> GenerationResult:
    """Autoregressively sample `[10, T_gen]` ModdedDAC codes from `S2ProDualAR`."""
    device = model.embeddings.weight.device
    dtype = model.embeddings.weight.dtype
    cfg = model.config

    prompt_len = int(prompt.size(1))
    needed_seq = prompt_len + max_new_tokens + 8
    model.setup_caches(max_batch_size=1, max_seq_len=needed_seq, dtype=dtype)

    gen = torch.Generator(device="cpu")
    gen.manual_seed(int(seed))

    # Build constrained semantic + <|im_end|> logit bias
    sem_bias = torch.full((cfg.vocab_size,), float("-inf"), device=device, dtype=torch.float32)
    sem_bias[cfg.semantic_begin_id : cfg.semantic_end_id + 1] = 0.0
    sem_bias[cfg.im_end_id] = 0.0

    # Residual codebooks 1..9 in ModdedDAC have size 1024 (while fast_output has 4096 rows)
    res_bias = torch.zeros((cfg.codebook_size,), device=device, dtype=torch.float32)
    if constrain_residual_vocab and cfg.residual_codebook_size < cfg.codebook_size:
        res_bias[cfg.residual_codebook_size :] = float("-inf")

    prev_main_window: list[int] = []
    generated_frames: list[list[int]] = []
    hit_eos = False
    residual_clamped_count = 0

    t0 = time.perf_counter()
    with torch.inference_mode():
        cur_inp = prompt.unsqueeze(0).to(device=device, dtype=torch.long)  # [1, 11, prompt_len]
        start_pos = 0

        for step in range(max_new_tokens):
            logits, slow_hidden = model.forward_slow(cur_inp, start_pos=start_pos, use_cache=True)
            start_pos += cur_inp.size(2)

            step_logits = logits[0, -1].float() + sem_bias
            if step < min_new_tokens:
                step_logits[cfg.im_end_id] = float("-inf")

            main_tok = sample_token_from_logits(
                step_logits, temperature=temperature, top_p=top_p, top_k=top_k, generator=gen
            )

            # Repetition Aware Sampling (RAS) fallback when semantic token repeats in last 10 frames
            is_sem = cfg.semantic_begin_id <= main_tok <= cfg.semantic_end_id
            if is_sem and main_tok in prev_main_window:
                main_tok = sample_token_from_logits(
                    step_logits,
                    temperature=RAS_HIGH_TEMP,
                    top_p=RAS_HIGH_TOP_P,
                    top_k=top_k,
                    generator=gen,
                )

            if main_tok == cfg.im_end_id:
                hit_eos = True
                break

            prev_main_window.append(main_tok)
            if len(prev_main_window) > RAS_WIN_SIZE:
                prev_main_window.pop(0)

            cb0 = max(0, min(cfg.codebook_size - 1, main_tok - cfg.semantic_begin_id))
            frame_codes = [cb0]

            # Fast AR step 0: condition Fast AR KV cache on Slow AR hidden state
            model.forward_fast(slow_hidden, pos=0)
            cb_in = torch.tensor([cb0], device=device, dtype=torch.long)
            fast_h = model.fast_embeddings(cb_in)

            # Fast AR steps 1..9: predict residual codebooks 1..9
            for cb_idx in range(1, cfg.num_codebooks):
                cb_logits = model.forward_fast(fast_h, pos=cb_idx)[0, -1].float()
                if constrain_residual_vocab:
                    cb_logits = cb_logits + res_bias
                cb_val = sample_token_from_logits(
                    cb_logits,
                    temperature=temperature,
                    top_p=top_p,
                    top_k=top_k,
                    generator=gen,
                )
                if cb_val >= cfg.residual_codebook_size:
                    residual_clamped_count += 1
                    cb_val = cfg.residual_codebook_size - 1
                frame_codes.append(cb_val)
                if cb_idx + 1 < cfg.num_codebooks:
                    cb_in = torch.tensor([cb_val], device=device, dtype=torch.long)
                    fast_h = model.fast_embeddings(cb_in)

            generated_frames.append(frame_codes)

            # Feed `[main_tok, cb0, cb1, ..., cb9]` as next Slow AR token
            next_vec = [main_tok] + frame_codes
            cur_inp = torch.tensor(next_vec, device=device, dtype=torch.long).view(
                1, cfg.num_codebooks + 1, 1
            )

        if device.type == "mps":
            torch.mps.synchronize()

    elapsed_sec = time.perf_counter() - t0
    if generated_frames:
        codes_np = np.asarray(generated_frames, dtype=np.int32).T.copy()  # [10, T_gen]
    else:
        codes_np = np.zeros((cfg.num_codebooks, 0), dtype=np.int32)

    n_gen = int(codes_np.shape[1])
    return GenerationResult(
        codes=codes_np,
        seed=int(seed),
        temperature=float(temperature),
        top_p=float(top_p),
        top_k=int(top_k),
        prompt_tokens=prompt_len,
        generated_frames=n_gen,
        hit_eos=hit_eos,
        elapsed_sec=round(elapsed_sec, 3),
        frames_per_sec=round(n_gen / max(elapsed_sec, 1e-6), 2),
        residual_clamped_count=residual_clamped_count,
    )


def encode_reference_audio_to_codes(
    ref_wav_path: Path,
    codec_pth: Path = DEFAULT_CODEC_PTH,
    *,
    device: str = "mps",
    max_seconds: float | None = None,
) -> tuple[np.ndarray, float]:
    """Encode a real reference WAV into `[10, T_ref]` ModdedDAC discrete codes (cached by file stat)."""
    import scipy.signal as spsig
    import soundfile as sf

    ref_resolved = Path(ref_wav_path).resolve()
    st = ref_resolved.stat()
    codec_resolved = Path(codec_pth).resolve()
    cache_key = (
        str(ref_resolved),
        int(st.st_mtime_ns),
        int(st.st_size),
        str(codec_resolved),
        None if max_seconds is None else float(max_seconds),
    )
    with _REF_CODES_CACHE_LOCK:
        cached = _REF_CODES_CACHE.get(cache_key)
        if cached is not None:
            return cached[0].copy(), cached[1]

    modded_dac_path = Path(__file__).resolve().parent / "modded_dac.py"
    spec = importlib.util.spec_from_file_location("voicestudio_modded_dac", str(modded_dac_path))
    if spec is None or spec.loader is None:
        raise ImportError(f"Failed to import modded_dac.py from {modded_dac_path}")
    modded_dac = importlib.util.module_from_spec(spec)
    sys.modules["voicestudio_modded_dac"] = modded_dac
    spec.loader.exec_module(modded_dac)

    wav, sr = sf.read(str(ref_resolved), dtype="float32", always_2d=False)
    if wav.ndim > 1:
        wav = wav.mean(axis=1, dtype=np.float32)
    if sr != SAMPLE_RATE_HZ:
        g = math.gcd(SAMPLE_RATE_HZ, int(sr))
        wav = spsig.resample_poly(wav.astype(np.float64), SAMPLE_RATE_HZ // g, int(sr) // g).astype(
            np.float32
        )
    if max_seconds is not None:
        wav = wav[: int(round(max_seconds * SAMPLE_RATE_HZ))]
    wav = np.ascontiguousarray(wav, dtype=np.float32)
    dur_sec = float(wav.size) / float(SAMPLE_RATE_HZ)

    dac = modded_dac.load_s2_pro_dac(str(codec_resolved), device=device, strict=True)
    with torch.inference_mode():
        codes_t, code_lens = modded_dac.encode_waveform_44k(dac, torch.from_numpy(wav))
        n_frames = int(code_lens[0].item())
        codes_np = codes_t[0, :, :n_frames].detach().cpu().numpy().astype(np.int32)
    del dac
    if device == "mps" and torch.backends.mps.is_available():
        torch.mps.empty_cache()

    with _REF_CODES_CACHE_LOCK:
        _REF_CODES_CACHE[cache_key] = (codes_np.copy(), dur_sec)
    return codes_np, dur_sec


def decode_codes_pytorch_dac(
    codes: np.ndarray,
    codec_pth: Path | None = DEFAULT_CODEC_PTH,
    *,
    tflite_path: Path | None = None,
    device: str = "cpu",
) -> np.ndarray:
    """Decode `[10, T]` codes via full-sequence PyTorch `ModdedDAC.from_indices` for parity checks."""
    resolved_codec_pth = resolve_pytorch_dac_checkpoint(codec_pth, tflite_path=tflite_path)

    modded_dac_path = Path(__file__).resolve().parent / "modded_dac.py"
    spec = importlib.util.spec_from_file_location("voicestudio_modded_dac", str(modded_dac_path))
    if spec is None or spec.loader is None:
        raise ImportError(f"Failed to import modded_dac.py from {modded_dac_path}")
    modded_dac = importlib.util.module_from_spec(spec)
    sys.modules["voicestudio_modded_dac"] = modded_dac
    spec.loader.exec_module(modded_dac)

    clamped = np.asarray(codes, dtype=np.int64).copy()
    if clamped.ndim == 3:
        clamped = clamped[0]
    clamped[0] = np.clip(clamped[0], 0, NUM_SEMANTIC_TOKENS - 1)
    clamped[1:] = np.clip(clamped[1:], 0, RESIDUAL_CODEBOOK_SIZE - 1)

    dac = modded_dac.load_s2_pro_dac(str(resolved_codec_pth), device=device, strict=True)
    with torch.inference_mode():
        pcm = dac.from_indices(torch.from_numpy(clamped[None]).to(device=device))
        out = pcm[0, 0].detach().cpu().float().numpy()
    del dac
    if device == "mps" and torch.backends.mps.is_available():
        torch.mps.empty_cache()
    return np.ascontiguousarray(out, dtype=np.float32)


def compute_waveform_parity(ref_pcm: np.ndarray, test_pcm: np.ndarray) -> dict[str, float]:
    """Compute SNR dB, RMSE, max_abs_diff, and Pearson r between two equal-length waveforms."""
    a = np.asarray(ref_pcm, dtype=np.float64).ravel()
    b = np.asarray(test_pcm, dtype=np.float64).ravel()
    n = min(a.size, b.size)
    a = a[:n]
    b = b[:n]
    diff = b - a
    mse = float(np.mean(diff * diff))
    sig_pow = float(np.mean(a * a))
    snr_db = float(10.0 * math.log10(sig_pow / max(mse, 1e-18))) if sig_pow > 0.0 else 0.0
    corr = float(np.corrcoef(a, b)[0, 1]) if sig_pow > 0.0 and float(np.mean(b * b)) > 0.0 else 1.0
    return {
        "samples": int(n),
        "max_abs_diff": round(float(np.max(np.abs(diff))), 6),
        "rmse": round(math.sqrt(mse), 6),
        "snr_db": round(snr_db, 2),
        "pearson_corr": round(corr, 6),
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="fishaudio/s2-pro Dual-AR + LoRA local inference")
    parser.add_argument("--decode-tflite-batch", type=Path, default=None)
    parser.add_argument("--decode-tflite-out", type=Path, default=None)
    parser.add_argument("--text", type=str, default=None)
    parser.add_argument("--ref-wav", type=Path, default=None)
    parser.add_argument("--ref-text", type=str, default=None)
    parser.add_argument("--output-wav", type=Path, default=None)
    parser.add_argument("--output-codes", type=Path, default=None)
    parser.add_argument("--s2-pro-dir", type=Path, default=DEFAULT_S2_PRO_HF_DIR)
    parser.add_argument("--lora-dir", type=Path, default=DEFAULT_LORA_DIR)
    parser.add_argument("--lora-scale", type=float, default=1.0)
    parser.add_argument("--codec-pth", type=Path, default=DEFAULT_CODEC_PTH)
    parser.add_argument("--codec-tflite", type=Path, default=DEFAULT_CODEC_TFLITE)
    parser.add_argument("--seed", type=int, default=948101)
    parser.add_argument("--temperature", type=float, default=0.72)
    parser.add_argument("--top-p", type=float, default=0.80)
    parser.add_argument("--top-k", type=int, default=30)
    parser.add_argument("--max-new-tokens", type=int, default=384)
    parser.add_argument("--device", type=str, default="mps")
    args = parser.parse_args(argv)

    if args.decode_tflite_batch is not None:
        if args.decode_tflite_out is None:
            raise ValueError("--decode-tflite-out is required with --decode-tflite-batch")
        return _run_decode_tflite_batch_cli(args.decode_tflite_batch, args.decode_tflite_out)

    if not args.text or not args.output_wav:
        parser.error("--text and --output-wav are required for synthesis")

    import soundfile as sf

    ref_codes = None
    if args.ref_wav is not None:
        ref_codes, ref_dur = encode_reference_audio_to_codes(
            args.ref_wav, codec_pth=args.codec_pth, device=args.device
        )
        print(f"[ref] Encoded {args.ref_wav} ({ref_dur:.2f}s) -> codes {ref_codes.shape}", flush=True)

    lora_path = None if args.lora_scale == 0.0 else args.lora_dir
    model, tokenizer, lora_stats = load_s2_pro_dual_ar(
        args.s2_pro_dir,
        lora_dir=lora_path,
        lora_scale=args.lora_scale,
        device=args.device,
    )
    if lora_stats is not None:
        print(f"[lora] Merged: {asdict(lora_stats)}", flush=True)

    prompt = build_s2_pro_prompt_tensor(
        tokenizer,
        args.text,
        ref_text=args.ref_text,
        ref_codes=ref_codes,
    )
    gen_res = generate_dual_ar_codes(
        model,
        prompt,
        seed=args.seed,
        max_new_tokens=args.max_new_tokens,
        temperature=args.temperature,
        top_p=args.top_p,
        top_k=args.top_k,
    )
    print(
        f"[gen] seed={gen_res.seed} frames={gen_res.generated_frames} "
        f"hit_eos={gen_res.hit_eos} elapsed={gen_res.elapsed_sec}s ({gen_res.frames_per_sec} fps)",
        flush=True,
    )
    if args.output_codes is not None:
        args.output_codes.parent.mkdir(parents=True, exist_ok=True)
        np.save(args.output_codes, gen_res.codes)

    [(pcm, dec_meta)] = decode_codes_batch_tflite([gen_res.codes], tflite_path=args.codec_tflite)
    args.output_wav.parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(args.output_wav), np.clip(pcm, -1.0, 1.0), SAMPLE_RATE_HZ, subtype="PCM_16")
    print(f"[decode] {dec_meta} -> {args.output_wav}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
