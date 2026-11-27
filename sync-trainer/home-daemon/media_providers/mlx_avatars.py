"""Local Apple Silicon MLX talking-head video adapters for Aircheck Mac Studio rig.

Supports two native MLX harnesses installed under `.mlx-avatars/` (or overridden via env):
  - `avatarforcing_mlx`: AvatarForcing (CVPR 2026, CC BY-NC 4.0) — author-recommended
    `float32` flow transformer + `float16` motion decoder (512x512 @ 25 fps, real-time).
  - `longcat_avatar_mlx`: LongCat-Video-Avatar 1.6 (MIT) — author-recommended
    `v1.6-bf16` on M5 Max 128 GB (also supports `v1.6-q4` / `v1.6-q8` if installed).

No host filesystem paths are ever returned in public probe metadata, and neither adapter
ever falls back to a hosted cloud provider on failure.
"""
from __future__ import annotations

import json
import math
import os
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_AVATARS_ROOT = REPO_ROOT / ".mlx-avatars"

LONGCAT_VARIANT_ORDER: tuple[tuple[str, str], ...] = (
    ("v1.6-bf16", "LongCat-Video-Avatar-1.6-bf16"),
    ("v1.6-q4", "LongCat-Video-Avatar-1.6-q4"),
    ("v1.6-q8", "LongCat-Video-Avatar-1.6-q8"),
)

DEFAULT_LONGCAT_PROMPT = (
    "A presenter speaks naturally and expressively directly to the camera in clean studio lighting."
)

# 480p aspect buckets from upstream LongCat-Video-Avatar (height, width)
LONGCAT_RATIO_BUCKETS: dict[str, tuple[int, int]] = {
    "9:16": (832, 480),
    "16:9": (480, 832),
    "1:1": (640, 608),
}


def _avatars_root() -> Path:
    raw = os.environ.get("AIRCHECK_MLX_AVATARS_ROOT", "").strip()
    return Path(raw).expanduser() if raw else DEFAULT_AVATARS_ROOT


def _avatarforcing_paths() -> tuple[Path, Path, Path]:
    repo = _avatars_root() / "avatarforcing-mlx"
    cli = repo / ".venv" / "bin" / "avatarforcing-mlx"
    cache_raw = os.environ.get("AVATARFORCING_MLX_HOME", "").strip()
    cache = Path(cache_raw).expanduser() if cache_raw else (Path.home() / ".cache" / "avatarforcing-mlx")
    return repo, cli, cache


def _longcat_paths() -> tuple[Path, Path, Path]:
    repo = _avatars_root() / "longcat-avatar-mlx"
    py = repo / ".venv" / "bin" / "python"
    weights_raw = os.environ.get("AIRCHECK_LONGCAT_WEIGHTS_DIR", "").strip()
    weights_root = Path(weights_raw).expanduser() if weights_raw else (repo / "weights")
    return repo, py, weights_root


def _check_indexed_shards(component_dir: Path, index_filename: str) -> tuple[int, int]:
    """Return (present_shards, total_shards) for an HF sharded safetensors component."""
    idx_path = component_dir / index_filename
    if not idx_path.is_file():
        return 0, 0
    try:
        idx = json.loads(idx_path.read_text(encoding="utf-8"))
        weight_map = idx.get("weight_map")
        if not isinstance(weight_map, dict) or not weight_map:
            return 0, 0
        shards = sorted(set(str(v) for v in weight_map.values()))
        present = sum(
            1 for name in shards
            if (component_dir / name).is_file() and (component_dir / name).stat().st_size > 0
        )
        return present, len(shards)
    except Exception:
        return 0, 0


def _inspect_longcat_variant(variant_dir: Path) -> tuple[bool, int, int]:
    """Check whether all required LongCat 1.6 files and shards exist and are non-empty."""
    required_files = [
        variant_dir / "vae" / "config.json",
        variant_dir / "vae" / "diffusion_pytorch_model.safetensors",
        variant_dir / "audio_encoder" / "config.json",
        variant_dir / "audio_encoder" / "model.safetensors",
        variant_dir / "lora" / "dmd_lora.safetensors",
        variant_dir / "lora" / "opsd_lora.safetensors",
        variant_dir / "dit" / "config.json",
        variant_dir / "text_encoder" / "config.json",
        variant_dir / "pipeline_config.json",
    ]
    base_present = sum(1 for p in required_files if p.is_file() and p.stat().st_size > 0)
    dit_present, dit_total = _check_indexed_shards(variant_dir / "dit", "diffusion_pytorch_model.safetensors.index.json")
    t5_present, t5_total = _check_indexed_shards(variant_dir / "text_encoder", "model.safetensors.index.json")
    total = len(required_files) + (dit_total or 1) + (t5_total or 1)
    present = base_present + dit_present + t5_present
    ready = (
        base_present == len(required_files)
        and dit_total > 0 and dit_present == dit_total
        and t5_total > 0 and t5_present == t5_total
    )
    return ready, present, total


