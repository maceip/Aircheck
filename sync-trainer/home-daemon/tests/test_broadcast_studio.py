"""Unit & integration tests for `render_broadcast_studio.py`."""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path
from typing import Any

import jsonschema
import numpy as np
import pytest
import soundfile as sf

HOME_DAEMON_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = HOME_DAEMON_DIR.parents[1]
SCHEMA_PATH = REPO_ROOT / "docs" / "swarm" / "schemas" / "clause_node.schema.json"
REAL_LEXICON_PATH = Path("/Users/mac/Downloads/voicestudio-models/bundles/v001/lexicon.json")

if str(HOME_DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(HOME_DAEMON_DIR))

import render_broadcast_studio as rbs  # noqa: E402
import synthesize_and_render as sar  # noqa: E402


def _make_speech_like_wave(
    duration_sec: float,
    *,
    f0_hz: float = 165.0,
    amplitude: float = 0.25,
    lead_silence_sec: float = 0.05,
    tail_silence_sec: float = 0.12,
    sample_rate: int = rbs.SAMPLE_RATE_HZ,
) -> np.ndarray:
    """Generate a smooth harmonic speech-band waveform with clean lead/tail decay."""
    n = int(round(duration_sec * sample_rate))
    t = np.arange(n, dtype=np.float64) / float(sample_rate)
    env = 0.65 + 0.35 * np.sin(2.0 * math.pi * 4.5 * t)
    wave = (
        0.55 * np.sin(2.0 * math.pi * f0_hz * t)
        + 0.30 * np.sin(2.0 * math.pi * (2.0 * f0_hz) * t)
        + 0.15 * np.sin(2.0 * math.pi * (3.0 * f0_hz) * t)
    )
    pcm = (amplitude * env * wave).astype(np.float64)

    lead = min(n // 4, int(round(lead_silence_sec * sample_rate)))
    tail = min(n // 4, int(round(tail_silence_sec * sample_rate)))
    if lead > 0:
        pcm[:lead] = 0.0
        ramp = min(660, n - lead)
        pcm[lead : lead + ramp] *= np.linspace(0.0, 1.0, ramp, dtype=np.float64)
    if tail > 0:
        ramp = min(880, n - tail)
        pcm[n - tail - ramp : n - tail] *= np.linspace(1.0, 0.0, ramp, dtype=np.float64)
        pcm[n - tail :] = 0.0

    return pcm.astype(np.float32)


def _make_room_ir_12ms() -> np.ndarray:
    ir = np.zeros(529, dtype=np.float32)
    ir[0] = 0.88
    ir[47] = 0.12
    ir[113] = -0.07
    ir[211] = 0.04
    return ir


def _make_room_tone(duration_sec: float = 1.0, seed: int = 17) -> np.ndarray:
    rng = np.random.default_rng(seed)
    n = int(round(duration_sec * rbs.SAMPLE_RATE_HZ))
    # Gentle low-passed room noise at ~-34 dBFS (calibrated to -58 dBFS by pipeline)
    white = rng.standard_normal(n).astype(np.float64)
    smooth = np.convolve(white, np.ones(8, dtype=np.float64) / 8.0, mode="same")
    return (smooth * 0.02).astype(np.float32)


def test_multi_clause_splitting_and_lexicon_ipa_overrides(tmp_path: Path) -> None:
    lexicon_file = tmp_path / "lexicon.json"
    lexicon_file.write_text(
        json.dumps(
            {
                "Aircheck": "ˈɛərˌtʃɛk",
                "Aircheck Studio": "ˈɛərˌtʃɛk ˈstuːdioʊ",
                "LUFS": "lʌfs",
                "Pixel": "ˈpɪksəl",
            }
        ),
        encoding="utf-8",
    )
    lexicon = rbs.load_lexicon(lexicon_file)

    long_script = (
        "Welcome back to Aircheck Studio at -16 LUFS on the Pixel. "
        "First we normalize every broadcast script with deterministic phonetic overrides, "
        "then we synthesize four discrete audio codebook rollouts through our rank thirty two adapter; "
        "next our verifier filters dropped words and speaker drift, "
        "and finally our five millisecond neon mastering stage locks integrated loudness to minus sixteen luffs, "
        "while keeping every single cut aligned to sample accurate zero crossings across the entire timeline, "
        "plus ensuring zero cloud latency when exporting on device. "
        "Dr. Smith paid $1,250.50 for 44.1 kHz audio!"
    )

    clauses = rbs.split_script_into_clauses(
        long_script,
        lexicon=lexicon,
        phonetic_overrides={"Pixel": "ˈpɪksəl_OVR"},
    )
    assert len(clauses) >= 4

    for idx, c in enumerate(clauses):
        assert c.clause_id == f"c_{idx + 1:03d}"
        assert c.index == idx
        assert c.estimated_duration_sec < rbs.MAX_CLAUSE_DURATION_SEC, (
            f"Clause {c.clause_id} exceeded 14s: {c.estimated_duration_sec}s"
        )
        assert c.normalized_phonemes
        if idx == 0:
            assert c.acoustic_prefix_pcm_path is None
        else:
            assert c.acoustic_prefix_pcm_path == f"takes/c_{idx:03d}_winner_tail_1500ms.pcm"

    # First clause applies phrase lexicon ("Aircheck Studio"), lexicon ("LUFS"), and inline override ("Pixel")
    first_ipa = clauses[0].normalized_phonemes
    assert "ˈɛərˌtʃɛk ˈstuːdioʊ" in first_ipa
    assert "lʌfs" in first_ipa
    assert "ˈpɪksəl_OVR" in first_ipa

    # Abbreviations & decimals remain inside one sentence
    assert any("Dr. Smith" in c.raw_text and "44.1 kHz" in c.raw_text for c in clauses)

    # Also verify real bundles/v001/lexicon.json loads cleanly if present on host
    if REAL_LEXICON_PATH.is_file():
        real_lex = rbs.load_lexicon(REAL_LEXICON_PATH)
        assert "Aircheck" in real_lex and len(real_lex) > 100


def test_zero_crossing_splice_continuity_and_gap_scaling() -> None:
    room_tone = _make_room_tone(1.0)
    room_ir = _make_room_ir_12ms()

    # Deliberately create 3 clauses with high-energy waveforms and off-zero initial cut targets
    # so the splice engine must snap within +/-88 samples AND equal-power cosine crossfade (660 samples).
    slices: list[rbs.SpliceSlice] = []
    for i, f0 in enumerate((155.0, 210.0, 180.0)):
        pcm = _make_speech_like_wave(
            1.6, f0_hz=f0, amplitude=0.35, lead_silence_sec=0.0, tail_silence_sec=0.0
        )
        # Choose deliberately non-zero start/end targets inside the active tone
        slices.append(
            rbs.SpliceSlice(
                clause_id=f"c_{i + 1:03d}",
                pcm44k=pcm,
                zero_cross_in=1137,
                zero_cross_out=pcm.size - 1143,
                base_gap_after_samples=rbs.DEFAULT_BASE_GAP_SAMPLES,
            )
        )

    stitched_full, meta_full = rbs.splice_clauses_edl(
        slices,
        room_tone_44k=room_tone,
        room_ir_44k=room_ir,
        global_gap_scale=1.0,
        apply_room_ir=True,
        apply_lufs_normalization=True,
    )
    stitched_tight, meta_tight = rbs.splice_clauses_edl(
        slices,
        room_tone_44k=room_tone,
        room_ir_44k=room_ir,
        global_gap_scale=0.25,
        apply_room_ir=True,
        apply_lufs_normalization=True,
    )

    assert stitched_full.size > stitched_tight.size
    expected_gap_diff = int(round(rbs.DEFAULT_BASE_GAP_SAMPLES * (1.0 - 0.25))) * 2
    assert abs((stitched_full.size - stitched_tight.size) - expected_gap_diff) <= 4

    # Core requirement: max |s[n] - s[n-1]| < 0.02f across ALL clause seams!
    assert len(meta_full["seam_sample_indices"]) == 4
    assert meta_full["max_seam_step"] < 0.02, f"Seam discontinuity too large: {meta_full['max_seam_step']}"
    assert meta_tight["max_seam_step"] < 0.02, f"Tight seam discontinuity too large: {meta_tight['max_seam_step']}"

    # Verify -16.0 LUFS +/- 0.5 LU and soft true-peak limiter <= 0.99f
    assert abs(meta_full["mastered_lufs"] - (-16.0)) <= 0.5
    assert meta_full["peak_amplitude"] <= 0.99
    assert abs(meta_tight["mastered_lufs"] - (-16.0)) <= 0.5
    assert meta_tight["peak_amplitude"] <= 0.99

    # Verify room tone sits near -58 dBFS inside the inter-clause gap
    b0 = meta_full["clause_boundaries"][0]
    gap_start = b0["activeEndSample"] + 64
    gap_end = b0["activeEndSample"] + b0["gapAfterSamples"] - 64
    gap_seg = stitched_full[gap_start:gap_end].astype(np.float64)
    gap_rms = float(np.sqrt(np.mean(gap_seg * gap_seg)))
    gap_dbfs = 20.0 * math.log10(max(gap_rms, 1e-12))
    assert -64.0 <= gap_dbfs <= -50.0, f"Unexpected inter-clause room tone floor: {gap_dbfs:.2f} dBFS"

    # Reject out-of-range global_gap_scale
    with pytest.raises(ValueError):
        rbs.splice_clauses_edl(slices, global_gap_scale=0.10)
    with pytest.raises(ValueError):
        rbs.splice_clauses_edl(slices, global_gap_scale=1.25)


def test_full_broadcast_studio_manifest_schema_and_aligned_pcm(tmp_path: Path) -> None:
    room_ir_path = tmp_path / "room_ir_44k.pcm"
    room_tone_path = tmp_path / "room_tone_44k.pcm"
    rbs.write_aligned_pcm_44k(room_ir_path, _make_room_ir_12ms())
    rbs.write_aligned_pcm_44k(room_tone_path, _make_room_tone(1.0))

    lexicon_path = tmp_path / "lexicon.json"
    lexicon_path.write_text(
        json.dumps({"Aircheck": "ˈɛərˌtʃɛk", "Pixel": "ˈpɪksəl", "LUFS": "lʌfs"}),
        encoding="utf-8",
    )

    script = (
        "We ripped out the cloud wrapper on the Pixel. "
        "Aircheck masters every clause to minus sixteen LUFS locally. "
        "Zero clicks across every single hairline edit."
    )

    def _synth_hook(clause: rbs.PlannedClause, n_takes: int) -> list[rbs.SynthesizedTakeData]:
        takes: list[rbs.SynthesizedTakeData] = []
        for t in range(n_takes):
            dur = 2.0 + 0.08 * t
            pcm = _make_speech_like_wave(dur, f0_hz=160.0 + 12.0 * clause.index + 3.0 * t)
            tokens = [151678 + ((clause.index * 500 + t * 40 + k * 7) % 4096) for k in range(32)]
            takes.append(
                rbs.SynthesizedTakeData(
                    pcm44k=pcm,
                    discrete_audio_tokens=tokens,
                    seed=948101 + clause.index * 100 + t,
                    temperature=0.72,
                )
            )
        return takes

    call_counter = {"n": 0}

    def _verify_hook(pcm: np.ndarray, expected_text: str) -> dict[str, Any]:
        take_idx = call_counter["n"] % 3
        call_counter["n"] += 1
        # Make take_idx == 1 the clear strict 4-gate winner for every clause!
        if take_idx == 1:
            return {
                "recognized_text": expected_text,
                "cer": 0.0,
                "max_phoneme_duration_ms": 140.0,
                "wespeaker_cosine": 0.91,
                "dnsmos_p808": 4.32,
                "tail_rms_dbfs": sar.compute_tail_rms_dbfs(pcm),
                "syllables_per_sec": 5.1,
            }
        return {
            "recognized_text": expected_text + " uh",
            "cer": 0.08 if take_idx == 0 else 0.0,
            "max_phoneme_duration_ms": 180.0,
            "wespeaker_cosine": 0.82,
            "dnsmos_p808": 3.95,
            "tail_rms_dbfs": sar.compute_tail_rms_dbfs(pcm),
            "syllables_per_sec": 4.8,
        }

    out_dir = tmp_path / "studio_run"
    report = rbs.render_broadcast_studio(
        script_text=script,
        project_id="aircheck_demo_001",
        output_dir=out_dir,
        takes_per_clause=3,
        global_gap_scale=0.60,
        lexicon_path=lexicon_path,
        lora_dir=Path("/tmp/lora-work-v002/peft_lora_adapter"),
        dac_tflite=Path("/tmp/bundles/v002/codec_decoder_fp16.tflite"),
        room_ir_path=room_ir_path,
        room_tone_path=room_tone_path,
        skip_video=True,
        schema_path=SCHEMA_PATH,
        synthesizer_fn=_synth_hook,
        verifier_fn=_verify_hook,
    )

    assert report["clause_count"] == 3
    assert report["active_lora_version"] == 2
    assert report["video"] is None
    assert report["splice"]["max_seam_step"] < 0.02
    assert report["master_pcm_bytes"] % rbs.PCM_ALIGNMENT_BYTES == 0

    # Master WAV exists at exact 44.1 kHz
    wav_data, wav_sr = sf.read(str(Path(report["master_wav_path"])), dtype="float32")
    assert wav_sr == 44100
    assert wav_data.size == report["splice"]["total_samples"]

    # Validate emitted VoiceProjectManifest.json directly against canonical JSON Schema!
    manifest_path = Path(report["manifest_path"])
    assert manifest_path.is_file()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator(schema).validate(manifest)

    assert manifest["projectId"] == "aircheck_demo_001"
    assert manifest["sampleRate"] == 44100
    assert manifest["globalGapScale"] == pytest.approx(0.60)
    assert manifest["activeLoraVersion"] == 2
    assert len(manifest["clauses"]) == 3

    for idx, clause_node in enumerate(manifest["clauses"]):
        assert clause_node["clauseId"] == f"c_{idx + 1:03d}"
        assert clause_node["selectedTakeIndex"] == 1  # Strict 4-gate winner picked!
        assert len(clause_node["candidates"]) == 3

        # Acoustic prefix tail chained and persisted on disk (64-byte aligned)
        if idx == 0:
            assert clause_node["acousticPrefixPcmPath"] is None
        else:
            prev_tail = out_dir / clause_node["acousticPrefixPcmPath"]
            assert prev_tail.is_file()
            assert prev_tail.stat().st_size % rbs.PCM_ALIGNMENT_BYTES == 0

        for cand in clause_node["candidates"]:
            pcm_file = out_dir / cand["pcmFilePath"]
            assert pcm_file.is_file()
            assert pcm_file.stat().st_size % rbs.PCM_ALIGNMENT_BYTES == 0
            assert pcm_file.stat().st_size >= cand["totalSamples"] * 4
            assert len(cand["discreteAudioTokens"]) == 32
            assert all(151678 <= tid <= 155773 for tid in cand["discreteAudioTokens"])
            assert len(cand["tokens"]) >= 4

            for tok in cand["tokens"]:
                assert abs(tok["zeroCrossIn"] - tok["startSample"]) <= rbs.ZERO_CROSS_SCAN_SAMPLES
                assert abs(tok["zeroCrossOut"] - tok["endSample"]) <= rbs.ZERO_CROSS_SCAN_SAMPLES
                assert tok["zeroCrossIn"] <= tok["zeroCrossOut"]
                assert tok["startSample"] <= tok["f0PeakSample"] <= tok["endSample"]

        assert clause_node["candidates"][1]["metrics"]["passedAllGates"] is True


def test_broadcast_studio_video_mode_hooks(tmp_path: Path) -> None:
    script = "First broadcast beat. Second broadcast beat."
    rendered_calls: list[tuple[Path, Path, str]] = []

    def _synth_hook(clause: rbs.PlannedClause, n_takes: int) -> list[rbs.SynthesizedTakeData]:
        return [
            rbs.SynthesizedTakeData(
                pcm44k=_make_speech_like_wave(1.5, f0_hz=175.0),
                discrete_audio_tokens=[151678 + k for k in range(16)],
                seed=100 + clause.index,
                temperature=0.7,
            )
            for _ in range(n_takes)
        ]

    def _verify_hook(pcm: np.ndarray, expected_text: str) -> dict[str, Any]:
        return {
            "recognized_text": expected_text,
            "cer": 0.0,
            "max_phoneme_duration_ms": 120.0,
            "wespeaker_cosine": 0.90,
            "dnsmos_p808": 4.25,
            "tail_rms_dbfs": sar.compute_tail_rms_dbfs(pcm),
            "syllables_per_sec": 5.0,
        }

    def _video_hook(wav_path: Path, mp4_path: Path, transcript: str) -> dict[str, Any]:
        rendered_calls.append((wav_path, mp4_path, transcript))
        return {"output_mp4": str(mp4_path), "transcript": transcript}

    report = rbs.render_broadcast_studio(
        script_text=script,
        project_id="video_test",
        output_dir=tmp_path / "vid_out",
        takes_per_clause=1,
        room_ir_path=None,
        room_tone_path=None,
        skip_video=False,
        video_mode="per-clause",
        schema_path=SCHEMA_PATH,
        synthesizer_fn=_synth_hook,
        verifier_fn=_verify_hook,
        video_renderer_fn=_video_hook,
    )

    assert report["video"] is not None
    assert report["video"]["mode"] == "per-clause"
    assert len(rendered_calls) == 2
    assert all(wav.is_file() for wav, _, _ in rendered_calls)


def test_cli_arg_parser_supports_script_file_v001_v002_and_skip_video(tmp_path: Path) -> None:
    script_file = tmp_path / "episode.txt"
    script_file.write_text("Clause one on Pixel. Clause two in Aircheck Studio.", encoding="utf-8")

    parser = rbs.build_arg_parser()
    args = parser.parse_args(
        [
            "--script-file",
            str(script_file),
            "--project-id",
            "ep_042",
            "--takes-per-clause",
            "4",
            "--lora-dir",
            "/Users/mac/Downloads/voicestudio-models/lora-work-v002/peft_lora_adapter",
            "--dac-tflite",
            "/Users/mac/Downloads/voicestudio-models/bundles/v002/codec_decoder_fp16.tflite",
            "--global-gap-scale",
            "0.45",
            "--phonetic-override",
            "Aircheck=ˈɛərˌtʃɛk",
            "--skip-video",
        ]
    )
    assert args.script_file == script_file
    assert args.project_id == "ep_042"
    assert args.takes_per_clause == 4
    assert rbs.infer_lora_version(Path(args.lora_dir)) == 2
    assert str(args.dac_tflite).endswith("v002/codec_decoder_fp16.tflite")
    assert args.global_gap_scale == pytest.approx(0.45)
    assert args.skip_video is True
    assert rbs._parse_phonetic_overrides(args.phonetic_override) == {"Aircheck": "ˈɛərˌtʃɛk"}


def test_real_bundle_v001_room_ir_and_room_tone_splice_if_present() -> None:
    ir_path = rbs.DEFAULT_BUNDLE_DIR / "room_ir_44k.pcm"
    tone_path = rbs.DEFAULT_BUNDLE_DIR / "room_tone_44k.pcm"
    if not (ir_path.is_file() and tone_path.is_file()):
        pytest.skip("Real bundles/v001 room_ir_44k.pcm / room_tone_44k.pcm not present")

    import enroll_voice_assets as eva

    room_ir = eva.read_pcm_44k(ir_path)
    room_tone = eva.read_pcm_44k(tone_path)
    slices = [
        rbs.SpliceSlice(
            clause_id=f"c_{i + 1:03d}",
            pcm44k=_make_speech_like_wave(1.4, f0_hz=150.0 + 25.0 * i, amplitude=0.30, lead_silence_sec=0.0, tail_silence_sec=0.0),
            zero_cross_in=900,
            zero_cross_out=int(1.4 * rbs.SAMPLE_RATE_HZ) - 900,
            base_gap_after_samples=rbs.DEFAULT_BASE_GAP_SAMPLES,
        )
        for i in range(3)
    ]
    stitched, meta = rbs.splice_clauses_edl(
        slices,
        room_tone_44k=room_tone,
        room_ir_44k=room_ir,
        global_gap_scale=0.5,
        apply_room_ir=True,
        apply_lufs_normalization=True,
    )
    assert stitched.size > 0
    assert meta["max_seam_step"] < 0.02, f"Real bundle seam step exceeded 0.02f: {meta['max_seam_step']}"
    assert abs(meta["mastered_lufs"] - (-16.0)) <= 0.5
    assert meta["peak_amplitude"] <= 0.99

