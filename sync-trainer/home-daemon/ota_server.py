"""Pure-stdlib Tailscale/WireGuard OTA, Preference Telemetry & Render Bridge HTTP Server.

Endpoints:
  GET  /health                              -> JSON {status, lora_version, bundle_version, pending_preferences, render_bridge, active_render_jobs}
  GET  /v1/bundles/latest/manifest.json     -> Verified bundle_manifest.json
  GET  /v1/bundles/{version}/manifest.json  -> Verified manifest for a specific bundle version
  GET  /v1/bundles/{version}/{filename}     -> Path-traversal-guarded file download with full RFC 9110
                                               HTTP Range resume (206 Partial Content, Accept-Ranges: bytes,
                                               Content-Range) for ~2.6 GB base GGUF & ~35 MB LoRA deltas
  POST /v1/preferences                      -> Strictly validates every NDJSON/JSONL line against
                                               preference_tuple.schema.json (400 on any malformed record)
                                               and atomically appends valid lines to preferences.jsonl
  POST /v1/render-video                     -> Enqueues a real Mac Studio talking-head render job
                                               (202 Accepted -> {job_id, status: "QUEUED"})
  GET  /v1/jobs/{job_id}                    -> Real persisted render job status, metrics, and video_url
  GET  /v1/renders/{filename}               -> Path-traversal-guarded MP4 streaming with RFC 9110 Range
                                               support (206 Partial Content, Content-Type: video/mp4)
"""

from __future__ import annotations

import argparse
import base64
import binascii
import datetime
import ipaddress
import json
import logging
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import urllib.parse
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable, Sequence

from tinker_voice_daemon import (
    SchemaValidationError,
    parse_preferences_ndjson,
    validate_bundle_manifest,
)

LOGGER = logging.getLogger("voicestudio.ota_server")

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8443
STREAM_CHUNK_BYTES = 256 * 1024  # 256 KiB streaming chunks for multi-GB GGUF & MP4 files
MAX_PREFERENCES_BODY_BYTES = 16 * 1024 * 1024  # 16 MiB
MAX_RENDER_BODY_BYTES = 64 * 1024 * 1024  # 64 MiB (WAV + JPEG base64 uploads)

DEFAULT_AVATAR_PATH = Path("/Users/mac/Downloads/clone/images/avatar_ref.jpg")
DEFAULT_VENV_PYTHON = Path("/Users/mac/.venv/bin/python")
VALID_VOICE_SOURCES = frozenset({"ENROLLED_VOICE_CLONE", "MIC_VOICEOVER"})
VALID_RESOLUTIONS = frozenset({"480P", "720P", "1080P"})
VALID_RATIOS = frozenset({"9:16", "16:9", "1:1"})

_TAILSCALE_IPV4_NET = ipaddress.ip_network("100.64.0.0/10")
_TAILSCALE_IPV6_NET = ipaddress.ip_network("fd7a:115c:a1e0::/48")
_RANGE_RE = re.compile(r"^bytes=(\d*)-(\d*)$")
_SAFE_SEGMENT_RE = re.compile(r"^[A-Za-z0-9._-]+$")
_VERSION_NUM_RE = re.compile(r"v(\d+)", re.IGNORECASE)


def is_tailscale_or_loopback_host(host: str) -> bool:
    """Checks whether host is loopback or a Tailscale CGNAT/ULA address."""
    if host == "localhost":
        return True
    try:
        addr = ipaddress.ip_address(host)
    except ValueError:
        return False
    if addr.is_loopback:
        return True
    if isinstance(addr, ipaddress.IPv4Address):
        return addr in _TAILSCALE_IPV4_NET
    return addr in _TAILSCALE_IPV6_NET


def _parse_single_byte_range(
    range_header: str, file_size: int
) -> tuple[int, int] | None:
    """Parses a single HTTP Range header (`bytes=start-end`, `bytes=start-`, `bytes=-suffix`).

    Returns `(start, end)` inclusive byte offsets, or None if unsatisfiable/malformed.
    """
    if file_size <= 0:
        return None
    header = range_header.strip()
    if "," in header:
        # Multi-range requests are rejected cleanly with 416 to keep streaming deterministic.
        return None
    match = _RANGE_RE.match(header)
    if not match:
        return None
    start_str, end_str = match.group(1), match.group(2)
    if not start_str and not end_str:
        return None

    if not start_str:
        # Suffix byte range: bytes=-N
        suffix_len = int(end_str)
        if suffix_len <= 0:
            return None
        start = max(0, file_size - suffix_len)
        end = file_size - 1
        return (start, end)

    start = int(start_str)
    if start >= file_size:
        return None

    if not end_str:
        end = file_size - 1
    else:
        end = int(end_str)
        if end < start:
            return None
        end = min(end, file_size - 1)

    return (start, end)


def _has_traversal_markers(raw_path: str) -> bool:
    """Inspects both raw and URL-decoded path strings for traversal or separator smuggling."""
    lowered = raw_path.lower()
    if (
        "\x00" in raw_path
        or "\\" in raw_path
        or "%2e" in lowered
        or "%2f" in lowered
        or "%5c" in lowered
        or "%00" in lowered
    ):
        return True
    decoded = urllib.parse.unquote(raw_path)
    if "\x00" in decoded or "\\" in decoded:
        return True
    segments = [seg for seg in decoded.split("/") if seg != ""]
    return any(seg in (".", "..") for seg in segments)


