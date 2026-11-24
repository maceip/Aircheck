"""Tactile Mobile Plain-Text Drafting Engine passes for the Mac :8443 rig daemon.

Provides the 9 stateless HTTP pass handlers wired into ``ota_server.py``:
  1. ``POST /v1/draft/transcribe``         -> local ``whisper-cli`` WAV transcription
  2. ``POST /v1/draft/angles``             -> 2-3 extracted claims + 3 structural blindspots
  3. ``POST /v1/draft/arc``                -> 4-beat spoken arc (HOOK/DETAIL/SHIFT/PAYOFF) or single-beat reroll
  4. ``POST /v1/draft/synthesize``         -> 3-posture draft fanout (DIRECT/RECEIPT/CONTRARIAN) or 1-slot refresh
  5. ``POST /v1/draft/synthesize-script``  -> 3 full spoken script versions (DIRECT/PUNCHY/STORY) sized to target_sec
  6. ``POST /v1/draft/brush-swap``         -> terse plain-spoken replacements + "" clean cut
  7. ``POST /v1/draft/seam-heal``          -> lock-preserving seam repair after cut/splice/reorder/mention
  8. ``POST /v1/draft/density``            -> lock-preserving compression/expansion (-25%..+15%)
  9. ``POST /v1/draft/rewrite``            -> inline seed_clean / cut_polish rewrite with lock conservation

Constitutional guarantees:
  - Zero fake, mocked, or placeholder production responses. Transcription always invokes real
    ``/opt/homebrew/bin/whisper-cli`` with the fastest existing ggml model under ``/Users/mac/models``.
    All 8 LLM passes route through ``LlmRouter`` (supporting mixed local ``local_mlx`` / ``local_ollama`` /
    ``local_llama_cpp`` and remote ``remote_bailian`` / ``remote_openai`` / ``remote_gemini`` /
    ``remote_anthropic`` providers with explicit HTTP 502/503 errors on provider failure or empty output).
  - Strict style enforcement in every LLM system prompt and output sanitizer (no metaphors,
    no rhetorical questions, no em/en dashes, no throat-clearing openers, plain ASCII only).
  - Strict ``<lock>...</lock>`` token conservation on ``seam-heal``, ``density``, and ``rewrite``:
    every locked substring from input is verified character-for-character identical in output; any
    non-empty valid JSON LLM output violating lock parity is deterministically rejected in favor of
    a lock-preserving fallback constructed directly from the caller's locked spans.
"""

from __future__ import annotations

import base64
import binascii
import io
import json
import logging
import os
import re
import shutil
import subprocess
import tempfile
import unicodedata
import wave
from pathlib import Path
from typing import Any, Callable, Sequence

from llm_router import (
    ALL_PROVIDERS,
    LOCAL_PROVIDERS,
    STAGE_DEFAULT_MAX_TOKENS,
    LlmProviderError,
    LlmRouter,
    parse_bl_chat_content as _router_parse_bl_chat_content,
)

LOGGER = logging.getLogger("voicestudio.drafting_passes")

DEFAULT_WHISPER_BIN = Path("/opt/homebrew/bin/whisper-cli")
DEFAULT_WHISPER_MODELS_DIR = Path("/Users/mac/models")
DEFAULT_BL_BIN = Path("/Users/mac/.local/bin/bl")
DEFAULT_DRAFT_LLM_MODEL = "qwen3.8-flash"

# Ordered fastest-first among accurate English / multilingual ggml models.
PREFERRED_WHISPER_MODELS: tuple[str, ...] = (
    "ggml-base.en.bin",
    "ggml-small.en.bin",
    "ggml-base.bin",
    "ggml-small.bin",
    "ggml-medium-q5_0.bin",
    "ggml-tiny.en.bin",
    "ggml-tiny.bin",
    "ggml-medium.en.bin",
    "ggml-medium.bin",
)

POSTURE_LABELS: tuple[str, str, str] = ("DIRECT", "RECEIPT", "CONTRARIAN")
VALID_SEAM_OPERATIONS: frozenset[str] = frozenset(
    {"strike_cut", "voice_splice", "reorder", "weave_mention"}
)

MAX_DRAFT_AUDIO_BODY_BYTES = 32 * 1024 * 1024  # 32 MiB WAV base64
MAX_DRAFT_TEXT_BODY_BYTES = 1 * 1024 * 1024  # 1 MiB JSON text payload
MAX_CANDIDATE_CHARS = 280
MIN_DENSITY_DELTA = -25
MAX_DENSITY_DELTA = 15

STYLE_CONSTRAINTS_SYSTEM_BLOCK = (
    "STRICT STYLE CONSTRAINTS (MANDATORY ON EVERY OUTPUT):\n"
    "- Write direct, concrete, plain-spoken English prose.\n"
    "- BAN metaphors, idioms, analogies, and figurative language.\n"
    "- BAN rhetorical questions (never use '?' to ask the reader a rhetorical or leading question).\n"
    "- BAN em-dashes and en-dashes (never output '—' or '–').\n"
    "- BAN introductory throat-clearing such as \"Here's the thing:\", \"In today's world:\", "
    "\"Honestly,\", \"Look,\", \"Let's be real:\", \"The truth is:\", \"To be honest:\", "
    "\"At the end of the day,\".\n"
    "- Use plain ASCII punctuation only (straight apostrophe ', straight quote \", period ., "
    "comma ,, colon :, plain hyphen -).\n"
    "- Return STRICT JSON ONLY matching the exact requested schema. No markdown fences, no commentary."
)

_LOCK_SPAN_RE = re.compile(r"<lock>(.*?)</lock>", re.DOTALL)
_LOCK_TAG_ANY_RE = re.compile(r"</?lock>")
_REPLACE_SPAN_RE = re.compile(r"<replace>(.*?)</replace>", re.DOTALL)
_REPLACE_TAG_ANY_RE = re.compile(r"</?replace>")

_DIGIT_DASH_RE = re.compile(r"(?<=\d)\s*[\u2013\u2014\u2015]\s*(?=\d)")
_ANY_DASH_RE = re.compile(r"\s*[\u2013\u2014\u2015]\s*")
_WHISPER_NOISE_RE = re.compile(
    r"(?:\[\s*(?:blank_audio|silence|music|applause|laughter|inaudible|no\s+speech)\s*\]"
    r"|\(\s*(?:blank_audio|silence|music|applause|laughter|inaudible)\s*\))",
    re.IGNORECASE,
)
_THROAT_CLEARING_RE = re.compile(
    r"^(?:"
    r"here(?:'s|\s+is)\s+the\s+thing\s*[:,-]\s*"
    r"|in\s+today'?s\s+world\s*[,:-]\s*"
    r"|honestly\s*[,:-]\s*"
    r"|to\s+be\s+honest\s*[,:-]\s*"
    r"|let'?s\s+be\s+real\s*[:,-]\s*"
    r"|the\s+truth\s+is\s*[:,-]\s*"
    r"|at\s+the\s+end\s+of\s+the\s+day\s*[,:-]\s*"
    r"|look\s*,\s*"
    r"|listen\s*,\s*"
    r")+",
    re.IGNORECASE,
)
_DANGLING_REORDER_OPENER_RE = re.compile(
    r"^(?:consequently|however|furthermore|additionally|therefore|moreover)\s*,\s+",
    re.IGNORECASE,
)

_UNICODE_PUNCT_MAP: dict[str, str] = {
    "\u2018": "'",
    "\u2019": "'",
    "\u201a": "'",
    "\u201b": "'",
    "\u2032": "'",
    "\u02bc": "'",
    "\u201c": '"',
    "\u201d": '"',
    "\u201e": '"',
    "\u201f": '"',
    "\u2033": '"',
    "\u2026": "...",
    "\u00a0": " ",
    "\u2000": " ",
    "\u2001": " ",
    "\u2002": " ",
    "\u2003": " ",
    "\u2004": " ",
    "\u2005": " ",
    "\u2006": " ",
    "\u2007": " ",
    "\u2008": " ",
    "\u2009": " ",
    "\u200a": " ",
    "\u200b": "",
    "\u202f": " ",
    "\u205f": " ",
    "\u3000": " ",
    "\u2022": "-",
    "\u00b7": "-",
    "\u2212": "-",
}

BRUSH_NUDGE_DIRECTIVES: dict[str, str] = {
    "simpler": "Use shorter, plainer, more common words than the original span.",
    "sharper": "Make the phrasing more pointed, vivid, and assertive than the original span.",
    "less_formal": "Make the phrasing casual and conversational, like talking to a friend.",
    "more": "Offer three fresh options unlike any earlier ones.",
}

ARC_DEFAULT_TARGET_SEC = 60
SCRIPT_POSTURE_LABELS: tuple[str, ...] = ("DIRECT", "PUNCHY", "STORY")
# Conversational broadcast read rate used to size the word budget for a target runtime.
ARC_SPEAKING_WORDS_PER_SEC = 2.5
ARC_BEAT_LABELS: tuple[str, ...] = ("HOOK", "DETAIL", "SHIFT", "PAYOFF")
ARC_BEAT_PURPOSE: dict[str, str] = {
    "HOOK": "the surprising core tension in one line",
    "DETAIL": "the concrete evidence or mechanism behind it",
    "SHIFT": "what people are doing about it, or the turn",
    "PAYOFF": "the takeaway or demand that closes the video",
}
# Fraction of the runtime at which each beat ends (HOOK ends at 8s of a 60s video, etc.).
_ARC_BEAT_END_FRACTIONS: tuple[float, ...] = (8 / 60, 28 / 60, 48 / 60, 1.0)


