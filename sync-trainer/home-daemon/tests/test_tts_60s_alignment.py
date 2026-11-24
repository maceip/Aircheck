"""M2.2 automated verification suite:
1. 60s / >15s multi-clause splitting, caller_clauses ID mapping, dynamic token budgets, and EDL splicing.
2. Real forced word & clause alignment via `whisper-cli -ojf` (non-proportional pauses, segment-boundary flush, strict failure on malformed JSON).
3. Strict 4-gate enforcement & warm retries across `render_broadcast_studio.py`, `synthesize_and_render.py`, `/v1/core/tts`, and `/v1/render-video`.
4. `s2_pro_infer.py` v002 DAC parity checkpoint resolution (`codec_finetuned_v002.pth`) + `WarmS2ProEngine` + `resolve_latest_synthesis_assets`.
5. Ephemeral `port=0` HTTP integration tests for sync & `?async=1` `/v1/core/tts`, `/v1/render-video`, and `GET /v1/renders/align_<id>.json`.
"""

from __future__ import annotations

import json
import math
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import wave
from pathlib import Path
from typing import Any

import numpy as np
import pytest
import soundfile as sf

HOME_DAEMON_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = HOME_DAEMON_DIR.parents[1]
SCHEMA_PATH = REPO_ROOT / "docs" / "swarm" / "schemas" / "clause_node.schema.json"

if str(HOME_DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(HOME_DAEMON_DIR))

import ota_server  # noqa: E402
import render_broadcast_studio as rbs  # noqa: E402
import s2_pro_infer  # noqa: E402
import synthesize_and_render as sar  # noqa: E402


