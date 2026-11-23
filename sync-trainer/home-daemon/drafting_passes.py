"""Tactile Mobile Plain-Text Drafting Engine passes for the Mac :8443 rig daemon.

Provides the 6 stateless HTTP pass handlers wired into ``ota_server.py``:
  1. ``POST /v1/draft/transcribe``  -> local ``whisper-cli`` WAV transcription
  2. ``POST /v1/draft/angles``      -> 2-3 extracted claims + 3 structural blindspots
  3. ``POST /v1/draft/synthesize``  -> 3-posture draft fanout (DIRECT/RECEIPT/CONTRARIAN) or 1-slot refresh
  4. ``POST /v1/draft/brush-swap``  -> 2 terse plain-spoken replacements + "" clean cut
  5. ``POST /v1/draft/seam-heal``   -> lock-preserving seam repair after cut/splice/reorder/mention
  6. ``POST /v1/draft/density``     -> lock-preserving compression/expansion (-25%..+15%)

Constitutional guarantees:
  - Zero fake, mocked, or placeholder production responses. Transcription always invokes real
    ``/opt/homebrew/bin/whisper-cli`` with the fastest existing ggml model under ``/Users/mac/models``.
    LLM passes always invoke real ``/Users/mac/.local/bin/bl text chat --enable-thinking false
    --enable-search false --output json``.
  - Strict style enforcement in every LLM system prompt and output sanitizer (no metaphors,
    no rhetorical questions, no em/en dashes, no throat-clearing openers, plain ASCII only).
  - Strict ``<lock>...</lock>`` token conservation on ``seam-heal`` and ``density``: every locked
    substring from input is verified character-for-character identical in output; any LLM output
    violating lock parity is deterministically rejected in favor of a lock-preserving fallback
    constructed directly from the caller's locked spans.
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
    """Extracts assistant text from ``bl text chat --output json`` stdout.

    Supports both Bailian CLI compact ``{"content": "..."}`` payloads and OpenAI-compatible
    ``{"choices": [{"message": {"content": "..."}}]}`` payloads, ignoring warning envelopes.
    """
    objs = _extract_json_objects(raw_stdout)
    if not objs:
        raise RuntimeError(f"bl text chat returned non-JSON output: {raw_stdout.strip()[:240]}")

    for obj in objs:
        if "error" in obj and isinstance(obj["error"], dict):
            msg = obj["error"].get("message") or json.dumps(obj["error"])
            raise RuntimeError(f"bl text chat error: {msg}")

    for obj in objs:
        raw_content = obj.get("content")
        if isinstance(raw_content, str) and raw_content.strip():
            return raw_content.strip()
        choices = obj.get("choices")
        if isinstance(choices, list) and choices:
            first_choice = choices[0]
            if isinstance(first_choice, dict):
                message = first_choice.get("message")
                if isinstance(message, dict):
                    msg_content = message.get("content")
                    if isinstance(msg_content, str) and msg_content.strip():
                        return msg_content.strip()
                    if isinstance(msg_content, list):
                        parts = [
                            str(part.get("text") or "")
                            for part in msg_content
                            if isinstance(part, dict)
                        ]
                        joined = "".join(parts).strip()
                        if joined:
                            return joined

    raise RuntimeError("bl text chat returned empty assistant content.")


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


class DraftPassEngine:
    """Stateless Mac rig executor for all 6 ``/v1/draft/*`` endpoints."""

    def __init__(
        self,
        *,
        whisper_bin: Path | str | None = None,
        whisper_models_dir: Path | str = DEFAULT_WHISPER_MODELS_DIR,
        whisper_model_path: Path | str | None = None,
        bl_bin: Path | str = DEFAULT_BL_BIN,
        llm_model: str | None = None,
        llm_timeout_sec: int = 45,
        max_lock_retries: int = 1,
        llm_caller: LlmCallerFn | None = None,
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
        self._llm_timeout_sec = llm_timeout_sec
        self._max_lock_retries = max(0, int(max_lock_retries))
        self._llm_caller = llm_caller
        self._transcribe_runner = transcribe_runner

    def _call_llm(self, system_prompt: str, user_prompt: str) -> str:
        if self._llm_caller is not None:
            return self._llm_caller(system_prompt, user_prompt)

        resolved_bl = self._bl_bin if self._bl_bin.is_file() else Path(shutil.which("bl") or "")
        if not resolved_bl.is_file():
            raise RuntimeError("Bailian CLI `bl` is not available on this Mac rig.")

        proc = subprocess.run(
            [
                str(resolved_bl),
                "text",
                "chat",
                "--model",
                self._llm_model,
                "--enable-thinking",
                "false",
                "--enable-search",
                "false",
                "--system",
                system_prompt,
                "--message",
                user_prompt,
                "--output",
                "json",
            ],
            capture_output=True,
            text=True,
            timeout=self._llm_timeout_sec,
            check=False,
        )
        if proc.returncode != 0:
            raise RuntimeError(_summarize_subprocess_error("bl text chat", proc))
        return parse_bl_chat_content(proc.stdout)

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

        last_err: Exception | None = None
        for attempt in range(2):
            prompt = user_prompt
            if attempt > 0:
                prompt += (
                    "\nCORRECTION: Previous response was incomplete. You MUST return 2 to 3 "
                    "extracted_angles AND exactly 3 blindspots with non-empty label and preview."
                )
            content = self._call_llm(system_prompt, prompt)
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
                }
            except RuntimeError as exc:
                last_err = exc
        raise last_err or RuntimeError("Angles pass failed to return valid angle lists.")

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

        last_err: Exception | None = None
        for attempt in range(2):
            prompt = user_prompt
            if attempt > 0:
                prompt += (
                    f"\nCORRECTION: Return exactly {len(expected_postures)} candidate(s) with non-empty "
                    "plain ASCII text <= 280 characters."
                )
            content = self._call_llm(system_prompt, prompt)
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

                return {"candidates": out_candidates}
            except RuntimeError as exc:
                last_err = exc
        raise last_err or RuntimeError("Synthesize pass failed to return valid candidates.")

    # 4. POST /v1/draft/brush-swap
    def brush_swap(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("Brush-swap request body must be a JSON object")
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
            "Return 2 distinct, terse, plain-spoken ASCII replacement phrases for ONLY the "
            "<replace>...</replace> span (not the full sentence). Option 1 must be the strongest "
            "terse plain-spoken replacement; Option 2 must be a distinct second alternative. "
            "Neither option should simply repeat the original target span.\n\n"
            'Return STRICT JSON ONLY: {"alternatives":["opt1","opt2"]}'
        )

        last_err: Exception | None = None
        for attempt in range(2):
            prompt = user_prompt
            if attempt > 0:
                prompt += (
                    "\nCORRECTION: Return exactly 2 distinct non-empty plain-ASCII replacement "
                    'strings in {"alternatives":["opt1","opt2"]}.'
                )
            content = self._call_llm(system_prompt, prompt)
            try:
                parsed = _extract_first_json_object(content, required_keys=("alternatives",))
                raw_alts = parsed.get("alternatives")
                if not isinstance(raw_alts, list):
                    raise RuntimeError("AI brush-swap response missing alternatives list")

                cleaned_alts: list[str] = []
                seen_lower: set[str] = set()
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
                    if len(cleaned_alts) >= 2:
                        break

                if len(cleaned_alts) < 2:
                    for same in deferred_same:
                        cleaned_alts.append(same)
                        if len(cleaned_alts) >= 2:
                            break

                if len(cleaned_alts) < 2:
                    raise RuntimeError(
                        f"AI brush-swap returned {len(cleaned_alts)} non-empty alternatives, expected 2"
                    )

                # Exact 3-element contract: [top terse replacement, second alternative, "" clean cut]
                return {"alternatives": [cleaned_alts[0], cleaned_alts[1], ""]}
            except RuntimeError as exc:
                last_err = exc
        raise last_err or RuntimeError("Brush-swap pass failed to return valid alternatives.")

    # 5. POST /v1/draft/seam-heal
    def seam_heal(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("Seam-heal request body must be a JSON object")
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

        healed = self._run_lock_conserving_text_pass(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            marked_text=marked_text,
            locked_spans=locked_spans,
            operation=operation,
        )
        return {
            "text": healed,
            "lock_parity_preserved": True,
        }

    # 6. POST /v1/draft/density
    def density(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("Density request body must be a JSON object")
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

        adjusted = self._run_lock_conserving_text_pass(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            marked_text=marked_text,
            locked_spans=locked_spans,
            operation=None,
        )
        return {
            "text": adjusted,
            "lock_parity_preserved": True,
        }

    def _run_lock_conserving_text_pass(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        marked_text: str,
        locked_spans: list[str],
        operation: str | None,
    ) -> str:
        attempts = 1 + (self._max_lock_retries if locked_spans else 0)
        prompt = user_prompt
        for attempt in range(attempts):
            content = self._call_llm(system_prompt, prompt)
            parsed = _extract_first_json_object(content, required_keys=("text",))
            raw_text = parsed.get("text")
            if not isinstance(raw_text, str) or not strip_lock_tags(raw_text).strip():
                if attempt + 1 < attempts:
                    continue
                if locked_spans:
                    LOGGER.warning(
                        "LLM returned empty text on locked pass; falling back to lock-preserving text"
                    )
                    return build_lock_preserving_fallback(marked_text, operation=operation)
                raise RuntimeError("AI pass returned empty text.")

            if verify_lock_parity(locked_spans, raw_text):
                sanitized = sanitize_preserving_locks(
                    raw_text,
                    locked_spans,
                    strip_throat_clearing=True,
                )
                if verify_lock_parity(locked_spans, sanitized) and sanitized.strip():
                    return sanitized

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

        # Deterministic rejection of lock-violating LLM output: return lock-preserving fallback.
        fallback = build_lock_preserving_fallback(marked_text, operation=operation)
        if not verify_lock_parity(locked_spans, fallback):
            raise RuntimeError("Lock parity invariant failed unexpectedly on fallback text")
        return fallback
