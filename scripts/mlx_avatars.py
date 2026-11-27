#!/usr/bin/env python3
"""Aircheck helper CLI for local Apple Silicon MLX talking-head avatars.

Wraps the two local harnesses installed under `.mlx-avatars/`:
  1. AvatarForcing (CVPR 2026, CC BY-NC 4.0) — author-recommended defaults:
     flow_dtype=float32, decoder_dtype=float16 (real-time 512x512 @ 25 fps).
  2. LongCat-Video-Avatar 1.6 (MIT) — author-recommended for M5 Max 128 GB:
     variant=v1.6-bf16 (48.96 GB on disk; matches q4 speed without 4-bit loss,
     faster than q8 on M5). Also supports v1.6-q4 (27.54 GB) / v1.6-q8 (34.99 GB).

Usage:
  ./scripts/mlx_avatars.py status
  ./scripts/mlx_avatars.py avatarforcing --image face.jpg --audio speech.wav --out out.mp4 [--seconds 6]
  ./scripts/mlx_avatars.py longcat-download [--variant v1.6-bf16] [--weights-dir /path/to/weights]
  ./scripts/mlx_avatars.py longcat --image face.jpg --audio speech.wav --prompt "..." --out out.mp4
"""
from __future__ import annotations

import argparse
import os
import pathlib
import shutil
import subprocess
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
AVATARS_ROOT = REPO_ROOT / ".mlx-avatars"
AF_DIR = AVATARS_ROOT / "avatarforcing-mlx"
LC_DIR = AVATARS_ROOT / "longcat-avatar-mlx"
DEFAULT_LC_WEIGHTS = LC_DIR / "weights"

LONGCAT_VARIANTS = {
    "v1.6-bf16": ("mlx-community/LongCat-Video-Avatar-1.6-bf16", "LongCat-Video-Avatar-1.6-bf16", 52.0),
    "v1.6-q8": ("mlx-community/LongCat-Video-Avatar-1.6-q8", "LongCat-Video-Avatar-1.6-q8", 38.0),
    "v1.6-q4": ("mlx-community/LongCat-Video-Avatar-1.6-q4", "LongCat-Video-Avatar-1.6-q4", 30.0),
}


def free_gb(path: pathlib.Path) -> float:
    probe = path
    while not probe.exists() and probe.parent != probe:
        probe = probe.parent
    usage = shutil.disk_usage(probe)
    return usage.free / 1e9


def cmd_status(_args: argparse.Namespace) -> int:
    af_cache = pathlib.Path(os.environ.get("AVATARFORCING_MLX_HOME", pathlib.Path.home() / ".cache" / "avatarforcing-mlx"))
    af_ready = (af_cache / "flow_transformer_mlx.safetensors").exists() and (af_cache / "motion_autoencoder.pth").exists()
    print(f"AvatarForcing MLX: {'READY' if af_ready else 'NOT DOWNLOADED'} (repo={AF_DIR}, cache={af_cache})")
    print(f"LongCat-Avatar 1.6 MLX: venv={'READY' if (LC_DIR / '.venv/bin/python').exists() else 'MISSING'} (repo={LC_DIR})")
    for variant, (_, dirname, need_gb) in LONGCAT_VARIANTS.items():
        wdir = DEFAULT_LC_WEIGHTS / dirname
        present = (wdir / "dit").exists() and (wdir / "vae").exists()
        print(f"  - {variant}: {'READY' if present else f'NOT DOWNLOADED (needs ~{need_gb:.0f} GB free)'} -> {wdir}")
    print(f"Free space on {REPO_ROOT}: {free_gb(REPO_ROOT):.2f} GB")
    return 0


def cmd_avatarforcing(args: argparse.Namespace) -> int:
    cmd = [
        "uv", "run", "avatarforcing-mlx", "generate",
        "--image", str(pathlib.Path(args.image).resolve()),
        "--audio", str(pathlib.Path(args.audio).resolve()),
        "--out", str(pathlib.Path(args.out).resolve()),
        "--flow-dtype", args.flow_dtype,
        "--decoder-dtype", args.decoder_dtype,
        "--nfe", str(args.nfe),
        "--seed", str(args.seed),
    ]
    if args.crop:
        cmd.append("--crop")
    if args.seconds is not None:
        cmd.extend(["--seconds", str(args.seconds)])
    return subprocess.call(cmd, cwd=str(AF_DIR))


def cmd_longcat_download(args: argparse.Namespace) -> int:
    hf_repo, dirname, need_gb = LONGCAT_VARIANTS[args.variant]
    weights_root = pathlib.Path(args.weights_dir).expanduser().resolve()
    target = weights_root / dirname
    avail = free_gb(weights_root)
    if avail < need_gb and not args.force:
        print(
            f"ERROR: {weights_root} has only {avail:.2f} GB free; {args.variant} ({hf_repo}) "
            f"requires ~{need_gb:.0f} GB free. Free disk space or pass --weights-dir /Volumes/<drive>/longcat-weights.",
            file=sys.stderr,
        )
        return 2
    target.mkdir(parents=True, exist_ok=True)
    hf_bin = shutil.which("hf") or shutil.which("huggingface-cli")
    if not hf_bin:
        print("ERROR: `hf` / `huggingface-cli` not found in PATH.", file=sys.stderr)
        return 1
    cmd = [hf_bin, "download", hf_repo, "--local-dir", str(target)]
    print("Running:", " ".join(cmd))
    return subprocess.call(cmd)


