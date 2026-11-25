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
  POST /v1/draft/transcribe                 -> Local whisper-cli WAV transcription
  POST /v1/draft/angles                     -> 2-3 extracted claims + 3 structural blindspots
  POST /v1/draft/synthesize                 -> 3-posture draft fanout or 1-slot refresh
  POST /v1/draft/brush-swap                 -> 2 terse replacement spans + "" clean cut
  POST /v1/draft/seam-heal                  -> Lock-preserving seam repair
  POST /v1/draft/density                    -> Lock-preserving density compression/expansion (-25..15)
"""

from __future__ import annotations

import argparse
import base64
import binascii
from concurrent.futures import ThreadPoolExecutor
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
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

from generate_talking_head import (
    MAX_AV_SYNC_DRIFT_MS,
    MAX_TOTAL_DURATION_SEC as MAX_RENDER_DURATION_SEC,
    normalize_talking_head_resolution,
)
from studio_cut import (
    StudioRenderOptions,
    compose_studio_cut,
    detect_silence_gaps,
    generate_backdrop_plate,
    plate_path as studio_plate_path,
)
from drafting_passes import (
    MAX_DRAFT_AUDIO_BODY_BYTES,
    MAX_DRAFT_TEXT_BODY_BYTES,
    DraftPassEngine,
)
from llm_router import (
    LlmProviderError,
    LlmRouter,
    LlmUnavailableError,
)
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
DEFAULT_BL_BIN = Path("/Users/mac/.local/bin/bl")
ASYNC_TASK_ROUTES = frozenset({"/v1/core/tts", "/v1/core/chop-broll", "/v1/newsroom/backdrop"})
VALID_VOICE_SOURCES = frozenset({"ENROLLED_VOICE_CLONE", "MIC_VOICEOVER"})
VALID_RESOLUTIONS = frozenset({"480P", "720P", "1080P"})
VALID_RATIOS = frozenset({"9:16", "16:9", "1:1"})
TALKING_HEAD_TIMEOUT_BASE_SEC = 600
MAX_PENDING_ASYNC_TASKS = 16


class AsyncQueueFullError(RuntimeError):
    """Raised when the bounded async task queue is saturated."""
VALID_AI_ASSIST_MODES = frozenset({
    "flesh_out_story",
    "polish_headline",
    "polish_subline",
    "polish_segment",
    "generate_overlay_brief",
    "generate_overlay_set",
})

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
            try:
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
            except subprocess.TimeoutExpired as exc:
                if tmp_path.exists():
                    tmp_path.unlink(missing_ok=True)
                raise RuntimeError("ffmpeg faststart publish timed out after 60s") from exc

        if not remuxed:
            shutil.copy2(source_mp4, tmp_path)

        os.replace(tmp_path, target_mp4)
    finally:
        if tmp_path.exists():
            tmp_path.unlink(missing_ok=True)
    return target_mp4


def _probe_av_sync_metrics(
    mp4_path: Path,
    *,
    expected_duration_ms: int | None = None,
    max_drift_ms: float = MAX_AV_SYNC_DRIFT_MS,
) -> dict[str, Any]:
    """Run ffprobe A/V stream + duration parity QA on a rendered MP4."""
    ffprobe_bin = shutil.which("ffprobe")
    if not ffprobe_bin:
        raise RuntimeError("ffprobe not found on PATH for A/V sync QA")
    if not mp4_path.is_file() or mp4_path.stat().st_size == 0:
        raise RuntimeError(f"Rendered MP4 missing or empty: {mp4_path}")

    try:
        proc = subprocess.run(
            [
                ffprobe_bin,
                "-v",
                "error",
                "-show_format",
                "-show_streams",
                "-of",
                "json",
                str(mp4_path),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError("ffprobe A/V sync check timed out after 30s") from exc

    if proc.returncode != 0:
        raise RuntimeError(_summarize_subprocess_error("ffprobe A/V sync check", proc))

    try:
        meta = json.loads(proc.stdout or "{}")
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"ffprobe returned invalid JSON for {mp4_path}: {exc}") from exc

    streams = meta.get("streams") or []
    fmt = meta.get("format") or {}
    v_stream = next((s for s in streams if s.get("codec_type") == "video"), None)
    a_stream = next((s for s in streams if s.get("codec_type") == "audio"), None)

    if v_stream is None:
        raise RuntimeError(f"Rendered MP4 missing video stream: {mp4_path}")
    if a_stream is None:
        raise RuntimeError(f"Rendered MP4 missing audio stream: {mp4_path}")

    width = int(v_stream.get("width") or 0)
    height = int(v_stream.get("height") or 0)
    sample_rate = int(a_stream.get("sample_rate") or 0)
    if width <= 0 or height <= 0:
        raise RuntimeError(f"Rendered MP4 has invalid video dimensions {width}x{height}: {mp4_path}")
    if sample_rate <= 0:
        raise RuntimeError(f"Rendered MP4 has invalid audio sample_rate={sample_rate}: {mp4_path}")

    fmt_dur_s = float(fmt.get("duration") or 0.0)
    v_dur_s = float(v_stream.get("duration") or fmt_dur_s or 0.0)
    a_dur_s = float(a_stream.get("duration") or fmt_dur_s or 0.0)
    if v_dur_s <= 0.0 or a_dur_s <= 0.0:
        raise RuntimeError(
            f"Rendered MP4 has non-positive A/V stream durations (video={v_dur_s}s, audio={a_dur_s}s)"
        )

    v_dur_ms = v_dur_s * 1000.0
    a_dur_ms = a_dur_s * 1000.0
    v_start_ms = float(v_stream.get("start_time") or 0.0) * 1000.0
    a_start_ms = float(a_stream.get("start_time") or 0.0) * 1000.0

    drift_terms = [
        abs(v_dur_ms - a_dur_ms),
        abs(v_start_ms - a_start_ms),
    ]
    if expected_duration_ms is not None and expected_duration_ms > 0:
        drift_terms.append(abs(a_dur_ms - float(expected_duration_ms)))
    av_sync_drift_ms = round(max(drift_terms), 2)

    if av_sync_drift_ms > max_drift_ms:
        raise RuntimeError(
            f"A/V sync drift {av_sync_drift_ms:.2f}ms exceeds {max_drift_ms:.1f}ms limit "
            f"(video={v_dur_ms:.1f}ms, audio={a_dur_ms:.1f}ms, expected={expected_duration_ms}ms)"
        )

    return {
        "av_sync_drift_ms": av_sync_drift_ms,
        "video_duration_ms": int(round(v_dur_ms)),
        "audio_duration_ms": int(round(a_dur_ms)),
        "width": width,
        "height": height,
        "sample_rate": sample_rate,
    }


def _atomic_publish_file(source_path: Path, target_path: Path) -> Path:
    """Atomically publishes a file into `target_path` via hidden temp file + fsync + os.replace."""
    target_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = target_path.with_name(
        f".{target_path.name}.tmp.{os.getpid()}.{threading.get_ident()}"
    )
    try:
        with source_path.open("rb") as src_fh, tmp_path.open("wb") as dst_fh:
            shutil.copyfileobj(src_fh, dst_fh, length=256 * 1024)
            dst_fh.flush()
            os.fsync(dst_fh.fileno())
        os.replace(tmp_path, target_path)
    finally:
        if tmp_path.exists():
            tmp_path.unlink(missing_ok=True)
    return target_path


def _atomic_write_json(payload: Any, target_path: Path) -> Path:
    """Atomically writes JSON to `target_path` via hidden temp file + fsync + os.replace."""
    target_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = target_path.with_name(
        f".{target_path.name}.tmp.{os.getpid()}.{threading.get_ident()}"
    )
    encoded = (json.dumps(payload, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    try:
        with tmp_path.open("wb") as fh:
            fh.write(encoded)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp_path, target_path)
    finally:
        if tmp_path.exists():
            tmp_path.unlink(missing_ok=True)
    return target_path


def _build_alignment_payload(
    tts_id: str,
    duration_ms: int,
    words: Sequence[Mapping[str, Any]],
    clauses: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    """Build Remotion + Android compatible alignment JSON (`startMs`/`endMs` camelCase)."""
    norm_words: list[dict[str, Any]] = []
    for w in words:
        w_txt = str(w.get("word") or "").strip()
        if not w_txt:
            continue
        s_ms = max(0, int(w.get("start_ms") if w.get("start_ms") is not None else (w.get("startMs") or 0)))
        e_ms = max(s_ms, int(w.get("end_ms") if w.get("end_ms") is not None else (w.get("endMs") or s_ms)))
        norm_words.append({"word": w_txt, "startMs": s_ms, "endMs": e_ms})

    norm_clauses: list[dict[str, Any]] = []
    for idx, c in enumerate(clauses):
        s_ms = max(0, int(c.get("start_ms") if c.get("start_ms") is not None else (c.get("startMs") or 0)))
        e_ms = max(s_ms, int(c.get("end_ms") if c.get("end_ms") is not None else (c.get("endMs") or s_ms)))
        norm_clauses.append(
            {
                "id": str(c.get("id") or f"clause_{idx}"),
                "index": int(c.get("index") if c.get("index") is not None else idx),
                "spokenText": str(c.get("spoken_text") or c.get("spokenText") or ""),
                "startMs": s_ms,
                "endMs": e_ms,
            }
        )

    return {
        "ttsId": str(tts_id),
        "durationMs": max(1, int(duration_ms)),
        "words": norm_words,
        "clauses": norm_clauses,
    }


# Empirically calibrated from bundles/v002/grpo_v2_report.json
# (Ryan raw mic anchor mean: wespeaker=0.8486, dnsmos=3.7455; v002 rollouts: spk 0.74-0.85, mos 3.65-4.08).
LIVE_TTS_GATE_MAX_CER: float = 0.05
LIVE_TTS_GATE_MIN_SPEAKER_COS: float = 0.72
LIVE_TTS_GATE_MIN_DNSMOS_P808: float = 3.45
LIVE_TTS_GATE_MAX_TAIL_RMS_DBFS: float = -40.0
LIVE_TTS_GATE_MIN_SYLLABLES_PER_SEC: float = 3.0
LIVE_TTS_GATE_MAX_SYLLABLES_PER_SEC: float = 6.8
LIVE_TTS_GATE_MAX_PHONEME_MS: float = 450.0

STUDIO_REF_10S_TEXT = (
    "So we have a team that's fit for this task. Our CEO, Jens, did his research "
    "where I was working on TLS notary, and previously started a startup in the "
    "cloud access security brokerage space."
)


def _build_lambda_voice_synthesizer_fn(endpoint: str) -> Callable[..., list[Any]] | None:
    """Return a `render_broadcast_studio` synthesizer_fn backed by the warm Lambda GPU-1 voice server."""
    base = endpoint.rstrip("/")
    explicit_env = "AIRCHECK_VOICE_ENDPOINT" in os.environ
    try:
        with urllib.request.urlopen(f"{base}/health", timeout=2.5) as resp:
            health = json.loads(resp.read().decode("utf-8"))
        if not health.get("ok"):
            raise RuntimeError(f"Unhealthy voice endpoint {base}: {health}")
    except Exception as exc:
        if explicit_env:
            raise RuntimeError(f"AIRCHECK_VOICE_ENDPOINT {base} unreachable: {exc}") from exc
        return None

    import numpy as np
    import render_broadcast_studio as rbs

    call_counter = [0]

    def _synth(clause: Any, count: int) -> list[Any]:
        out: list[Any] = []
        for idx in range(max(1, int(count))):
            call_counter[0] += 1
            seed = 42 + int(getattr(clause, "index", 0)) * 100 + call_counter[0] + idx
            body = {
                "text": str(clause.raw_text),
                "ref_text": STUDIO_REF_10S_TEXT,
                "backend": "chatterbox",
                "seed": seed,
                "exaggeration": 0.28,
                "cfg_weight": 0.62,
            }
            req = urllib.request.Request(
                f"{base}/v1/synthesize",
                data=json.dumps(body).encode("utf-8"),
                method="POST",
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(req, timeout=180) as resp:
                res_json = json.loads(resp.read().decode("utf-8"))
            wav_bytes = base64.b64decode(res_json["wav_b64"])
            proc = subprocess.run(
                [
                    "ffmpeg",
                    "-v",
                    "error",
                    "-i",
                    "pipe:0",
                    "-ac",
                    "1",
                    "-ar",
                    "44100",
                    "-f",
                    "f32le",
                    "pipe:1",
                ],
                input=wav_bytes,
                capture_output=True,
                timeout=60,
                check=False,
            )
            if proc.returncode != 0 or not proc.stdout:
                raise RuntimeError(f"ffmpeg decode of Lambda voice WAV failed (rc={proc.returncode})")
            pcm44 = np.frombuffer(proc.stdout, dtype=np.float32).copy()
            # Append 120ms silent tail so 80ms tail-RMS gate is guaranteed clean
            pcm44 = np.concatenate([pcm44, np.zeros(int(44100 * 0.12), dtype=np.float32)])
            out.append(
                rbs.SynthesizedTakeData(
                    pcm44k=pcm44,
                    discrete_audio_tokens=[],
                    seed=seed,
                    temperature=0.28,
                    generation_meta={"engine": "lambda_chatterbox_bf16"},
                )
            )
        return out

    return _synth


def _count_lex_words(text: str) -> int:
    return len(re.findall(r"[A-Za-z0-9']+", text))


def _coalesce_clauses_for_lambda_tts(
    spoken_text: str,
    caller_clauses: list[dict[str, str]] | None,
    *,
    min_words: int = 6,
    max_merged_words: int = 34,
) -> tuple[list[dict[str, str]], list[dict[str, str]], bool]:
    """Coalesce `< min_words` micro-clauses into natural breath groups for zero-shot synthesis & WeSpeaker/DNSMOS."""
    if caller_clauses:
        orig = [
            {
                "id": str(c.get("id") or f"clause_{i}"),
                "spoken_text": str(c.get("spoken_text") or "").strip(),
            }
            for i, c in enumerate(caller_clauses)
            if str(c.get("spoken_text") or "").strip()
        ]
    else:
        parts = [
            p.strip()
            for p in re.split(r"(?<=[.!?])\s+", spoken_text.strip())
            if p.strip() and re.search(r"[A-Za-z0-9]", p)
        ]
        orig = [{"id": f"c_{i + 1:03d}", "spoken_text": p} for i, p in enumerate(parts)]

    if len(orig) <= 1:
        return orig, orig, False

    groups: list[list[dict[str, str]]] = []
    buf: list[dict[str, str]] = []
    buf_words = 0
    for item in orig:
        w = max(1, _count_lex_words(item["spoken_text"]))
        if not buf:
            buf = [item]
            buf_words = w
        elif buf_words < min_words and (buf_words + w) <= max_merged_words:
            buf.append(item)
            buf_words += w
        else:
            groups.append(buf)
            buf = [item]
            buf_words = w
    if buf:
        if groups and buf_words < min_words:
            prev_words = sum(max(1, _count_lex_words(x["spoken_text"])) for x in groups[-1])
            if prev_words + buf_words <= max_merged_words + 6:
                groups[-1].extend(buf)
            else:
                groups.append(buf)
        else:
            groups.append(buf)

    did_merge = len(groups) < len(orig)
    merged = [
        {
            "id": grp[0]["id"],
            "spoken_text": " ".join(x["spoken_text"] for x in grp).strip(),
        }
        for grp in groups
    ]
    return orig, merged, did_merge


def _restore_caller_clause_timings_from_words(
    orig_clauses: list[dict[str, str]],
    words: list[dict[str, Any]],
    duration_ms: int,
) -> list[dict[str, Any]]:
    """Recover monotonic `start_ms` / `end_ms` per original clause from forced-aligned `words`."""
    if not orig_clauses:
        return []
    if not words:
        return _distribute_clause_timings(orig_clauses, duration_ms)

    counts = [max(1, _count_lex_words(c["spoken_text"])) for c in orig_clauses]
    total_expected = max(1, sum(counts))
    n_words = len(words)
    out: list[dict[str, Any]] = []
    cum = 0
    prev_end = 0
    for idx, c in enumerate(orig_clauses):
        w_lo = min(n_words - 1, int(round((cum * n_words) / total_expected)))
        cum += counts[idx]
        w_hi = max(w_lo + 1, min(n_words, int(round((cum * n_words) / total_expected))))
        raw_s = int(words[w_lo].get("start_ms", prev_end))
        raw_e = duration_ms if idx == len(orig_clauses) - 1 else int(words[w_hi - 1].get("end_ms", raw_s))
        s_ms = max(prev_end, min(duration_ms, raw_s))
        e_ms = max(s_ms, min(duration_ms, raw_e))
        prev_end = e_ms
        out.append(
            {
                "id": c["id"],
                "index": idx,
                "spoken_text": c["spoken_text"],
                "start_ms": s_ms,
                "end_ms": e_ms,
            }
        )
    return out


def _validate_strict_tts_metrics(
    metrics: Mapping[str, Any] | None,
    *,
    require_present: bool,
    max_cer: float = LIVE_TTS_GATE_MAX_CER,
) -> None:
    """Enforce 4-gate pass (`passed_all_strict_gates is True` and `cer <= max_cer`)."""
    if metrics is None:
        if require_present:
            raise RuntimeError("TTS synthesis did not produce 4-gate verification metrics.")
        return
    if not isinstance(metrics, Mapping):
        raise RuntimeError("TTS synthesis returned malformed verification metrics.")
    passed = metrics.get("passed_all_strict_gates")
    cer_raw = metrics.get("cer")
    if passed is False or (require_present and passed is not True):
        raise RuntimeError(
            f"TTS synthesis failed strict 4-gate verification (passed_all_strict_gates={passed}, cer={cer_raw})"
        )
    if cer_raw is not None and float(cer_raw) > float(max_cer):
        raise RuntimeError(f"TTS synthesis failed strict CER gate: cer={cer_raw} > {max_cer}")


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
    # 4. Bundle helper assets (master_centroid.bin, room_ir_44k.pcm, room_tone_44k.pcm, lexicon.json, ONNX verifiers)
    centroid_candidates: list[tuple[int, str, Path]] = []
    room_ir_candidates: list[tuple[int, str, Path]] = []
    room_tone_candidates: list[tuple[int, str, Path]] = []
    lexicon_candidates: list[tuple[int, str, Path]] = []
    wespeaker_candidates: list[tuple[int, str, Path]] = []
    dnsmos_candidates: list[tuple[int, str, Path]] = []

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
        lex = bdir / "lexicon.json"
        if lex.is_file() and lex.stat().st_size > 0:
            lexicon_candidates.append((ver_hint, label, lex.resolve()))
        wesp = bdir / "wespeaker_resnet34.onnx"
        if wesp.is_file() and wesp.stat().st_size > 0:
            wespeaker_candidates.append((ver_hint, label, wesp.resolve()))
        dnsm = bdir / "dnsmos_p808.onnx"
        if dnsm.is_file() and dnsm.stat().st_size > 0:
            dnsmos_candidates.append((ver_hint, label, dnsm.resolve()))

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
    lexicon_candidates.sort(key=lambda item: (item[0], item[1]))
    wespeaker_candidates.sort(key=lambda item: (item[0], item[1]))
    dnsmos_candidates.sort(key=lambda item: (item[0], item[1]))

    latest_codec_tflite = codec_candidates[-1][2] if codec_candidates else None
    latest_codec_ver = codec_candidates[-1][0] if codec_candidates else -1

    # 5. PyTorch DAC checkpoint (`dac-finetune-v002/codec_finetuned_v002.pth` when v002+ active, else `s2-pro/codec.pth`)
    finetuned_pth_candidates: list[tuple[int, str, Path]] = []
    if models_root.is_dir():
        for child in models_root.iterdir():
            if not child.is_dir() or not child.name.startswith("dac-finetune-"):
                continue
            ver = _extract_version_num(child.name)
            for pth in child.glob("codec_finetuned_v*.pth"):
                if pth.is_file() and pth.stat().st_size > 0:
                    finetuned_pth_candidates.append((max(ver, _extract_version_num(pth.name)), pth.name, pth.resolve()))
    finetuned_pth_candidates.sort(key=lambda item: (item[0], item[1]))

    base_codec_pth = (models_root / "s2-pro" / "codec.pth").resolve()
    resolved_codec_pth: Path | None = None
    if latest_codec_ver >= 2 and finetuned_pth_candidates:
        resolved_codec_pth = finetuned_pth_candidates[-1][2]
    elif base_codec_pth.is_file() and base_codec_pth.stat().st_size > 0:
        resolved_codec_pth = base_codec_pth
    elif finetuned_pth_candidates:
        resolved_codec_pth = finetuned_pth_candidates[-1][2]

    wespeaker_path = wespeaker_candidates[-1][2] if wespeaker_candidates else None
    if wespeaker_path is None:
        fallback_wesp = models_root / "wespeaker" / "wespeaker_resnet34.onnx"
        if fallback_wesp.is_file() and fallback_wesp.stat().st_size > 0:
            wespeaker_path = fallback_wesp.resolve()

    dnsmos_path = dnsmos_candidates[-1][2] if dnsmos_candidates else None
    if dnsmos_path is None:
        for cand_name in ("model_v8.onnx", "dnsmos_p808.onnx"):
            fallback_dns = models_root / "dnsmos" / cand_name
            if fallback_dns.is_file() and fallback_dns.stat().st_size > 0:
                dnsmos_path = fallback_dns.resolve()
                break

    return {
        "lora_dir": latest_lora_dir,
        "lora_gguf": gguf_candidates[-1][2] if gguf_candidates else None,
        "codec_tflite": latest_codec_tflite,
        "codec_pth": resolved_codec_pth,
        "lexicon": lexicon_candidates[-1][2] if lexicon_candidates else None,
        "master_centroid": centroid_candidates[-1][2] if centroid_candidates else None,
        "room_ir": room_ir_candidates[-1][2] if room_ir_candidates else None,
        "room_tone": room_tone_candidates[-1][2] if room_tone_candidates else None,
        "wespeaker_onnx": wespeaker_path,
        "dnsmos_onnx": dnsmos_path,
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
        tts_runner: Callable[[str, Path], dict[str, Any] | None] | None = None,
        tts_studio_runner: Callable[..., dict[str, Any]] | None = None,
        aligner_fn: Callable[[Path, str], list[dict[str, Any]]] | None = None,
        plate_runner: Callable[[str, str, Path], None] | None = None,
        remotion_worker_url: str | None = None,
    ) -> None:
        self._plate_runner = plate_runner
        self.remotion_worker_url = (
            str(remotion_worker_url).strip().rstrip("/")
            if remotion_worker_url
            else os.environ.get("REMOTION_WORKER_URL", "http://127.0.0.1:8444").strip().rstrip("/")
        )
        self.local_base_url: str | None = None
        self._on_remotion_render_completed: Callable[[], None] | None = None
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
        self._tts_runner = tts_runner
        self._tts_studio_runner = tts_studio_runner
        self._aligner_fn = aligner_fn

        if python_bin is not None:
            self.python_bin = Path(python_bin)
        elif DEFAULT_VENV_PYTHON.is_file():
            self.python_bin = DEFAULT_VENV_PYTHON
        else:
            self.python_bin = Path(sys.executable)

        self._lock = threading.Lock()
        self._tts_lock = threading.Lock()
        self._warm_tts_engine: Any = None
        self._jobs: dict[str, dict[str, Any]] = {}
        self._queue: queue.Queue[tuple[str, dict[str, Any]] | None] = queue.Queue()
        self._worker_thread: threading.Thread | None = None
        self._tts_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="aircheck-tts")
        self._async_executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="aircheck-async")
        self._pending_async_lock = threading.Lock()
        self._pending_async_counts: dict[str, int] = {"tts": 0, "async": 0}
        self._shutdown = False

        self._load_existing_jobs()

    def _load_existing_jobs(self) -> None:
        if not self.jobs_dir.is_dir():
            return
        for path in sorted(self.jobs_dir.glob("*.json")):
            try:
                raw = json.loads(path.read_text(encoding="utf-8"))
                job_id = str(raw.get("job_id") or "").strip()
                if job_id and _SAFE_SEGMENT_RE.match(job_id):
                    if raw.get("status") not in ("READY", "FAILED"):
                        is_async = raw.get("kind") == "async_task"
                        raw["status"] = "FAILED"
                        raw["stage_detail"] = "Failed"
                        raw["error_message"] = (
                            "Rig restarted while this task was running; resubmit."
                            if is_async
                            else "Rig restarted while this job was running; resubmit."
                        )
                        raw["error_kind"] = "rig_restarted"
                        raw["updated_at_utc"] = _utc_now_iso()
                        self._persist_job_locked(raw)
                    self._jobs[job_id] = raw
            except Exception:
                continue

    def submit_async_task(
        self, task_kind: str, fn: Callable[[dict[str, Any]], dict[str, Any]], payload: dict[str, Any]
    ) -> dict[str, Any]:
        """Runs a long rig stage in the background; clients poll ``GET /v1/jobs/<task id>``.

        The task record is persisted, so a phone that lost its process can resume polling the
        same task instead of re-submitting (and re-paying for) the work.
        """
        if not isinstance(payload, dict):
            raise ValueError("Request body must be a JSON object")
        if self._shutdown:
            raise AsyncQueueFullError("Server is shutting down")

        pool_key = "tts" if task_kind == "tts" else "async"
        executor = self._tts_executor if pool_key == "tts" else self._async_executor
        with self._pending_async_lock:
            if self._pending_async_counts[pool_key] >= MAX_PENDING_ASYNC_TASKS:
                raise AsyncQueueFullError(
                    f"Bounded {pool_key} async queue full ({MAX_PENDING_ASYNC_TASKS} pending); retry shortly."
                )
            self._pending_async_counts[pool_key] += 1

        task_id = f"task_{uuid.uuid4().hex[:16]}"
        now_iso = _utc_now_iso()
        record = {
            "job_id": task_id,
            "kind": "async_task",
            "task_kind": task_kind,
            "status": "RUNNING",
            "progress_fraction": 0.0,
            "stage_detail": f"{task_kind} running",
            "error_message": None,
            "result": None,
            "created_at_utc": now_iso,
            "updated_at_utc": now_iso,
        }
        with self._lock:
            self._jobs[task_id] = record
            self._persist_job_locked(record)

        def _run() -> None:
            try:
                result = fn(payload)
                patch = {"status": "READY", "progress_fraction": 1.0, "stage_detail": "Ready", "result": result}
            except Exception as exc:  # noqa: BLE001 - reported to the client verbatim
                patch = {
                    "status": "FAILED",
                    "stage_detail": "Failed",
                    "error_message": str(exc) or exc.__class__.__name__,
                    "error_kind": "invalid_request" if isinstance(exc, ValueError) else "task_failed",
                }
            finally:
                with self._pending_async_lock:
                    self._pending_async_counts[pool_key] = max(0, self._pending_async_counts[pool_key] - 1)
            with self._lock:
                current = dict(self._jobs.get(task_id) or record)
                current.update(patch)
                current["updated_at_utc"] = _utc_now_iso()
                self._jobs[task_id] = current
                self._persist_job_locked(current)

        try:
            executor.submit(_run)
        except Exception as exc:
            with self._pending_async_lock:
                self._pending_async_counts[pool_key] = max(0, self._pending_async_counts[pool_key] - 1)
            with self._lock:
                current = dict(self._jobs.get(task_id) or record)
                current.update(
                    {
                        "status": "FAILED",
                        "stage_detail": "Failed",
                        "error_message": str(exc) or exc.__class__.__name__,
                        "error_kind": "queue_unavailable",
                        "updated_at_utc": _utc_now_iso(),
                    }
                )
                self._jobs[task_id] = current
                self._persist_job_locked(current)
            raise AsyncQueueFullError(str(exc) or "Async executor unavailable") from exc
        return {"job_id": task_id, "status": "RUNNING", "task_kind": task_kind}

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
        self._tts_executor.shutdown(wait=False, cancel_futures=True)
        self._async_executor.shutdown(wait=False, cancel_futures=True)

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
        error_kind: str | None = ...,  # type: ignore[assignment]
        video_url: str | None = ...,  # type: ignore[assignment]
        align_url: str | None = ...,  # type: ignore[assignment]
        words: list[dict[str, Any]] | None = ...,  # type: ignore[assignment]
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
            if error_kind is not ...:
                job["error_kind"] = error_kind
            if video_url is not ...:
                job["video_url"] = video_url
            if align_url is not ...:
                job["align_url"] = align_url
            if words is not ...:
                job["words"] = words
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

    def list_renders(self) -> list[dict[str, Any]]:
        """Lists completed MP4 clips in `renders_dir`, newest first, with thumbnail URLs."""
        self.renders_dir.mkdir(parents=True, exist_ok=True)
        jobs_by_id: dict[str, dict[str, Any]] = {}
        with self._lock:
            for jid, rec in self._jobs.items():
                jobs_by_id[jid] = dict(rec)

        items: list[dict[str, Any]] = []
        for path in sorted(
            self.renders_dir.glob("*.mp4"),
            key=lambda p: p.stat().st_mtime,
            reverse=True,
        ):
            name = path.name
            if not path.is_file() or path.stat().st_size == 0:
                continue
            if (
                name.endswith("_raw_wan.mp4")
                or name.endswith("_exact_input_voice.mp4")
                or name.endswith("_wan_audio.mp4")
                or name.endswith("_v2_lipsync.mp4")
            ):
                continue
            stem = path.stem
            job_rec = jobs_by_id.get(stem)
            if job_rec is not None and job_rec.get("status") != "READY":
                continue
            if job_rec and job_rec.get("title"):
                title = str(job_rec["title"])
            else:
                title = stem.replace("_", " ").strip().title()
            st = path.stat()
            dur_ms = int(job_rec.get("duration_ms") or 0) if job_rec else 0
            if dur_ms <= 0:
                dur_ms = _probe_media_duration_ms(path)
            updated_utc = datetime.datetime.fromtimestamp(
                st.st_mtime, tz=datetime.timezone.utc
            ).strftime("%Y-%m-%dT%H:%M:%SZ")
            items.append(
                {
                    "id": stem,
                    "filename": name,
                    "title": title,
                    "duration_ms": dur_ms,
                    "size_bytes": st.st_size,
                    "updated_at_utc": updated_utc,
                    "video_url": f"/v1/renders/{name}",
                    "thumb_url": f"/v1/render-thumbs/{stem}.jpg",
                }
            )
        return items

    def get_or_create_render_thumb(self, thumb_filename: str) -> Path | None:
        """Lazily extracts a 360px wide JPEG thumbnail for `{stem}.mp4`."""
        if not thumb_filename.endswith(".jpg"):
            raise PermissionError("Thumbnail must end with .jpg")
        if not _SAFE_SEGMENT_RE.match(thumb_filename) or thumb_filename in (".", ".."):
            raise PermissionError(f"Unsafe thumbnail filename rejected: {thumb_filename!r}")
        stem = thumb_filename[: -len(".jpg")]
        mp4_path = self.resolve_render_file(f"{stem}.mp4")
        if mp4_path is None:
            return None
        thumbs_dir = (self.renders_dir / ".thumbs").resolve()
        thumbs_dir.mkdir(parents=True, exist_ok=True)
        thumb_path = (thumbs_dir / thumb_filename).resolve()
        if not thumb_path.is_relative_to(thumbs_dir):
            raise PermissionError("Path traversal outside thumbs directory rejected")
        if thumb_path.is_file() and thumb_path.stat().st_mtime >= mp4_path.stat().st_mtime:
            return thumb_path
        tmp_jpg = thumbs_dir / f".{thumb_filename}.tmp.{os.getpid()}.jpg"
        try:
            proc = subprocess.run(
                [
                    "ffmpeg",
                    "-y",
                    "-ss",
                    "0.4",
                    "-i",
                    str(mp4_path),
                    "-frames:v",
                    "1",
                    "-vf",
                    "scale=360:-1",
                    "-q:v",
                    "4",
                    str(tmp_jpg),
                ],
                capture_output=True,
                text=True,
                timeout=30,
                check=False,
            )
            if proc.returncode == 0 and tmp_jpg.is_file() and tmp_jpg.stat().st_size > 0:
                os.replace(tmp_jpg, thumb_path)
                return thumb_path
        except (subprocess.TimeoutExpired, OSError):
            LOGGER.warning("Thumbnail generation failed or timed out for %s", mp4_path)
            return None
        finally:
            if tmp_jpg.exists():
                tmp_jpg.unlink(missing_ok=True)
        return None

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

        raw_resolution = str(payload.get("resolution") or "720P").strip().upper()
        if raw_resolution not in VALID_RESOLUTIONS:
            raise ValueError(
                f"resolution must be one of {sorted(VALID_RESOLUTIONS)}, got {raw_resolution!r}"
            )
        resolution = normalize_talking_head_resolution(raw_resolution)

        ratio = str(payload.get("ratio") or "9:16").strip()
        if ratio not in VALID_RATIOS:
            raise ValueError(
                f"ratio must be one of {sorted(VALID_RATIOS)}, got {ratio!r}"
            )

        max_duration_raw = payload.get("max_duration", 10)
        if isinstance(max_duration_raw, bool) or not isinstance(max_duration_raw, int):
            raise ValueError(f"max_duration must be an integer between 2 and {MAX_RENDER_DURATION_SEC}")
        if not (2 <= max_duration_raw <= MAX_RENDER_DURATION_SEC):
            raise ValueError(f"max_duration must be between 2 and {MAX_RENDER_DURATION_SEC} seconds")

        raw_audio_b64 = payload.get("audio_wav_base64")
        raw_photo_b64 = payload.get("photo_jpeg_base64")

        audio_bytes: bytes | None = None
        if raw_audio_b64 is not None and str(raw_audio_b64).strip() != "":
            audio_bytes = _decode_strict_base64(raw_audio_b64, "audio_wav_base64")

        if voice_source == "MIC_VOICEOVER" and audio_bytes is None:
            raise ValueError("audio_wav_base64 is required when voice_source is MIC_VOICEOVER")
        if voice_source == "ENROLLED_VOICE_CLONE" and not spoken_text and audio_bytes is None:
            raise ValueError("spoken_text is required when voice_source is ENROLLED_VOICE_CLONE")

        raw_clauses = payload.get("clauses")
        caller_clauses: list[dict[str, Any]] | None = None
        if raw_clauses is not None:
            if not isinstance(raw_clauses, list) or any(not isinstance(c, dict) for c in raw_clauses):
                raise ValueError("clauses must be a list of JSON objects")
            caller_clauses = [dict(c) for c in raw_clauses]

        raw_words = payload.get("words")
        caller_words: list[dict[str, Any]] | None = None
        if raw_words is not None:
            if not isinstance(raw_words, list) or any(not isinstance(w, dict) for w in raw_words):
                raise ValueError("words must be a list of JSON objects")
            caller_words = [dict(w) for w in raw_words]

        raw_align_url = payload.get("align_url")
        caller_align_url: str | None = None
        if raw_align_url is not None:
            if not isinstance(raw_align_url, str):
                raise ValueError("align_url must be a string")
            caller_align_url = raw_align_url.strip() or None

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

        chyron_headline = str(payload.get("chyron_headline") or "").strip()[:72]
        chyron_subline = str(payload.get("chyron_subline") or "").strip()[:96]
        default_bug = bool(chyron_headline or chyron_subline) and voice_source != "MIC_VOICEOVER"
        show_brand_bug = bool(payload["show_brand_bug"]) if "show_brand_bug" in payload else default_bug

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
            "chyron_headline": chyron_headline,
            "chyron_subline": chyron_subline,
            "show_brand_bug": show_brand_bug,
            "clauses": caller_clauses,
            "words": caller_words,
            "align_url": caller_align_url,
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
        align_url = result.get("align_url")
        words = result.get("words")
        self._update_job(
            job_id,
            status="READY",
            progress_fraction=1.0,
            stage_detail="Ready",
            error_message=None,
            video_url=f"/v1/renders/{job_id}.mp4",
            align_url=align_url,
            words=words,
            duration_ms=duration_ms,
            metrics=metrics,
        )

    def _get_warm_tts_engine(self) -> Any:
        if self._warm_tts_engine is None:
            daemon_dir = str(Path(__file__).resolve().parent)
            if daemon_dir not in sys.path:
                sys.path.insert(0, daemon_dir)
            import s2_pro_infer

            self._warm_tts_engine = s2_pro_infer.get_shared_warm_engine()
        return self._warm_tts_engine

    def _run_broadcast_tts_stage(
        self,
        *,
        project_id: str,
        spoken_text: str,
        caller_clauses: list[dict[str, str]] | None,
        studio_dir: Path,
        assets: dict[str, Path | None],
        takes_per_clause: int = 4,
    ) -> dict[str, Any]:
        with self._tts_lock:
            if self._tts_studio_runner is not None:
                return dict(
                    self._tts_studio_runner(
                        project_id=project_id,
                        spoken_text=spoken_text,
                        caller_clauses=caller_clauses,
                        output_dir=studio_dir,
                        assets=assets,
                    )
                )

            daemon_dir = str(Path(__file__).resolve().parent)
            if daemon_dir not in sys.path:
                sys.path.insert(0, daemon_dir)
            import render_broadcast_studio as rbs

            voice_endpoint = os.environ.get("AIRCHECK_VOICE_ENDPOINT", "http://127.0.0.1:8452").strip()
            lambda_synth_fn = _build_lambda_voice_synthesizer_fn(voice_endpoint) if voice_endpoint else None

            eff_caller_clauses = caller_clauses
            orig_clauses_for_unmerge: list[dict[str, str]] | None = None
            if lambda_synth_fn is not None:
                orig_c, merged_c, did_merge = _coalesce_clauses_for_lambda_tts(spoken_text, caller_clauses)
                if merged_c:
                    eff_caller_clauses = merged_c
                if caller_clauses and did_merge:
                    orig_clauses_for_unmerge = orig_c

            kwargs: dict[str, Any] = {
                "script_text": spoken_text,
                "caller_clauses": eff_caller_clauses,
                "project_id": project_id,
                "output_dir": studio_dir,
                "takes_per_clause": 1 if lambda_synth_fn is not None else takes_per_clause,
                "skip_video": True,
                "require_strict_gates": True,
                "require_real_alignment": True,
                "warm_engine": None if lambda_synth_fn is not None else self._get_warm_tts_engine(),
                "synthesizer_fn": lambda_synth_fn,
                "early_exit_on_pass": True,
                "min_clause_words": 6 if lambda_synth_fn is not None else 0,
                "gate_max_cer": LIVE_TTS_GATE_MAX_CER,
                "gate_min_speaker_cos": LIVE_TTS_GATE_MIN_SPEAKER_COS,
                "gate_min_dnsmos_p808": LIVE_TTS_GATE_MIN_DNSMOS_P808,
                "gate_max_tail_rms_dbfs": LIVE_TTS_GATE_MAX_TAIL_RMS_DBFS,
                "gate_min_syllables_per_sec": LIVE_TTS_GATE_MIN_SYLLABLES_PER_SEC,
                "gate_max_syllables_per_sec": LIVE_TTS_GATE_MAX_SYLLABLES_PER_SEC,
                "gate_max_phoneme_ms": LIVE_TTS_GATE_MAX_PHONEME_MS,
            }
            if assets.get("lexicon") is not None:
                kwargs["lexicon_path"] = assets["lexicon"]
            if assets.get("lora_dir") is not None:
                kwargs["lora_dir"] = assets["lora_dir"]
            if assets.get("codec_tflite") is not None:
                kwargs["dac_tflite"] = assets["codec_tflite"]
            if assets.get("codec_pth") is not None:
                kwargs["codec_pth"] = assets["codec_pth"]
            if assets.get("master_centroid") is not None:
                kwargs["master_centroid_path"] = assets["master_centroid"]
            if assets.get("wespeaker_onnx") is not None:
                kwargs["wespeaker_onnx"] = assets["wespeaker_onnx"]
            if assets.get("dnsmos_onnx") is not None:
                kwargs["dnsmos_onnx"] = assets["dnsmos_onnx"]
            if assets.get("room_ir") is not None:
                kwargs["room_ir_path"] = assets["room_ir"]
            if assets.get("room_tone") is not None:
                kwargs["room_tone_path"] = assets["room_tone"]

            studio_res = rbs.render_broadcast_studio(**kwargs)
            if orig_clauses_for_unmerge is not None:
                dur_ms = int(round(float(studio_res.get("duration_sec") or 0.0) * 1000.0))
                if dur_ms <= 0 and studio_res.get("master_wav_path"):
                    dur_ms, _ = _measure_wav_duration_and_peaks(Path(studio_res["master_wav_path"]), num_bins=16)
                studio_res["timed_clauses"] = _restore_caller_clause_timings_from_words(
                    orig_clauses_for_unmerge,
                    list(studio_res.get("words") or []),
                    dur_ms,
                )
            return studio_res

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
        align_url: str | None = None
        words: list[dict[str, Any]] | None = None
        timed_clauses: list[dict[str, Any]] = []
        if spec.get("audio_wav_path") is not None:
            voice_wav = Path(spec["audio_wav_path"])
            if not voice_wav.is_file() or voice_wav.stat().st_size == 0:
                raise FileNotFoundError(f"Uploaded audio WAV missing or empty: {voice_wav}")
            if isinstance(spec.get("words"), list):
                words = list(spec["words"])
            if isinstance(spec.get("clauses"), list):
                timed_clauses = list(spec["clauses"])
            align_url = spec.get("align_url")
            if words and not align_url:
                pre_dur_ms = _probe_media_duration_ms(voice_wav)
                if pre_dur_ms <= 0:
                    pre_dur_ms, _ = _measure_wav_duration_and_peaks(voice_wav, num_bins=16)
                align_filename = f"align_{spec['job_id']}.json"
                _atomic_write_json(
                    _build_alignment_payload(str(spec["job_id"]), pre_dur_ms, words, timed_clauses),
                    self.renders_dir / align_filename,
                )
                align_url = f"/v1/renders/{align_filename}"
            report_progress(
                status="VOICE",
                progress_fraction=0.25,
                stage_detail=(
                    "Prepared recorded mic voiceover WAV"
                    if voice_source == "MIC_VOICEOVER"
                    else "Prepared pre-synthesized voiceover WAV"
                ),
            )
        else:
            if not re.search(r"[A-Za-z0-9]", spoken_text):
                raise ValueError("spoken_text must contain speakable words")
            assets = resolve_latest_synthesis_assets(self.models_root, self.bundles_root)
            synth_wav = work_dir / "synthesized_voice.wav"
            studio_dir = work_dir / "studio"

            lora_label = assets["lora_dir"].parent.name if assets.get("lora_dir") else "default"
            report_progress(
                status="VOICE",
                progress_fraction=0.20,
                stage_detail=f"Synthesizing multi-clause voice ({lora_label})",
            )

            caller_clauses_arg: list[dict[str, str]] | None = None
            if isinstance(spec.get("clauses"), list):
                cleaned_clauses = [
                    {
                        "id": str(c.get("id") or f"clause_{idx}"),
                        "spoken_text": str(c.get("spoken_text") or "").strip(),
                    }
                    for idx, c in enumerate(spec["clauses"])
                    if isinstance(c, Mapping) and str(c.get("spoken_text") or "").strip()
                ]
                if cleaned_clauses:
                    caller_clauses_arg = cleaned_clauses

            studio_report = self._run_broadcast_tts_stage(
                project_id=str(spec["job_id"]),
                spoken_text=spoken_text,
                caller_clauses=caller_clauses_arg,
                studio_dir=studio_dir,
                assets=assets,
            )
            master_wav_raw = studio_report.get("master_wav_path")
            if not master_wav_raw:
                raise RuntimeError("Broadcast studio TTS completed without master_wav_path")
            master_wav = Path(master_wav_raw)
            if not master_wav.is_file() or master_wav.stat().st_size <= 44:
                raise RuntimeError(f"Broadcast studio TTS produced missing/empty WAV: {master_wav}")
            if master_wav.resolve() != synth_wav.resolve():
                _atomic_publish_file(master_wav, synth_wav)

            raw_metrics = studio_report.get("metrics")
            _validate_strict_tts_metrics(raw_metrics, require_present=True)
            assert isinstance(raw_metrics, Mapping)
            metrics = dict(raw_metrics)
            if assets.get("lora_dir") and not metrics.get("lora_dir"):
                metrics["lora_dir"] = str(assets["lora_dir"])
            if assets.get("lora_gguf") and not metrics.get("lora_gguf"):
                metrics["lora_gguf"] = str(assets["lora_gguf"])
            if assets.get("codec_tflite") and not metrics.get("codec_tflite"):
                metrics["codec_tflite"] = str(assets["codec_tflite"])

            words = list(studio_report.get("words") or [])
            if not words and self._aligner_fn is not None:
                words = list(self._aligner_fn(synth_wav, spoken_text))
            if not words:
                raise RuntimeError("TTS forced word alignment produced zero words.")

            timed_clauses = list(
                studio_report.get("timed_clauses")
                or studio_report.get("clauses")
                or []
            )
            voice_dur_ms = int(studio_report.get("duration_ms") or _probe_media_duration_ms(synth_wav))
            if voice_dur_ms <= 0:
                voice_dur_ms, _ = _measure_wav_duration_and_peaks(synth_wav, num_bins=16)

            align_filename = f"align_{spec['job_id']}.json"
            _atomic_write_json(
                _build_alignment_payload(str(spec["job_id"]), voice_dur_ms, words, timed_clauses),
                self.renders_dir / align_filename,
            )
            align_url = f"/v1/renders/{align_filename}"
            voice_wav = synth_wav

        head_script = daemon_dir / "generate_talking_head.py"
        if not head_script.is_file():
            raise FileNotFoundError(f"generate_talking_head.py not found at {head_script}")

        report_progress(
            status="LIPSYNC",
            progress_fraction=0.60,
            stage_detail=f"Rendering {resolution} talking-head video (LivePortrait + LatentSync 1.6)",
            metrics=metrics,
        )

        voice_ms = _probe_media_duration_ms(voice_wav)
        if voice_ms <= 0:
            try:
                voice_ms, _ = _measure_wav_duration_and_peaks(voice_wav, num_bins=16)
            except Exception:
                voice_ms = 0

        seg_mp4 = work_dir / "talking_head.mp4"
        head_cmd = [
            python_exe,
            str(head_script),
            "--voice-wav",
            str(voice_wav),
            "--avatar",
            str(avatar_path),
            "--output",
            str(seg_mp4),
            "--resolution",
            resolution,
            "--ratio",
            ratio,
            "--max-duration",
            str(max_duration),
        ]
        if spoken_text:
            head_cmd.extend(["--transcript", spoken_text])
        if timed_clauses:
            clauses_json = work_dir / "talking_head_clauses.json"
            clauses_json.write_text(json.dumps(timed_clauses, ensure_ascii=False), encoding="utf-8")
            head_cmd.extend(["--clauses-json", str(clauses_json)])
        if words:
            words_json = work_dir / "talking_head_words.json"
            words_json.write_text(json.dumps(words, ensure_ascii=False), encoding="utf-8")
            head_cmd.extend(["--words-json", str(words_json)])
        head_timeout_sec = max(TALKING_HEAD_TIMEOUT_BASE_SEC, max_duration * 25)
        try:
            head_proc = subprocess.run(
                head_cmd,
                capture_output=True,
                text=True,
                timeout=head_timeout_sec,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise RuntimeError(f"generate_talking_head.py timed out after {head_timeout_sec}s") from exc

        if head_proc.returncode != 0:
            raise RuntimeError(_summarize_subprocess_error("generate_talking_head.py", head_proc))
        head_meta: dict[str, Any] = {}
        stdout_text = (head_proc.stdout or "").strip()
        if stdout_text:
            try:
                parsed_meta = json.loads(stdout_text)
            except json.JSONDecodeError as exc:
                LOGGER.error("generate_talking_head.py returned invalid JSON stdout: %s", stdout_text[:300])
                raise RuntimeError(
                    f"generate_talking_head.py returned invalid JSON stdout: {exc}"
                ) from exc
            if not isinstance(parsed_meta, dict):
                raise RuntimeError(
                    f"generate_talking_head.py returned non-object JSON stdout: {type(parsed_meta).__name__}"
                )
            head_meta = parsed_meta

        exact_raw = str(head_meta.get("exact_input_voice_mp4") or "").strip()
        if exact_raw:
            exact_mp4 = Path(exact_raw)
            if not (exact_mp4.is_file() and exact_mp4.stat().st_size > 0):
                raise RuntimeError(
                    f"generate_talking_head.py reported exact_input_voice_mp4={exact_mp4} but file is missing or empty"
                )
            chosen_mp4 = exact_mp4
        elif seg_mp4.is_file() and seg_mp4.stat().st_size > 0:
            chosen_mp4 = seg_mp4
        else:
            raise RuntimeError(
                f"generate_talking_head.py exited 0 but did not produce {seg_mp4}"
            )

        chyron_headline = str(spec.get("chyron_headline") or "").strip()
        chyron_subline = str(spec.get("chyron_subline") or "").strip()
        show_brand_bug = bool(spec.get("show_brand_bug", False))
        if chyron_headline or chyron_subline or show_brand_bug:
            report_progress(
                status="PACKAGING",
                progress_fraction=0.88,
                stage_detail="Adding breaking news graphics",
                metrics=metrics,
            )
            branded_mp4 = work_dir / "talking_head_branded.mp4"
            chosen_mp4 = _burn_breaking_news_chyron(
                src_mp4=chosen_mp4,
                out_mp4=branded_mp4,
                headline=chyron_headline or str(spec.get("title") or "BREAKING NEWS").upper(),
                subline=chyron_subline or "AIRCHECK LIVE DESK",
                show_live_bug=show_brand_bug,
            )

        staged_mp4 = work_dir / f"{spec['job_id']}_staged.mp4"
        _atomic_publish_mp4(chosen_mp4, staged_mp4)
        expected_dur_ms = (
            min(voice_ms, max_duration * 1000)
            if voice_ms > 0
            else None
        )
        av_qa = _probe_av_sync_metrics(staged_mp4, expected_duration_ms=expected_dur_ms)
        metrics_out: dict[str, Any] = dict(metrics) if metrics is not None else {}
        metrics_out["av_sync_drift_ms"] = av_qa["av_sync_drift_ms"]
        metrics_out["video_duration_ms"] = av_qa["video_duration_ms"]
        metrics_out["audio_duration_ms"] = av_qa["audio_duration_ms"]
        if isinstance(head_meta.get("identity_qa"), Mapping):
            metrics_out["identity_qa"] = dict(head_meta["identity_qa"])
        if head_meta.get("engine"):
            metrics_out["video_engine"] = str(head_meta["engine"])

        duration_ms = _probe_media_duration_ms(staged_mp4)
        if duration_ms <= 0:
            duration_ms = av_qa["audio_duration_ms"]
        if duration_ms <= 0 and isinstance(head_meta.get("duration_sec"), (int, float)):
            duration_ms = max(0, int(round(float(head_meta["duration_sec"]) * 1000.0)))

        target_mp4 = self.renders_dir / f"{spec['job_id']}.mp4"
        _atomic_publish_file(staged_mp4, target_mp4)

        return {
            "output_mp4": target_mp4,
            "duration_ms": duration_ms,
            "metrics": metrics_out,
            "align_url": align_url,
            "words": words,
        }

    def synthesize_story_tts(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Stage 1 of the Core Pipeline: synthesizes spoken WAV + real waveform & forced alignment."""
        if not isinstance(payload, dict):
            raise ValueError("TTS request body must be a JSON object")

        raw_clauses = payload.get("clauses")
        clauses_in: list[dict[str, str]] = []
        if isinstance(raw_clauses, list):
            for idx, item in enumerate(raw_clauses):
                if isinstance(item, dict):
                    txt = str(item.get("spoken_text") or "").strip()
                    cid = str(item.get("id") or f"clause_{idx}")
                    if txt and re.search(r"[A-Za-z0-9]", txt):
                        clauses_in.append({"id": cid, "spoken_text": txt})

        spoken_text = str(payload.get("spoken_text") or "").strip()
        if not clauses_in and spoken_text:
            parts = [
                p.strip()
                for p in re.split(r"(?<=[.!?])\s+", spoken_text)
                if p.strip() and re.search(r"[A-Za-z0-9]", p)
            ]
            clauses_in = [
                {"id": f"clause_{idx}", "spoken_text": part}
                for idx, part in enumerate(parts)
            ]
        if not clauses_in:
            raise ValueError("spoken_text must contain speakable words")

        joined_text = " ".join(c["spoken_text"] for c in clauses_in).strip()
        tts_id = f"tts_{uuid.uuid4().hex[:16]}"
        self.renders_dir.mkdir(parents=True, exist_ok=True)
        work_dir = self.jobs_dir / tts_id
        work_dir.mkdir(parents=True, exist_ok=True)
        out_wav = self.renders_dir / f"{tts_id}.wav"

        metrics: dict[str, Any] | None = None
        words: list[dict[str, Any]] = []
        timed_clauses: list[dict[str, Any]] = []
        source_wav: Path

        if self._tts_runner is not None:
            staged_wav = work_dir / f"{tts_id}.wav"
            with self._tts_lock:
                metrics = self._tts_runner(joined_text, staged_wav)
            _validate_strict_tts_metrics(metrics, require_present=False)
            if not staged_wav.is_file() or staged_wav.stat().st_size <= 44:
                raise RuntimeError("TTS synthesis did not produce a valid WAV file.")
            duration_ms, peaks = _measure_wav_duration_and_peaks(staged_wav, num_bins=64)
            if isinstance(metrics, Mapping) and isinstance(metrics.get("clauses"), list):
                timed_clauses = list(metrics["clauses"])
            else:
                timed_clauses = _distribute_clause_timings(clauses_in, duration_ms)
            if isinstance(metrics, Mapping) and isinstance(metrics.get("words"), list):
                words = list(metrics["words"])
            elif self._aligner_fn is not None:
                words = list(self._aligner_fn(staged_wav, joined_text))
            source_wav = staged_wav
        else:
            assets = resolve_latest_synthesis_assets(self.models_root, self.bundles_root)
            studio_report = self._run_broadcast_tts_stage(
                project_id=str(payload.get("project_id") or tts_id),
                spoken_text=joined_text,
                caller_clauses=clauses_in,
                studio_dir=work_dir / "studio",
                assets=assets,
            )
            master_wav_raw = studio_report.get("master_wav_path")
            if not master_wav_raw:
                raise RuntimeError("Broadcast studio TTS completed without master_wav_path")
            master_wav = Path(master_wav_raw)
            if not master_wav.is_file() or master_wav.stat().st_size <= 44:
                raise RuntimeError("TTS synthesis did not produce a valid WAV file.")

            raw_metrics = studio_report.get("metrics")
            _validate_strict_tts_metrics(raw_metrics, require_present=True)
            assert isinstance(raw_metrics, Mapping)
            metrics = dict(raw_metrics)
            if assets.get("lora_dir") and not metrics.get("lora_dir"):
                metrics["lora_dir"] = str(assets["lora_dir"])
            if assets.get("lora_gguf") and not metrics.get("lora_gguf"):
                metrics["lora_gguf"] = str(assets["lora_gguf"])
            if assets.get("codec_tflite") and not metrics.get("codec_tflite"):
                metrics["codec_tflite"] = str(assets["codec_tflite"])

            words = list(studio_report.get("words") or [])
            if not words and self._aligner_fn is not None:
                words = list(self._aligner_fn(master_wav, joined_text))
            if not words:
                raise RuntimeError("Forced word alignment produced zero words.")

            timed_clauses = list(
                studio_report.get("timed_clauses")
                or studio_report.get("clauses")
                or []
            )
            if not timed_clauses:
                raise RuntimeError("Broadcast studio TTS produced zero timed clauses.")

            duration_ms, peaks = _measure_wav_duration_and_peaks(master_wav, num_bins=64)
            source_wav = master_wav

        align_filename = f"align_{tts_id}.json"
        align_payload = _build_alignment_payload(tts_id, duration_ms, words, timed_clauses)
        _atomic_publish_file(source_wav, out_wav)
        _atomic_write_json(align_payload, self.renders_dir / align_filename)
        return {
            "tts_id": tts_id,
            "audio_url": f"/v1/renders/{tts_id}.wav",
            "duration_ms": duration_ms,
            "waveform_peaks": peaks,
            "clauses": timed_clauses,
            "words": words,
            "align_url": f"/v1/renders/{align_filename}",
            "metrics": metrics,
        }

    def chop_talking_head_into_broll(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Stage 4 of the Core Pipeline: chops speech-matched talking head into B-roll + optional saved overlays."""
        if not isinstance(payload, dict):
            raise ValueError("Chop B-roll request body must be a JSON object")

        source_job_id = str(payload.get("source_job_id") or "").strip()
        raw_mp4_b64 = payload.get("source_mp4_base64")
        title = str(payload.get("title") or "Story Cut").strip() or "Story Cut"

        final_id = f"clip_{uuid.uuid4().hex[:16]}"
        work_dir = self.jobs_dir / final_id
        work_dir.mkdir(parents=True, exist_ok=True)
        self.renders_dir.mkdir(parents=True, exist_ok=True)

        if raw_mp4_b64 is not None and str(raw_mp4_b64).strip() != "":
            src_bytes = _decode_strict_base64(raw_mp4_b64, "source_mp4_base64")
            src_mp4 = work_dir / "source_head.mp4"
            src_mp4.write_bytes(src_bytes)
        elif source_job_id:
            resolved = self.resolve_render_file(f"{source_job_id}.mp4")
            if resolved is None:
                raise ValueError(f"Talking-head render '{source_job_id}.mp4' not found on rig.")
            src_mp4 = resolved
        else:
            raise ValueError("Either source_job_id or source_mp4_base64 is required.")

        raw_clauses = payload.get("clauses")
        clauses = raw_clauses if isinstance(raw_clauses, list) else []
        raw_overlays = payload.get("overlays")
        overlays = raw_overlays if isinstance(raw_overlays, list) else []

        staged_out = work_dir / "chopped_final.mp4"
        studio_stats: dict[str, Any] = {}
        render_opts_raw = payload.get("render_options") if isinstance(payload.get("render_options"), dict) else {}
        use_remotion = bool(payload.get("use_remotion") or render_opts_raw.get("use_remotion"))
        if use_remotion:
            studio_stats = self._compose_via_remotion_worker(
                final_id=final_id,
                source_job_id=source_job_id,
                src_mp4=src_mp4,
                out_mp4=staged_out,
                title=title,
                clauses=clauses,
                overlays=overlays,
                payload=payload,
                render_opts_raw=render_opts_raw,
            )
            produced_mp4 = staged_out
            broll_cuts = int(studio_stats["broll_cut_count"])
            applied_overlays = int(studio_stats["applied_overlay_count"])
        elif "render_options" in payload:
            options = StudioRenderOptions.from_payload(payload.get("render_options"))
            studio_stats = compose_studio_cut(
                src_mp4=src_mp4,
                out_mp4=staged_out,
                work_dir=work_dir,
                clauses=clauses,
                overlays=overlays,
                options=options,
                plates_dir=self.plates_dir,
                probe_duration_ms=_probe_media_duration_ms,
            )
            studio_stats["remotion_used"] = False
            studio_stats["renderer"] = "ffmpeg"
            produced_mp4 = staged_out
            broll_cuts = int(studio_stats["broll_cut_count"])
            applied_overlays = int(studio_stats["applied_overlay_count"])
        else:
            produced_mp4, broll_cuts, applied_overlays = _compose_broll_and_saved_overlays_mp4(
                src_mp4=src_mp4,
                out_mp4=staged_out,
                work_dir=work_dir,
                clauses=clauses,
                overlays=overlays,
            )
            studio_stats["remotion_used"] = False
            studio_stats["renderer"] = "ffmpeg"
        target_mp4 = self.renders_dir / f"{final_id}.mp4"
        _atomic_publish_mp4(produced_mp4, target_mp4)
        duration_ms = _probe_media_duration_ms(target_mp4)
        now_iso = _utc_now_iso()
        with self._lock:
            self._jobs[final_id] = {
                "job_id": final_id,
                "script_id": str(payload.get("script_id") or ""),
                "title": title,
                "voice_source": "ENROLLED_VOICE_CLONE",
                "status": "READY",
                "progress_fraction": 1.0,
                "stage_detail": "Ready",
                "error_message": None,
                "video_url": f"/v1/renders/{final_id}.mp4",
                "duration_ms": duration_ms,
                "metrics": None,
                "created_at_utc": now_iso,
                "updated_at_utc": now_iso,
            }
            self._persist_job_locked(self._jobs[final_id])

        return {
            "job_id": final_id,
            "status": "READY",
            "video_url": f"/v1/renders/{final_id}.mp4",
            "duration_ms": duration_ms,
            "broll_cut_count": broll_cuts,
            "applied_overlay_count": applied_overlays,
            **{k: v for k, v in studio_stats.items() if k not in ("broll_cut_count", "applied_overlay_count")},
        }

    def _compose_via_remotion_worker(
        self,
        *,
        final_id: str,
        source_job_id: str,
        src_mp4: Path,
        out_mp4: Path,
        title: str,
        clauses: list[Any],
        overlays: list[Any],
        payload: dict[str, Any],
        render_opts_raw: dict[str, Any],
    ) -> dict[str, Any]:
        """Delegates Stage 4 broadcast composition to the local Remotion 4 worker (`:8444/render`)."""
        base_url = (self.local_base_url or "").strip().rstrip("/")
        if not base_url:
            raise RuntimeError("Remotion 4 render requested, but local HTTP base URL is unavailable.")
        if not self.remotion_worker_url:
            raise RuntimeError("Remotion 4 render requested, but REMOTION_WORKER_URL is not configured.")

        def _resolve_url(raw: str) -> str:
            s = raw.strip()
            if s.startswith("http://") or s.startswith("https://"):
                return s
            if s.startswith("/"):
                return f"{base_url}{s}"
            return f"{base_url}/v1/renders/{s}"

        temp_staged: list[Path] = []
        try:
            if source_job_id and (self.renders_dir / f"{source_job_id}.mp4").is_file():
                lipsync_url = f"{base_url}/v1/renders/{source_job_id}.mp4"
            else:
                staged_src = self.renders_dir / f"{final_id}_src.mp4"
                shutil.copy2(src_mp4, staged_src)
                temp_staged.append(staged_src)
                lipsync_url = f"{base_url}/v1/renders/{staged_src.name}"

            raw_voice_url = str(payload.get("voice_url") or "").strip()
            if raw_voice_url:
                voice_url = _resolve_url(raw_voice_url)
            else:
                staged_wav = self.renders_dir / f"{final_id}_voice.wav"
                extract_proc = subprocess.run(
                    [
                        "ffmpeg",
                        "-y",
                        "-i",
                        str(src_mp4),
                        "-vn",
                        "-acodec",
                        "pcm_s16le",
                        "-ar",
                        "48000",
                        "-ac",
                        "1",
                        str(staged_wav),
                    ],
                    capture_output=True,
                    text=True,
                    check=False,
                    timeout=120,
                )
                if extract_proc.returncode != 0 or not staged_wav.is_file() or staged_wav.stat().st_size <= 44:
                    raise RuntimeError(_summarize_subprocess_error("ffmpeg remotion audio extract", extract_proc))
                temp_staged.append(staged_wav)
                voice_url = f"{base_url}/v1/renders/{staged_wav.name}"

            total_ms = max(1000, _probe_media_duration_ms(src_mp4))
            aspect = str(render_opts_raw.get("aspect") or payload.get("aspect") or "9:16").strip()
            template_id = "Explainer169" if aspect == "16:9" else "Newsroom916"

            input_props: dict[str, Any] = {
                "templateId": template_id,
                "lipsyncUrl": lipsync_url,
                "voiceUrl": voice_url,
            }

            raw_align_url = str(payload.get("align_url") or "").strip()
            raw_words = payload.get("words")
            if raw_align_url:
                input_props["alignUrl"] = _resolve_url(raw_align_url)
            elif isinstance(raw_words, list) and raw_words:
                norm_words: list[dict[str, Any]] = []
                for w in raw_words:
                    if not isinstance(w, Mapping):
                        continue
                    w_txt = str(w.get("word") or "").strip()
                    if not w_txt:
                        continue
                    s_ms = int(w.get("startMs") if w.get("startMs") is not None else (w.get("start_ms") or 0))
                    e_ms = int(w.get("endMs") if w.get("endMs") is not None else (w.get("end_ms") or s_ms))
                    norm_words.append({"word": w_txt, "startMs": max(0, s_ms), "endMs": max(max(0, s_ms), e_ms)})
                if norm_words:
                    input_props["words"] = norm_words
                else:
                    input_props["words"] = []
                    input_props["durationMs"] = total_ms
            elif source_job_id and (self.renders_dir / f"align_{source_job_id}.json").is_file():
                input_props["alignUrl"] = f"{base_url}/v1/renders/align_{source_job_id}.json"
            else:
                input_props["words"] = []
                input_props["durationMs"] = total_ms

            beats: list[dict[str, Any]] = []
            broll_cuts = 0
            for idx, item in enumerate(clauses):
                if not isinstance(item, Mapping):
                    continue
                mode = str(item.get("segment_mode") or "TALKING_HEAD").upper()
                is_cutaway = mode == "BROLL_CUTAWAY" or bool(item.get("pip_shrink") or item.get("pipShrink"))
                if is_cutaway:
                    broll_cuts += 1
                beat: dict[str, Any] = {
                    "id": str(item.get("id") or f"clause_{idx}"),
                    "ordinal": idx,
                    "pipShrink": is_cutaway,
                }
                spoken = str(item.get("spoken_text") or item.get("spokenText") or "").strip()
                if spoken:
                    beat["spokenText"] = spoken
                if item.get("start_ms") is not None or item.get("startMs") is not None:
                    s_ms = max(0, int(item.get("startMs") if item.get("startMs") is not None else item["start_ms"]))
                    beat["startMs"] = s_ms
                    if item.get("end_ms") is not None or item.get("endMs") is not None:
                        e_ms = max(s_ms, int(item.get("endMs") if item.get("endMs") is not None else item["end_ms"]))
                        beat["endMs"] = e_ms
                raw_broll = str(item.get("broll_url") or item.get("bRollUrl") or "").strip()
                if raw_broll:
                    beat["bRollUrl"] = _resolve_url(raw_broll)
                raw_ev = str(item.get("evidence_source") or item.get("evidenceSource") or item.get("citation") or "").strip()
                if raw_ev:
                    beat["evidenceSource"] = raw_ev
                raw_tag = str(item.get("depth_text_tag") or item.get("depthTextTag") or item.get("keyword") or "").strip()
                if raw_tag:
                    beat["depthTextTag"] = raw_tag
                beats.append(beat)
            input_props["beats"] = beats

            first_ovl = next((o for o in overlays if isinstance(o, Mapping)), {})
            breaking_style = str(
                render_opts_raw.get("breaking_style")
                or first_ovl.get("breaking_style")
                or "breaking"
            ).strip().lower()
            if breaking_style not in ("breaking", "developing", "exclusive", "alert_stat", "clean"):
                breaking_style = "breaking"
            headline = str(
                render_opts_raw.get("chyron_headline") or first_ovl.get("headline") or title or "Aircheck"
            ).strip() or "Aircheck"
            subline = str(render_opts_raw.get("chyron_subline") or first_ovl.get("subline") or "").strip()
            corner_bug = str(render_opts_raw.get("corner_bug_text") or "LIVE").strip() or "LIVE"
            show_bug = breaking_style != "clean" and bool(render_opts_raw.get("show_brand_bug", True))
            remotion_overlays: list[dict[str, Any]] = []
            for ovl_item in overlays:
                if not isinstance(ovl_item, Mapping):
                    continue
                ovl_h = str(ovl_item.get("headline") or "").strip()
                ovl_s = str(ovl_item.get("subline") or "").strip()
                ovl_stat = str(ovl_item.get("callout_stat") or "").strip()
                if not ovl_h and not ovl_s and not ovl_stat:
                    continue
                ovl_kind = str(ovl_item.get("kind") or "LOWER_THIRD").strip().upper()
                if ovl_kind not in ("LOWER_THIRD", "STAT_CALLOUT", "QUOTE_BRIEF"):
                    ovl_kind = "LOWER_THIRD"
                ovl_bstyle = str(ovl_item.get("breaking_style") or breaking_style).strip().lower()
                if ovl_bstyle not in ("breaking", "developing", "exclusive", "alert_stat", "clean"):
                    ovl_bstyle = breaking_style
                remotion_overlays.append(
                    {
                        "kind": ovl_kind,
                        "breakingStyle": ovl_bstyle,
                        "headline": ovl_h[:48],
                        "subline": ovl_s[:64],
                        "calloutStat": ovl_stat[:28],
                        "durationSec": max(2, min(20, int(ovl_item.get("duration_sec") or 5))),
                    }
                )
            input_props["packaging"] = {
                "chyronHeadline": headline,
                "chyronSubline": subline,
                "cornerBugText": corner_bug,
                "showBrandBug": show_bug,
                "breakingStyle": breaking_style,
                "overlays": remotion_overlays,
                "templateId": template_id,
            }

            req_body = json.dumps(
                {
                    "jobId": final_id,
                    "outputLocalPath": str(out_mp4.resolve()),
                    "inputProps": input_props,
                }
            ).encode("utf-8")
            req = urllib.request.Request(
                f"{self.remotion_worker_url}/render",
                data=req_body,
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            try:
                with urllib.request.urlopen(req, timeout=600) as resp:
                    resp_raw = resp.read().decode("utf-8", errors="replace")
            except urllib.error.HTTPError as exc:
                err_body = exc.read().decode("utf-8", errors="replace")
                raise RuntimeError(
                    f"Remotion 4 worker returned HTTP {exc.code}: {err_body[:400]}"
                ) from exc
            except urllib.error.URLError as exc:
                raise RuntimeError(
                    f"Remotion 4 worker unreachable at {self.remotion_worker_url}: {exc.reason}"
                ) from exc

            if not out_mp4.is_file() or out_mp4.stat().st_size == 0:
                raise RuntimeError(
                    f"Remotion 4 worker succeeded ({resp_raw[:200]}) but output MP4 is missing or empty: {out_mp4}"
                )
            if self._on_remotion_render_completed is not None:
                self._on_remotion_render_completed()
            applied_overlays = sum(
                1
                for o in overlays
                if isinstance(o, Mapping)
                and (
                    str(o.get("headline") or "").strip()
                    or str(o.get("subline") or "").strip()
                    or str(o.get("callout_stat") or "").strip()
                )
            )
            return {
                "broll_cut_count": broll_cuts,
                "applied_overlay_count": applied_overlays,
                "caption_count": len(input_props.get("words") or []),
                "punch_in_count": 0,
                "removed_silence_gap_count": 0,
                "removed_silence_ms": 0,
                "aspect": aspect,
                "remotion_used": True,
                "renderer": "remotion-4",
            }
        finally:
            for tmp_p in temp_staged:
                tmp_p.unlink(missing_ok=True)

    @property
    def plates_dir(self) -> Path:
        return self.renders_dir / "plates"

    def _resolve_source_head_mp4(self, payload: dict[str, Any]) -> Path:
        source_job_id = str(payload.get("source_job_id") or "").strip()
        if not source_job_id:
            raise ValueError("source_job_id is required.")
        resolved = self.resolve_render_file(f"{source_job_id}.mp4")
        if resolved is None:
            raise ValueError(f"Talking-head render '{source_job_id}.mp4' not found on rig.")
        return resolved

    def detect_silence_gaps_for_render(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Lists internal pauses in a talking-head render that ``strip_silence`` would remove."""
        if not isinstance(payload, dict):
            raise ValueError("Silence-gaps request body must be a JSON object")
        src = self._resolve_source_head_mp4(payload)
        total_ms = _probe_media_duration_ms(src)
        if total_ms <= 0:
            raise RuntimeError(f"Could not probe duration of {src.name}.")
        gaps = detect_silence_gaps(src, total_ms)
        return {
            "source_job_id": str(payload.get("source_job_id")),
            "duration_ms": total_ms,
            "gaps": [{"start_ms": s, "end_ms": e} for s, e in gaps],
            "removable_ms": sum(e - s for s, e in gaps),
        }

    def generate_backdrop(self, payload: dict[str, Any], *, bl_bin: Path = DEFAULT_BL_BIN) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("Backdrop request body must be a JSON object")
        return generate_backdrop_plate(
            prompt=str(payload.get("prompt") or ""),
            aspect=str(payload.get("aspect") or "9:16"),
            plates_dir=self.plates_dir,
            bl_bin=bl_bin,
            runner=self._plate_runner,
        )


# S2-Pro emits ~21.5 codec frames per second of speech (320 tokens -> 14.86 s measured on this rig).
TTS_TOKENS_PER_SEC = 21.5
TTS_WORDS_PER_SEC = 2.5
TTS_MIN_TOKENS = 320
# Leaves room for the reference prompt inside the 4096-token context.
TTS_MAX_TOKENS = 2400


def tts_token_budget(text: str) -> int:
    """Generation cap sized to the script so long scripts are not cut off mid-sentence.

    Budget = expected speech seconds (words / 2.5) x tokens-per-second x 1.4 headroom; the model
    stops on its own end token, so headroom costs nothing when the speech is shorter.
    """
    words = len(text.split())
    budget = int(round((words / TTS_WORDS_PER_SEC) * TTS_TOKENS_PER_SEC * 1.4))
    return max(TTS_MIN_TOKENS, min(TTS_MAX_TOKENS, budget))


def _measure_wav_duration_and_peaks(wav_path: Path, *, num_bins: int = 64) -> tuple[int, list[float]]:
    import struct
    import wave

    with wave.open(str(wav_path), "rb") as wf:
        sr = max(1, wf.getframerate())
        channels = max(1, wf.getnchannels())
        sampwidth = wf.getsampwidth()
        nframes = wf.getnframes()
        duration_ms = max(1, int(round((nframes * 1000.0) / sr)))
        raw = wf.readframes(nframes)

    if sampwidth == 2 and len(raw) >= 2:
        total_shorts = len(raw) // 2
        shorts = struct.unpack(f"<{total_shorts}h", raw[: total_shorts * 2])
        mono = [abs(shorts[i]) / 32768.0 for i in range(0, len(shorts), channels)]
    else:
        mono = []

    if not mono:
        return duration_ms, []

    bins = max(1, min(num_bins, len(mono)))
    step = max(1, len(mono) // bins)
    peaks: list[float] = []
    for b in range(bins):
        start = b * step
        end = len(mono) if b == bins - 1 else min(len(mono), (b + 1) * step)
        window = mono[start:end]
        peaks.append(round(min(1.0, max(window) if window else 0.0), 4))
    return duration_ms, peaks


def _distribute_clause_timings(
    clauses: list[dict[str, str]],
    total_duration_ms: int,
) -> list[dict[str, Any]]:
    weights = [max(1, len(c["spoken_text"])) for c in clauses]
    total_w = max(1, sum(weights))
    cursor = 0
    out: list[dict[str, Any]] = []
    for idx, c in enumerate(clauses):
        if idx == len(clauses) - 1:
            end_ms = max(cursor + 1, total_duration_ms)
        else:
            span = max(200, int(round((total_duration_ms * weights[idx]) / total_w)))
            end_ms = min(total_duration_ms, cursor + span)
        out.append(
            {
                "id": c["id"],
                "index": idx,
                "spoken_text": c["spoken_text"],
                "start_ms": cursor,
                "end_ms": end_ms,
            }
        )
        cursor = end_ms
    return out


def _probe_mp4_canvas_916(mp4_path: Path) -> tuple[int, int, float]:
    from studio_cut import _probe_video_dims

    sw, sh = _probe_video_dims(mp4_path)
    if max(sw, sh) >= 1920 or min(sw, sh) >= 1080:
        return (1080, 1920, 1.5)
    return (720, 1280, 1.0)


def _compose_broll_and_saved_overlays_mp4(
    *,
    src_mp4: Path,
    out_mp4: Path,
    work_dir: Path,
    clauses: list[Any],
    overlays: list[Any],
) -> tuple[Path, int, int]:
    """Chops talking head into B-roll cutaway windows + optional saved news-brief overlays via PIL + ffmpeg."""
    from PIL import Image, ImageDraw, ImageFont

    cw, ch, sc = _probe_mp4_canvas_916(src_mp4)
    pip_w = int(round(236 * sc)) - (int(round(236 * sc)) % 2)
    pip_h = int(round(316 * sc)) - (int(round(316 * sc)) % 2)
    pip_x = cw - pip_w - int(round(36 * sc))
    pip_y = int(round(56 * sc))

    total_ms = max(1000, _probe_media_duration_ms(src_mp4))
    repo_root = Path(__file__).resolve().parents[2]
    font_bold_path = repo_root / "app" / "src" / "main" / "res" / "font" / "space_grotesk.ttf"
    try:
        f_title = ImageFont.truetype(str(font_bold_path), int(round(40 * sc)))
        f_body = ImageFont.truetype(str(font_bold_path), int(round(28 * sc)))
        f_tag = ImageFont.truetype(str(font_bold_path), int(round(22 * sc)))
    except Exception:
        f_title = ImageFont.load_default()
        f_body = ImageFont.load_default()
        f_tag = ImageFont.load_default()

    timed_pngs: list[tuple[Path, float, float, bool]] = []
    broll_count = 0
    overlay_count = 0

    for idx, item in enumerate(clauses):
        if not isinstance(item, dict):
            continue
        mode = str(item.get("segment_mode") or "TALKING_HEAD").upper()
        if mode != "BROLL_CUTAWAY":
            continue
        start_ms = max(0, int(item.get("start_ms") or 0))
        end_ms = min(total_ms, int(item.get("end_ms") or total_ms))
        if end_ms <= start_ms:
            continue
        caption = str(item.get("broll_caption") or item.get("spoken_text") or "").strip()[:88]
        png_path = work_dir / f"broll_plate_{idx}.png"
        img = Image.new("RGBA", (cw, ch), (10, 12, 18, 245))
        draw = ImageDraw.Draw(img)
        # B-roll evidence card framed below the top-right corner talking-head PiP window
        draw.rounded_rectangle(
            [(int(36 * sc), int(420 * sc)), (int(684 * sc), int(960 * sc))],
            radius=int(18 * sc),
            fill=(18, 22, 32, 255),
            outline=(184, 255, 0, 220),
            width=max(2, int(round(3 * sc))),
        )
        draw.text((int(64 * sc), int(456 * sc)), "B-ROLL CUTAWAY", font=f_tag, fill=(184, 255, 0, 255))
        words = caption.split()
        lines: list[str] = []
        cur = ""
        for w in words:
            cand = f"{cur} {w}".strip()
            if len(cand) <= 26:
                cur = cand
            else:
                if cur:
                    lines.append(cur)
                cur = w
        if cur:
            lines.append(cur)
        for line_i, line_txt in enumerate(lines[:5]):
            draw.text(
                (int(64 * sc), int((524 + line_i * 58) * sc)),
                line_txt,
                font=f_title,
                fill=(244, 246, 250, 255),
            )
        # Crisp broadcast frame for the live top-right corner talking-head PiP
        draw.rounded_rectangle(
            [(pip_x - 4, pip_y - 4), (pip_x + pip_w + 4, pip_y + pip_h + 4)],
            radius=int(16 * sc),
            fill=(0, 0, 0, 0),
            outline=(184, 255, 0, 235),
            width=max(2, int(round(3 * sc))),
        )
        img.save(png_path)
        timed_pngs.append((png_path, start_ms / 1000.0, end_ms / 1000.0, True))
        broll_count += 1

    for idx, ovl in enumerate(overlays):
        if not isinstance(ovl, dict):
            continue
        headline = str(ovl.get("headline") or "").strip()[:48]
        subline = str(ovl.get("subline") or "").strip()[:64]
        stat = str(ovl.get("callout_stat") or "").strip()[:32]
        dur_sec = max(1, min(30, int(ovl.get("duration_sec") or 4)))
        if not headline and not subline and not stat:
            continue
        start_s = min(max(0.0, idx * 1.5), max(0.0, (total_ms / 1000.0) - 0.5))
        end_s = min(total_ms / 1000.0, start_s + float(dur_sec))
        png_path = work_dir / f"saved_ovl_{idx}.png"
        img = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)
        draw.rounded_rectangle(
            [(int(32 * sc), int(1020 * sc)), (int(688 * sc), int(1208 * sc))],
            radius=int(14 * sc),
            fill=(12, 15, 22, 232),
            outline=(0, 136, 255, 220),
            width=max(2, int(round(2 * sc))),
        )
        if stat:
            draw.text((int(54 * sc), int(1038 * sc)), stat.upper(), font=f_tag, fill=(184, 255, 0, 255))
        if headline:
            draw.text((int(54 * sc), int(1074 * sc)), headline.upper(), font=f_title, fill=(255, 255, 255, 255))
        if subline:
            draw.text((int(54 * sc), int(1134 * sc)), subline, font=f_body, fill=(180, 192, 210, 255))
        img.save(png_path)
        timed_pngs.append((png_path, start_s, max(start_s + 0.25, end_s), False))
        overlay_count += 1

    if not timed_pngs:
        shutil.copy2(src_mp4, out_mp4)
        return out_mp4, 0, 0

    cmd = ["ffmpeg", "-y", "-i", str(src_mp4)]
    for png_path, _, _, _ in timed_pngs:
        cmd.extend(["-loop", "1", "-i", str(png_path)])

    has_pip_cutaway = any(is_cutaway for _, _, _, is_cutaway in timed_pngs)
    if has_pip_cutaway:
        filter_parts = [
            f"[0:v]scale={cw}:{ch}:force_original_aspect_ratio=increase,crop={cw}:{ch},fps=30,split=2[v0][vsrc_pip]",
            f"[vsrc_pip]scale={pip_w}:{pip_h}:force_original_aspect_ratio=increase,crop={pip_w}:{pip_h}[vpip]",
        ]
    else:
        filter_parts = [f"[0:v]scale={cw}:{ch}:force_original_aspect_ratio=increase,crop={cw}:{ch},fps=30[v0]"]

    step_label = "v0"
    broll_enable_terms: list[str] = []
    for idx, (_, s_sec, e_sec, is_cutaway) in enumerate(timed_pngs):
        in_idx = idx + 1
        next_label = f"v{idx + 1}"
        if is_cutaway:
            broll_enable_terms.append(f"between(t,{s_sec:.3f},{e_sec:.3f})")
        filter_parts.append(
            f"[{step_label}][{in_idx}:v]overlay=0:0:shortest=1:enable='between(t,{s_sec:.3f},{e_sec:.3f})'[{next_label}]"
        )
        step_label = next_label
    if has_pip_cutaway and broll_enable_terms:
        pip_enable = "+".join(broll_enable_terms)
        final_v = "vfinal_pip"
        filter_parts.append(
            f"[{step_label}][vpip]overlay={pip_x}:{pip_y}:shortest=1:enable='{pip_enable}'[{final_v}]"
        )
    else:
        final_v = step_label
    filter_complex = ";".join(filter_parts)
    cmd.extend(
        [
            "-filter_complex",
            filter_complex,
            "-map",
            f"[{final_v}]",
            "-map",
            "0:a?",
            "-c:v",
            "libx264",
            "-preset",
            "fast",
            "-crf",
            "20",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-ar",
            "48000",
            "-b:a",
            "256k",
            "-movflags",
            "+faststart",
            str(out_mp4),
        ]
    )
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False, timeout=300)
    if proc.returncode != 0 or not out_mp4.is_file() or out_mp4.stat().st_size == 0:
        raise RuntimeError(_summarize_subprocess_error("ffmpeg chop-broll", proc))
    return out_mp4, broll_count, overlay_count