def _arc_windows(target_sec: int) -> list[tuple[int, int]]:
    out: list[tuple[int, int]] = []
    start = 0
    for frac in _ARC_BEAT_END_FRACTIONS:
        end = target_sec if frac >= 1.0 else int(round(target_sec * frac))
        out.append((start, end))
        start = end
    return out


LlmCallerFn = Callable[[str, str], str]
TranscribeRunnerFn = Callable[[Path, Path, Path], str]


def _summarize_subprocess_error(label: str, proc: subprocess.CompletedProcess[str]) -> str:
    stderr = (proc.stderr or "").strip()
    stdout = (proc.stdout or "").strip()
    detail = stderr if stderr else stdout
    if len(detail) > 900:
        detail = "..." + detail[-900:]
    return (
        f"{label} failed (rc={proc.returncode}): {detail}"
        if detail
        else f"{label} failed (rc={proc.returncode})"
    )


def _extract_json_objects(raw_text: str) -> list[dict[str, Any]]:
    """Extracts all top-level JSON objects from CLI stdout or LLM content."""
    cleaned = raw_text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned)
        cleaned = re.sub(r"\s*```$", "", cleaned).strip()
    decoder = json.JSONDecoder()
    objs: list[dict[str, Any]] = []
    pos = 0
    while pos < len(cleaned):
        idx = cleaned.find("{", pos)
        if idx < 0:
            break
        try:
            obj, end = decoder.raw_decode(cleaned, idx)
            if isinstance(obj, dict):
                objs.append(obj)
            pos = end
        except json.JSONDecodeError:
            pos = idx + 1
    return objs


def parse_bl_chat_content(raw_stdout: str) -> str:
    """Extracts assistant text from ``bl text chat --output json`` stdout."""
    return _router_parse_bl_chat_content(raw_stdout)


def _extract_first_json_object(raw_text: str, *, required_keys: Sequence[str] = ()) -> dict[str, Any]:
    objs = _extract_json_objects(raw_text)
    if required_keys:
        for obj in objs:
            if all(k in obj for k in required_keys):
                return obj
    if objs:
        return objs[0]
    raise RuntimeError(f"AI model did not return valid JSON: {raw_text.strip()[:240]}")


def _sanitize_ascii_core(text: str) -> str:
    """Converts typographic Unicode punctuation to plain ASCII without stripping edge spaces."""
    out = text
    # Convert digit-to-digit en/em dashes into plain hyphens first (e.g. 10–20 -> 10-20).
    out = _DIGIT_DASH_RE.sub("-", out)
    # Convert remaining em/en dashes cleanly to comma + space (avoiding em-dash leakage).
    out = _ANY_DASH_RE.sub(", ", out)
    for src, dst in _UNICODE_PUNCT_MAP.items():
        if src in out:
            out = out.replace(src, dst)
    out = unicodedata.normalize("NFKD", out).encode("ascii", "ignore").decode("ascii")
    out = out.replace("\r\n", "\n").replace("\r", "\n")
    out = re.sub(r"[ \t]+", " ", out)
    out = re.sub(r",\s*,+", ",", out)
    out = re.sub(r"\s+([,.;:!?])", r"\1", out)
    return out


def sanitize_plain_ascii(
    text: str,
    *,
    strip_throat_clearing: bool = True,
    max_chars: int | None = None,
) -> str:
    """Enforces plain ASCII punctuation, clean spacing, and optional throat-clearing removal."""
    out = _sanitize_ascii_core(text)
    lines = [line.strip() for line in out.split("\n")]
    out = "\n".join(line for line in lines if line).strip()

    if out.startswith(", "):
        out = out[2:].lstrip()
    if out.endswith(","):
        out = out.rstrip(",").rstrip()

    if strip_throat_clearing and out:
        stripped = _THROAT_CLEARING_RE.sub("", out).lstrip()
        if stripped and stripped != out:
            if stripped[0].islower() and (len(stripped) == 1 or stripped[1].islower() or stripped[1] == " "):
                stripped = stripped[0].upper() + stripped[1:]
            out = stripped

    if max_chars is not None and len(out) > max_chars:
        out = _truncate_cleanly(out, max_chars)

    return out


def _truncate_cleanly(text: str, max_chars: int) -> str:
    if len(text) <= max_chars:
        return text
    window = text[:max_chars].rstrip()
    # Prefer ending on a sentence boundary within the trailing 35% of the budget.
    min_sentence_cut = int(max_chars * 0.65)
    best_sent = max(window.rfind(". "), window.rfind("! "), window.rfind("?"))
    if best_sent >= min_sentence_cut:
        return window[: best_sent + 1].strip()
    if window.endswith("."):
        return window
    last_space = window.rfind(" ")
    if last_space > 0:
        window = window[:last_space].rstrip(" ,;:-")
    else:
        window = window.rstrip(" ,;:-")
    return window


def _validate_balanced_lock_tags(marked_text: str) -> None:
    opens = len(re.findall(r"<lock>", marked_text))
    closes = len(re.findall(r"</lock>", marked_text))
    if opens != closes:
        raise ValueError("Unbalanced <lock>...</lock> tags in marked_text")
    # Also ensure lock tags are not nested or reversed.
    depth = 0
    for m in _LOCK_TAG_ANY_RE.finditer(marked_text):
        if m.group(0) == "<lock>":
            depth += 1
            if depth > 1:
                raise ValueError("Nested <lock> tags are not supported in marked_text")
        else:
            depth -= 1
            if depth < 0:
                raise ValueError("Unbalanced </lock> closing tag in marked_text")


def extract_locked_spans(marked_text: str) -> list[str]:
    """Extracts every non-empty ``<lock>...</lock>`` substring in exact appearance order."""
    return [m.group(1) for m in _LOCK_SPAN_RE.finditer(marked_text) if m.group(1) != ""]


def strip_lock_tags(text: str) -> str:
    return _LOCK_TAG_ANY_RE.sub("", text)


def verify_lock_parity(locked_spans: Sequence[str], candidate_text: str) -> bool:
    """Verifies every locked substring appears character-for-character identical in order."""
    plain = strip_lock_tags(candidate_text)
    cursor = 0
    for span in locked_spans:
        idx = plain.find(span, cursor)
        if idx < 0:
            return False
        cursor = idx + len(span)
    return True


def _split_around_locked_spans(
    plain_text: str, locked_spans: Sequence[str]
) -> list[tuple[str, bool]] | None:
    """Splits ``plain_text`` into ``(segment, is_locked)`` tuples matching ``locked_spans``."""
    parts: list[tuple[str, bool]] = []
    cursor = 0
    for span in locked_spans:
        idx = plain_text.find(span, cursor)
        if idx < 0:
            return None
        if idx > cursor:
            parts.append((plain_text[cursor:idx], False))
        parts.append((span, True))
        cursor = idx + len(span)
    if cursor < len(plain_text):
        parts.append((plain_text[cursor:], False))
    return parts


def _sanitize_unlocked_segment(
    seg: str,
    *,
    is_first: bool,
    is_last: bool,
    strip_throat_clearing: bool,
) -> str:
    if not seg:
        return ""
    leading_space = seg[0].isspace()
    trailing_space = seg[-1].isspace()
    cleaned = _sanitize_ascii_core(seg)
    had_trailing_space = trailing_space or (len(cleaned) > 0 and cleaned[-1].isspace())
    if is_first:
        cleaned = cleaned.lstrip()
        if cleaned.startswith(", "):
            cleaned = cleaned[2:].lstrip()
        if strip_throat_clearing and cleaned:
            stripped = _THROAT_CLEARING_RE.sub("", cleaned).lstrip()
            if stripped != cleaned:
                if stripped and stripped[0].islower() and (
                    len(stripped) == 1 or stripped[1].islower() or stripped[1] == " "
                ):
                    stripped = stripped[0].upper() + stripped[1:]
                cleaned = stripped
                if not cleaned:
                    had_trailing_space = False
    else:
        cleaned = cleaned.lstrip()
        if leading_space and cleaned and cleaned[0] not in ",.;:!?":
            cleaned = " " + cleaned
        elif leading_space and not cleaned:
            cleaned = " "

    if is_last:
        cleaned = cleaned.rstrip()
    else:
        cleaned = cleaned.rstrip()
        if had_trailing_space:
            cleaned = cleaned + " " if cleaned else " "

    return cleaned


def sanitize_preserving_locks(
    candidate_text: str,
    locked_spans: Sequence[str],
    *,
    strip_throat_clearing: bool = True,
) -> str:
    """Sanitizes unlocked text to plain ASCII while preserving every locked span byte-for-byte."""
    plain = strip_lock_tags(candidate_text)
    if not locked_spans:
        return sanitize_plain_ascii(plain, strip_throat_clearing=strip_throat_clearing)

    parts = _split_around_locked_spans(plain, locked_spans)
    if parts is None:
        raise ValueError("Cannot sanitize text that does not satisfy lock parity")

    rebuilt: list[str] = []
    last_idx = len(parts) - 1
    for i, (seg, is_locked) in enumerate(parts):
        if is_locked:
            rebuilt.append(seg)
        else:
            rebuilt.append(
                _sanitize_unlocked_segment(
                    seg,
                    is_first=(i == 0),
                    is_last=(i == last_idx),
                    strip_throat_clearing=strip_throat_clearing,
                )
            )
    result = "".join(rebuilt)
    # Collapse accidental double spaces only at boundaries outside locked spans.
    if not verify_lock_parity(locked_spans, result):
        return plain
    return result