def cmd_longcat(args: argparse.Namespace) -> int:
    _, dirname, _ = LONGCAT_VARIANTS[args.variant]
    weights_root = pathlib.Path(args.weights_dir).expanduser().resolve()
    variant_dir = weights_root / dirname
    if not (variant_dir / "dit").exists():
        print(
            f"ERROR: LongCat weights missing at {variant_dir}. "
            f"Run: ./scripts/mlx_avatars.py longcat-download --variant {args.variant} --weights-dir {weights_root}",
            file=sys.stderr,
        )
        return 2
    py = LC_DIR / ".venv" / "bin" / "python"
    image_p = str(pathlib.Path(args.image).resolve())
    audio_p = str(pathlib.Path(args.audio).resolve())
    out_p = str(pathlib.Path(args.out).resolve())
    # Run inline via LongCat's venv to avoid upstream run_inference.py calling load_demo_inputs() unconditionally
    runner = f"""
import pathlib, subprocess, sys, time
import mlx.core as mx
import numpy as np
sys.path.insert(0, {str(LC_DIR / 'scripts')!r})
import run_inference as ri

weights = pathlib.Path({str(weights_root)!r})
variant = {args.variant!r}
image_path = pathlib.Path({image_p!r})
audio_path = pathlib.Path({audio_p!r})
prompt = {args.prompt!r}
out_path = pathlib.Path({out_p!r})
num_frames = {int(args.num_frames)}
num_segments = {int(args.num_segments)}
seed = {int(args.seed)}
height = {args.height!r}
width = {args.width!r}

t0 = time.time()
pipeline = ri.build_pipeline(weights, variant=variant)
print(f"Loaded {{variant}} in {{time.time() - t0:.1f}}s", flush=True)
if height is None or width is None:
    height, width = ri.bucket_size(image_path)
total_frames = pipeline.frames_for_segments(num_segments, num_frames)
print(f"Generating {{width}}x{{height}}, {{num_segments}} seg(s), {{total_frames}} frames ({{total_frames / pipeline.config.target_fps:.2f}}s)", flush=True)

image = ri.preprocess_image(image_path, height=height, width=width)
audio_mel, audio_num_samples = ri.preprocess_audio_mel(audio_path, seconds=total_frames / pipeline.config.target_fps + 1.0)
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
        print(f"  seg {{seg+1}} step {{step}}/{{total}}: {{now - step_t[0]:.1f}}s", flush=True)
    step_t[0] = now

t1 = time.time()
video = pipeline(
    image=image, audio_mel=audio_mel,
    text_embeds=text_embeds, text_mask=text_mask,
    uncond_embeds=uncond_embeds, uncond_mask=uncond_mask,
    height=height, width=width, num_frames=num_frames, seed=seed,
    num_segments=num_segments, audio_num_samples=audio_num_samples, on_step=on_step,
)
mx.eval(video)
elapsed = time.time() - t1
print(f"Done in {{elapsed:.1f}}s, peak {{mx.get_peak_memory()/1e9:.1f}} GB", flush=True)
arr = (np.asarray(video).transpose(0, 2, 3, 4, 1)[0] * 127.5 + 127.5).clip(0, 255).astype(np.uint8)
out_path.parent.mkdir(parents=True, exist_ok=True)
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
print(f"Saved {{out_path}}", flush=True)
"""
    return subprocess.call([str(py), "-c", runner], cwd=str(LC_DIR))


def main() -> int:
    ap = argparse.ArgumentParser(description="Aircheck MLX talking-head avatar helper")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("status", help="Show install, weight, and disk status")

    af = sub.add_parser("avatarforcing", help="Generate talking head with AvatarForcing MLX (fp32 flow / fp16 decoder)")
    af.add_argument("--image", required=True)
    af.add_argument("--audio", required=True)
    af.add_argument("--out", required=True)
    af.add_argument("--seconds", type=float, default=None)
    af.add_argument("--no-crop", dest="crop", action="store_false", default=True)
    af.add_argument("--flow-dtype", default="float32", choices=["float32", "bfloat16", "float16"])
    af.add_argument("--decoder-dtype", default="float16", choices=["float16", "float32"])
    af.add_argument("--nfe", type=int, default=10)
    af.add_argument("--seed", type=int, default=25)

    lcd = sub.add_parser("longcat-download", help="Download LongCat-Video-Avatar 1.6 MLX weights (default v1.6-bf16)")
    lcd.add_argument("--variant", default="v1.6-bf16", choices=tuple(LONGCAT_VARIANTS))
    lcd.add_argument("--weights-dir", default=str(DEFAULT_LC_WEIGHTS))
    lcd.add_argument("--force", action="store_true")

    lc = sub.add_parser("longcat", help="Generate talking avatar with LongCat-Video-Avatar 1.6 MLX")
    lc.add_argument("--image", required=True)
    lc.add_argument("--audio", required=True)
    lc.add_argument("--prompt", default="A person speaks naturally directly to the camera in studio lighting.")
    lc.add_argument("--out", required=True)
    lc.add_argument("--variant", default="v1.6-bf16", choices=tuple(LONGCAT_VARIANTS))
    lc.add_argument("--weights-dir", default=str(DEFAULT_LC_WEIGHTS))
    lc.add_argument("--num-frames", type=int, default=93)
    lc.add_argument("--num-segments", type=int, default=1)
    lc.add_argument("--height", type=int, default=None)
    lc.add_argument("--width", type=int, default=None)
    lc.add_argument("--seed", type=int, default=42)

    args = ap.parse_args()
    if args.cmd == "status":
        return cmd_status(args)
    if args.cmd == "avatarforcing":
        return cmd_avatarforcing(args)
    if args.cmd == "longcat-download":
        return cmd_longcat_download(args)
    if args.cmd == "longcat":
        return cmd_longcat(args)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
