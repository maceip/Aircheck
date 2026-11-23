"""Pure-stdlib Tailscale/WireGuard OTA & Preference Telemetry HTTP Server.

Endpoints:
  GET  /health                              -> JSON {status, lora_version, bundle_version, pending_preferences}
  GET  /v1/bundles/latest/manifest.json     -> Verified bundle_manifest.json
  GET  /v1/bundles/{version}/manifest.json  -> Verified manifest for a specific bundle version
  GET  /v1/bundles/{version}/{filename}     -> Path-traversal-guarded file download with full RFC 9110
                                               HTTP Range resume (206 Partial Content, Accept-Ranges: bytes,
                                               Content-Range) for ~2.6 GB base GGUF & ~35 MB LoRA deltas
  POST /v1/preferences                      -> Strictly validates every NDJSON/JSONL line against
                                               preference_tuple.schema.json (400 on any malformed record)
                                               and atomically appends valid lines to preferences.jsonl
"""

from __future__ import annotations

import argparse
import ipaddress
import json
import logging
import os
import re
import threading
import urllib.parse
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Sequence

from tinker_voice_daemon import (
    SchemaValidationError,
    parse_preferences_ndjson,
    validate_bundle_manifest,
)

LOGGER = logging.getLogger("voicestudio.ota_server")

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8443
STREAM_CHUNK_BYTES = 256 * 1024  # 256 KiB streaming chunks for multi-GB GGUF files
MAX_PREFERENCES_BODY_BYTES = 16 * 1024 * 1024  # 16 MiB

_TAILSCALE_IPV4_NET = ipaddress.ip_network("100.64.0.0/10")
_TAILSCALE_IPV6_NET = ipaddress.ip_network("fd7a:115c:a1e0::/48")
_RANGE_RE = re.compile(r"^bytes=(\d*)-(\d*)$")
_SAFE_SEGMENT_RE = re.compile(r"^[A-Za-z0-9._-]+$")


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


class OtaHttpServer(ThreadingHTTPServer):
    """Multi-threaded stdlib HTTP server holding the shared OtaBundleStore."""

    allow_reuse_address = True
    daemon_threads = True

    def __init__(
        self,
        server_address: tuple[str, int],
        store: OtaBundleStore,
        *,
        quiet: bool = False,
    ) -> None:
        self.store = store
        self.quiet = quiet
        super().__init__(server_address, OtaRequestHandler)


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
        }
        if manifest_error is not None:
            payload["manifest_error"] = manifest_error

        self._send_json(HTTPStatus.OK, payload, head_only=head_only)

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
        file_size = artifact_path.stat().st_size
        content_type = (
            "application/json; charset=utf-8"
            if filename.endswith(".json")
            else "application/octet-stream"
        )

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
            if sha256_etag:
                self.send_header("ETag", f'"{sha256_etag}"')
            self.end_headers()
            if not head_only:
                self._stream_file_range(artifact_path, start, length)
            return

        self.send_response(int(HTTPStatus.OK))
        self.send_header("Content-Type", content_type)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(file_size))
        if sha256_etag:
            self.send_header("ETag", f'"{sha256_etag}"')
        self.end_headers()
        if not head_only:
            self._stream_file_range(artifact_path, 0, file_size)

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

    def do_POST(self) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path != "/v1/preferences":
            self._send_json(
                HTTPStatus.NOT_FOUND,
                {"error": "not_found", "detail": f"Unknown POST endpoint: {parsed.path}"},
            )
            return

        content_length_raw = self.headers.get("Content-Length")
        if content_length_raw is None:
            self._send_json(
                HTTPStatus.LENGTH_REQUIRED,
                {"error": "length_required", "detail": "Content-Length header is required"},
            )
            return

        try:
            content_length = int(content_length_raw)
        except ValueError:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "detail": "Invalid Content-Length header"},
            )
            return

        if content_length <= 0:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "detail": "Empty preference payload"},
            )
            return

        if content_length > self.server.store.max_preferences_body_bytes:
            self._send_json(
                HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                {
                    "error": "payload_too_large",
                    "detail": f"Payload exceeds max size of {self.server.store.max_preferences_body_bytes} bytes",
                },
            )
            return

        raw_body = self.rfile.read(content_length)
        try:
            text_body = raw_body.decode("utf-8")
        except UnicodeDecodeError as exc:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "detail": f"Payload is not valid UTF-8: {exc}"},
            )
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


def create_ota_server(
    host: str,
    port: int,
    bundles_root: Path | str,
    preferences_path: Path | str,
    *,
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
    return OtaHttpServer((host, port), store, quiet=quiet)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="VoiceStudio Tailscale/WireGuard OTA & Preference Telemetry HTTP Server"
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
    parser.add_argument("--quiet", action="store_true", help="Suppress per-request access logs")

    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    server = create_ota_server(
        host=args.host,
        port=args.port,
        bundles_root=args.bundles_dir,
        preferences_path=args.preferences_file,
        quiet=args.quiet,
    )
    bound_host, bound_port = server.server_address[:2]
    LOGGER.info("VoiceStudio OTA server listening on http://%s:%d", bound_host, bound_port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
