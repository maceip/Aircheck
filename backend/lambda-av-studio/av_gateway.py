#!/usr/bin/env python3
"""
Persistent Warm HTTP Worker Daemons for GPU-0 (Talking Head) and GPU-1 (Studio Voice)
on the Lambda 2xA6000 instance, bound strictly to 127.0.0.1 (accessed over SSH tunnel).
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import tempfile
from pathlib import Path
from typing import Any

import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel


class VideoRenderRequest(BaseModel):
    avatar_b64: str
    avatar_ext: str = ".jpg"
    voice_wav_b64: str
    width: int = 1080
    height: int = 1920
    motion_mode: str = "liveportrait_hf"
    inference_steps: int = 25
    guidance_scale: float = 1.5
    seed: int = 42


class VoiceSynthRequest(BaseModel):
    text: str
    ref_wav_b64: str | None = None
    ref_text: str | None = None
    backend: str = "chatterbox"
    seed: int = 42
    exaggeration: float = 0.35
    cfg_weight: float = 0.55
    speed: float = 1.0


def build_video_app() -> FastAPI:
    from video_engine import ROOT, TalkingHeadEngine

    eng = TalkingHeadEngine(device="cuda:0")
    app = FastAPI(title="Aircheck Lambda Video Engine (GPU 0)")

    @app.get("/health")
    def health() -> dict[str, Any]:
        return {"ok": True, "role": "video", "engine": "liveportrait_hf+latentsync_1.6_512"}

    @app.post("/v1/render_talking_head")
    def render_talking_head(req: VideoRenderRequest) -> dict[str, Any]:
        with tempfile.TemporaryDirectory(prefix="av_req_", dir=str(ROOT / "work")) as td:
            td_path = Path(td)
            ext = req.avatar_ext if req.avatar_ext.startswith(".") else f".{req.avatar_ext}"
            avatar_p = td_path / f"avatar{ext}"
            wav_p = td_path / "voice.wav"
            out_p = td_path / "out_1080p.mp4"
            avatar_p.write_bytes(base64.b64decode(req.avatar_b64))
            wav_p.write_bytes(base64.b64decode(req.voice_wav_b64))
            meta = eng.render(
                avatar_path=avatar_p,
                voice_wav=wav_p,
                output_mp4=out_p,
                width=req.width,
                height=req.height,
                motion_mode=req.motion_mode,
                inference_steps=req.inference_steps,
                guidance_scale=req.guidance_scale,
                seed=req.seed,
            )
            if not out_p.is_file() or out_p.stat().st_size == 0:
                raise HTTPException(status_code=500, detail="Rendered MP4 missing or empty")
            meta["mp4_b64"] = base64.b64encode(out_p.read_bytes()).decode("ascii")
            return meta

    return app


def build_voice_app(default_ref_wav: Path) -> FastAPI:
    from voice_engine import DEFAULT_REF_TEXT, StudioVoiceEngine

    eng = StudioVoiceEngine(device="cuda:0")
    # Warm primary backend immediately
    _ = eng.chatterbox
    _ = eng.f5
    app = FastAPI(title="Aircheck Lambda Voice Engine (GPU 1)")

    @app.get("/health")
    def health() -> dict[str, Any]:
        return {"ok": True, "role": "voice", "backends": ["chatterbox", "f5"]}

    @app.post("/v1/synthesize")
    def synthesize(req: VoiceSynthRequest) -> dict[str, Any]:
        with tempfile.TemporaryDirectory(prefix="tts_req_") as td:
            td_path = Path(td)
            if req.ref_wav_b64:
                ref_p = td_path / "ref.wav"
                ref_p.write_bytes(base64.b64decode(req.ref_wav_b64))
            else:
                if not default_ref_wav.is_file():
                    raise HTTPException(status_code=400, detail=f"Default ref wav missing: {default_ref_wav}")
                ref_p = default_ref_wav
            out_p = td_path / "synth_48k.wav"
            meta = eng.synthesize(
                text=req.text,
                ref_wav=ref_p,
                out_wav=out_p,
                backend=req.backend,
                ref_text=req.ref_text or DEFAULT_REF_TEXT,
                seed=req.seed,
                exaggeration=req.exaggeration,
                cfg_weight=req.cfg_weight,
                speed=req.speed,
            )
            meta["wav_b64"] = base64.b64encode(out_p.read_bytes()).decode("ascii")
            return meta

    return app


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--role", required=True, choices=["video", "voice"])
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument(
        "--default-ref-wav",
        type=Path,
        default=Path("/home/ubuntu/aircheck-av/assets/voice_ref.wav"),
    )
    args = ap.parse_args()
    app = build_video_app() if args.role == "video" else build_voice_app(args.default_ref_wav)
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
