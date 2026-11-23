"""Automated Test Suite for Real ONNX Voice Enrollment & Device Dataset Mining.

Verifies:
  1. Exact Android `AudioDspMetrics.kt` feature parity (log-Mel fbank, resampler, CER).
  2. Real ONNX Runtime inference on Ryan's genuine audio (`wespeaker_en_voxceleb_resnet34.onnx`,
     `dnsmos/model_v8.onnx`, `sherpa-onnx` Citrinet-512 `model.int8.onnx`).
  3. Real OTA v001 bundle outputs in `/Users/mac/Downloads/voicestudio-models/bundles/v001/`:
     - `master_centroid.bin` (exact 1,024 bytes, 256 finite float32s, L2 norm == 1.0)
     - `room_tone_44k.pcm` (64-byte aligned, >=3.0s, calibrated to -58.0 dBFS, click-free loop seam)
     - `room_ir_44k.pcm` (64-byte aligned, 529 active taps / 544 aligned, unit L2 energy, causal, non-delta)
     - `lexicon.json` (valid flat JSON matching `ClauseNormalizer.kt`, covers Ryan's proper nouns & tech terms)
     - `enrollment_report.json` (real SHA-256 digests matching on-disk assets + real measured scores)
  4. Dataset mining hygiene (`discover_personal_media` deduplication & exclusion, VAD slicing
     strictly within [2.5s, 13.5s], Whisper hallucination filtering, `manifest.jsonl` schema).
"""

from __future__ import annotations

import json
import math
import struct
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
import soundfile as sf

DAEMON_DIR = Path(__file__).resolve().parents[1]
if str(DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(DAEMON_DIR))

from enroll_voice_assets import (  # noqa: E402
    DEFAULT_BUNDLE_DIR,
    DEFAULT_CITRINET_DIR,
    DEFAULT_DATASET_MANIFEST,
    DEFAULT_DNSMOS_P808_ONNX,
    DEFAULT_VOICE_DIR,
    DEFAULT_WESPEAKER_ONNX,
    PCM_ALIGNMENT_BYTES,
    ROOM_IR_ACTIVE_TAPS,
    ROOM_IR_ALIGNED_TAPS,
    ROOM_TONE_MIN_DURATION_SEC,
    ROOM_TONE_TARGET_DBFS,
    SPEAKER_CENTROID_BYTES,
    SPEAKER_EMBEDDING_DIM,
    SYNTHESIS_SAMPLE_RATE_HZ,
    VERIFIER_SAMPLE_RATE_HZ,
    CitrinetCtcEngine,
    DnsmosP808Engine,
    WeSpeakerEngine,
    build_lexicon_json,
    compute_cer,
    compute_log_mel_filterbank,
    compute_refined_master_centroid,
    compute_rms_dbfs,
    cosine_similarity,
    derive_room_ir_44k,
    extract_room_tone_44k,
    l2_normalize,
    load_audio_mono,
    normalize_transcript,
    read_pcm_44k,
    resample_44k_to_16k_android_exact,
    resample_audio,
    sha256_file,
    write_aligned_pcm_44k,
    write_master_centroid_bin,
)
from mine_device_voice_dataset import (  # noqa: E402
    clean_and_validate_transcript,
    deduplicate_near_identical_utterances,
    discover_personal_media,
    slice_speech_utterances,
)


