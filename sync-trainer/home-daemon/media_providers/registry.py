from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

VALID_VOICE_BACKENDS: tuple[str, ...] = (
    "auto",
    "s2pro_v002",
    "chatterbox_local",
    "chatterbox",
    "f5",
    "heygen_voice",
)
VALID_VIDEO_BACKENDS: tuple[str, ...] = (
    "heygen",
    "musetalk",
)

# Normalize legacy config aliases ("legacy" / "chatterbox" inside --media-config).
_CONFIG_TTS_ALIASES: dict[str, str] = {
    "legacy": "auto",
    "auto": "auto",
    "chatterbox": "chatterbox_local",
    "chatterbox_local": "chatterbox_local",
}
_CONFIG_VIDEO_ALIASES: dict[str, str] = {
    "legacy": "heygen",
    "heygen": "heygen",
    "musetalk": "musetalk",
}


@dataclass(frozen=True)
class ProviderConfig:
    """Server-level opt-in configuration for local media providers (never exposes host paths over HTTP)."""

    tts_backend: str = "auto"
    video_backend: str = "heygen"
    chatterbox: dict[str, Any] | None = None
    musetalk: dict[str, Any] | None = None

    @property
    def local_chatterbox_configured(self) -> bool:
        return isinstance(self.chatterbox, dict) and bool(self.chatterbox)

    @property
    def local_musetalk_configured(self) -> bool:
        return isinstance(self.musetalk, dict) and bool(self.musetalk)

    def snapshot(self) -> dict[str, Any]:
        return {
            "schema_version": 1,
            **{key: value for key, value in asdict(self).items() if value is not None},
        }


def load_config(path: Path | str | None) -> ProviderConfig:
    if path is None:
        return ProviderConfig()
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(raw, dict) or set(raw) - {
        "schema_version",
        "tts_backend",
        "video_backend",
        "chatterbox",
        "musetalk",
    }:
        raise ValueError("Invalid media provider configuration fields")
    if type(raw.get("schema_version")) is not int or raw["schema_version"] != 1:
        raise ValueError("Media provider configuration requires schema_version 1")
    raw_tts = raw.get("tts_backend", "auto")
    raw_video = raw.get("video_backend", "heygen")
    if not isinstance(raw_tts, str) or raw_tts not in _CONFIG_TTS_ALIASES:
        raise ValueError("Unsupported media backend; unimplemented providers cannot be enabled")
    if not isinstance(raw_video, str) or raw_video not in _CONFIG_VIDEO_ALIASES:
        raise ValueError("Unsupported media backend; unimplemented providers cannot be enabled")
    tts = _CONFIG_TTS_ALIASES[raw_tts]
    video = _CONFIG_VIDEO_ALIASES[raw_video]
    for name in ("chatterbox", "musetalk"):
        if name in raw and not isinstance(raw[name], dict):
            raise ValueError(f"{name} configuration must be an object")
    if tts == "chatterbox_local" and not raw.get("chatterbox"):
        raise ValueError("Selected Chatterbox backend requires configuration")
    if video == "musetalk" and not raw.get("musetalk"):
        raise ValueError("Selected MuseTalk backend requires configuration")
    return ProviderConfig(tts, video, raw.get("chatterbox"), raw.get("musetalk"))