def probe_avatarforcing() -> dict[str, Any]:
    """Probe local AvatarForcing MLX readiness without exposing host paths."""
    _, cli, cache = _avatarforcing_paths()
    if not cli.is_file():
        return {
            "available": False,
            "detail": "Local AvatarForcing MLX runtime not installed on rig",
        }
    required = [
        cache / "flow_transformer_mlx.safetensors",
        cache / "motion_autoencoder.pth",
        cache / "wav2vec2-base-960h" / "config.json",
    ]
    missing = [p.name for p in required if not (p.is_file() and p.stat().st_size > 0)]
    if missing:
        return {
            "available": False,
            "detail": "AvatarForcing MLX checkpoints not downloaded yet",
        }
    return {
        "available": True,
        "detail": "Mac Studio MLX · fp32 flow / fp16 decode · realtime (CC BY-NC)",
    }


def probe_longcat_avatar() -> dict[str, Any]:
    """Probe local LongCat-Video-Avatar 1.6 MLX readiness without exposing host paths."""
    _, py, weights_root = _longcat_paths()
    if not py.is_file():
        return {
            "available": False,
            "variant": None,
            "detail": "Local LongCat-Avatar 1.6 MLX runtime not installed on rig",
        }

    preferred = os.environ.get("AIRCHECK_LONGCAT_VARIANT", "").strip()
    order = list(LONGCAT_VARIANT_ORDER)
    if preferred:
        order.sort(key=lambda pair: 0 if pair[0] == preferred else 1)

    partial_note: str | None = None
    for variant, dirname in order:
        vdir = weights_root / dirname
        if not vdir.exists():
            continue
        ready, present, total = _inspect_longcat_variant(vdir)
        if ready:
            return {
                "available": True,
                "variant": variant,
                "detail": f"Mac Studio MLX · {variant} 8-step CFG-free (MIT)",
            }
        if present > 0 and partial_note is None:
            partial_note = f"LongCat 1.6 {variant} downloading ({present}/{total} files ready)"

    return {
        "available": False,
        "variant": None,
        "detail": partial_note or "LongCat 1.6 weights not downloaded (needs v1.6-bf16)",
    }


def _mux_and_frame_avatar(
    *,
    raw_mp4: Path,
    master_wav: Path,
    output: Path,
    width: int,
    height: int,
    duration_ms: int,
    square_backdrop: bool,
    timeout_sec: int,
) -> dict[str, Any]:
    """Frame rendered MLX video cleanly into target WxH, mux canonical master WAV, and verify A/V sync."""
    from generate_talking_head import verify_av_sync_ffprobe

    if square_backdrop:
        # Full-bleed blurred studio backdrop behind centered 512x512 face crop (no harsh black bars or ear crop)
        fc = (
            f"[0:v]split=2[bg][fg];"
            f"[bg]scale={width}:{height}:force_original_aspect_ratio=increase:flags=lanczos,"
            f"crop={width}:{height},gblur=sigma=32,eq=brightness=-0.16[bg2];"
            f"[fg]scale={width}:{height}:force_original_aspect_ratio=decrease:flags=lanczos[fg2];"
            f"[bg2][fg2]overlay=(W-w)/2:(H-h)/2,format=yuv420p[vout]"
        )
        filter_args = ["-filter_complex", fc, "-map", "[vout]", "-map", "1:a:0"]
    else:
        vf = (
            f"scale={width}:{height}:force_original_aspect_ratio=increase:flags=lanczos,"
            f"crop={width}:{height},format=yuv420p"
        )
        filter_args = ["-map", "0:v:0", "-map", "1:a:0", "-vf", vf]

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".mlx_publish_", dir=output.parent) as publish_dir:
        staged = Path(publish_dir) / "render.mp4"
        cmd = [
            "ffmpeg",
            "-v", "error",
            "-y",
            "-i", str(raw_mp4),
            "-i", str(master_wav),
            *filter_args,
            "-c:v", "libx264",
            "-crf", "16",
            "-preset", "medium",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac",
            "-b:a", "192k",
            "-ar", "44100",
            "-shortest",
            "-movflags", "+faststart",
            str(staged),
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout_sec, check=False)
        if proc.returncode != 0 or not staged.is_file() or staged.stat().st_size == 0:
            raise RuntimeError(f"ffmpeg framing/mux failed for MLX avatar: {(proc.stderr or proc.stdout)[-1000:]}")
        qa = verify_av_sync_ffprobe(
            staged,
            expected_audio_ms=duration_ms,
            expected_width=width,
            expected_height=height,
        )
        os.replace(staged, output)
    return qa