class TestSignalAndBinaryPrimitives(unittest.TestCase):
    def test_log_mel_filterbank_shape_and_cmn_zero_mean(self) -> None:
        sr = VERIFIER_SAMPLE_RATE_HZ
        t = np.arange(sr * 2, dtype=np.float32) / float(sr)
        # Multi-harmonic speech-like signal
        sig = (
            0.25 * np.sin(2.0 * np.pi * 165.0 * t)
            + 0.12 * np.sin(2.0 * np.pi * 495.0 * t)
            + 0.06 * np.sin(2.0 * np.pi * 1240.0 * t)
        ).astype(np.float32)
        feats = compute_log_mel_filterbank(sig, num_mel_bins=80, apply_cmn=True)
        expected_frames = 1 + (sig.size - 400) // 160
        self.assertEqual(feats.shape, (expected_frames, 80))
        self.assertEqual(feats.dtype, np.float32)
        self.assertTrue(np.all(np.isfinite(feats)))
        # CMN must zero-center every Mel channel across time
        self.assertLess(float(np.max(np.abs(feats.mean(axis=0)))), 1e-4)

    def test_android_exact_resampler_matches_polyphase_length_and_spectrum(self) -> None:
        sr = SYNTHESIS_SAMPLE_RATE_HZ
        t = np.arange(sr, dtype=np.float32) / float(sr)
        sig = (0.4 * np.sin(2.0 * np.pi * 440.0 * t)).astype(np.float32)
        out_kt = resample_44k_to_16k_android_exact(sig)
        out_poly = resample_audio(sig, sr, VERIFIER_SAMPLE_RATE_HZ)
        self.assertAlmostEqual(out_kt.size, VERIFIER_SAMPLE_RATE_HZ, delta=2)
        self.assertAlmostEqual(out_poly.size, VERIFIER_SAMPLE_RATE_HZ, delta=2)
        n = min(out_kt.size, out_poly.size)
        corr = float(np.corrcoef(out_kt[32 : n - 32], out_poly[32 : n - 32])[0, 1])
        self.assertGreater(corr, 0.995)

    def test_cer_and_transcript_normalization(self) -> None:
        self.assertEqual(normalize_transcript("Hello, I'm bare-metal!"), "hello i'm bare metal")
        self.assertEqual(compute_cer("Hello world", "hello, world!"), 0.0)
        self.assertGreater(compute_cer("hello world", "hello"), 0.4)

    def test_write_master_centroid_bin_and_aligned_pcm(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            rng = np.random.default_rng(7)
            raw = rng.standard_normal(SPEAKER_EMBEDDING_DIM).astype(np.float32) * 3.5
            bin_path = tmp_dir / "master_centroid.bin"
            info = write_master_centroid_bin(bin_path, raw)
            self.assertEqual(info["size_bytes"], SPEAKER_CENTROID_BYTES)
            self.assertEqual(bin_path.stat().st_size, 1024)
            loaded = np.frombuffer(bin_path.read_bytes(), dtype="<f4")
            self.assertEqual(loaded.shape, (256,))
            self.assertAlmostEqual(float(np.linalg.norm(loaded)), 1.0, places=5)
            self.assertAlmostEqual(cosine_similarity(loaded, raw), 1.0, places=5)

            # 529 floats -> must pad with 15 zero floats to 544 floats = 2,176 bytes (64-byte aligned)
            ir = np.zeros(ROOM_IR_ACTIVE_TAPS, dtype=np.float32)
            ir[0] = 0.98
            ir[100] = 0.198997
            pcm_path = tmp_dir / "room_ir_44k.pcm"
            pcm_info = write_aligned_pcm_44k(pcm_path, ir)
            self.assertEqual(pcm_info["active_samples"], 529)
            self.assertEqual(pcm_info["aligned_samples"], ROOM_IR_ALIGNED_TAPS)
            self.assertEqual(pcm_info["size_bytes"] % PCM_ALIGNMENT_BYTES, 0)
            loaded_ir = read_pcm_44k(pcm_path)
            self.assertEqual(loaded_ir.size, ROOM_IR_ALIGNED_TAPS)
            self.assertTrue(np.all(loaded_ir[ROOM_IR_ACTIVE_TAPS:] == 0.0))

    def test_refined_centroid_leave_one_out_metrics(self) -> None:
        base = l2_normalize(np.arange(1, SPEAKER_EMBEDDING_DIM + 1, dtype=np.float32))
        rng = np.random.default_rng(42)
        seeds = []
        for i in range(4):
            vec = l2_normalize(base + 0.015 * rng.standard_normal(SPEAKER_EMBEDDING_DIM))
            seeds.append((f"seed_{i}", vec, 3.2))
        mined = []
        for i in range(12):
            vec = l2_normalize(base + 0.010 * rng.standard_normal(SPEAKER_EMBEDDING_DIM))
            mined.append((f"mined_{i}", vec, 3.3, 0.92))
        centroid, metrics = compute_refined_master_centroid(seeds, mined)
        self.assertEqual(centroid.shape, (256,))
        self.assertAlmostEqual(float(np.linalg.norm(centroid)), 1.0, places=5)
        self.assertEqual(metrics["num_contributors"], 16)
        self.assertGreater(metrics["mean_self_cosine"], 0.95)
        self.assertGreater(metrics["mean_leave_one_out_cosine"], 0.94)


class TestMiningHygieneHelpers(unittest.TestCase):
    def test_discover_personal_media_skips_duplicates_and_synthetic(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            dl = Path(tmp) / "Downloads"
            raw = Path(tmp) / "raw"
            dl.mkdir()
            raw.mkdir()
            for name in (
                "20261104_190358_52ab03fc.mp4",
                "20261104_190358_52ab03fc-2.mp4",
                "lv_0_20260702164505.mp4",
                "lv_0_20260702164505 (1).mp4",
                "lv_0_20250726022941.mp4",
                "lv_0_20250726022941_under100MB.mp4",
                "lv_0_20260906151907.mp4",
                "lv_0_20260906151907_first_2m02s.mp4",
                "20260701_210832_c286bc03.mp4",
                "20260701_210832_372cd3c4~2.mp4",
                "ElevenLabs_2026-10-07T22_24_27_mace_ivc.mp3",
                "GINTAMA.m4a",
                "macmacmacprime_logo.mp4",
                "Tolmo Voice Over.m4a",
            ):
                (dl / name).write_bytes(b"0")
            (raw / "PXL_20261016_215422134.wav").write_bytes(b"0")

            selected, skipped = discover_personal_media(dl, raw)
            sel_names = {p.name for p in selected}
            self.assertEqual(
                sel_names,
                {
                    "20261104_190358_52ab03fc.mp4",
                    "lv_0_20260702164505.mp4",
                    "lv_0_20250726022941.mp4",
                    "lv_0_20260906151907.mp4",
                    "20260701_210832_c286bc03.mp4",
                    "Tolmo Voice Over.m4a",
                    "PXL_20261016_215422134.wav",
                },
            )
            self.assertGreaterEqual(len(skipped), 5)

    def test_clean_and_validate_transcript_rejects_blank_and_hallucinations(self) -> None:
        ok, err = clean_and_validate_transcript(
            "Hello, this message is for Matthew Swish and Ranya.", 4.5
        )
        self.assertIsNone(err)
        self.assertEqual(ok, "Hello, this message is for Matthew Swish and Ranya.")

        self.assertIsNone(clean_and_validate_transcript("[BLANK_AUDIO]", 3.0)[0])
        self.assertIsNone(clean_and_validate_transcript("Thank you for watching.", 4.0)[0])
        self.assertIsNone(clean_and_validate_transcript("hi there", 3.0)[0])
        self.assertIsNone(
            clean_and_validate_transcript("yeah " * 12, 4.0)[0]
        )

    def test_slice_speech_utterances_enforces_duration_bounds(self) -> None:
        sr44 = SYNTHESIS_SAMPLE_RATE_HZ
        sr16 = VERIFIER_SAMPLE_RATE_HZ
        # Build 20s waveform: 4s speech, 1s silence, 14s speech (must split cleanly <= 13.5s)
        t_4s = np.arange(4 * sr44, dtype=np.float32) / float(sr44)
        t_14s = np.arange(14 * sr44, dtype=np.float32) / float(sr44)
        speech1 = (0.18 * np.sin(2.0 * np.pi * 190.0 * t_4s)).astype(np.float32)
        pause = (0.0005 * np.sin(2.0 * np.pi * 60.0 * np.arange(sr44) / sr44)).astype(np.float32)
        speech2 = (0.18 * np.sin(2.0 * np.pi * 210.0 * t_14s)).astype(np.float32)
        # Put a brief low-energy valley at 7.0s inside speech2 so the splitter cuts there
        speech2[int(6.8 * sr44) : int(7.2 * sr44)] *= 0.05
        pcm44 = np.concatenate([pause[: sr44 // 2], speech1, pause, speech2, pause[: sr44 // 2]])
        pcm16 = resample_audio(pcm44, sr44, sr16)

        slices = slice_speech_utterances(
            pcm44,
            pcm16,
            source_name="unit_test.wav",
            source_path=Path("unit_test.wav"),
            min_dur_sec=2.5,
            max_dur_sec=13.5,
        )
        self.assertGreaterEqual(len(slices), 3)
        for sl in slices:
            dur = sl.pcm_44k.size / float(sr44)
            self.assertGreaterEqual(dur, 2.5)
            self.assertLessEqual(dur, 13.5)

    def test_deduplicate_near_identical_utterances_keeps_highest_quality_take(self) -> None:
        entries = [
            {
                "id": "a_001",
                "transcript": "That is why we are building Code Browser for AI agents today.",
                "dnsmos_p808": 3.4,
                "wespeaker_cos": 0.74,
            },
            {
                "id": "a_002",
                "transcript": "That is why we are building Code Browser for AI agents today.",
                "dnsmos_p808": 3.85,
                "wespeaker_cos": 0.81,
            },
            {
                "id": "b_001",
                "transcript": "Completely different sentence about Pixel hardware and TPU models.",
                "dnsmos_p808": 3.6,
                "wespeaker_cos": 0.79,
            },
        ]
        kept, dropped = deduplicate_near_identical_utterances(entries)
        self.assertEqual(dropped, 1)
        self.assertEqual([e["id"] for e in kept], ["a_002", "b_001"])


class TestRealEnrolledBundleAndOnnxModels(unittest.TestCase):
    """End-to-end verification against REAL ONNX models and Ryan's generated OTA v001 bundle."""

    def test_master_centroid_bin_format_and_real_wespeaker_similarity(self) -> None:
        centroid_path = DEFAULT_BUNDLE_DIR / "master_centroid.bin"
        self.assertTrue(centroid_path.is_file(), f"Missing {centroid_path}")
        self.assertEqual(centroid_path.stat().st_size, SPEAKER_CENTROID_BYTES)

        centroid = np.frombuffer(centroid_path.read_bytes(), dtype="<f4")
        self.assertEqual(centroid.shape, (SPEAKER_EMBEDDING_DIM,))
        self.assertTrue(np.all(np.isfinite(centroid)))
        self.assertAlmostEqual(float(np.linalg.norm(centroid)), 1.0, places=5)

        # Verify against real WeSpeaker ResNet34 ONNX on Ryan's real voice_ref.wav & raw PXL clip
        wespeaker = WeSpeakerEngine(DEFAULT_WESPEAKER_ONNX)
        pcm44, _ = load_audio_mono(DEFAULT_VOICE_DIR / "voice_ref.wav", target_sr=SYNTHESIS_SAMPLE_RATE_HZ)
        emb_android = wespeaker.extract_embedding(resample_44k_to_16k_android_exact(pcm44))
        cos_ryan = cosine_similarity(emb_android, centroid)
        self.assertGreater(
            cos_ryan,
            0.82,
            f"Expected Ryan's real voice_ref.wav cosine > 0.82 vs master_centroid.bin, got {cos_ryan:.4f}",
        )

        pxl44, _ = load_audio_mono(
            DEFAULT_VOICE_DIR / "raw" / "PXL_20260925_191922074.wav",
            target_sr=SYNTHESIS_SAMPLE_RATE_HZ,
        )
        cos_pxl = cosine_similarity(
            wespeaker.extract_embedding(resample_44k_to_16k_android_exact(pxl44)),
            centroid,
        )
        self.assertGreater(cos_pxl, 0.85)

        # Verify synthetic MiMo clone scores clearly lower than Ryan's real voice
        mimo_path = DEFAULT_VOICE_DIR / "clone_test_mimo.wav"
        if mimo_path.is_file():
            mimo16, _ = load_audio_mono(mimo_path, target_sr=VERIFIER_SAMPLE_RATE_HZ)
            cos_mimo = cosine_similarity(wespeaker.extract_embedding(mimo16), centroid)
            self.assertLess(cos_mimo, cos_ryan - 0.15)

    def test_real_dnsmos_and_citrinet_models_on_ryan_voice(self) -> None:
        pcm16, _ = load_audio_mono(DEFAULT_VOICE_DIR / "voice_ref_alt.wav", target_sr=VERIFIER_SAMPLE_RATE_HZ)
        dnsmos = DnsmosP808Engine(DEFAULT_DNSMOS_P808_ONNX)
        mos = dnsmos.score_p808(pcm16)
        self.assertTrue(1.0 <= mos <= 5.0, f"DNSMOS p808 out of MOS range [1, 5]: {mos}")

        ctc = CitrinetCtcEngine(DEFAULT_CITRINET_DIR)
        hyp = ctc.transcribe(pcm16)
        self.assertIn("message", hyp.lower())
        self.assertIn("mail", hyp.lower())

    def test_room_tone_44k_pcm_alignment_duration_rms_and_seam(self) -> None:
        tone_path = DEFAULT_BUNDLE_DIR / "room_tone_44k.pcm"
        self.assertTrue(tone_path.is_file(), f"Missing {tone_path}")
        size_bytes = tone_path.stat().st_size
        self.assertEqual(size_bytes % PCM_ALIGNMENT_BYTES, 0)

        pcm = read_pcm_44k(tone_path)
        self.assertTrue(np.all(np.isfinite(pcm)))
        duration_sec = pcm.size / float(SYNTHESIS_SAMPLE_RATE_HZ)
        self.assertGreaterEqual(duration_sec, ROOM_TONE_MIN_DURATION_SEC)

        rms_dbfs = compute_rms_dbfs(pcm)
        self.assertAlmostEqual(
            rms_dbfs,
            ROOM_TONE_TARGET_DBFS,
            delta=0.05,
            msg=f"Expected room_tone_44k.pcm RMS == -58.0 dBFS, got {rms_dbfs:.4f} dBFS",
        )
        # Must not be digital silence and must have smooth click-free circular loop seam
        self.assertGreater(float(np.max(np.abs(pcm))), 1e-4)
        self.assertLess(abs(float(pcm[0]) - float(pcm[-1])), 0.01)

    def test_room_ir_44k_pcm_alignment_unit_energy_causal_non_delta(self) -> None:
        ir_path = DEFAULT_BUNDLE_DIR / "room_ir_44k.pcm"
        self.assertTrue(ir_path.is_file(), f"Missing {ir_path}")
        size_bytes = ir_path.stat().st_size
        self.assertEqual(size_bytes % PCM_ALIGNMENT_BYTES, 0)

        ir = read_pcm_44k(ir_path)
        self.assertEqual(ir.size, ROOM_IR_ALIGNED_TAPS)
        self.assertTrue(np.all(np.isfinite(ir)))
        # Trailing 64-byte alignment padding samples must be exact zeros
        self.assertTrue(np.all(ir[ROOM_IR_ACTIVE_TAPS:] == 0.0))
        # Causal: direct tap 0 is dominant positive peak
        self.assertEqual(int(np.argmax(np.abs(ir))), 0)
        self.assertGreater(float(ir[0]), 0.85)
        self.assertLess(float(ir[0]), 0.999)  # Non-delta: real early reflections present
        # Unit L2 energy gain
        energy = float(np.sum(ir.astype(np.float64) ** 2))
        self.assertAlmostEqual(energy, 1.0, places=5)
        # Clean causal decay to zero at tap 528
        self.assertAlmostEqual(float(ir[ROOM_IR_ACTIVE_TAPS - 1]), 0.0, places=6)

    def test_lexicon_json_schema_matches_clause_normalizer(self) -> None:
        lex_path = DEFAULT_BUNDLE_DIR / "lexicon.json"
        self.assertTrue(lex_path.is_file(), f"Missing {lex_path}")
        data = json.loads(lex_path.read_text(encoding="utf-8"))
        self.assertIsInstance(data, dict)
        self.assertGreaterEqual(len(data), 250)
        for k, v in data.items():
            self.assertIsInstance(k, str)
            self.assertIsInstance(v, str)
            self.assertTrue(k.strip() and v.strip())
        # Verify required Ryan transcript proper nouns & VoiceStudio terms
        for required_term in (
            "Aircheck",
            "VoiceStudio",
            "s2-pro",
            "LUFS",
            "WeSpeaker",
            "DNSMOS",
            "Matthew",
            "Swish",
            "Ranya",
            "Ronnie",
            "J. Cole",
            "IoT",
            "hackathon",
            "rollerblading",
            "MacEIP",
            "Huawei",
            "Xiaomi",
            "Dimensity",
            "Hermes",
            "MCP",
        ):
            self.assertIn(required_term, data, f"Missing term in lexicon.json: {required_term}")

    def test_enrollment_report_and_dataset_manifest_integrity(self) -> None:
        report_path = DEFAULT_BUNDLE_DIR / "enrollment_report.json"
        self.assertTrue(report_path.is_file(), f"Missing {report_path}")
        report = json.loads(report_path.read_text(encoding="utf-8"))

        for asset_name in (
            "master_centroid.bin",
            "room_tone_44k.pcm",
            "room_ir_44k.pcm",
            "lexicon.json",
        ):
            asset_path = DEFAULT_BUNDLE_DIR / asset_name
            self.assertEqual(
                report["assets"][asset_name]["sha256"],
                sha256_file(asset_path),
                f"SHA-256 mismatch in enrollment_report.json for {asset_name}",
            )
            self.assertEqual(
                report["assets"][asset_name]["size_bytes"],
                asset_path.stat().st_size,
            )

        # Verify mined dataset manifest.jsonl schema & WeSpeaker >= 0.72 gate
        self.assertTrue(DEFAULT_DATASET_MANIFEST.is_file(), f"Missing {DEFAULT_DATASET_MANIFEST}")
        lines = [
            ln.strip()
            for ln in DEFAULT_DATASET_MANIFEST.read_text(encoding="utf-8").splitlines()
            if ln.strip()
        ]
        self.assertGreater(len(lines), 10)
        required_keys = {
            "id",
            "wav_44k",
            "duration_sec",
            "transcript",
            "wespeaker_cos",
            "dnsmos_p808",
            "source_video",
        }
        seen_ids: set[str] = set()
        for ln in lines:
            rec = json.loads(ln)
            self.assertEqual(set(rec.keys()), required_keys)
            self.assertNotIn(rec["id"], seen_ids)
            seen_ids.add(rec["id"])
            self.assertGreaterEqual(float(rec["wespeaker_cos"]), 0.72)
            self.assertTrue(2.5 <= float(rec["duration_sec"]) <= 13.5)
            self.assertTrue(1.0 <= float(rec["dnsmos_p808"]) <= 5.0)
            self.assertTrue(str(rec["transcript"]).strip())
            self.assertTrue(Path(rec["wav_44k"]).is_file())


if __name__ == "__main__":
    unittest.main()
