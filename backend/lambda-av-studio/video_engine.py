#!/usr/bin/env python3
"""
GPU-0 1080x1920 Real-Identity Talking-Head Engine for Aircheck.

Combines:
1. Optional LivePortrait pose+blink animation (`source_max_dim=1920`, `flag_normalize_lip=True`,
   `driving_option="pose-friendly"`) + DIS optical-flow high-frequency 1080p pore/iris detail
   transfer from the source portrait so non-mouth facial detail retains camera sharpness.
2. ByteDance LatentSync 1.6 (`stage2_512.yaml`, 512x512 mouth/lower-face diffusion + DeepCache)
   composited seamlessly onto the full 1080x1920 frame.
3. Studio 48 kHz / 256 kbps AAC mux of the exact untouched input voice waveform (never using
   LatentSync's internal 16 kHz resampled audio).
4. Objective 512-d ArcFace (`buffalo_l`) CSIM identity & Laplacian sharpness QA on output frames.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

import cv2
import numpy as np
import torch

ROOT = Path(os.environ.get("AIRCHECK_AV_ROOT", "/home/ubuntu/aircheck-av"))
LATENTSYNC_DIR = ROOT / "LatentSync"
LIVEPORTRAIT_DIR = ROOT / "LivePortrait"

sys.path.insert(0, str(LATENTSYNC_DIR))

from accelerate.utils import set_seed  # noqa: E402
from DeepCache import DeepCacheSDHelper  # noqa: E402
from diffusers import AutoencoderKL, DDIMScheduler  # noqa: E402
from insightface.app import FaceAnalysis  # noqa: E402
from omegaconf import OmegaConf  # noqa: E402

from latentsync.models.unet import UNet3DConditionModel  # noqa: E402
from latentsync.pipelines.lipsync_pipeline import LipsyncPipeline  # noqa: E402
from latentsync.whisper.audio2feature import Audio2Feature  # noqa: E402


def _run(cmd: list[str], timeout: int = 600) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=False)
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip()[-1200:]
        raise RuntimeError(f"Command failed (rc={proc.returncode}): {' '.join(cmd[:4])} :: {detail}")
    return proc


def prepare_canvas_portrait(src_img: Path, out_img: Path, width: int, height: int) -> Path:
    """Scale & center-crop portrait cleanly using Lanczos4 to exact (width, height)."""
    img = cv2.imread(str(src_img), cv2.IMREAD_COLOR)
    if img is None:
        raise RuntimeError(f"Failed to decode portrait image: {src_img}")
    ih, iw = img.shape[:2]
    scale = max(width / float(iw), height / float(ih))
    nw = max(width, int(round(iw * scale)))
    nh = max(height, int(round(ih * scale)))
    interp = cv2.INTER_LANCZOS4 if scale >= 1.0 else cv2.INTER_AREA
    resized = cv2.resize(img, (nw, nh), interpolation=interp)
    x0 = max(0, (nw - width) // 2)
    y0 = max(0, (nh - height) // 2)
    cropped = resized[y0 : y0 + height, x0 : x0 + width]
    out_img.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(out_img), cropped, [cv2.IMWRITE_PNG_COMPRESSION, 1])
    return out_img


def restore_high_freq_camera_detail(
    source_img_path: Path,
    lp_video_path: Path,
    out_video_path: Path,
    *,
    sigma: float = 2.4,
    strength: float = 0.82,
) -> Path:
    """Transfer true 1080p camera pore/iris/beard high-frequency detail onto LivePortrait frames."""
    src_bgr = cv2.imread(str(source_img_path), cv2.IMREAD_COLOR)
    if src_bgr is None:
        raise RuntimeError(f"Cannot read {source_img_path}")

    cap = cv2.VideoCapture(str(lp_video_path))
    fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
    frames: list[np.ndarray] = []
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if frame.shape[:2] != src_bgr.shape[:2]:
            frame = cv2.resize(frame, (src_bgr.shape[1], src_bgr.shape[0]), interpolation=cv2.INTER_LANCZOS4)
        frames.append(frame)
    cap.release()
    if not frames:
        raise RuntimeError(f"LivePortrait produced 0 frames at {lp_video_path}")

    src_f = src_bgr.astype(np.float32)
    src_low = cv2.GaussianBlur(src_f, (0, 0), sigmaX=sigma, sigmaY=sigma)
    src_hf = src_f - src_low

    ref_gray = cv2.cvtColor(frames[0], cv2.COLOR_BGR2GRAY)
    dis = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)
    h, w = ref_gray.shape
    grid_x, grid_y = np.meshgrid(np.arange(w, dtype=np.float32), np.arange(h, dtype=np.float32))

    raw_dir = out_video_path.parent / "hf_frames"
    if raw_dir.exists():
        shutil.rmtree(raw_dir)
    raw_dir.mkdir(parents=True, exist_ok=True)

    # Restrict detail transfer strictly to the interior portrait region (zero border ripple)
    yy = np.linspace(-1.0, 1.0, h, dtype=np.float32)[:, None]
    xx = np.linspace(-1.0, 1.0, w, dtype=np.float32)[None, :]
    radial = np.sqrt((xx / 0.62) ** 2 + ((yy + 0.06) / 0.58) ** 2)
    interior_mask = np.clip(1.0 - np.maximum(0.0, radial - 0.65) / 0.30, 0.0, 1.0)[..., None]

    # First warp src_hf from source_img -> frames[0] to align normalized lip/head baseline
    src_gray = cv2.cvtColor(src_bgr, cv2.COLOR_BGR2GRAY)
    flow_s2r = dis.calc(ref_gray, src_gray, None)
    aligned_hf0 = cv2.remap(
        src_hf,
        grid_x + flow_s2r[..., 0],
        grid_y + flow_s2r[..., 1],
        interpolation=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_REFLECT101,
    )

    for idx, frm in enumerate(frames):
        cur_gray = cv2.cvtColor(frm, cv2.COLOR_BGR2GRAY)
        if idx == 0:
            warped_hf = aligned_hf0
            warped_ref = frames[0].astype(np.float32)
        else:
            # Backward flow from cur -> frames[0] pulls aligned_hf0 cleanly into cur coordinates
            flow = dis.calc(cur_gray, ref_gray, None)
            map_x = grid_x + flow[..., 0]
            map_y = grid_y + flow[..., 1]
            warped_hf = cv2.remap(
                aligned_hf0, map_x, map_y, interpolation=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT101
            )
            warped_ref = cv2.remap(
                frames[0].astype(np.float32),
                map_x,
                map_y,
                interpolation=cv2.INTER_LINEAR,
                borderMode=cv2.BORDER_REFLECT101,
            )

        frm_f = frm.astype(np.float32)
        # Attenuate HF transfer where semantic appearance changes (e.g. closed eyelid during blink)
        photometric_diff = np.mean(np.abs(frm_f - warped_ref), axis=2, keepdims=True)
        gate = np.clip(1.0 - (photometric_diff - 10.0) / 18.0, 0.0, 1.0) * interior_mask
        enhanced_f = np.clip(frm_f + strength * gate * warped_hf, 0.0, 255.0)
        # Feather outermost 20px border back to untouched source portrait to eliminate any paste-back edge seam
        bx = np.minimum(np.arange(w, dtype=np.float32), np.arange(w - 1, -1, -1, dtype=np.float32))[None, :]
        by = np.minimum(np.arange(h, dtype=np.float32), np.arange(h - 1, -1, -1, dtype=np.float32))[:, None]
        b_alpha = np.clip(np.minimum(bx, by) / 20.0, 0.0, 1.0)[..., None]
        enhanced = (b_alpha * enhanced_f + (1.0 - b_alpha) * src_f).astype(np.uint8)
        cv2.imwrite(str(raw_dir / f"f_{idx:05d}.png"), enhanced)

    _run(
        [
            "ffmpeg",
            "-y",
            "-loglevel",
            "error",
            "-framerate",
            f"{fps:.4f}",
            "-i",
            str(raw_dir / "f_%05d.png"),
            "-c:v",
            "libx264",
            "-crf",
            "12",
            "-preset",
            "fast",
            "-pix_fmt",
            "yuv420p",
            str(out_video_path),
        ]
    )
    shutil.rmtree(raw_dir, ignore_errors=True)
    return out_video_path


def build_subtle_still_base_video(portrait_png: Path, out_mp4: Path, duration_sec: float = 8.0) -> Path:
    """Create a 25fps lossless base video from the native 1080p portrait for direct mouth-only dubbing."""
    _run(
        [
            "ffmpeg",
            "-y",
            "-loglevel",
            "error",
            "-loop",
            "1",
            "-framerate",
            "25",
            "-i",
            str(portrait_png),
            "-t",
            f"{min(8.0, max(2.0, duration_sec)):.3f}",
            "-c:v",
            "libx264",
            "-crf",
            "10",
            "-preset",
            "ultrafast",
            "-pix_fmt",
            "yuv420p",
            str(out_mp4),
        ]
    )
    return out_mp4


def run_liveportrait_driving(
    portrait_png: Path,
    work_dir: Path,
    *,
    max_dim: int = 1920,
    driving_multiplier: float = 0.30,
    driving_template: str = "d0.mp4",
) -> Path:
    """Run KwaiVGI/LivePortrait in locked-gaze pose mode and apply 1080p high-frequency detail transfer."""
    lp_out_dir = work_dir / "lp_out"
    lp_out_dir.mkdir(parents=True, exist_ok=True)
    driving_path = LIVEPORTRAIT_DIR / "assets" / "examples" / "driving" / driving_template
    if not driving_path.exists():
        driving_path = LIVEPORTRAIT_DIR / "assets" / "examples" / "driving" / "d0.mp4"

    cmd = [
        sys.executable,
        "inference.py",
        "-s",
        str(portrait_png),
        "-d",
        str(driving_path),
        "-o",
        str(lp_out_dir),
        "--source-max-dim",
        str(max_dim),
        "--driving-option",
        "pose-friendly",
        "--driving-multiplier",
        str(driving_multiplier),
        "--animation-region",
        "pose",
        "--flag-normalize-lip",
        "--flag-pasteback",
        "--flag-stitching",
        "--flag-relative-motion",
    ]
    proc = subprocess.run(
        cmd,
        cwd=str(LIVEPORTRAIT_DIR),
        capture_output=True,
        text=True,
        timeout=300,
        check=False,
    )
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip()[-1200:]
        raise RuntimeError(f"LivePortrait failed (rc={proc.returncode}): {detail}")

    candidates = [
        p for p in sorted(lp_out_dir.glob("*.mp4")) if not p.name.endswith("_concat.mp4")
    ]
    if not candidates:
        raise RuntimeError(f"LivePortrait produced no output MP4 in {lp_out_dir}")
    raw_lp_mp4 = candidates[0]
    hf_lp_mp4 = work_dir / "lp_hf_1080p.mp4"
    return restore_high_freq_camera_detail(portrait_png, raw_lp_mp4, hf_lp_mp4)


class TalkingHeadEngine:
    def __init__(self, device: str = "cuda:0") -> None:
        torch.backends.cuda.matmul.allow_tf32 = True
        torch.backends.cudnn.allow_tf32 = True
        torch.backends.cudnn.benchmark = True
        self.device = device
        os.chdir(str(LATENTSYNC_DIR))
        cfg_path = LATENTSYNC_DIR / "configs" / "unet" / "stage2_512.yaml"
        self.config = OmegaConf.load(str(cfg_path))
        ckpt_path = LATENTSYNC_DIR / "checkpoints" / "latentsync_unet.pt"
        whisper_ckpt = LATENTSYNC_DIR / "checkpoints" / "whisper" / "tiny.pt"

        dtype = torch.float16
        self.dtype = dtype
        scheduler = DDIMScheduler.from_pretrained(str(LATENTSYNC_DIR / "configs"))
        audio_encoder = Audio2Feature(
            model_path=str(whisper_ckpt),
            device=device,
            num_frames=self.config.data.num_frames,
            audio_feat_length=self.config.data.audio_feat_length,
        )
        vae = AutoencoderKL.from_pretrained("stabilityai/sd-vae-ft-mse", torch_dtype=dtype)
        vae.config.scaling_factor = 0.18215
        vae.config.shift_factor = 0

        unet, _ = UNet3DConditionModel.from_pretrained(
            OmegaConf.to_container(self.config.model),
            str(ckpt_path),
            device="cpu",
        )
        unet = unet.to(dtype=dtype)

        self.pipeline = LipsyncPipeline(
            vae=vae,
            audio_encoder=audio_encoder,
            unet=unet,
            scheduler=scheduler,
        ).to(device)

        self.deepcache = DeepCacheSDHelper(pipe=self.pipeline)
        self.deepcache.set_params(cache_interval=3, cache_branch_id=0)
        self.deepcache.enable()

        self.arcface = FaceAnalysis(
            name="buffalo_l",
            root=str(LATENTSYNC_DIR / "checkpoints" / "auxiliary"),
            providers=["CUDAExecutionProvider", "CPUExecutionProvider"],
        )
        self.arcface.prepare(ctx_id=0, det_size=(512, 512))

    def measure_identity_and_sharpness(self, ref_img_path: Path, video_mp4: Path, samples: int = 8) -> dict[str, Any]:
        ref_bgr = cv2.imread(str(ref_img_path), cv2.IMREAD_COLOR)
        ref_faces = self.arcface.get(ref_bgr)
        if not ref_faces:
            return {"csim_mean": 0.0, "csim_min": 0.0, "laplacian_var": 0.0, "frames_checked": 0}
        ref_emb = ref_faces[0].normed_embedding

        cap = cv2.VideoCapture(str(video_mp4))
        total = max(1, int(cap.get(cv2.CAP_PROP_FRAME_COUNT)))
        idxs = sorted({min(total - 1, int(round(i * (total - 1) / max(1, samples - 1)))) for i in range(samples)})
        csims: list[float] = []
        laps: list[float] = []
        for target_idx in idxs:
            cap.set(cv2.CAP_PROP_POS_FRAMES, target_idx)
            ok, frm = cap.read()
            if not ok:
                continue
            faces = self.arcface.get(frm)
            if faces:
                f = max(faces, key=lambda x: (x.bbox[2] - x.bbox[0]) * (x.bbox[3] - x.bbox[1]))
                csims.append(float(np.dot(ref_emb, f.normed_embedding)))
                x1, y1, x2, y2 = [max(0, int(v)) for v in f.bbox]
                crop_gray = cv2.cvtColor(frm[y1:y2, x1:x2], cv2.COLOR_BGR2GRAY)
                laps.append(float(cv2.Laplacian(crop_gray, cv2.CV_64F).var()))
        cap.release()
        return {
            "csim_mean": round(float(np.mean(csims)), 4) if csims else 0.0,
            "csim_min": round(float(np.min(csims)), 4) if csims else 0.0,
            "face_laplacian_var": round(float(np.mean(laps)), 2) if laps else 0.0,
            "frames_checked": len(csims),
        }

    def render(
        self,
        *,
        avatar_path: Path,
        voice_wav: Path,
        output_mp4: Path,
        width: int = 1080,
        height: int = 1920,
        motion_mode: str = "liveportrait_hf",
        driving_video: Path | None = None,
        inference_steps: int = 25,
        guidance_scale: float = 1.5,
        seed: int = 42,
    ) -> dict[str, Any]:
        t0 = time.time()
        with tempfile.TemporaryDirectory(prefix="aircheck_ls16_", dir=str(ROOT / "work")) as td:
            work_dir = Path(td)
            # 1. Master lossless 48kHz audio + 16kHz conditioning copy for Whisper
            master_48k = work_dir / "master_48k.wav"
            whisper_16k = work_dir / "cond_16k.wav"
            _run(
                [
                    "ffmpeg",
                    "-y",
                    "-loglevel",
                    "error",
                    "-i",
                    str(voice_wav),
                    "-ac",
                    "1",
                    "-ar",
                    "48000",
                    "-c:a",
                    "pcm_s16le",
                    str(master_48k),
                ]
            )
            _run(
                [
                    "ffmpeg",
                    "-y",
                    "-loglevel",
                    "error",
                    "-i",
                    str(master_48k),
                    "-ac",
                    "1",
                    "-ar",
                    "16000",
                    "-c:a",
                    "pcm_s16le",
                    str(whisper_16k),
                ]
            )

            dur_s = float(
                _run(
                    [
                        "ffprobe",
                        "-v",
                        "error",
                        "-show_entries",
                        "format=duration",
                        "-of",
                        "default=noprint_wrappers=1:nokey=1",
                        str(master_48k),
                    ]
                ).stdout.strip()
            )

            # 2. Prepare base video at exact (width, height)
            portrait_png = prepare_canvas_portrait(avatar_path, work_dir / "portrait.png", width, height)
            t_motion0 = time.time()
            if driving_video is not None and driving_video.exists():
                base_mp4 = work_dir / "base_driving.mp4"
                _run(
                    [
                        "ffmpeg",
                        "-y",
                        "-loglevel",
                        "error",
                        "-i",
                        str(driving_video),
                        "-vf",
                        f"scale={width}:{height}:force_original_aspect_ratio=increase,crop={width}:{height},fps=25,setsar=1",
                        "-an",
                        "-c:v",
                        "libx264",
                        "-crf",
                        "12",
                        "-preset",
                        "fast",
                        "-pix_fmt",
                        "yuv420p",
                        str(base_mp4),
                    ]
                )
            elif motion_mode == "still":
                base_mp4 = build_subtle_still_base_video(portrait_png, work_dir / "base_still.mp4", dur_s)
            else:
                base_mp4 = run_liveportrait_driving(
                    portrait_png,
                    work_dir,
                    max_dim=max(width, height),
                )
            motion_sec = round(time.time() - t_motion0, 2)

            # 3. Run LatentSync 1.6 (512x512 mouth diffusion composited onto full frame)
            t_sync0 = time.time()
            set_seed(seed)
            ls_temp = work_dir / "ls_temp"
            ls_raw_out = work_dir / "ls_raw.mp4"
            os.chdir(str(LATENTSYNC_DIR))
            self.pipeline(
                video_path=str(base_mp4),
                audio_path=str(whisper_16k),
                video_out_path=str(ls_raw_out),
                num_frames=self.config.data.num_frames,
                num_inference_steps=inference_steps,
                guidance_scale=guidance_scale,
                weight_dtype=self.dtype,
                width=self.config.data.resolution,
                height=self.config.data.resolution,
                mask_image_path=str(LATENTSYNC_DIR / self.config.data.mask_image_path),
                temp_dir=str(ls_temp),
            )
            lipsync_sec = round(time.time() - t_sync0, 2)

            # 4. Mux LatentSync's raw video stream with the UNTOUCHED 48 kHz master WAV (256k AAC)
            video_only = ls_temp / "video.mp4"
            if not video_only.exists():
                video_only = ls_raw_out
            vid_dur = float(
                _run(
                    [
                        "ffprobe",
                        "-v",
                        "error",
                        "-show_entries",
                        "format=duration",
                        "-of",
                        "default=noprint_wrappers=1:nokey=1",
                        str(video_only),
                    ]
                ).stdout.strip()
            )
            target_dur = min(dur_s, vid_dur) if vid_dur > 0 else dur_s
            output_mp4.parent.mkdir(parents=True, exist_ok=True)
            _run(
                [
                    "ffmpeg",
                    "-y",
                    "-loglevel",
                    "error",
                    "-i",
                    str(video_only),
                    "-i",
                    str(master_48k),
                    "-map",
                    "0:v:0",
                    "-map",
                    "1:a:0",
                    "-c:v",
                    "libx264",
                    "-preset",
                    "medium",
                    "-crf",
                    "16",
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
                    "-t",
                    f"{target_dur:.6f}",
                    "-movflags",
                    "+faststart",
                    str(output_mp4),
                ]
            )

            qa = self.measure_identity_and_sharpness(portrait_png, output_mp4)
            total_sec = round(time.time() - t0, 2)
            return {
                "output_mp4": str(output_mp4),
                "output_bytes": output_mp4.stat().st_size,
                "width": width,
                "height": height,
                "duration_sec": round(target_dur, 3),
                "motion_mode": motion_mode,
                "motion_sec": motion_sec,
                "lipsync_sec": lipsync_sec,
                "total_render_sec": total_sec,
                "identity_qa": qa,
            }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--avatar", type=Path, required=True)
    ap.add_argument("--voice-wav", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--width", type=int, default=1080)
    ap.add_argument("--height", type=int, default=1920)
    ap.add_argument("--motion-mode", default="liveportrait_hf", choices=["liveportrait_hf", "still"])
    ap.add_argument("--driving-video", type=Path, default=None)
    ap.add_argument("--steps", type=int, default=25)
    ap.add_argument("--guidance", type=float, default=1.5)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    eng = TalkingHeadEngine(device="cuda:0")
    res = eng.render(
        avatar_path=args.avatar,
        voice_wav=args.voice_wav,
        output_mp4=args.output,
        width=args.width,
        height=args.height,
        motion_mode=args.motion_mode,
        driving_video=args.driving_video,
        inference_steps=args.steps,
        guidance_scale=args.guidance,
        seed=args.seed,
    )
    print(json.dumps(res, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