def render_avatarforcing(
    *,
    master_wav: Path,
    avatar: Path,
    output: Path,
    width: int,
    height: int,
    duration_ms: int,
    timeout_sec: int = 900,
) -> dict[str, Any]:
    """Render talking-head video via local AvatarForcing MLX (author defaults: fp32 flow, fp16 decoder)."""
    status = probe_avatarforcing()
    if not status["available"]:
        raise RuntimeError(
            f"AvatarForcing MLX is unavailable ({status['detail']}); no hosted fallback attempted."
        )
    repo, cli, cache = _avatarforcing_paths()
    t0 = time.perf_counter()
    with tempfile.TemporaryDirectory(prefix="aircheck_af_") as tmp_str:
        tmp = Path(tmp_str)
        raw_mp4 = tmp / "avatarforcing_raw.mp4"
        cmd = [
            str(cli),
            "--weights", str(cache),
            "generate",
            "--image", str(avatar.resolve()),
            "--audio", str(master_wav.resolve()),
            "--out", str(raw_mp4),
            "--crop",
            "--flow-dtype", "float32",
            "--decoder-dtype", "float16",
            "--nfe", "10",
        ]
        proc = subprocess.run(
            cmd,
            cwd=str(repo),
            capture_output=True,
            text=True,
            timeout=timeout_sec,
            check=False,
        )
        if proc.returncode != 0 or not raw_mp4.is_file() or raw_mp4.stat().st_size == 0:
            err = (proc.stderr or proc.stdout or "").strip()[-1500:]
            raise RuntimeError(f"AvatarForcing MLX failed (rc={proc.returncode}): {err}")
        qa = _mux_and_frame_avatar(
            raw_mp4=raw_mp4,
            master_wav=master_wav,
            output=output,
            width=width,
            height=height,
            duration_ms=duration_ms,
            square_backdrop=(width != height),
            timeout_sec=min(timeout_sec, 300),
        )
    elapsed_sec = round(time.perf_counter() - t0, 2)
    return {
        "engine": "avatarforcing_mlx:fp32_fp16",
        "output_mp4": str(output),
        "exact_input_voice_mp4": str(output),
        "output_bytes": output.stat().st_size,
        "duration_ms": duration_ms,
        "duration_sec": round(duration_ms / 1000.0, 3),
        "render_elapsed_sec": elapsed_sec,
        "ffprobe_qa": qa,
        "av_sync_drift_ms": qa["av_sync_drift_ms"],
        "audio_duration_ms": qa["audio_duration_ms"],
        "video_duration_ms": qa["video_duration_ms"],
        "provenance": {
            "provider": "avatarforcing_mlx",
            "flow_dtype": "float32",
            "decoder_dtype": "float16",
            "nfe": 10,
            "device": "apple_mlx_mps",
            "execution": "local_mlx",
        },
    }


def compute_longcat_segments(duration_ms: int, fps: int = 25, first_frames: int = 93, cont_frames: int = 80) -> int:
    """Compute LongCat 1.6 segment count covering duration_ms at 25 fps (93f start + 80f per continuation)."""
    need_frames = max(1, int(math.ceil((duration_ms / 1000.0) * fps)))
    if need_frames <= first_frames:
        return 1
    return 1 + int(math.ceil((need_frames - first_frames) / cont_frames))