class OtaBundleStore:
    """Thread-safe bundle directory resolver and atomic preferences.jsonl store."""

    def __init__(
        self,
        bundles_root: Path | str,
        preferences_path: Path | str,
        *,
        max_preferences_body_bytes: int = MAX_PREFERENCES_BODY_BYTES,
    ) -> None:
        self.bundles_root = Path(bundles_root).resolve()
        self.preferences_path = Path(preferences_path).resolve()
        self.max_preferences_body_bytes = max_preferences_body_bytes
        self._preferences_lock = threading.Lock()

    def _load_manifest_from_dir(self, bundle_dir: Path) -> dict[str, Any]:
        manifest_file = bundle_dir / "bundle_manifest.json"
        if not manifest_file.is_file():
            raise FileNotFoundError(f"bundle_manifest.json missing in {bundle_dir}")
        decoded = json.loads(manifest_file.read_text(encoding="utf-8"))
        return validate_bundle_manifest(decoded)

    def resolve_latest_bundle(self) -> tuple[Path, dict[str, Any]] | None:
        """Resolves the latest verified bundle directory & manifest.

        Supports:
          1. Direct flat bundle layout (`bundles_root/bundle_manifest.json`)
          2. Explicit `bundles_root/latest` directory/symlink
          3. Multi-version subdirectories (`bundles_root/<version>/bundle_manifest.json`),
             selecting highest `(lora_version, created_at_utc, bundle_version)`.
        """
        if not self.bundles_root.is_dir():
            return None

        direct_manifest = self.bundles_root / "bundle_manifest.json"
        if direct_manifest.is_file():
            manifest = self._load_manifest_from_dir(self.bundles_root)
            return (self.bundles_root, manifest)

        latest_link = self.bundles_root / "latest"
        if latest_link.is_dir():
            resolved = latest_link.resolve()
            if resolved.is_relative_to(self.bundles_root) and (
                resolved / "bundle_manifest.json"
            ).is_file():
                manifest = self._load_manifest_from_dir(resolved)
                return (resolved, manifest)

        candidates: list[tuple[tuple[int, str, str], Path, dict[str, Any]]] = []
        for child in self.bundles_root.iterdir():
            if not child.is_dir() or child.name.startswith("."):
                continue
            resolved = child.resolve()
            if not resolved.is_relative_to(self.bundles_root):
                continue
            if not (resolved / "bundle_manifest.json").is_file():
                continue
            manifest = self._load_manifest_from_dir(resolved)
            sort_key = (
                int(manifest["lora_version"]),
                str(manifest["created_at_utc"]),
                str(manifest["bundle_version"]),
            )
            candidates.append((sort_key, resolved, manifest))

        if not candidates:
            return None
        candidates.sort(key=lambda item: item[0])
        _, best_dir, best_manifest = candidates[-1]
        return (best_dir, best_manifest)

    def resolve_bundle_dir_for_version(self, version: str) -> Path | None:
        """Resolves a safe bundle directory for `{version}` (or `latest`)."""
        if version == "latest":
            latest = self.resolve_latest_bundle()
            return latest[0] if latest is not None else None

        if not _SAFE_SEGMENT_RE.match(version) or version in (".", ".."):
            return None

        # Multi-version subdirectory check
        candidate_dir = (self.bundles_root / version).resolve()
        if (
            candidate_dir.is_relative_to(self.bundles_root)
            and candidate_dir.is_dir()
            and (candidate_dir / "bundle_manifest.json").is_file()
        ):
            return candidate_dir

        # Direct flat bundle layout check (where bundles_root holds a single bundle)
        direct_manifest = self.bundles_root / "bundle_manifest.json"
        if direct_manifest.is_file():
            manifest = self._load_manifest_from_dir(self.bundles_root)
            if manifest.get("bundle_version") == version:
                return self.bundles_root

        # Fallback: scan child dirs matching manifest.bundle_version
        if self.bundles_root.is_dir():
            for child in self.bundles_root.iterdir():
                if not child.is_dir():
                    continue
                resolved = child.resolve()
                if not resolved.is_relative_to(self.bundles_root):
                    continue
                if not (resolved / "bundle_manifest.json").is_file():
                    continue
                try:
                    manifest = self._load_manifest_from_dir(resolved)
                except Exception:
                    continue
                if manifest.get("bundle_version") == version:
                    return resolved

        return None

    def resolve_bundle_artifact(
        self, version: str, filename: str
    ) -> tuple[Path, str | None] | None:
        """Safely resolves `{version}/{filename}` strictly inside the bundle directory."""
        if not _SAFE_SEGMENT_RE.match(filename) or filename in (".", ".."):
            raise PermissionError(f"Unsafe artifact filename rejected: {filename!r}")

        bundle_dir = self.resolve_bundle_dir_for_version(version)
        if bundle_dir is None:
            return None

        bundle_dir_resolved = bundle_dir.resolve()
        candidate_file = (bundle_dir_resolved / filename).resolve()

        # Guard against symlinks or normalized paths escaping bundle_dir_resolved
        if not candidate_file.is_relative_to(bundle_dir_resolved):
            raise PermissionError("Path traversal outside bundle directory rejected")
        if candidate_file.parent != bundle_dir_resolved:
            raise PermissionError("Nested path traversal rejected")
        if not candidate_file.is_file():
            return None

        sha256_etag: str | None = None
        try:
            manifest = self._load_manifest_from_dir(bundle_dir_resolved)
            for art in manifest["artifacts"]:
                if art["filename"] == filename:
                    sha256_etag = art["sha256"]
                    break
        except Exception:
            sha256_etag = None

        return (candidate_file, sha256_etag)

    def count_pending_preferences(self) -> int:
        with self._preferences_lock:
            if not self.preferences_path.is_file():
                return 0
            count = 0
            with self.preferences_path.open("r", encoding="utf-8") as fh:
                for line in fh:
                    if line.strip():
                        count += 1
            return count

    def append_preferences_ndjson(self, payload_text: str) -> tuple[int, int]:
        """Validates every NDJSON line first, then atomically appends to preferences.jsonl.

        Returns `(accepted_count, total_pending_preferences)`.
        """
        records = parse_preferences_ndjson(payload_text)
        if not records:
            raise SchemaValidationError(
                "Request body must contain at least one preference JSONL record"
            )

        serialized_lines = "".join(
            json.dumps(rec.to_dict(), separators=(",", ":"), ensure_ascii=False) + "\n"
            for rec in records
        ).encode("utf-8")

        with self._preferences_lock:
            self.preferences_path.parent.mkdir(parents=True, exist_ok=True)
            tmp_path = self.preferences_path.with_name(
                f".{self.preferences_path.name}.tmp.{os.getpid()}.{threading.get_ident()}"
            )
            try:
                existing_count = 0
                with tmp_path.open("wb") as out_fh:
                    if self.preferences_path.is_file():
                        with self.preferences_path.open("rb") as in_fh:
                            last_byte = b""
                            for raw_line in in_fh:
                                if raw_line.strip():
                                    existing_count += 1
                                out_fh.write(raw_line)
                                if raw_line:
                                    last_byte = raw_line[-1:]
                            if last_byte and last_byte != b"\n":
                                out_fh.write(b"\n")
                    out_fh.write(serialized_lines)
                    out_fh.flush()
                    os.fsync(out_fh.fileno())
                os.replace(tmp_path, self.preferences_path)
            finally:
                if tmp_path.exists():
                    tmp_path.unlink(missing_ok=True)

            total_pending = existing_count + len(records)
            return (len(records), total_pending)


def _utc_now_iso() -> str:
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _extract_version_num(name: str) -> int:
    match = _VERSION_NUM_RE.search(name)
    return int(match.group(1)) if match else -1


def _content_type_for_filename(filename: str) -> str:
    lower = filename.lower()
    if lower.endswith(".mp4"):
        return "video/mp4"
    if lower.endswith(".json"):
        return "application/json; charset=utf-8"
    if lower.endswith(".jpg") or lower.endswith(".jpeg"):
        return "image/jpeg"
    if lower.endswith(".png"):
        return "image/png"
    if lower.endswith(".wav"):
        return "audio/wav"
    return "application/octet-stream"


