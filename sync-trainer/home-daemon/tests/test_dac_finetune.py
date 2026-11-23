"""Unit and real-artifact verification tests for ModdedDAC decoder fine-tuning (Fix #2).

Covers:
1. Strict zero-leakage train/eval dataset split & leakage guard invariants.
2. Differentiable acoustic losses (MR-STFT, 44.1 kHz Mel, 16 kHz Android/WeSpeaker speech-band
   Mel with bit-level resampling parity, SI-SNR) and finite gradient propagation.
3. Exact parameter freezing (`decoder.model` 54,104,162 params trainable; `encoder` + `quantizer`
   100% frozen) and bit-exact checkpoint preservation.
4. Causal left-context batch sampling, cosine LR schedule, EMA updates, and early stopping score.
5. Real `codec_finetuned_v002.pth` strict=True 541-key loading, frozen-key bit-exactness vs stock
   `codec.pth`, `dac_finetune_report.json` integrity, and `bundles/v002/codec_decoder_fp16.tflite`
   TFL3 header, `[1, 10, 64] -> [1, 1, 131072]` contract, and >55 dB FP16 parity.
"""

from __future__ import annotations

import json
import math
import random
import sys
from pathlib import Path

import numpy as np
import pytest
import torch

HOME_DAEMON_DIR = Path(__file__).resolve().parent.parent
if str(HOME_DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(HOME_DAEMON_DIR))

from enroll_voice_assets import (  # noqa: E402
    WeSpeakerEngine,
    compute_log_mel_filterbank,
    load_audio_mono,
    resample_44k_to_16k_android_exact,
)
from export_codec_decoder_tflite import (  # noqa: E402
    DEFAULT_SEQ_LEN,
    compute_parity_metrics,
    compute_sha256,
    verify_tfl3_magic,
)
from finetune_dac_decoder import (  # noqa: E402
    CAUSAL_CONTEXT_Z_FRAMES,
    DEFAULT_OUTPUT_PTH,
    DEFAULT_REPORT_JSON,
    DEFAULT_STOCK_CODEC_PTH,
    DEFAULT_V002_EXPORT_REPORT,
    DEFAULT_V002_TFLITE,
    DEFAULT_VOICE_REF_ALT,
    LEAKY_VOICE_FILENAMES,
    Z_FRAME_HOP_SAMPLES,
    AudioClipSpec,
    CachedEncodedClip,
    ClipEvalMetrics,
    DecoderEMA,
    FrozenWeSpeakerResNet34,
    MultiPeriodDiscriminator,
    MultiResolutionSTFTLoss,
    MultiScaleMelLoss,
    SpeechBand16kMelLoss,
    assert_no_split_leakage,
    build_train_eval_split,
    compute_cosine_lr,
    compute_early_stopping_score,
    compute_hinge_disc_loss,
    compute_hinge_gen_and_fm_loss,
    compute_si_snr_db,
    freeze_encoder_and_quantizer,
    sample_causal_crop_batch,
    save_finetuned_checkpoint,
    summarize_eval_metrics,
    verify_frozen_keys_bit_exact,
)
from modded_dac import (  # noqa: E402
    FRAME_LENGTH,
    NUM_CODEBOOKS,
    SAMPLE_RATE_HZ,
    ModdedDAC,
    encode_waveform_44k,
    load_s2_pro_dac,
)
from s2_pro_infer import decode_codes_batch_tflite  # noqa: E402


def test_train_eval_split_zero_leakage():
    train_clips, eval_clips = build_train_eval_split()
    assert len(eval_clips) == 7
    assert len(train_clips) >= 40
    assert any(c.clip_id == "voice_ref_alt" for c in eval_clips)

    train_ids = {c.clip_id for c in train_clips}
    eval_ids = {c.clip_id for c in eval_clips}
    assert not (train_ids & eval_ids)

    train_paths = {c.wav_path.resolve() for c in train_clips}
    eval_paths = {c.wav_path.resolve() for c in eval_clips}
    assert not (train_paths & eval_paths)

    train_shas = {c.sha256 for c in train_clips}
    eval_shas = {c.sha256 for c in eval_clips}
    assert not (train_shas & eval_shas)

    for c in train_clips:
        assert c.wav_path.name not in LEAKY_VOICE_FILENAMES
        assert c.manifest_dnsmos_p808 is not None and c.manifest_dnsmos_p808 >= 3.45
        assert c.manifest_wespeaker_cos is not None and c.manifest_wespeaker_cos >= 0.73


