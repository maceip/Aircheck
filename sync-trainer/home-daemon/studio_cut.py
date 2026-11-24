"""Clip Studio v4 render options for the Core Pipeline Stage 4 cut.

Everything here produces a real effect in the composed MP4:

* ``strip_silence``  – ffmpeg ``silencedetect`` finds internal pauses in the talking-head audio and
  the matching video/audio spans are removed (clause / overlay windows are remapped).
* ``punch_in``       – every second talking-head clause is reframed with a 1.12x centre crop
  (classic jump-cut punch-in).
* ``captions``       – each clause's spoken text is burned in as a lower caption band during its
  window.
* ``aspect``         – ``"9:16"`` renders 720x1280, ``"16:9"`` renders 1280x720.
* ``look``           – Newsroom look: optional generated backdrop plate (presenter is framed in a
  window over the blurred plate), studio brightness in EV (linear RGB gain 2^EV), warm/cool
  colour temperature, film grain, and a 1dp presenter border.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Sequence

SILENCE_NOISE_DB = -35.0
SILENCE_MIN_GAP_SEC = 0.35
# Silence kept on each side of a removed pause so cuts do not clip word onsets/tails.
SILENCE_KEEP_PAD_MS = 90
PUNCH_IN_SCALE = 1.12
# wan3.0-video generates at most 30 s per clip (Bailian model card).
WAN_MAX_CLIP_SEC = 30
WARM_TEMPERATURE_K = 4800
COOL_TEMPERATURE_K = 7800
GRAIN_STRENGTH = 10
LOOK_LIGHT_VALUES = ("warm", "cool", "neutral")
ASPECT_SIZES: dict[str, tuple[int, int]] = {"9:16": (720, 1280), "16:9": (1280, 720)}

_SILENCE_START_RE = re.compile(r"silence_start:\s*(-?[0-9.]+)")
_SILENCE_END_RE = re.compile(r"silence_end:\s*(-?[0-9.]+)")
_PLATE_ID_RE = re.compile(r"^plate_[0-9a-f]{12}$")


@dataclass(frozen=True)
class StudioLook:
    plate_id: str | None = None
    blur_percent: int = 0
    brightness_ev: float = 0.0
    light: str = "neutral"
    grain: bool = False
    border: bool = False

    @staticmethod
    def from_payload(raw: Any) -> "StudioLook":
        if raw is None:
            return StudioLook()
        if not isinstance(raw, dict):
            raise ValueError("render_options.look must be an object")
        plate_id = raw.get("plate_id")
        if plate_id is not None:
            plate_id = str(plate_id).strip() or None
            if plate_id is not None and not _PLATE_ID_RE.match(plate_id):
                raise ValueError(f"Invalid look plate_id: {plate_id!r}")
        try:
            blur = int(raw.get("blur_percent") or 0)
            ev = float(raw.get("brightness_ev") or 0.0)
        except (TypeError, ValueError) as exc:
            raise ValueError("look.blur_percent must be int and look.brightness_ev a number") from exc
        if not 0 <= blur <= 100:
            raise ValueError("look.blur_percent must be within 0..100")
        if not -3.0 <= ev <= 3.0:
            raise ValueError("look.brightness_ev must be within -3..3")
        light = str(raw.get("light") or "neutral").strip().lower()
        if light not in LOOK_LIGHT_VALUES:
            raise ValueError(f"look.light must be one of {LOOK_LIGHT_VALUES}")
        return StudioLook(
            plate_id=plate_id,
            blur_percent=blur,
            brightness_ev=round(ev, 2),
            light=light,
            grain=bool(raw.get("grain", False)),
            border=bool(raw.get("border", False)),
        )


@dataclass(frozen=True)
class StudioRenderOptions:
    strip_silence: bool = False
    punch_in: bool = False
    captions: bool = False
    aspect: str = "9:16"
    look: StudioLook = field(default_factory=StudioLook)

    @staticmethod
    def from_payload(raw: Any) -> "StudioRenderOptions":
        if raw is None:
            return StudioRenderOptions()
        if not isinstance(raw, dict):
            raise ValueError("render_options must be an object")
        aspect = str(raw.get("aspect") or "9:16").strip()
        if aspect not in ASPECT_SIZES:
            raise ValueError(f"render_options.aspect must be one of {sorted(ASPECT_SIZES)}")
        return StudioRenderOptions(
            strip_silence=bool(raw.get("strip_silence", False)),
            punch_in=bool(raw.get("punch_in", False)),
            captions=bool(raw.get("captions", False)),
            aspect=aspect,
            look=StudioLook.from_payload(raw.get("look")),
        )


# ---------------------------------------------------------------------------
# Silence detection & time remapping
# ---------------------------------------------------------------------------


def parse_silencedetect_log(log_text: str, total_ms: int) -> list[tuple[int, int]]:
    """Parses ffmpeg ``silencedetect`` stderr into internal (start_ms, end_ms) gaps.

    Leading and trailing silence is ignored (it is not a gap between words). Each gap is shrunk
    by ``SILENCE_KEEP_PAD_MS`` on both sides; gaps that vanish after padding are dropped.
    """
    starts = [float(m.group(1)) for m in _SILENCE_START_RE.finditer(log_text)]
    ends = [float(m.group(1)) for m in _SILENCE_END_RE.finditer(log_text)]
    gaps: list[tuple[int, int]] = []
    for idx, start_s in enumerate(starts):
        end_s = ends[idx] if idx < len(ends) else total_ms / 1000.0
        start_ms = int(round(max(0.0, start_s) * 1000.0))
        end_ms = int(round(end_s * 1000.0))
        if start_ms <= 0 or end_ms >= total_ms:
            continue
        start_ms += SILENCE_KEEP_PAD_MS
        end_ms -= SILENCE_KEEP_PAD_MS
        if end_ms - start_ms >= 60:
            gaps.append((start_ms, end_ms))
    return gaps


def detect_silence_gaps(media_path: Path, total_ms: int) -> list[tuple[int, int]]:
    ffmpeg_bin = shutil.which("ffmpeg")
    if not ffmpeg_bin:
        raise RuntimeError("ffmpeg is not installed on this Mac rig.")
    proc = subprocess.run(
        [
            ffmpeg_bin,
            "-hide_banner",
            "-nostats",
            "-i",
            str(media_path),
            "-map",
            "0:a:0",
            "-af",
            f"silencedetect=noise={SILENCE_NOISE_DB}dB:d={SILENCE_MIN_GAP_SEC}",
            "-f",
            "null",
            "-",
        ],
        capture_output=True,
        text=True,
        check=False,
        timeout=180,
    )
    if proc.returncode != 0:
        tail = (proc.stderr or "").strip().splitlines()[-3:]
        raise RuntimeError("ffmpeg silencedetect failed: " + " | ".join(tail))
    return parse_silencedetect_log(proc.stderr or "", total_ms)


def keep_segments(total_ms: int, gaps: Sequence[tuple[int, int]]) -> list[tuple[int, int]]:
    out: list[tuple[int, int]] = []
    cursor = 0
    for start, end in sorted(gaps):
        if start > cursor:
            out.append((cursor, start))
        cursor = max(cursor, end)
    if cursor < total_ms:
        out.append((cursor, total_ms))
    return out


def remap_ms(ms: int, gaps: Sequence[tuple[int, int]]) -> int:
    """Maps a timestamp in the original media onto the silence-stripped timeline."""
    removed = 0
    for start, end in sorted(gaps):
        if ms >= end:
            removed += end - start
        elif ms > start:
            removed += ms - start
    return max(0, ms - removed)


def _strip_silence(src_mp4: Path, out_mp4: Path, segments: Sequence[tuple[int, int]]) -> None:
    expr = "+".join(f"between(t,{s / 1000.0:.3f},{e / 1000.0:.3f})" for s, e in segments)
    cmd = [
        "ffmpeg",
        "-y",
        "-i",
        str(src_mp4),
        "-filter_complex",
        f"[0:v]select='{expr}',setpts=N/FRAME_RATE/TB[v];[0:a]aselect='{expr}',asetpts=N/SR/TB[a]",
        "-map",
        "[v]",
        "-map",
        "[a]",
        "-c:v",
        "libx264",
        "-preset",
        "fast",
        "-crf",
        "18",
        "-pix_fmt",
        "yuv420p",
        "-c:a",
        "aac",
        "-b:a",
        "192k",
        str(out_mp4),
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if proc.returncode != 0 or not out_mp4.is_file() or out_mp4.stat().st_size == 0:
        tail = (proc.stderr or "").strip().splitlines()[-3:]
        raise RuntimeError("ffmpeg strip-silence failed: " + " | ".join(tail))


def pick_split_points(total_ms: int, gaps: Sequence[tuple[int, int]], max_chunk_ms: int) -> list[int]:
    """Cut points so every chunk is <= max_chunk_ms, preferring the middle of a pause."""
    points: list[int] = []
    start = 0
    while total_ms - start > max_chunk_ms:
        limit = start + max_chunk_ms
        mids = [(s + e) // 2 for s, e in gaps if start + 1000 < (s + e) // 2 <= limit]
        cut = max(mids) if mids else limit
        points.append(cut)
        start = cut
    return points


def _probe_ms(media: Path) -> int:
    proc = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", str(media)],
        capture_output=True, text=True, check=False, timeout=30,
    )
    try:
        return max(0, int(round(float(proc.stdout.strip()) * 1000.0)))
    except ValueError:
        return 0


def split_wav_at_pauses(wav: Path, work_dir: Path, *, max_chunk_sec: int) -> list[Path]:
    """Splits a voice WAV into <= max_chunk_sec pieces at natural pauses (real silencedetect)."""
    total_ms = _probe_ms(wav)
    gaps = detect_silence_gaps(wav, total_ms) if total_ms > 0 else []
    points = pick_split_points(total_ms, gaps, max_chunk_sec * 1000)
    bounds = [0, *points, total_ms]
    out: list[Path] = []
    for i in range(len(bounds) - 1):
        piece = work_dir / f"voice_chunk_{i}.wav"
        proc = subprocess.run(
            ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(wav),
             "-ss", f"{bounds[i] / 1000.0:.3f}", "-to", f"{bounds[i + 1] / 1000.0:.3f}", "-c:a", "pcm_s16le", str(piece)],
            capture_output=True, text=True, check=False,
        )
        if proc.returncode != 0 or not piece.is_file():
            raise RuntimeError(f"ffmpeg voice split failed: {(proc.stderr or '').strip()[-300:]}")
        out.append(piece)
    return out


def concat_mp4_segments(segments: Sequence[Path], out_mp4: Path) -> Path:
    """Joins talking-head segments into one MP4 (re-encoded so codecs/timebases always match)."""
    cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error"]
    for seg in segments:
        cmd.extend(["-i", str(seg)])
    n = len(segments)
    labels = "".join(f"[{i}:v][{i}:a]" for i in range(n))
    cmd.extend([
        "-filter_complex", f"{labels}concat=n={n}:v=1:a=1[v][a]",
        "-map", "[v]", "-map", "[a]",
        "-c:v", "libx264", "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", str(out_mp4),
    ])
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if proc.returncode != 0 or not out_mp4.is_file():
        raise RuntimeError(f"ffmpeg segment concat failed: {(proc.stderr or '').strip()[-300:]}")
    return out_mp4


# ---------------------------------------------------------------------------
# Backdrop plate generation (Bailian `bl image generate`)
# ---------------------------------------------------------------------------


def plate_path(plates_dir: Path, plate_id: str) -> Path | None:
    if not _PLATE_ID_RE.match(plate_id):
        return None
    candidate = (plates_dir / f"{plate_id}.png").resolve()
    if candidate.parent != plates_dir.resolve() or not candidate.is_file():
        return None
    return candidate


def generate_backdrop_plate(
    *,
    prompt: str,
    aspect: str,
    plates_dir: Path,
    bl_bin: Path,
    runner: Any = None,
) -> dict[str, Any]:
    """Generates a studio backdrop plate image from ``prompt`` and stores it as PNG."""
    clean_prompt = " ".join(str(prompt or "").split())
    if not clean_prompt:
        raise ValueError("prompt must be a non-empty string")
    if len(clean_prompt) > 600:
        raise ValueError("prompt must be at most 600 characters")
    if aspect not in ASPECT_SIZES:
        raise ValueError(f"aspect must be one of {sorted(ASPECT_SIZES)}")
    plates_dir.mkdir(parents=True, exist_ok=True)
    plate_id = f"plate_{uuid.uuid4().hex[:12]}"
    work = plates_dir / f".{plate_id}_work"
    work.mkdir(parents=True, exist_ok=True)
    try:
        full_prompt = (
            f"{clean_prompt}. Empty studio backdrop for a news presenter, no people, no text, "
            "photographic, shallow depth of field."
        )
        if runner is not None:
            runner(full_prompt, aspect, work)
        else:
            resolved = bl_bin if bl_bin.is_file() else Path(shutil.which("bl") or "")
            if not resolved.is_file():
                raise RuntimeError("Bailian CLI `bl` is not available on this Mac rig.")
            proc = subprocess.run(
                [
                    str(resolved),
                    "image",
                    "generate",
                    "--prompt",
                    full_prompt,
                    "--size",
                    aspect,
                    "--n",
                    "1",
                    "--watermark",
                    "false",
                    "--out-dir",
                    str(work),
                    "--out-prefix",
                    "plate",
                    "--output",
                    "json",
                ],
                capture_output=True,
                text=True,
                check=False,
                timeout=240,
            )
            if proc.returncode != 0:
                tail = ((proc.stderr or "") + (proc.stdout or "")).strip().splitlines()[-3:]
                raise RuntimeError("bl image generate failed: " + " | ".join(tail))
        produced = sorted(
            p for p in work.iterdir() if p.suffix.lower() in (".png", ".jpg", ".jpeg", ".webp")
        )
        if not produced:
            raise RuntimeError("bl image generate produced no image file.")
        from PIL import Image

        target = plates_dir / f"{plate_id}.png"
        with Image.open(produced[0]) as img:
            img.convert("RGB").save(target, format="PNG")
            width, height = img.size
        (plates_dir / f"{plate_id}.json").write_text(
            json.dumps({"plate_id": plate_id, "prompt": clean_prompt, "aspect": aspect}),
            encoding="utf-8",
        )
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return {
        "plate_id": plate_id,
        "url": f"/v1/plates/{plate_id}.png",
        "width": width,
        "height": height,
        "prompt": clean_prompt,
    }


# ---------------------------------------------------------------------------
# Composition
# ---------------------------------------------------------------------------


def _wrap(text: str, max_chars: int) -> list[str]:
    lines: list[str] = []
    cur = ""
    for word in text.split():
        cand = f"{cur} {word}".strip()
        if len(cand) <= max_chars:
            cur = cand
        else:
            if cur:
                lines.append(cur)
            cur = word
    if cur:
        lines.append(cur)
    return lines


def _fonts(size_scale: float) -> tuple[Any, Any, Any]:
    from PIL import ImageFont

    repo_root = Path(__file__).resolve().parents[2]
    font_path = repo_root / "app" / "src" / "main" / "res" / "font" / "space_grotesk.ttf"
    try:
        return (
            ImageFont.truetype(str(font_path), int(40 * size_scale)),
            ImageFont.truetype(str(font_path), int(28 * size_scale)),
            ImageFont.truetype(str(font_path), int(22 * size_scale)),
        )
    except Exception:
        d = ImageFont.load_default()
        return d, d, d


def _between(windows: Sequence[tuple[float, float]]) -> str:
    return "+".join(f"between(t,{s:.3f},{e:.3f})" for s, e in windows)


def compose_studio_cut(
    *,
    src_mp4: Path,
    out_mp4: Path,
    work_dir: Path,
    clauses: list[Any],
    overlays: list[Any],
    options: StudioRenderOptions,
    plates_dir: Path,
    probe_duration_ms: Any,
) -> dict[str, Any]:
    from PIL import Image, ImageDraw

    width, height = ASPECT_SIZES[options.aspect]
    scale = min(width, height) / 720.0
    f_title, f_body, f_tag = _fonts(scale)
    total_ms = max(1000, int(probe_duration_ms(src_mp4)))

    gaps: list[tuple[int, int]] = []
    source = src_mp4
    if options.strip_silence:
        gaps = detect_silence_gaps(src_mp4, total_ms)
        if gaps:
            stripped = work_dir / "silence_stripped.mp4"
            _strip_silence(src_mp4, stripped, keep_segments(total_ms, gaps))
            source = stripped
            total_ms = max(1000, int(probe_duration_ms(stripped)))

    def remapped_window(item: dict[str, Any]) -> tuple[int, int] | None:
        start = remap_ms(max(0, int(item.get("start_ms") or 0)), gaps)
        end = min(total_ms, remap_ms(int(item.get("end_ms") or total_ms), gaps))
        return (start, end) if end > start else None

    clause_dicts = [c for c in clauses if isinstance(c, dict)]
    timed_pngs: list[tuple[Path, float, float]] = []
    broll_count = 0
    caption_count = 0
    overlay_count = 0
    punch_windows: list[tuple[float, float]] = []
    talking_head_seen = 0

    for idx, item in enumerate(clause_dicts):
        window = remapped_window(item)
        if window is None:
            continue
        start_s, end_s = window[0] / 1000.0, window[1] / 1000.0
        mode = str(item.get("segment_mode") or "TALKING_HEAD").upper()
        if mode == "BROLL_CUTAWAY":
            caption = str(item.get("broll_caption") or item.get("spoken_text") or "").strip()[:88]
            png = work_dir / f"broll_plate_{idx}.png"
            img = Image.new("RGBA", (width, height), (10, 12, 18, 242))
            draw = ImageDraw.Draw(img)
            box = (int(width * 0.055), int(height * 0.17), int(width * 0.945), int(height * 0.6))
            draw.rectangle([box[:2], box[2:]], fill=(18, 22, 32, 255), outline=(235, 235, 239, 220), width=3)
            draw.text((box[0] + int(28 * scale), box[1] + int(36 * scale)), "B-ROLL", font=f_tag, fill=(142, 142, 150, 255))
            chars = max(12, int(26 * width / 720))
            for li, line in enumerate(_wrap(caption, chars)[:5]):
                draw.text(
                    (box[0] + int(28 * scale), box[1] + int(110 * scale) + li * int(58 * scale)),
                    line,
                    font=f_title,
                    fill=(244, 246, 250, 255),
                )
            img.save(png)
            timed_pngs.append((png, start_s, end_s))
            broll_count += 1
        else:
            if options.punch_in and talking_head_seen % 2 == 1:
                punch_windows.append((start_s, end_s))
            talking_head_seen += 1

        if options.captions:
            spoken = str(item.get("spoken_text") or "").strip()
            if spoken:
                png = work_dir / f"caption_{idx}.png"
                img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
                draw = ImageDraw.Draw(img)
                chars = max(16, int(34 * width / 720))
                lines = _wrap(spoken, chars)[-3:]
                line_h = int(40 * scale)
                band_h = line_h * len(lines) + int(28 * scale)
                top = height - band_h - int(height * 0.06)
                left = int(width * 0.04)
                draw.rectangle([(left, top), (width - left, top + band_h)], fill=(0, 0, 0, 230), outline=(255, 255, 255, 255), width=2)
                draw.rectangle([(left, top), (left + int(6 * scale), top + band_h)], fill=(215, 25, 33, 255))
                for li, line in enumerate(lines):
                    draw.text((left + int(24 * scale), top + int(14 * scale) + li * line_h), line, font=f_body, fill=(235, 235, 239, 255))
                img.save(png)
                timed_pngs.append((png, start_s, end_s))
                caption_count += 1

    for idx, ovl in enumerate(overlays):
        if not isinstance(ovl, dict):
            continue
        headline = str(ovl.get("headline") or "").strip()[:48]
        subline = str(ovl.get("subline") or "").strip()[:64]
        stat = str(ovl.get("callout_stat") or "").strip()[:32]
        dur_sec = max(1, min(30, int(ovl.get("duration_sec") or 4)))
        if not headline and not subline and not stat:
            continue
        start_s = min(max(0.0, idx * 1.5), max(0.0, (total_ms / 1000.0) - 0.5))
        end_s = min(total_ms / 1000.0, start_s + float(dur_sec))
        png = work_dir / f"saved_ovl_{idx}.png"
        img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)
        left = int(width * 0.045)
        top = int(height * 0.04)
        draw.rectangle(
            [(left, top), (int(width * 0.62), top + int(170 * scale))],
            fill=(17, 17, 19, 235),
            outline=(235, 235, 239, 255),
            width=2,
        )
        y = top + int(16 * scale)
        if stat:
            draw.text((left + int(20 * scale), y), stat.upper(), font=f_tag, fill=(142, 142, 150, 255))
            y += int(34 * scale)
        if headline:
            draw.text((left + int(20 * scale), y), headline, font=f_title, fill=(255, 255, 255, 255))
            y += int(52 * scale)
        if subline:
            draw.text((left + int(20 * scale), y), subline, font=f_body, fill=(200, 200, 208, 255))
        img.save(png)
        timed_pngs.append((png, start_s, max(start_s + 0.25, end_s)))
        overlay_count += 1

    look = options.look
    plate = plate_path(plates_dir, look.plate_id) if look.plate_id else None
    if look.plate_id and plate is None:
        raise ValueError(f"Backdrop plate '{look.plate_id}' not found on rig.")

    cmd = ["ffmpeg", "-y", "-i", str(source)]
    next_input = 1
    plate_input: int | None = None
    if plate is not None:
        cmd.extend(["-loop", "1", "-i", str(plate)])
        plate_input = next_input
        next_input += 1
    png_inputs: list[int] = []
    for png, _, _ in timed_pngs:
        cmd.extend(["-loop", "1", "-i", str(png)])
        png_inputs.append(next_input)
        next_input += 1

    parts: list[str] = []
    if plate_input is not None:
        inset = int(round(min(width, height) * 0.083))
        pw, ph = width - 2 * inset, height - 2 * inset
        pw -= pw % 2
        ph -= ph % 2
        blur_radius = int(round(look.blur_percent / 100.0 * 24))
        bg_chain = f"[{plate_input}:v]scale={width}:{height}:force_original_aspect_ratio=increase,crop={width}:{height}"
        if blur_radius > 0:
            bg_chain += f",boxblur={blur_radius}:1"
        parts.append(bg_chain + ",fps=30,format=yuv420p[bg]")
        frame_w, frame_h = pw, ph
    else:
        inset = 0
        frame_w, frame_h = width, height

    base_fit = f"scale={frame_w}:{frame_h}:force_original_aspect_ratio=increase,crop={frame_w}:{frame_h},fps=30"
    if punch_windows:
        zw = int(round(frame_w * PUNCH_IN_SCALE))
        zh = int(round(frame_h * PUNCH_IN_SCALE))
        zw += zw % 2
        zh += zh % 2
        parts.append("[0:v]split=2[p_norm][p_zoom]")
        parts.append(f"[p_norm]{base_fit}[p_n]")
        parts.append(
            f"[p_zoom]scale={zw}:{zh}:force_original_aspect_ratio=increase,crop={frame_w}:{frame_h},fps=30[p_z]"
        )
        parts.append(f"[p_n][p_z]overlay=0:0:enable='{_between(punch_windows)}'[pres0]")
    else:
        parts.append(f"[0:v]{base_fit}[pres0]")

    pres_label = "pres0"
    if look.border:
        border_px = max(2, int(round(2 * scale)))
        if plate_input is not None:
            parts.append(f"[{pres_label}]drawbox=x=0:y=0:w=iw:h=ih:color=white@0.95:t={border_px}[pres1]")
        else:
            edge = int(round(12 * scale))
            parts.append(
                f"[{pres_label}]drawbox=x={edge}:y={edge}:w=iw-{2 * edge}:h=ih-{2 * edge}:color=white@0.95:t={border_px}[pres1]"
            )
        pres_label = "pres1"

    if plate_input is not None:
        parts.append(f"[bg][{pres_label}]overlay={inset}:{inset}:shortest=1[v0]")
    else:
        parts.append(f"[{pres_label}]null[v0]")

    for i, ((_, s_sec, e_sec), in_idx) in enumerate(zip(timed_pngs, png_inputs)):
        parts.append(
            f"[v{i}][{in_idx}:v]overlay=0:0:shortest=1:enable='between(t,{s_sec:.3f},{e_sec:.3f})'[v{i + 1}]"
        )
    graded = f"v{len(timed_pngs)}"
    grade_filters: list[str] = []
    if abs(look.brightness_ev) > 1e-6:
        gain = round(2.0 ** look.brightness_ev, 4)
        grade_filters.append(f"colorchannelmixer=rr={gain}:gg={gain}:bb={gain}")
    if look.light == "warm":
        grade_filters.append(f"colortemperature=temperature={WARM_TEMPERATURE_K}")
    elif look.light == "cool":
        grade_filters.append(f"colortemperature=temperature={COOL_TEMPERATURE_K}")
    if look.grain:
        grade_filters.append(f"noise=alls={GRAIN_STRENGTH}:allf=t")
    grade_filters.append("format=yuv420p")
    parts.append(f"[{graded}]" + ",".join(grade_filters) + "[vout]")

    cmd.extend(
        [
            "-filter_complex",
            ";".join(parts),
            "-map",
            "[vout]",
            "-map",
            "0:a?",
            "-t",
            f"{total_ms / 1000.0:.3f}",
            "-c:v",
            "libx264",
            "-preset",
            "fast",
            "-crf",
            "20",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "192k",
            "-movflags",
            "+faststart",
            str(out_mp4),
        ]
    )
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if proc.returncode != 0 or not out_mp4.is_file() or out_mp4.stat().st_size == 0:
        tail = (proc.stderr or "").strip().splitlines()[-4:]
        raise RuntimeError("ffmpeg studio cut failed: " + " | ".join(tail))

    remapped_clauses = []
    for item in clause_dicts:
        window = remapped_window(item)
        remapped_clauses.append(
            {
                "id": str(item.get("id") or ""),
                "start_ms": window[0] if window else 0,
                "end_ms": window[1] if window else 0,
            }
        )
    return {
        "broll_cut_count": broll_count,
        "applied_overlay_count": overlay_count,
        "caption_count": caption_count,
        "punch_in_count": len(punch_windows),
        "removed_silence_gap_count": len(gaps),
        "removed_silence_ms": sum(e - s for s, e in gaps),
        "aspect": options.aspect,
        "clauses": remapped_clauses,
    }