def render_longcat_avatar(
    *,
    master_wav: Path,
    avatar: Path,
    output: Path,
    width: int,
    height: int,
    ratio: str,
    duration_ms: int,
    motion_prompt: str | None = None,
    timeout_sec: int = 7200,
) -> dict[str, Any]:
    """Render talking-head video via local LongCat-Video-Avatar 1.6 MLX (author default: v1.6-bf16)."""
    status = probe_longcat_avatar()
    if not status["available"] or not status.get("variant"):
        raise RuntimeError(
            f"LongCat-Video-Avatar 1.6 MLX is unavailable ({status['detail']}); no hosted fallback attempted."
        )
    variant = str(status["variant"])
    repo, py, weights_root = _longcat_paths()
    lc_h, lc_w = LONGCAT_RATIO_BUCKETS.get(ratio, (832, 480))
    num_segments = compute_longcat_segments(duration_ms)
    prompt = (motion_prompt or "").strip() or DEFAULT_LONGCAT_PROMPT
    t0 = time.perf_counter()

    with tempfile.TemporaryDirectory(prefix="aircheck_longcat_") as tmp_str:
        tmp = Path(tmp_str)
        raw_mp4 = tmp / "longcat_raw.mp4"
        runner = f"""
import pathlib, subprocess, sys, time
import mlx.core as mx
import numpy as np
sys.path.insert(0, {str(repo / 'scripts')!r})
import run_inference as ri

weights = pathlib.Path({str(weights_root)!r})
variant = {variant!r}
image_path = pathlib.Path({str(avatar.resolve())!r})
audio_path = pathlib.Path({str(master_wav.resolve())!r})
prompt = {prompt!r}
out_path = pathlib.Path({str(raw_mp4)!r})
height, width = {int(lc_h)}, {int(lc_w)}
num_frames, num_segments = 93, {int(num_segments)}

pipeline = ri.build_pipeline(weights, variant=variant)
total_frames = pipeline.frames_for_segments(num_segments, num_frames)
image = ri.preprocess_image(image_path, height=height, width=width)
audio_mel, audio_num_samples = ri.preprocess_audio_mel(
    audio_path, seconds=total_frames / pipeline.config.target_fps + 1.0
)
ids, mask = ri.tokenize_prompt(prompt, weights / ri.VARIANT_DIRNAMES[variant])
text_embeds = pipeline.text_encoder(ids, mask=mask)[:, None, :, :]
text_mask = mask[:, None, None, :]
empty_ids, empty_mask = mx.zeros_like(ids), mx.zeros_like(mask)
uncond_embeds = pipeline.text_encoder(empty_ids, mask=empty_mask)[:, None, :, :]
uncond_mask = empty_mask[:, None, None, :]

step_t = [time.time()]
def on_step(seg, step, total):
    now = time.time()
    if step > 0:
        print(f"longcat seg {{seg+1}}/{{num_segments}} step {{step}}/{{total}}: {{now - step_t[0]:.1f}}s", file=sys.stderr, flush=True)
    step_t[0] = now

video = pipeline(
    image=image, audio_mel=audio_mel,
    text_embeds=text_embeds, text_mask=text_mask,
    uncond_embeds=uncond_embeds, uncond_mask=uncond_mask,
    height=height, width=width, num_frames=num_frames, seed=42,
    num_segments=num_segments, audio_num_samples=audio_num_samples, on_step=on_step,
)
mx.eval(video)
arr = (np.asarray(video).transpose(0, 2, 3, 4, 1)[0] * 127.5 + 127.5).clip(0, 255).astype(np.uint8)
fps = pipeline.config.target_fps
subprocess.run([
    "ffmpeg", "-loglevel", "error", "-y",
    "-f", "rawvideo", "-pix_fmt", "rgb24",
    "-video_size", f"{{arr.shape[2]}}x{{arr.shape[1]}}", "-framerate", str(fps),
    "-i", "pipe:0", "-i", str(audio_path),
    "-t", f"{{arr.shape[0] / fps:.6f}}",
    "-map", "0:v:0", "-map", "1:a:0",
    "-c:v", "libx264", "-crf", "16", "-preset", "medium", "-pix_fmt", "yuv420p",
    "-c:a", "aac", "-shortest", str(out_path),
], input=np.ascontiguousarray(arr).tobytes(), check=True)
"""
        proc = subprocess.run(
            [str(py), "-c", runner],
            cwd=str(repo),
            capture_output=True,
            text=True,
            timeout=timeout_sec,
            check=False,
        )
        if proc.returncode != 0 or not raw_mp4.is_file() or raw_mp4.stat().st_size == 0:
            err = (proc.stderr or proc.stdout or "").strip()[-1500:]
            raise RuntimeError(f"LongCat-Video-Avatar 1.6 MLX failed (rc={proc.returncode}): {err}")
        qa = _mux_and_frame_avatar(
            raw_mp4=raw_mp4,
            master_wav=master_wav,
            output=output,
            width=width,
            height=height,
            duration_ms=duration_ms,
            square_backdrop=False,
            timeout_sec=300,
        )
    elapsed_sec = round(time.perf_counter() - t0, 2)
    return {
        "engine": f"longcat_avatar_mlx:{variant}",
        "output_mp4": str(output),
        "exact_input_voice_mp4": str(output),
        "output_bytes": output.stat().st_size,
        "duration_ms": duration_ms,
        "duration_sec": round(duration_ms / 1000.0, 3),
        "render_elapsed_sec": elapsed_sec,
        "ffprobe_qa": qa,
        "av_sync_drift_ms": qa["av_sync_drift_ms"],
        "audio_duration_ms": qa["audio_duration_ms"],
        "video_duration_ms": qa["video_duration_ms"],
        "provenance": {
            "provider": "longcat_avatar_mlx",
            "variant": variant,
            "num_segments": num_segments,
            "native_bucket": f"{lc_w}x{lc_h}",
            "device": "apple_mlx",
            "execution": "local_mlx",
        },
    }
