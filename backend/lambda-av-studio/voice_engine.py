#!/usr/bin/env python3
"""
GPU-1 Studio BF16 Voice Cloning Engine for Aircheck (F5-TTS v1 + Chatterbox TTS).

Synthesizes natural 48 kHz broadcast-mastered speech cloned from Ryan's clean
reference recording (`voice_ref.wav`), supporting multi-sentence scripts with
clean breath spacing and zero attention drift.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

import numpy as np
import soundfile as sf
import torch

DEFAULT_REF_TEXT = (
    "Ronnie and Matt, I'm actually, I'm deadass about that IoT hackathon tomorrow. "
    "You guys don't have to go, but I'm gonna go. I gotta show up and get a job, so let me know."
)


def split_sentences(text: str, max_chars: int = 210) -> list[str]:
    cleaned = re.sub(r"\s+", " ", text).strip()
    if not cleaned:
        return []
    raw_parts = [p.strip() for p in re.split(r"(?<=[.!?])\s+", cleaned) if p.strip()]
    chunks: list[str] = []
    for part in raw_parts:
        if len(part) <= max_chars:
            chunks.append(part)
        else:
            sub_parts = [s.strip() for s in re.split(r"(?<=[,;:])\s+", part) if s.strip()]
            buf = ""
            for sp in sub_parts:
                cand = f"{buf} {sp}".strip() if buf else sp
                if len(cand) <= max_chars:
                    buf = cand
                else:
                    if buf:
                        chunks.append(buf)
                    buf = sp
            if buf:
                chunks.append(buf)
    return chunks


def apply_cosine_fades(wav: np.ndarray, sr: int, fade_ms: float = 12.0) -> np.ndarray:
    n = int(round(sr * fade_ms / 1000.0))
    if wav.size <= 2 * n or n <= 0:
        return wav
    out = wav.astype(np.float32).copy()
    ramp = 0.5 * (1.0 - np.cos(np.linspace(0.0, np.pi, n, dtype=np.float32)))
    out[:n] *= ramp
    out[-n:] *= ramp[::-1]
    return out


def master_to_48k_broadcast_wav(raw_wav: Path, out_wav: Path) -> Path:
    out_wav.parent.mkdir(parents=True, exist_ok=True)
    proc = subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-loglevel",
            "error",
            "-i",
            str(raw_wav),
            "-af",
            "highpass=f=75,loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000",
            "-ac",
            "1",
            "-c:a",
            "pcm_s16le",
            str(out_wav),
        ],
        capture_output=True,
        text=True,
        timeout=120,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg broadcast mastering failed: {proc.stderr.strip()}")
    return out_wav


class StudioVoiceEngine:
    def __init__(self, device: str = "cuda:0") -> None:
        self.device = device
        self._f5 = None
        self._chatterbox = None

    @property
    def f5(self):
        if self._f5 is None:
            from f5_tts.api import F5TTS

            self._f5 = F5TTS(device=self.device)
        return self._f5

    @property
    def chatterbox(self):
        if self._chatterbox is None:
            from chatterbox.tts import ChatterboxTTS

            self._chatterbox = ChatterboxTTS.from_pretrained(device=self.device)
        return self._chatterbox

    def synthesize(
        self,
        *,
        text: str,
        ref_wav: Path,
        out_wav: Path,
        backend: str = "chatterbox",
        ref_text: str = DEFAULT_REF_TEXT,
        seed: int = 42,
        exaggeration: float = 0.35,
        cfg_weight: float = 0.55,
        speed: float = 1.0,
    ) -> dict[str, Any]:
        t0 = time.time()
        torch.manual_seed(seed)
        if torch.cuda.is_available():
            torch.cuda.manual_seed_all(seed)

        sentences = split_sentences(text)
        if not sentences:
            raise ValueError("Spoken text is empty")

        pieces: list[np.ndarray] = []
        sr_out = 24000
        with tempfile.TemporaryDirectory(prefix="aircheck_tts_") as td:
            td_path = Path(td)
            # Clean 24kHz reference for zero-shot conditioning
            ref_24k = td_path / "ref_24k.wav"
            subprocess.run(
                [
                    "ffmpeg",
                    "-y",
                    "-loglevel",
                    "error",
                    "-i",
                    str(ref_wav),
                    "-ac",
                    "1",
                    "-ar",
                    "24000",
                    "-c:a",
                    "pcm_s16le",
                    str(ref_24k),
                ],
                check=True,
            )

            for idx, sent in enumerate(sentences):
                if backend == "f5":
                    wav_np, sr_out, _ = self.f5.infer(
                        ref_file=str(ref_24k),
                        ref_text=ref_text,
                        gen_text=sent,
                        speed=speed,
                        seed=seed + idx,
                    )
                    seg = np.asarray(wav_np, dtype=np.float32).reshape(-1)
                elif backend == "chatterbox":
                    wav_t = self.chatterbox.generate(
                        sent,
                        audio_prompt_path=str(ref_24k),
                        exaggeration=exaggeration,
                        cfg_weight=cfg_weight,
                    )
                    sr_out = int(self.chatterbox.sr)
                    seg = wav_t.squeeze().detach().cpu().numpy().astype(np.float32).reshape(-1)
                else:
                    raise ValueError(f"Unsupported voice backend: {backend!r}")

                seg = apply_cosine_fades(seg, sr_out, fade_ms=12.0)
                pieces.append(seg)
                if idx + 1 < len(sentences):
                    pause = np.zeros(int(round(sr_out * 0.14)), dtype=np.float32)
                    pieces.append(pause)

            joined = np.concatenate(pieces, axis=0)
            raw_joined = td_path / "joined_raw.wav"
            sf.write(str(raw_joined), joined, sr_out, subtype="PCM_16")
            master_to_48k_broadcast_wav(raw_joined, out_wav)

        dur_s = float(sf.info(str(out_wav)).duration)
        return {
            "out_wav": str(out_wav),
            "backend": backend,
            "sentence_count": len(sentences),
            "duration_sec": round(dur_s, 3),
            "synth_sec": round(time.time() - t0, 2),
        }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--text", required=True)
    ap.add_argument("--ref-wav", type=Path, required=True)
    ap.add_argument("--ref-text", default=DEFAULT_REF_TEXT)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--backend", default="chatterbox", choices=["chatterbox", "f5"])
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--exaggeration", type=float, default=0.35)
    ap.add_argument("--cfg-weight", type=float, default=0.55)
    args = ap.parse_args()

    eng = StudioVoiceEngine(device="cuda:0")
    res = eng.synthesize(
        text=args.text,
        ref_wav=args.ref_wav,
        out_wav=args.output,
        backend=args.backend,
        ref_text=args.ref_text,
        seed=args.seed,
        exaggeration=args.exaggeration,
        cfg_weight=args.cfg_weight,
    )
    print(json.dumps(res, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