def build_lock_preserving_fallback(marked_text: str, *, operation: str | None = None) -> str:
    """Deterministic lock-preserving fallback constructed directly from ``marked_text``.

    Never alters a single character inside any ``<lock>...</lock>`` substring; performs only
    conservative plain-ASCII and punctuation/seam cleanup strictly outside locked spans.
    """
    parts: list[tuple[str, bool]] = []
    cursor = 0
    for m in _LOCK_SPAN_RE.finditer(marked_text):
        if m.start() > cursor:
            parts.append((marked_text[cursor : m.start()], False))
        span = m.group(1)
        if span != "":
            parts.append((span, True))
        cursor = m.end()
    if cursor < len(marked_text):
        parts.append((marked_text[cursor:], False))

    cleaned_parts: list[tuple[str, bool]] = []
    last_idx = len(parts) - 1
    for i, (seg, is_locked) in enumerate(parts):
        if is_locked:
            cleaned_parts.append((seg, True))
            continue
        seg_work = seg
        if operation == "strike_cut":
            seg_work = re.sub(r",\s*(?:and|but|so)\s*([.!?])", r"\1", seg_work, flags=re.IGNORECASE)
            seg_work = re.sub(r",\s*,+", ",", seg_work)
            seg_work = re.sub(r"\s+([.!?])", r"\1", seg_work)
        elif operation == "reorder" and i == 0:
            lead_stripped = seg_work.lstrip()
            stripped = _DANGLING_REORDER_OPENER_RE.sub("", lead_stripped)
            if stripped != lead_stripped:
                if stripped and stripped[0].islower() and (
                    len(stripped) == 1 or stripped[1].islower() or stripped[1] == " "
                ):
                    stripped = stripped[0].upper() + stripped[1:]
                seg_work = stripped
        cleaned = _sanitize_unlocked_segment(
            seg_work,
            is_first=(i == 0),
            is_last=(i == last_idx),
            strip_throat_clearing=False,
        )
        cleaned_parts.append((cleaned, False))

    result = "".join(seg for seg, _ in cleaned_parts)
    # Final guard: if cleanup somehow broke empty/whitespace parity, fall back to bare tag strip.
    locked_spans = extract_locked_spans(marked_text)
    if not verify_lock_parity(locked_spans, result):
        return strip_lock_tags(marked_text).strip()
    return result


def resolve_whisper_bin(whisper_bin: Path | str | None = None) -> Path:
    if whisper_bin is not None:
        candidate = Path(whisper_bin)
        if candidate.is_file():
            return candidate
        raise RuntimeError(f"whisper-cli binary not found at {candidate}")
    if DEFAULT_WHISPER_BIN.is_file():
        return DEFAULT_WHISPER_BIN
    found = shutil.which("whisper-cli")
    if found:
        return Path(found)
    raise RuntimeError(
        f"whisper-cli binary not found (checked {DEFAULT_WHISPER_BIN} and PATH)."
    )


def resolve_whisper_model(
    models_dir: Path | str = DEFAULT_WHISPER_MODELS_DIR,
    explicit_model: Path | str | None = None,
) -> Path:
    """Selects the fastest existing local Whisper ggml model."""
    if explicit_model is not None:
        p = Path(explicit_model)
        if p.is_file() and p.stat().st_size > 0:
            return p
        raise FileNotFoundError(f"Configured Whisper model not found: {p}")

    env_model = os.environ.get("AIRCHECK_WHISPER_MODEL", "").strip()
    if env_model:
        p = Path(env_model)
        if p.is_file() and p.stat().st_size > 0:
            return p
        raise FileNotFoundError(f"AIRCHECK_WHISPER_MODEL not found: {p}")

    root = Path(models_dir)
    if root.is_dir():
        for name in PREFERRED_WHISPER_MODELS:
            cand = root / name
            if cand.is_file() and cand.stat().st_size > 0:
                return cand
        fallback_models = sorted(
            (p for p in root.glob("ggml*.bin") if p.is_file() and p.stat().st_size > 0),
            key=lambda p: p.stat().st_size,
        )
        if fallback_models:
            return fallback_models[0]

    raise FileNotFoundError(
        f"No Whisper ggml*.bin model found under {root} "
        f"(checked {', '.join(PREFERRED_WHISPER_MODELS[:5])})."
    )