def test_leakage_guard_catches_overlap(tmp_path):
    f1 = tmp_path / "a.wav"
    f2 = tmp_path / "voice_ref.wav"
    f1.write_bytes(b"RIFF0000")
    f2.write_bytes(b"RIFF1111")

    clip_a = AudioClipSpec("clip_a", f1, "src_a", 5.0, sha256="aaa")
    clip_b = AudioClipSpec("clip_b", f1, "src_b", 5.0, sha256="bbb")
    with pytest.raises(AssertionError, match="paths"):
        assert_no_split_leakage([clip_a], [clip_b])

    clip_sha = AudioClipSpec("clip_c", tmp_path / "c.wav", "src_c", 5.0, sha256="aaa")
    with pytest.raises(AssertionError, match="SHA-256"):
        assert_no_split_leakage([clip_a], [clip_sha])

    clip_leaky = AudioClipSpec("voice_ref", f2, "voice_ref.wav", 26.4, sha256="ccc")
    with pytest.raises(AssertionError, match="Leaky reference file"):
        assert_no_split_leakage([clip_leaky], [])


def test_acoustic_losses_parity_and_gradients():
    assert DEFAULT_VOICE_REF_ALT.is_file()
    wav_44k, _ = load_audio_mono(DEFAULT_VOICE_REF_ALT, target_sr=SAMPLE_RATE_HZ)
    seg = wav_44k[:16384].copy()

    # Exact parity of differentiable 16 kHz resampler & CMN log-mel vs Android/enroll_voice_assets
    ref_16 = resample_44k_to_16k_android_exact(seg)
    ref_mel = compute_log_mel_filterbank(ref_16, log_floor=1e-10)

    sb = SpeechBand16kMelLoss(log_floor=1e-10)
    x_t = torch.from_numpy(seg).unsqueeze(0).requires_grad_(True)
    pt_16 = sb.resample_44k_to_16k(x_t)
    assert np.max(np.abs(pt_16[0].detach().numpy() - ref_16)) < 1e-6

    _, pt_cmn = sb.compute_features(x_t)
    assert np.max(np.abs(pt_cmn[0].detach().numpy() - ref_mel)) < 5e-4

    # Zero loss on identical signal, positive loss + finite grad on perturbed signal
    mr = MultiResolutionSTFTLoss()
    m44 = MultiScaleMelLoss()
    tgt = torch.from_numpy(seg).unsqueeze(0)
    sc_id, mag_id = mr(tgt, tgt)
    assert float(sc_id.item()) < 1e-6
    assert float(mag_id.item()) < 1e-6
    assert float(m44(tgt, tgt).item()) < 1e-6
    assert float(sb(tgt, tgt).item()) < 1e-6

    rng = np.random.default_rng(7)
    pert = torch.from_numpy(seg + 0.02 * rng.standard_normal(seg.shape).astype(np.float32)).unsqueeze(0)
    pert.requires_grad_(True)
    sc, mag = mr(pert, tgt)
    l44 = m44(pert, tgt)
    l16 = sb(pert, tgt)
    total = sc + mag + l44 + l16
    assert float(total.item()) > 0.1
    total.backward()
    assert pert.grad is not None
    assert torch.isfinite(pert.grad).all()
    assert float(pert.grad.abs().max().item()) > 1e-5

    # SI-SNR sanity
    assert compute_si_snr_db(seg, seg) > 60.0
    assert compute_si_snr_db(seg * 0.5, seg) > 60.0  # scale-invariant
    assert compute_si_snr_db(pert.detach().numpy()[0], seg) < 30.0


