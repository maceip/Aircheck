from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from media_providers.chatterbox import offline_environment
from media_providers.registry import existing_absolute_file, file_sha256, verify_artifacts


REVIEWED_REVISION = "2536eaf06b85f775d8e04b7fd81bf6699155a58d"


def avatar_cache_key(avatar: Path, manifest: dict[str, Any]) -> str:
    material = {"avatar_sha256": file_sha256(avatar), "artifacts": manifest["files"], "revision": REVIEWED_REVISION, "version": "v15", "fps": 25, "bbox_shift": 0, "extra_margin": 10}
    return hashlib.sha256(json.dumps(material, sort_keys=True).encode()).hexdigest()


def validate_config(config: dict[str, Any]) -> tuple[Path, Path, Path, dict[str, Any]]:
    if config.get("reference_consent") is not True:
        raise ValueError("MuseTalk requires explicit portrait/presenter consent")
    python = existing_absolute_file(config.get("python_bin"), "musetalk.python_bin", preserve_symlink=True)
    entry = existing_absolute_file(config.get("inference_script"), "musetalk.inference_script")
    repo = entry.parent.parent
    if entry != repo / "scripts" / "inference.py":
        raise ValueError("MuseTalk entry must be scripts/inference.py in an installed checkout")
    revision = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    dirty = subprocess.run(["git", "-C", str(repo), "status", "--porcelain", "--untracked-files=no"], capture_output=True, text=True, check=True).stdout.strip()
    if revision != REVIEWED_REVISION or dirty:
        raise ValueError("MuseTalk requires the clean reviewed source revision; no automatic checkout changes")
    manifest_path = existing_absolute_file(config.get("artifact_manifest"), "musetalk.artifact_manifest")
    value = config.get("model_dir")
    if not isinstance(value, str) or not Path(value).is_absolute():
        raise ValueError("MuseTalk model_dir must be an absolute directory")
    models = Path(value).resolve(strict=True)
    required = {
        "musetalkV15/unet.pth", "musetalkV15/musetalk.json",
        "dwpose/dw-ll_ucoco_384.pth", "face-parse-bisent/resnet18-5c106cde.pth",
        "face-parse-bisent/79999_iter.pth", "s3fd/s3fd.pth",
        "sd-vae-ft-mse/config.json", "whisper/config.json", "whisper/preprocessor_config.json",
    }
    required.update(p.relative_to(models).as_posix() for p in models.rglob("*") if p.is_file() and p.resolve() != manifest_path)
    manifest = verify_artifacts(models, manifest_path, provider="musetalk", required=required)
    if manifest["source_revision"] != REVIEWED_REVISION:
        raise ValueError("MuseTalk manifest source revision mismatch")
    if not isinstance(manifest.get("hdtf_clearance"), str) or not manifest["hdtf_clearance"].strip():
        raise ValueError("MuseTalk requires a recorded HDTF commercialization clearance")
    return repo, python, models, manifest


def stage_sources(repo: Path, work: Path) -> None:
    listing = subprocess.run(
        ["git", "-C", str(repo), "ls-files", "-z", "--", "scripts", "musetalk"],
        capture_output=True, text=True, check=True, timeout=30,
    ).stdout
    for name in filter(None, listing.split("\0")):
        relative = Path(name)
        if relative.suffix not in {".py", ".json", ".yaml", ".yml", ".txt"}:
            continue
        source = repo / relative
        if source.is_symlink() or not source.resolve().is_relative_to(repo.resolve()):
            raise ValueError("MuseTalk source file escapes the reviewed checkout")
        target = work / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)


