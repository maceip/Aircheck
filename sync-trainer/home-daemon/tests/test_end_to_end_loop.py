"""Gate 5a End-to-End Automated Test Suite for sync-trainer/home-daemon.

Runs cleanly with both `python3 -m pytest` and stdlib `python3 -m unittest`
without requiring H100 torch/tinker-cookbook wheels.
"""

from __future__ import annotations

import asyncio
import hashlib
import http.client
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from typing import Any

DAEMON_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = DAEMON_DIR.parents[1]
if str(DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(DAEMON_DIR))

from ota_server import create_ota_server, is_tailscale_or_loopback_host  # noqa: E402
from tinker_voice_daemon import (  # noqa: E402
    FOREGROUND_RAM_CEILING_MB,
    SAMPLE_RATE_HZ,
    AudioGateMetrics,
    PreferenceRecord,
    SchemaValidationError,
    SingleSpeakerAudioEnv,
    build_bundle_manifest,
    build_opsd_and_grpo_dataset,
    compute_audio_reward,
    load_preferences_jsonl,
    run_nightly_update,
    validate_bundle_manifest,
    validate_preference_record,
)


def _make_valid_preference(
    index: int = 0,
    *,
    user_action: str = "TAKE_SWAP_AND_PACE_EDIT",
    user_gap_scale: float = 0.45,
    raw_text: str | None = None,
    normalized_phonemes: str | None = None,
    chosen_tokens: list[int] | None = None,
    rejected_tokens: list[int] | None = None,
    gate_failures: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    return {
        "timestamp": 1795409000 + index * 37,
        "clause_id": f"c_{index:03d}",
        "lora_version": 12,
        "raw_text": raw_text
        if raw_text is not None
        else f"We ripped out the cloud wrapper in clause {index} and ran Aircheck bare-metal.",
        "normalized_phonemes": normalized_phonemes
        if normalized_phonemes is not None
        else "w iː ɹ ˈɪ p t ˈaʊ t ð ə k l ˈaʊ d ɹ ˈæ p ɚ",
        "user_action": user_action,
        "user_gap_scale": user_gap_scale,
        "chosen_tokens": chosen_tokens
        if chosen_tokens is not None
        else [1042 + index, 8819, 441, 902],
        "rejected_tokens": rejected_tokens
        if rejected_tokens is not None
        else [1042 + index, 7104, 118, 994],
        "gate_failures": gate_failures
        if gate_failures is not None
        else [
            {
                "seed": 948104 + index,
                "cer": 0.08,
                "reason": "SKIPPED_WORD",
                "tokens": [1042 + index, 331, 512],
            }
        ],
    }


def _populate_bundle_dir(
    bundle_dir: Path,
    *,
    lora_version: int = 12,
    include_stale_lora: bool = False,
) -> dict[str, bytes]:
    """Writes deterministic realistic binary/json payloads matching all OTA artifact roles."""
    files: dict[str, bytes] = {
        "s2_pro_4b_q4_k_m.gguf": b"GGUF" + bytes(range(256)) * 16,
        f"voice_lora_v{lora_version:03d}.gguf": b"GGUF" + b"\x03\x00\x00\x00" + b"LORA" * 64,
        "codec_decoder_fp16.tflite": b"TFL3" + b"\x11\x22\x33\x44" * 32,
        "sherpa_ctc_aligner_en.tar.gz": b"\x1f\x8b\x08\x00" + b"SHERPA_CTC" * 24,
        "wespeaker_resnet34.onnx": b"\x08\x07\x12\x09wespeaker" * 16,
        "master_centroid.bin": b"\x00\x00\x80\x3f" * 256,
        "dnsmos_p808.onnx": b"\x08\x07\x12\x06dnsmos" * 16,
        "room_ir_44k.pcm": b"\x00\x00\x00\x3f" * 529,
        "room_tone_44k.pcm": b"\x00\x00\x80\x38" * 1024,
        "lexicon.json": json.dumps(
            {
                "Aircheck": "ˈɛɚ tʃ ɛ k",
                "bare-metal": "b ˈɛɚ m ˈɛ t ə l",
                "GGUF": "dʒ iː dʒ iː j uː ˈɛ f",
            }
        ).encode("utf-8"),
    }
    if include_stale_lora and lora_version > 0:
        files[f"voice_lora_v{lora_version - 1:03d}.gguf"] = b"GGUF" + b"OLD_LORA" * 16

    bundle_dir.mkdir(parents=True, exist_ok=True)
    for name, content in files.items():
        (bundle_dir / name).write_bytes(content)
    return files


class TestPreferenceSchemaValidation(unittest.TestCase):
    def test_valid_preferences_jsonl_round_trips_and_matches_repo_schema(self) -> None:
        schema_path = REPO_ROOT / "docs" / "swarm" / "schemas" / "preference_tuple.schema.json"
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        allowed_actions = schema["properties"]["user_action"]["enum"]
        self.assertEqual(len(allowed_actions), 6)

        with tempfile.TemporaryDirectory() as tmp:
            jsonl_path = Path(tmp) / "preferences.jsonl"
            lines = [
                json.dumps(_make_valid_preference(idx, user_action=act))
                for idx, act in enumerate(allowed_actions)
            ]
            jsonl_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

            records = load_preferences_jsonl(jsonl_path)
            self.assertEqual(len(records), len(allowed_actions))
            for idx, rec in enumerate(records):
                self.assertIsInstance(rec, PreferenceRecord)
                self.assertEqual(rec.clause_id, f"c_{idx:03d}")
                self.assertEqual(rec.user_action, allowed_actions[idx])
                # Verify exact required keys on round-trip
                self.assertEqual(set(rec.to_dict().keys()), set(schema["required"]))

    def test_invalid_preference_payloads_are_rejected(self) -> None:
        base = _make_valid_preference()

        # 1. Missing required key
        missing = dict(base)
        del missing["chosen_tokens"]
        with self.assertRaises(SchemaValidationError):
            validate_preference_record(missing)

        # 2. Extra unexpected field (additionalProperties: false)
        extra = dict(base, unexpected_field="nope")
        with self.assertRaises(SchemaValidationError):
            validate_preference_record(extra)

        # 3. Invalid user_action enum
        bad_action = dict(base, user_action="RANDOM_UNSPECIFIED_ACTION")
        with self.assertRaises(SchemaValidationError):
            validate_preference_record(bad_action)

        # 4. user_gap_scale out of [0.25, 1.0]
        for bad_gap in (0.24, 1.01, -0.5, float("nan"), True):
            with self.assertRaises(SchemaValidationError):
                validate_preference_record(dict(base, user_gap_scale=bad_gap))

        # 5. Bool masquerading as int in timestamp / token arrays
        with self.assertRaises(SchemaValidationError):
            validate_preference_record(dict(base, timestamp=True))
        with self.assertRaises(SchemaValidationError):
            validate_preference_record(dict(base, chosen_tokens=[1, True, 3]))

        # 6. Negative timestamp or lora_version
        with self.assertRaises(SchemaValidationError):
            validate_preference_record(dict(base, timestamp=-1))
        with self.assertRaises(SchemaValidationError):
            validate_preference_record(dict(base, lora_version=-2))

        # 7. Malformed gate_failures entry (additionalProperties / negative cer / empty reason)
        bad_gf_extra = dict(
            base,
            gate_failures=[
                {"seed": 1, "cer": 0.1, "reason": "SKIPPED_WORD", "tokens": [1], "x": 2}
            ],
        )
        with self.assertRaises(SchemaValidationError):
            validate_preference_record(bad_gf_extra)

        bad_gf_cer = dict(
            base,
            gate_failures=[{"seed": 1, "cer": -0.01, "reason": "SKIPPED_WORD", "tokens": [1]}],
        )
        with self.assertRaises(SchemaValidationError):
            validate_preference_record(bad_gf_cer)

        bad_gf_reason = dict(
            base,
            gate_failures=[{"seed": 1, "cer": 0.05, "reason": "", "tokens": [1]}],
        )
        with self.assertRaises(SchemaValidationError):
            validate_preference_record(bad_gf_reason)

        # 8. Malformed JSONL line in file
        with tempfile.TemporaryDirectory() as tmp:
            bad_jsonl = Path(tmp) / "preferences.jsonl"
            bad_jsonl.write_text(json.dumps(base) + "\n{not valid json\n", encoding="utf-8")
            with self.assertRaises(SchemaValidationError):
                load_preferences_jsonl(bad_jsonl)


class TestRewardAndAudioEnv(unittest.TestCase):
    def test_exact_spec_reward_function(self) -> None:
        # Hard pronunciation penalty: CER > 0.0 ALWAYS yields -2.0 even if human_chosen=True
        failed_metrics = AudioGateMetrics(
            cer=0.01,
            speaker_cosine_sim=0.99,
            dnsmos_p808=5.0,
        )
        self.assertEqual(compute_audio_reward(failed_metrics, human_chosen=False), -2.0)
        self.assertEqual(compute_audio_reward(failed_metrics, human_chosen=True), -2.0)

        # Passing reward formula: 1.5 * sim + 0.5 * (dnsmos / 5.0) + (1.0 if human_chosen else 0.0)
        good_metrics = AudioGateMetrics(
            cer=0.0,
            speaker_cosine_sim=0.92,
            dnsmos_p808=4.5,
        )
        expected_base = 1.5 * 0.92 + 0.5 * (4.5 / 5.0)  # 1.38 + 0.45 = 1.83
        self.assertAlmostEqual(
            compute_audio_reward(good_metrics, human_chosen=False),
            expected_base,
            places=9,
        )
        self.assertAlmostEqual(
            compute_audio_reward(good_metrics, human_chosen=True),
            expected_base + 1.0,
            places=9,
        )

    def test_single_speaker_audio_env_lifecycle_and_no_metric_fabrication(self) -> None:
        record = validate_preference_record(_make_valid_preference())

        # Env with real verifier callback
        def real_verifier(tokens: Any, rec: PreferenceRecord) -> AudioGateMetrics:
            if tuple(tokens) == rec.chosen_tokens:
                return AudioGateMetrics(cer=0.0, speaker_cosine_sim=0.94, dnsmos_p808=4.4)
            return AudioGateMetrics(cer=0.12, speaker_cosine_sim=0.90, dnsmos_p808=4.2)

        env = SingleSpeakerAudioEnv(record, verifier_fn=real_verifier, stop_token_ids=[2])
        obs, stop = asyncio.run(env.initial_observation())
        self.assertEqual(obs["raw_text"], record.raw_text)
        self.assertNotIn("user_gap_scale", obs)
        self.assertNotIn("normalized_phonemes", obs)
        self.assertEqual(stop, [2])

        res_chosen = asyncio.run(env.step(record.chosen_tokens))
        self.assertTrue(res_chosen.episode_done)
        expected = 1.5 * 0.94 + 0.5 * (4.4 / 5.0) + 1.0
        self.assertAlmostEqual(res_chosen.reward, expected, places=9)

        res_failed = asyncio.run(env.step(record.rejected_tokens))
        self.assertEqual(res_failed.reward, -2.0)

        # Constitutional Law 2: Env without verifier_fn MUST NOT fabricate metrics for unknown tokens
        env_no_verifier = SingleSpeakerAudioEnv(record)
        with self.assertRaises(RuntimeError):
            asyncio.run(env_no_verifier.step(record.chosen_tokens))

        # Recorded CER > 0 gate failure resolves deterministically to -2.0 without fabrication
        res_logged_failure = asyncio.run(
            env_no_verifier.step(record.gate_failures[0].tokens)
        )
        self.assertEqual(res_logged_failure.reward, -2.0)


class TestOpsdAndGrpoDatasetBuilder(unittest.TestCase):
    def test_deterministic_opsd_and_grpo_batching_on_10_realistic_records(self) -> None:
        actions = [
            "TAKE_SWAP",
            "PACE_EDIT",
            "TAKE_SWAP_AND_PACE_EDIT",
            "PHONEME_OVERRIDE",
            "VIDEO_ANCHOR_SNAP",
            "PROJECT_EXPORT",
            "TAKE_SWAP",
            "PACE_EDIT",
            "TAKE_SWAP_AND_PACE_EDIT",
            "PHONEME_OVERRIDE",
        ]
        lexicon = {
            "Aircheck": "ˈɛɚ tʃ ɛ k",
            "GGUF": "dʒ iː dʒ iː j uː ˈɛ f",
            "LiteRT": "l ˈaɪ t ɑːɹ t ˈiː",
        }
        records: list[PreferenceRecord] = []
        for idx in range(10):
            gap = round(0.30 + idx * 0.07, 2)
            raw_text = (
                f"Aircheck runs GGUF and LiteRT locally for studio clause {idx}."
                if idx % 2 == 0
                else f"Clipper trims dead air cleanly in clause {idx}."
            )
            failures = (
                [
                    {
                        "seed": 5000 + idx,
                        "cer": 0.11,
                        "reason": "SKIPPED_WORD",
                        "tokens": [2000 + idx, 10, 11],
                    },
                    {
                        "seed": 5100 + idx,
                        "cer": 0.0,
                        "reason": "TAIL_NOISE_GLITCH",
                        "tokens": [2000 + idx, 99, 88],
                    },
                ]
                if idx < 7
                else []
            )
            records.append(
                validate_preference_record(
                    _make_valid_preference(
                        idx,
                        user_action=actions[idx],
                        user_gap_scale=gap,
                        raw_text=raw_text,
                        gate_failures=failures,
                    )
                )
            )

        ds_forward = build_opsd_and_grpo_dataset(records, lexicon=lexicon)
        ds_reversed = build_opsd_and_grpo_dataset(list(reversed(records)), lexicon=lexicon)

        # Deterministic regardless of input ordering
        self.assertEqual(ds_forward, ds_reversed)
        self.assertEqual(ds_forward.record_count, 10)
        self.assertEqual(len(ds_forward.opsd_pairs), 10)
        self.assertEqual(len(ds_forward.grpo_groups), 10)

        for idx, pair in enumerate(ds_forward.opsd_pairs):
            self.assertEqual(pair.clause_id, f"c_{idx:03d}")
            # Student sees raw_text ONLY (no edited cadence or phoneme leak)
            self.assertIn(pair.raw_text, pair.student_prompt)
            self.assertNotIn("cadence_gap_scale", pair.student_prompt)
            self.assertNotIn("<|phonemes|>", pair.student_prompt)
            self.assertNotIn("<|lexicon|>", pair.student_prompt)

            # Teacher is conditioned on edited cadence + lexicon.json + normalized_phonemes
            self.assertIn(f"{pair.user_gap_scale:.4f}", pair.teacher_prompt)
            self.assertIn(pair.normalized_phonemes, pair.teacher_prompt)
            if idx % 2 == 0:
                self.assertEqual(
                    set(pair.lexicon_overrides.keys()),
                    {"Aircheck", "GGUF", "LiteRT"},
                )
                self.assertIn("Aircheck=ˈɛɚ tʃ ɛ k", pair.teacher_prompt)
            else:
                self.assertEqual(pair.lexicon_overrides, {})

        # Verify GRPO groups contain chosen, rejected, and gate failures with exact -2.0 CER penalty
        first_group = ds_forward.grpo_groups[0]
        roles = [c.role for c in first_group.candidates]
        self.assertEqual(roles, ["chosen", "rejected", "gate_failure", "gate_failure"])
        cer_failure = next(
            c for c in first_group.candidates if c.failure_reason == "SKIPPED_WORD"
        )
        self.assertEqual(cer_failure.fixed_reward, -2.0)
        noise_failure = next(
            c for c in first_group.candidates if c.failure_reason == "TAIL_NOISE_GLITCH"
        )
        self.assertIsNone(noise_failure.fixed_reward)
        # 1 human swap pair + 2 gate-failure contrast pairs
        self.assertEqual(len(first_group.preference_pairs), 3)


class TestFailFastZeroFabrication(unittest.TestCase):
    def test_run_nightly_update_fails_fast_on_missing_base_checkpoint(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            prefs = root / "preferences.jsonl"
            prefs.write_text(json.dumps(_make_valid_preference()) + "\n", encoding="utf-8")

            missing_ckpt = root / "nonexistent_s2_pro_base_ckpt"
            lora_out = root / "lora_out"
            llama_cpp = root / "llama.cpp"
            llama_cpp.mkdir()
            (llama_cpp / "convert_lora_to_gguf.py").write_text("# stub\n", encoding="utf-8")

            with self.assertRaises(RuntimeError) as ctx:
                run_nightly_update(
                    jsonl_path=prefs,
                    base_ckpt=missing_ckpt,
                    lora_out_dir=lora_out,
                    next_version=13,
                    llama_cpp_dir=llama_cpp,
                )

            self.assertIn("checkpoint", str(ctx.exception).lower())
            # Zero fabricated .gguf files created
            created_ggufs = list(root.rglob("*.gguf"))
            self.assertEqual(created_ggufs, [])

    def test_run_nightly_update_fails_fast_on_missing_converter(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            prefs = root / "preferences.jsonl"
            prefs.write_text(json.dumps(_make_valid_preference()) + "\n", encoding="utf-8")

            base_ckpt = root / "base_ckpt"
            base_ckpt.mkdir()
            lora_out = root / "lora_out"
            llama_cpp = root / "empty_llama_cpp"
            llama_cpp.mkdir()

            with self.assertRaises(RuntimeError) as ctx:
                run_nightly_update(
                    jsonl_path=prefs,
                    base_ckpt=base_ckpt,
                    lora_out_dir=lora_out,
                    next_version=13,
                    llama_cpp_dir=llama_cpp,
                )

            self.assertIn("convert_lora_to_gguf.py", str(ctx.exception))
            self.assertEqual(list(root.rglob("*.gguf")), [])

    def test_run_nightly_update_fails_fast_without_training_wheels(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            prefs = root / "preferences.jsonl"
            prefs.write_text(json.dumps(_make_valid_preference()) + "\n", encoding="utf-8")

            base_ckpt = root / "base_ckpt"
            base_ckpt.mkdir()
            lora_out = root / "lora_out"
            llama_cpp = root / "llama.cpp"
            llama_cpp.mkdir()
            (llama_cpp / "convert_lora_to_gguf.py").write_text("# present\n", encoding="utf-8")

            with self.assertRaises(RuntimeError) as ctx:
                run_nightly_update(
                    jsonl_path=prefs,
                    base_ckpt=base_ckpt,
                    lora_out_dir=lora_out,
                    next_version=13,
                    llama_cpp_dir=llama_cpp,
                )

            msg = str(ctx.exception).lower()
            self.assertTrue(
                "torch" in msg or "tinker" in msg,
                f"Expected missing training runtime error, got: {ctx.exception}",
            )
            self.assertEqual(list(root.rglob("*.gguf")), [])

    def test_pyproject_toml_metadata_and_optional_train_deps(self) -> None:
        raw_toml = (DAEMON_DIR / "pyproject.toml").read_text(encoding="utf-8")
        try:
            import tomllib
        except ImportError:
            self.assertIn('requires-python = ">=3.10"', raw_toml)
            self.assertIn("torch>=2.6", raw_toml)
            self.assertIn("tinker-cookbook", raw_toml)
            self.assertIn("pytest", raw_toml)
            return

        pyproject = tomllib.loads(raw_toml)
        self.assertEqual(pyproject["project"]["requires-python"], ">=3.10")
        train_deps = pyproject["project"]["optional-dependencies"]["train"]
        self.assertTrue(any(d.startswith("torch>=2.6") for d in train_deps))
        self.assertTrue(any(d.startswith("tinker-cookbook") for d in train_deps))
        self.assertTrue(any(d.startswith("pytest") for d in train_deps))

    def test_export_lora_gguf_sh_fails_fast_on_missing_inputs(self) -> None:
        script = DAEMON_DIR / "export_lora_gguf.sh"
        self.assertTrue(script.is_file())
        self.assertTrue(os.access(script, os.X_OK), "export_lora_gguf.sh must be executable")

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            out_gguf = root / "voice_lora_v013.gguf"
            proc = subprocess.run(
                [
                    str(script),
                    "--adapter-dir",
                    str(root / "missing_adapter"),
                    "--base-model",
                    str(root / "missing_base"),
                    "--llama-cpp-dir",
                    str(root / "missing_llama"),
                    "--output",
                    str(out_gguf),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertNotEqual(proc.returncode, 0)
            self.assertFalse(out_gguf.exists())

            # Also verify shell wrapper fails fast and cleans temp file when converter emits non-GGUF data
            adapter_dir = root / "adapter"
            adapter_dir.mkdir()
            (adapter_dir / "adapter_config.json").write_text('{"r": 32}\n', encoding="utf-8")
            (adapter_dir / "adapter_model.safetensors").write_bytes(b"SAFETENSORS_HEADER")
            base_dir = root / "base"
            base_dir.mkdir()
            llama_dir = root / "llama.cpp"
            llama_dir.mkdir()
            (llama_dir / "convert_lora_to_gguf.py").write_text(
                "import sys\n"
                "out = sys.argv[sys.argv.index('--outfile') + 1]\n"
                "open(out, 'wb').write(b'NOT_GGUF_PAYLOAD')\n",
                encoding="utf-8",
            )
            bad_proc = subprocess.run(
                [
                    str(script),
                    "--adapter-dir",
                    str(adapter_dir),
                    "--base-model",
                    str(base_dir),
                    "--llama-cpp-dir",
                    str(llama_dir),
                    "--output",
                    str(out_gguf),
                    "--python",
                    sys.executable,
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertNotEqual(bad_proc.returncode, 0)
            self.assertIn("GGUF magic", bad_proc.stderr)
            self.assertFalse(out_gguf.exists())
            self.assertEqual(list(root.glob("voice_lora_v013.gguf.tmp.*")), [])


class TestBundleManifestBuilder(unittest.TestCase):
    def test_build_bundle_manifest_sha256_44k_ram_budget_and_schema(self) -> None:
        schema_path = REPO_ROOT / "docs" / "swarm" / "schemas" / "ota_bundle_manifest.schema.json"
        schema = json.loads(schema_path.read_text(encoding="utf-8"))

        with tempfile.TemporaryDirectory() as tmp:
            bundle_dir = Path(tmp) / "2026.11.22-lora-v012"
            files = _populate_bundle_dir(bundle_dir, lora_version=12, include_stale_lora=True)

            manifest = build_bundle_manifest(
                bundle_dir=bundle_dir,
                bundle_version="2026.11.22-lora-v012",
                lora_version=12,
            )

            # Written file matches returned dict and passes strict validator
            manifest_file = bundle_dir / "bundle_manifest.json"
            self.assertTrue(manifest_file.is_file())
            on_disk = json.loads(manifest_file.read_text(encoding="utf-8"))
            self.assertEqual(on_disk, manifest)
            validate_bundle_manifest(on_disk)

            # Schema-level assertions against docs/swarm/schemas/ota_bundle_manifest.schema.json
            self.assertEqual(set(manifest.keys()), set(schema["required"]))
            self.assertEqual(manifest["schema_version"], schema["properties"]["schema_version"]["const"])
            self.assertEqual(manifest["sample_rate_hz"], SAMPLE_RATE_HZ)
            self.assertLessEqual(manifest["foreground_ram_ceiling_mb"], FOREGROUND_RAM_CEILING_MB)

            # Older voice_lora_v011.gguf must NOT be included; all 10 active roles MUST be present
            filenames = {art["filename"] for art in manifest["artifacts"]}
            self.assertNotIn("voice_lora_v011.gguf", filenames)
            self.assertIn("voice_lora_v012.gguf", filenames)
            self.assertEqual(len(manifest["artifacts"]), 10)

            allowed_roles = set(schema["properties"]["artifacts"]["items"]["properties"]["role"]["enum"])
            allowed_formats = set(schema["properties"]["artifacts"]["items"]["properties"]["format"]["enum"])
            allowed_cadences = set(schema["properties"]["artifacts"]["items"]["properties"]["update_cadence"]["enum"])
            sha_re = re.compile(schema["properties"]["artifacts"]["items"]["properties"]["sha256"]["pattern"])

            total_ram = 0
            for art in manifest["artifacts"]:
                self.assertIn(art["role"], allowed_roles)
                self.assertIn(art["format"], allowed_formats)
                self.assertIn(art["update_cadence"], allowed_cadences)
                self.assertRegex(art["sha256"], sha_re)

                raw_bytes = files[art["filename"]]
                self.assertEqual(art["size_bytes"], len(raw_bytes))
                self.assertEqual(art["sha256"], hashlib.sha256(raw_bytes).hexdigest())
                if art["role"] == "voice_lora_gguf":
                    self.assertEqual(art["update_cadence"], "nightly_delta")
                else:
                    self.assertEqual(art["update_cadence"], "static_once")
                total_ram += art["estimated_ram_mb"]

            self.assertLessEqual(total_ram, FOREGROUND_RAM_CEILING_MB)

    def test_build_bundle_manifest_enforces_44k_and_ram_ceiling(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            bundle_dir = Path(tmp) / "bundle"
            _populate_bundle_dir(bundle_dir, lora_version=3)

            # Reject non-44.1kHz sample rates (e.g. 48kHz RVC legacy)
            with self.assertRaises(ValueError):
                build_bundle_manifest(
                    bundle_dir=bundle_dir,
                    bundle_version="2026.11.22-lora-v003",
                    lora_version=3,
                    sample_rate_hz=48_000,
                )

            # Reject ceiling above 9500 MB
            with self.assertRaises(ValueError):
                build_bundle_manifest(
                    bundle_dir=bundle_dir,
                    bundle_version="2026.11.22-lora-v003",
                    lora_version=3,
                    foreground_ram_ceiling_mb=9_501,
                )

            # Reject artifact RAM sum exceeding 9500 MB
            with self.assertRaises(ValueError):
                build_bundle_manifest(
                    bundle_dir=bundle_dir,
                    bundle_version="2026.11.22-lora-v003",
                    lora_version=3,
                    artifact_ram_overrides={"s2_pro_base_gguf": 9_200},
                )


class TestLiveOtaServer(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.bundles_root = self.root / "bundles"
        self.bundle_version = "2026.11.22-lora-v012"
        self.bundle_dir = self.bundles_root / self.bundle_version
        self.files = _populate_bundle_dir(self.bundle_dir, lora_version=12)
        self.manifest = build_bundle_manifest(
            bundle_dir=self.bundle_dir,
            bundle_version=self.bundle_version,
            lora_version=12,
        )
        # Write a secret file outside bundles_root to confirm path traversal never reads it
        (self.root / "secret.txt").write_text("TOP_SECRET_OUTSIDE_BUNDLE\n", encoding="utf-8")

        self.prefs_path = self.root / "data" / "preferences.jsonl"
        self.server = create_ota_server(
            host="127.0.0.1",
            port=0,
            bundles_root=self.bundles_root,
            preferences_path=self.prefs_path,
            quiet=True,
        )
        self.port = self.server.server_address[1]
        self._thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self._thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self._thread.join(timeout=5.0)
        self._tmp.cleanup()

    def _request(
        self,
        method: str,
        path: str,
        *,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, str], bytes]:
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5.0)
        try:
            req_headers = dict(headers or {})
            if body is not None and "Content-Length" not in req_headers:
                req_headers["Content-Length"] = str(len(body))
            conn.request(method, path, body=body, headers=req_headers)
            resp = conn.getresponse()
            data = resp.read()
            resp_headers = {k.lower(): v for k, v in resp.getheaders()}
            return resp.status, resp_headers, data
        finally:
            conn.close()

    def test_tailscale_host_helper(self) -> None:
        self.assertTrue(is_tailscale_or_loopback_host("127.0.0.1"))
        self.assertTrue(is_tailscale_or_loopback_host("100.101.102.103"))
        self.assertTrue(is_tailscale_or_loopback_host("fd7a:115c:a1e0::1"))
        self.assertFalse(is_tailscale_or_loopback_host("0.0.0.0"))
        self.assertFalse(is_tailscale_or_loopback_host("8.8.8.8"))

    def test_health_manifest_preferences_and_range_downloads(self) -> None:
        # 1. GET /health initial state
        status, _, body = self._request("GET", "/health")
        self.assertEqual(status, 200)
        health = json.loads(body.decode("utf-8"))
        self.assertEqual(health["lora_version"], 12)
        self.assertEqual(health["bundle_version"], self.bundle_version)
        self.assertEqual(health["pending_preferences"], 0)

        # 2. GET /v1/bundles/latest/manifest.json
        status, _, body = self._request("GET", "/v1/bundles/latest/manifest.json")
        self.assertEqual(status, 200)
        fetched_manifest = json.loads(body.decode("utf-8"))
        validate_bundle_manifest(fetched_manifest)
        self.assertEqual(fetched_manifest, self.manifest)

        # 3. POST /v1/preferences with 2 valid NDJSON records
        valid_ndjson = (
            json.dumps(_make_valid_preference(0))
            + "\n"
            + json.dumps(_make_valid_preference(1, user_action="PHONEME_OVERRIDE"))
            + "\n"
        ).encode("utf-8")
        status, _, body = self._request(
            "POST",
            "/v1/preferences",
            body=valid_ndjson,
            headers={"Content-Type": "application/x-ndjson"},
        )
        self.assertEqual(status, 200)
        post_resp = json.loads(body.decode("utf-8"))
        self.assertEqual(post_resp["accepted_count"], 2)
        self.assertEqual(post_resp["pending_preferences"], 2)

        # Verify persisted file parses cleanly
        loaded = load_preferences_jsonl(self.prefs_path)
        self.assertEqual(len(loaded), 2)

        # Health now reflects pending_preferences == 2
        status, _, body = self._request("GET", "/health")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body.decode("utf-8"))["pending_preferences"], 2)

        # 4. POST /v1/preferences with 1 valid + 1 malformed line MUST return 400 and append ZERO
        bad_batch = (
            json.dumps(_make_valid_preference(2))
            + "\n"
            + json.dumps(dict(_make_valid_preference(3), user_gap_scale=2.5))
            + "\n"
        ).encode("utf-8")
        status, _, _ = self._request("POST", "/v1/preferences", body=bad_batch)
        self.assertEqual(status, 400)
        self.assertEqual(len(load_preferences_jsonl(self.prefs_path)), 2)

        # 5. Full artifact download
        lora_name = "voice_lora_v012.gguf"
        lora_bytes = self.files[lora_name]
        artifact_url = f"/v1/bundles/{self.bundle_version}/{lora_name}"

        status, hdrs, body = self._request("GET", artifact_url)
        self.assertEqual(status, 200)
        self.assertEqual(hdrs.get("accept-ranges"), "bytes")
        self.assertEqual(int(hdrs["content-length"]), len(lora_bytes))
        self.assertEqual(body, lora_bytes)

        # 6. Partial resume download: Range: bytes=10-29 -> 206 Partial Content
        status, hdrs, body = self._request(
            "GET",
            artifact_url,
            headers={"Range": "bytes=10-29"},
        )
        self.assertEqual(status, 206)
        self.assertEqual(hdrs.get("accept-ranges"), "bytes")
        self.assertEqual(
            hdrs.get("content-range"), f"bytes 10-29/{len(lora_bytes)}"
        )
        self.assertEqual(int(hdrs["content-length"]), 20)
        self.assertEqual(body, lora_bytes[10:30])

        # 7. Open-ended and suffix Range requests
        status, hdrs, body = self._request(
            "GET",
            artifact_url,
            headers={"Range": f"bytes={len(lora_bytes) - 16}-"},
        )
        self.assertEqual(status, 206)
        self.assertEqual(body, lora_bytes[-16:])

        status, hdrs, body = self._request(
            "GET",
            artifact_url,
            headers={"Range": "bytes=-12"},
        )
        self.assertEqual(status, 206)
        self.assertEqual(body, lora_bytes[-12:])

        # 8. Out-of-bounds Range -> 416 Range Not Satisfiable
        status, hdrs, _ = self._request(
            "GET",
            artifact_url,
            headers={"Range": "bytes=999999-1000000"},
        )
        self.assertEqual(status, 416)
        self.assertEqual(hdrs.get("content-range"), f"bytes */{len(lora_bytes)}")

        # 9. HEAD request returns Content-Length & Accept-Ranges with empty body
        status, hdrs, body = self._request("HEAD", artifact_url)
        self.assertEqual(status, 200)
        self.assertEqual(hdrs.get("accept-ranges"), "bytes")
        self.assertEqual(int(hdrs["content-length"]), len(lora_bytes))
        self.assertEqual(body, b"")

    def test_concurrent_preference_posts_are_atomic_and_lossless(self) -> None:
        errors: list[Exception] = []

        def worker(worker_idx: int) -> None:
            try:
                payload = (
                    json.dumps(_make_valid_preference(100 + worker_idx * 2))
                    + "\n"
                    + json.dumps(_make_valid_preference(101 + worker_idx * 2))
                    + "\n"
                ).encode("utf-8")
                status, _, _ = self._request("POST", "/v1/preferences", body=payload)
                if status != 200:
                    errors.append(AssertionError(f"worker {worker_idx} got status {status}"))
            except Exception as exc:
                errors.append(exc)

        threads = [threading.Thread(target=worker, args=(i,)) for i in range(6)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=10.0)

        self.assertEqual(errors, [])
        records = load_preferences_jsonl(self.prefs_path)
        self.assertEqual(len(records), 12)

    def test_path_traversal_attempts_rejected_with_403_or_404(self) -> None:
        traversal_paths = [
            f"/v1/bundles/{self.bundle_version}/../../secret.txt",
            f"/v1/bundles/{self.bundle_version}/%2e%2e/%2e%2e/secret.txt",
            f"/v1/bundles/{self.bundle_version}/..%2f..%2fsecret.txt",
            "/v1/bundles/..%2f/secret.txt",
            f"/v1/bundles/{self.bundle_version}/..\\..\\secret.txt",
            f"/v1/bundles/{self.bundle_version}/does_not_exist.gguf",
        ]
        for path in traversal_paths:
            status, _, body = self._request("GET", path)
            self.assertIn(
                status,
                (403, 404),
                f"Expected 403/404 for {path!r}, got {status}",
            )
            self.assertNotIn(b"TOP_SECRET_OUTSIDE_BUNDLE", body)

        # Symlink escaping bundle_dir MUST also be rejected with 403
        escape_link = self.bundle_dir / "escape_link.gguf"
        escape_link.symlink_to(self.root / "secret.txt")
        status, _, body = self._request(
            "GET", f"/v1/bundles/{self.bundle_version}/escape_link.gguf"
        )
        self.assertEqual(status, 403)
        self.assertNotIn(b"TOP_SECRET_OUTSIDE_BUNDLE", body)


if __name__ == "__main__":
    unittest.main()