def test_frozen_wespeaker_resnet34_parity_and_mpd_losses():
    wav_44k, _ = load_audio_mono(DEFAULT_VOICE_REF_ALT, target_sr=SAMPLE_RATE_HZ)
    seg = wav_44k[:32768].copy()

    ws_ort = WeSpeakerEngine()
    ort_emb = ws_ort.extract_embedding(resample_44k_to_16k_android_exact(seg))

    sb = SpeechBand16kMelLoss(log_floor=1e-10)
    ws_pt = FrozenWeSpeakerResNet34()
    assert all(not p.requires_grad for p in ws_pt.parameters())

    x_t = torch.from_numpy(seg).unsqueeze(0).requires_grad_(True)
    _, cmn = sb.compute_features(x_t)
    pt_emb, feats = ws_pt.forward_with_features(cmn)
    assert len(feats) == 4
    max_diff = float(np.max(np.abs(pt_emb[0].detach().numpy() - ort_emb)))
    cos = float(np.dot(pt_emb[0].detach().numpy(), ort_emb))
    assert max_diff < 1e-5, f"WeSpeaker PyTorch vs ORT drift too high: {max_diff}"
    assert cos > 0.999999

    loss_ws = (1.0 - pt_emb.sum()) + sum(f.abs().mean() for f in feats)
    loss_ws.backward()
    assert x_t.grad is not None and torch.isfinite(x_t.grad).all()

    mpd = MultiPeriodDiscriminator(periods=(2, 3, 5))
    real = torch.from_numpy(seg).view(1, 1, -1)
    fake = (real + 0.01 * torch.randn_like(real)).detach().requires_grad_(True)
    r_outs, f_outs, r_fmaps, f_fmaps = mpd(real, fake)
    d_loss = compute_hinge_disc_loss(r_outs, f_outs)
    g_adv, g_fm = compute_hinge_gen_and_fm_loss(f_outs, r_fmaps, f_fmaps)
    assert torch.isfinite(d_loss) and torch.isfinite(g_adv) and torch.isfinite(g_fm)
    (g_adv + g_fm).backward()
    assert fake.grad is not None and torch.isfinite(fake.grad).all()


def test_freeze_encoder_and_quantizer_exact_param_counts():
    dac = ModdedDAC()
    trainable, frozen = freeze_encoder_and_quantizer(dac)
    assert trainable == 54_104_162
    assert frozen > 300_000_000
    assert dac.encoder.training is False
    assert dac.quantizer.training is False
    assert dac.decoder.training is True

    for name, p in dac.named_parameters():
        if name.startswith("decoder.model."):
            assert p.requires_grad is True, f"Expected trainable: {name}"
        else:
            assert p.requires_grad is False, f"Expected frozen: {name}"


def test_causal_crop_sampler_ema_lr_and_early_stopping(tmp_path):
    dummy_path = tmp_path / "dummy.wav"
    dummy_path.write_bytes(b"0")
    spec_short = AudioClipSpec("short", dummy_path, "s", 1.0)
    spec_long = AudioClipSpec("long", dummy_path, "l", 4.0)

    c_short = CachedEncodedClip(
        spec=spec_short,
        codes=torch.zeros(1, NUM_CODEBOOKS, 16, dtype=torch.int64),
        z_upsampled=torch.randn(1, 1024, 64),
        target_wav_44k=torch.randn(1, 1, 64 * Z_FRAME_HOP_SAMPLES),
    )
    c_long = CachedEncodedClip(
        spec=spec_long,
        codes=torch.zeros(1, NUM_CODEBOOKS, 80, dtype=torch.int64),
        z_upsampled=torch.randn(1, 1024, 320),
        target_wav_44k=torch.randn(1, 1, 320 * Z_FRAME_HOP_SAMPLES),
    )

    z_b, w_b = sample_causal_crop_batch(
        [c_short, c_long],
        batch_size=4,
        crop_z_frames=128,
        context_z_frames=CAUSAL_CONTEXT_Z_FRAMES,
        rng=random.Random(42),
        device=torch.device("cpu"),
    )
    assert z_b.shape == (4, 1024, 128 + CAUSAL_CONTEXT_Z_FRAMES)
    assert w_b.shape == (4, 1, 128 * Z_FRAME_HOP_SAMPLES)

    # Cosine LR warmup + decay
    lr0 = compute_cosine_lr(0, total_steps=100, peak_lr=2e-5, min_lr=2e-6, warmup_steps=10)
    lr9 = compute_cosine_lr(9, total_steps=100, peak_lr=2e-5, min_lr=2e-6, warmup_steps=10)
    lr_end = compute_cosine_lr(100, total_steps=100, peak_lr=2e-5, min_lr=2e-6, warmup_steps=10)
    assert math.isclose(lr0, 2e-6, rel_tol=1e-6)
    assert math.isclose(lr9, 2e-5, rel_tol=1e-6)
    assert math.isclose(lr_end, 2e-6, rel_tol=1e-6)

    # EMA shadow update
    lin = torch.nn.Linear(4, 4)
    ema = DecoderEMA(lin, decay=0.9)
    orig_w = lin.weight.detach().clone()
    with torch.no_grad():
        lin.weight.add_(1.0)
    ema.update(lin)
    assert torch.allclose(ema.shadow["weight"], orig_w + 0.1, atol=1e-6)

    # Early stopping score favors WeSpeaker + DNSMOS + log-STFT gains and penalizes DNSMOS drop
    base = summarize_eval_metrics(
        "base",
        [ClipEvalMetrics("a", 5.0, 0.88, 0.78, -0.10, 3.80, 3.70, -0.10, 3.3, 1.00, 0.25, 10.0, 0.02, 0.85)],
    )
    better = summarize_eval_metrics(
        "better",
        [ClipEvalMetrics("a", 5.0, 0.88, 0.84, -0.04, 3.80, 3.78, -0.02, 3.4, 0.90, 0.22, 11.5, 0.018, 0.88)],
    )
    regressed_dns = summarize_eval_metrics(
        "regressed",
        [ClipEvalMetrics("a", 5.0, 0.88, 0.80, -0.08, 3.80, 3.40, -0.40, 3.0, 1.05, 0.27, 9.5, 0.021, 0.84)],
    )
    assert compute_early_stopping_score(better, base) > 0.0
    assert compute_early_stopping_score(regressed_dns, base) < 0.0