def render(config: dict[str, Any], *, master_wav: Path, avatar: Path, output: Path, width: int, height: int, duration_ms: int) -> dict[str, Any]:
    repo, python, models, manifest = validate_config(config)
    if output.exists():
        raise FileExistsError(f"Refusing to replace existing render: {output}")
    timeout = config.get("timeout_seconds", 1800)
    if type(timeout) is not int or not 1 <= timeout <= 7200:
        raise ValueError("MuseTalk timeout_seconds must be 1..7200")
    env = offline_environment()
    probe = subprocess.run([str(python), "-c", "import torch; raise SystemExit(0 if torch.cuda.is_available() else 1)"], env=env, capture_output=True, timeout=30)
    if probe.returncode:
        raise RuntimeError("Reviewed MuseTalk adapter requires a CUDA runtime; native Apple MPS is not supported. No hosted fallback was attempted.")
    key = avatar_cache_key(avatar, manifest)
    cache_root = config.get("cache_dir")
    if not isinstance(cache_root, str) or not Path(cache_root).is_absolute():
        raise ValueError("MuseTalk cache_dir must be an absolute task-owned directory")
    cache = Path(cache_root).resolve()
    cache.mkdir(parents=True, exist_ok=True)
    cached_coords = cache / f"{key}.pkl"
    output.parent.mkdir(parents=True, exist_ok=True)
    # Upstream builds shell commands; only controlled, space-free local paths reach its CLI.
    with tempfile.TemporaryDirectory(prefix="aircheck_musetalk_", dir="/tmp") as directory:
        work = Path(directory)
        (work / "models").symlink_to(models, target_is_directory=True)
        stage_sources(repo, work)
        detector = work / "musetalk" / "utils" / "face_detection" / "detection" / "sfd" / "s3fd.pth"
        detector.symlink_to(models / "s3fd" / "s3fd.pth")
        env["PYTHONPATH"] = os.pathsep.join([str(work), str(work / "musetalk" / "utils")])
        source = work / f"{key}.png"
        subprocess.run(["ffmpeg", "-v", "error", "-i", str(avatar), "-frames:v", "1", str(source)], capture_output=True, check=True, timeout=60)
        shutil.copyfile(master_wav, work / "voice.wav")
        coordinates = work / f"{key}.pkl"
        if cached_coords.is_file():
            shutil.copyfile(cached_coords, coordinates)
        request = {"aircheck": {"video_path": str(source), "audio_path": str(work / "voice.wav"), "result_name": "render.mp4"}}
        (work / "request.json").write_text(json.dumps(request), encoding="utf-8")
        command = [str(python), str(Path(__file__).with_name("offline_runner.py")), "scripts.inference", "--inference_config", str(work / "request.json"), "--result_dir", str(work / "results"), "--version", "v15", "--unet_config", "models/musetalkV15/musetalk.json", "--unet_model_path", "models/musetalkV15/unet.pth", "--fps", "25", "--batch_size", "1", "--saved_coord", "--use_saved_coord"]
        process = subprocess.run(command, cwd=work, env=env, capture_output=True, text=True, timeout=timeout, check=False)
        raw = work / "results" / "v15" / "render.mp4"
        if process.returncode or not raw.is_file() or raw.stat().st_size == 0:
            raise RuntimeError(f"MuseTalk failed without a valid render: {process.stderr[-1500:]}")
        from generate_talking_head import verify_av_sync_ffprobe

        with tempfile.TemporaryDirectory(prefix=".musetalk_publish_", dir=output.parent) as publish_dir:
            staged = Path(publish_dir) / "render.mp4"
            subprocess.run([
                "ffmpeg", "-v", "error", "-i", str(raw), "-i", str(master_wav),
                "-map", "0:v:0", "-map", "1:a:0", "-vf", f"scale={width}:{height}:force_original_aspect_ratio=decrease,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-ar", "44100", "-shortest", "-movflags", "+faststart", str(staged),
            ], capture_output=True, check=True, timeout=timeout)
            qa = verify_av_sync_ffprobe(staged, expected_audio_ms=duration_ms, expected_width=width, expected_height=height)
            os.link(staged, output)
        if coordinates.is_file() and not cached_coords.exists():
            with tempfile.TemporaryDirectory(prefix=".coords_", dir=cache) as cache_work:
                staged_coords = Path(cache_work) / "coords.pkl"
                shutil.copyfile(coordinates, staged_coords)
                try:
                    os.link(staged_coords, cached_coords)
                except FileExistsError:
                    pass
    return {
        "engine": "musetalk:v15", "output_mp4": str(output), "exact_input_voice_mp4": str(output),
        "duration_ms": duration_ms, "duration_sec": duration_ms / 1000.0, "ffprobe_qa": qa,
        "av_sync_drift_ms": qa["av_sync_drift_ms"], "audio_duration_ms": qa["audio_duration_ms"], "video_duration_ms": qa["video_duration_ms"],
        "provenance": {"provider": "musetalk", "source_revision": REVIEWED_REVISION, "model_revision": manifest["model_revision"], "avatar_cache_key": key, "cache_scope": "face coordinates only; not VAE latents", "audio_source": "canonical input master; AAC delivery encoding", "device": "cuda", "execution": "local_worker"},
    }