def _make_speech_wave(
    duration_sec: float,
    *,
    f0_hz: float = 168.0,
    amplitude: float = 0.28,
    sample_rate: int = rbs.SAMPLE_RATE_HZ,
) -> np.ndarray:
    n = int(round(duration_sec * sample_rate))
    t = np.arange(n, dtype=np.float64) / float(sample_rate)
    env = 0.65 + 0.35 * np.sin(2.0 * math.pi * 4.2 * t)
    wave_arr = (
        0.55 * np.sin(2.0 * math.pi * f0_hz * t)
        + 0.30 * np.sin(2.0 * math.pi * (2.0 * f0_hz) * t)
        + 0.15 * np.sin(2.0 * math.pi * (3.0 * f0_hz) * t)
    )
    pcm = (amplitude * env * wave_arr).astype(np.float64)
    lead = min(n // 8, int(round(0.04 * sample_rate)))
    tail = min(n // 8, int(round(0.10 * sample_rate)))
    if lead > 0:
        pcm[:lead] = 0.0
    if tail > 0:
        ramp = min(660, n - tail)
        pcm[n - tail - ramp : n - tail] *= np.linspace(1.0, 0.0, ramp, dtype=np.float64)
        pcm[n - tail :] = 0.0
    return pcm.astype(np.float32)


def _make_room_ir_and_tone(tmp_path: Path) -> tuple[Path, Path]:
    ir = np.zeros(529, dtype=np.float32)
    ir[0] = 0.90
    ir[43] = 0.10
    rng = np.random.default_rng(23)
    tone = (np.convolve(rng.standard_normal(rbs.SAMPLE_RATE_HZ), np.ones(8) / 8.0, mode="same") * 0.02).astype(
        np.float32
    )
    ir_path = tmp_path / "room_ir_44k.pcm"
    tone_path = tmp_path / "room_tone_44k.pcm"
    rbs.write_aligned_pcm_44k(ir_path, ir)
    rbs.write_aligned_pcm_44k(tone_path, tone)
    return ir_path, tone_path


def _write_minimal_bundle(bundle_dir: Path, *, lora_version: int = 2) -> None:
    bundle_dir.mkdir(parents=True, exist_ok=True)
    files = {
        "s2_pro_4b_q4_k_m.gguf": b"GGUF" + b"\x01" * 32,
        f"voice_lora_v{lora_version:03d}.gguf": b"GGUF" + b"LORA" * 8,
        "codec_decoder_fp16.tflite": b"TFL3" + b"\x22" * 32,
        "sherpa_ctc_aligner.tar.gz": b"\x1f\x8b\x08\x00" + b"CTC" * 8,
        "wespeaker_resnet34.onnx": b"ONNX_WESP" * 4,
        "master_centroid.bin": b"\x00\x00\x80\x3f" * 256,
        "dnsmos_p808.onnx": b"ONNX_DNSMOS" * 4,
        "room_ir_44k.pcm": b"\x00\x00\x00\x3f" * 32,
        "room_tone_44k.pcm": b"\x00\x00\x80\x38" * 32,
        "lexicon.json": b'{"Aircheck":"test"}\n',
    }
    for name, content in files.items():
        (bundle_dir / name).write_bytes(content)
    from tinker_voice_daemon import build_bundle_manifest

    build_bundle_manifest(
        bundle_dir=bundle_dir,
        bundle_version=f"v{lora_version:03d}",
        lora_version=lora_version,
    )


# ---------------------------------------------------------------------------
# 1. 60s / >15s Multi-Clause Splitting, Caller Clause ID Mapping & EDL Splice
# ---------------------------------------------------------------------------


def test_60s_multi_clause_splitting_caller_ids_and_edl_mastering(tmp_path: Path) -> None:
    ir_path, tone_path = _make_room_ir_and_tone(tmp_path)
    lexicon_path = tmp_path / "lexicon.json"
    lexicon_path.write_text(json.dumps({"Aircheck": "ˈɛərˌtʃɛk", "LUFS": "lʌfs"}), encoding="utf-8")

    # Build a ~165-word / ~60s multi-sentence broadcast script with one deliberately long (>15s) caller clause.
    long_caller_clause_text = (
        "Tonight in downtown San Francisco engineers rolled out a complete bare metal voice synthesis stack "
        "running locally on Apple Silicon without any cloud wrapper, preserving sample accurate zero crossings "
        "across every broadcast transition; meanwhile independent verification gates measure character error rate, "
        "speaker embedding similarity, acoustic signal quality, and natural syllable cadence in real time, "
        "ensuring that sixty full seconds of multi clause reporting never stall or truncate mid sentence."
    )
    short_clause_1 = "Welcome back to Aircheck Studio at minus sixteen LUFS."
    short_clause_2 = "Every word boundary locks directly to the stitched master timeline."

    caller_clauses = [
        {"id": "lead_open", "spoken_text": short_clause_1},
        {"id": "deep_body", "spoken_text": long_caller_clause_text},
        {"id": "close_tag", "spoken_text": short_clause_2},
    ]

    planned, parent_groups = rbs.plan_caller_clauses(caller_clauses, lexicon=rbs.load_lexicon(lexicon_path))
    assert len(planned) >= 5
    assert len(parent_groups) == 3
    assert len(parent_groups[1]["sub_indices"]) >= 2, "Long >15s caller clause must split into multiple <14s sub-clauses"
    for c in planned:
        assert c.estimated_duration_sec < rbs.MAX_CLAUSE_DURATION_SEC
        # Dynamic token budget scales above 320 for longer clauses without exceeding 420
        dyn_tokens = max(320, min(420, int(round(c.estimated_duration_sec * 21.5 * 1.45))))
        assert 320 <= dyn_tokens <= 420

    # Target ~60s active speech across the planned sub-clauses
    per_clause_sec = 60.5 / float(len(planned))

    def _synth_60s(clause: rbs.PlannedClause, n_takes: int) -> list[rbs.SynthesizedTakeData]:
        pcm = _make_speech_wave(per_clause_sec, f0_hz=155.0 + 7.0 * clause.index)
        return [
            rbs.SynthesizedTakeData(
                pcm44k=pcm,
                discrete_audio_tokens=[151678 + k for k in range(24)],
                seed=948101 + clause.index * 10 + t,
                temperature=0.72,
            )
            for t in range(n_takes)
        ]

    def _verify_60s(pcm: np.ndarray, expected_text: str) -> dict[str, Any]:
        tokens = [w for w in expected_text.replace(";", "").replace(",", "").replace(".", "").split() if w]
        start_s = int(0.04 * rbs.SAMPLE_RATE_HZ)
        end_s = max(start_s + 1000, pcm.size - int(0.05 * rbs.SAMPLE_RATE_HZ))
        weights = [0.6 if idx % 2 == 0 else 1.4 for idx in range(len(tokens))]
        total_w = float(sum(weights))
        spans: list[tuple[str, int, int]] = []
        cursor = float(start_s)
        span_total = float(end_s - start_s)
        for idx, (tok, w) in enumerate(zip(tokens, weights)):
            s0 = int(round(cursor))
            cursor += (w / total_w) * span_total
            s1 = end_s if idx == len(tokens) - 1 else max(s0 + 100, int(round(cursor)) - 80)
            spans.append((tok, min(end_s - 1, s0), min(end_s, max(s0 + 1, s1))))
        return {
            "recognized_text": expected_text,
            "cer": 0.0,
            "max_phoneme_duration_ms": 145.0,
            "wespeaker_cosine": 0.91,
            "dnsmos_p808": 4.30,
            "tail_rms_dbfs": sar.compute_tail_rms_dbfs(pcm),
            "syllables_per_sec": 5.0,
            "word_spans": spans,
        }

    report = rbs.render_broadcast_studio(
        caller_clauses=caller_clauses,
        project_id="sixty_sec_story",
        output_dir=tmp_path / "studio_60s",
        takes_per_clause=2,
        lexicon_path=lexicon_path,
        room_ir_path=ir_path,
        room_tone_path=tone_path,
        skip_video=True,
        require_strict_gates=True,
        require_real_alignment=True,
        schema_path=SCHEMA_PATH,
        synthesizer_fn=_synth_60s,
        verifier_fn=_verify_60s,
    )

    assert report["duration_ms"] >= 55_000, f"Expected ~60s master WAV, got {report['duration_ms']}ms"
    assert report["splice"]["max_seam_step"] < 0.02
    assert abs(report["splice"]["mastered_lufs"] - (-16.0)) <= 0.6
    assert report["metrics"]["passed_all_strict_gates"] is True
    assert report["metrics"]["cer"] == 0.0

    # Caller clause IDs preserved across sub-splits
    timed_clauses = report["timed_clauses"]
    assert [c["id"] for c in timed_clauses] == ["lead_open", "deep_body", "close_tag"]
    assert timed_clauses[0]["start_ms"] == 0
    assert timed_clauses[-1]["end_ms"] == report["duration_ms"]
    assert timed_clauses[1]["end_ms"] - timed_clauses[1]["start_ms"] > 20_000

    # Monotonic non-decreasing master-timeline words
    words = report["words"]
    assert len(words) >= 70
    for idx, w in enumerate(words):
        assert 0 <= w["start_ms"] <= w["end_ms"] <= report["duration_ms"]
        if idx > 0:
            assert w["start_ms"] >= words[idx - 1]["start_ms"]


# ---------------------------------------------------------------------------
# 2. Real Forced Word Alignment (`whisper-cli -ojf`) + Segment Flush + Failures
# ---------------------------------------------------------------------------


def test_real_whisper_cli_forced_alignment_non_proportional_pauses(tmp_path: Path) -> None:
    if not (sar.DEFAULT_WHISPER_BIN.is_file() and sar.DEFAULT_WHISPER_MODEL.is_file()):
        pytest.skip("whisper-cli or ggml-medium-q5_0.bin not present on host")
    say_bin = shutil.which("say")
    ffmpeg_bin = shutil.which("ffmpeg")
    if not say_bin or not ffmpeg_bin:
        pytest.skip("macOS say or ffmpeg not available")

    # Synthesize two real spoken phrases separated by a genuine 1.8s silence gap!
    aiff_1 = tmp_path / "p1.aiff"
    aiff_2 = tmp_path / "p2.aiff"
    wav_1 = tmp_path / "p1.wav"
    wav_2 = tmp_path / "p2.wav"
    subprocess.run([say_bin, "-v", "Samantha", "-o", str(aiff_1), "Alpha signal confirmed."], check=True, timeout=30)
    subprocess.run([say_bin, "-v", "Samantha", "-o", str(aiff_2), "Bravo channel locked."], check=True, timeout=30)
    subprocess.run([ffmpeg_bin, "-y", "-i", str(aiff_1), "-ar", "16000", "-ac", "1", str(wav_1)], check=True, capture_output=True, timeout=30)
    subprocess.run([ffmpeg_bin, "-y", "-i", str(aiff_2), "-ar", "16000", "-ac", "1", str(wav_2)], check=True, capture_output=True, timeout=30)

    p1, sr1 = sf.read(str(wav_1), dtype="float32")
    p2, sr2 = sf.read(str(wav_2), dtype="float32")
    assert sr1 == 16000 and sr2 == 16000

    gap = np.zeros(int(1.8 * 16000), dtype=np.float32)
    combined_16k = np.concatenate([p1, gap, p2])

    transcript, word_spans = rbs.transcribe_pcm16k_whisper_with_words(
        combined_16k,
        whisper_bin=sar.DEFAULT_WHISPER_BIN,
        whisper_model=sar.DEFAULT_WHISPER_MODEL,
        sample_rate_hz=rbs.SAMPLE_RATE_HZ,
    )
    assert transcript
    assert len(word_spans) >= 4

    # Verify non-proportional timestamps reflect the real ~1.8s gap between phrase 1 and phrase 2!
    words_lower = [w.lower() for w, _, _ in word_spans]
    assert any("alpha" in w for w in words_lower)
    assert any("bravo" in w for w in words_lower)
    alpha_idx = next(i for i, w in enumerate(words_lower) if "alpha" in w)
    bravo_idx = next(i for i, w in enumerate(words_lower) if "bravo" in w)
    gap_ms = ((word_spans[bravo_idx][1] - word_spans[alpha_idx][2]) * 1000.0) / float(rbs.SAMPLE_RATE_HZ)
    assert gap_ms >= 1300.0, f"Expected real >=1.3s pause reflected in forced word timestamps, got {gap_ms:.1f}ms"


def test_whisper_segment_boundary_flush_and_strict_json_errors(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    fake_bin = tmp_path / "whisper-cli"
    fake_model = tmp_path / "model.bin"
    fake_bin.write_text("#!/bin/sh\n", encoding="utf-8")
    fake_model.write_bytes(b"GGML")

    # 1. Cross-segment word concatenation regression test (segment 2 token lacks leading space)
    def _fake_run_multiseg(cmd: list[str], **kwargs: Any) -> subprocess.CompletedProcess[str]:
        out_prefix = Path(cmd[cmd.index("-of") + 1])
        Path(f"{out_prefix}.txt").write_text("Hello world\n", encoding="utf-8")
        Path(f"{out_prefix}.json").write_text(
            json.dumps(
                {
                    "transcription": [
                        {"tokens": [{"text": " Hello", "offsets": {"from": 100, "to": 420}}]},
                        {"tokens": [{"text": "world", "offsets": {"from": 900, "to": 1250}}]},
                    ]
                }
            ),
            encoding="utf-8",
        )
        return subprocess.CompletedProcess(cmd, 0, "", "")

    monkeypatch.setattr(rbs.subprocess, "run", _fake_run_multiseg)
    _, spans = rbs.transcribe_pcm16k_whisper_with_words(
        np.zeros(16000, dtype=np.float32),
        whisper_bin=fake_bin,
        whisper_model=fake_model,
    )
    assert [w for w, _, _ in spans] == ["Hello", "world"]

    # 2. Malformed JSON must raise RuntimeError (never silently return empty word_spans)
    def _fake_run_bad_json(cmd: list[str], **kwargs: Any) -> subprocess.CompletedProcess[str]:
        out_prefix = Path(cmd[cmd.index("-of") + 1])
        Path(f"{out_prefix}.txt").write_text("Hello\n", encoding="utf-8")
        Path(f"{out_prefix}.json").write_text("{not valid json", encoding="utf-8")
        return subprocess.CompletedProcess(cmd, 0, "", "")

    monkeypatch.setattr(rbs.subprocess, "run", _fake_run_bad_json)
    with pytest.raises(RuntimeError, match="Malformed whisper-cli"):
        rbs.transcribe_pcm16k_whisper_with_words(
            np.zeros(16000, dtype=np.float32),
            whisper_bin=fake_bin,
            whisper_model=fake_model,
        )

    # 3. align_word_tokens(require_real_alignment=True) must reject empty whisper_word_spans (no char fallback)
    with pytest.raises(RuntimeError, match="Real forced word alignment required"):
        rbs.align_word_tokens(
            _make_speech_wave(1.0),
            "Never fall back to character ratios.",
            whisper_word_spans=[],
            require_real_alignment=True,
        )


# ---------------------------------------------------------------------------
# 3. Strict 4-Gate Enforcement & Warm Retries
# ---------------------------------------------------------------------------


def test_broadcast_studio_retries_warm_and_rejects_persistent_gate_failure(tmp_path: Path) -> None:
    calls_for_clause: dict[str, int] = {}

    def _synth_hook(clause: rbs.PlannedClause, n_takes: int) -> list[rbs.SynthesizedTakeData]:
        calls_for_clause[clause.clause_id] = calls_for_clause.get(clause.clause_id, 0) + 1
        return [
            rbs.SynthesizedTakeData(
                pcm44k=_make_speech_wave(1.6),
                discrete_audio_tokens=[151678 + k for k in range(10)],
                seed=100 + t,
                temperature=0.72,
            )
            for t in range(n_takes)
        ]

    # Case A: Initial attempt fails CER > 0, retry attempt passes -> succeeds!
    attempt_counter = {"n": 0}

    def _verify_retry_passes(pcm: np.ndarray, expected_text: str) -> dict[str, Any]:
        attempt_counter["n"] += 1
        cer_val = 0.25 if attempt_counter["n"] <= 2 else 0.0
        return {
            "recognized_text": expected_text,
            "cer": cer_val,
            "max_phoneme_duration_ms": 120.0,
            "wespeaker_cosine": 0.90,
            "dnsmos_p808": 4.25,
            "tail_rms_dbfs": sar.compute_tail_rms_dbfs(pcm),
            "syllables_per_sec": 5.0,
            "word_spans": [("Hello", 2000, 12000), ("world", 13000, 24000)],
        }

    ok_report = rbs.render_broadcast_studio(
        script_text="Hello world.",
        project_id="retry_pass",
        output_dir=tmp_path / "retry_pass",
        takes_per_clause=2,
        room_ir_path=None,
        room_tone_path=None,
        skip_video=True,
        require_strict_gates=True,
        max_retries_per_clause=1,
        require_real_alignment=True,
        schema_path=SCHEMA_PATH,
        synthesizer_fn=_synth_hook,
        verifier_fn=_verify_retry_passes,
    )
    assert calls_for_clause["c_001"] == 2
    assert ok_report["metrics"]["passed_all_strict_gates"] is True

    # Case B: All retries fail strict gates -> must raise RuntimeError!
    def _verify_always_fails(pcm: np.ndarray, expected_text: str) -> dict[str, Any]:
        return {
            "recognized_text": "garbled",
            "cer": 0.42,
            "max_phoneme_duration_ms": 150.0,
            "wespeaker_cosine": 0.75,
            "dnsmos_p808": 3.5,
            "tail_rms_dbfs": sar.compute_tail_rms_dbfs(pcm),
            "syllables_per_sec": 5.0,
            "word_spans": [("garbled", 2000, 12000)],
        }

    with pytest.raises(RuntimeError, match="failed strict 4-gate verification"):
        rbs.render_broadcast_studio(
            script_text="Hello world.",
            project_id="retry_fail",
            output_dir=tmp_path / "retry_fail",
            takes_per_clause=1,
            room_ir_path=None,
            room_tone_path=None,
            skip_video=True,
            require_strict_gates=True,
            max_retries_per_clause=1,
            require_real_alignment=True,
            schema_path=SCHEMA_PATH,
            synthesizer_fn=_synth_hook,
            verifier_fn=_verify_always_fails,
        )


def test_synthesize_and_render_strict_gate_retry_and_failure(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # Stub heavy model loading/ONNX calls in synthesize_and_render.py to verify retry + fail + parity wiring.
    ref_wav = tmp_path / "ref.wav"
    sf.write(str(ref_wav), _make_speech_wave(1.0), sar.SAMPLE_RATE_HZ)
    centroid_path = tmp_path / "master_centroid.bin"
    centroid_path.write_bytes(np.ones(256, dtype="<f4").tobytes())
    tflite_dir = tmp_path / "models" / "bundles" / "v002"
    tflite_dir.mkdir(parents=True)
    tflite_path = tflite_dir / "codec_decoder_fp16.tflite"
    tflite_path.write_bytes(b"TFL3")
    finetuned_dir = tmp_path / "models" / "dac-finetune-v002"
    finetuned_dir.mkdir(parents=True)
    finetuned_pth = finetuned_dir / "codec_finetuned_v002.pth"
    finetuned_pth.write_bytes(b"PTH")

    class _FakeWeSpeaker:
        def __init__(self, *_a: Any, **_k: Any) -> None:
            pass

        def extract_embedding(self, _pcm: np.ndarray) -> np.ndarray:
            return np.ones(256, dtype=np.float32)

    class _FakeDnsmos:
        def __init__(self, *_a: Any, **_k: Any) -> None:
            pass

        def score_p808(self, _pcm: np.ndarray) -> float:
            return 4.3

        def score_sig_bak_ovr(self, _pcm: np.ndarray) -> dict[str, float]:
            return {"sig": 4.2, "bak": 4.4, "ovr": 4.1}

    parity_calls: list[Path | None] = []
    gen_seeds: list[int] = []

    monkeypatch.setattr(sar.eva, "WeSpeakerEngine", _FakeWeSpeaker)
    monkeypatch.setattr(sar.eva, "DnsmosP808Engine", _FakeDnsmos)
    monkeypatch.setattr(sar.eva, "resample_44k_to_16k_android_exact", lambda x, *_a: np.zeros(16000, dtype=np.float32))
    monkeypatch.setattr(sar.s2_pro_infer, "encode_reference_audio_to_codes", lambda *_a, **_k: (np.zeros((10, 8), dtype=np.int32), 1.0))
    monkeypatch.setattr(sar.s2_pro_infer, "load_s2_pro_dual_ar", lambda *_a, **_k: (object(), object(), None))
    monkeypatch.setattr(sar.s2_pro_infer, "build_s2_pro_prompt_tensor", lambda *_a, **_k: np.zeros((11, 8)))

    def _fake_gen(_m: Any, _p: Any, *, seed: int, **_k: Any) -> sar.s2_pro_infer.GenerationResult:
        gen_seeds.append(seed)
        return sar.s2_pro_infer.GenerationResult(
            codes=np.zeros((10, 16), dtype=np.int32),
            seed=seed,
            temperature=0.72,
            top_p=0.8,
            top_k=30,
            prompt_tokens=8,
            generated_frames=16,
            hit_eos=True,
            elapsed_sec=0.1,
            frames_per_sec=160.0,
            residual_clamped_count=0,
        )

    monkeypatch.setattr(sar.s2_pro_infer, "generate_dual_ar_codes", _fake_gen)
    monkeypatch.setattr(
        sar.s2_pro_infer,
        "decode_codes_batch_tflite",
        lambda codes_list, **_k: [(_make_speech_wave(1.5), {"decoder": "fake"}) for _ in codes_list],
    )

    def _fake_pt_decode(_codes: Any, *, codec_pth: Path | None = None, tflite_path: Path | None = None, **_k: Any) -> np.ndarray:
        parity_calls.append(sar.s2_pro_infer.resolve_pytorch_dac_checkpoint(codec_pth, tflite_path=tflite_path))
        return _make_speech_wave(1.5)

    monkeypatch.setattr(sar.s2_pro_infer, "decode_codes_pytorch_dac", _fake_pt_decode)

    # Always transcribe wrong text so CER > 0 even after retry
    monkeypatch.setattr(sar, "transcribe_pcm16k_whisper", lambda *_a, **_k: "completely wrong words")

    report_json = tmp_path / "fail_report.json"
    with pytest.raises(RuntimeError, match="Strict 4-gate verification failed"):
        sar.run_end_to_end_pipeline(
            target_text="Hello world on Pixel.",
            ref_wav=ref_wav,
            ref_text="Reference.",
            extra_ref_wav=None,
            seeds=(101,),
            lora_dir=None,
            codec_tflite=tflite_path,
            master_centroid_path=centroid_path,
            room_ir_path=tmp_path / "missing_ir.pcm",
            room_tone_path=tmp_path / "missing_tone.pcm",
            wespeaker_onnx=tmp_path / "w.onnx",
            dnsmos_onnx=tmp_path / "d.onnx",
            output_wav=tmp_path / "out.wav",
            report_json=report_json,
            device="cpu",
            skip_video=True,
            require_strict_gates=True,
            max_gate_retries=1,
        )

    # Retried with seed + 1000 while model stayed loaded, wrote diagnostic report, and resolved v002 parity pth!
    assert gen_seeds == [101, 1101]
    assert report_json.is_file()
    rep = json.loads(report_json.read_text(encoding="utf-8"))
    assert rep["strict_gates_passed"] is False
    assert rep["sampling"]["attempts_run"] == 2
    assert parity_calls and parity_calls[0] == finetuned_pth


# ---------------------------------------------------------------------------
# 4. `s2_pro_infer.py` v002 DAC Parity Resolution & Caching
# ---------------------------------------------------------------------------


def test_resolve_pytorch_dac_checkpoint_v002_parity_and_overrides(tmp_path: Path) -> None:
    models_root = tmp_path / "voicestudio-models"
    v2_bundle = models_root / "bundles" / "v002"
    v2_bundle.mkdir(parents=True)
    v2_tflite = v2_bundle / "codec_decoder_fp16.tflite"
    v2_tflite.write_bytes(b"TFL3")
    v2_pth = models_root / "dac-finetune-v002" / "codec_finetuned_v002.pth"
    v2_pth.parent.mkdir(parents=True)
    v2_pth.write_bytes(b"FINETUNED_V002")

    v1_tflite = models_root / "bundles" / "v001" / "codec_decoder_fp16.tflite"
    v1_tflite.parent.mkdir(parents=True)
    v1_tflite.write_bytes(b"TFL3_V1")

    # v002 tflite + default/None codec_pth -> resolves to codec_finetuned_v002.pth
    assert s2_pro_infer.resolve_pytorch_dac_checkpoint(None, tflite_path=v2_tflite) == v2_pth
    assert (
        s2_pro_infer.resolve_pytorch_dac_checkpoint(s2_pro_infer.DEFAULT_CODEC_PTH, tflite_path=v2_tflite)
        == v2_pth
    )

    # Explicit custom codec_pth always wins
    custom_pth = tmp_path / "custom_codec.pth"
    custom_pth.write_bytes(b"CUSTOM")
    assert s2_pro_infer.resolve_pytorch_dac_checkpoint(custom_pth, tflite_path=v2_tflite) == custom_pth

    # v001 tflite + DEFAULT_CODEC_PTH preserves DEFAULT_CODEC_PTH
    assert (
        s2_pro_infer.resolve_pytorch_dac_checkpoint(s2_pro_infer.DEFAULT_CODEC_PTH, tflite_path=v1_tflite)
        == s2_pro_infer.DEFAULT_CODEC_PTH
    )

    # Real on-disk v002 artifacts check if present on host
    real_v2_tflite = Path("/Users/mac/Downloads/voicestudio-models/bundles/v002/codec_decoder_fp16.tflite")
    if real_v2_tflite.is_file() and s2_pro_infer.DEFAULT_FINETUNED_CODEC_PTH.is_file():
        assert (
            s2_pro_infer.resolve_pytorch_dac_checkpoint(
                s2_pro_infer.DEFAULT_CODEC_PTH, tflite_path=real_v2_tflite
            )
            == s2_pro_infer.DEFAULT_FINETUNED_CODEC_PTH
        )


def test_warm_s2_pro_engine_caches_and_reloads_on_lora_change(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    load_calls: list[Path | None] = []

    def _fake_load(hf_dir: Path, *, lora_dir: Path | None = None, **_k: Any) -> tuple[Any, Any, None]:
        load_calls.append(lora_dir)
        return object(), object(), None

    monkeypatch.setattr(s2_pro_infer, "load_s2_pro_dual_ar", _fake_load)
    eng = s2_pro_infer.WarmS2ProEngine()
    hf = tmp_path / "hf"
    hf.mkdir()
    l1 = tmp_path / "lora_v1"
    l2 = tmp_path / "lora_v2"
    l1.mkdir()
    l2.mkdir()

    m1, _, _ = eng.get_or_load(hf, lora_dir=l1, device="cpu")
    m1_again, _, _ = eng.get_or_load(hf, lora_dir=l1, device="cpu")
    assert m1 is m1_again
    assert len(load_calls) == 1

    m2, _, _ = eng.get_or_load(hf, lora_dir=l2, device="cpu")
    assert m2 is not m1
    assert len(load_calls) == 2


def test_resolve_latest_synthesis_assets_includes_lexicon_onnx_and_v002_codec_pth(tmp_path: Path) -> None:
    models_root = tmp_path / "models"
    bundles_root = models_root / "bundles"
    _write_minimal_bundle(bundles_root / "v001", lora_version=1)
    _write_minimal_bundle(bundles_root / "v002", lora_version=2)
    v2_pth = models_root / "dac-finetune-v002" / "codec_finetuned_v002.pth"
    v2_pth.parent.mkdir(parents=True)
    v2_pth.write_bytes(b"DAC_V2")

    assets = ota_server.resolve_latest_synthesis_assets(models_root, bundles_root)
    assert assets["codec_tflite"] == (bundles_root / "v002" / "codec_decoder_fp16.tflite").resolve()
    assert assets["lexicon"] == (bundles_root / "v002" / "lexicon.json").resolve()
    assert assets["wespeaker_onnx"] == (bundles_root / "v002" / "wespeaker_resnet34.onnx").resolve()
    assert assets["dnsmos_onnx"] == (bundles_root / "v002" / "dnsmos_p808.onnx").resolve()
    assert assets["codec_pth"] == v2_pth.resolve()


# ---------------------------------------------------------------------------
# 5. Ephemeral `port=0` Server Integration: /v1/core/tts & /v1/render-video
# ---------------------------------------------------------------------------


_ORIG_SUBPROCESS_RUN = subprocess.run
_VALID_MP4_CACHE: dict[float, bytes] = {}


def _write_fake_mp4(path: Path, duration_sec: float = 2.5) -> None:
    cached = _VALID_MP4_CACHE.get(duration_sec)
    if cached is None:
        import tempfile

        ffmpeg_bin = shutil.which("ffmpeg")
        if ffmpeg_bin:
            with tempfile.TemporaryDirectory(prefix="m22_mp4_") as td:
                tmp_mp4 = Path(td) / "sample.mp4"
                _ORIG_SUBPROCESS_RUN(
                    [
                        ffmpeg_bin,
                        "-y",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-f",
                        "lavfi",
                        "-i",
                        f"color=c=black:s=720x1280:r=30:d={duration_sec:.3f}",
                        "-f",
                        "lavfi",
                        "-i",
                        f"sine=frequency=440:sample_rate=44100:duration={duration_sec:.3f}",
                        "-c:v",
                        "libx264",
                        "-preset",
                        "ultrafast",
                        "-pix_fmt",
                        "yuv420p",
                        "-c:a",
                        "aac",
                        "-ar",
                        "44100",
                        "-ac",
                        "1",
                        "-t",
                        f"{duration_sec:.3f}",
                        str(tmp_mp4),
                    ],
                    check=True,
                    timeout=30,
                )
                cached = tmp_mp4.read_bytes()
        else:
            cached = b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isomiso2mp41" + b"\x00" * 512
        _VALID_MP4_CACHE[duration_sec] = cached
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(cached)


def test_http_core_tts_sync_async_align_url_and_gate_failures(tmp_path: Path) -> None:
    bundles_root = tmp_path / "bundles"
    _write_minimal_bundle(bundles_root / "v002", lora_version=2)
    prefs_file = tmp_path / "preferences.jsonl"
    renders_dir = tmp_path / "renders"
    renders_dir.mkdir(parents=True)
    avatar = tmp_path / "avatar.jpg"
    avatar.write_bytes(b"\xff\xd8\xff\xe0AVATAR\xff\xd9")

    gate_mode = {"fail": False}

    def _studio_runner(
        *,
        project_id: str,
        spoken_text: str,
        caller_clauses: list[dict[str, str]] | None,
        output_dir: Path,
        assets: dict[str, Path | None],
    ) -> dict[str, Any]:
        del assets
        output_dir.mkdir(parents=True, exist_ok=True)
        wav_path = output_dir / f"{project_id}_master.wav"
        sf.write(str(wav_path), _make_speech_wave(3.4), 44100, subtype="PCM_16")
        if gate_mode["fail"]:
            return {
                "master_wav_path": str(wav_path),
                "duration_ms": 3400,
                "words": [{"word": "bad", "start_ms": 100, "end_ms": 400}],
                "timed_clauses": [{"id": "c0", "index": 0, "spoken_text": spoken_text, "start_ms": 0, "end_ms": 3400}],
                "metrics": {"cer": 0.25, "passed_all_strict_gates": False},
            }
        # Deliberately non-proportional timings (clause 0 gets 900ms, clause 1 gets 2500ms)
        clauses_out = []
        if caller_clauses and len(caller_clauses) == 2:
            clauses_out = [
                {"id": caller_clauses[0]["id"], "index": 0, "spoken_text": caller_clauses[0]["spoken_text"], "start_ms": 0, "end_ms": 900},
                {"id": caller_clauses[1]["id"], "index": 1, "spoken_text": caller_clauses[1]["spoken_text"], "start_ms": 900, "end_ms": 3400},
            ]
        else:
            clauses_out = [{"id": "c_001", "index": 0, "spoken_text": spoken_text, "start_ms": 0, "end_ms": 3400}]
        words_out = [
            {"word": "Opening", "start_ms": 80, "end_ms": 380},
            {"word": "line", "start_ms": 420, "end_ms": 860},
            {"word": "Second", "start_ms": 1350, "end_ms": 1950},
            {"word": "beat", "start_ms": 2050, "end_ms": 3100},
        ]
        return {
            "master_wav_path": str(wav_path),
            "duration_ms": 3400,
            "words": words_out,
            "timed_clauses": clauses_out,
            "metrics": {
                "cer": 0.0,
                "wespeaker_cosine": 0.91,
                "dnsmos_p808": 4.28,
                "tail_rms_dbfs": -56.0,
                "mastered_lufs": -16.0,
                "syllables_per_sec": 5.0,
                "max_phoneme_duration_ms": 140.0,
                "passed_all_strict_gates": True,
            },
        }

    server = ota_server.create_ota_server(
        host="127.0.0.1",
        port=0,
        bundles_root=bundles_root,
        preferences_path=prefs_file,
        renders_dir=renders_dir,
        models_root=tmp_path,
        default_avatar_path=avatar,
        tts_studio_runner=_studio_runner,
        quiet=True,
    )
    host, port = server.server_address[:2]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        # 1. Sync POST /v1/core/tts returns words, non-proportional clauses, metrics, and camelCase align_url
        req_body = json.dumps(
            {
                "project_id": "m22_sync",
                "clauses": [
                    {"id": "c_open", "spoken_text": "Opening line."},
                    {"id": "c_next", "spoken_text": "Second beat."},
                ],
            }
        ).encode("utf-8")
        req = urllib.request.Request(
            f"http://{host}:{port}/v1/core/tts",
            data=req_body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            assert resp.status == 200
            tts_res = json.loads(resp.read().decode("utf-8"))

        assert tts_res["clauses"][0]["end_ms"] == 900
        assert tts_res["clauses"][1]["start_ms"] == 900
        assert len(tts_res["words"]) == 4
        assert tts_res["words"][0]["start_ms"] == 80
        assert tts_res["metrics"]["passed_all_strict_gates"] is True
        assert tts_res["metrics"]["cer"] == 0.0
        assert tts_res["align_url"].startswith("/v1/renders/align_tts_")

        # Fetch align_url and validate strict Remotion + Android compatible schema
        with urllib.request.urlopen(f"http://{host}:{port}{tts_res['align_url']}", timeout=10) as a_resp:
            assert a_resp.status == 200
            assert "application/json" in a_resp.headers.get("Content-Type", "")
            align_json = json.loads(a_resp.read().decode("utf-8"))

        assert isinstance(align_json, dict)
        assert align_json["ttsId"] == tts_res["tts_id"]
        assert align_json["durationMs"] == tts_res["duration_ms"]
        assert align_json["words"][0] == {"word": "Opening", "startMs": 80, "endMs": 380}
        assert align_json["clauses"][0]["startMs"] == 0
        assert align_json["clauses"][0]["endMs"] == 900

        # 2. Async POST /v1/core/tts?async=1
        req_async = urllib.request.Request(
            f"http://{host}:{port}/v1/core/tts?async=1",
            data=req_body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req_async, timeout=10) as resp:
            assert resp.status == 202
            task_id = json.loads(resp.read().decode("utf-8"))["job_id"]

        deadline = time.monotonic() + 5.0
        job_snap: dict[str, Any] = {}
        while time.monotonic() < deadline:
            with urllib.request.urlopen(f"http://{host}:{port}/v1/jobs/{task_id}", timeout=5) as j_resp:
                job_snap = json.loads(j_resp.read().decode("utf-8"))
            if job_snap.get("status") != "RUNNING":
                break
            time.sleep(0.02)
        assert job_snap["status"] == "READY"
        assert job_snap["result"]["align_url"].endswith(".json")
        assert len(job_snap["result"]["words"]) == 4

        # 3. Gate failure on /v1/core/tts returns HTTP 502 (sync) and FAILED (async)
        gate_mode["fail"] = True
        req_fail = urllib.request.Request(
            f"http://{host}:{port}/v1/core/tts",
            data=req_body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with pytest.raises(urllib.error.HTTPError) as exc_info:
            urllib.request.urlopen(req_fail, timeout=10)
        assert exc_info.value.code == 502

        # 4. Gate failure on /v1/render-video transitions job to FAILED before LIPSYNC with zero MP4
        req_vid = urllib.request.Request(
            f"http://{host}:{port}/v1/render-video",
            data=json.dumps(
                {
                    "script_id": "proj_gate_fail",
                    "title": "Gate Fail",
                    "spoken_text": "Opening line. Second beat.",
                    "voice_source": "ENROLLED_VOICE_CLONE",
                }
            ).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req_vid, timeout=10) as v_resp:
            assert v_resp.status == 202
            vid_job_id = json.loads(v_resp.read().decode("utf-8"))["job_id"]

        deadline = time.monotonic() + 5.0
        vid_snap: dict[str, Any] = {}
        while time.monotonic() < deadline:
            with urllib.request.urlopen(f"http://{host}:{port}/v1/jobs/{vid_job_id}", timeout=5) as j_resp:
                vid_snap = json.loads(j_resp.read().decode("utf-8"))
            if vid_snap.get("status") in ("READY", "FAILED"):
                break
            time.sleep(0.02)
        assert vid_snap["status"] == "FAILED"
        assert "strict 4-gate" in vid_snap["error_message"]
        assert vid_snap["video_url"] is None
        assert not (renders_dir / f"{vid_job_id}.mp4").exists()
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5.0)


def test_http_render_video_default_runner_surfaces_align_url_and_words(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    bundles_root = tmp_path / "bundles"
    _write_minimal_bundle(bundles_root / "v002", lora_version=2)
    prefs_file = tmp_path / "preferences.jsonl"
    renders_dir = tmp_path / "renders"
    renders_dir.mkdir(parents=True)
    avatar = tmp_path / "avatar.jpg"
    avatar.write_bytes(b"\xff\xd8\xff\xe0AVATAR\xff\xd9")

    def _studio_runner(
        *,
        project_id: str,
        spoken_text: str,
        caller_clauses: Any,
        output_dir: Path,
        assets: dict[str, Path | None],
    ) -> dict[str, Any]:
        del caller_clauses, assets
        output_dir.mkdir(parents=True, exist_ok=True)
        wav_path = output_dir / f"{project_id}_master.wav"
        sf.write(str(wav_path), _make_speech_wave(2.5), 44100, subtype="PCM_16")
        return {
            "master_wav_path": str(wav_path),
            "duration_ms": 2500,
            "words": [
                {"word": "Breaking", "start_ms": 50, "end_ms": 480},
                {"word": "story", "start_ms": 520, "end_ms": 1100},
            ],
            "timed_clauses": [
                {"id": "c_001", "index": 0, "spoken_text": spoken_text, "start_ms": 0, "end_ms": 2500}
            ],
            "metrics": {
                "cer": 0.0,
                "wespeaker_cosine": 0.92,
                "dnsmos_p808": 4.35,
                "tail_rms_dbfs": -55.0,
                "mastered_lufs": -16.0,
                "syllables_per_sec": 4.8,
                "max_phoneme_duration_ms": 130.0,
                "passed_all_strict_gates": True,
            },
        }

    # Stub only generate_talking_head.py & _burn_breaking_news_chyron subprocesses for fast CPU test
    real_run = ota_server.subprocess.run

    def _fake_subprocess_run(cmd: list[str], *args: Any, **kwargs: Any) -> Any:
        cmd_str = " ".join(str(x) for x in cmd)
        if "generate_talking_head.py" in cmd_str:
            out_idx = cmd.index("--output") + 1
            out_mp4 = Path(cmd[out_idx])
            _write_fake_mp4(out_mp4)
            return subprocess.CompletedProcess(
                cmd, 0, json.dumps({"output_mp4": str(out_mp4), "duration_sec": 2.5}), ""
            )
        if cmd and Path(cmd[0]).name == "ffmpeg":
            # Remux/chyron stub writes target MP4 cleanly if asked during test
            out_mp4 = Path(cmd[-1])
            _write_fake_mp4(out_mp4)
            return subprocess.CompletedProcess(cmd, 0, "", "")
        return real_run(cmd, *args, **kwargs)

    monkeypatch.setattr(ota_server.subprocess, "run", _fake_subprocess_run)

    server = ota_server.create_ota_server(
        host="127.0.0.1",
        port=0,
        bundles_root=bundles_root,
        preferences_path=prefs_file,
        renders_dir=renders_dir,
        models_root=tmp_path,
        default_avatar_path=avatar,
        tts_studio_runner=_studio_runner,
        quiet=True,
    )
    host, port = server.server_address[:2]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        req = urllib.request.Request(
            f"http://{host}:{port}/v1/render-video",
            data=json.dumps(
                {
                    "script_id": "proj_ok",
                    "title": "Breaking Story",
                    "spoken_text": "Breaking story.",
                    "voice_source": "ENROLLED_VOICE_CLONE",
                    "show_brand_bug": False,
                }
            ).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            assert resp.status == 202
            job_id = json.loads(resp.read().decode("utf-8"))["job_id"]

        deadline = time.monotonic() + 5.0
        snap: dict[str, Any] = {}
        while time.monotonic() < deadline:
            with urllib.request.urlopen(f"http://{host}:{port}/v1/jobs/{job_id}", timeout=5) as j_resp:
                snap = json.loads(j_resp.read().decode("utf-8"))
            if snap.get("status") in ("READY", "FAILED"):
                break
            time.sleep(0.02)
        assert snap["status"] == "READY", snap
        assert snap["video_url"] == f"/v1/renders/{job_id}.mp4"
        assert snap["align_url"] == f"/v1/renders/align_{job_id}.json"
        assert snap["words"] == [
            {"word": "Breaking", "start_ms": 50, "end_ms": 480},
            {"word": "story", "start_ms": 520, "end_ms": 1100},
        ]
        assert snap["metrics"]["passed_all_strict_gates"] is True

        with urllib.request.urlopen(f"http://{host}:{port}{snap['align_url']}", timeout=5) as a_resp:
            assert a_resp.status == 200
            align_obj = json.loads(a_resp.read().decode("utf-8"))
        assert align_obj["words"][0]["startMs"] == 50
        assert align_obj["words"][1]["endMs"] == 1100
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5.0)


# ---------------------------------------------------------------------------
# 6. Iteration 2 Remediation Regression Tests
# ---------------------------------------------------------------------------


def test_rejected_core_tts_never_leaks_wav_or_align_and_thumb_timeout_handled(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    bundles_root = tmp_path / "bundles"
    _write_minimal_bundle(bundles_root / "v002", lora_version=2)

    # Case A: Production `tts_studio_runner` path rejects failing metrics/empty words without leaking to renders_dir
    renders_a = tmp_path / "renders_a"
    renders_a.mkdir(parents=True)
    prefs_a = tmp_path / "prefs_a.jsonl"

    seen_tts_ids: list[str] = []

    def _failing_studio_runner(
        *,
        project_id: str,
        spoken_text: str,
        caller_clauses: Any,
        output_dir: Path,
        assets: dict[str, Path | None],
    ) -> dict[str, Any]:
        del caller_clauses, assets
        seen_tts_ids.append(output_dir.parent.name)
        output_dir.mkdir(parents=True, exist_ok=True)
        wav_path = output_dir / f"{project_id}_master.wav"
        sf.write(str(wav_path), _make_speech_wave(2.0), 44100, subtype="PCM_16")
        return {
            "master_wav_path": str(wav_path),
            "duration_ms": 2000,
            "words": [{"word": "bad", "start_ms": 50, "end_ms": 300}],
            "timed_clauses": [{"id": "c0", "index": 0, "spoken_text": spoken_text, "start_ms": 0, "end_ms": 2000}],
            "metrics": {"cer": 0.25, "passed_all_strict_gates": False},
        }

    srv_a = ota_server.create_ota_server(
        host="127.0.0.1",
        port=0,
        bundles_root=bundles_root,
        preferences_path=prefs_a,
        renders_dir=renders_a,
        models_root=tmp_path,
        tts_studio_runner=_failing_studio_runner,
        quiet=True,
    )
    host_a, port_a = srv_a.server_address[:2]
    t_a = threading.Thread(target=srv_a.serve_forever, daemon=True)
    t_a.start()
    try:
        req = urllib.request.Request(
            f"http://{host_a}:{port_a}/v1/core/tts",
            data=json.dumps({"spoken_text": "Should fail gate."}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with pytest.raises(urllib.error.HTTPError) as exc_info:
            urllib.request.urlopen(req, timeout=10)
        assert exc_info.value.code == 502
        assert list(renders_a.glob("tts_*.wav")) == []
        assert list(renders_a.glob("align_*.json")) == []
        failed_tts_id = seen_tts_ids[-1]
        with pytest.raises(urllib.error.HTTPError) as wav_404:
            urllib.request.urlopen(f"http://{host_a}:{port_a}/v1/renders/{failed_tts_id}.wav", timeout=5)
        assert wav_404.value.code == 404

        # Also test async ?async=1 leaves zero leaked WAVs or align JSONs
        req_async = urllib.request.Request(
            f"http://{host_a}:{port_a}/v1/core/tts?async=1",
            data=json.dumps({"spoken_text": "Should fail async gate."}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req_async, timeout=10) as a_resp:
            assert a_resp.status == 202
            job_id = json.loads(a_resp.read().decode("utf-8"))["job_id"]
        deadline = time.monotonic() + 5.0
        snap: dict[str, Any] = {}
        while time.monotonic() < deadline:
            with urllib.request.urlopen(f"http://{host_a}:{port_a}/v1/jobs/{job_id}", timeout=5) as j_resp:
                snap = json.loads(j_resp.read().decode("utf-8"))
            if snap.get("status") in ("READY", "FAILED"):
                break
            time.sleep(0.02)
        assert snap["status"] == "FAILED"
        assert list(renders_a.glob("tts_*.wav")) == []
        assert list(renders_a.glob("align_*.json")) == []

        # Test get_or_create_render_thumb catches TimeoutExpired cleanly and cleans up tmp_jpg
        bad_mp4 = renders_a / "corrupt.mp4"
        bad_mp4.write_bytes(b"BAD_MP4")

        def _timeout_run(*_a: Any, **_k: Any) -> Any:
            raise subprocess.TimeoutExpired(cmd=["ffmpeg"], timeout=30)

        monkeypatch.setattr(ota_server.subprocess, "run", _timeout_run)
        assert srv_a.render_manager.get_or_create_render_thumb("corrupt.jpg") is None
        thumbs_dir = renders_a / "thumbs"
        assert not any(thumbs_dir.glob("*.tmp.*")) if thumbs_dir.exists() else True
    finally:
        srv_a.shutdown()
        srv_a.server_close()
        t_a.join(timeout=5.0)

    # Case B: `_tts_runner` test-hook branch stages in work_dir and never leaks failing WAV to renders_dir
    renders_b = tmp_path / "renders_b"
    renders_b.mkdir(parents=True)
    prefs_b = tmp_path / "prefs_b.jsonl"

    def _failing_tts_runner(_text: str, staged_wav: Path) -> dict[str, Any]:
        sf.write(str(staged_wav), _make_speech_wave(1.5), 44100, subtype="PCM_16")
        return {"cer": 0.4, "passed_all_strict_gates": False}

    srv_b = ota_server.create_ota_server(
        host="127.0.0.1",
        port=0,
        bundles_root=bundles_root,
        preferences_path=prefs_b,
        renders_dir=renders_b,
        models_root=tmp_path,
        tts_runner=_failing_tts_runner,
        quiet=True,
    )
    host_b, port_b = srv_b.server_address[:2]
    t_b = threading.Thread(target=srv_b.serve_forever, daemon=True)
    t_b.start()
    try:
        req_b = urllib.request.Request(
            f"http://{host_b}:{port_b}/v1/core/tts",
            data=json.dumps({"spoken_text": "Hook failure check."}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with pytest.raises(urllib.error.HTTPError) as exc_b:
            urllib.request.urlopen(req_b, timeout=10)
        assert exc_b.value.code == 502
        assert list(renders_b.glob("tts_*.wav")) == []
        assert list(renders_b.glob("align_*.json")) == []
    finally:
        srv_b.shutdown()
        srv_b.server_close()
        t_b.join(timeout=5.0)


def test_v002_bundle_ancestor_dir_and_custom_models_root_isolation(tmp_path: Path) -> None:
    # 1. Ancestor directory with "v2" / "dev2" must NOT misclassify a v001 bundle as v002
    v1_under_dev2 = tmp_path / "dev2" / "bundles" / "v001" / "codec_decoder_fp16.tflite"
    v1_under_dev2.parent.mkdir(parents=True)
    v1_under_dev2.write_bytes(b"TFL3_V1")

    is_v2, matched = s2_pro_infer._is_v002_tflite_bundle(v1_under_dev2)
    assert is_v2 is False
    assert matched is None
    assert (
        s2_pro_infer.resolve_pytorch_dac_checkpoint(
            s2_pro_infer.DEFAULT_CODEC_PTH, tflite_path=v1_under_dev2
        )
        == s2_pro_infer.DEFAULT_CODEC_PTH
    )

    # 2. Custom models_root with v002 bundle lacking dac-finetune-v002 must NOT leak to host DEFAULT_FINETUNED_CODEC_PTH
    isolated_root = tmp_path / "isolated_models"
    iso_tflite = isolated_root / "bundles" / "v002" / "codec_decoder_fp16.tflite"
    iso_tflite.parent.mkdir(parents=True)
    iso_tflite.write_bytes(b"TFL3_V2")

    # Without s2-pro/codec.pth in isolated_root -> returns DEFAULT_CODEC_PTH (never DEFAULT_FINETUNED_CODEC_PTH)
    resolved_no_base = s2_pro_infer.resolve_pytorch_dac_checkpoint(None, tflite_path=iso_tflite)
    assert resolved_no_base == s2_pro_infer.DEFAULT_CODEC_PTH
    assert resolved_no_base != s2_pro_infer.DEFAULT_FINETUNED_CODEC_PTH

    # With isolated_root/s2-pro/codec.pth -> prefers isolated base codec over global paths
    iso_base_codec = isolated_root / "s2-pro" / "codec.pth"
    iso_base_codec.parent.mkdir(parents=True)
    iso_base_codec.write_bytes(b"ISO_BASE")
    assert s2_pro_infer.resolve_pytorch_dac_checkpoint(None, tflite_path=iso_tflite) == iso_base_codec


def test_whisper_word_normalization_preserves_numbers_currency_percent_and_apostrophes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    fake_bin = tmp_path / "whisper-cli"
    fake_model = tmp_path / "model.bin"
    fake_bin.write_text("#!/bin/sh\n", encoding="utf-8")
    fake_model.write_bytes(b"GGML")

    def _fake_run_punct(cmd: list[str], **kwargs: Any) -> subprocess.CompletedProcess[str]:
        del kwargs
        out_prefix = Path(cmd[cmd.index("-of") + 1])
        Path(f"{out_prefix}.txt").write_text(
            "44.1 $1,250.50 99.9% Aircheck’s confirmed. ...\n", encoding="utf-8"
        )
        Path(f"{out_prefix}.json").write_text(
            json.dumps(
                {
                    "transcription": [
                        {
                            "tokens": [
                                {"text": " 44", "offsets": {"from": 100, "to": 250}},
                                {"text": ".1", "offsets": {"from": 250, "to": 420}},
                                {"text": " $", "offsets": {"from": 450, "to": 500}},
                                {"text": "1", "offsets": {"from": 500, "to": 580}},
                                {"text": ",", "offsets": {"from": 580, "to": 600}},
                                {"text": "250", "offsets": {"from": 600, "to": 780}},
                                {"text": ".", "offsets": {"from": 780, "to": 800}},
                                {"text": "50", "offsets": {"from": 800, "to": 940}},
                                {"text": " 99", "offsets": {"from": 980, "to": 1100}},
                                {"text": ".9", "offsets": {"from": 1100, "to": 1220}},
                                {"text": "%", "offsets": {"from": 1220, "to": 1300}},
                                {"text": " Aircheck", "offsets": {"from": 1350, "to": 1600}},
                                {"text": "\u2019s", "offsets": {"from": 1600, "to": 1720}},
                                {"text": " confirmed", "offsets": {"from": 1750, "to": 2050}},
                                {"text": ".", "offsets": {"from": 2050, "to": 2100}},
                                {"text": " ...", "offsets": {"from": 2150, "to": 2250}},
                                {"text": " \u2014", "offsets": {"from": 2260, "to": 2300}},
                            ]
                        }
                    ]
                }
            ),
            encoding="utf-8",
        )
        return subprocess.CompletedProcess(cmd, 0, "", "")

    monkeypatch.setattr(rbs.subprocess, "run", _fake_run_punct)
    _, spans = rbs.transcribe_pcm16k_whisper_with_words(
        np.zeros(16000, dtype=np.float32),
        whisper_bin=fake_bin,
        whisper_model=fake_model,
    )
    assert [w for w, _, _ in spans] == ["44.1", "$1,250.50", "99.9%", "Aircheck's", "confirmed"]


def test_punctuation_only_clauses_filtered_and_all_punct_returns_400(tmp_path: Path) -> None:
    # 1. Mixed punctuation-only + real clauses drop "..." cleanly in planner & splitter
    planned, parent_groups = rbs.plan_caller_clauses(
        [
            {"id": "c_punct", "spoken_text": "..."},
            {"id": "c_dash", "spoken_text": "\u2014 !?"},
            {"id": "c_real", "spoken_text": "Alpha signal confirmed."},
        ]
    )
    assert len(planned) == 1
    assert planned[0].parent_clause_id == "c_real"
    assert [g["id"] for g in parent_groups] == ["c_real"]
    assert rbs.split_script_into_clauses("... \u2014 !?") == []

    # 2. HTTP /v1/core/tts filters punctuation-only clauses alongside real ones (no 502) and rejects all-punct with 400
    bundles_root = tmp_path / "bundles"
    _write_minimal_bundle(bundles_root / "v002", lora_version=2)
    renders_dir = tmp_path / "renders"
    renders_dir.mkdir(parents=True)
    prefs_file = tmp_path / "prefs.jsonl"
    seen_clauses: list[list[dict[str, str]] | None] = []

    def _studio_runner(
        *,
        project_id: str,
        spoken_text: str,
        caller_clauses: list[dict[str, str]] | None,
        output_dir: Path,
        assets: dict[str, Path | None],
    ) -> dict[str, Any]:
        del assets
        seen_clauses.append(caller_clauses)
        output_dir.mkdir(parents=True, exist_ok=True)
        wav_path = output_dir / f"{project_id}_master.wav"
        sf.write(str(wav_path), _make_speech_wave(2.0), 44100, subtype="PCM_16")
        return {
            "master_wav_path": str(wav_path),
            "duration_ms": 2000,
            "words": [{"word": "Alpha", "start_ms": 50, "end_ms": 400}],
            "timed_clauses": [
                {"id": "c_real", "index": 0, "spoken_text": spoken_text, "start_ms": 0, "end_ms": 2000}
            ],
            "metrics": {"cer": 0.0, "passed_all_strict_gates": True},
        }

    srv = ota_server.create_ota_server(
        host="127.0.0.1",
        port=0,
        bundles_root=bundles_root,
        preferences_path=prefs_file,
        renders_dir=renders_dir,
        models_root=tmp_path,
        tts_studio_runner=_studio_runner,
        quiet=True,
    )
    host, port = srv.server_address[:2]
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    try:
        req_mixed = urllib.request.Request(
            f"http://{host}:{port}/v1/core/tts",
            data=json.dumps(
                {
                    "clauses": [
                        {"id": "c_punct", "spoken_text": "..."},
                        {"id": "c_real", "spoken_text": "Alpha."},
                    ]
                }
            ).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req_mixed, timeout=10) as resp:
            assert resp.status == 200
        assert seen_clauses == [[{"id": "c_real", "spoken_text": "Alpha."}]]

        for bad_payload in (
            {"clauses": [{"id": "c_only_punct", "spoken_text": "..."}]},
            {"spoken_text": "... \u2014 !?"},
        ):
            req_bad = urllib.request.Request(
                f"http://{host}:{port}/v1/core/tts",
                data=json.dumps(bad_payload).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with pytest.raises(urllib.error.HTTPError) as exc_bad:
                urllib.request.urlopen(req_bad, timeout=10)
            assert exc_bad.value.code == 400
    finally:
        srv.shutdown()
        srv.server_close()
        t.join(timeout=5.0)


def test_split_into_sentences_handles_quoted_boundaries_and_abbreviations() -> None:
    assert rbs._split_into_sentences('She said "Wait." Then she left.') == [
        'She said "Wait."',
        "Then she left.",
    ]
    assert rbs._split_into_sentences('He asked \u201cReally?\u201d We nodded.') == [
        "He asked \u201cReally?\u201d",
        "We nodded.",
    ]
    assert rbs._split_into_sentences("Dr. J.R. Smith spoke in the U.S. today.") == [
        "Dr. J.R. Smith spoke in the U.S. today."
    ]


def test_synthesize_and_render_accepts_none_codec_pth_and_warm_engine_lock(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    ref_wav = tmp_path / "ref.wav"
    sf.write(str(ref_wav), _make_speech_wave(1.0), sar.SAMPLE_RATE_HZ)
    centroid_path = tmp_path / "master_centroid.bin"
    centroid_path.write_bytes(np.ones(256, dtype="<f4").tobytes())
    tflite_dir = tmp_path / "models" / "bundles" / "v002"
    tflite_dir.mkdir(parents=True)
    tflite_path = tflite_dir / "codec_decoder_fp16.tflite"
    tflite_path.write_bytes(b"TFL3")
    finetuned_dir = tmp_path / "models" / "dac-finetune-v002"
    finetuned_dir.mkdir(parents=True)
    finetuned_pth = finetuned_dir / "codec_finetuned_v002.pth"
    finetuned_pth.write_bytes(b"PTH")

    class _FakeWeSpeaker:
        def __init__(self, *_a: Any, **_k: Any) -> None:
            pass

        def extract_embedding(self, _pcm: np.ndarray) -> np.ndarray:
            return np.ones(256, dtype=np.float32)

    class _FakeDnsmos:
        def __init__(self, *_a: Any, **_k: Any) -> None:
            pass

        def score_p808(self, _pcm: np.ndarray) -> float:
            return 4.35

        def score_sig_bak_ovr(self, _pcm: np.ndarray) -> dict[str, float]:
            return {"sig": 4.2, "bak": 4.4, "ovr": 4.1}

    seen_codec_pth_args: list[Path | None] = []
    monkeypatch.setattr(sar.eva, "WeSpeakerEngine", _FakeWeSpeaker)
    monkeypatch.setattr(sar.eva, "DnsmosP808Engine", _FakeDnsmos)
    monkeypatch.setattr(sar.eva, "resample_44k_to_16k_android_exact", lambda x, *_a: np.zeros(16000, dtype=np.float32))
    monkeypatch.setattr(sar.s2_pro_infer, "encode_reference_audio_to_codes", lambda *_a, **_k: (np.zeros((10, 8), dtype=np.int32), 1.0))
    monkeypatch.setattr(sar.s2_pro_infer, "load_s2_pro_dual_ar", lambda *_a, **_k: (object(), object(), None))
    monkeypatch.setattr(sar.s2_pro_infer, "build_s2_pro_prompt_tensor", lambda *_a, **_k: np.zeros((11, 8)))
    monkeypatch.setattr(
        sar.s2_pro_infer,
        "generate_dual_ar_codes",
        lambda _m, _p, *, seed, **_k: sar.s2_pro_infer.GenerationResult(
            codes=np.zeros((10, 16), dtype=np.int32),
            seed=seed,
            temperature=0.72,
            top_p=0.8,
            top_k=30,
            prompt_tokens=8,
            generated_frames=16,
            hit_eos=True,
            elapsed_sec=0.1,
            frames_per_sec=160.0,
            residual_clamped_count=0,
        ),
    )
    monkeypatch.setattr(
        sar.s2_pro_infer,
        "decode_codes_batch_tflite",
        lambda codes_list, **_k: [(_make_speech_wave(1.5), {"decoder": "fake"}) for _ in codes_list],
    )

    def _fake_pt_decode(_codes: Any, *, codec_pth: Path | None = None, tflite_path: Path | None = None, **_k: Any) -> np.ndarray:
        seen_codec_pth_args.append(codec_pth)
        return _make_speech_wave(1.5)

    monkeypatch.setattr(sar.s2_pro_infer, "decode_codes_pytorch_dac", _fake_pt_decode)
    monkeypatch.setattr(sar, "transcribe_pcm16k_whisper", lambda *_a, **_k: "Hello world on Pixel.")

    out_wav_pass = tmp_path / "pass.wav"
    rep = sar.run_end_to_end_pipeline(
        target_text="Hello world on Pixel.",
        ref_wav=ref_wav,
        ref_text="Reference.",
        extra_ref_wav=None,
        seeds=(101,),
        lora_dir=None,
        codec_pth=None,
        codec_tflite=tflite_path,
        master_centroid_path=centroid_path,
        room_ir_path=tmp_path / "missing_ir.pcm",
        room_tone_path=tmp_path / "missing_tone.pcm",
        wespeaker_onnx=tmp_path / "w.onnx",
        dnsmos_onnx=tmp_path / "d.onnx",
        output_wav=out_wav_pass,
        report_json=tmp_path / "pass_report.json",
        device="cpu",
        skip_video=True,
        require_strict_gates=True,
        max_gate_retries=0,
    )
    assert rep["strict_gates_passed"] is True
    assert out_wav_pass.is_file()
    assert seen_codec_pth_args == [finetuned_pth]

    # When strict gates fail, output_wav must NOT be published while report_json is written
    monkeypatch.setattr(sar, "transcribe_pcm16k_whisper", lambda *_a, **_k: "wrong words")
    out_wav_fail = tmp_path / "fail.wav"
    fail_report = tmp_path / "fail_none_codec_report.json"
    with pytest.raises(RuntimeError, match="Strict 4-gate verification failed"):
        sar.run_end_to_end_pipeline(
            target_text="Hello world on Pixel.",
            ref_wav=ref_wav,
            ref_text="Reference.",
            extra_ref_wav=None,
            seeds=(101,),
            lora_dir=None,
            codec_pth=None,
            codec_tflite=tflite_path,
            master_centroid_path=centroid_path,
            room_ir_path=tmp_path / "missing_ir.pcm",
            room_tone_path=tmp_path / "missing_tone.pcm",
            wespeaker_onnx=tmp_path / "w.onnx",
            dnsmos_onnx=tmp_path / "d.onnx",
            output_wav=out_wav_fail,
            report_json=fail_report,
            device="cpu",
            skip_video=True,
            require_strict_gates=True,
            max_gate_retries=0,
        )
    assert not out_wav_fail.exists()
    assert fail_report.is_file()

    # WarmS2ProEngine lock coordinates with unload() and re-entrant get_or_load()
    eng = s2_pro_infer.WarmS2ProEngine()
    hf = tmp_path / "hf"
    hf.mkdir()
    monkeypatch.setattr(s2_pro_infer, "load_s2_pro_dual_ar", lambda *_a, **_k: (object(), object(), None))
    unloaded = threading.Event()
    with eng.lock:
        eng.get_or_load(hf, lora_dir=None, device="cpu")
        t_unload = threading.Thread(target=lambda: (eng.unload(), unloaded.set()))
        t_unload.start()
        time.sleep(0.05)
        assert not unloaded.is_set(), "unload() must block until WarmS2ProEngine.lock is released"
    t_unload.join(timeout=2.0)
    assert unloaded.is_set()


def test_early_exit_on_pass_stops_at_first_passing_take_and_retries_on_failure(tmp_path: Path) -> None:
    ir_path, tone_path = _make_room_ir_and_tone(tmp_path)
    lexicon_path = tmp_path / "lexicon.json"
    lexicon_path.write_text("{}", encoding="utf-8")

    caller_clauses = [
        {"id": "c_fast", "spoken_text": "First clause passes immediately on take zero."},
        {"id": "c_retry", "spoken_text": "Second clause fails take zero then passes take one."},
    ]
    call_counts: dict[str, int] = {"c_fast": 0, "c_retry": 0}

    def _synth(clause: rbs.PlannedClause, n_takes: int) -> list[rbs.SynthesizedTakeData]:
        parent = clause.parent_clause_id or clause.clause_id
        out = []
        for _ in range(n_takes):
            call_counts[parent] = call_counts.get(parent, 0) + 1
            attempt = call_counts[parent]
            pcm = _make_speech_wave(3.0, f0_hz=165.0 + 10.0 * attempt)
            out.append(
                rbs.SynthesizedTakeData(
                    pcm44k=pcm,
                    discrete_audio_tokens=[151678 + attempt],
                    seed=9000 + attempt,
                    temperature=0.7,
                )
            )
        return out

    verify_calls: dict[str, int] = {}

    def _verify(pcm: np.ndarray, expected_text: str) -> dict[str, Any]:
        verify_calls[expected_text] = verify_calls.get(expected_text, 0) + 1
        attempt = verify_calls[expected_text]
        tokens = [w for w in expected_text.replace(".", "").split() if w]
        spans = [(tok, 1000 + i * 2000, 2800 + i * 2000) for i, tok in enumerate(tokens)]
        # Make c_retry fail CER on attempt 1 and pass on attempt 2
        bad_first = "Second clause" in expected_text and attempt == 1
        return {
            "recognized_text": "garbled" if bad_first else expected_text,
            "cer": 0.45 if bad_first else 0.0,
            "max_phoneme_duration_ms": 140.0,
            "wespeaker_cosine": 0.90,
            "dnsmos_p808": 4.25,
            "tail_rms_dbfs": sar.compute_tail_rms_dbfs(pcm),
            "syllables_per_sec": 4.8,
            "word_spans": spans,
        }

    report = rbs.render_broadcast_studio(
        caller_clauses=caller_clauses,
        project_id="early_exit_test",
        output_dir=tmp_path / "studio_early_exit",
        takes_per_clause=4,
        lexicon_path=lexicon_path,
        room_ir_path=ir_path,
        room_tone_path=tone_path,
        skip_video=True,
        require_strict_gates=True,
        require_real_alignment=True,
        early_exit_on_pass=True,
        schema_path=SCHEMA_PATH,
        synthesizer_fn=_synth,
        verifier_fn=_verify,
    )

    assert report["metrics"]["passed_all_strict_gates"] is True
    assert call_counts["c_fast"] == 1, f"Expected early exit after 1 passing take, got {call_counts['c_fast']}"
    assert call_counts["c_retry"] == 2, f"Expected 2 takes (fail take 0, pass take 1), got {call_counts['c_retry']}"


def test_chop_broll_use_remotion_delegates_to_worker_and_fails_loudly(tmp_path: Path) -> None:
    if not shutil.which("ffmpeg"):
        pytest.skip("ffmpeg not available")
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    bundles_dir = tmp_path / "bundles"
    _write_minimal_bundle(bundles_dir / "v002", lora_version=2)
    renders_dir = tmp_path / "renders"
    renders_dir.mkdir(parents=True, exist_ok=True)
    src_mp4 = renders_dir / "head_rem_test.mp4"
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "color=c=0x112233:s=360x640:d=2:r=30",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:duration=2:sample_rate=44100",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            str(src_mp4),
        ],
        check=True,
        capture_output=True,
        timeout=30,
    )

    received_payloads: list[dict[str, Any]] = []
    fail_worker = {"flag": False}

    class _StubRemotionHandler(BaseHTTPRequestHandler):
        def log_message(self, format: str, *args: Any) -> None:
            return

        def do_POST(self) -> None:
            raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            body = json.loads(raw.decode("utf-8"))
            received_payloads.append(body)
            if fail_worker["flag"]:
                self.send_response(500)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"error":"remotion_crash"}')
                return
            out_path = Path(body["outputLocalPath"])
            out_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src_mp4, out_path)
            resp = json.dumps({"status": "READY", "outputLocalPath": str(out_path)}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp)))
            self.end_headers()
            self.wfile.write(resp)

    remotion_srv = ThreadingHTTPServer(("127.0.0.1", 0), _StubRemotionHandler)
    remotion_port = remotion_srv.server_port
    remotion_thread = threading.Thread(target=remotion_srv.serve_forever, daemon=True)
    remotion_thread.start()

    store = ota_server.OtaBundleStore(bundles_dir, tmp_path / "prefs.jsonl")
    rm = ota_server.RenderJobManager(
        renders_dir=renders_dir,
        bundles_root=bundles_dir,
        remotion_worker_url=f"http://127.0.0.1:{remotion_port}",
    )
    ota_srv = ota_server.OtaHttpServer(("127.0.0.1", 0), store, render_manager=rm, quiet=True)
    ota_port = ota_srv.server_port
    ota_thread = threading.Thread(target=ota_srv.serve_forever, daemon=True)
    ota_thread.start()

    try:
        req_body = {
            "source_job_id": "head_rem_test",
            "script_id": "story_rem",
            "title": "REMOTION DESK",
            "use_remotion": True,
            "words": [
                {"word": "Breaking", "start_ms": 0, "end_ms": 500},
                {"word": "news", "start_ms": 500, "end_ms": 1100},
            ],
            "clauses": [
                {
                    "id": "c0",
                    "spoken_text": "Breaking news.",
                    "start_ms": 0,
                    "end_ms": 1100,
                    "segment_mode": "BROLL_CUTAWAY",
                    "broll_url": "/v1/renders/broll_globe.jpg",
                    "evidence_source": "LAB REPORT",
                    "depth_text_tag": "LIVE",
                }
            ],
            "overlays": [{"headline": "DESK ALERT", "subline": "LOCAL PIPELINE"}],
        }
        req = urllib.request.Request(
            f"http://127.0.0.1:{ota_port}/v1/core/chop-broll",
            data=json.dumps(req_body).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            assert resp.status == 200
            out = json.loads(resp.read().decode("utf-8"))

        assert out["remotion_used"] is True
        assert out["renderer"] == "remotion-4"
        assert out["broll_cut_count"] == 1
        assert len(received_payloads) == 1
        ip = received_payloads[0]["inputProps"]
        assert ip["templateId"] == "Newsroom916"
        assert ip["lipsyncUrl"] == f"http://127.0.0.1:{ota_port}/v1/renders/head_rem_test.mp4"
        assert ip["voiceUrl"].startswith(f"http://127.0.0.1:{ota_port}/v1/renders/")
        assert ip["words"] == [
            {"word": "Breaking", "startMs": 0, "endMs": 500},
            {"word": "news", "startMs": 500, "endMs": 1100},
        ]
        assert ip["beats"][0]["pipShrink"] is True
        assert ip["beats"][0]["bRollUrl"] == f"http://127.0.0.1:{ota_port}/v1/renders/broll_globe.jpg"
        assert ip["beats"][0]["evidenceSource"] == "LAB REPORT"
        assert ip["beats"][0]["depthTextTag"] == "LIVE"
        assert ip["packaging"]["chyronHeadline"] == "DESK ALERT"

        with urllib.request.urlopen(f"http://127.0.0.1:{ota_port}/health", timeout=10) as hresp:
            health = json.loads(hresp.read().decode("utf-8"))
        assert health["remotion_renders_completed"] == 1

        # Failure path: zero silent fallback when Remotion worker fails
        fail_worker["flag"] = True
        fail_req = urllib.request.Request(
            f"http://127.0.0.1:{ota_port}/v1/core/chop-broll",
            data=json.dumps(req_body).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with pytest.raises(urllib.error.HTTPError) as exc_info:
            urllib.request.urlopen(fail_req, timeout=30)
        assert exc_info.value.code == 502
    finally:
        ota_srv.shutdown()
        ota_srv.server_close()
        rm.shutdown()
        remotion_srv.shutdown()
        remotion_srv.server_close()


def test_calibrated_live_v002_gates_accept_broadcast_quality_and_reject_garbled(tmp_path: Path) -> None:
    ir_path, tone_path = _make_room_ir_and_tone(tmp_path)
    lexicon_path = tmp_path / "lexicon.json"
    lexicon_path.write_text("{}", encoding="utf-8")

    def _synth(clause: rbs.PlannedClause, n_takes: int) -> list[rbs.SynthesizedTakeData]:
        return [
            rbs.SynthesizedTakeData(
                pcm44k=_make_speech_wave(3.0, f0_hz=170.0),
                discrete_audio_tokens=[151678],
                seed=7000 + i,
                temperature=0.7,
            )
            for i in range(n_takes)
        ]

    # Real measured v002-grade metrics (cer=0.0127 compound spacing, spk=0.793, mos=3.92) pass calibrated live gates
    def _verify_v002_realistic(pcm: np.ndarray, expected_text: str) -> dict[str, Any]:
        tokens = [w for w in expected_text.replace(".", "").split() if w]
        spans = [(tok, 1000 + i * 2000, 2800 + i * 2000) for i, tok in enumerate(tokens)]
        return {
            "recognized_text": expected_text,
            "cer": 0.0127,
            "max_phoneme_duration_ms": 180.0,
            "wespeaker_cosine": 0.793,
            "dnsmos_p808": 3.92,
            "tail_rms_dbfs": sar.compute_tail_rms_dbfs(pcm),
            "syllables_per_sec": 4.4,
            "word_spans": spans,
        }

    ok = rbs.render_broadcast_studio(
        script_text="Tonight on Aircheck Tech Desk, mobile video production crossed a major line.",
        project_id="v002_live_cal_ok",
        output_dir=tmp_path / "cal_ok",
        takes_per_clause=4,
        lexicon_path=lexicon_path,
        room_ir_path=ir_path,
        room_tone_path=tone_path,
        skip_video=True,
        require_strict_gates=True,
        require_real_alignment=True,
        early_exit_on_pass=True,
        gate_max_cer=ota_server.LIVE_TTS_GATE_MAX_CER,
        gate_min_speaker_cos=ota_server.LIVE_TTS_GATE_MIN_SPEAKER_COS,
        gate_min_dnsmos_p808=ota_server.LIVE_TTS_GATE_MIN_DNSMOS_P808,
        gate_max_tail_rms_dbfs=ota_server.LIVE_TTS_GATE_MAX_TAIL_RMS_DBFS,
        gate_min_syllables_per_sec=ota_server.LIVE_TTS_GATE_MIN_SYLLABLES_PER_SEC,
        gate_max_syllables_per_sec=ota_server.LIVE_TTS_GATE_MAX_SYLLABLES_PER_SEC,
        gate_max_phoneme_ms=ota_server.LIVE_TTS_GATE_MAX_PHONEME_MS,
        schema_path=SCHEMA_PATH,
        synthesizer_fn=_synth,
        verifier_fn=_verify_v002_realistic,
    )
    assert ok["metrics"]["passed_all_strict_gates"] is True
    ota_server._validate_strict_tts_metrics(ok["metrics"], require_present=True)

    # Garbled / wrong-speaker take MUST still fail loudly under calibrated live gates
    def _verify_imposter(pcm: np.ndarray, expected_text: str) -> dict[str, Any]:
        return {
            "recognized_text": "totally garbled output",
            "cer": 0.28,
            "max_phoneme_duration_ms": 190.0,
            "wespeaker_cosine": 0.54,
            "dnsmos_p808": 2.95,
            "tail_rms_dbfs": sar.compute_tail_rms_dbfs(pcm),
            "syllables_per_sec": 4.4,
            "word_spans": [("totally", 1000, 4000)],
        }

    with pytest.raises(RuntimeError, match="failed strict 4-gate verification"):
        rbs.render_broadcast_studio(
            script_text="Tonight on Aircheck Tech Desk.",
            project_id="v002_live_cal_fail",
            output_dir=tmp_path / "cal_fail",
            takes_per_clause=2,
            max_retries_per_clause=0,
            lexicon_path=lexicon_path,
            room_ir_path=ir_path,
            room_tone_path=tone_path,
            skip_video=True,
            require_strict_gates=True,
            require_real_alignment=True,
            early_exit_on_pass=True,
            gate_max_cer=ota_server.LIVE_TTS_GATE_MAX_CER,
            gate_min_speaker_cos=ota_server.LIVE_TTS_GATE_MIN_SPEAKER_COS,
            gate_min_dnsmos_p808=ota_server.LIVE_TTS_GATE_MIN_DNSMOS_P808,
            gate_max_tail_rms_dbfs=ota_server.LIVE_TTS_GATE_MAX_TAIL_RMS_DBFS,
            gate_min_syllables_per_sec=ota_server.LIVE_TTS_GATE_MIN_SYLLABLES_PER_SEC,
            gate_max_syllables_per_sec=ota_server.LIVE_TTS_GATE_MAX_SYLLABLES_PER_SEC,
            gate_max_phoneme_ms=ota_server.LIVE_TTS_GATE_MAX_PHONEME_MS,
            schema_path=SCHEMA_PATH,
            synthesizer_fn=_synth,
            verifier_fn=_verify_imposter,
        )