def _decode_strict_base64(raw_value: Any, field_name: str) -> bytes:
    if not isinstance(raw_value, str):
        raise ValueError(f"{field_name} must be a base64 string")
    text = raw_value.strip()
    if text.startswith("data:") and "," in text:
        text = text.split(",", 1)[1]
    compact = "".join(text.split())
    if not compact:
        raise ValueError(f"{field_name} must not be empty")
    try:
        decoded = base64.b64decode(compact, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError(f"{field_name} contains invalid base64: {exc}") from exc
    if not decoded:
        raise ValueError(f"{field_name} decoded to 0 bytes")
    return decoded


def validate_and_measure_wav(wav_bytes: bytes) -> int:
    """Validates a WAV payload and returns its positive duration in milliseconds.

    Raises ``ValueError`` for empty, truncated, corrupt, or non-WAV payloads.
    """
    if len(wav_bytes) < 44:
        raise ValueError("audio_wav_base64 is too short to be a valid WAV file")
    if wav_bytes[:4] != b"RIFF" or wav_bytes[8:12] != b"WAVE":
        raise ValueError("audio_wav_base64 does not contain a valid RIFF/WAVE header")

    try:
        with wave.open(io.BytesIO(wav_bytes), "rb") as wf:
            nchannels = wf.getnchannels()
            framerate = wf.getframerate()
            sampwidth = wf.getsampwidth()
            nframes = wf.getnframes()
            first_frame = wf.readframes(1)
        if nchannels < 1 or framerate <= 0 or sampwidth < 1 or nframes <= 0 or not first_frame:
            raise ValueError("WAV payload contains zero audio frames or invalid format parameters")
        duration_ms = int(round((nframes / float(framerate)) * 1000.0))
        if duration_ms <= 0:
            duration_ms = 1
        return duration_ms
    except wave.Error:
        # Support valid IEEE-float / extensible WAV containers when ffprobe confirms audio.
        ffprobe_bin = shutil.which("ffprobe")
        if not ffprobe_bin:
            raise ValueError("Invalid or unsupported WAV audio payload")
        with tempfile.TemporaryDirectory(prefix="aircheck_wav_probe_") as tmpdir:
            probe_wav = Path(tmpdir) / "probe.wav"
            probe_wav.write_bytes(wav_bytes)
            proc = subprocess.run(
                [
                    ffprobe_bin,
                    "-v",
                    "error",
                    "-select_streams",
                    "a:0",
                    "-show_entries",
                    "format=duration",
                    "-of",
                    "default=noprint_wrappers=1:nokey=1",
                    str(probe_wav),
                ],
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )
            if proc.returncode != 0:
                raise ValueError("Invalid or corrupt WAV audio payload")
            raw = (proc.stdout or "").strip()
            try:
                sec = float(raw)
            except ValueError as exc:
                raise ValueError("WAV audio payload has unreadable duration") from exc
            if sec <= 0.0:
                raise ValueError("WAV audio payload has zero duration")
            return max(1, int(round(sec * 1000.0)))


def _default_transcribe_runner(whisper_bin: Path, model_path: Path, wav_path: Path) -> str:
    cmd = [
        str(whisper_bin),
        "-m",
        str(model_path),
        "-f",
        str(wav_path),
        "-l",
        "en",
        "-nt",
        "-np",
        "-sns",
    ]
    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(_summarize_subprocess_error("whisper-cli", proc))
    return proc.stdout or ""


def _parse_string_list(raw: Any, field_name: str) -> list[str]:
    if raw is None:
        return []
    if not isinstance(raw, list):
        raise ValueError(f"{field_name} must be a list of strings")
    out: list[str] = []
    for item in raw:
        if not isinstance(item, str):
            raise ValueError(f"Every item in {field_name} must be a string")
        cleaned = item.strip()
        if cleaned:
            out.append(cleaned)
    return out


def _format_steering_block(steering_constraints: Sequence[str]) -> str:
    if not steering_constraints:
        return ""
    bullet_lines = "\n".join(f"- {c}" for c in steering_constraints)
    return f"\nUSER STEERING CONSTRAINTS (MUST OBEY):\n{bullet_lines}\n"


def _extract_llm_overrides(payload: dict[str, Any]) -> tuple[str | None, str | None]:
    raw_provider = payload.get("llm_provider")
    if raw_provider is not None:
        if not isinstance(raw_provider, str) or raw_provider.strip() not in ALL_PROVIDERS:
            raise ValueError(f"Unsupported llm_provider: {raw_provider!r}")
        llm_provider: str | None = raw_provider.strip()
    else:
        llm_provider = None

    raw_model = payload.get("llm_model")
    if raw_model is not None:
        if not isinstance(raw_model, str) or not raw_model.strip():
            raise ValueError("llm_model must be a non-empty string")
        llm_model: str | None = raw_model.strip()
    else:
        llm_model = None

    return llm_provider, llm_model


class DraftPassEngine:
    """Stateless Mac rig executor for all ``/v1/draft/*`` endpoints."""

    def __init__(
        self,
        *,
        whisper_bin: Path | str | None = None,
        whisper_models_dir: Path | str = DEFAULT_WHISPER_MODELS_DIR,
        whisper_model_path: Path | str | None = None,
        bl_bin: Path | str = DEFAULT_BL_BIN,
        llm_model: str | None = None,
        llm_timeout_sec: float | None = None,
        max_lock_retries: int = 1,
        llm_caller: LlmCallerFn | None = None,
        llm_router: LlmRouter | None = None,
        transcribe_runner: TranscribeRunnerFn | None = None,
    ) -> None:
        self._whisper_bin = whisper_bin
        self._whisper_models_dir = Path(whisper_models_dir)
        self._whisper_model_path = whisper_model_path
        self._bl_bin = Path(bl_bin)
        self._llm_model = (
            llm_model
            or os.environ.get("AIRCHECK_DRAFT_LLM_MODEL", "").strip()
            or DEFAULT_DRAFT_LLM_MODEL
        )
        self._llm_timeout_sec: float | None = (
            float(llm_timeout_sec) if llm_timeout_sec is not None else None
        )
        self._max_lock_retries = max(0, int(max_lock_retries))
        self._llm_caller = llm_caller
        if llm_router is not None:
            self._llm_router: LlmRouter | None = llm_router
        elif llm_caller is None:
            router_kwargs: dict[str, Any] = {
                "bl_bin": self._bl_bin,
                "default_draft_model": self._llm_model,
            }
            if self._llm_timeout_sec is not None:
                router_kwargs["timeout_sec"] = self._llm_timeout_sec
            self._llm_router = LlmRouter(**router_kwargs)
        else:
            self._llm_router = None
        self._transcribe_runner = transcribe_runner

    def _call_llm(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        stage: str = "draft.angles",
        llm_provider: str | None = None,
        llm_model: str | None = None,
        max_tokens: int | None = None,
    ) -> tuple[str, dict[str, Any]]:
        if llm_provider is not None and llm_provider not in ALL_PROVIDERS:
            raise ValueError(f"Unsupported llm_provider: {llm_provider!r}")
        if llm_model is not None and (not isinstance(llm_model, str) or not llm_model.strip()):
            raise ValueError("llm_model must be a non-empty string")

        if self._llm_caller is not None:
            eff_provider = llm_provider or "custom_caller"
            eff_model = llm_model or self._llm_model
            is_local = (eff_provider in LOCAL_PROVIDERS) if llm_provider else True
            try:
                content = self._llm_caller(system_prompt, user_prompt)
            except LlmProviderError:
                raise
            except Exception as exc:
                raise LlmProviderError(
                    str(exc),
                    provider=eff_provider,
                    stage=stage,
                    model=eff_model,
                    status_code=502,
                ) from exc
            return str(content or ""), {
                "provider": eff_provider,
                "model": eff_model,
                "is_local": is_local,
            }

        assert self._llm_router is not None
        complete_kwargs: dict[str, Any] = {
            "llm_provider": llm_provider,
            "llm_model": llm_model,
            "max_tokens": max_tokens or STAGE_DEFAULT_MAX_TOKENS.get(stage, 1024),
        }
        if self._llm_timeout_sec is not None:
            complete_kwargs["timeout_sec"] = self._llm_timeout_sec
        res = self._llm_router.complete(
            stage,
            system_prompt,
            user_prompt,
            **complete_kwargs,
        )
        return res.text, res.llm_meta

    # 1. POST /v1/draft/transcribe
    def transcribe(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("Transcribe request body must be a JSON object")
        raw_audio_b64 = payload.get("audio_wav_base64")
        if raw_audio_b64 is None:
            raise ValueError("audio_wav_base64 is required")

        wav_bytes = _decode_strict_base64(raw_audio_b64, "audio_wav_base64")
        duration_ms = validate_and_measure_wav(wav_bytes)

        if self._transcribe_runner is not None:
            whisper_bin = Path(self._whisper_bin) if self._whisper_bin is not None else DEFAULT_WHISPER_BIN
            model_path = (
                Path(self._whisper_model_path)
                if self._whisper_model_path is not None
                else self._whisper_models_dir / "ggml-base.en.bin"
            )
            runner = self._transcribe_runner
        else:
            whisper_bin = resolve_whisper_bin(self._whisper_bin)
            model_path = resolve_whisper_model(
                models_dir=self._whisper_models_dir,
                explicit_model=self._whisper_model_path,
            )
            runner = _default_transcribe_runner

        with tempfile.TemporaryDirectory(prefix="aircheck_draft_asr_") as tmpdir:
            wav_path = Path(tmpdir) / "utterance.wav"
            wav_path.write_bytes(wav_bytes)
            raw_transcript = runner(whisper_bin, model_path, wav_path)

        cleaned = _WHISPER_NOISE_RE.sub(" ", raw_transcript)
        cleaned = re.sub(r"\s+", " ", cleaned).strip()
        cleaned = sanitize_plain_ascii(cleaned, strip_throat_clearing=False)
        return {
            "transcript": cleaned,
            "duration_ms": duration_ms,
        }

    # 2. POST /v1/draft/angles
    def angles(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("Angles request body must be a JSON object")
        llm_provider, llm_model = _extract_llm_overrides(payload)
        seed_raw = payload.get("seed_transcript")
        if not isinstance(seed_raw, str) or not seed_raw.strip():
            raise ValueError("seed_transcript must be a non-empty string")
        seed = seed_raw.strip()
        steering = _parse_string_list(payload.get("steering_constraints"), "steering_constraints")

        system_prompt = (
            "You dissect rough voice notes into concrete claims and structural blindspots "
            "for a plain-text drafting engine.\n"
            + STYLE_CONSTRAINTS_SYSTEM_BLOCK
        )
        user_prompt = (
            f"SEED TRANSCRIPT:\n{seed}\n"
            f"{_format_steering_block(steering)}\n"
            "Extract:\n"
            "1. extracted_angles: 2 to 3 concrete claims directly parsed from SEED TRANSCRIPT. "
            "Each item has id ('ex_1', 'ex_2', 'ex_3'), label (2 to 5 words), and preview "
            "(one crisp plain-ASCII concrete argument sentence).\n"
            "2. blindspots: exactly 3 structural blindspots ABSENT from SEED TRANSCRIPT "
            "(for example: concrete failure mode, latency/cost receipt, inverted premise). "
            "Each item has id ('bs_1', 'bs_2', 'bs_3'), label (2 to 5 words), and preview "
            "(one crisp plain-ASCII concrete argument this angle injects).\n\n"
            "Return STRICT JSON ONLY with this exact schema:\n"
            '{"extracted_angles":[{"id":"ex_1","label":"...","preview":"..."}],'
            '"blindspots":[{"id":"bs_1","label":"...","preview":"..."}]}'
        )

        stage = "draft.angles"
        last_err: Exception | None = None
        last_meta: dict[str, Any] = {"provider": llm_provider or "unknown", "model": llm_model or self._llm_model, "is_local": False}
        for attempt in range(2):
            prompt = user_prompt
            if attempt > 0:
                prompt += (
                    "\nCORRECTION: Previous response was incomplete. You MUST return 2 to 3 "
                    "extracted_angles AND exactly 3 blindspots with non-empty label and preview."
                )
            content, last_meta = self._call_llm(
                system_prompt,
                prompt,
                stage=stage,
                llm_provider=llm_provider,
                llm_model=llm_model,
                max_tokens=1024,
            )
            try:
                parsed = _extract_first_json_object(
                    content, required_keys=("extracted_angles", "blindspots")
                )
                extracted = self._normalize_angle_items(
                    parsed.get("extracted_angles"), prefix="ex", min_count=2, max_count=3
                )
                blindspots = self._normalize_angle_items(
                    parsed.get("blindspots"), prefix="bs", min_count=3, max_count=3
                )
                return {
                    "extracted_angles": extracted,
                    "blindspots": blindspots,
                    "llm_meta": last_meta,
                }
            except RuntimeError as exc:
                last_err = exc
        raise LlmProviderError(
            str(last_err or "Angles pass failed to return valid angle lists."),
            provider=last_meta["provider"],
            stage=stage,
            model=last_meta.get("model"),
            status_code=502,
        )

    # 2b. POST /v1/draft/arc
    def arc(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Proposes a 4-beat spoken arc (HOOK / DETAIL / SHIFT / PAYOFF) sized to ``target_sec``.

        With ``reroll_index`` + ``existing_beats`` only that one beat is rewritten; every other
        beat is returned verbatim.
        """
        if not isinstance(payload, dict):
            raise ValueError("Arc request body must be a JSON object")
        llm_provider, llm_model = _extract_llm_overrides(payload)
        seed_raw = payload.get("seed_transcript")
        if not isinstance(seed_raw, str) or not seed_raw.strip():
            raise ValueError("seed_transcript must be a non-empty string")
        seed = seed_raw.strip()
        steering = _parse_string_list(payload.get("steering_constraints"), "steering_constraints")
        angles = _parse_string_list(payload.get("selected_angles"), "selected_angles")
        try:
            target_sec = int(payload.get("target_sec") or ARC_DEFAULT_TARGET_SEC)
        except (TypeError, ValueError) as exc:
            raise ValueError("target_sec must be an integer") from exc
        if not 15 <= target_sec <= 180:
            raise ValueError("target_sec must be between 15 and 180")
        word_budget = int(round(target_sec * ARC_SPEAKING_WORDS_PER_SEC))
        windows = _arc_windows(target_sec)

        reroll_raw = payload.get("reroll_index")
        existing: list[str] = []
        reroll_index: int | None = None
        if reroll_raw is not None:
            try:
                reroll_index = int(reroll_raw)
            except (TypeError, ValueError) as exc:
                raise ValueError("reroll_index must be an integer") from exc
            raw_existing = payload.get("existing_beats")
            if not isinstance(raw_existing, list) or len(raw_existing) != len(ARC_BEAT_LABELS):
                raise ValueError(
                    f"existing_beats must list exactly {len(ARC_BEAT_LABELS)} beat texts when rerolling"
                )
            existing = [sanitize_plain_ascii(str(t or ""), strip_throat_clearing=False) for t in raw_existing]
            if not 0 <= reroll_index < len(ARC_BEAT_LABELS):
                raise ValueError("reroll_index out of range")

        system_prompt = (
            "You outline short spoken-video scripts as a fixed 4-beat arc for a solo creator.\n"
            + STYLE_CONSTRAINTS_SYSTEM_BLOCK
        )
        angle_block = ("SELECTED ANGLES TO COVER: " + " | ".join(angles) + "\n") if angles else ""
        beat_spec = "\n".join(
            f"- {label} ({lo}-{hi}s): {ARC_BEAT_PURPOSE[label]}"
            for label, (lo, hi) in zip(ARC_BEAT_LABELS, windows)
        )
        if reroll_index is None:
            user_prompt = (
                f"SEED VOICE NOTE:\n{seed}\n{angle_block}{_format_steering_block(steering)}\n"
                f"Target runtime {target_sec}s (about {word_budget} spoken words total).\n"
                f"Write ONE plain-ASCII sentence (6 to 14 words) for each beat:\n{beat_spec}\n\n"
                'Return STRICT JSON ONLY: {"beats":["hook","detail","shift","payoff"]}'
            )
        else:
            label = ARC_BEAT_LABELS[reroll_index]
            current = "\n".join(f"{ARC_BEAT_LABELS[i]}: {t}" for i, t in enumerate(existing))
            user_prompt = (
                f"SEED VOICE NOTE:\n{seed}\n{angle_block}{_format_steering_block(steering)}\n"
                f"CURRENT ARC:\n{current}\n"
                f"Rewrite ONLY the {label} beat ({ARC_BEAT_PURPOSE[label]}) as one new plain-ASCII "
                f"sentence (6 to 14 words) that differs clearly from: {existing[reroll_index]}\n\n"
                'Return STRICT JSON ONLY: {"beat":"..."}'
            )

        stage = "draft.arc"
        last_err: Exception | None = None
        last_meta: dict[str, Any] = {"provider": llm_provider or "unknown", "model": llm_model or self._llm_model, "is_local": False}
        for attempt in range(2):
            prompt = user_prompt
            if attempt > 0:
                prompt += "\nCORRECTION: Return the exact JSON schema with non-empty sentences."
            content, last_meta = self._call_llm(
                system_prompt,
                prompt,
                stage=stage,
                llm_provider=llm_provider,
                llm_model=llm_model,
                max_tokens=1024,
            )
            try:
                if reroll_index is None:
                    parsed = _extract_first_json_object(content, required_keys=("beats",))
                    raw_beats = parsed.get("beats")
                    if not isinstance(raw_beats, list):
                        raise RuntimeError("AI arc response missing beats list")
                    texts = [
                        sanitize_plain_ascii(str(b), strip_throat_clearing=True)
                        for b in raw_beats
                        if isinstance(b, str)
                    ]
                    texts = [t for t in texts if t]
                    if len(texts) < len(ARC_BEAT_LABELS):
                        raise RuntimeError(f"AI arc returned {len(texts)} beats, expected 4")
                    texts = texts[: len(ARC_BEAT_LABELS)]
                else:
                    parsed = _extract_first_json_object(content, required_keys=("beat",))
                    new_text = sanitize_plain_ascii(str(parsed.get("beat") or ""), strip_throat_clearing=True)
                    if not new_text:
                        raise RuntimeError("AI arc reroll returned an empty beat")
                    texts = list(existing)
                    texts[reroll_index] = new_text
                return {
                    "target_sec": target_sec,
                    "word_budget": word_budget,
                    "beats": [
                        {"label": label, "start_sec": lo, "end_sec": hi, "text": text}
                        for label, (lo, hi), text in zip(ARC_BEAT_LABELS, windows, texts)
                    ],
                    "llm_meta": last_meta,
                }
            except RuntimeError as exc:
                last_err = exc
        raise LlmProviderError(
            str(last_err or "Arc pass failed to return a valid 4-beat arc."),
            provider=last_meta["provider"],
            stage=stage,
            model=last_meta.get("model"),
            status_code=502,
        )

    def _normalize_angle_items(
        self, raw_items: Any, *, prefix: str, min_count: int, max_count: int
    ) -> list[dict[str, str]]:
        if not isinstance(raw_items, list):
            raise RuntimeError(f"AI angles response missing list for '{prefix}'")
        normalized: list[dict[str, str]] = []
        seen_labels: set[str] = set()
        for item in raw_items:
            if not isinstance(item, dict):
                continue
            label = sanitize_plain_ascii(str(item.get("label") or ""), strip_throat_clearing=True)
            preview = sanitize_plain_ascii(str(item.get("preview") or ""), strip_throat_clearing=True)
            if not label or not preview:
                continue
            label_key = label.lower()
            if label_key in seen_labels:
                continue
            seen_labels.add(label_key)
            idx = len(normalized) + 1
            raw_id = sanitize_plain_ascii(str(item.get("id") or ""), strip_throat_clearing=False)
            clean_id = raw_id if re.match(r"^[A-Za-z0-9_-]{1,32}$", raw_id) else f"{prefix}_{idx}"
            normalized.append({"id": clean_id, "label": label, "preview": preview})
            if len(normalized) >= max_count:
                break
        if len(normalized) < min_count:
            raise RuntimeError(
                f"AI angles response returned {len(normalized)} items for '{prefix}', expected at least {min_count}"
            )
        # Ensure unique IDs across the returned items.
        used_ids: set[str] = set()
        for idx, item in enumerate(normalized, start=1):
            if item["id"] in used_ids:
                item["id"] = f"{prefix}_{idx}"
            used_ids.add(item["id"])
        return normalized

    # 3. POST /v1/draft/synthesize
    def synthesize(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("Synthesize request body must be a JSON object")
        llm_provider, llm_model = _extract_llm_overrides(payload)
        seed_raw = payload.get("seed_transcript")
        if not isinstance(seed_raw, str) or not seed_raw.strip():
            raise ValueError("seed_transcript must be a non-empty string")
        seed = seed_raw.strip()
        selected_angles = _parse_string_list(payload.get("selected_angles"), "selected_angles")
        steering = _parse_string_list(payload.get("steering_constraints"), "steering_constraints")
        existing_drafts = _parse_string_list(payload.get("existing_drafts"), "existing_drafts")

        slot = payload.get("slot")
        if slot is not None:
            if isinstance(slot, bool) or not isinstance(slot, int) or slot not in (0, 1, 2):
                raise ValueError("slot must be null, 0, 1, or 2")

        system_prompt = (
            "You synthesize compact, high-signal plain-text posts (maximum 280 ASCII characters each) "
            "grounded strictly in the speaker's seed transcript and selected angles.\n"
            + STYLE_CONSTRAINTS_SYSTEM_BLOCK
        )

        angles_block = ""
        if selected_angles:
            angles_block = "\nSELECTED ANGLES TO INCLUDE:\n" + "\n".join(
                f"- {a}" for a in selected_angles
            ) + "\n"

        existing_block = ""
        if existing_drafts:
            existing_block = "\nEXISTING DRAFTS (DO NOT REPEAT OR PARAPHRASE CLOSELY):\n" + "\n".join(
                f"- {d}" for d in existing_drafts
            ) + "\n"

        if slot is None:
            user_prompt = (
                f"SEED TRANSCRIPT:\n{seed}\n"
                f"{angles_block}"
                f"{_format_steering_block(steering)}"
                f"{existing_block}\n"
                "Write exactly 3 distinct plain-ASCII drafts (each <= 280 characters):\n"
                "- Slot 0 posture_label='DIRECT': direct plain-spoken thesis stated plainly.\n"
                "- Slot 1 posture_label='RECEIPT': concrete mechanism, operational cost, latency, or verifiable proof.\n"
                "- Slot 2 posture_label='CONTRARIAN': challenge the default premise or name the concrete failure mode.\n"
                "Each candidate must make a distinct argument and avoid rhetorical questions, metaphors, and em-dashes.\n\n"
                "Return STRICT JSON ONLY:\n"
                '{"candidates":['
                '{"posture_label":"DIRECT","text":"..."},'
                '{"posture_label":"RECEIPT","text":"..."},'
                '{"posture_label":"CONTRARIAN","text":"..."}]}'
            )
            expected_postures = list(POSTURE_LABELS)
        else:
            target_posture = POSTURE_LABELS[slot]
            posture_guidance = {
                "DIRECT": "direct plain-spoken thesis stated plainly",
                "RECEIPT": "concrete mechanism, operational cost, latency, or verifiable proof",
                "CONTRARIAN": "challenge the default premise or name the concrete failure mode",
            }[target_posture]
            user_prompt = (
                f"SEED TRANSCRIPT:\n{seed}\n"
                f"{angles_block}"
                f"{_format_steering_block(steering)}"
                f"{existing_block}\n"
                f"Write exactly 1 fresh plain-ASCII draft (<= 280 characters) for posture_label='{target_posture}' "
                f"({posture_guidance}). It MUST take a distinct angle and phrasing different from all EXISTING DRAFTS.\n\n"
                "Return STRICT JSON ONLY:\n"
                f'{{"candidates":[{{"posture_label":"{target_posture}","text":"..."}}]}}'
            )
            expected_postures = [target_posture]

        stage = "draft.synthesize"
        last_err: Exception | None = None
        last_meta: dict[str, Any] = {"provider": llm_provider or "unknown", "model": llm_model or self._llm_model, "is_local": False}
        for attempt in range(2):
            prompt = user_prompt
            if attempt > 0:
                prompt += (
                    f"\nCORRECTION: Return exactly {len(expected_postures)} candidate(s) with non-empty "
                    "plain ASCII text <= 280 characters."
                )
            content, last_meta = self._call_llm(
                system_prompt,
                prompt,
                stage=stage,
                llm_provider=llm_provider,
                llm_model=llm_model,
                max_tokens=1024,
            )
            try:
                parsed = _extract_first_json_object(content, required_keys=("candidates",))
                raw_candidates = parsed.get("candidates")
                if not isinstance(raw_candidates, list):
                    raise RuntimeError("AI synthesize response missing candidates list")

                # Index by posture if provided, falling back to positional order.
                by_posture: dict[str, str] = {}
                positional: list[str] = []
                for entry in raw_candidates:
                    if not isinstance(entry, dict):
                        continue
                    txt = sanitize_plain_ascii(
                        str(entry.get("text") or ""),
                        strip_throat_clearing=True,
                        max_chars=MAX_CANDIDATE_CHARS,
                    )
                    if not txt:
                        continue
                    positional.append(txt)
                    label = str(entry.get("posture_label") or "").strip().upper()
                    if label in expected_postures and label not in by_posture:
                        by_posture[label] = txt

                out_candidates: list[dict[str, str]] = []
                used_texts: set[str] = set()
                pos_idx = 0
                for posture in expected_postures:
                    chosen = by_posture.get(posture)
                    if not chosen:
                        while pos_idx < len(positional) and positional[pos_idx] in used_texts:
                            pos_idx += 1
                        if pos_idx < len(positional):
                            chosen = positional[pos_idx]
                            pos_idx += 1
                    if not chosen:
                        raise RuntimeError(
                            f"AI synthesize response missing candidate text for posture {posture}"
                        )
                    used_texts.add(chosen)
                    out_candidates.append({"posture_label": posture, "text": chosen})

                if slot is not None and existing_drafts and attempt == 0:
                    existing_norm = {
                        sanitize_plain_ascii(d, strip_throat_clearing=False).lower()
                        for d in existing_drafts
                    }
                    if out_candidates[0]["text"].lower() in existing_norm:
                        raise RuntimeError(
                            "AI synthesize slot refresh repeated an existing draft verbatim"
                        )

                return {"candidates": out_candidates, "llm_meta": last_meta}
            except RuntimeError as exc:
                last_err = exc
        raise LlmProviderError(
            str(last_err or "Synthesize pass failed to return valid candidates."),
            provider=last_meta["provider"],
            stage=stage,
            model=last_meta.get("model"),
            status_code=502,
        )

    # 3b. POST /v1/draft/synthesize-script
    def synthesize_script(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Writes 3 spoken video-script versions (DIRECT / PUNCHY / STORY) sized to ``target_sec``.

        Each version is one paragraph per arc beat, joined by blank lines so the client keeps
        paragraph boundaries.
        """
        if not isinstance(payload, dict):
            raise ValueError("Synthesize-script request body must be a JSON object")
        llm_provider, llm_model = _extract_llm_overrides(payload)
        seed_raw = payload.get("seed_transcript")
        if not isinstance(seed_raw, str) or not seed_raw.strip():
            raise ValueError("seed_transcript must be a non-empty string")
        seed = seed_raw.strip()
        angles = _parse_string_list(payload.get("selected_angles"), "selected_angles")
        steering = _parse_string_list(payload.get("steering_constraints"), "steering_constraints")
        arc_beats = _parse_string_list(payload.get("arc_beats"), "arc_beats")
        try:
            target_sec = int(payload.get("target_sec") or ARC_DEFAULT_TARGET_SEC)
        except (TypeError, ValueError) as exc:
            raise ValueError("target_sec must be an integer") from exc
        if not 15 <= target_sec <= 180:
            raise ValueError("target_sec must be between 15 and 180")
        word_budget = int(round(target_sec * ARC_SPEAKING_WORDS_PER_SEC))
        beat_count = len(arc_beats) if arc_beats else len(ARC_BEAT_LABELS)

        system_prompt = (
            "You write tight spoken scripts for solo creators reading to camera.\n"
            + STYLE_CONSTRAINTS_SYSTEM_BLOCK
        )
        arc_block = ""
        if arc_beats:
            arc_block = "APPROVED ARC (one paragraph per beat, in order):\n" + "\n".join(
                f"{i + 1}. {b}" for i, b in enumerate(arc_beats)
            ) + "\n"
        angle_block = ("ANGLES TO COVER: " + " | ".join(angles) + "\n") if angles else ""
        user_prompt = (
            f"SEED VOICE NOTE:\n{seed}\n{arc_block}{angle_block}{_format_steering_block(steering)}\n"
            f"Write 3 distinct versions of a {target_sec}s script, each about {word_budget} spoken words, "
            f"each exactly {beat_count} paragraphs (one per beat):\n"
            "- posture_label='DIRECT': states the argument plainly.\n"
            "- posture_label='PUNCHY': short hard-hitting sentences.\n"
            "- posture_label='STORY': told through a concrete moment.\n\n"
            "Return STRICT JSON ONLY:\n"
            '{"versions":[{"posture_label":"DIRECT","paragraphs":["..."]},'
            '{"posture_label":"PUNCHY","paragraphs":["..."]},'
            '{"posture_label":"STORY","paragraphs":["..."]}]}'
        )
        stage = "draft.synthesize_script"
        expected = list(SCRIPT_POSTURE_LABELS)
        last_err: Exception | None = None
        last_meta: dict[str, Any] = {"provider": llm_provider or "unknown", "model": llm_model or self._llm_model, "is_local": False}
        for attempt in range(3):
            prompt = user_prompt
            if attempt > 0:
                prompt += (
                    f"\nCORRECTION: Return exactly 3 versions, each with exactly {beat_count} "
                    "non-empty paragraphs."
                )
            content, last_meta = self._call_llm(
                system_prompt,
                prompt,
                stage=stage,
                llm_provider=llm_provider,
                llm_model=llm_model,
                max_tokens=2048,
            )
            try:
                parsed = _extract_first_json_object(content, required_keys=("versions",))
                raw_versions = parsed.get("versions")
                if not isinstance(raw_versions, list):
                    raise RuntimeError("AI synthesize-script response missing versions list")
                by_label: dict[str, list[str]] = {}
                positional: list[list[str]] = []
                for entry in raw_versions:
                    if not isinstance(entry, dict):
                        continue
                    paras_raw = entry.get("paragraphs")
                    if not isinstance(paras_raw, list):
                        continue
                    paras = [
                        sanitize_plain_ascii(str(x), strip_throat_clearing=True)
                        for x in paras_raw
                        if isinstance(x, str)
                    ]
                    paras = [x for x in paras if x]
                    if len(paras) > beat_count:
                        # Fold overflow paragraphs into the final beat rather than discarding the version.
                        paras = paras[: beat_count - 1] + [" ".join(paras[beat_count - 1 :])]
                    if len(paras) != beat_count:
                        continue
                    positional.append(paras)
                    label = str(entry.get("posture_label") or "").strip().upper()
                    if label in expected and label not in by_label:
                        by_label[label] = paras
                out: list[dict[str, Any]] = []
                used: set[int] = set()
                for label in expected:
                    paras = by_label.get(label)
                    if paras is None:
                        idx = next((i for i in range(len(positional)) if i not in used and positional[i] not in by_label.values()), None)
                        if idx is None:
                            raise RuntimeError(f"AI synthesize-script missing version {label}")
                        used.add(idx)
                        paras = positional[idx]
                    text = "\n\n".join(paras)
                    out.append(
                        {
                            "posture_label": label,
                            "text": text,
                            "word_count": len(text.split()),
                        }
                    )
                return {
                    "target_sec": target_sec,
                    "word_budget": word_budget,
                    "candidates": out,
                    "llm_meta": last_meta,
                }
            except RuntimeError as exc:
                last_err = exc
        raise LlmProviderError(
            str(last_err or "Synthesize-script pass failed to return 3 versions."),
            provider=last_meta["provider"],
            stage=stage,
            model=last_meta.get("model"),
            status_code=502,
        )

    # 4. POST /v1/draft/brush-swap
    def brush_swap(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("Brush-swap request body must be a JSON object")
        llm_provider, llm_model = _extract_llm_overrides(payload)
        marked_raw = payload.get("marked_text")
        if not isinstance(marked_raw, str) or not marked_raw.strip():
            raise ValueError("marked_text must be a non-empty string")
        marked_text = marked_raw.strip()
        _validate_balanced_lock_tags(marked_text)

        matches = list(_REPLACE_SPAN_RE.finditer(marked_text))
        if len(matches) != 1:
            raise ValueError("marked_text must contain exactly one <replace>...</replace> span")
        target_span = matches[0].group(1).strip()
        if not target_span:
            raise ValueError("<replace>...</replace> span must not be empty")

        steering = _parse_string_list(payload.get("steering_constraints"), "steering_constraints")
        nudge_raw = payload.get("nudge")
        nudge = str(nudge_raw).strip().lower() if nudge_raw is not None else ""
        if nudge and nudge not in BRUSH_NUDGE_DIRECTIVES:
            raise ValueError(
                f"nudge must be one of {sorted(BRUSH_NUDGE_DIRECTIVES)} (got {nudge_raw!r})"
            )
        exclude = _parse_string_list(payload.get("exclude_alternatives"), "exclude_alternatives")
        want_count = 3 if nudge == "more" else 2
        nudge_block = ""
        if nudge:
            nudge_block = f"TONE NUDGE: {BRUSH_NUDGE_DIRECTIVES[nudge]}\n"
        if exclude:
            nudge_block += "DO NOT REPEAT ANY OF THESE EARLIER OPTIONS: " + " | ".join(exclude) + "\n"
        system_prompt = (
            "You propose compact drop-in word or phrase replacements for ONLY the substring wrapped "
            "in <replace>...</replace>, fitting cleanly into the surrounding text and respecting "
            "all <lock>...</lock> spans.\n"
            + STYLE_CONSTRAINTS_SYSTEM_BLOCK
        )
        user_prompt = (
            f"MARKED DRAFT:\n{marked_text}\n"
            f"TARGET SPAN TO REPLACE: {target_span}\n"
            f"{_format_steering_block(steering)}\n"
            f"{nudge_block}"
            f"Return {want_count} distinct, terse, plain-spoken ASCII replacement phrases for ONLY the "
            "<replace>...</replace> span (not the full sentence). Option 1 must be the strongest "
            "terse plain-spoken replacement; later options must be distinct alternatives. "
            "No option should simply repeat the original target span.\n\n"
            "Return STRICT JSON ONLY: "
            + ('{"alternatives":["opt1","opt2","opt3"]}' if want_count == 3 else '{"alternatives":["opt1","opt2"]}')
        )

        stage = "draft.brush_swap"
        last_err: Exception | None = None
        last_meta: dict[str, Any] = {"provider": llm_provider or "unknown", "model": llm_model or self._llm_model, "is_local": False}
        for attempt in range(2):
            prompt = user_prompt
            if attempt > 0:
                prompt += (
                    f"\nCORRECTION: Return exactly {want_count} distinct non-empty plain-ASCII "
                    'replacement strings in {"alternatives":[...]}.'
                )
            content, last_meta = self._call_llm(
                system_prompt,
                prompt,
                stage=stage,
                llm_provider=llm_provider,
                llm_model=llm_model,
                max_tokens=512,
            )
            try:
                parsed = _extract_first_json_object(content, required_keys=("alternatives",))
                raw_alts = parsed.get("alternatives")
                if not isinstance(raw_alts, list):
                    raise RuntimeError("AI brush-swap response missing alternatives list")

                cleaned_alts: list[str] = []
                seen_lower: set[str] = {e.lower() for e in exclude}
                target_lower = target_span.lower()
                deferred_same: list[str] = []

                for entry in raw_alts:
                    if not isinstance(entry, str):
                        continue
                    cand = _REPLACE_TAG_ANY_RE.sub("", strip_lock_tags(entry))
                    cand = sanitize_plain_ascii(cand, strip_throat_clearing=False)
                    if len(cand) >= 2 and (
                        (cand[0] == '"' and cand[-1] == '"') or (cand[0] == "'" and cand[-1] == "'")
                    ):
                        cand = cand[1:-1].strip()
                    if not cand:
                        continue
                    key = cand.lower()
                    if key in seen_lower:
                        continue
                    seen_lower.add(key)
                    if key == target_lower:
                        deferred_same.append(cand)
                    else:
                        cleaned_alts.append(cand)
                    if len(cleaned_alts) >= want_count:
                        break

                if len(cleaned_alts) < want_count:
                    for same in deferred_same:
                        cleaned_alts.append(same)
                        if len(cleaned_alts) >= want_count:
                            break

                if len(cleaned_alts) < want_count:
                    raise RuntimeError(
                        f"AI brush-swap returned {len(cleaned_alts)} non-empty alternatives, expected {want_count}"
                    )

                # Contract: [want_count replacements..., "" clean cut]
                result: dict[str, Any] = {
                    "alternatives": [*cleaned_alts[:want_count], ""],
                    "llm_meta": last_meta,
                }
                if nudge:
                    result["nudge"] = nudge
                return result
            except RuntimeError as exc:
                last_err = exc
        raise LlmProviderError(
            str(last_err or "Brush-swap pass failed to return valid alternatives."),
            provider=last_meta["provider"],
            stage=stage,
            model=last_meta.get("model"),
            status_code=502,
        )

    # 5. POST /v1/draft/seam-heal
    def seam_heal(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("Seam-heal request body must be a JSON object")
        llm_provider, llm_model = _extract_llm_overrides(payload)
        marked_raw = payload.get("marked_text")
        if not isinstance(marked_raw, str) or not marked_raw.strip():
            raise ValueError("marked_text must be a non-empty string")
        marked_text = marked_raw.strip()
        _validate_balanced_lock_tags(marked_text)
        if not strip_lock_tags(marked_text).strip():
            raise ValueError("marked_text contains no text outside <lock> tags")

        operation = str(payload.get("operation") or "").strip().lower()
        if operation not in VALID_SEAM_OPERATIONS:
            raise ValueError(
                f"operation must be one of {sorted(VALID_SEAM_OPERATIONS)}, got {operation!r}"
            )
        steering = _parse_string_list(payload.get("steering_constraints"), "steering_constraints")
        locked_spans = extract_locked_spans(marked_text)

        op_instructions = {
            "strike_cut": "A clause was just deleted. Repair only broken grammar, punctuation, or stray connectors at the cut seam.",
            "voice_splice": "Locked user speech was just spliced in. Smooth only the immediately adjacent unlocked words so the seam reads naturally.",
            "reorder": "Clauses were just reordered. Fix capitalization and remove dangling sentence-start connectors outside locked spans.",
            "weave_mention": "A @handle mention was just woven into the draft. Repair only the surrounding unlocked grammar and punctuation.",
        }[operation]

        system_prompt = (
            "You perform minimal surgical seam repair on plain-text drafts.\n"
            "ABSOLUTE LOCK CONSERVATION RULE: Every substring enclosed in <lock>...</lock> MUST "
            "appear character-for-character IDENTICAL in your output text (without the <lock> and "
            "</lock> tags). Do NOT change casing, spelling, punctuation, or word order inside any "
            "locked span. Only adjust unlocked words at the edit seam.\n"
            + STYLE_CONSTRAINTS_SYSTEM_BLOCK
        )
        user_prompt = (
            f"OPERATION: {operation}\n"
            f"TASK: {op_instructions}\n"
            f"MARKED TEXT:\n{marked_text}\n"
            f"{_format_steering_block(steering)}\n"
            "Output the seam-healed plain ASCII text with <lock> and </lock> tags removed and "
            "EVERY locked substring preserved verbatim.\n"
            'Return STRICT JSON ONLY: {"text":"..."}'
        )

        healed, llm_meta = self._run_lock_conserving_text_pass(
            stage="draft.seam_heal",
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            marked_text=marked_text,
            locked_spans=locked_spans,
            operation=operation,
            llm_provider=llm_provider,
            llm_model=llm_model,
            max_tokens=1024,
        )
        return {
            "text": healed,
            "lock_parity_preserved": True,
            "llm_meta": llm_meta,
        }

    # 6. POST /v1/draft/density
    def density(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("Density request body must be a JSON object")
        llm_provider, llm_model = _extract_llm_overrides(payload)
        marked_raw = payload.get("marked_text")
        if not isinstance(marked_raw, str) or not marked_raw.strip():
            raise ValueError("marked_text must be a non-empty string")
        marked_text = marked_raw.strip()
        _validate_balanced_lock_tags(marked_text)
        plain_input = strip_lock_tags(marked_text).strip()
        if not plain_input:
            raise ValueError("marked_text contains no text outside <lock> tags")

        raw_delta = payload.get("delta_percent")
        if raw_delta is None or isinstance(raw_delta, bool) or not isinstance(raw_delta, (int, float)):
            raise ValueError(
                f"delta_percent must be a number between {MIN_DENSITY_DELTA} and {MAX_DENSITY_DELTA}"
            )
        delta_percent = int(round(float(raw_delta)))
        if not (MIN_DENSITY_DELTA <= delta_percent <= MAX_DENSITY_DELTA):
            raise ValueError(
                f"delta_percent must be between {MIN_DENSITY_DELTA} and {MAX_DENSITY_DELTA}, got {raw_delta!r}"
            )

        steering = _parse_string_list(payload.get("steering_constraints"), "steering_constraints")
        locked_spans = extract_locked_spans(marked_text)

        orig_len = len(plain_input)
        target_len = max(1, int(round(orig_len * (1.0 + delta_percent / 100.0))))
        if delta_percent < 0:
            direction_note = (
                f"Compress ONLY the unlocked prose by roughly {abs(delta_percent)}% "
                f"(from ~{orig_len} chars toward ~{target_len} chars) by trimming filler words "
                "while retaining concrete facts."
            )
        elif delta_percent > 0:
            direction_note = (
                f"Relax/expand ONLY the unlocked prose by roughly +{delta_percent}% "
                f"(from ~{orig_len} chars toward ~{target_len} chars) by clarifying concrete details "
                "without adding fluff, metaphors, or throat-clearing."
            )
        else:
            direction_note = (
                f"Keep total length near {orig_len} chars while keeping crisp plain ASCII prose."
            )

        system_prompt = (
            "You adjust the information density of a plain-text draft by tightening or relaxing "
            "unlocked words only.\n"
            "ABSOLUTE LOCK CONSERVATION RULE: Every substring enclosed in <lock>...</lock> MUST "
            "appear character-for-character IDENTICAL in your output text (without the <lock> and "
            "</lock> tags). Never paraphrase, re-case, or alter any locked span.\n"
            + STYLE_CONSTRAINTS_SYSTEM_BLOCK
        )
        user_prompt = (
            f"DELTA PERCENT: {delta_percent:+d}%\n"
            f"TASK: {direction_note}\n"
            f"MARKED TEXT:\n{marked_text}\n"
            f"{_format_steering_block(steering)}\n"
            "Output the adjusted plain ASCII text with <lock> and </lock> tags removed and "
            "EVERY locked substring preserved verbatim.\n"
            'Return STRICT JSON ONLY: {"text":"..."}'
        )

        adjusted, llm_meta = self._run_lock_conserving_text_pass(
            stage="draft.density",
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            marked_text=marked_text,
            locked_spans=locked_spans,
            operation=None,
            llm_provider=llm_provider,
            llm_model=llm_model,
            max_tokens=1024,
        )
        return {
            "text": adjusted,
            "lock_parity_preserved": True,
            "llm_meta": llm_meta,
        }

    # ------------------------------------------------------------------
    # 7. POST /v1/draft/rewrite (inline ✨ AI rewrite / clean / expand)
    # ------------------------------------------------------------------

    def rewrite(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("Request body must be a JSON object")
        llm_provider, llm_model = _extract_llm_overrides(payload)

        raw_text = payload.get("text")
        if not isinstance(raw_text, str) or not strip_lock_tags(raw_text).strip():
            raise ValueError("text must be a non-empty string")
        _validate_balanced_lock_tags(raw_text)

        mode = str(payload.get("mode") or "seed_clean").strip().lower()
        if mode not in ("seed_clean", "cut_polish"):
            raise ValueError("mode must be 'seed_clean' or 'cut_polish'")

        steering = _parse_string_list(payload.get("steering_constraints"), "steering_constraints")
        locked_spans = extract_locked_spans(raw_text)

        if mode == "seed_clean":
            task_note = (
                "Clean up, tighten, and sharpen this raw voice/text seed into crisp, punchy, "
                "concrete plain-spoken prose (max 240 chars) ready to develop or synthesize."
            )
        else:
            task_note = (
                "Sharpen and tighten this short-form draft (max 275 chars) into crisp plain-spoken "
                "prose while conserving every <lock>...</lock> substring verbatim."
            )

        system_prompt = (
            "You rewrite rough mobile notes into clean, concrete, plain-spoken prose.\n"
            "ABSOLUTE LOCK CONSERVATION RULE: Every substring enclosed in <lock>...</lock> MUST "
            "appear character-for-character IDENTICAL in your output text (without the <lock> and "
            "</lock> tags). Never paraphrase, re-case, or alter any locked span.\n"
            + STYLE_CONSTRAINTS_SYSTEM_BLOCK
        )
        user_prompt = (
            f"TASK: {task_note}\n"
            f"INPUT:\n{raw_text}\n"
            f"{_format_steering_block(steering)}\n"
            'Return STRICT JSON ONLY: {"text":"..."}'
        )

        rewritten, llm_meta = self._run_lock_conserving_text_pass(
            stage="draft.rewrite",
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            marked_text=raw_text,
            locked_spans=locked_spans,
            operation=None,
            llm_provider=llm_provider,
            llm_model=llm_model,
            max_tokens=1024,
        )
        return {
            "text": rewritten,
            "lock_parity_preserved": True,
            "llm_meta": llm_meta,
        }

    def _run_lock_conserving_text_pass(
        self,
        *,
        stage: str,
        system_prompt: str,
        user_prompt: str,
        marked_text: str,
        locked_spans: list[str],
        operation: str | None,
        llm_provider: str | None = None,
        llm_model: str | None = None,
        max_tokens: int = 1024,
    ) -> tuple[str, dict[str, Any]]:
        attempts = 1 + (self._max_lock_retries if locked_spans else 0)
        prompt = user_prompt
        last_meta: dict[str, Any] = {
            "provider": llm_provider or "unknown",
            "model": llm_model or self._llm_model,
            "is_local": False,
        }
        for attempt in range(attempts):
            content, last_meta = self._call_llm(
                system_prompt,
                prompt,
                stage=stage,
                llm_provider=llm_provider,
                llm_model=llm_model,
                max_tokens=max_tokens,
            )
            try:
                parsed = _extract_first_json_object(content, required_keys=("text",))
            except RuntimeError as exc:
                raise LlmProviderError(
                    str(exc),
                    provider=last_meta["provider"],
                    stage=stage,
                    model=last_meta.get("model"),
                    status_code=502,
                ) from exc

            raw_text = parsed.get("text")
            if not isinstance(raw_text, str) or not strip_lock_tags(raw_text).strip():
                raise LlmProviderError(
                    "AI pass returned empty text.",
                    provider=last_meta["provider"],
                    stage=stage,
                    model=last_meta.get("model"),
                    status_code=502,
                )

            sanitized_plain = sanitize_plain_ascii(
                strip_lock_tags(raw_text),
                strip_throat_clearing=True,
            ).strip()
            if not sanitized_plain:
                if attempt + 1 < attempts:
                    prompt = (
                        f"{user_prompt}\n\n"
                        "EMPTY OUTPUT REJECTED. Return non-empty plain-ASCII prose in "
                        '{"text":"..."}.'
                    )
                    continue
                raise LlmProviderError(
                    "AI pass returned empty text after sanitization.",
                    provider=last_meta["provider"],
                    stage=stage,
                    model=last_meta.get("model"),
                    status_code=502,
                )

            if verify_lock_parity(locked_spans, raw_text):
                sanitized = sanitize_preserving_locks(
                    raw_text,
                    locked_spans,
                    strip_throat_clearing=True,
                )
                if not sanitized.strip():
                    if attempt + 1 < attempts:
                        prompt = (
                            f"{user_prompt}\n\n"
                            "EMPTY OUTPUT REJECTED. Return non-empty plain-ASCII prose in "
                            '{"text":"..."}.'
                        )
                        continue
                    raise LlmProviderError(
                        "AI pass returned empty text after sanitization.",
                        provider=last_meta["provider"],
                        stage=stage,
                        model=last_meta.get("model"),
                        status_code=502,
                    )
                if verify_lock_parity(locked_spans, sanitized):
                    return sanitized, last_meta

            missing = [span for span in locked_spans if span not in strip_lock_tags(raw_text)]
            LOGGER.warning(
                "LLM violated <lock> parity on attempt %d/%d (missing=%r)",
                attempt + 1,
                attempts,
                missing,
            )
            if attempt + 1 < attempts:
                required_list = "\n".join(f"- {span!r}" for span in locked_spans)
                prompt = (
                    f"{user_prompt}\n\n"
                    "LOCK PARITY VIOLATION REJECTED. Your previous output altered or dropped one "
                    "or more locked substrings. Every exact string below MUST appear verbatim and "
                    f"in order:\n{required_list}\n"
                    'Return STRICT JSON ONLY: {"text":"..."}'
                )

        # Deterministic rejection of lock-violating non-empty JSON LLM output: return lock-preserving fallback.
        fallback = build_lock_preserving_fallback(marked_text, operation=operation)
        if not verify_lock_parity(locked_spans, fallback):
            raise LlmProviderError(
                "Lock parity invariant failed unexpectedly on fallback text",
                provider=last_meta["provider"],
                stage=stage,
                model=last_meta.get("model"),
                status_code=502,
            )
        return fallback, last_meta