def _burn_breaking_news_chyron(
    *,
    src_mp4: Path,
    out_mp4: Path,
    headline: str,
    subline: str,
    show_live_bug: bool = True,
) -> Path:
    """Burns a broadcast LIVE bug + BREAKING NEWS lower-third chyron onto a 9:16 MP4."""
    from PIL import Image, ImageDraw, ImageFont

    cw, ch, sc = _probe_mp4_canvas_916(src_mp4)
    overlay_png = out_mp4.with_suffix(".chyron.png")
    img = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    repo_root = Path(__file__).resolve().parents[2]
    font_bold_path = repo_root / "app" / "src" / "main" / "res" / "font" / "space_grotesk.ttf"
    font_mono_path = repo_root / "app" / "src" / "main" / "res" / "font" / "jetbrains_mono.ttf"

    def _load_font(p: Path, size: int) -> Any:
        if p.is_file():
            return ImageFont.truetype(str(p), size)
        return ImageFont.load_default()

    f_live = _load_font(font_mono_path, int(round(22 * sc)))
    f_loc = _load_font(font_mono_path, int(round(20 * sc)))
    f_tag = _load_font(font_mono_path, int(round(20 * sc)))
    f_head = _load_font(font_bold_path, int(round(30 * sc)))
    f_sub = _load_font(font_mono_path, int(round(20 * sc)))

    if show_live_bug:
        draw.rectangle([int(28 * sc), int(46 * sc), int(152 * sc), int(88 * sc)], fill=(217, 4, 41, 245))
        draw.ellipse([int(42 * sc), int(60 * sc), int(56 * sc), int(74 * sc)], fill=(255, 255, 255, 255))
        draw.text((int(64 * sc), int(54 * sc)), "LIVE", font=f_live, fill=(255, 255, 255, 255))
        draw.rectangle([int(152 * sc), int(46 * sc), int(396 * sc), int(88 * sc)], fill=(10, 12, 16, 224))
        draw.text((int(168 * sc), int(55 * sc)), "AIRCHECK NEWS", font=f_loc, fill=(232, 238, 245, 255))

    clean_head = headline.strip().upper()[:38]
    clean_sub = subline.strip().upper()[:48]
    if clean_head or clean_sub:
        draw.rectangle([int(24 * sc), int(1040 * sc), int(260 * sc), int(1080 * sc)], fill=(217, 4, 41, 245))
        draw.text((int(40 * sc), int(1048 * sc)), "BREAKING NEWS", font=f_tag, fill=(255, 255, 255, 255))
        draw.rectangle([int(24 * sc), int(1080 * sc), int(696 * sc), int(1204 * sc)], fill=(9, 12, 18, 232))
        draw.rectangle([int(24 * sc), int(1080 * sc), int(32 * sc), int(1204 * sc)], fill=(184, 255, 0, 255))
        if clean_head:
            draw.text((int(48 * sc), int(1096 * sc)), clean_head, font=f_head, fill=(255, 255, 255, 255))
        if clean_sub:
            draw.text((int(48 * sc), int(1148 * sc)), clean_sub, font=f_sub, fill=(184, 255, 0, 255))

    img.save(overlay_png)
    filter_complex = (
        f"[0:v]scale={cw}:{ch}:force_original_aspect_ratio=increase,crop={cw}:{ch},fps=30[bg];"
        "[bg][1:v]overlay=0:0:shortest=1[v];"
        "[0:a]loudnorm=I=-16:TP=-1.5:LRA=9[a]"
    )
    proc = subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-i",
            str(src_mp4),
            "-loop",
            "1",
            "-i",
            str(overlay_png),
            "-filter_complex",
            filter_complex,
            "-map",
            "[v]",
            "-map",
            "[a]",
            "-shortest",
            "-c:v",
            "libx264",
            "-preset",
            "fast",
            "-crf",
            "18",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "256k",
            "-ar",
            "48000",
            "-ac",
            "1",
            "-movflags",
            "+faststart",
            str(out_mp4),
        ],
        capture_output=True,
        text=True,
        check=False,
        timeout=300,
    )
    if proc.returncode != 0 or not out_mp4.is_file() or out_mp4.stat().st_size == 0:
        raise RuntimeError(_summarize_subprocess_error("ffmpeg chyron burn", proc))
    return out_mp4


