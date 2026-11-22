#!/usr/bin/env python3
"""
Phase A · Dataset Preparation for Aircheck Cloud Persona Training.

Curates personal multi-minute videos (for example `/Users/mac/Downloads/clone/`)
into:
  1. Clean 48 kHz mono -16 LUFS vocal stems (`voice_dataset/`) for persistent `voice_id` fine-tuning.
  2. Sharp eye-visible head/viseme frames (`head_frames/`) for Flux/Wan Identity LoRA (`head_model_id`).

Respects quality exclusions so sunglasses, handheld occlusion, crowd ambience, or off-voice days
never pollute the enrolled models.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Iterable, List, Optional


DEFAULT_EXCLUDED_SUBSTRINGS = (
    "PXL_20261120_232709514",  # self-reported off-voice day / low level
    "VID_20261025_154029",     # sunglasses + venue ambience
)

DEFAULT_IMAGE_ONLY_SUBSTRINGS = (
    "lv_0_20261116082307",     # other voices join near end -> frames only, not voice
)


@dataclass(frozen=True)
class PreparedArtifact:
    kind: str  # "voice_stem" | "head_frame"
    source_clip: str
    path: str
    sha256: str
    size_bytes: int


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def classify_clip(
    clip_path: Path,
    excluded: Iterable[str] = DEFAULT_EXCLUDED_SUBSTRINGS,
    image_only: Iterable[str] = DEFAULT_IMAGE_ONLY_SUBSTRINGS,
) -> str:
    """Returns 'exclude', 'head_only', or 'voice_and_head'."""
    name = clip_path.name
    if any(token in name for token in excluded):
        return "exclude"
    if any(token in name for token in image_only):
        return "head_only"
    return "voice_and_head"


def extract_clean_voice_stem(src_video: Path, dst_wav: Path) -> None:
    dst_wav.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        "ffmpeg",
        "-y",
        "-i",
        str(src_video),
        "-vn",
        "-ac",
        "1",
        "-ar",
        "48000",
        "-af",
        "highpass=f=80,silenceremove=start_periods=1:start_silence=0.25:start_threshold=-40dB,loudnorm=I=-16:TP=-1.5:LRA=11",
        "-c:a",
        "pcm_s16le",
        str(dst_wav),
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def extract_head_frames(src_video: Path, out_dir: Path, fps: float = 2.0) -> List[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    pattern = out_dir / f"{src_video.stem}_%04d.jpg"
    cmd = [
        "ffmpeg",
        "-y",
        "-i",
        str(src_video),
        "-vf",
        f"fps={fps},scale='min(1080,iw)':-2",
        "-q:v",
        "2",
        str(pattern),
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    return sorted(out_dir.glob(f"{src_video.stem}_*.jpg"))


def prepare_dataset(input_dir: Path, output_dir: Path, fps: float = 2.0) -> dict:
    voice_dir = output_dir / "voice_stems"
    frames_dir = output_dir / "head_frames"
    voice_dir.mkdir(parents=True, exist_ok=True)
    frames_dir.mkdir(parents=True, exist_ok=True)

    artifacts: List[PreparedArtifact] = []
    skipped: List[str] = []

    video_files = sorted(
        p for p in input_dir.iterdir() if p.is_file() and p.suffix.lower() in {".mp4", ".mov", ".mkv"}
    )
    if not video_files:
        raise FileNotFoundError(f"No reference videos found in {input_dir}")

    for clip in video_files:
        role = classify_clip(clip)
        if role == "exclude":
            skipped.append(clip.name)
            continue

        if role in ("voice_and_head",):
            wav_path = voice_dir / f"{clip.stem}.wav"
            extract_clean_voice_stem(clip, wav_path)
            artifacts.append(
                PreparedArtifact(
                    kind="voice_stem",
                    source_clip=clip.name,
                    path=str(wav_path),
                    sha256=sha256_file(wav_path),
                    size_bytes=wav_path.stat().st_size,
                )
            )

        if role in ("head_only", "voice_and_head"):
            frames = extract_head_frames(clip, frames_dir, fps=fps)
            for frame in frames:
                artifacts.append(
                    PreparedArtifact(
                        kind="head_frame",
                        source_clip=clip.name,
                        path=str(frame),
                        sha256=sha256_file(frame),
                        size_bytes=frame.stat().st_size,
                    )
                )

    manifest = {
        "inputDir": str(input_dir),
        "skippedClips": skipped,
        "voiceStemCount": sum(1 for a in artifacts if a.kind == "voice_stem"),
        "headFrameCount": sum(1 for a in artifacts if a.kind == "head_frame"),
        "artifacts": [asdict(a) for a in artifacts],
    }
    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Prepare Aircheck Phase A Persona Training dataset")
    parser.add_argument("--input-dir", type=Path, required=True, help="Directory with personal talking videos")
    parser.add_argument("--output-dir", type=Path, required=True, help="Output curated dataset directory")
    parser.add_argument("--fps", type=float, default=2.0, help="Frame extraction rate for head/viseme bank")
    args = parser.parse_args(argv)

    manifest = prepare_dataset(args.input_dir, args.output_dir, fps=args.fps)
    print(
        json.dumps(
            {
                "voiceStemCount": manifest["voiceStemCount"],
                "headFrameCount": manifest["headFrameCount"],
                "skippedClips": manifest["skippedClips"],
                "manifest": str(args.output_dir / "manifest.json"),
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