def build_provider_catalog(
    config: ProviderConfig,
    *,
    s2pro_available: bool,
    lambda_backends: Sequence[str],
    heygen_options: Mapping[str, Any],
    heygen_engines: Sequence[Mapping[str, Any]],
    default_heygen_look_id: str,
    default_heygen_engine: str,
    default_heygen_resolution: str,
    default_heygen_voice_id: str,
) -> dict[str, Any]:
    """Build the single unified `GET /v1/media/providers` payload (zero leaked filesystem paths)."""
    lambda_set = {str(b).lower() for b in lambda_backends}
    heygen_available = bool(heygen_options.get("available"))
    heygen_looks = list(heygen_options.get("looks") or [])
    heygen_voice = heygen_options.get("voice") if isinstance(heygen_options.get("voice"), Mapping) else None

    voice_backends: list[dict[str, Any]] = [
        {
            "id": "auto",
            "label": "Auto (Best Available)",
            "available": True,
            "execution": "auto",
            "detail": (
                "Local Chatterbox (--media-config)"
                if config.tts_backend == "chatterbox_local"
                else "Active GPU endpoint or local S2-Pro v002"
            ),
        },
        {
            "id": "s2pro_v002",
            "label": "S2-Pro v002 LoRA",
            "available": bool(s2pro_available),
            "execution": "local_gpu",
            "detail": "Fine-tuned on your voice (Mac Studio GPU)",
        },
        {
            "id": "chatterbox_local",
            "label": "Chatterbox Local",
            "available": config.local_chatterbox_configured,
            "execution": "local_mps",
            "detail": (
                "Isolated local Chatterbox (--media-config)"
                if config.local_chatterbox_configured
                else "Requires --media-config chatterbox block"
            ),
        },
        {
            "id": "chatterbox",
            "label": "Chatterbox GPU",
            "available": "chatterbox" in lambda_set,
            "execution": "remote_gpu",
            "detail": "Fast zero-shot voice on GPU endpoint",
        },
        {
            "id": "f5",
            "label": "F5-TTS GPU",
            "available": "f5" in lambda_set,
            "execution": "remote_gpu",
            "detail": "Zero-shot F5 voice on GPU endpoint",
        },
        {
            "id": "heygen_voice",
            "label": "HeyGen Voice",
            "available": heygen_voice is not None,
            "execution": "hosted",
            "detail": (
                f"HeyGen {heygen_voice.get('mode', 'instant')} clone"
                if heygen_voice is not None
                else "HeyGen cloned voice unavailable"
            ),
        },
    ]

    video_backends: list[dict[str, Any]] = [
        {
            "id": "heygen",
            "label": "HeyGen Avatar",
            "available": heygen_available,
            "execution": "hosted",
            "detail": (
                f"{len(heygen_looks)} live look(s)"
                if heygen_available
                else str(heygen_options.get("error") or "HeyGen CLI unavailable")
            ),
        },
        {
            "id": "musetalk",
            "label": "MuseTalk v1.5",
            "available": config.local_musetalk_configured,
            "execution": "local_cuda",
            "detail": (
                "Isolated CUDA worker (--media-config)"
                if config.local_musetalk_configured
                else "Requires --media-config musetalk block (CUDA)"
            ),
        },
    ]

    return {
        "schema_version": 2,
        "tts_backend": config.tts_backend,
        "video_backend": config.video_backend,
        "automatic_downloads": False,
        "voice_backends": voice_backends,
        "video_backends": video_backends,
        "heygen": {
            "available": heygen_available,
            "error": heygen_options.get("error"),
            "looks": heygen_looks,
            "engines": [dict(e) for e in heygen_engines],
            "voice": dict(heygen_voice) if heygen_voice is not None else None,
        },
        "defaults": {
            "voice_backend": config.tts_backend,
            "video_backend": config.video_backend,
            "heygen_look_id": default_heygen_look_id,
            "heygen_engine": default_heygen_engine,
            "heygen_resolution": default_heygen_resolution,
            "heygen_voice_id": default_heygen_voice_id,
        },
    }


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_artifacts(root: Path, manifest_path: Path, *, provider: str, required: set[str]) -> dict[str, Any]:
    """Verify locally approved artifacts before third-party model deserialization."""
    root = root.resolve(strict=True)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict) or manifest.get("provider") != provider:
        raise ValueError("Artifact manifest provider mismatch")
    for key in ("source_revision", "model_revision", "license_review"):
        if not isinstance(manifest.get(key), str) or not manifest[key].strip():
            raise ValueError(f"Artifact manifest requires {key}")
    if manifest.get("commercial_use_approved") is not True:
        raise ValueError("Artifacts require recorded commercial-use approval; no license is inferred")
    files = manifest.get("files")
    if not isinstance(files, dict):
        raise ValueError("Artifact manifest files must be an object")
    if not required.issubset(files):
        raise ValueError(f"Artifact manifest missing required files: {sorted(required - set(files))}")
    for name, expected in files.items():
        if not isinstance(name, str) or Path(name).is_absolute() or ".." in Path(name).parts:
            raise ValueError("Artifact filenames must be relative paths without traversal")
        target = (root / name).resolve(strict=True)
        if not target.is_relative_to(root) or not target.is_file():
            raise ValueError("Artifact escapes the configured model directory")
        if not isinstance(expected, str) or len(expected) != 64 or any(c not in "0123456789abcdef" for c in expected):
            raise ValueError(f"Invalid SHA-256 for {name}")
        if file_sha256(target) != expected:
            raise ValueError(f"Artifact checksum mismatch: {name}")
    return manifest


def existing_absolute_file(value: Any, label: str, *, preserve_symlink: bool = False) -> Path:
    if not isinstance(value, str) or not Path(value).is_absolute():
        raise ValueError(f"{label} must be an absolute local path")
    path = Path(value).resolve(strict=True)
    if not path.is_file():
        raise ValueError(f"{label} must be a local file")
    return Path(value) if preserve_symlink else path