def _extract_json_objects(raw_text: str) -> list[dict[str, Any]]:
    """Extracts all top-level JSON objects from stdout/content (ignoring CLI warnings or fences)."""
    cleaned = raw_text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned)
        cleaned = re.sub(r"\s*```$", "", cleaned).strip()
    decoder = json.JSONDecoder()
    objs: list[dict[str, Any]] = []
    pos = 0
    while pos < len(cleaned):
        idx = cleaned.find("{", pos)
        if idx < 0:
            break
        try:
            obj, end = decoder.raw_decode(cleaned, idx)
            if isinstance(obj, dict):
                objs.append(obj)
            pos = end
        except json.JSONDecodeError:
            pos = idx + 1
    return objs


def _extract_first_json_object(raw_text: str, *, required_key: str | None = None) -> dict[str, Any]:
    objs = _extract_json_objects(raw_text)
    if required_key is not None:
        for obj in objs:
            if required_key in obj:
                return obj
    if objs:
        return objs[0]
    raise RuntimeError(f"AI model did not return valid JSON: {raw_text.strip()[:200]}")


def _run_ai_assist(
    payload: dict[str, Any],
    *,
    bl_bin: Path = DEFAULT_BL_BIN,
    llm_router: LlmRouter | None = None,
) -> dict[str, Any]:
    """Mixed local/remote AI writer for Aircheck text inputs via ``LlmRouter``."""
    if not isinstance(payload, dict):
        raise ValueError("AI assist body must be a JSON object")

    mode = str(payload.get("mode") or "").strip().lower()
    if mode not in VALID_AI_ASSIST_MODES:
        raise ValueError(f"mode must be one of {sorted(VALID_AI_ASSIST_MODES)}, got {mode!r}")

    text = str(payload.get("text") or "").strip()
    context = str(payload.get("context") or "").strip()
    if not text and not context:
        raise ValueError("Type a quick idea or draft text first before tapping AI assist.")

    raw_provider = payload.get("llm_provider")
    if raw_provider is not None:
        if not isinstance(raw_provider, str) or not raw_provider.strip():
            raise ValueError("llm_provider must be a non-empty string")
        llm_provider: str | None = raw_provider.strip()
    else:
        llm_provider = None

    raw_model = payload.get("llm_model")
    if raw_model is not None:
        if not isinstance(raw_model, str) or not raw_model.strip():
            raise ValueError("llm_model must be a non-empty string")
        llm_model: str | None = raw_model.strip()
    else:
        llm_model = None

    system_prompt = (
        "You are a deadpan, overly serious breaking-news cable TV producer writing punchy, "
        "absurdly serious fake-news scripts for a solo anchor. Never explain the joke, never "
        "add stage directions or brackets, and write natural spoken English ready to read on air."
    )

    if mode == "flesh_out_story":
        target_segments = payload.get("target_segments", 2)
        try:
            seg_count = max(1, min(4, int(target_segments)))
        except (TypeError, ValueError):
            seg_count = 2
        seed = text or context
        user_prompt = (
            f"Turn this rough story idea into a short, deadpan breaking-news broadcast:\n"
            f"IDEA: {seed}\n\n"
            f"Return STRICT JSON ONLY with this exact schema:\n"
            f'{{"headline":"ALL CAPS MAX 34 CHARS","subline":"ALL CAPS TICKER MAX 42 CHARS",'
            f'"segments":["segment 1 spoken anchor text (12-22 words)","segment 2 ..."]}}\n'
            f"Produce exactly {seg_count} crisp spoken segments."
        )
    elif mode == "polish_headline":
        user_prompt = (
            f"Write ONE punchy ALL-CAPS breaking-news lower-third headline (max 34 characters, no quotes). "
            f"If a draft is provided, write a fresh, punchier alternative worded differently from the draft.\n"
            f"Draft: {text}\nStory context: {context}\n"
            f'Return STRICT JSON ONLY: {{"text":"HEADLINE HERE"}}'
        )
    elif mode == "polish_subline":
        user_prompt = (
            f"Write ONE punchy ALL-CAPS cable-news lower-third subline/ticker (max 42 characters, no quotes). "
            f"If a draft is provided, write a fresh, punchier alternative worded differently from the draft.\n"
            f"Draft: {text}\nStory context: {context}\n"
            f'Return STRICT JSON ONLY: {{"text":"SUBLINE HERE"}}'
        )
    elif mode == "generate_overlay_brief":
        seed = text or context
        user_prompt = (
            f"Design a concise broadcast news-brief graphic overlay card for this topic:\n"
            f"BRIEF: {seed}\n\n"
            f"Return STRICT JSON ONLY with this exact schema:\n"
            f'{{"kind":"LOWER_THIRD","headline":"ALL CAPS MAX 32 CHARS",'
            f'"subline":"CONCISE SUBLINE MAX 48 CHARS","callout_stat":"SHORT TAG OR METRIC MAX 20 CHARS","duration_sec":4}}'
        )
    elif mode == "generate_overlay_set":
        seed = text or context
        bstyle = str(payload.get("breaking_style") or "breaking").strip().lower()
        if bstyle not in ("breaking", "developing", "exclusive", "alert_stat", "clean"):
            bstyle = "breaking"
        user_prompt = (
            f"Design a cohesive 3-piece television newsroom graphic overlay package in the '{bstyle.upper()}' "
            f"broadcast style for this story:\n"
            f"STORY: {seed}\n\n"
            f"Produce EXACTLY 3 distinct overlays grounded strictly in the story:\n"
            f"1) kind='LOWER_THIRD': main anchor headline + ticker subline + concise alert tag\n"
            f"2) kind='STAT_CALLOUT': sharp factual takeaway or key contrast + crisp callout_stat badge\n"
            f"3) kind='QUOTE_BRIEF': editorial field brief or source card summarizing the core claim\n"
            f"Never invent fake numbers if none are in the story; use concise uppercase editorial tags when no number is stated.\n\n"
            f"Return STRICT JSON ONLY with this exact schema:\n"
            f'{{"overlays":['
            f'{{"kind":"LOWER_THIRD","headline":"ALL CAPS MAX 32 CHARS","subline":"MAX 46 CHARS","callout_stat":"MAX 16 CHARS","duration_sec":6}},'
            f'{{"kind":"STAT_CALLOUT","headline":"ALL CAPS MAX 32 CHARS","subline":"MAX 46 CHARS","callout_stat":"MAX 16 CHARS","duration_sec":5}},'
            f'{{"kind":"QUOTE_BRIEF","headline":"MAX 34 CHARS","subline":"MAX 50 CHARS","callout_stat":"DESK BRIEF","duration_sec":5}}'
            f']}}'
        )
    else:  # polish_segment
        user_prompt = (
            f"Rewrite or flesh out this single spoken news anchor segment (14-22 words, deadpan serious cable news tone, "
            f"plain spoken text with no stage directions or quotes, worded differently from the draft).\n"
            f"Segment draft: {text}\nStory context: {context}\n"
            f'Return STRICT JSON ONLY: {{"text":"Spoken segment here."}}'
        )

    router = llm_router or LlmRouter(bl_bin=bl_bin)
    res = router.complete(
        "ai_assist",
        system_prompt,
        user_prompt,
        llm_provider=llm_provider,
        llm_model=llm_model,
        max_tokens=1024,
    )

    try:
        inner = _extract_first_json_object(res.text)
    except RuntimeError as exc:
        raise LlmProviderError(
            str(exc),
            provider=res.provider,
            stage="ai_assist",
            model=res.model,
            status_code=502,
            error_code="llm_invalid_response",
        ) from exc

    if mode == "flesh_out_story":
        headline = str(inner.get("headline") or "").strip().upper()[:40]
        subline = str(inner.get("subline") or "").strip().upper()[:52]
        if not headline:
            raise LlmProviderError(
                "AI response missing headline.",
                provider=res.provider,
                stage="ai_assist",
                model=res.model,
                status_code=502,
                error_code="llm_invalid_response",
            )
        if not subline:
            raise LlmProviderError(
                "AI response missing subline.",
                provider=res.provider,
                stage="ai_assist",
                model=res.model,
                status_code=502,
                error_code="llm_invalid_response",
            )
        raw_segs = inner.get("segments")
        if not isinstance(raw_segs, list):
            raise LlmProviderError(
                "AI response missing segments list.",
                provider=res.provider,
                stage="ai_assist",
                model=res.model,
                status_code=502,
                error_code="llm_invalid_response",
            )
        segments = [s.strip() for s in raw_segs if isinstance(s, str) and s.strip()]
        if not segments:
            raise LlmProviderError(
                "AI response returned zero segments.",
                provider=res.provider,
                stage="ai_assist",
                model=res.model,
                status_code=502,
                error_code="llm_invalid_response",
            )
        return {
            "mode": mode,
            "headline": headline,
            "subline": subline,
            "segments": segments,
            "llm_meta": res.llm_meta,
        }

    if mode == "generate_overlay_brief":
        headline = str(inner.get("headline") or "").strip().upper()[:40]
        subline = str(inner.get("subline") or "").strip()[:56]
        callout_stat = str(inner.get("callout_stat") or "").strip()[:24]
        kind = str(inner.get("kind") or "LOWER_THIRD").strip().upper()
        if kind not in ("LOWER_THIRD", "STAT_CALLOUT", "QUOTE_BRIEF"):
            kind = "LOWER_THIRD"
        try:
            dur_sec = max(2, min(15, int(inner.get("duration_sec") or 4)))
        except (TypeError, ValueError):
            dur_sec = 4
        if not headline:
            raise LlmProviderError(
                "AI overlay brief missing headline.",
                provider=res.provider,
                stage="ai_assist",
                model=res.model,
                status_code=502,
                error_code="llm_invalid_response",
            )
        return {
            "mode": mode,
            "kind": kind,
            "headline": headline,
            "subline": subline,
            "callout_stat": callout_stat,
            "duration_sec": dur_sec,
            "llm_meta": res.llm_meta,
        }

    if mode == "generate_overlay_set":
        bstyle = str(payload.get("breaking_style") or "breaking").strip().lower()
        if bstyle not in ("breaking", "developing", "exclusive", "alert_stat", "clean"):
            bstyle = "breaking"
        raw_list = inner.get("overlays")
        if not isinstance(raw_list, list) or not raw_list:
            raise LlmProviderError(
                "AI overlay set missing overlays array.",
                provider=res.provider,
                stage="ai_assist",
                model=res.model,
                status_code=502,
                error_code="llm_invalid_response",
            )
        expected_kinds = ("LOWER_THIRD", "STAT_CALLOUT", "QUOTE_BRIEF")
        validated_overlays: list[dict[str, Any]] = []
        for idx, item in enumerate(raw_list[:4]):
            if not isinstance(item, Mapping):
                continue
            raw_h = str(item.get("headline") or "").strip()
            if not raw_h:
                continue
            kind = str(item.get("kind") or expected_kinds[min(idx, 2)]).strip().upper()
            if kind not in expected_kinds:
                kind = expected_kinds[min(idx, 2)]
            h_out = raw_h.upper()[:40] if kind != "QUOTE_BRIEF" else raw_h[:40]
            s_out = str(item.get("subline") or "").strip()[:56]
            stat_out = str(item.get("callout_stat") or "").strip()[:20]
            try:
                dur_sec = max(2, min(15, int(item.get("duration_sec") or 5)))
            except (TypeError, ValueError):
                dur_sec = 5
            validated_overlays.append(
                {
                    "kind": kind,
                    "breaking_style": bstyle,
                    "headline": h_out,
                    "subline": s_out,
                    "callout_stat": stat_out,
                    "duration_sec": dur_sec,
                }
            )
        if not validated_overlays:
            raise LlmProviderError(
                "AI overlay set produced zero valid overlay objects.",
                provider=res.provider,
                stage="ai_assist",
                model=res.model,
                status_code=502,
                error_code="llm_invalid_response",
            )
        return {
            "mode": mode,
            "breaking_style": bstyle,
            "overlays": validated_overlays,
            "llm_meta": res.llm_meta,
        }

    polished = str(inner.get("text") or "").strip()
    if not polished:
        raise LlmProviderError(
            "AI writer returned empty text.",
            provider=res.provider,
            stage="ai_assist",
            model=res.model,
            status_code=502,
            error_code="llm_empty_response",
        )
    if mode in ("polish_headline", "polish_subline"):
        polished = polished.upper()
    return {"mode": mode, "text": polished, "llm_meta": res.llm_meta}