def _decode_strict_base64(raw_value: Any, field_name: str) -> bytes:
    if not isinstance(raw_value, str):
        raise ValueError(f"{field_name} must be a base64 string")
    text = raw_value.strip()
    if text.startswith("data:") and "," in text:
        text = text.split(",", 1)[1]
    compact = "".join(text.split())
    if not compact:
        raise ValueError(f"{field_name} must not be empty")
    try:
        decoded = base64.b64decode(compact, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError(f"{field_name} contains invalid base64: {exc}") from exc
    if not decoded:
        raise ValueError(f"{field_name} decoded to 0 bytes")
    return decoded


def _probe_media_duration_ms(media_path: Path) -> int:
    ffprobe_bin = shutil.which("ffprobe")
    if not ffprobe_bin or not media_path.is_file():
        return 0
    try:
        proc = subprocess.run(
            [
                ffprobe_bin,
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                str(media_path),
            ],
            capture_output=True,
            text=True,
            timeout=15,
            check=False,
        )
        if proc.returncode != 0:
            return 0
        raw = proc.stdout.strip()
        if not raw:
            return 0
        sec = float(raw)
        return max(0, int(round(sec * 1000.0)))
    except Exception:
        return 0


def _atomic_publish_mp4(source_mp4: Path, target_mp4: Path) -> Path:
    """Atomically publishes `source_mp4` to `target_mp4`, adding `+faststart` when ffmpeg is available."""
    target_mp4.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = target_mp4.with_name(
        f".{target_mp4.name}.tmp.{os.getpid()}.{threading.get_ident()}"
    )
    try:
        ffmpeg_bin = shutil.which("ffmpeg")
        remuxed = False
        if ffmpeg_bin:
            proc = subprocess.run(
                [
                    ffmpeg_bin,
                    "-y",
                    "-i",
                    str(source_mp4),
                    "-c",
                    "copy",
                    "-movflags",
                    "+faststart",
                    str(tmp_path),
                ],
                capture_output=True,
                text=True,
                timeout=60,
                check=False,
            )
            if proc.returncode == 0 and tmp_path.is_file() and tmp_path.stat().st_size > 0:
                remuxed = True
            elif tmp_path.exists():
                tmp_path.unlink(missing_ok=True)

        if not remuxed:
            shutil.copy2(source_mp4, tmp_path)

        os.replace(tmp_path, target_mp4)
    finally:
        if tmp_path.exists():
            tmp_path.unlink(missing_ok=True)
    return target_mp4


def resolve_latest_synthesis_assets(
    models_root: Path, bundles_root: Path
) -> dict[str, Path | None]:
    """Resolves the latest available LoRA adapter, LiteRT DAC codec, and bundle support files.

    Prefers v002+ (highest version number) over v001 when present and non-empty.
    """
    # 1. Latest PEFT LoRA adapter (`lora-work-v002/peft_lora_adapter` > `lora-work-v001/peft_lora_adapter`)
    lora_candidates: list[tuple[int, str, Path]] = []
    if models_root.is_dir():
        for child in models_root.iterdir():
            if not child.is_dir() or not child.name.startswith("lora-work-"):
                continue
            adapter_dir = child / "peft_lora_adapter"
            if (
                adapter_dir.is_dir()
                and (adapter_dir / "adapter_config.json").is_file()
                and (adapter_dir / "adapter_model.safetensors").is_file()
            ):
                lora_candidates.append(
                    (_extract_version_num(child.name), child.name, adapter_dir.resolve())
                )
    lora_candidates.sort(key=lambda item: (item[0], item[1]))
    latest_lora_dir = lora_candidates[-1][2] if lora_candidates else None

    # 2. Latest GGUF LoRA (`bundles/v002/voice_lora_v002.gguf` > `bundles/v001/voice_lora_v001.gguf`)
    gguf_candidates: list[tuple[int, str, Path]] = []
    # 3. Latest LiteRT codec (`bundles/v002/codec_decoder_fp16.tflite` > `bundles/v001/codec_decoder_fp16.tflite`)
    codec_candidates: list[tuple[int, str, Path]] = []
    # 4. Bundle helper assets (master_centroid.bin, room_ir_44k.pcm, room_tone_44k.pcm)
    centroid_candidates: list[tuple[int, str, Path]] = []
    room_ir_candidates: list[tuple[int, str, Path]] = []
    room_tone_candidates: list[tuple[int, str, Path]] = []

    def _scan_bundle_dir(bdir: Path, ver_hint: int, label: str) -> None:
        codec = bdir / "codec_decoder_fp16.tflite"
        if codec.is_file() and codec.stat().st_size > 0:
            codec_candidates.append((ver_hint, label, codec.resolve()))
        for gguf in bdir.glob("voice_lora_v*.gguf"):
            if gguf.is_file() and gguf.stat().st_size > 0:
                gguf_candidates.append(
                    (max(ver_hint, _extract_version_num(gguf.name)), gguf.name, gguf.resolve())
                )
        centroid = bdir / "master_centroid.bin"
        if centroid.is_file() and centroid.stat().st_size > 0:
            centroid_candidates.append((ver_hint, label, centroid.resolve()))
        room_ir = bdir / "room_ir_44k.pcm"
        if room_ir.is_file() and room_ir.stat().st_size > 0:
            room_ir_candidates.append((ver_hint, label, room_ir.resolve()))
        room_tone = bdir / "room_tone_44k.pcm"
        if room_tone.is_file() and room_tone.stat().st_size > 0:
            room_tone_candidates.append((ver_hint, label, room_tone.resolve()))

    if bundles_root.is_dir():
        _scan_bundle_dir(bundles_root, 0, "")
        for child in bundles_root.iterdir():
            if not child.is_dir() or child.name.startswith("."):
                continue
            _scan_bundle_dir(child, _extract_version_num(child.name), child.name)

    gguf_candidates.sort(key=lambda item: (item[0], item[1]))
    codec_candidates.sort(key=lambda item: (item[0], item[1]))
    centroid_candidates.sort(key=lambda item: (item[0], item[1]))
    room_ir_candidates.sort(key=lambda item: (item[0], item[1]))
    room_tone_candidates.sort(key=lambda item: (item[0], item[1]))

    return {
        "lora_dir": latest_lora_dir,
        "lora_gguf": gguf_candidates[-1][2] if gguf_candidates else None,
        "codec_tflite": codec_candidates[-1][2] if codec_candidates else None,
        "master_centroid": centroid_candidates[-1][2] if centroid_candidates else None,
        "room_ir": room_ir_candidates[-1][2] if room_ir_candidates else None,
        "room_tone": room_tone_candidates[-1][2] if room_tone_candidates else None,
    }


def _summarize_subprocess_error(label: str, proc: subprocess.CompletedProcess[str]) -> str:
    stderr = (proc.stderr or "").strip()
    stdout = (proc.stdout or "").strip()
    detail = stderr if stderr else stdout
    if len(detail) > 900:
        detail = "..." + detail[-900:]
    return f"{label} failed (rc={proc.returncode}): {detail}" if detail else f"{label} failed (rc={proc.returncode})"


RenderRunnerFn = Callable[
    [dict[str, Any], Path, Callable[..., None]],
    dict[str, Any],
]


class RenderJobManager:
    """Thread-safe persisted render job queue backing POST /v1/render-video & GET /v1/jobs/{id}."""

    def __init__(
        self,
        renders_dir: Path | str,
        bundles_root: Path | str,
        *,
        models_root: Path | str | None = None,
        jobs_dir: Path | str | None = None,
        python_bin: Path | str | None = None,
        default_avatar_path: Path | str = DEFAULT_AVATAR_PATH,
        max_render_body_bytes: int = MAX_RENDER_BODY_BYTES,
        render_runner: RenderRunnerFn | None = None,
    ) -> None:
        self.renders_dir = Path(renders_dir).resolve()
        self.bundles_root = Path(bundles_root).resolve()
        self.models_root = (
            Path(models_root).resolve()
            if models_root is not None
            else self.bundles_root.parent.resolve()
        )
        self.jobs_dir = (
            Path(jobs_dir).resolve()
            if jobs_dir is not None
            else (self.renders_dir / "jobs").resolve()
        )
        self.default_avatar_path = Path(default_avatar_path)
        self.max_render_body_bytes = max_render_body_bytes
        self._render_runner = render_runner

        if python_bin is not None:
            self.python_bin = Path(python_bin)
        elif DEFAULT_VENV_PYTHON.is_file():
            self.python_bin = DEFAULT_VENV_PYTHON
        else:
            self.python_bin = Path(sys.executable)

        self._lock = threading.Lock()
        self._jobs: dict[str, dict[str, Any]] = {}
        self._queue: queue.Queue[tuple[str, dict[str, Any]] | None] = queue.Queue()
        self._worker_thread: threading.Thread | None = None
        self._shutdown = False

        self._load_existing_jobs()

    def _load_existing_jobs(self) -> None:
        if not self.jobs_dir.is_dir():
            return
        for path in self.jobs_dir.glob("*.json"):
            try:
                raw = json.loads(path.read_text(encoding="utf-8"))
                job_id = str(raw.get("job_id") or "").strip()
                if job_id and _SAFE_SEGMENT_RE.match(job_id):
                    self._jobs[job_id] = raw
            except Exception:
                continue

    def _ensure_worker_started_locked(self) -> None:
        if self._shutdown:
            return
        if self._worker_thread is None or not self._worker_thread.is_alive():
            self._worker_thread = threading.Thread(
                target=self._worker_loop,
                name="voicestudio-render-worker",
                daemon=True,
            )
            self._worker_thread.start()

    def shutdown(self) -> None:
        with self._lock:
            self._shutdown = True
            worker = self._worker_thread
        if worker is not None and worker.is_alive():
            self._queue.put(None)
            worker.join(timeout=3.0)

    def _persist_job_locked(self, job: dict[str, Any]) -> None:
        self.jobs_dir.mkdir(parents=True, exist_ok=True)
        job_file = self.jobs_dir / f"{job['job_id']}.json"
        tmp_file = job_file.with_name(
            f".{job_file.name}.tmp.{os.getpid()}.{threading.get_ident()}"
        )
        encoded = (json.dumps(job, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
        try:
            with tmp_file.open("wb") as fh:
                fh.write(encoded)
                fh.flush()
                os.fsync(fh.fileno())
            os.replace(tmp_file, job_file)
        finally:
            if tmp_file.exists():
                tmp_file.unlink(missing_ok=True)

    def _update_job(
        self,
        job_id: str,
        *,
        status: str | None = None,
        progress_fraction: float | None = None,
        stage_detail: str | None = None,
        error_message: str | None = ...,  # type: ignore[assignment]
        video_url: str | None = ...,  # type: ignore[assignment]
        duration_ms: int | None = None,
        metrics: dict[str, Any] | None = ...,  # type: ignore[assignment]
    ) -> dict[str, Any]:
        with self._lock:
            job = dict(self._jobs[job_id])
            if status is not None:
                job["status"] = status
            if progress_fraction is not None:
                job["progress_fraction"] = round(float(max(0.0, min(1.0, progress_fraction))), 4)
            if stage_detail is not None:
                job["stage_detail"] = stage_detail
            if error_message is not ...:
                job["error_message"] = error_message
            if video_url is not ...:
                job["video_url"] = video_url
            if duration_ms is not None:
                job["duration_ms"] = max(0, int(duration_ms))
            if metrics is not ...:
                job["metrics"] = metrics
            job["updated_at_utc"] = _utc_now_iso()
            self._jobs[job_id] = job
            self._persist_job_locked(job)
            return dict(job)

    def get_job(self, job_id: str) -> dict[str, Any] | None:
        if not _SAFE_SEGMENT_RE.match(job_id) or job_id in (".", ".."):
            raise PermissionError(f"Unsafe job_id rejected: {job_id!r}")
        with self._lock:
            job = self._jobs.get(job_id)
            if job is not None:
                return dict(job)
        # Fallback read from disk if created externally or reloaded
        job_file = (self.jobs_dir / f"{job_id}.json").resolve()
        if not job_file.is_relative_to(self.jobs_dir):
            raise PermissionError("Path traversal outside jobs directory rejected")
        if not job_file.is_file():
            return None
        try:
            loaded = json.loads(job_file.read_text(encoding="utf-8"))
        except Exception:
            return None
        with self._lock:
            self._jobs[job_id] = loaded
        return dict(loaded)

    def count_active_jobs(self) -> int:
        with self._lock:
            return sum(
                1
                for job in self._jobs.values()
                if job.get("status") not in ("READY", "FAILED")
            )

    def resolve_render_file(self, filename: str) -> Path | None:
        """Safely resolves `renders_dir/{filename}` rejecting traversal and symlink escapes."""
        if not _SAFE_SEGMENT_RE.match(filename) or filename in (".", ".."):
            raise PermissionError(f"Unsafe render filename rejected: {filename!r}")
        self.renders_dir.mkdir(parents=True, exist_ok=True)
        candidate = (self.renders_dir / filename).resolve()
        if not candidate.is_relative_to(self.renders_dir):
            raise PermissionError("Path traversal outside renders directory rejected")
        if candidate.parent != self.renders_dir:
            raise PermissionError("Nested render path traversal rejected")
        if not candidate.is_file():
            return None
        return candidate

    def enqueue_job(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("Render request body must be a JSON object")

        script_id = str(payload.get("script_id") or "").strip()
        title = str(payload.get("title") or "").strip() or "Untitled Aircheck"
        spoken_text = str(payload.get("spoken_text") or "").strip()
        voice_source = str(payload.get("voice_source") or "ENROLLED_VOICE_CLONE").strip().upper()
        if voice_source not in VALID_VOICE_SOURCES:
            raise ValueError(
                f"voice_source must be one of {sorted(VALID_VOICE_SOURCES)}, got {voice_source!r}"
            )

        resolution = str(payload.get("resolution") or "720P").strip().upper()
        if resolution not in VALID_RESOLUTIONS:
            raise ValueError(
                f"resolution must be one of {sorted(VALID_RESOLUTIONS)}, got {resolution!r}"
            )

        ratio = str(payload.get("ratio") or "9:16").strip()
        if ratio not in VALID_RATIOS:
            raise ValueError(
                f"ratio must be one of {sorted(VALID_RATIOS)}, got {ratio!r}"
            )

        max_duration_raw = payload.get("max_duration", 10)
        if isinstance(max_duration_raw, bool) or not isinstance(max_duration_raw, int):
            raise ValueError("max_duration must be an integer between 2 and 60")
        if not (2 <= max_duration_raw <= 60):
            raise ValueError("max_duration must be between 2 and 60 seconds")

        raw_audio_b64 = payload.get("audio_wav_base64")
        raw_photo_b64 = payload.get("photo_jpeg_base64")

        audio_bytes: bytes | None = None
        if raw_audio_b64 is not None and str(raw_audio_b64).strip() != "":
            audio_bytes = _decode_strict_base64(raw_audio_b64, "audio_wav_base64")

        if voice_source == "MIC_VOICEOVER" and audio_bytes is None:
            raise ValueError("audio_wav_base64 is required when voice_source is MIC_VOICEOVER")
        if voice_source == "ENROLLED_VOICE_CLONE" and not spoken_text:
            raise ValueError("spoken_text is required when voice_source is ENROLLED_VOICE_CLONE")

        photo_bytes: bytes | None = None
        if raw_photo_b64 is not None and str(raw_photo_b64).strip() != "":
            photo_bytes = _decode_strict_base64(raw_photo_b64, "photo_jpeg_base64")

        job_id = f"job_{uuid.uuid4().hex[:16]}"
        work_dir = self.jobs_dir / job_id
        work_dir.mkdir(parents=True, exist_ok=True)
        self.renders_dir.mkdir(parents=True, exist_ok=True)

        avatar_path: Path
        if photo_bytes is not None:
            ext = ".png" if photo_bytes.startswith(b"\x89PNG\r\n\x1a\n") else ".jpg"
            avatar_path = work_dir / f"avatar{ext}"
            avatar_path.write_bytes(photo_bytes)
        else:
            avatar_path = self.default_avatar_path

        audio_wav_path: Path | None = None
        if audio_bytes is not None:
            audio_wav_path = work_dir / "mic_voiceover.wav"
            audio_wav_path.write_bytes(audio_bytes)

        now_iso = _utc_now_iso()
        job_record: dict[str, Any] = {
            "job_id": job_id,
            "script_id": script_id,
            "title": title,
            "voice_source": voice_source,
            "status": "QUEUED",
            "progress_fraction": 0.05,
            "stage_detail": "Queued on Mac Studio rig",
            "error_message": None,
            "video_url": None,
            "duration_ms": 0,
            "metrics": None,
            "created_at_utc": now_iso,
            "updated_at_utc": now_iso,
        }

        work_spec: dict[str, Any] = {
            "job_id": job_id,
            "script_id": script_id,
            "title": title,
            "spoken_text": spoken_text,
            "voice_source": voice_source,
            "audio_wav_path": audio_wav_path,
            "avatar_path": avatar_path,
            "resolution": resolution,
            "ratio": ratio,
            "max_duration": max_duration_raw,
            "work_dir": work_dir,
        }

        with self._lock:
            self._jobs[job_id] = job_record
            self._persist_job_locked(job_record)
            self._ensure_worker_started_locked()

        self._queue.put((job_id, work_spec))
        return {
            "job_id": job_id,
            "status": "QUEUED",
            "progress_fraction": 0.05,
            "stage_detail": "Queued on Mac Studio rig",
        }

    def _worker_loop(self) -> None:
        while True:
            item = self._queue.get()
            if item is None:
                self._queue.task_done()
                return
            job_id, work_spec = item
            try:
                self._execute_job(job_id, work_spec)
            except Exception as exc:
                LOGGER.exception("Render job %s failed", job_id)
                self._update_job(
                    job_id,
                    status="FAILED",
                    stage_detail="Failed",
                    error_message=str(exc) or exc.__class__.__name__,
                    video_url=None,
                )
            finally:
                self._queue.task_done()

    def _execute_job(self, job_id: str, spec: dict[str, Any]) -> None:
        work_dir: Path = spec["work_dir"]
        target_mp4 = self.renders_dir / f"{job_id}.mp4"

        def report_progress(
            *,
            status: str,
            progress_fraction: float,
            stage_detail: str,
            metrics: dict[str, Any] | None = ...,  # type: ignore[assignment]
        ) -> None:
            self._update_job(
                job_id,
                status=status,
                progress_fraction=progress_fraction,
                stage_detail=stage_detail,
                metrics=metrics,
            )

        runner = self._render_runner if self._render_runner is not None else self._default_render_runner
        result = runner(spec, work_dir, report_progress)
        if not isinstance(result, dict):
            raise RuntimeError("Render runner returned invalid result payload")

        produced_raw = result.get("output_mp4")
        if not produced_raw:
            raise RuntimeError("Render runner completed without producing output_mp4")
        produced_mp4 = Path(produced_raw).resolve()
        if not produced_mp4.is_file() or produced_mp4.stat().st_size == 0:
            raise RuntimeError(f"Rendered MP4 missing or empty: {produced_mp4}")

        if produced_mp4 != target_mp4.resolve():
            _atomic_publish_mp4(produced_mp4, target_mp4)
        final_mp4 = target_mp4.resolve()

        duration_ms = int(result.get("duration_ms") or 0)
        if duration_ms <= 0:
            duration_ms = _probe_media_duration_ms(final_mp4)

        metrics = result.get("metrics")
        self._update_job(
            job_id,
            status="READY",
            progress_fraction=1.0,
            stage_detail="Ready",
            error_message=None,
            video_url=f"/v1/renders/{job_id}.mp4",
            duration_ms=duration_ms,
            metrics=metrics,
        )

    def _default_render_runner(
        self,
        spec: dict[str, Any],
        work_dir: Path,
        report_progress: Callable[..., None],
    ) -> dict[str, Any]:
        daemon_dir = Path(__file__).resolve().parent
        avatar_path: Path = Path(spec["avatar_path"])
        if not avatar_path.is_file():
            raise FileNotFoundError(f"Avatar image not found: {avatar_path}")

        voice_source: str = spec["voice_source"]
        spoken_text: str = spec["spoken_text"]
        resolution: str = spec["resolution"]
        ratio: str = spec["ratio"]
        max_duration: int = int(spec["max_duration"])
        python_exe = str(self.python_bin)

        metrics: dict[str, Any] | None = None
        if voice_source == "MIC_VOICEOVER" and spec.get("audio_wav_path") is not None:
            voice_wav = Path(spec["audio_wav_path"])
            if not voice_wav.is_file() or voice_wav.stat().st_size == 0:
                raise FileNotFoundError(f"Recorded mic WAV missing or empty: {voice_wav}")
            report_progress(
                status="VOICE",
                progress_fraction=0.25,
                stage_detail="Prepared recorded mic voiceover WAV",
            )
        else:
            synth_script = daemon_dir / "synthesize_and_render.py"
            if not synth_script.is_file():
                raise FileNotFoundError(f"synthesize_and_render.py not found at {synth_script}")

            assets = resolve_latest_synthesis_assets(self.models_root, self.bundles_root)
            synth_wav = work_dir / "synthesized_voice.wav"
            report_json = work_dir / "synth_verification_report.json"

            lora_label = assets["lora_dir"].parent.name if assets.get("lora_dir") else "default"
            report_progress(
                status="VOICE",
                progress_fraction=0.20,
                stage_detail=f"Synthesizing voice ({lora_label})",
            )

            synth_cmd = [
                python_exe,
                str(synth_script),
                "--target-text",
                spoken_text,
                "--output-wav",
                str(synth_wav),
                "--report-json",
                str(report_json),
                "--skip-video",
            ]
            if assets.get("lora_dir") is not None:
                synth_cmd.extend(["--lora-dir", str(assets["lora_dir"])])
            if assets.get("codec_tflite") is not None:
                synth_cmd.extend(["--codec-tflite", str(assets["codec_tflite"])])
            if assets.get("master_centroid") is not None:
                synth_cmd.extend(["--master-centroid", str(assets["master_centroid"])])
            if assets.get("room_ir") is not None:
                synth_cmd.extend(["--room-ir", str(assets["room_ir"])])
            if assets.get("room_tone") is not None:
                synth_cmd.extend(["--room-tone", str(assets["room_tone"])])

            proc = subprocess.run(synth_cmd, capture_output=True, text=True, check=False)
            if proc.returncode != 0:
                raise RuntimeError(_summarize_subprocess_error("synthesize_and_render.py", proc))
            if not synth_wav.is_file() or synth_wav.stat().st_size == 0:
                raise RuntimeError(
                    f"synthesize_and_render.py exited 0 but did not produce {synth_wav}"
                )

            if report_json.is_file():
                try:
                    report_data = json.loads(report_json.read_text(encoding="utf-8"))
                    selected = report_data.get("selected_take") or {}
                    gates = selected.get("mastered_gates") or selected.get("raw_gates") or {}
                    mastering = selected.get("mastering") or {}
                    metrics = {
                        "cer": gates.get("cer"),
                        "wespeaker_cosine": gates.get("wespeaker_cosine"),
                        "dnsmos_p808": gates.get("dnsmos_p808"),
                        "syllables_per_sec": gates.get("syllables_per_sec"),
                        "tail_rms_dbfs": gates.get("tail_rms_dbfs"),
                        "mastered_lufs": mastering.get("mastered_lufs"),
                        "recognized_text": gates.get("recognized_text"),
                        "passed_all_strict_gates": gates.get("passed_all_strict_gates"),
                        "lora_dir": str(assets["lora_dir"]) if assets.get("lora_dir") else None,
                        "lora_gguf": str(assets["lora_gguf"]) if assets.get("lora_gguf") else None,
                        "codec_tflite": str(assets["codec_tflite"]) if assets.get("codec_tflite") else None,
                    }
                except Exception as exc:
                    LOGGER.warning("Could not parse synth report %s: %s", report_json, exc)

            voice_wav = synth_wav

        head_script = daemon_dir / "generate_talking_head.py"
        if not head_script.is_file():
            raise FileNotFoundError(f"generate_talking_head.py not found at {head_script}")

        report_progress(
            status="LIPSYNC",
            progress_fraction=0.60,
            stage_detail="Rendering talking-head video (wan3.0-video)",
            metrics=metrics,
        )

        raw_out_mp4 = work_dir / "talking_head.mp4"
        head_cmd = [
            python_exe,
            str(head_script),
            "--voice-wav",
            str(voice_wav),
            "--avatar",
            str(avatar_path),
            "--output",
            str(raw_out_mp4),
            "--resolution",
            resolution,
            "--ratio",
            ratio,
            "--max-duration",
            str(max_duration),
        ]
        if spoken_text:
            head_cmd.extend(["--transcript", spoken_text])

        head_proc = subprocess.run(head_cmd, capture_output=True, text=True, check=False)
        if head_proc.returncode != 0:
            raise RuntimeError(_summarize_subprocess_error("generate_talking_head.py", head_proc))

        head_meta: dict[str, Any] = {}
        stdout_text = (head_proc.stdout or "").strip()
        if stdout_text:
            try:
                head_meta = json.loads(stdout_text)
            except json.JSONDecodeError:
                head_meta = {}

        # Prefer wan3.0-video's native frame-accurate lip-locked output (`raw_out_mp4`) over
        # post-hoc audio replacement (`exact_voice_mp4`), because blind audio replacement shifts
        # lip timing whenever the input WAV cadence differs from the generated video cadence.
        chosen_mp4 = (
            raw_out_mp4
            if raw_out_mp4.is_file() and raw_out_mp4.stat().st_size > 0
            else Path(str(head_meta.get("exact_input_voice_mp4", "")))
        )
        if not chosen_mp4.is_file() or chosen_mp4.stat().st_size == 0:
            raise RuntimeError(
                f"generate_talking_head.py exited 0 but did not produce {chosen_mp4}"
            )

        target_mp4 = self.renders_dir / f"{spec['job_id']}.mp4"
        _atomic_publish_mp4(chosen_mp4, target_mp4)
        duration_ms = _probe_media_duration_ms(target_mp4)
        if duration_ms <= 0 and isinstance(head_meta.get("duration_sec"), (int, float)):
            duration_ms = max(0, int(round(float(head_meta["duration_sec"]) * 1000.0)))

        return {
            "output_mp4": target_mp4,
            "duration_ms": duration_ms,
            "metrics": metrics,
        }


class OtaHttpServer(ThreadingHTTPServer):
    """Multi-threaded stdlib HTTP server holding OtaBundleStore and RenderJobManager."""

    allow_reuse_address = True
    daemon_threads = True

    def __init__(
        self,
        server_address: tuple[str, int],
        store: OtaBundleStore,
        *,
        render_manager: RenderJobManager | None = None,
        quiet: bool = False,
    ) -> None:
        self.store = store
        self.render_manager = render_manager or RenderJobManager(
            renders_dir=store.bundles_root.parent / "renders",
            bundles_root=store.bundles_root,
        )
        self.quiet = quiet
        super().__init__(server_address, OtaRequestHandler)

    def server_close(self) -> None:
        try:
            self.render_manager.shutdown()
        finally:
            super().server_close()


class OtaRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server: OtaHttpServer

    def log_message(self, format: str, *args: Any) -> None:
        if not getattr(self.server, "quiet", False):
            LOGGER.info("%s - %s", self.address_string(), format % args)

    def _send_json(
        self,
        status: int | HTTPStatus,
        payload: dict[str, Any],
        *,
        extra_headers: dict[str, str] | None = None,
        head_only: bool = False,
    ) -> None:
        body = (json.dumps(payload, separators=(",", ":"), ensure_ascii=False) + "\n").encode(
            "utf-8"
        )
        self.send_response(int(status))
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        if not head_only:
            self.wfile.write(body)

    def do_HEAD(self) -> None:
        self._handle_get_or_head(head_only=True)

    def do_GET(self) -> None:
        self._handle_get_or_head(head_only=False)

    def _handle_get_or_head(self, *, head_only: bool) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        raw_path = parsed.path

        # Reject path traversal immediately before any filesystem resolution.
        if _has_traversal_markers(raw_path):
            self._send_json(
                HTTPStatus.FORBIDDEN,
                {"error": "forbidden", "detail": "Path traversal attempt rejected"},
                head_only=head_only,
            )
            return

        if raw_path == "/health":
            self._handle_health(head_only=head_only)
            return

        if raw_path == "/v1/bundles/latest/manifest.json":
            self._handle_manifest("latest", head_only=head_only)
            return

        if raw_path.startswith("/v1/bundles/"):
            remainder = raw_path[len("/v1/bundles/") :]
            parts = remainder.split("/")
            if len(parts) != 2 or not parts[0] or not parts[1]:
                self._send_json(
                    HTTPStatus.NOT_FOUND,
                    {"error": "not_found", "detail": "Invalid bundle route"},
                    head_only=head_only,
                )
                return
            version, filename = parts[0], parts[1]
            if filename == "manifest.json":
                self._handle_manifest(version, head_only=head_only)
                return
            self._handle_artifact_download(version, filename, head_only=head_only)
            return

        if raw_path.startswith("/v1/jobs/"):
            remainder = raw_path[len("/v1/jobs/") :]
            parts = remainder.split("/")
            if len(parts) != 1 or not parts[0]:
                self._send_json(
                    HTTPStatus.NOT_FOUND,
                    {"error": "not_found", "detail": "Invalid job route"},
                    head_only=head_only,
                )
                return
            self._handle_get_job(parts[0], head_only=head_only)
            return

        if raw_path.startswith("/v1/renders/"):
            remainder = raw_path[len("/v1/renders/") :]
            parts = remainder.split("/")
            if len(parts) != 1 or not parts[0]:
                self._send_json(
                    HTTPStatus.FORBIDDEN if len(parts) > 1 else HTTPStatus.NOT_FOUND,
                    {
                        "error": "forbidden" if len(parts) > 1 else "not_found",
                        "detail": "Nested render path rejected" if len(parts) > 1 else "Invalid render route",
                    },
                    head_only=head_only,
                )
                return
            self._handle_render_download(parts[0], head_only=head_only)
            return

        self._send_json(
            HTTPStatus.NOT_FOUND,
            {"error": "not_found", "detail": f"Unknown endpoint: {raw_path}"},
            head_only=head_only,
        )

    def _handle_health(self, *, head_only: bool) -> None:
        lora_version: int = 0
        bundle_version: str | None = None
        manifest_error: str | None = None

        try:
            latest = self.server.store.resolve_latest_bundle()
            if latest is not None:
                _, manifest = latest
                lora_version = int(manifest["lora_version"])
                bundle_version = str(manifest["bundle_version"])
        except Exception as exc:
            manifest_error = str(exc)

        payload: dict[str, Any] = {
            "status": "ok" if manifest_error is None else "degraded",
            "lora_version": lora_version,
            "bundle_version": bundle_version,
            "pending_preferences": self.server.store.count_pending_preferences(),
            "render_bridge": True,
            "active_render_jobs": self.server.render_manager.count_active_jobs(),
        }
        if manifest_error is not None:
            payload["manifest_error"] = manifest_error

        self._send_json(HTTPStatus.OK, payload, head_only=head_only)

    def _handle_get_job(self, job_id: str, *, head_only: bool) -> None:
        try:
            job = self.server.render_manager.get_job(job_id)
        except PermissionError as exc:
            self._send_json(
                HTTPStatus.FORBIDDEN,
                {"error": "forbidden", "detail": str(exc)},
                head_only=head_only,
            )
            return
        if job is None:
            self._send_json(
                HTTPStatus.NOT_FOUND,
                {"error": "not_found", "detail": f"Render job '{job_id}' not found"},
                head_only=head_only,
            )
            return
        self._send_json(HTTPStatus.OK, job, head_only=head_only)

    def _handle_render_download(self, filename: str, *, head_only: bool) -> None:
        try:
            render_path = self.server.render_manager.resolve_render_file(filename)
        except PermissionError as exc:
            self._send_json(
                HTTPStatus.FORBIDDEN,
                {"error": "forbidden", "detail": str(exc)},
                head_only=head_only,
            )
            return

        if render_path is None:
            self._send_json(
                HTTPStatus.NOT_FOUND,
                {"error": "not_found", "detail": f"Render file '{filename}' not found"},
                head_only=head_only,
            )
            return

        self._serve_file_with_range(
            render_path,
            _content_type_for_filename(filename),
            head_only=head_only,
        )

    def _handle_manifest(self, version: str, *, head_only: bool) -> None:
        try:
            bundle_dir = self.server.store.resolve_bundle_dir_for_version(version)
            if bundle_dir is None:
                self._send_json(
                    HTTPStatus.NOT_FOUND,
                    {"error": "not_found", "detail": f"Bundle version '{version}' not found"},
                    head_only=head_only,
                )
                return
            manifest = self.server.store._load_manifest_from_dir(bundle_dir)
        except FileNotFoundError:
            self._send_json(
                HTTPStatus.NOT_FOUND,
                {"error": "not_found", "detail": "No OTA bundle manifest installed"},
                head_only=head_only,
            )
            return
        except (SchemaValidationError, json.JSONDecodeError) as exc:
            self._send_json(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                {
                    "error": "invalid_manifest",
                    "detail": f"Bundle manifest failed schema validation: {exc}",
                },
                head_only=head_only,
            )
            return

        self._send_json(HTTPStatus.OK, manifest, head_only=head_only)

    def _handle_artifact_download(
        self, version: str, filename: str, *, head_only: bool
    ) -> None:
        try:
            resolved = self.server.store.resolve_bundle_artifact(version, filename)
        except PermissionError as exc:
            self._send_json(
                HTTPStatus.FORBIDDEN,
                {"error": "forbidden", "detail": str(exc)},
                head_only=head_only,
            )
            return

        if resolved is None:
            self._send_json(
                HTTPStatus.NOT_FOUND,
                {
                    "error": "not_found",
                    "detail": f"Artifact '{filename}' not found in bundle '{version}'",
                },
                head_only=head_only,
            )
            return

        artifact_path, sha256_etag = resolved
        content_type = (
            "application/json; charset=utf-8"
            if filename.endswith(".json")
            else "application/octet-stream"
        )
        self._serve_file_with_range(
            artifact_path,
            content_type,
            etag=sha256_etag,
            head_only=head_only,
        )

    def _serve_file_with_range(
        self,
        file_path: Path,
        content_type: str,
        *,
        etag: str | None = None,
        head_only: bool = False,
    ) -> None:
        file_size = file_path.stat().st_size
        range_header = self.headers.get("Range")
        if range_header is not None:
            parsed_range = _parse_single_byte_range(range_header, file_size)
            if parsed_range is None:
                self.send_response(int(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE))
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Range", f"bytes */{file_size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return

            start, end = parsed_range
            length = end - start + 1
            self.send_response(int(HTTPStatus.PARTIAL_CONTENT))
            self.send_header("Content-Type", content_type)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Range", f"bytes {start}-{end}/{file_size}")
            self.send_header("Content-Length", str(length))
            if etag:
                self.send_header("ETag", f'"{etag}"')
            self.end_headers()
            if not head_only:
                self._stream_file_range(file_path, start, length)
            return

        self.send_response(int(HTTPStatus.OK))
        self.send_header("Content-Type", content_type)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(file_size))
        if etag:
            self.send_header("ETag", f'"{etag}"')
        self.end_headers()
        if not head_only:
            self._stream_file_range(file_path, 0, file_size)

    def _stream_file_range(self, file_path: Path, offset: int, length: int) -> None:
        remaining = length
        with file_path.open("rb") as fh:
            fh.seek(offset)
            while remaining > 0:
                to_read = min(STREAM_CHUNK_BYTES, remaining)
                chunk = fh.read(to_read)
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    def _read_post_utf8_body(self, *, max_bytes: int, empty_detail: str) -> str | None:
        content_length_raw = self.headers.get("Content-Length")
        if content_length_raw is None:
            self._send_json(
                HTTPStatus.LENGTH_REQUIRED,
                {"error": "length_required", "detail": "Content-Length header is required"},
            )
            return None

        try:
            content_length = int(content_length_raw)
        except ValueError:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "detail": "Invalid Content-Length header"},
            )
            return None

        if content_length <= 0:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "detail": empty_detail},
            )
            return None

        if content_length > max_bytes:
            self._send_json(
                HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                {
                    "error": "payload_too_large",
                    "detail": f"Payload exceeds max size of {max_bytes} bytes",
                },
            )
            return None

        raw_body = self.rfile.read(content_length)
        try:
            return raw_body.decode("utf-8")
        except UnicodeDecodeError as exc:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "detail": f"Payload is not valid UTF-8: {exc}"},
            )
            return None

    def do_POST(self) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path == "/v1/preferences":
            self._handle_post_preferences()
            return
        if parsed.path == "/v1/render-video":
            self._handle_post_render_video()
            return

        self._send_json(
            HTTPStatus.NOT_FOUND,
            {"error": "not_found", "detail": f"Unknown POST endpoint: {parsed.path}"},
        )

    def _handle_post_preferences(self) -> None:
        text_body = self._read_post_utf8_body(
            max_bytes=self.server.store.max_preferences_body_bytes,
            empty_detail="Empty preference payload",
        )
        if text_body is None:
            return

        try:
            accepted_count, total_pending = self.server.store.append_preferences_ndjson(
                text_body
            )
        except SchemaValidationError as exc:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "schema_validation_failed", "detail": str(exc)},
            )
            return

        self._send_json(
            HTTPStatus.OK,
            {
                "status": "accepted",
                "accepted_count": accepted_count,
                "pending_preferences": total_pending,
            },
        )

    def _handle_post_render_video(self) -> None:
        text_body = self._read_post_utf8_body(
            max_bytes=self.server.render_manager.max_render_body_bytes,
            empty_detail="Empty render request payload",
        )
        if text_body is None:
            return

        try:
            decoded = json.loads(text_body)
        except json.JSONDecodeError as exc:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "detail": f"Invalid JSON body: {exc}"},
            )
            return

        try:
            enqueued = self.server.render_manager.enqueue_job(decoded)
        except ValueError as exc:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "invalid_render_request", "detail": str(exc)},
            )
            return

        self._send_json(HTTPStatus.ACCEPTED, enqueued)


