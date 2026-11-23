#!/usr/bin/env python3
"""
Direct Voice-to-Talking-Head CLI for Aircheck / VoiceStudio.

Takes ANY real voice audio input (.wav/.mp3/.m4a/.pcm) + portrait image (defaulting to
Ryan's canonical `/Users/mac/Downloads/clone/images/avatar_ref.jpg`), optionally transcribes
the voice locally via offline `whisper-cli` (`/Users/mac/models/ggml-medium-q5_0.bin`),
encodes inline base64 media payloads, and invokes `bl video ref --model wan3.0-video`
to generate a real audio-driven talking-head MP4.
"""

from __future__ import annotations

import argparse
import base64
import json
import math
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

DEFAULT_AVATAR = Path("/Users/mac/Downloads/clone/images/avatar_ref.jpg")
DEFAULT_WHISPER_MODEL = Path("/Users/mac/models/ggml-medium-q5_0.bin")
DEFAULT_BL_BIN = Path("/Users/mac/.local/bin/bl")


def probe_duration_sec(audio_path: Path) -> float:
    out = subprocess.check_output(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(audio_path),
        ],
        text=True,
    ).strip()
    return float(out)


def prepare_mp3_and_wav16k(
    voice_input: Path,
    tmp_dir: Path,
    max_duration_sec: int,
    sample_rate_for_pcm: int = 44100,
) -> tuple[Path, Path, int]:
    mp3_path = tmp_dir / "voice_input.mp3"
    wav16_path = tmp_dir / "voice_input_16k.wav"

    input_args = []
    if voice_input.suffix.lower() == ".pcm":
        input_args = ["-f", "f32le", "-ar", str(sample_rate_for_pcm), "-ac", "1"]

    cmd_mp3 = [
        "ffmpeg",
        "-y",
        *input_args,
        "-i",
        str(voice_input),
        "-t",
        str(max_duration_sec),
        "-ac",
        "1",
        "-ar",
        "44100",
        "-b:a",
        "128k",
        str(mp3_path),
    ]
    subprocess.run(cmd_mp3, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    cmd_wav16 = [
        "ffmpeg",
        "-y",
        "-i",
        str(mp3_path),
        "-ac",
        "1",
        "-ar",
        "16000",
        "-c:a",
        "pcm_s16le",
        str(wav16_path),
    ]
    subprocess.run(cmd_wav16, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    actual_dur = probe_duration_sec(mp3_path)
    duration_int = max(2, min(max_duration_sec, int(math.ceil(actual_dur))))
    return mp3_path, wav16_path, duration_int


def transcribe_offline_whisper(wav16_path: Path, whisper_model: Path, tmp_dir: Path) -> str:
    whisper_bin = shutil.which("whisper-cli")
    if not whisper_bin or not whisper_model.exists():
        return ""
    out_prefix = tmp_dir / "whisper_out"
    subprocess.run(
        [
            whisper_bin,
            "-m",
            str(whisper_model),
            "-l",
            "en",
            "-f",
            str(wav16_path),
            "-otxt",
            "-of",
            str(out_prefix),
            "-np",
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    txt_file = Path(f"{out_prefix}.txt")
    if not txt_file.exists():
        return ""
    return " ".join(txt_file.read_text(encoding="utf-8").split())


def generate_talking_head(
    voice_input: Path,
    avatar_path: Path,
    output_mp4: Path,
    *,
    transcript: str | None = None,
    resolution: str = "720P",
    ratio: str = "9:16",
    max_duration: int = 10,
    style_prompt: str | None = None,
    watermark: bool = False,
    dry_run: bool = False,
    replace_audio_with_input: bool = True,
    bl_bin: Path = DEFAULT_BL_BIN,
    whisper_model: Path = DEFAULT_WHISPER_MODEL,
) -> dict:
    if not voice_input.exists():
        raise FileNotFoundError(f"Voice input not found: {voice_input}")
    if not avatar_path.exists():
        raise FileNotFoundError(f"Avatar image not found: {avatar_path}")
    if not bl_bin.exists():
        found = shutil.which("bl")
        if not found:
            raise RuntimeError("Bailian CLI `bl` not found on PATH or at /Users/mac/.local/bin/bl")
        bl_bin = Path(found)

    output_mp4.parent.mkdir(parents=True, exist_ok=True)
    raw_video_mp4 = output_mp4.with_name(f"{output_mp4.stem}_raw_wan.mp4")

    with tempfile.TemporaryDirectory(prefix="aircheck_head_") as tmp_str:
        tmp_dir = Path(tmp_str)
        mp3_path, wav16_path, duration_int = prepare_mp3_and_wav16k(
            voice_input, tmp_dir, max_duration_sec=max_duration
        )
        spoken_text = (transcript or "").strip()
        if not spoken_text:
            spoken_text = transcribe_offline_whisper(wav16_path, whisper_model, tmp_dir)

        mime_img = "image/png" if avatar_path.suffix.lower() == ".png" else "image/jpeg"
        img_b64 = base64.b64encode(avatar_path.read_bytes()).decode("ascii")
        mp3_b64 = base64.b64encode(mp3_path.read_bytes()).decode("ascii")

        scene = style_prompt or (
            "Image 1 speaks directly to the camera in a natural, conversational tone with crisp, "
            "accurate lip-sync matching Audio 1, natural blinking, subtle head movement, and "
            "clean studio lighting in a single unbroken static shot."
        )
        prompt = f'{scene} Spoken dialogue: "{spoken_text}"' if spoken_text else scene

        cmd = [
            str(bl_bin),
            "video",
            "ref",
            "--model",
            "wan3.0-video",
            "--image",
            f"data:{mime_img};base64,{img_b64}",
            "--image-voice",
            f"data:audio/mpeg;base64,{mp3_b64}",
            "--prompt",
            prompt,
            "--resolution",
            resolution,
            "--ratio",
            ratio,
            "--duration",
            str(duration_int),
            "--watermark",
            "true" if watermark else "false",
            "--output",
            "json",
        ]
        if dry_run:
            cmd.append("--dry-run")
        else:
            cmd.extend(["--download", str(raw_video_mp4)])

        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode != 0:
            raise RuntimeError(
                f"bl video ref failed (rc={proc.returncode}):\nSTDOUT: {proc.stdout}\nSTDERR: {proc.stderr}"
            )

        result = {
            "voice_input": str(voice_input),
            "avatar_path": str(avatar_path),
            "transcript": spoken_text,
            "duration_sec": duration_int,
            "resolution": resolution,
            "ratio": ratio,
            "dry_run": dry_run,
            "bl_stdout": proc.stdout.strip(),
        }

        if not dry_run:
            if not raw_video_mp4.exists() or raw_video_mp4.stat().st_size == 0:
                raise RuntimeError(f"Expected downloaded video at {raw_video_mp4}, but file is missing/empty.")
            shutil.copy2(raw_video_mp4, output_mp4)
            result["raw_wan_mp4"] = str(raw_video_mp4)
            result["output_mp4"] = str(output_mp4)
            result["output_bytes"] = output_mp4.stat().st_size

            # Also emit an exact-original-voice muxed variant so Ryan can compare wan3.0 native
            # audio vs his exact raw microphone track muxed onto the generated talking-head video.
            if replace_audio_with_input:
                orig_audio_mp4 = output_mp4.with_name(f"{output_mp4.stem}_exact_input_voice.mp4")
                subprocess.run(
                    [
                        "ffmpeg",
                        "-y",
                        "-i",
                        str(raw_video_mp4),
                        "-i",
                        str(mp3_path),
                        "-map",
                        "0:v:0",
                        "-map",
                        "1:a:0",
                        "-c:v",
                        "copy",
                        "-c:a",
                        "aac",
                        "-b:a",
                        "192k",
                        "-shortest",
                        str(orig_audio_mp4),
                    ],
                    check=True,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                result["exact_input_voice_mp4"] = str(orig_audio_mp4)

        return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate a talking-head MP4 from any voice audio + portrait.")
    parser.add_argument("--voice-wav", required=True, type=Path, help="Input voice audio (.wav/.mp3/.m4a/.pcm)")
    parser.add_argument("--avatar", type=Path, default=DEFAULT_AVATAR, help="Portrait image path")
    parser.add_argument("--output", required=True, type=Path, help="Output talking-head MP4 path")
    parser.add_argument("--transcript", default=None, help="Optional spoken transcript (auto-transcribed via whisper-cli if omitted)")
    parser.add_argument("--resolution", default="720P", choices=["480P", "720P", "1080P"])
    parser.add_argument("--ratio", default="9:16", choices=["9:16", "16:9", "1:1"])
    parser.add_argument("--max-duration", type=int, default=10, help="Max clip duration in seconds (default 10)")
    parser.add_argument("--style-prompt", default=None, help="Custom scene/camera prompt")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    res = generate_talking_head(
        voice_input=args.voice_wav,
        avatar_path=args.avatar,
        output_mp4=args.output,
        transcript=args.transcript,
        resolution=args.resolution,
        ratio=args.ratio,
        max_duration=args.max_duration,
        style_prompt=args.style_prompt,
        dry_run=args.dry_run,
    )
    print(json.dumps(res, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
