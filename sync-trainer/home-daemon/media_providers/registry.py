from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class Project:
    id: str
    capability: str
    source: str
    code_license: str
    model_license: str
    integration: str
    apple_support: str
    caveat: str


PROJECTS = (
    Project("openavatarchat", "orchestration", "HumanAIGC-Engineering/OpenAvatarChat", "Apache-2.0", "per component", "architecture reference", "standard stack requires CUDA", "Session/stream patterns only; no vendored handlers or models."),
    Project("chatterbox", "tts", "resemble-ai/chatterbox", "MIT", "MIT", "local adapter", "MPS or CPU", "Upstream retired; no inference benchmark claimed. PerTh watermark retained."),
    Project("musetalk", "lip_sync", "TMElyralab/MuseTalk", "MIT", "MIT with HDTF commercialization notice", "external CLI adapter", "CUDA worker; no native MPS claim", "Requires explicit artifact clearance and installed upstream checkout."),
    Project("amphion", "evaluation_and_codecs", "open-mmlab/Amphion", "MIT", "many checkpoints CC BY-NC / CC BY-NC-ND", "evaluation concepts", "CUDA oriented", "Restricted checkpoints are not integrated."),
    Project("rvc", "voice_conversion", "RVC-Project/Retrieval-based-Voice-Conversion-WebUI", "MIT", "per artifact", "not implemented", "current main lacks MPS selection", "Separate performance conversion, never a default post-TTS stage."),
    Project("sadtalker", "portrait_animation", "OpenTalker/SadTalker", "Apache-2.0 with third-party exceptions", "review dependencies", "not implemented", "documented M1 CPU setup", "No accelerated Mac path or live performance claimed."),
    Project("echomimic_v2", "upper_body_animation", "antgroup/echomimic_v2", "Apache-2.0", "unresolved", "blocked", "CUDA dependent", "Checkpoint permission unresolved; offline rendering only."),
    Project("facefusion", "face_processing", "facefusion/facefusion", "OpenRAIL-AS; incomplete inspected text", "mixed, including noncommercial", "blocked", "CoreML integration upstream", "No code or weights copied; obtain complete governing terms."),
    Project("vidu_s", "hosted_avatar", "shengshu-ai/Vidu-S", "no core implementation license", "unreleased", "not implemented", "API client only", "No public local model; hosted service needs separate authorization."),
    Project("omnitalker", "joint_audio_video", "HumanAIGC/omnitalker", "no model implementation license", "unreleased", "blocked", "unknown", "Project page and remote demo frontend are not inference code."),
    Project("deepfake_live_macos", "camera_face_swap", "ilyasakkus/deepfake-live-for-macos", "AGPL-3.0 with additional terms", "research-only InsightFace models", "blocked", "CoreML swap; CPU detection", "Mouth compositing is not audio-driven lip sync."),
)


@dataclass(frozen=True)
class ProviderConfig:
    tts_backend: str = "legacy"
    video_backend: str = "legacy"
    chatterbox: dict[str, Any] | None = None
    musetalk: dict[str, Any] | None = None

    def snapshot(self) -> dict[str, Any]:
        return {"schema_version": 1, **{key: value for key, value in asdict(self).items() if value is not None}}


def load_config(path: Path | str | None) -> ProviderConfig:
    if path is None:
        return ProviderConfig()
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(raw, dict) or set(raw) - {"schema_version", "tts_backend", "video_backend", "chatterbox", "musetalk"}:
        raise ValueError("Invalid media provider configuration fields")
    if type(raw.get("schema_version")) is not int or raw["schema_version"] != 1:
        raise ValueError("Media provider configuration requires schema_version 1")
    tts = raw.get("tts_backend", "legacy")
    video = raw.get("video_backend", "legacy")
    if tts not in ("legacy", "chatterbox") or video not in ("legacy", "musetalk"):
        raise ValueError("Unsupported media backend; unimplemented providers cannot be enabled")
    for name in ("chatterbox", "musetalk"):
        if name in raw and not isinstance(raw[name], dict):
            raise ValueError(f"{name} configuration must be an object")
    if tts == "chatterbox" and not raw.get("chatterbox"):
        raise ValueError("Selected Chatterbox backend requires configuration")
    if video == "musetalk" and not raw.get("musetalk"):
        raise ValueError("Selected MuseTalk backend requires configuration")
    return ProviderConfig(tts, video, raw.get("chatterbox"), raw.get("musetalk"))


def catalog(config: ProviderConfig) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "tts_backend": config.tts_backend,
        "video_backend": config.video_backend,
        "automatic_downloads": False,
        "readiness": "configuration_only; models not loaded or benchmarked",
        "projects": [dict(asdict(p), source=f"https://github.com/{p.source}") for p in PROJECTS],
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
