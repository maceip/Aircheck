"""End-to-end verification tests for fishaudio/s2-pro ModdedDAC & LiteRT FP16 export.

Exercises the REAL checkpoint (/Users/mac/Downloads/voicestudio-models/s2-pro/codec.pth),
Ryan's REAL voice recording (/Users/mac/Downloads/clone/voice/voice_ref_alt.wav), and
the REAL exported FP16 LiteRT flatbuffer (/Users/mac/Downloads/voicestudio-models/bundles/v001/codec_decoder_fp16.tflite).
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pytest
import scipy.signal as spsig
import torch

HOME_DAEMON_DIR = Path(__file__).resolve().parent.parent
if str(HOME_DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(HOME_DAEMON_DIR))

from export_codec_decoder_tflite import (  # noqa: E402
    DEFAULT_CHECKPOINT,
    DEFAULT_OUTPUT_TFLITE,
    DEFAULT_REPORT_JSON,
    DEFAULT_SEQ_LEN,
    DEFAULT_VERIFY_WAV,
    FP16_FLOAT_CASTING_OPS,
    build_fp16_float_casting_recipe,
    compute_parity_metrics,
    compute_sha256,
    load_mono_44k_wav,
    run_tflite_compiled_model,
    run_tflite_interpreter,
    verify_tfl3_magic,
)
from modded_dac import (  # noqa: E402
    FRAME_LENGTH,
    HOP_LENGTH,
    NUM_CODEBOOKS,
    RESIDUAL_CODEBOOK_SIZE,
    SAMPLE_RATE_HZ,
    SEMANTIC_CODEBOOK_SIZE,
    ExportableModdedDACDecoder,
    decode_codes_44k,
    encode_waveform_44k,
    load_s2_pro_dac,
)


@pytest.fixture(scope="module")
def loaded_dac():
    assert DEFAULT_CHECKPOINT.is_file(), f"Missing real checkpoint: {DEFAULT_CHECKPOINT}"
    return load_s2_pro_dac(DEFAULT_CHECKPOINT, device="cpu", strict=True)


@pytest.fixture(scope="module")
def real_voice_codes_and_audio(loaded_dac):
    assert DEFAULT_VERIFY_WAV.is_file(), f"Missing real audio: {DEFAULT_VERIFY_WAV}"
    wav_44k = load_mono_44k_wav(DEFAULT_VERIFY_WAV, max_seconds=3.5)
    device = "mps" if torch.backends.mps.is_available() else "cpu"
    model_dev = loaded_dac.to(device)
    codes, code_lens = encode_waveform_44k(model_dev, torch.from_numpy(wav_44k))
    recon = decode_codes_44k(model_dev, codes)
    codes_cpu = codes.cpu()
    code_lens_cpu = code_lens.cpu()
    recon_cpu = recon.cpu()
    loaded_dac.cpu()
    return wav_44k, codes_cpu, code_lens_cpu, recon_cpu


def test_modded_dac_strict_load_all_541_keys(loaded_dac):
    sd = loaded_dac.state_dict()
    assert len(sd) == 541
    assert loaded_dac.sample_rate == SAMPLE_RATE_HZ == 44100
    assert loaded_dac.hop_length == HOP_LENGTH == 512
    assert loaded_dac.frame_length == FRAME_LENGTH == 2048


def test_real_audio_encode_decode_roundtrip(real_voice_codes_and_audio):
    wav_44k, codes, code_lens, recon = real_voice_codes_and_audio
    assert codes.ndim == 3
    assert codes.shape[0] == 1
    assert codes.shape[1] == NUM_CODEBOOKS == 10
    seq_len = codes.shape[2]
    assert seq_len >= DEFAULT_SEQ_LEN
    assert int(code_lens[0].item()) == seq_len

    # Verify discrete codebook bounds match s2-pro spec (1x4096 semantic + 9x1024 residual).
    sem = codes[:, 0, :]
    res = codes[:, 1:, :]
    assert int(sem.min().item()) >= 0
    assert int(sem.max().item()) < SEMANTIC_CODEBOOK_SIZE
    assert int(res.min().item()) >= 0
    assert int(res.max().item()) < RESIDUAL_CODEBOOK_SIZE

    assert recon.shape == (1, 1, seq_len * FRAME_LENGTH)
    recon_np = recon[0, 0].numpy()
    assert np.isfinite(recon_np).all()

    ref = wav_44k
    rec = recon_np[: len(ref)]
    rms_rec = float(np.sqrt(np.mean(rec * rec)))
    assert rms_rec > 0.05

    lag0_corr = float(np.corrcoef(ref, rec)[0, 1])
    assert lag0_corr > 0.65, f"Lag-0 waveform correlation too low: {lag0_corr:.4f}"

    _, _, z_ref = spsig.stft(ref, fs=SAMPLE_RATE_HZ, nperseg=1024, noverlap=768)
    _, _, z_rec = spsig.stft(rec, fs=SAMPLE_RATE_HZ, nperseg=1024, noverlap=768)
    stft_corr = float(
        np.corrcoef(
            np.log(np.abs(z_ref) + 1e-5).ravel(),
            np.log(np.abs(z_rec) + 1e-5).ravel(),
        )[0, 1]
    )
    assert stft_corr > 0.88, f"Log-STFT spectral correlation too low: {stft_corr:.4f}"


def test_exportable_decoder_pytorch_parity_and_non_mutation(
    loaded_dac, real_voice_codes_and_audio
):
    _, codes, _, _ = real_voice_codes_and_audio
    codes_chunk = codes[:, :, :DEFAULT_SEQ_LEN].to(torch.int32).contiguous()

    exportable = ExportableModdedDACDecoder(loaded_dac, seq_len=DEFAULT_SEQ_LEN).eval()
    with torch.inference_mode():
        # Verify caller's original model still runs cleanly (not mutated by export copy).
        ref_pcm = loaded_dac.from_indices(codes_chunk.long()).numpy()
        exp_pcm = exportable(codes_chunk).numpy()

    assert exp_pcm.shape == (1, 1, DEFAULT_SEQ_LEN * FRAME_LENGTH)
    assert np.isfinite(exp_pcm).all()

    metrics = compute_parity_metrics(ref_pcm, exp_pcm)
    assert metrics["max_abs_diff"] < 1e-4, f"Unexpected wrapper drift: {metrics}"
    assert metrics["snr_db"] > 100.0, f"Unexpected wrapper SNR: {metrics}"


def test_exported_fp16_tflite_contract_tfl3_and_size():
    assert DEFAULT_OUTPUT_TFLITE.is_file(), f"Missing exported model: {DEFAULT_OUTPUT_TFLITE}"
    assert verify_tfl3_magic(DEFAULT_OUTPUT_TFLITE)

    size_bytes = DEFAULT_OUTPUT_TFLITE.stat().st_size
    size_mib = size_bytes / (1024 * 1024)
    # 184M decoder-side parameters in FP16 (~350-380 MiB)
    assert 200.0 <= size_mib <= 410.0, f"Unexpected FP16 TFLite size: {size_mib:.2f} MiB"

    if DEFAULT_REPORT_JSON.is_file():
        report = json.loads(DEFAULT_REPORT_JSON.read_text(encoding="utf-8"))
        assert report["tfl3_magic_valid"] is True
        assert report["fp16_size_bytes"] == size_bytes
        assert report["sha256"] == compute_sha256(DEFAULT_OUTPUT_TFLITE)


def test_exported_fp16_tflite_numerical_parity_on_ryan_voice(
    loaded_dac, real_voice_codes_and_audio
):
    _, codes, _, _ = real_voice_codes_and_audio
    codes_chunk = codes[:, :, :DEFAULT_SEQ_LEN].to(torch.int32).contiguous()

    with torch.inference_mode():
        pt_ref_pcm = loaded_dac.from_indices(codes_chunk.long()).numpy()

    tflite_pcm, contract, _ = run_tflite_interpreter(
        DEFAULT_OUTPUT_TFLITE, codes_chunk.numpy()
    )

    assert "serving_default" in contract["signatures"]
    assert contract["signatures"]["serving_default"]["inputs"] == ["codes"]
    assert contract["signatures"]["serving_default"]["outputs"] == ["pcm_44k"]
    assert contract["input"]["shape"] == [1, NUM_CODEBOOKS, DEFAULT_SEQ_LEN]
    assert contract["input"]["dtype"] == "int32"
    assert contract["output"]["shape"] == [1, 1, DEFAULT_SEQ_LEN * FRAME_LENGTH]
    assert contract["output"]["dtype"] == "float32"

    assert tflite_pcm.shape == (1, 1, DEFAULT_SEQ_LEN * FRAME_LENGTH)
    assert np.isfinite(tflite_pcm).all()

    metrics = compute_parity_metrics(pt_ref_pcm, tflite_pcm)
    assert metrics["snr_db"] > 45.0, f"FP16 TFLite SNR too low: {metrics}"
    assert metrics["rmse"] < 5e-3, f"FP16 TFLite RMSE too high: {metrics}"
    assert metrics["pearson_corr"] > 0.999, f"FP16 TFLite corr too low: {metrics}"


def test_exported_fp16_tflite_compiled_model_execution(real_voice_codes_and_audio):
    _, codes, _, _ = real_voice_codes_and_audio
    codes_chunk = codes[:, :, :DEFAULT_SEQ_LEN].to(torch.int32).contiguous().numpy()

    # Mirror Android LiteRtCodecDecoder.kt warm-up with all-zero codes, then real codes.
    zero_codes = np.zeros_like(codes_chunk)
    zero_res = run_tflite_compiled_model(DEFAULT_OUTPUT_TFLITE, zero_codes)
    assert zero_res is not None, "ai_edge_litert.compiled_model.CompiledModel unavailable"
    zero_pcm, _ = zero_res
    assert zero_pcm.shape == (1, 1, DEFAULT_SEQ_LEN * FRAME_LENGTH)
    assert np.isfinite(zero_pcm).all()

    cm_res = run_tflite_compiled_model(DEFAULT_OUTPUT_TFLITE, codes_chunk)
    assert cm_res is not None
    cm_pcm, _ = cm_res
    interp_pcm, _, _ = run_tflite_interpreter(DEFAULT_OUTPUT_TFLITE, codes_chunk)

    metrics = compute_parity_metrics(interp_pcm, cm_pcm)
    assert metrics["snr_db"] > 80.0, f"CompiledModel vs Interpreter drift: {metrics}"
    assert metrics["max_abs_diff"] < 1e-3


def test_export_helpers_and_magic_validation(tmp_path):
    recipe = build_fp16_float_casting_recipe()
    assert len(recipe) == len(FP16_FLOAT_CASTING_OPS)
    for entry in recipe:
        assert entry["algorithm_key"] == "float_casting"
        assert entry["op_config"]["weight_tensor_config"]["num_bits"] == 16
        assert entry["op_config"]["weight_tensor_config"]["dtype"] == "FLOAT"
        assert entry["op_config"]["compute_precision"] == "FLOAT"

    bad_file = tmp_path / "not_tflite.bin"
    bad_file.write_bytes(b"\x00\x00\x00\x00FAKE1234567890")
    assert verify_tfl3_magic(bad_file) is False

    short_file = tmp_path / "short.bin"
    short_file.write_bytes(b"TFL3")
    assert verify_tfl3_magic(short_file) is False

    valid_header_file = tmp_path / "ok.tflite"
    valid_header_file.write_bytes(b"\x1c\x00\x00\x00TFL3" + b"\x00" * 32)
    assert verify_tfl3_magic(valid_header_file) is True
    assert len(compute_sha256(valid_header_file)) == 64