def test_save_and_verify_frozen_keys_bit_exact_synthetic(tmp_path):
    stock_sd = {
        "encoder.block.0.weight": torch.randn(4, 4, dtype=torch.float32),
        "quantizer.post_module.freqs_cis": torch.randn(8, 2, dtype=torch.bfloat16),
        "quantizer.post_module.causal_mask": torch.tril(torch.ones(4, 4, dtype=torch.bool)),
        "decoder.model.0.conv.bias": torch.zeros(4, dtype=torch.float32),
        "decoder.model.0.conv.weight": torch.ones(4, 4, dtype=torch.float32),
    }
    stock_path = tmp_path / "stock.pth"
    ft_path = tmp_path / "ft.pth"
    torch.save(stock_sd, str(stock_path))

    new_dec = {
        "0.conv.bias": torch.full((4,), 0.25, dtype=torch.float32),
        "0.conv.weight": torch.full((4, 4), 1.5, dtype=torch.float32),
    }
    info = save_finetuned_checkpoint(stock_path, new_dec, ft_path)
    assert info["total_keys"] == 5
    assert info["updated_decoder_keys"] == 2
    assert info["frozen_bit_exact_keys"] == 3
    assert info["max_abs_weight_delta"] == pytest.approx(0.5, abs=1e-5)

    check = verify_frozen_keys_bit_exact(stock_path, ft_path)
    assert check["frozen_keys_bit_exact"] == 3
    assert check["decoder_keys_changed"] == 2

    # Mutate a frozen key and ensure verifier raises
    bad_sd = torch.load(str(ft_path), map_location="cpu", weights_only=True)
    bad_sd["encoder.block.0.weight"] = bad_sd["encoder.block.0.weight"] + 1e-4
    bad_path = tmp_path / "bad.pth"
    torch.save(bad_sd, str(bad_path))
    with pytest.raises(AssertionError, match="Frozen key mutated"):
        verify_frozen_keys_bit_exact(stock_path, bad_path)


