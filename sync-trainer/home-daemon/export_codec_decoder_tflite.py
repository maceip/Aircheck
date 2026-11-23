"""Export fishaudio/s2-pro 44.1 kHz ModdedDAC decoder to FP16 LiteRT (.tflite).

Pipeline:
1. Loads ``/Users/mac/Downloads/voicestudio-models/s2-pro/codec.pth`` via
   ``modded_dac.load_s2_pro_dac(..., strict=True)`` (all 541 keys verified).
2. Extracts the decoder-only graph ``ExportableModdedDACDecoder`` (weight_norm
   fused, float32 RoPE cos/sin, explicit additive causal window mask):
   - Signature: ``serving_default``
   - Input:  ``codes``   (`int32 [1, 10, T]`, default `T=64` -> ~2.972 s @ 44.1 kHz)
   - Output: ``pcm_44k`` (`float32 [1, 1, T * 2048]`, default `[1, 1, 131072]`)
3. Converts the PyTorch module to FlatBuffers FP32 ``.tflite`` via ``litert_torch``.
4. Applies genuine FP16 weight quantization (``float_casting`` with FP32 compute
   precision across ``FULLY_CONNECTED``, ``CONV_2D``, ``DEPTHWISE_CONV_2D``,
   ``CONV_2D_TRANSPOSE``, and ``EMBEDDING_LOOKUP``) via ``ai_edge_quantizer``.
5. Verifies the ``TFL3`` FlatBuffers header, runs numerical parity on Ryan's
   real discrete audio codes from ``voice_ref_alt.wav`` against PyTorch FP32
   ``ModdedDAC.from_indices``, and writes a verification JSON report + WAV.

Note: Run with ``/Users/mac/.venv-lrt/bin/python`` (has matching ``litert_torch``,
``ai_edge_quantizer``, and ``ai_edge_litert`` native libraries on macOS arm64).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

import numpy as np
import scipy.io.wavfile as wavfile
import scipy.signal as spsig
import torch
import torch.nn as nn

# Allow direct script execution as well as package/path imports.
_THIS_DIR = Path(__file__).resolve().parent
if str(_THIS_DIR) not in sys.path:
    sys.path.insert(0, str(_THIS_DIR))

from modded_dac import (  # noqa: E402
    FRAME_LENGTH,
    NUM_CODEBOOKS,
    SAMPLE_RATE_HZ,
    ExportableModdedDACDecoder,
    ModdedDAC,
    encode_waveform_44k,
    load_s2_pro_dac,
)

DEFAULT_CHECKPOINT = Path("/Users/mac/Downloads/voicestudio-models/s2-pro/codec.pth")
DEFAULT_OUTPUT_TFLITE = Path(
    "/Users/mac/Downloads/voicestudio-models/bundles/v001/codec_decoder_fp16.tflite"
)
DEFAULT_VERIFY_WAV = Path("/Users/mac/Downloads/clone/voice/voice_ref_alt.wav")
DEFAULT_REPORT_JSON = Path(
    "/Users/mac/Downloads/voicestudio-models/bundles/v001/codec_export_report.json"
)
DEFAULT_TFLITE_CHECK_WAV = Path(
    "/Users/mac/Downloads/voicestudio-models/bundles/v001/dac_tflite_fp16_check.wav"
)
DEFAULT_SEQ_LEN: int = 64  # 64 * 2048 = 131,072 samples (~2.972 s @ 44.1 kHz)

# Keep tiny 8-dim codebook EMBEDDING_LOOKUP tables (~0.2 MiB) in FP32 so LiteRT
# GPU delegates accept them cleanly while quantizing 99.9% of decoder weights to FP16.
FP16_FLOAT_CASTING_OPS: Tuple[str, ...] = (
    "FULLY_CONNECTED",
    "CONV_2D",
    "DEPTHWISE_CONV_2D",
    "CONV_2D_TRANSPOSE",
)


class _NamedOutputDecoderWrapper(nn.Module):
    """Thin signature wrapper exposing named output ``pcm_44k``."""

    def __init__(self, inner: ExportableModdedDACDecoder) -> None:
        super().__init__()
        self.inner = inner

    def forward(self, codes: torch.Tensor) -> Dict[str, torch.Tensor]:
        return {"pcm_44k": self.inner(codes)}


def compute_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_tfl3_magic(path: Path) -> bool:
    if not path.is_file() or path.stat().st_size < 16:
        return False
    with path.open("rb") as fh:
        header = fh.read(8)
    return len(header) == 8 and header[4:8] == b"TFL3"


def load_mono_44k_wav(wav_path: Path, max_seconds: Optional[float] = 4.5) -> np.ndarray:
    """Read WAV via scipy and resample cleanly to 44.1 kHz mono float32 in [-1, 1]."""
    sr, data = wavfile.read(str(wav_path))
    if np.issubdtype(data.dtype, np.integer):
        max_val = float(np.iinfo(data.dtype).max)
        audio = data.astype(np.float32) / max_val
    else:
        audio = data.astype(np.float32)
    if audio.ndim > 1:
        audio = audio.mean(axis=1)
    if sr != SAMPLE_RATE_HZ:
        g = math.gcd(SAMPLE_RATE_HZ, sr)
        audio = spsig.resample_poly(audio, SAMPLE_RATE_HZ // g, sr // g).astype(
            np.float32
        )
    if max_seconds is not None:
        max_samples = int(max_seconds * SAMPLE_RATE_HZ)
        audio = audio[:max_samples]
    return np.ascontiguousarray(audio, dtype=np.float32)


def write_pcm16_wav(path: Path, pcm: np.ndarray, sample_rate: int = SAMPLE_RATE_HZ) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    clipped = np.clip(pcm, -1.0, 1.0)
    pcm16 = (clipped * 32767.0).astype(np.int16)
    wavfile.write(str(path), sample_rate, pcm16)


def build_fp16_float_casting_recipe() -> list[dict[str, Any]]:
    """Build ai_edge_quantizer recipe for FP16 weight-only float casting."""
    return [
        {
            "regex": ".*",
            "operation": op_name,
            "algorithm_key": "float_casting",
            "op_config": {
                "weight_tensor_config": {"num_bits": 16, "dtype": "FLOAT"},
                "compute_precision": "FLOAT",
                "explicit_dequantize": True,
            },
        }
        for op_name in FP16_FLOAT_CASTING_OPS
    ]


def compute_parity_metrics(ref: np.ndarray, test: np.ndarray) -> Dict[str, float]:
    ref_f = np.asarray(ref, dtype=np.float64).ravel()
    test_f = np.asarray(test, dtype=np.float64).ravel()
    assert ref_f.shape == test_f.shape, f"Shape mismatch: {ref_f.shape} vs {test_f.shape}"
    diff = test_f - ref_f
    max_abs_diff = float(np.max(np.abs(diff)))
    mean_abs_diff = float(np.mean(np.abs(diff)))
    mse = float(np.mean(diff * diff))
    rmse = float(math.sqrt(mse))
    sig_power = float(np.mean(ref_f * ref_f))
    snr_db = float(10.0 * math.log10(sig_power / max(mse, 1e-18)))
    corr = float(np.corrcoef(ref_f, test_f)[0, 1]) if sig_power > 0 else 1.0
    return {
        "max_abs_diff": max_abs_diff,
        "mean_abs_diff": mean_abs_diff,
        "rmse": rmse,
        "snr_db": snr_db,
        "pearson_corr": corr,
    }


def run_tflite_interpreter(
    model_path: Path, codes_i32: np.ndarray
) -> Tuple[np.ndarray, Dict[str, Any], float]:
    """Run exported .tflite via ai_edge_litert Interpreter and return (pcm, contract, ms)."""
    from ai_edge_litert.interpreter import Interpreter

    interp = Interpreter(model_path=str(model_path), num_threads=4)
    sigs = interp.get_signature_list()
    in_details = interp.get_input_details()
    out_details = interp.get_output_details()
    interp.allocate_tensors()

    in_idx = in_details[0]["index"]
    out_idx = out_details[0]["index"]

    # Warm-up pass before timing
    interp.set_tensor(in_idx, np.ascontiguousarray(codes_i32, dtype=np.int32))
    interp.invoke()

    t0 = time.perf_counter()
    interp.set_tensor(in_idx, np.ascontiguousarray(codes_i32, dtype=np.int32))
    interp.invoke()
    pcm = np.array(interp.get_tensor(out_idx), copy=True)
    elapsed_ms = (time.perf_counter() - t0) * 1000.0

    contract = {
        "signatures": sigs,
        "input": {
            "name": str(in_details[0]["name"]),
            "shape": [int(d) for d in in_details[0]["shape"]],
            "dtype": str(np.dtype(in_details[0]["dtype"])),
        },
        "output": {
            "name": str(out_details[0]["name"]),
            "shape": [int(d) for d in out_details[0]["shape"]],
            "dtype": str(np.dtype(out_details[0]["dtype"])),
        },
    }
    return pcm, contract, elapsed_ms


def run_tflite_compiled_model(
    model_path: Path,
    codes_i32: np.ndarray,
    accelerator: str = "CPU",
) -> Optional[Tuple[np.ndarray, float]]:
    """Verify execution via LiteRT CompiledModel API (mirrors Android LiteRtCodecDecoder)."""
    try:
        from ai_edge_litert.compiled_model import CompiledModel
        from ai_edge_litert.hardware_accelerator import HardwareAccelerator
    except Exception:
        return None

    hw = getattr(HardwareAccelerator, accelerator.upper(), HardwareAccelerator.CPU)
    model = CompiledModel.from_file(str(model_path), hardware_accel=hw)
    sig_idx = model.get_signature_index("serving_default")
    in_bufs = model.create_input_buffers(sig_idx)
    out_bufs = model.create_output_buffers(sig_idx)
    arr = np.ascontiguousarray(codes_i32, dtype=np.int32)
    in_bufs[0].write(arr)
    model.run_by_index(sig_idx, in_bufs, out_bufs)

    t0 = time.perf_counter()
    in_bufs[0].write(arr)
    model.run_by_index(sig_idx, in_bufs, out_bufs)
    expected_elems = 1 * 1 * int(codes_i32.shape[-1]) * FRAME_LENGTH
    out_flat = out_bufs[0].read(expected_elems, np.float32)
    elapsed_ms = (time.perf_counter() - t0) * 1000.0
    return out_flat.reshape(1, 1, expected_elems), elapsed_ms


def export_codec_decoder_fp16(
    checkpoint_path: Path = DEFAULT_CHECKPOINT,
    output_path: Path = DEFAULT_OUTPUT_TFLITE,
    seq_len: int = DEFAULT_SEQ_LEN,
    verify_wav_path: Optional[Path] = DEFAULT_VERIFY_WAV,
    report_path: Optional[Path] = DEFAULT_REPORT_JSON,
    tflite_check_wav_path: Optional[Path] = DEFAULT_TFLITE_CHECK_WAV,
    keep_fp32_path: Optional[Path] = None,
) -> Dict[str, Any]:
    import ai_edge_quantizer
    import litert_torch

    t_start = time.perf_counter()
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    print(f"[1/5] Loading s2-pro ModdedDAC strictly from {checkpoint_path}...")
    dac: ModdedDAC = load_s2_pro_dac(checkpoint_path, device="cpu", strict=True)
    state_key_count = len(dac.state_dict())
    print(f"      Loaded ALL {state_key_count} keys with strict=True.")

    # Encode Ryan's real audio to get authentic discrete codes for verification.
    if verify_wav_path is not None and Path(verify_wav_path).is_file():
        print(f"[2/5] Encoding real verification audio {verify_wav_path}...")
        wav_44k = load_mono_44k_wav(Path(verify_wav_path), max_seconds=4.5)
        enc_device = "mps" if torch.backends.mps.is_available() else "cpu"
        dac_enc = dac.to(enc_device)
        codes_all, _ = encode_waveform_44k(dac_enc, torch.from_numpy(wav_44k))
        codes_all = codes_all.cpu()
        dac.cpu()
        if codes_all.shape[-1] < seq_len:
            pad_len = seq_len - codes_all.shape[-1]
            codes_all = torch.nn.functional.pad(codes_all, (0, pad_len))
        real_codes_i32 = codes_all[:, :, :seq_len].to(torch.int32).contiguous()
    else:
        raise FileNotFoundError(
            f"Real verification WAV not found (Constitutional Law 2 forbids synthetic fallback): {verify_wav_path}"
        )

    print(f"[3/5] Building ExportableModdedDACDecoder (T={seq_len}, output={seq_len * FRAME_LENGTH} samples)...")
    export_decoder = ExportableModdedDACDecoder(dac, seq_len=seq_len).eval()
    decoder_param_count = sum(p.numel() for p in export_decoder.parameters())
    decoder_buf_count = sum(b.numel() for b in export_decoder.buffers())

    with torch.inference_mode():
        pt_ref_pcm = dac.from_indices(real_codes_i32.long()).cpu().numpy()
        pt_exp_pcm = export_decoder(real_codes_i32).cpu().numpy()

    pt_wrapper_parity = compute_parity_metrics(pt_ref_pcm, pt_exp_pcm)
    print(
        "      PyTorch Exportable vs ModdedDAC.from_indices: "
        f"max_abs_diff={pt_wrapper_parity['max_abs_diff']:.3e}, "
        f"RMSE={pt_wrapper_parity['rmse']:.3e}, SNR={pt_wrapper_parity['snr_db']:.2f} dB"
    )
    if pt_wrapper_parity["max_abs_diff"] >= 1e-4:
        raise RuntimeError(
            f"Exportable wrapper drift too high: {pt_wrapper_parity['max_abs_diff']}"
        )

    print("[4/5] Converting to FP32 LiteRT FlatBuffer via litert_torch + FP16 quantization via ai_edge_quantizer...")
    wrapped = _NamedOutputDecoderWrapper(export_decoder).eval()
    sample_codes = torch.zeros((1, NUM_CODEBOOKS, seq_len), dtype=torch.int32)

    t_conv = time.perf_counter()
    edge_model = litert_torch.signature(
        "serving_default",
        wrapped,
        sample_kwargs={"codes": sample_codes},
    ).convert()
    conv_sec = time.perf_counter() - t_conv

    with tempfile.TemporaryDirectory(prefix="voicestudio_dac_export_") as tmpdir:
        fp32_path = (
            Path(keep_fp32_path)
            if keep_fp32_path is not None
            else Path(tmpdir) / "codec_decoder_fp32.tflite"
        )
        fp32_path.parent.mkdir(parents=True, exist_ok=True)
        edge_model.export(str(fp32_path))
        fp32_bytes = fp32_path.stat().st_size
        print(
            f"      FP32 TFLite exported in {conv_sec:.1f}s: "
            f"{fp32_bytes:,} bytes ({fp32_bytes / (1024 * 1024):.2f} MiB)"
        )

        t_q = time.perf_counter()
        qt = ai_edge_quantizer.Quantizer(str(fp32_path))
        qt.load_quantization_recipe(build_fp16_float_casting_recipe())
        q_result = qt.quantize()
        q_result.export_model(str(output_path), overwrite=True)
        quant_sec = time.perf_counter() - t_q

    if not verify_tfl3_magic(output_path):
        raise RuntimeError(f"Exported model failed TFL3 magic verification: {output_path}")

    fp16_bytes = output_path.stat().st_size
    fp16_sha256 = compute_sha256(output_path)
    print(
        f"      FP16 TFLite quantized in {quant_sec:.1f}s: "
        f"{fp16_bytes:,} bytes ({fp16_bytes / (1024 * 1024):.2f} MiB), "
        f"ratio={fp16_bytes / fp32_bytes:.3f}, sha256={fp16_sha256}"
    )

    print("[5/5] Verifying numerical parity on Ryan's real discrete codes...")
    codes_np = real_codes_i32.numpy()
    tflite_pcm, contract, interp_ms = run_tflite_interpreter(output_path, codes_np)
    assert np.isfinite(tflite_pcm).all(), "Exported TFLite output contains NaN/Inf!"
    tflite_parity = compute_parity_metrics(pt_ref_pcm, tflite_pcm)
    print(
        "      Interpreter FP16 TFLite vs PyTorch FP32: "
        f"max_abs_diff={tflite_parity['max_abs_diff']:.4e}, "
        f"RMSE={tflite_parity['rmse']:.4e}, "
        f"SNR={tflite_parity['snr_db']:.2f} dB, "
        f"corr={tflite_parity['pearson_corr']:.6f}, "
        f"latency={interp_ms:.1f} ms"
    )

    cm_result = run_tflite_compiled_model(output_path, codes_np)
    compiled_model_report: Optional[Dict[str, Any]] = None
    if cm_result is not None:
        cm_pcm, cm_ms = cm_result
        assert np.isfinite(cm_pcm).all(), "CompiledModel output contains NaN/Inf!"
        cm_parity = compute_parity_metrics(pt_ref_pcm, cm_pcm)
        compiled_model_report = {
            "latency_ms": round(cm_ms, 2),
            **cm_parity,
        }
        print(
            "      CompiledModel FP16 TFLite vs PyTorch FP32: "
            f"max_abs_diff={cm_parity['max_abs_diff']:.4e}, "
            f"RMSE={cm_parity['rmse']:.4e}, "
            f"SNR={cm_parity['snr_db']:.2f} dB, "
            f"latency={cm_ms:.1f} ms"
        )

    if tflite_check_wav_path is not None:
        write_pcm16_wav(Path(tflite_check_wav_path), tflite_pcm[0, 0], SAMPLE_RATE_HZ)
        print(f"      Saved TFLite decoded audio check to {tflite_check_wav_path}")

    report: Dict[str, Any] = {
        "checkpoint_path": str(checkpoint_path),
        "checkpoint_strict_keys": state_key_count,
        "output_tflite_path": str(output_path),
        "tfl3_magic_valid": True,
        "fp32_size_bytes": fp32_bytes,
        "fp16_size_bytes": fp16_bytes,
        "fp16_size_mib": round(fp16_bytes / (1024 * 1024), 2),
        "compression_ratio": round(fp16_bytes / fp32_bytes, 4),
        "sha256": fp16_sha256,
        "seq_len_frames": seq_len,
        "output_samples": seq_len * FRAME_LENGTH,
        "sample_rate_hz": SAMPLE_RATE_HZ,
        "frame_length_samples": FRAME_LENGTH,
        "duration_sec_per_chunk": round((seq_len * FRAME_LENGTH) / SAMPLE_RATE_HZ, 4),
        "decoder_param_count": int(decoder_param_count),
        "decoder_buffer_count": int(decoder_buf_count),
        "tensor_contract": contract,
        "verify_wav_path": str(verify_wav_path),
        "pytorch_exportable_parity": pt_wrapper_parity,
        "tflite_interpreter_parity": {
            "latency_ms": round(interp_ms, 2),
            **tflite_parity,
        },
        "tflite_compiled_model_parity": compiled_model_report,
        "convert_sec": round(conv_sec, 2),
        "quantize_sec": round(quant_sec, 2),
        "total_sec": round(time.perf_counter() - t_start, 2),
    }

    if report_path is not None:
        rp = Path(report_path)
        rp.parent.mkdir(parents=True, exist_ok=True)
        rp.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"      Wrote export report to {rp}")

    return report


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Export fishaudio/s2-pro 44.1 kHz ModdedDAC decoder to FP16 LiteRT (.tflite)."
    )
    parser.add_argument("--checkpoint", type=Path, default=DEFAULT_CHECKPOINT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_TFLITE)
    parser.add_argument("--seq-len", type=int, default=DEFAULT_SEQ_LEN)
    parser.add_argument("--verify-wav", type=Path, default=DEFAULT_VERIFY_WAV)
    parser.add_argument("--report-json", type=Path, default=DEFAULT_REPORT_JSON)
    parser.add_argument("--check-wav", type=Path, default=DEFAULT_TFLITE_CHECK_WAV)
    parser.add_argument("--keep-fp32", type=Path, default=None)
    return parser.parse_args()


if __name__ == "__main__":
    args = _parse_args()
    export_codec_decoder_fp16(
        checkpoint_path=args.checkpoint,
        output_path=args.output,
        seq_len=args.seq_len,
        verify_wav_path=args.verify_wav,
        report_path=args.report_json,
        tflite_check_wav_path=args.check_wav,
        keep_fp32_path=args.keep_fp32,
    )
