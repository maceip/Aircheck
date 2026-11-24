"""Automated Test Suite for OTA v001 Bundle Packaging & Live HTTP Verification.

Covers:
  1. Isolated unit tests in temporary directories:
     - Hardlink staging (zero multi-GB disk duplication, identical inode)
     - Deterministic `sherpa_ctc_aligner.tar.gz` archive packing (`model.int8.onnx` + `tokens.txt`)
     - Constitutional Law 2 fail-fast validation (rejects missing/corrupt GGUF/TFL3/PCM/centroid/lexicon)
     - Strict `ota_bundle_manifest.schema.json` validation (10 roles, 44.1 kHz, lora_version=1, <=9500 MB)
     - Live `ota_server.py` HTTP verification on ephemeral port (`GET /health`, manifest, `206 Partial Content` Range)
  2. Real end-to-end verification of `/Users/mac/Downloads/voicestudio-models/bundles/v001/`
     and live `ota_server.py` serving the real 10-artifact `v001` bundle.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import struct
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

DAEMON_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = DAEMON_DIR.parents[1]
if str(DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(DAEMON_DIR))

from package_ota_v001 import (  # noqa: E402
    CANONICAL_FILENAMES,
    DEFAULT_BUNDLE_VERSION,
    DEFAULT_LORA_VERSION,
    DEFAULT_MODELS_ROOT,
    DEFAULT_SCHEMA_PATH,
    REQUIRED_SHERPA_MEMBERS,
    assemble_ota_v001_bundle,
    link_or_copy_artifact,
    pack_sherpa_ctc_tarball,
    validate_artifact_payloads,
    validate_manifest_against_schema,
    verify_live_ota_server,
)
from tinker_voice_daemon import (  # noqa: E402
    FOREGROUND_RAM_CEILING_MB,
    SAMPLE_RATE_HZ,
    VALID_ARTIFACT_ROLES,
    compute_file_sha256,
)


def _make_unit_centroid_bytes() -> bytes:
    val = 1.0 / math.sqrt(256.0)
    return struct.pack("<256f", *([val] * 256))


def _create_mock_models_tree(root: Path, *, lora_version: int = 1) -> Path:
    """Creates a realistic 10-artifact directory layout under a temporary directory for unit tests."""
    (root / "s2-pro").mkdir(parents=True)
    (root / "s2-pro" / "s2-pro.Q4_K_M.gguf").write_bytes(b"GGUF" + b"\x03\x00\x00\x00" + b"BASE" * 2048)

    sherpa_dir = root / "sherpa-ctc" / "sherpa-onnx-nemo-ctc-en-citrinet-512"
    sherpa_dir.mkdir(parents=True)
    (sherpa_dir / "model.int8.onnx").write_bytes(b"\x08\x07\x12\x08citrinet" * 512)
    (sherpa_dir / "tokens.txt").write_text("<blk> 0\n▁hello 1\n▁world 2\n", encoding="utf-8")

    (root / "wespeaker").mkdir(parents=True)
    (root / "wespeaker" / "wespeaker_en_voxceleb_resnet34.onnx").write_bytes(
        b"\x08\x07\x12\x09wespeaker" * 512
    )

    (root / "dnsmos").mkdir(parents=True)
    (root / "dnsmos" / "model_v8.onnx").write_bytes(b"\x08\x07\x12\x06dnsmos" * 256)

    bundle_dir = root / "bundles" / "v001"
    bundle_dir.mkdir(parents=True)
    # Write > 65,536 bytes for voice_lora so Range: bytes=0-65535 exercises full 64 KiB partial read
    lora_payload = b"GGUF" + b"\x03\x00\x00\x00" + (bytes(range(256)) * 512)
    (bundle_dir / f"voice_lora_v{lora_version:03d}.gguf").write_bytes(lora_payload)
    (bundle_dir / "codec_decoder_fp16.tflite").write_bytes(b"\x1c\x00\x00\x00TFL3" + b"\xab" * 4096)
    (bundle_dir / "master_centroid.bin").write_bytes(_make_unit_centroid_bytes())
    (bundle_dir / "room_ir_44k.pcm").write_bytes(b"\x00\x00\x80\x3f" * 544)  # 2,176 B (64B aligned)
    (bundle_dir / "room_tone_44k.pcm").write_bytes(b"\x00\x00\x80\x38" * 1024)  # 4,096 B (64B aligned)
    (bundle_dir / "lexicon.json").write_text(
        json.dumps({"Aircheck": "ˈɛɚ tʃ ɛ k", "VoiceStudio": "v ˈɔɪ s s t ˈuː d i oʊ"}),
        encoding="utf-8",
    )
    return bundle_dir


class TestOtaV001PackagerUnit(unittest.TestCase):
    def test_assemble_bundle_hardlinks_packs_validates_and_serves_live_http(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            mroot = Path(tmp) / "models"
            bdir = _create_mock_models_tree(mroot, lora_version=1)

            summary = assemble_ota_v001_bundle(
                models_root=mroot,
                bundle_version="v001",
                lora_version=1,
                schema_path=DEFAULT_SCHEMA_PATH,
            )

            self.assertEqual(summary["artifact_count"], 10)
            self.assertEqual(summary["sample_rate_hz"], SAMPLE_RATE_HZ)
            self.assertEqual(summary["lora_version"], 1)
            self.assertLessEqual(summary["total_estimated_ram_mb"], FOREGROUND_RAM_CEILING_MB)

            # Verify large static models are hardlinked (same inode, zero extra disk duplication)
            for src_rel, dst_name in (
                ("s2-pro/s2-pro.Q4_K_M.gguf", "s2_pro_4b_q4_k_m.gguf"),
                ("wespeaker/wespeaker_en_voxceleb_resnet34.onnx", "wespeaker_resnet34.onnx"),
                ("dnsmos/model_v8.onnx", "dnsmos_p808.onnx"),
            ):
                src = mroot / src_rel
                dst = bdir / dst_name
                self.assertTrue(os.path.samefile(src, dst), f"Expected hardlink for {dst_name}")
                self.assertEqual(summary["staging_actions"][dst_name], "hardlinked")

            # Verify deterministic sherpa_ctc_aligner.tar.gz contents & idempotency
            tar_path = bdir / "sherpa_ctc_aligner.tar.gz"
            sha_first = compute_file_sha256(tar_path)
            with tarfile.open(tar_path, "r:gz") as tf:
                members = sorted(m.name for m in tf.getmembers() if m.isfile())
            self.assertEqual(members, sorted(REQUIRED_SHERPA_MEMBERS))

            repack_info = pack_sherpa_ctc_tarball(
                mroot / "sherpa-ctc" / "sherpa-onnx-nemo-ctc-en-citrinet-512",
                tar_path,
                force=True,
            )
            self.assertEqual(repack_info["sha256"], sha_first)

            # Verify second assemble run reuses hardlinks & tarball cleanly
            summary2 = assemble_ota_v001_bundle(
                models_root=mroot,
                bundle_version="v001",
                lora_version=1,
                schema_path=DEFAULT_SCHEMA_PATH,
            )
            self.assertEqual(summary2["staging_actions"]["s2_pro_4b_q4_k_m.gguf"], "existing_hardlink")
            self.assertEqual(summary2["staging_actions"]["sherpa_ctc_aligner.tar.gz"], "reused")

            # Verify live ota_server.py HTTP endpoints on ephemeral port
            http_res = verify_live_ota_server(
                bundles_root=mroot / "bundles",
                bundle_version="v001",
                lora_version=1,
            )
            self.assertEqual(http_res["health_status"], 200)
            self.assertEqual(http_res["health"]["bundle_version"], "v001")
            self.assertEqual(http_res["health"]["lora_version"], 1)
            self.assertEqual(http_res["manifest_status"], 200)
            self.assertEqual(http_res["manifest_artifact_count"], 10)
            self.assertEqual(http_res["lora_range_status"], 206)
            self.assertEqual(http_res["lora_range_bytes"], 65_536)
            self.assertEqual(http_res["base_gguf_head_status"], 200)
            self.assertEqual(http_res["centroid_status"], 200)

    def test_validate_artifact_payloads_rejects_corrupt_or_missing_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            mroot = Path(tmp) / "models"
            bdir = _create_mock_models_tree(mroot, lora_version=1)
            assemble_ota_v001_bundle(models_root=mroot, bundle_version="v001", lora_version=1)
            validate_artifact_payloads(bdir, lora_version=1)

            # 1. Corrupt GGUF magic header
            orig_lora = (bdir / "voice_lora_v001.gguf").read_bytes()
            (bdir / "voice_lora_v001.gguf").write_bytes(b"NOPE" + orig_lora[4:])
            with self.assertRaises(ValueError):
                validate_artifact_payloads(bdir, lora_version=1)
            (bdir / "voice_lora_v001.gguf").write_bytes(orig_lora)

            # 2. Unaligned PCM file
            orig_ir = (bdir / "room_ir_44k.pcm").read_bytes()
            (bdir / "room_ir_44k.pcm").write_bytes(orig_ir + b"\x00\x00\x00\x00")
            with self.assertRaises(ValueError):
                validate_artifact_payloads(bdir, lora_version=1)
            (bdir / "room_ir_44k.pcm").write_bytes(orig_ir)

            # 3. Missing source in link_or_copy_artifact
            with self.assertRaises(FileNotFoundError):
                link_or_copy_artifact(mroot / "does_not_exist.onnx", bdir / "x.onnx")


class TestRealOtaV001BundleOnDisk(unittest.TestCase):
    """Verifies the real packaged OTA `v001` bundle under `/Users/mac/Downloads/voicestudio-models/bundles/v001/`."""

    @unittest.skipUnless(
        (DEFAULT_MODELS_ROOT / "bundles" / DEFAULT_BUNDLE_VERSION / "bundle_manifest.json").is_file(),
        "Real OTA v001 bundle not present on this host",
    )
    def test_real_v001_bundle_manifest_all_10_artifacts_and_live_ota_http(self) -> None:
        bdir = DEFAULT_MODELS_ROOT / "bundles" / DEFAULT_BUNDLE_VERSION
        manifest_path = bdir / "bundle_manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

        # 1. Strictly validate against docs/swarm/schemas/ota_bundle_manifest.schema.json
        validate_manifest_against_schema(manifest, schema_path=DEFAULT_SCHEMA_PATH)
        self.assertEqual(manifest["schema_version"], 1)
        self.assertEqual(manifest["bundle_version"], "v001")
        self.assertEqual(manifest["lora_version"], DEFAULT_LORA_VERSION)
        self.assertEqual(manifest["sample_rate_hz"], 44_100)
        self.assertLessEqual(manifest["foreground_ram_ceiling_mb"], 9_500)

        artifacts = manifest["artifacts"]
        self.assertEqual(len(artifacts), 10)
        roles = {a["role"] for a in artifacts}
        self.assertEqual(roles, VALID_ARTIFACT_ROLES)

        total_ram = sum(int(a["estimated_ram_mb"]) for a in artifacts)
        self.assertLessEqual(total_ram, 9_500)

        # 2. Verify every canonical filename, payload header, and on-disk size/SHA-256
        validate_artifact_payloads(bdir, lora_version=DEFAULT_LORA_VERSION)
        by_role = {a["role"]: a for a in artifacts}
        for role, expected_name in CANONICAL_FILENAMES.items():
            art = by_role[role]
            self.assertEqual(art["filename"], expected_name)
            fpath = bdir / expected_name
            self.assertTrue(fpath.is_file(), f"Missing real artifact {fpath}")
            self.assertEqual(fpath.stat().st_size, art["size_bytes"])
            # Fast-verify small/medium artifacts fully and base GGUF header + hardlink inode
            if role == "s2_pro_base_gguf":
                src_base = DEFAULT_MODELS_ROOT / "s2-pro" / "s2-pro.Q4_K_M.gguf"
                self.assertTrue(os.path.samefile(src_base, fpath))
            else:
                self.assertEqual(compute_file_sha256(fpath), art["sha256"])

        # 3. Verify real sherpa_ctc_aligner.tar.gz contains exact source model.int8.onnx & tokens.txt
        sherpa_src = DEFAULT_MODELS_ROOT / "sherpa-ctc" / "sherpa-onnx-nemo-ctc-en-citrinet-512"
        with tarfile.open(bdir / "sherpa_ctc_aligner.tar.gz", "r:gz") as tf:
            names = {m.name for m in tf.getmembers() if m.isfile()}
            self.assertEqual(names, set(REQUIRED_SHERPA_MEMBERS))
            for member_name in REQUIRED_SHERPA_MEMBERS:
                src_sha = compute_file_sha256(sherpa_src / member_name)
                extracted = tf.extractfile(member_name)
                self.assertIsNotNone(extracted)
                tar_sha = hashlib.sha256(extracted.read()).hexdigest()
                self.assertEqual(tar_sha, src_sha)

        # 4. Live HTTP verification against real ota_server.py on ephemeral port
        # Point bundles_root directly at v001 dir so this v001 contract test stays deterministic
        # even when newer bundles (e.g. v002) also live alongside v001 under bundles/.
        http_res = verify_live_ota_server(
            bundles_root=bdir,
            bundle_version="v001",
            lora_version=DEFAULT_LORA_VERSION,
            range_end_byte=65_535,
        )
        self.assertEqual(http_res["health_status"], 200)
        self.assertEqual(http_res["health"]["status"], "ok")
        self.assertEqual(http_res["health"]["bundle_version"], "v001")
        self.assertEqual(http_res["health"]["lora_version"], 1)
        self.assertEqual(http_res["manifest_status"], 200)
        self.assertEqual(http_res["manifest_artifact_count"], 10)
        self.assertEqual(http_res["lora_range_status"], 206)
        self.assertEqual(http_res["lora_range_bytes"], 65_536)
        self.assertEqual(http_res["base_gguf_head_status"], 200)
        self.assertEqual(http_res["centroid_status"], 200)


if __name__ == "__main__":
    unittest.main()