def test_finetuned_v002_checkpoint_and_report_artifacts():
    assert DEFAULT_OUTPUT_PTH.is_file(), f"Missing fine-tuned checkpoint: {DEFAULT_OUTPUT_PTH}"
    assert DEFAULT_REPORT_JSON.is_file(), f"Missing fine-tune report: {DEFAULT_REPORT_JSON}"

    # 1. Strict 541-key load and bit-exact frozen encoder+quantizer verification
    bit_check = verify_frozen_keys_bit_exact(DEFAULT_STOCK_CODEC_PTH, DEFAULT_OUTPUT_PTH)
    assert bit_check["total_keys"] == 541
    assert bit_check["frozen_keys_bit_exact"] == 422
    assert bit_check["decoder_keys_changed"] > 0

    dac_v2 = load_s2_pro_dac(DEFAULT_OUTPUT_PTH, device="cpu", strict=True)
    assert len(dac_v2.state_dict()) == 541

    # 2. Report integrity & SHA-256 verification
    report = json.loads(DEFAULT_REPORT_JSON.read_text(encoding="utf-8"))
    assert report["dataset_split"]["zero_leakage_verified"] is True
    assert report["dataset_split"]["num_eval_clips"] == 7
    assert report["model_params"]["trainable_decoder_model_params"] == 54_104_162
    assert report["checkpoint"]["sha256"] == compute_sha256(DEFAULT_OUTPUT_PTH)
    assert report["frozen_keys_verification"]["frozen_keys_bit_exact"] == 422

    # Verify reported metrics are real finite numbers
    base = report["pytorch_heldout_baseline"]
    ft = report["pytorch_heldout_finetuned"]
    assert 0.5 < base["mean_wespeaker_cos"] < 1.0
    assert 0.5 < ft["mean_wespeaker_cos"] < 1.0
    assert 2.0 < base["mean_dnsmos_p808"] < 5.0
    assert 2.0 < ft["mean_dnsmos_p808"] < 5.0


def test_exported_v002_fp16_tflite_contract_and_parity():
    assert DEFAULT_V002_TFLITE.is_file(), f"Missing exported v002 TFLite: {DEFAULT_V002_TFLITE}"
    assert verify_tfl3_magic(DEFAULT_V002_TFLITE)
    size_mib = DEFAULT_V002_TFLITE.stat().st_size / (1024 * 1024)
    assert 200.0 <= size_mib <= 410.0

    assert DEFAULT_V002_EXPORT_REPORT.is_file()
    exp_report = json.loads(DEFAULT_V002_EXPORT_REPORT.read_text(encoding="utf-8"))
    assert exp_report["tfl3_magic_valid"] is True
    assert exp_report["sha256"] == compute_sha256(DEFAULT_V002_TFLITE)
    assert exp_report["tensor_contract"]["input"]["shape"] == [1, NUM_CODEBOOKS, DEFAULT_SEQ_LEN]
    assert exp_report["tensor_contract"]["input"]["dtype"] == "int32"
    assert exp_report["tensor_contract"]["output"]["shape"] == [
        1,
        1,
        DEFAULT_SEQ_LEN * FRAME_LENGTH,
    ]
    assert exp_report["tensor_contract"]["output"]["dtype"] == "float32"
    assert exp_report["tflite_interpreter_parity"]["snr_db"] > 55.0

    # End-to-end live numerical parity against PyTorch FP32 codec_finetuned_v002.pth
    wav_44k, _ = load_audio_mono(DEFAULT_VOICE_REF_ALT, target_sr=SAMPLE_RATE_HZ)
    dac_v2 = load_s2_pro_dac(DEFAULT_OUTPUT_PTH, device="cpu", strict=True)
    codes, _ = encode_waveform_44k(dac_v2, torch.from_numpy(wav_44k[: SAMPLE_RATE_HZ * 4]))
    codes_64 = codes[:, :, :DEFAULT_SEQ_LEN].to(torch.int32).contiguous()
    with torch.inference_mode():
        pt_pcm = dac_v2.from_indices(codes_64.long())[0, 0].numpy()

    decoded = decode_codes_batch_tflite(
        [codes_64[0].numpy()],
        tflite_path=DEFAULT_V002_TFLITE,
        chunk_frames=DEFAULT_SEQ_LEN,
    )
    tflite_pcm, meta = decoded[0]
    assert meta["output_samples"] == DEFAULT_SEQ_LEN * FRAME_LENGTH
    assert np.isfinite(tflite_pcm).all()

    parity = compute_parity_metrics(pt_pcm, tflite_pcm)
    assert parity["snr_db"] > 55.0, f"v002 FP16 TFLite parity SNR too low: {parity}"
    assert parity["pearson_corr"] > 0.999