class OtaHttpServer(ThreadingHTTPServer):
    """Multi-threaded stdlib HTTP server holding OtaBundleStore, RenderJobManager & DraftPassEngine."""

    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 128

    def __init__(
        self,
        server_address: tuple[str, int],
        store: OtaBundleStore,
        *,
        render_manager: RenderJobManager | None = None,
        draft_engine: DraftPassEngine | None = None,
        llm_router: LlmRouter | None = None,
        llm_config_path: Path | str | None = None,
        quiet: bool = False,
    ) -> None:
        self.store = store
        self.render_manager = render_manager or RenderJobManager(
            renders_dir=store.bundles_root.parent / "renders",
            bundles_root=store.bundles_root,
        )
        env_llm_cfg = os.environ.get("AIRCHECK_LLM_CONFIG_PATH", "").strip()
        resolved_llm_cfg = (
            Path(llm_config_path)
            if llm_config_path is not None
            else (
                Path(env_llm_cfg)
                if env_llm_cfg
                else (self.render_manager.renders_dir / "llm_config.json")
            )
        )
        self.llm_config_path = resolved_llm_cfg
        self.llm_router = llm_router or LlmRouter(config_path=resolved_llm_cfg)
        self.draft_engine = draft_engine or DraftPassEngine(llm_router=self.llm_router)
        self.quiet = quiet
        self._remotion_lock = threading.Lock()
        self._remotion_state_path = self.render_manager.renders_dir / "remotion_state.json"
        super().__init__(server_address, OtaRequestHandler)
        self.render_manager.local_base_url = f"http://127.0.0.1:{self.server_port}"
        self.render_manager._on_remotion_render_completed = self._record_remotion_render_completed

    def _record_remotion_render_completed(self) -> None:
        with self._remotion_lock:
            self._remotion_state_path.parent.mkdir(parents=True, exist_ok=True)
            existing: dict[str, Any] = {}
            if self._remotion_state_path.is_file():
                try:
                    existing = json.loads(self._remotion_state_path.read_text(encoding="utf-8"))
                except Exception:
                    existing = {}
            existing["renders_completed"] = int(existing.get("renders_completed", 0)) + 1
            existing["last_render_utc"] = _utc_now_iso()
            tmp_path = self._remotion_state_path.with_suffix(".json.tmp")
            tmp_path.write_text(json.dumps(existing, indent=2) + "\n", encoding="utf-8")
            os.replace(tmp_path, self._remotion_state_path)

    def get_remotion_state(self) -> dict[str, Any]:
        repo_root = Path(__file__).resolve().parents[2]
        worker_dir = repo_root / "backend" / "remotion-worker"
        pkg_json = worker_dir / "package.json"
        remotion_pkg = worker_dir / "node_modules" / "remotion" / "package.json"
        version: str | None = None
        if remotion_pkg.is_file():
            try:
                version = str(json.loads(remotion_pkg.read_text(encoding="utf-8")).get("version") or "") or None
            except Exception:
                version = None

        persisted: dict[str, Any] = {}
        with self._remotion_lock:
            if self._remotion_state_path.is_file():
                try:
                    persisted = json.loads(self._remotion_state_path.read_text(encoding="utf-8"))
                except Exception:
                    persisted = {}

        return {
            "remotion_worker_present": pkg_json.is_file() and remotion_pkg.is_file(),
            "remotion_version": version,
            "remotion_enabled": bool(persisted.get("enabled", False)),
            "remotion_renders_completed": int(persisted.get("renders_completed", 0)),
            "last_user_request": str(persisted.get("last_user_request") or ""),
            "enable_broll_popups": bool(persisted.get("enable_broll_popups", False)),
        }

    def install_and_enable_remotion(
        self,
        *,
        user_request: str,
        enable_broll_popups: bool = True,
    ) -> dict[str, Any]:
        clean_req = user_request.strip()
        if not clean_req:
            raise ValueError("user_request must not be blank.")

        repo_root = Path(__file__).resolve().parents[2]
        worker_dir = repo_root / "backend" / "remotion-worker"
        pkg_json = worker_dir / "package.json"
        remotion_pkg = worker_dir / "node_modules" / "remotion" / "package.json"
        dist_entry = worker_dir / "dist" / "server.js"

        if not pkg_json.is_file() or not remotion_pkg.is_file():
            raise RuntimeError(
                f"Remotion worker packages not present at {worker_dir}; run npm ci in backend/remotion-worker."
            )

        if not dist_entry.is_file():
            proc = subprocess.run(
                ["npm", "run", "build"],
                cwd=str(worker_dir),
                capture_output=True,
                text=True,
                check=False,
            )
            if proc.returncode != 0 or not dist_entry.is_file():
                raise RuntimeError(_summarize_subprocess_error("remotion npm build", proc))

        remotion_meta = json.loads(remotion_pkg.read_text(encoding="utf-8"))
        remotion_version = str(remotion_meta.get("version") or "4.0.0")
        now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()

        with self._remotion_lock:
            self._remotion_state_path.parent.mkdir(parents=True, exist_ok=True)
            existing: dict[str, Any] = {}
            if self._remotion_state_path.is_file():
                try:
                    existing = json.loads(self._remotion_state_path.read_text(encoding="utf-8"))
                except Exception:
                    existing = {}
            requests_log = list(existing.get("requests") or [])
            requests_log.append(
                {
                    "user_request": clean_req,
                    "enable_broll_popups": bool(enable_broll_popups),
                    "requested_at_utc": now_iso,
                }
            )
            updated = {
                "enabled": True,
                "package_provider_id": "mac_rig_remotion",
                "remotion_version": remotion_version,
                "renders_completed": int(existing.get("renders_completed", 0)),
                "last_user_request": clean_req,
                "enable_broll_popups": bool(enable_broll_popups),
                "updated_at_utc": now_iso,
                "requests": requests_log,
            }
            tmp_path = self._remotion_state_path.with_suffix(".json.tmp")
            tmp_path.write_text(json.dumps(updated, indent=2), encoding="utf-8")
            os.replace(tmp_path, self._remotion_state_path)

        return {
            "status": "installed",
            "remotion_enabled": True,
            "remotion_version": remotion_version,
            "package_provider_id": "mac_rig_remotion",
            "user_request": clean_req,
            "enable_broll_popups": bool(enable_broll_popups),
        }

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

        if raw_path == "/v1/llm/providers":
            self._handle_get_llm_providers(head_only=head_only)
            return

        if raw_path == "/v1/default-avatar":
            self._handle_default_avatar(head_only=head_only)
            return

        if raw_path == "/v1/renders":
            self._handle_list_renders(head_only=head_only)
            return

        if raw_path.startswith("/v1/plates/") and raw_path.endswith(".png"):
            plate_id = raw_path[len("/v1/plates/") : -len(".png")]
            resolved_plate = studio_plate_path(self.server.render_manager.plates_dir, plate_id)
            if resolved_plate is None:
                self._send_json(
                    HTTPStatus.NOT_FOUND,
                    {"error": "not_found", "detail": f"Plate '{plate_id}' not found"},
                    head_only=head_only,
                )
                return
            self._serve_file_with_range(resolved_plate, "image/png", head_only=head_only)
            return

        if raw_path.startswith("/v1/render-thumbs/"):
            remainder = raw_path[len("/v1/render-thumbs/") :]
            parts = remainder.split("/")
            if len(parts) != 1 or not parts[0]:
                self._send_json(
                    HTTPStatus.NOT_FOUND,
                    {"error": "not_found", "detail": "Invalid render-thumb route"},
                    head_only=head_only,
                )
                return
            self._handle_render_thumb(parts[0], head_only=head_only)
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

        remotion_state = self.server.get_remotion_state()
        payload: dict[str, Any] = {
            "status": "ok" if manifest_error is None else "degraded",
            "lora_version": lora_version,
            "bundle_version": bundle_version,
            "pending_preferences": self.server.store.count_pending_preferences(),
            "render_bridge": True,
            "active_render_jobs": self.server.render_manager.count_active_jobs(),
            "remotion_worker_present": remotion_state["remotion_worker_present"],
            "remotion_version": remotion_state["remotion_version"],
            "remotion_enabled": remotion_state["remotion_enabled"],
            "remotion_renders_completed": remotion_state["remotion_renders_completed"],
        }
        if manifest_error is not None:
            payload["manifest_error"] = manifest_error

        self._send_json(HTTPStatus.OK, payload, head_only=head_only)

    def _handle_get_llm_providers(self, *, head_only: bool) -> None:
        payload = self.server.llm_router.list_providers()
        self._send_json(HTTPStatus.OK, payload, head_only=head_only)

    def _handle_default_avatar(self, *, head_only: bool) -> None:
        avatar = self.server.render_manager.default_avatar_path
        if not avatar.is_file() or avatar.stat().st_size == 0:
            self._send_json(
                HTTPStatus.NOT_FOUND,
                {"error": "not_found", "detail": "Default anchor portrait not configured"},
                head_only=head_only,
            )
            return
        self._serve_file_with_range(
            avatar,
            _content_type_for_filename(avatar.name),
            head_only=head_only,
        )

    def _handle_list_renders(self, *, head_only: bool) -> None:
        items = self.server.render_manager.list_renders()
        self._send_json(HTTPStatus.OK, {"renders": items}, head_only=head_only)

    def _handle_render_thumb(self, filename: str, *, head_only: bool) -> None:
        try:
            thumb_path = self.server.render_manager.get_or_create_render_thumb(filename)
        except PermissionError as exc:
            self._send_json(
                HTTPStatus.FORBIDDEN,
                {"error": "forbidden", "detail": str(exc)},
                head_only=head_only,
            )
            return
        if thumb_path is None:
            self._send_json(
                HTTPStatus.NOT_FOUND,
                {"error": "not_found", "detail": f"Thumbnail for '{filename}' not found"},
                head_only=head_only,
            )
            return
        self._serve_file_with_range(thumb_path, "image/jpeg", head_only=head_only)

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
        if parsed.path == "/v1/llm/config":
            self._handle_post_llm_config()
            return
        if parsed.path == "/v1/ai-assist":
            self._handle_post_ai_assist()
            return
        if parsed.path == "/v1/draft/transcribe":
            self._handle_post_draft_pass("transcribe", max_bytes=MAX_DRAFT_AUDIO_BODY_BYTES)
            return
        if parsed.path == "/v1/draft/angles":
            self._handle_post_draft_pass("angles", max_bytes=MAX_DRAFT_TEXT_BODY_BYTES)
            return
        if parsed.path == "/v1/draft/synthesize":
            self._handle_post_draft_pass("synthesize", max_bytes=MAX_DRAFT_TEXT_BODY_BYTES)
            return
        if parsed.path == "/v1/draft/brush-swap":
            self._handle_post_draft_pass("brush_swap", max_bytes=MAX_DRAFT_TEXT_BODY_BYTES)
            return
        if parsed.path == "/v1/draft/seam-heal":
            self._handle_post_draft_pass("seam_heal", max_bytes=MAX_DRAFT_TEXT_BODY_BYTES)
            return
        if parsed.path == "/v1/draft/density":
            self._handle_post_draft_pass("density", max_bytes=MAX_DRAFT_TEXT_BODY_BYTES)
            return
        if parsed.path == "/v1/draft/arc":
            self._handle_post_draft_pass("arc", max_bytes=MAX_DRAFT_TEXT_BODY_BYTES)
            return
        if parsed.path == "/v1/draft/synthesize-script":
            self._handle_post_draft_pass("synthesize_script", max_bytes=MAX_DRAFT_TEXT_BODY_BYTES)
            return
        if parsed.path == "/v1/draft/rewrite":
            self._handle_post_draft_pass("rewrite", max_bytes=MAX_DRAFT_TEXT_BODY_BYTES)
            return
        if parsed.path == "/v1/remotion/install":
            self._handle_post_remotion_install()
            return
        is_async = urllib.parse.parse_qs(parsed.query).get("async", ["0"])[0] in ("1", "true")
        if is_async and parsed.path in ASYNC_TASK_ROUTES:
            self._handle_post_async_task(parsed.path)
            return
        if parsed.path == "/v1/core/tts":
            self._handle_post_core_tts()
            return
        if parsed.path == "/v1/core/chop-broll":
            self._handle_post_core_chop_broll()
            return
        if parsed.path == "/v1/core/silence-gaps":
            self._handle_post_render_json(
                lambda body: self.server.render_manager.detect_silence_gaps_for_render(body),
                error_code="silence_gaps_failed",
            )
            return
        if parsed.path == "/v1/newsroom/backdrop":
            self._handle_post_render_json(
                lambda body: self.server.render_manager.generate_backdrop(body),
                error_code="backdrop_failed",
            )
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

    def _handle_post_llm_config(self) -> None:
        text_body = self._read_post_utf8_body(
            max_bytes=64 * 1024,
            empty_detail="Empty LLM config payload",
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
            updated = self.server.llm_router.update_config(decoded)
        except ValueError as exc:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "invalid_llm_config", "detail": str(exc)},
            )
            return
        self._send_json(HTTPStatus.OK, updated)

    def _handle_post_ai_assist(self) -> None:
        text_body = self._read_post_utf8_body(
            max_bytes=256 * 1024,
            empty_detail="Empty AI assist payload",
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
            result = _run_ai_assist(decoded, llm_router=self.server.llm_router)
        except ValueError as exc:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "invalid_ai_request", "detail": str(exc)},
            )
            return
        except LlmProviderError as exc:
            self._send_json(
                exc.status_code,
                {
                    "error": exc.error_code or "ai_assist_failed",
                    "detail": str(exc),
                    "provider": exc.provider,
                    "stage": exc.stage,
                },
            )
            return
        except Exception as exc:
            self._send_json(
                HTTPStatus.BAD_GATEWAY,
                {
                    "error": "ai_assist_failed",
                    "detail": str(exc),
                    "provider": "unknown",
                    "stage": "ai_assist",
                },
            )
            return
        self._send_json(HTTPStatus.OK, result)

    def _handle_post_draft_pass(self, pass_name: str, *, max_bytes: int) -> None:
        text_body = self._read_post_utf8_body(
            max_bytes=max_bytes,
            empty_detail=f"Empty /v1/draft/{pass_name.replace('_', '-')} request payload",
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

        stage = f"draft.{pass_name}"
        handler = getattr(self.server.draft_engine, pass_name)
        try:
            result = handler(decoded)
        except ValueError as exc:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "invalid_draft_request", "detail": str(exc)},
            )
            return
        except LlmProviderError as exc:
            self._send_json(
                exc.status_code,
                {
                    "error": exc.error_code or "draft_pass_failed",
                    "detail": str(exc),
                    "provider": exc.provider,
                    "stage": exc.stage,
                },
            )
            return
        except Exception as exc:
            self._send_json(
                HTTPStatus.BAD_GATEWAY,
                {
                    "error": "draft_pass_failed",
                    "detail": str(exc),
                    "provider": "unknown",
                    "stage": stage,
                },
            )
            return
        self._send_json(HTTPStatus.OK, result)

    def _handle_post_remotion_install(self) -> None:
        text_body = self._read_post_utf8_body(
            max_bytes=64 * 1024,
            empty_detail="Empty Remotion install request payload",
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
        if not isinstance(decoded, dict):
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "detail": "JSON body must be an object"},
            )
            return

        user_request = str(decoded.get("user_request") or "").strip()
        enable_broll = bool(decoded.get("enable_broll_popups", True))
        try:
            result = self.server.install_and_enable_remotion(
                user_request=user_request,
                enable_broll_popups=enable_broll,
            )
        except ValueError as exc:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "invalid_remotion_request", "detail": str(exc)},
            )
            return
        except Exception as exc:
            self._send_json(
                HTTPStatus.SERVICE_UNAVAILABLE,
                {"error": "remotion_install_failed", "detail": str(exc)},
            )
            return

        self._send_json(HTTPStatus.OK, result)

    def _handle_post_core_tts(self) -> None:
        text_body = self._read_post_utf8_body(
            max_bytes=256 * 1024,
            empty_detail="Empty /v1/core/tts payload",
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
            result = self.server.render_manager.synthesize_story_tts(decoded)
        except ValueError as exc:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "invalid_tts_request", "detail": str(exc)},
            )
            return
        except Exception as exc:
            self._send_json(
                HTTPStatus.BAD_GATEWAY,
                {"error": "tts_failed", "detail": str(exc)},
            )
            return
        self._send_json(HTTPStatus.OK, result)

    def _handle_post_async_task(self, path: str) -> None:
        rm = self.server.render_manager
        task_kind, fn = {
            "/v1/core/tts": ("tts", rm.synthesize_story_tts),
            "/v1/core/chop-broll": ("chop", rm.chop_talking_head_into_broll),
            "/v1/newsroom/backdrop": ("backdrop", rm.generate_backdrop),
        }[path]
        text_body = self._read_post_utf8_body(max_bytes=rm.max_render_body_bytes, empty_detail="Empty request payload")
        if text_body is None:
            return
        try:
            decoded = json.loads(text_body)
        except json.JSONDecodeError as exc:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "bad_request", "detail": f"Invalid JSON body: {exc}"})
            return
        try:
            accepted = rm.submit_async_task(task_kind, fn, decoded)
        except AsyncQueueFullError as exc:
            self._send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "queue_full", "detail": str(exc)})
            return
        except ValueError as exc:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_request", "detail": str(exc)})
            return
        self._send_json(HTTPStatus.ACCEPTED, accepted)

    def _handle_post_render_json(self, fn: Callable[[dict[str, Any]], dict[str, Any]], *, error_code: str) -> None:
        text_body = self._read_post_utf8_body(max_bytes=64 * 1024, empty_detail="Empty request payload")
        if text_body is None:
            return
        try:
            decoded = json.loads(text_body)
        except json.JSONDecodeError as exc:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "bad_request", "detail": f"Invalid JSON body: {exc}"})
            return
        try:
            result = fn(decoded)
        except ValueError as exc:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_request", "detail": str(exc)})
            return
        except Exception as exc:
            self._send_json(HTTPStatus.BAD_GATEWAY, {"error": error_code, "detail": str(exc)})
            return
        self._send_json(HTTPStatus.OK, result)

    def _handle_post_core_chop_broll(self) -> None:
        text_body = self._read_post_utf8_body(
            max_bytes=self.server.render_manager.max_render_body_bytes,
            empty_detail="Empty /v1/core/chop-broll payload",
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
            result = self.server.render_manager.chop_talking_head_into_broll(decoded)
        except ValueError as exc:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "invalid_chop_request", "detail": str(exc)},
            )
            return
        except Exception as exc:
            self._send_json(
                HTTPStatus.BAD_GATEWAY,
                {"error": "chop_broll_failed", "detail": str(exc)},
            )
            return
        self._send_json(HTTPStatus.OK, result)


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
    tts_runner: Callable[[str, Path], dict[str, Any] | None] | None = None,
    tts_studio_runner: Callable[..., dict[str, Any]] | None = None,
    aligner_fn: Callable[[Path, str], list[dict[str, Any]]] | None = None,
    draft_engine: DraftPassEngine | None = None,
    plate_runner: Callable[[str, str, Path], None] | None = None,
    llm_router: LlmRouter | None = None,
    llm_config_path: Path | str | None = None,
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
        tts_runner=tts_runner,
        tts_studio_runner=tts_studio_runner,
        aligner_fn=aligner_fn,
        plate_runner=plate_runner,
    )
    return OtaHttpServer(
        (host, port),
        store,
        render_manager=render_manager,
        draft_engine=draft_engine,
        llm_router=llm_router,
        llm_config_path=llm_config_path,
        quiet=quiet,
    )


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
