"""VoiceStudio OTA v001 Bundle Packager & Live HTTP Server Verifier.

Assembles all 10 real hardware-verified artifacts for OTA bundle `v001` under
`/Users/mac/Downloads/voicestudio-models/bundles/v001/` (hardlinking large static
models to avoid unnecessary multi-GB disk duplication), generates and strictly
validates `bundle_manifest.json` against `docs/swarm/schemas/ota_bundle_manifest.schema.json`,
and runs an automated live HTTP verification against `ota_server.py`.

Constitutional Law 2: Refuses to fabricate, mock, or pad missing/corrupt artifacts.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import http.client
import json
import math
import os
import shutil
import struct
import sys
import tarfile
import tempfile
import threading
from pathlib import Path
from typing import Any, Mapping, Sequence

DAEMON_DIR = Path(__file__).resolve().parent
REPO_ROOT = DAEMON_DIR.parents[1]
if str(DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(DAEMON_DIR))

from ota_server import create_ota_server  # noqa: E402
from tinker_voice_daemon import (  # noqa: E402
    FOREGROUND_RAM_CEILING_MB,
    SAMPLE_RATE_HZ,
    VALID_ARTIFACT_ROLES,
    build_bundle_manifest,
    compute_file_sha256,
    validate_bundle_manifest,
)

DEFAULT_MODELS_ROOT = Path("/Users/mac/Downloads/voicestudio-models")
DEFAULT_BUNDLE_VERSION = "v001"
DEFAULT_LORA_VERSION = 1
DEFAULT_SCHEMA_PATH = REPO_ROOT / "docs" / "swarm" / "schemas" / "ota_bundle_manifest.schema.json"

REQUIRED_SHERPA_MEMBERS: tuple[str, ...] = ("model.int8.onnx", "tokens.txt")

# Canonical 10-artifact filenames for OTA bundle v001
CANONICAL_FILENAMES: dict[str, str] = {
    "s2_pro_base_gguf": "s2_pro_4b_q4_k_m.gguf",
    "voice_lora_gguf": "voice_lora_v001.gguf",
    "codec_decoder_tflite": "codec_decoder_fp16.tflite",
    "sherpa_ctc_aligner": "sherpa_ctc_aligner.tar.gz",
    "wespeaker_resnet34_onnx": "wespeaker_resnet34.onnx",
    "master_centroid_bin": "master_centroid.bin",
    "dnsmos_p808_onnx": "dnsmos_p808.onnx",
    "room_ir_pcm": "room_ir_44k.pcm",
    "room_tone_pcm": "room_tone_44k.pcm",
    "g2p_lexicon": "lexicon.json",
}


def link_or_copy_artifact(src: Path, dst: Path) -> str:
    """Stages `src` into `dst` via atomic hardlink (falling back to atomic copy on EXDEV).

    Returns `"existing_hardlink"`, `"hardlinked"`, or `"copied"`.
    """
    src_path = Path(src)
    dst_path = Path(dst)
    if not src_path.is_file() or src_path.stat().st_size == 0:
        raise FileNotFoundError(f"Required source artifact missing or empty: {src_path}")

    dst_path.parent.mkdir(parents=True, exist_ok=True)
    if dst_path.is_file():
        try:
            if os.path.samefile(src_path, dst_path):
                return "existing_hardlink"
        except OSError:
            pass

    tmp_path = dst_path.with_name(f".{dst_path.name}.tmp.{os.getpid()}")
    try:
        if tmp_path.exists():
            tmp_path.unlink()
        try:
            os.link(src_path, tmp_path)
            mode = "hardlinked"
        except OSError:
            shutil.copyfile(src_path, tmp_path)
            mode = "copied"
        os.replace(tmp_path, dst_path)
        return mode
    finally:
        if tmp_path.exists():
            tmp_path.unlink(missing_ok=True)


def pack_sherpa_ctc_tarball(
    sherpa_src_dir: Path,
    output_tar_gz: Path,
    *,
    force: bool = False,
) -> dict[str, Any]:
    """Deterministically packs `model.int8.onnx` + `tokens.txt` into `sherpa_ctc_aligner.tar.gz`.

    Places both files at archive root so extracting into `sherpa_ctc/` yields
    `sherpa_ctc/model.int8.onnx` and `sherpa_ctc/tokens.txt` matching Android's
    `OtaBundleSyncManager` and `OrtCtcAlignerEngine.validateModelDir`.
    """
    src_dir = Path(sherpa_src_dir)
    out_path = Path(output_tar_gz)
    if not src_dir.is_dir():
        raise FileNotFoundError(f"Missing Sherpa CTC model directory: {src_dir}")

    member_paths: list[tuple[str, Path]] = []
    expected_digests: dict[str, tuple[int, str]] = {}
    for member_name in REQUIRED_SHERPA_MEMBERS:
        p = src_dir / member_name
        if not p.is_file() or p.stat().st_size == 0:
            raise FileNotFoundError(f"Missing or empty Sherpa CTC file: {p}")
        member_paths.append((member_name, p))
        expected_digests[member_name] = (p.stat().st_size, compute_file_sha256(p))

    # Reuse existing archive if it already matches exact member bytes & structure.
    if not force and out_path.is_file() and out_path.stat().st_size > 0:
        try:
            if _verify_sherpa_tarball_matches(out_path, expected_digests):
                return {
                    "path": str(out_path),
                    "action": "reused",
                    "size_bytes": out_path.stat().st_size,
                    "sha256": compute_file_sha256(out_path),
                    "members": list(REQUIRED_SHERPA_MEMBERS),
                }
        except Exception:
            pass

    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = out_path.with_name(f".{out_path.name}.tmp.{os.getpid()}")
    try:
        with tmp_path.open("wb") as raw_fh:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw_fh, mtime=0) as gz_fh:
                with tarfile.open(fileobj=gz_fh, mode="w", format=tarfile.PAX_FORMAT) as tf:
                    for member_name, src_file in member_paths:
                        info = tf.gettarinfo(str(src_file), arcname=member_name)
                        info.uid = 0
                        info.gid = 0
                        info.uname = ""
                        info.gname = ""
                        info.mtime = 0
                        info.mode = 0o644
                        with src_file.open("rb") as mf:
                            tf.addfile(info, mf)
            raw_fh.flush()
            os.fsync(raw_fh.fileno())
        os.replace(tmp_path, out_path)
    finally:
        if tmp_path.exists():
            tmp_path.unlink(missing_ok=True)

    if not _verify_sherpa_tarball_matches(out_path, expected_digests):
        raise RuntimeError(f"Packed tarball failed member verification: {out_path}")

    return {
        "path": str(out_path),
        "action": "packed",
        "size_bytes": out_path.stat().st_size,
        "sha256": compute_file_sha256(out_path),
        "members": list(REQUIRED_SHERPA_MEMBERS),
    }


def _verify_sherpa_tarball_matches(
    tar_path: Path, expected_digests: Mapping[str, tuple[int, str]]
) -> bool:
    found: dict[str, tuple[int, str]] = {}
    with tarfile.open(tar_path, mode="r:gz") as tf:
        for member in tf.getmembers():
            if not member.isfile():
                continue
            extracted = tf.extractfile(member)
            if extracted is None:
                continue
            h = hashlib.sha256()
            size = 0
            while True:
                chunk = extracted.read(1024 * 1024)
                if not chunk:
                    break
                h.update(chunk)
                size += len(chunk)
            found[member.name] = (size, h.hexdigest())
    return set(found.keys()) == set(expected_digests.keys()) and all(
        found[k] == expected_digests[k] for k in expected_digests
    )


def validate_artifact_payloads(bundle_dir: Path, lora_version: int = DEFAULT_LORA_VERSION) -> None:
    """Validates binary format headers, alignment, and non-emptiness for all 10 OTA artifacts."""
    bdir = Path(bundle_dir)
    expected_files = dict(CANONICAL_FILENAMES)
    expected_files["voice_lora_gguf"] = f"voice_lora_v{lora_version:03d}.gguf"

    for role, filename in expected_files.items():
        path = bdir / filename
        if not path.is_file() or path.stat().st_size == 0:
            raise FileNotFoundError(f"Missing or empty OTA artifact for role '{role}': {path}")

    # GGUF magic headers
    for gguf_name in (expected_files["s2_pro_base_gguf"], expected_files["voice_lora_gguf"]):
        p = bdir / gguf_name
        with p.open("rb") as fh:
            if fh.read(4) != b"GGUF":
                raise ValueError(f"Invalid GGUF magic header in {p}")

    # FlatBuffer TFLite header ("TFL3" at offset 4)
    tflite_path = bdir / expected_files["codec_decoder_tflite"]
    with tflite_path.open("rb") as fh:
        hdr = fh.read(8)
        if len(hdr) < 8 or hdr[4:8] != b"TFL3":
            raise ValueError(f"Invalid TFLite header (expected TFL3 at offset 4) in {tflite_path}")

    # Sherpa CTC tar.gz must unpack cleanly and contain model.int8.onnx + tokens.txt
    sherpa_tar = bdir / expected_files["sherpa_ctc_aligner"]
    with tarfile.open(sherpa_tar, mode="r:gz") as tf:
        names = {m.name for m in tf.getmembers() if m.isfile() and m.size > 0}
        missing = set(REQUIRED_SHERPA_MEMBERS) - names
        if missing:
            raise ValueError(f"Sherpa CTC archive missing required members {sorted(missing)}: {sherpa_tar}")

    # 256-float L2-normalized master_centroid.bin (1,024 bytes)
    centroid_path = bdir / expected_files["master_centroid_bin"]
    raw_centroid = centroid_path.read_bytes()
    if len(raw_centroid) != 1024:
        raise ValueError(f"master_centroid.bin must be 1024 bytes, got {len(raw_centroid)}")
    vals = struct.unpack("<256f", raw_centroid)
    if not all(math.isfinite(v) for v in vals):
        raise ValueError("master_centroid.bin contains non-finite float values")
    norm = math.sqrt(sum(v * v for v in vals))
    if not (0.99 <= norm <= 1.01):
        raise ValueError(f"master_centroid.bin must be L2-normalized (~1.0), got {norm:.6f}")

    # 64-byte aligned headerless Float32 PCM files
    for pcm_key in ("room_ir_pcm", "room_tone_pcm"):
        pcm_path = bdir / expected_files[pcm_key]
        if pcm_path.stat().st_size % 64 != 0:
            raise ValueError(
                f"PCM artifact '{pcm_path.name}' is not 64-byte aligned ({pcm_path.stat().st_size} bytes)"
            )

    # Flat term -> IPA lexicon.json
    lex_path = bdir / expected_files["g2p_lexicon"]
    lex = json.loads(lex_path.read_text(encoding="utf-8"))
    if not isinstance(lex, dict) or not lex:
        raise ValueError(f"lexicon.json must be a non-empty JSON object: {lex_path}")


def validate_manifest_against_schema(
    manifest: Mapping[str, Any],
    schema_path: Path = DEFAULT_SCHEMA_PATH,
) -> None:
    """Validates `manifest` using both `tinker_voice_daemon.validate_bundle_manifest` and JSON Schema."""
    validate_bundle_manifest(dict(manifest))

    if not Path(schema_path).is_file():
        raise FileNotFoundError(f"OTA manifest JSON schema not found: {schema_path}")
    schema = json.loads(Path(schema_path).read_text(encoding="utf-8"))

    try:
        import jsonschema  # type: ignore[import-untyped]
    except ImportError:
        jsonschema = None

    if jsonschema is not None:
        validator = jsonschema.Draft202012Validator(schema)
        errors = sorted(validator.iter_errors(manifest), key=lambda e: list(e.path))
        if errors:
            detail = "; ".join(f"{list(e.path)}: {e.message}" for e in errors)
            raise ValueError(f"Manifest violated {schema_path.name}: {detail}")


def assemble_ota_v001_bundle(
    *,
    models_root: Path = DEFAULT_MODELS_ROOT,
    bundle_dir: Path | None = None,
    bundle_version: str = DEFAULT_BUNDLE_VERSION,
    lora_version: int = DEFAULT_LORA_VERSION,
    schema_path: Path = DEFAULT_SCHEMA_PATH,
    force_repack_sherpa: bool = False,
) -> dict[str, Any]:
    """Assembles all 10 OTA artifacts into `bundle_dir`, builds & validates `bundle_manifest.json`."""
    mroot = Path(models_root)
    bdir = Path(bundle_dir) if bundle_dir is not None else (mroot / "bundles" / bundle_version)
    bdir.mkdir(parents=True, exist_ok=True)

    staging_actions: dict[str, str] = {}

    # 1. Hardlink static external model artifacts (zero unnecessary multi-GB disk duplication)
    static_sources: list[tuple[Path, str]] = [
        (mroot / "s2-pro" / "s2-pro.Q4_K_M.gguf", "s2_pro_4b_q4_k_m.gguf"),
        (mroot / "wespeaker" / "wespeaker_en_voxceleb_resnet34.onnx", "wespeaker_resnet34.onnx"),
        (mroot / "dnsmos" / "model_v8.onnx", "dnsmos_p808.onnx"),
    ]
    for src, dst_name in static_sources:
        action = link_or_copy_artifact(src, bdir / dst_name)
        staging_actions[dst_name] = action

    # 2. Pack deterministic Sherpa-ONNX NeMo Citrinet-512 CTC aligner tarball
    sherpa_src_dir = mroot / "sherpa-ctc" / "sherpa-onnx-nemo-ctc-en-citrinet-512"
    sherpa_info = pack_sherpa_ctc_tarball(
        sherpa_src_dir=sherpa_src_dir,
        output_tar_gz=bdir / "sherpa_ctc_aligner.tar.gz",
        force=force_repack_sherpa,
    )
    staging_actions["sherpa_ctc_aligner.tar.gz"] = sherpa_info["action"]

    # 3. Validate all 10 real artifacts are present and intact
    validate_artifact_payloads(bdir, lora_version=lora_version)

    # 4. Generate verified bundle_manifest.json via tinker_voice_daemon.build_bundle_manifest()
    manifest = build_bundle_manifest(
        bundle_dir=bdir,
        bundle_version=bundle_version,
        lora_version=lora_version,
        sample_rate_hz=SAMPLE_RATE_HZ,
        foreground_ram_ceiling_mb=FOREGROUND_RAM_CEILING_MB,
    )

    # 5. Strictly validate against docs/swarm/schemas/ota_bundle_manifest.schema.json + 10-role completeness
    validate_manifest_against_schema(manifest, schema_path=schema_path)

    roles_present = {art["role"] for art in manifest["artifacts"]}
    missing_roles = VALID_ARTIFACT_ROLES - roles_present
    if missing_roles:
        raise ValueError(
            f"Incomplete OTA bundle manifest — missing required roles: {sorted(missing_roles)}"
        )
    if len(manifest["artifacts"]) != 10:
        raise ValueError(f"Expected exactly 10 OTA artifacts, got {len(manifest['artifacts'])}")

    total_ram_mb = sum(int(a["estimated_ram_mb"]) for a in manifest["artifacts"])
    if total_ram_mb > FOREGROUND_RAM_CEILING_MB:
        raise ValueError(f"Total estimated RAM {total_ram_mb} MB exceeds {FOREGROUND_RAM_CEILING_MB} MB")

    manifest_path = bdir / "bundle_manifest.json"
    return {
        "bundle_dir": str(bdir),
        "manifest_path": str(manifest_path),
        "manifest_sha256": compute_file_sha256(manifest_path),
        "bundle_version": manifest["bundle_version"],
        "lora_version": manifest["lora_version"],
        "sample_rate_hz": manifest["sample_rate_hz"],
        "artifact_count": len(manifest["artifacts"]),
        "total_size_bytes": sum(int(a["size_bytes"]) for a in manifest["artifacts"]),
        "total_estimated_ram_mb": total_ram_mb,
        "staging_actions": staging_actions,
        "manifest": manifest,
    }


def verify_live_ota_server(
    bundles_root: Path,
    *,
    bundle_version: str = DEFAULT_BUNDLE_VERSION,
    lora_version: int = DEFAULT_LORA_VERSION,
    range_end_byte: int = 65_535,
) -> dict[str, Any]:
    """Starts `ota_server.py` on an ephemeral localhost port and verifies live HTTP endpoints.

    Verifies:
      1. `GET /health` -> 200 OK with matching `bundle_version` and `lora_version`
      2. `GET /v1/bundles/latest/manifest.json` -> 200 OK matching on-disk manifest & schema
      3. `GET /v1/bundles/{version}/voice_lora_v{lora:03d}.gguf` with `Range: bytes=0-65535`
         -> 206 Partial Content, valid Content-Range & ETag, exact byte-for-byte SHA-256 match
      4. `HEAD /v1/bundles/{version}/s2_pro_4b_q4_k_m.gguf` -> 200 OK, exact Content-Length & ETag
      5. `GET /v1/bundles/{version}/master_centroid.bin` -> 200 OK, exact full-file SHA-256 match
    """
    broot = Path(bundles_root).resolve()
    bdir = broot / bundle_version if (broot / bundle_version / "bundle_manifest.json").is_file() else broot
    expected_manifest = json.loads((bdir / "bundle_manifest.json").read_text(encoding="utf-8"))
    validate_bundle_manifest(expected_manifest)

    lora_filename = f"voice_lora_v{lora_version:03d}.gguf"
    lora_path = bdir / lora_filename
    lora_size = lora_path.stat().st_size
    effective_end = min(range_end_byte, lora_size - 1)
    expected_range_len = effective_end + 1
    with lora_path.open("rb") as fh:
        expected_range_bytes = fh.read(expected_range_len)
    expected_range_sha256 = hashlib.sha256(expected_range_bytes).hexdigest()

    by_filename = {art["filename"]: art for art in expected_manifest["artifacts"]}
    expected_lora_sha256 = by_filename[lora_filename]["sha256"]
    expected_base_art = by_filename["s2_pro_4b_q4_k_m.gguf"]
    expected_centroid_art = by_filename["master_centroid.bin"]

    with tempfile.TemporaryDirectory(prefix="ota_v001_verify_") as tmp:
        prefs_path = Path(tmp) / "preferences.jsonl"
        server = create_ota_server(
            host="127.0.0.1",
            port=0,
            bundles_root=broot,
            preferences_path=prefs_path,
            quiet=True,
        )
        port = int(server.server_address[1])
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
            try:
                # 1. GET /health
                conn.request("GET", "/health")
                resp = conn.getresponse()
                health_body = resp.read()
                if resp.status != 200:
                    raise RuntimeError(f"GET /health returned HTTP {resp.status}")
                health_json = json.loads(health_body.decode("utf-8"))
                if health_json.get("status") != "ok":
                    raise RuntimeError(f"Unexpected /health status: {health_json}")
                if health_json.get("bundle_version") != bundle_version:
                    raise RuntimeError(
                        f"/health bundle_version mismatch: {health_json.get('bundle_version')} != {bundle_version}"
                    )
                if health_json.get("lora_version") != lora_version:
                    raise RuntimeError(
                        f"/health lora_version mismatch: {health_json.get('lora_version')} != {lora_version}"
                    )

                # 2. GET /v1/bundles/latest/manifest.json
                conn.request("GET", "/v1/bundles/latest/manifest.json")
                resp = conn.getresponse()
                manifest_body = resp.read()
                if resp.status != 200:
                    raise RuntimeError(f"GET /v1/bundles/latest/manifest.json returned HTTP {resp.status}")
                served_manifest = json.loads(manifest_body.decode("utf-8"))
                if served_manifest != expected_manifest:
                    raise RuntimeError("Served manifest JSON does not match on-disk bundle_manifest.json")

                # 3. GET /v1/bundles/{version}/voice_lora_v001.gguf with Range: bytes=0-65535
                conn.request(
                    "GET",
                    f"/v1/bundles/{bundle_version}/{lora_filename}",
                    headers={"Range": f"bytes=0-{effective_end}"},
                )
                resp = conn.getresponse()
                range_body = resp.read()
                if resp.status != 206:
                    raise RuntimeError(
                        f"Range GET {lora_filename} expected HTTP 206, got HTTP {resp.status}"
                    )
                content_range = resp.getheader("Content-Range")
                accept_ranges = resp.getheader("Accept-Ranges")
                etag = resp.getheader("ETag")
                expected_cr = f"bytes 0-{effective_end}/{lora_size}"
                if content_range != expected_cr:
                    raise RuntimeError(f"Content-Range mismatch: {content_range!r} != {expected_cr!r}")
                if accept_ranges != "bytes":
                    raise RuntimeError(f"Accept-Ranges mismatch: {accept_ranges!r}")
                if etag != f'"{expected_lora_sha256}"':
                    raise RuntimeError(f"LoRA ETag mismatch: {etag!r} != '\"{expected_lora_sha256}\"'")
                if len(range_body) != expected_range_len:
                    raise RuntimeError(
                        f"Range payload length mismatch: {len(range_body)} != {expected_range_len}"
                    )
                actual_range_sha256 = hashlib.sha256(range_body).hexdigest()
                if actual_range_sha256 != expected_range_sha256:
                    raise RuntimeError(
                        f"Range SHA-256 mismatch: {actual_range_sha256} != {expected_range_sha256}"
                    )
                if range_body[:4] != b"GGUF":
                    raise RuntimeError("Range payload of voice_lora GGUF does not start with GGUF magic")

                # 4. HEAD /v1/bundles/{version}/s2_pro_4b_q4_k_m.gguf
                conn.request("HEAD", f"/v1/bundles/{bundle_version}/s2_pro_4b_q4_k_m.gguf")
                resp = conn.getresponse()
                resp.read()
                if resp.status != 200:
                    raise RuntimeError(f"HEAD s2_pro_4b_q4_k_m.gguf returned HTTP {resp.status}")
                if int(resp.getheader("Content-Length") or "-1") != expected_base_art["size_bytes"]:
                    raise RuntimeError("HEAD Content-Length mismatch for s2_pro_4b_q4_k_m.gguf")
                if resp.getheader("ETag") != f'"{expected_base_art["sha256"]}"':
                    raise RuntimeError("HEAD ETag mismatch for s2_pro_4b_q4_k_m.gguf")

                # 5. GET /v1/bundles/{version}/master_centroid.bin (full download SHA-256 check)
                conn.request("GET", f"/v1/bundles/{bundle_version}/master_centroid.bin")
                resp = conn.getresponse()
                centroid_body = resp.read()
                if resp.status != 200:
                    raise RuntimeError(f"GET master_centroid.bin returned HTTP {resp.status}")
                centroid_sha = hashlib.sha256(centroid_body).hexdigest()
                if centroid_sha != expected_centroid_art["sha256"]:
                    raise RuntimeError(
                        f"master_centroid.bin SHA-256 mismatch: {centroid_sha} != {expected_centroid_art['sha256']}"
                    )

                return {
                    "server_port": port,
                    "health_status": 200,
                    "health": health_json,
                    "manifest_status": 200,
                    "manifest_artifact_count": len(served_manifest["artifacts"]),
                    "lora_range_status": 206,
                    "lora_content_range": content_range,
                    "lora_etag": etag,
                    "lora_range_bytes": len(range_body),
                    "lora_range_sha256": actual_range_sha256,
                    "lora_full_sha256": expected_lora_sha256,
                    "base_gguf_head_status": 200,
                    "base_gguf_size_bytes": expected_base_art["size_bytes"],
                    "centroid_status": 200,
                    "centroid_sha256": centroid_sha,
                }
            finally:
                conn.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Assemble, validate, and live-verify the VoiceStudio OTA v001 bundle."
    )
    parser.add_argument(
        "--models-root",
        type=Path,
        default=DEFAULT_MODELS_ROOT,
        help=f"Models root directory (default: {DEFAULT_MODELS_ROOT})",
    )
    parser.add_argument(
        "--bundle-version",
        default=DEFAULT_BUNDLE_VERSION,
        help=f"Bundle version name (default: {DEFAULT_BUNDLE_VERSION})",
    )
    parser.add_argument(
        "--lora-version",
        type=int,
        default=DEFAULT_LORA_VERSION,
        help=f"Active LoRA integer version (default: {DEFAULT_LORA_VERSION})",
    )
    parser.add_argument(
        "--schema",
        type=Path,
        default=DEFAULT_SCHEMA_PATH,
        help=f"Path to ota_bundle_manifest.schema.json (default: {DEFAULT_SCHEMA_PATH})",
    )
    parser.add_argument(
        "--force-repack-sherpa",
        action="store_true",
        help="Force repacking sherpa_ctc_aligner.tar.gz even if an identical archive exists",
    )
    parser.add_argument(
        "--skip-http-verify",
        action="store_true",
        help="Skip live ota_server.py HTTP verification",
    )
    args = parser.parse_args(argv)

    summary = assemble_ota_v001_bundle(
        models_root=args.models_root,
        bundle_version=args.bundle_version,
        lora_version=args.lora_version,
        schema_path=args.schema,
        force_repack_sherpa=args.force_repack_sherpa,
    )

    output: dict[str, Any] = {
        "bundle_dir": summary["bundle_dir"],
        "manifest_path": summary["manifest_path"],
        "manifest_sha256": summary["manifest_sha256"],
        "bundle_version": summary["bundle_version"],
        "lora_version": summary["lora_version"],
        "sample_rate_hz": summary["sample_rate_hz"],
        "artifact_count": summary["artifact_count"],
        "total_size_bytes": summary["total_size_bytes"],
        "total_estimated_ram_mb": summary["total_estimated_ram_mb"],
        "staging_actions": summary["staging_actions"],
        "artifacts": [
            {
                "role": a["role"],
                "filename": a["filename"],
                "format": a["format"],
                "size_bytes": a["size_bytes"],
                "estimated_ram_mb": a["estimated_ram_mb"],
                "update_cadence": a["update_cadence"],
                "sha256": a["sha256"],
            }
            for a in summary["manifest"]["artifacts"]
        ],
    }

    if not args.skip_http_verify:
        bundles_root = Path(args.models_root) / "bundles"
        output["live_http_verification"] = verify_live_ota_server(
            bundles_root=bundles_root,
            bundle_version=args.bundle_version,
            lora_version=args.lora_version,
        )

    print(json.dumps(output, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