def create_ota_server(
    host: str,
    port: int,
    bundles_root: Path | str,
    preferences_path: Path | str,
    *,
    renders_dir: Path | str | None = None,
    jobs_dir: Path | str | None = None,
    models_root: Path | str | None = None,
    python_bin: Path | str | None = None,
    default_avatar_path: Path | str = DEFAULT_AVATAR_PATH,
    render_runner: RenderRunnerFn | None = None,
    quiet: bool = False,
) -> OtaHttpServer:
    """Factory creating an OtaHttpServer bound to `(host, port)`."""
    if not is_tailscale_or_loopback_host(host) and not quiet:
        LOGGER.warning(
            "Binding OTA server to non-Tailscale/non-loopback address %s:%d; "
            "prefer a Tailscale (100.64.0.0/10) or WireGuard interface.",
            host,
            port,
        )
    store = OtaBundleStore(bundles_root=bundles_root, preferences_path=preferences_path)
    resolved_renders_dir = (
        Path(renders_dir).resolve()
        if renders_dir is not None
        else (store.bundles_root.parent / "renders").resolve()
    )
    render_manager = RenderJobManager(
        renders_dir=resolved_renders_dir,
        bundles_root=store.bundles_root,
        models_root=models_root,
        jobs_dir=jobs_dir,
        python_bin=python_bin,
        default_avatar_path=default_avatar_path,
        render_runner=render_runner,
    )
    return OtaHttpServer((host, port), store, render_manager=render_manager, quiet=quiet)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="VoiceStudio Tailscale/WireGuard OTA, Telemetry & Talking-Head Render Server"
    )
    parser.add_argument(
        "--host",
        default=DEFAULT_HOST,
        help="Bind IP address (e.g. Tailscale 100.x.y.z or 127.0.0.1)",
    )
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="TCP port (default: 8443)")
    parser.add_argument(
        "--bundles-dir",
        required=True,
        help="Root directory holding OTA bundle(s) and bundle_manifest.json",
    )
    parser.add_argument(
        "--preferences-file",
        required=True,
        help="Path to preferences.jsonl file for atomic preference appends",
    )
    parser.add_argument(
        "--renders-dir",
        default=None,
        help="Directory holding rendered MP4 clips and persisted render job JSONs (default: <bundles-dir>/../renders)",
    )
    parser.add_argument(
        "--default-avatar",
        default=str(DEFAULT_AVATAR_PATH),
        help="Fallback portrait image used when a phone render request omits photo_jpeg_base64",
    )
    parser.add_argument("--quiet", action="store_true", help="Suppress per-request access logs")

    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    server = create_ota_server(
        host=args.host,
        port=args.port,
        bundles_root=args.bundles_dir,
        preferences_path=args.preferences_file,
        renders_dir=args.renders_dir,
        default_avatar_path=args.default_avatar,
        quiet=args.quiet,
    )
    bound_host, bound_port = server.server_address[:2]
    LOGGER.info("VoiceStudio OTA & Render Bridge server listening on http://%s:%d", bound_host, bound_port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
