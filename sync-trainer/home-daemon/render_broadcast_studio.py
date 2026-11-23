#!/usr/bin/env python3
"""Full Multi-Clause Broadcast Script Studio for Aircheck / VoiceStudio.

End-to-end production pipeline converting a multi-clause broadcast script into:
1. `< 14.0s` clauses normalized with OTA `lexicon.json` IPA overrides (mirroring
   `:core-engine`'s `ClauseNormalizer.kt`).
2. `N` candidate takes per clause via `s2_pro_infer.py` (supporting `--lora-dir`
   for `v001` / `v002` and `--dac-tflite` for `v001` / `v002` FP16 LiteRT DAC).
3. Real 4-Gate Verifier scoring (offline `whisper-cli` CER + `WeSpeaker ResNet34`
   speaker cosine + Microsoft `DNSMOS p808` + 80 ms tail RMS + English syllable
   cadence) and automatic winning-take selection per clause.
4. Click-free broadcast mastering & EDL splicing:
   - Exact `±88`-sample (`±2 ms @ 44.1 kHz`) zero-crossing boundary alignment
   - `660`-sample (`15 ms @ 44.1 kHz`) equal-power cosine crossfades
   - Continuous `-58 dBFS` `room_tone_44k.pcm` studio bed
   - `12 ms` `room_ir_44k.pcm` room impulse convolution + ITU-R BS.1770-4 `-16.0 LUFS`
   - Configurable `--global-gap-scale` (`1.0 .. 0.25`) over `3,969`-sample (`90 ms`) gaps
5. Android-compatible `VoiceProjectManifest.json` (`sampleRate = 44100`, schema-validated
   against `docs/swarm/schemas/clause_node.schema.json`) + 64-byte-aligned `.pcm` takes
   ready to drop directly into the Android Hairline Clipper.
6. Optional talking-head MP4 rendering via `generate_talking_head.py` (per-clause or
   stitched; disable with `--skip-video`).
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

import numpy as np
import scipy.signal
import soundfile as sf

_THIS_DIR = Path(__file__).resolve().parent
if str(_THIS_DIR) not in sys.path:
    sys.path.insert(0, str(_THIS_DIR))

import enroll_voice_assets as eva  # noqa: E402
import generate_talking_head as gth  # noqa: E402
import s2_pro_infer  # noqa: E402
import synthesize_and_render as sar  # noqa: E402

# Canonical 44.1 kHz Aircheck / VoiceStudio constants (matching ClauseNode.kt & dsp_mastering.h)
SAMPLE_RATE_HZ: int = 44_100
VERIFIER_SR_HZ: int = 16_000
ZERO_CROSS_SCAN_SAMPLES: int = 88          # +/- 2 ms @ 44.1 kHz
EDL_CROSSFADE_SAMPLES: int = 660           # 15 ms equal-power cosine @ 44.1 kHz
DEFAULT_BASE_GAP_SAMPLES: int = 3_969      # 90 ms @ 44.1 kHz
ACOUSTIC_PREFIX_TAIL_SAMPLES: int = 66_150 # 1.5 s @ 44.1 kHz
PCM_ALIGNMENT_BYTES: int = 64
MAX_CLAUSE_DURATION_SEC: float = 14.0
TARGET_MAX_CLAUSE_SEC: float = 13.0
CONSERVATIVE_SYLLABLES_PER_SEC: float = 4.2
MIN_GLOBAL_GAP_SCALE: float = 0.25
MAX_GLOBAL_GAP_SCALE: float = 1.0
DEFAULT_TARGET_LUFS: float = -16.0
DEFAULT_NOISE_FLOOR_DBFS: float = -58.0
DEFAULT_MAX_PHONEME_MS: float = 380.0

DEFAULT_BUNDLE_DIR = Path("/Users/mac/Downloads/voicestudio-models/bundles/v001")
DEFAULT_LEXICON_PATH = DEFAULT_BUNDLE_DIR / "lexicon.json"
DEFAULT_SCHEMA_PATH = _THIS_DIR.parents[1] / "docs" / "swarm" / "schemas" / "clause_node.schema.json"

_COMMA_PAUSE_SEC: float = 0.16
_SEMICOLON_PAUSE_SEC: float = 0.24
_SENTENCE_END_PAUSE_SEC: float = 0.32

_ABBREVIATIONS: frozenset[str] = frozenset(
    {
        "mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.", "st.", "vs.", "etc.",
        "e.g.", "i.e.", "u.s.", "u.k.", "inc.", "ltd.", "corp.", "no.", "fig.",
        "jan.", "feb.", "mar.", "apr.", "jun.", "jul.", "aug.", "sep.", "sept.",
        "oct.", "nov.", "dec.",
    }
)
_CONJUNCTIONS: frozenset[str] = frozenset(
    {
        "and", "but", "or", "so", "yet", "because", "while", "whereas",
        "although", "though", "however", "which", "that", "then",
    }
)
_PROSODY_PUNCTUATION: frozenset[str] = frozenset({",", ".", "!", "?", ";", ":", "—", "–"})
_TOKEN_RE = re.compile(r"\$[0-9,]+(?:\.[0-9]+)?|[+-]?[0-9,]+(?:\.[0-9]+)?%?(?:[A-Za-z]+)?|[A-Za-z]+(?:['’-][A-Za-z0-9]+)*|[.,!?;:—–]")
_STRONG_SPLIT_RE = re.compile(r"[;:—–]\s+")
_COMMA_SPLIT_RE = re.compile(r",\s+")

_CONTRACTIONS: dict[str, list[str]] = {
    "don't": ["do", "not"],
    "doesn't": ["does", "not"],
    "didn't": ["did", "not"],
    "won't": ["will", "not"],
    "can't": ["cannot"],
    "couldn't": ["could", "not"],
    "shouldn't": ["should", "not"],
    "wouldn't": ["would", "not"],
    "isn't": ["is", "not"],
    "aren't": ["are", "not"],
    "wasn't": ["was", "not"],
    "weren't": ["were", "not"],
    "i'm": ["i", "am"],
    "i've": ["i", "have"],
    "i'll": ["i", "will"],
    "i'd": ["i", "would"],
    "we're": ["we", "are"],
    "we've": ["we", "have"],
    "we'll": ["we", "will"],
    "you're": ["you", "are"],
    "you've": ["you", "have"],
    "you'll": ["you", "will"],
    "they're": ["they", "are"],
    "they've": ["they", "have"],
    "it's": ["it", "is"],
    "that's": ["that", "is"],
    "there's": ["there", "is"],
    "what's": ["what", "is"],
    "let's": ["let", "us"],
}

_UNIT_EXPANSIONS: dict[str, list[str]] = {
    "khz": ["kilohertz"],
    "hz": ["hertz"],
    "ms": ["milliseconds"],
    "mb": ["megabytes"],
    "gb": ["gigabytes"],
    "kb": ["kilobytes"],
    "db": ["decibels"],
    "dbfs": ["decibels", "full", "scale"],
    "lufs": ["luffs"],
    "fps": ["frames", "per", "second"],
}

_LETTER_NAMES: dict[str, str] = {
    "A": "ay", "B": "bee", "C": "see", "D": "dee", "E": "ee", "F": "ef", "G": "jee",
    "H": "aitch", "I": "eye", "J": "jay", "K": "kay", "L": "el", "M": "em", "N": "en",
    "O": "oh", "P": "pee", "Q": "cue", "R": "ar", "S": "ess", "T": "tee", "U": "you",
    "V": "vee", "W": "double-you", "X": "ex", "Y": "why", "Z": "zee",
}

_ONES = [
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
    "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
    "seventeen", "eighteen", "nineteen",
]
_TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"]


@dataclasses.dataclass(frozen=True)
class PlannedClause:
    clause_id: str
    index: int
    raw_text: str
    normalized_phonemes: str
    estimated_duration_sec: float
    acoustic_prefix_pcm_path: str | None
    base_gap_after_samples: int = DEFAULT_BASE_GAP_SAMPLES


@dataclasses.dataclass
class SynthesizedTakeData:
    pcm44k: np.ndarray
    discrete_audio_tokens: list[int]
    seed: int
    temperature: float
    generation_meta: dict[str, Any] = dataclasses.field(default_factory=dict)
    decode_meta: dict[str, Any] = dataclasses.field(default_factory=dict)


@dataclasses.dataclass(frozen=True)
class SpliceSlice:
    clause_id: str
    pcm44k: np.ndarray
    zero_cross_in: int
    zero_cross_out: int
    base_gap_after_samples: int = DEFAULT_BASE_GAP_SAMPLES


# ---------------------------------------------------------------------------
# Lexicon Loading, English Expansion, IPA Normalization & < 14s Clause Split
# ---------------------------------------------------------------------------


def load_lexicon(lexicon_path: Path | None) -> dict[str, str]:
    """Load OTA `lexicon.json` (flat dict or wrapped `entries`/`lexicon` dict)."""
    if lexicon_path is None:
        return {}
    p = Path(lexicon_path)
    if not p.is_file():
        return {}
    raw = p.read_text(encoding="utf-8").strip()
    if not raw:
        return {}
    root = json.loads(raw)
    if not isinstance(root, dict):
        raise ValueError(f"Expected JSON object in lexicon: {p}")
    if isinstance(root.get("entries"), dict):
        entries = root["entries"]
    elif isinstance(root.get("lexicon"), dict):
        entries = root["lexicon"]
    else:
        entries = root

    out: dict[str, str] = {}
    for k, v in entries.items():
        if isinstance(k, str) and isinstance(v, str) and k.strip() and v.strip():
            out[k.strip()] = v.strip()
    return out


def _int_to_words(n: int) -> list[str]:
    if n < 0:
        return ["minus"] + _int_to_words(-n)
    if n < 20:
        return [_ONES[n]]
    if n < 100:
        t, r = divmod(n, 10)
        return [_TENS[t]] if r == 0 else [_TENS[t], _ONES[r]]
    if n < 1000:
        h, r = divmod(n, 100)
        return [_ONES[h], "hundred"] + (_int_to_words(r) if r else [])
    if n < 1_000_000:
        th, r = divmod(n, 1000)
        return _int_to_words(th) + ["thousand"] + (_int_to_words(r) if r else [])
    if n < 1_000_000_000:
        m, r = divmod(n, 1_000_000)
        return _int_to_words(m) + ["million"] + (_int_to_words(r) if r else [])
    return [_ONES[int(ch)] for ch in str(n) if ch.isdigit()]


def _expand_number_or_decimal(token: str) -> list[str] | None:
    cleaned = token.replace(",", "")
    m = re.fullmatch(r"([+-])?(\d+)(?:\.(\d+))?", cleaned)
    if not m:
        return None
    sign, int_part, frac_part = m.group(1), m.group(2), m.group(3)
    words: list[str] = []
    if sign == "-":
        words.append("minus")
    words.extend(_int_to_words(int(int_part)))
    if frac_part is not None:
        words.append("point")
        words.extend(_ONES[int(d)] for d in frac_part)
    return words


def expand_english_token(raw_token: str) -> list[str]:
    """Deterministically expand numbers, units, contractions, currency, and acronyms."""
    lower = raw_token.lower().replace("’", "'")
    if lower in _CONTRACTIONS:
        return list(_CONTRACTIONS[lower])
    if lower in _UNIT_EXPANSIONS:
        return list(_UNIT_EXPANSIONS[lower])
    if lower.rstrip(".") in _UNIT_EXPANSIONS:
        return list(_UNIT_EXPANSIONS[lower.rstrip(".")])

    if raw_token.startswith("$") and len(raw_token) > 1:
        num = _expand_number_or_decimal(raw_token[1:])
        if num is not None:
            return num + ["dollars"]

    if raw_token.endswith("%") and len(raw_token) > 1:
        num = _expand_number_or_decimal(raw_token[:-1])
        if num is not None:
            return num + ["percent"]

    plain_num = _expand_number_or_decimal(raw_token)
    if plain_num is not None:
        return plain_num

    m_unit = re.fullmatch(r"([+-]?\d+(?:\.\d+)?)([A-Za-z]+)", raw_token)
    if m_unit:
        num = _expand_number_or_decimal(m_unit.group(1))
        unit = _UNIT_EXPANSIONS.get(m_unit.group(2).lower())
        if num is not None and unit is not None:
            return num + list(unit)

    if "-" in raw_token and len(raw_token) > 1:
        parts = [p for p in raw_token.split("-") if p]
        if len(parts) > 1:
            out: list[str] = []
            for p in parts:
                out.extend(expand_english_token(p))
            return out

    if 2 <= len(raw_token) <= 5 and raw_token.isalpha() and raw_token.isupper():
        return [_LETTER_NAMES.get(ch, ch.lower()) for ch in raw_token]

    return [raw_token]


def _grapheme_to_ipa(word: str) -> str:
    """Deterministic lightweight English G2P fallback matching `ClauseNormalizer.kt`."""
    w = re.sub(r"[^a-z]", "", word.lower())
    if not w:
        return ""
    digraphs = [
        ("tion", "ʃən"),
        ("sion", "ʒən"),
        ("tʃ", "tʃ"),
        ("ch", "tʃ"),
        ("sh", "ʃ"),
        ("th", "θ"),
        ("ng", "ŋ"),
        ("ph", "f"),
        ("qu", "kw"),
        ("ee", "iː"),
        ("ea", "iː"),
        ("oo", "uː"),
        ("ou", "aʊ"),
        ("ai", "eɪ"),
        ("ay", "eɪ"),
        ("oi", "ɔɪ"),
        ("oy", "ɔɪ"),
        ("er", "ər"),
        ("ar", "ɑːr"),
        ("or", "ɔːr"),
    ]
    singles = {
        "a": "æ", "b": "b", "c": "k", "d": "d", "e": "ɛ", "f": "f", "g": "ɡ",
        "h": "h", "i": "ɪ", "j": "dʒ", "k": "k", "l": "l", "m": "m", "n": "n",
        "o": "ɑ", "p": "p", "q": "k", "r": "r", "s": "s", "t": "t", "u": "ʌ",
        "v": "v", "w": "w", "x": "ks", "y": "j", "z": "z",
    }
    out: list[str] = []
    i = 0
    while i < len(w):
        matched = False
        for g, ipa in digraphs:
            if w.startswith(g, i):
                out.append(ipa)
                i += len(g)
                matched = True
                break
        if not matched:
            out.append(singles.get(w[i], w[i]))
            i += 1
    return "".join(out)


def _is_word_like(tok: str) -> bool:
    return any(ch.isalpha() for ch in tok)


def normalize_clause_to_ipa(
    clause_text: str,
    lexicon: Mapping[str, str] | None = None,
    phonetic_overrides: Mapping[str, str] | None = None,
) -> str:
    """Convert a clause into deterministic IPA phonemes honoring overrides + `lexicon.json`."""
    exact_lookup: dict[str, str] = {}
    lower_lookup: dict[str, str] = {}
    for src in (lexicon or {}, phonetic_overrides or {}):
        for k, v in src.items():
            ks, vs = str(k).strip(), str(v).strip()
            if ks and vs:
                exact_lookup[ks] = vs
                lower_lookup[ks.lower()] = vs

    tokens = _TOKEN_RE.findall(clause_text)
    out: list[str] = []
    i = 0
    while i < len(tokens):
        tok = tokens[i]
        # 2-word phrase override match (e.g. "Aircheck Studio")
        if i + 1 < len(tokens) and _is_word_like(tok) and _is_word_like(tokens[i + 1]):
            phrase = f"{tok} {tokens[i + 1]}"
            phrase_ipa = exact_lookup.get(phrase) or lower_lookup.get(phrase.lower())
            if phrase_ipa:
                out.append(phrase_ipa)
                i += 2
                continue

        if len(tok) == 1 and tok in _PROSODY_PUNCTUATION:
            out.append(tok)
            i += 1
            continue

        direct = exact_lookup.get(tok) or lower_lookup.get(tok.lower())
        if direct:
            out.append(direct)
            i += 1
            continue

        for expanded in expand_english_token(tok):
            mapped = exact_lookup.get(expanded) or lower_lookup.get(expanded.lower())
            if mapped:
                out.append(mapped)
            else:
                ipa = _grapheme_to_ipa(expanded)
                if ipa:
                    out.append(ipa)
        i += 1

    return re.sub(r"\s+", " ", " ".join(out)).strip()


def estimate_clause_duration_sec(
    text: str,
    *,
    conservative_syl_per_sec: float = CONSERVATIVE_SYLLABLES_PER_SEC,
) -> float:
    """Conservative duration estimate (seconds) at Gate-4 lower cadence bound (4.2 syl/s)."""
    if not text.strip():
        return 0.0
    tokens = _TOKEN_RE.findall(text)
    syllables = 0
    pause_sec = 0.0
    for tok in tokens:
        if tok == ",":
            pause_sec += _COMMA_PAUSE_SEC
        elif tok in (";", ":", "—", "–"):
            pause_sec += _SEMICOLON_PAUSE_SEC
        elif tok in (".", "!", "?"):
            pause_sec += _SENTENCE_END_PAUSE_SEC
        elif _is_word_like(tok) or any(c.isdigit() for c in tok):
            for w in expand_english_token(tok):
                syllables += sar.count_word_syllables(w)
    return (float(syllables) / float(conservative_syl_per_sec)) + pause_sec


def _ends_with_abbreviation(prefix: str) -> bool:
    last_tok = prefix.rstrip().rsplit(" ", 1)[-1].lower()
    if last_tok in _ABBREVIATIONS:
        return True
    if len(last_tok) == 2 and last_tok[0].isalpha() and last_tok[1] == ".":
        return True
    if re.fullmatch(r"([a-z]\.){2,}", last_tok):
        return True
    return False


def _split_into_sentences(text: str) -> list[str]:
    result: list[str] = []
    for paragraph in re.split(r"\n+", text):
        trimmed = re.sub(r"\s+", " ", paragraph).strip()
        if not trimmed:
            continue
        cur: list[str] = []
        i = 0
        n = len(trimmed)
        while i < n:
            ch = trimmed[i]
            cur.append(ch)
            if ch in ".!?…":
                while i + 1 < n and trimmed[i + 1] in ".!?…":
                    i += 1
                    cur.append(trimmed[i])
                next_is_boundary = (i + 1 >= n) or trimmed[i + 1].isspace()
                is_decimal = (
                    ch == "."
                    and i > 0
                    and trimmed[i - 1].isdigit()
                    and i + 1 < n
                    and trimmed[i + 1].isdigit()
                )
                so_far = "".join(cur)
                is_abbr = ch == "." and _ends_with_abbreviation(so_far)
                if next_is_boundary and not is_decimal and not is_abbr:
                    while i + 1 < n and trimmed[i + 1] in "\"')»”’]":
                        i += 1
                        cur.append(trimmed[i])
                    sent = "".join(cur).strip()
                    if sent:
                        result.append(sent)
                    cur.clear()
            i += 1
        rem = "".join(cur).strip()
        if rem:
            result.append(rem)
    return result


def _split_at_best_delimiter(text: str, pattern: re.Pattern[str]) -> tuple[str, str] | None:
    matches = list(pattern.finditer(text))
    if not matches:
        return None
    mid = len(text) // 2
    best: tuple[str, str] | None = None
    best_dist = 10**9
    for m in matches:
        cut = m.end()
        if cut <= 0 or cut >= len(text):
            continue
        left = text[:cut].strip()
        right = text[cut:].strip()
        if len(left.split()) < 2 or len(right.split()) < 2:
            continue
        dist = abs(cut - mid)
        if dist < best_dist:
            best_dist = dist
            best = (left, right)
    return best


def _split_at_best_conjunction(text: str) -> tuple[str, str] | None:
    words = text.split()
    if len(words) < 6:
        return None
    mid = len(words) // 2
    best_idx = -1
    best_dist = 10**9
    for i in range(2, len(words) - 2):
        bare = words[i].lower().strip(",;:")
        if bare in _CONJUNCTIONS:
            dist = abs(i - mid)
            if dist < best_dist:
                best_dist = dist
                best_idx = i
    if best_idx < 0:
        return None
    return " ".join(words[:best_idx]), " ".join(words[best_idx:])


def _split_clause_to_duration_limit(
    text: str,
    out: list[str],
    *,
    max_clause_sec: float,
    conservative_syl_per_sec: float,
) -> None:
    cleaned = text.strip()
    if not cleaned:
        return
    if estimate_clause_duration_sec(cleaned, conservative_syl_per_sec=conservative_syl_per_sec) <= max_clause_sec:
        out.append(cleaned)
        return

    for splitter in (
        lambda t: _split_at_best_delimiter(t, _STRONG_SPLIT_RE),
        lambda t: _split_at_best_delimiter(t, _COMMA_SPLIT_RE),
        _split_at_best_conjunction,
    ):
        split = splitter(cleaned)
        if split is not None:
            _split_clause_to_duration_limit(
                split[0], out, max_clause_sec=max_clause_sec, conservative_syl_per_sec=conservative_syl_per_sec
            )
            _split_clause_to_duration_limit(
                split[1], out, max_clause_sec=max_clause_sec, conservative_syl_per_sec=conservative_syl_per_sec
            )
            return

    words = cleaned.split()
    if len(words) <= 1:
        out.append(cleaned)
        return
    mid = max(1, min(len(words) - 1, len(words) // 2))
    _split_clause_to_duration_limit(
        " ".join(words[:mid]), out, max_clause_sec=max_clause_sec, conservative_syl_per_sec=conservative_syl_per_sec
    )
    _split_clause_to_duration_limit(
        " ".join(words[mid:]), out, max_clause_sec=max_clause_sec, conservative_syl_per_sec=conservative_syl_per_sec
    )


def format_clause_id(one_based_index: int) -> str:
    return f"c_{one_based_index:03d}"


def split_script_into_clauses(
    script_text: str,
    *,
    lexicon: Mapping[str, str] | None = None,
    phonetic_overrides: Mapping[str, str] | None = None,
    max_clause_sec: float = TARGET_MAX_CLAUSE_SEC,
    conservative_syl_per_sec: float = CONSERVATIVE_SYLLABLES_PER_SEC,
    takes_dir_prefix: str = "takes",
    base_gap_after_samples: int = DEFAULT_BASE_GAP_SAMPLES,
) -> list[PlannedClause]:
    """Split a multi-clause script into `< 14.0s` clauses with IPA normalization & prefix chaining."""
    if not (1.0 < max_clause_sec < MAX_CLAUSE_DURATION_SEC):
        raise ValueError(f"max_clause_sec must be strictly in (1.0, {MAX_CLAUSE_DURATION_SEC}), got {max_clause_sec}")
    cleaned = script_text.replace("\r\n", "\n").replace("\r", "\n").strip()
    if not cleaned:
        return []

    sentences = _split_into_sentences(cleaned)
    raw_clauses: list[str] = []
    for sent in sentences:
        _split_clause_to_duration_limit(
            sent,
            raw_clauses,
            max_clause_sec=max_clause_sec,
            conservative_syl_per_sec=conservative_syl_per_sec,
        )

    prefix_dir = takes_dir_prefix.rstrip("/")
    planned: list[PlannedClause] = []
    for idx, text in enumerate(raw_clauses):
        cid = format_clause_id(idx + 1)
        prev_prefix = None if idx == 0 else f"{prefix_dir}/{format_clause_id(idx)}_winner_tail_1500ms.pcm"
        ipa = normalize_clause_to_ipa(text, lexicon=lexicon, phonetic_overrides=phonetic_overrides)
        est_sec = estimate_clause_duration_sec(text, conservative_syl_per_sec=conservative_syl_per_sec)
        planned.append(
            PlannedClause(
                clause_id=cid,
                index=idx,
                raw_text=text,
                normalized_phonemes=ipa,
                estimated_duration_sec=round(est_sec, 3),
                acoustic_prefix_pcm_path=prev_prefix,
                base_gap_after_samples=int(base_gap_after_samples),
            )
        )
    return planned


# ---------------------------------------------------------------------------
# Sample-Accurate Zero-Crossing, 64B-Aligned PCM I/O, YIN F0 & EDL Splice DSP
# ---------------------------------------------------------------------------


def find_nearest_zero_crossing(
    pcm: np.ndarray,
    target_sample: int,
    scan_radius: int = ZERO_CROSS_SCAN_SAMPLES,
) -> int:
    """Exact mirror of `FindNearestZeroCrossing` in `core-engine/src/main/cpp/dsp_mastering.cc`."""
    arr = np.asarray(pcm, dtype=np.float32).reshape(-1)
    n = int(arr.size)
    if n <= 0:
        return 0
    clamped_target = max(0, min(n - 1, int(target_sample)))
    radius = max(0, int(scan_radius))
    lo = max(0, clamped_target - radius)
    hi = min(n - 1, clamped_target + radius)

    best_sign_idx = -1
    best_sign_abs = float("inf")
    best_sign_dist = 10**18

    best_overall_idx = clamped_target
    best_overall_abs = float("inf")
    best_overall_dist = 10**18

    for i in range(lo, hi + 1):
        curr = float(arr[i])
        if not math.isfinite(curr):
            continue
        abs_val = abs(curr)
        dist = abs(i - clamped_target)
        if (abs_val < best_overall_abs - 1e-7) or (
            abs(abs_val - best_overall_abs) <= 1e-7 and dist < best_overall_dist
        ):
            best_overall_abs = abs_val
            best_overall_idx = i
            best_overall_dist = dist

        if i > lo:
            prev = float(arr[i - 1])
            if not math.isfinite(prev):
                continue
            crosses_zero = (prev <= 0.0 and curr >= 0.0) or (prev >= 0.0 and curr <= 0.0)
            if crosses_zero:
                cand_idx = (i - 1) if abs(prev) <= abs_val else i
                cand_abs = abs(float(arr[cand_idx]))
                cand_dist = abs(cand_idx - clamped_target)
                strictly_quieter = cand_abs < best_sign_abs - 1e-7
                tie_but_closer = (
                    abs(cand_abs - best_sign_abs) <= 1e-7 and cand_dist < best_sign_dist
                )
                if best_sign_idx < 0 or strictly_quieter or tie_but_closer:
                    best_sign_idx = cand_idx
                    best_sign_abs = cand_abs
                    best_sign_dist = cand_dist

    return best_sign_idx if best_sign_idx >= 0 else best_overall_idx


def write_aligned_pcm_44k(path: Path, pcm: np.ndarray) -> int:
    """Atomically write 64-byte-aligned headerless 44.1 kHz Float32 little-endian PCM."""
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    arr = np.asarray(pcm, dtype="<f4").reshape(-1)
    raw_bytes = arr.tobytes()
    rem = len(raw_bytes) % PCM_ALIGNMENT_BYTES
    pad = (PCM_ALIGNMENT_BYTES - rem) if rem != 0 else 0
    payload = raw_bytes + (b"\x00" * pad)

    tmp_path = p.with_name(p.name + ".tmp")
    tmp_path.write_bytes(payload)
    os.replace(tmp_path, p)
    return len(payload)


def extract_acoustic_prefix_tail(
    pcm: np.ndarray,
    tail_samples: int = ACOUSTIC_PREFIX_TAIL_SAMPLES,
) -> np.ndarray:
    """Extract trailing 1.5 s (`66,150` samples @ 44.1 kHz) for acoustic prefix chaining."""
    arr = np.asarray(pcm, dtype=np.float32).reshape(-1)
    if arr.size == 0 or tail_samples <= 0:
        return np.zeros(0, dtype=np.float32)
    count = min(int(arr.size), int(tail_samples))
    return arr[-count:].copy()


def find_f0_peak_sample(
    pcm44k: np.ndarray,
    start_sample: int,
    end_sample: int,
    sample_rate_hz: int = SAMPLE_RATE_HZ,
) -> int:
    """Locate voiced F0 emphasis peak inside `[start_sample, end_sample]` via YIN CMNDF."""
    arr = np.asarray(pcm44k, dtype=np.float64).reshape(-1)
    n = int(arr.size)
    if n == 0:
        return 0
    lo = max(0, min(n - 1, int(start_sample)))
    hi = max(lo, min(n - 1, int(end_sample)))
    span = hi - lo + 1
    if span <= 4:
        sub = np.abs(arr[lo : hi + 1])
        return lo + int(np.argmax(sub))

    # Human vocal pitch range 70..400 Hz -> lag bounds
    min_tau = max(2, int(sample_rate_hz // 400))
    max_tau = max(min_tau + 1, int(sample_rate_hz // 70))
    frame_len = max(2 * max_tau, int(round(0.030 * sample_rate_hz)))
    hop = max(64, int(round(0.010 * sample_rate_hz)))

    if span < frame_len:
        sub = np.abs(arr[lo : hi + 1])
        return lo + int(np.argmax(sub))

    best_voiced_score = -1.0
    best_voiced_center = (lo + hi) // 2
    best_rms = -1.0
    best_rms_center = (lo + hi) // 2

    max_w = frame_len - max_tau
    for pos in range(lo, hi - frame_len + 2, hop):
        seg = arr[pos : pos + frame_len]
        rms = float(np.sqrt(np.mean(seg * seg)))
        center = pos + frame_len // 2
        if rms > best_rms:
            best_rms = rms
            best_rms_center = center
        if rms < 1e-4:
            continue

        # Fast vectorized YIN difference function over lags [min_tau, max_tau]
        base = seg[:max_w]
        d = np.empty(max_tau + 1, dtype=np.float64)
        d[0] = 0.0
        for tau in range(1, max_tau + 1):
            diff = base - seg[tau : tau + max_w]
            d[tau] = float(np.dot(diff, diff))

        running = np.cumsum(d[1:])
        taus = np.arange(1, max_tau + 1, dtype=np.float64)
        cmndf = np.ones(max_tau + 1, dtype=np.float64)
        nz = running > 1e-12
        cmndf[1:][nz] = d[1:][nz] * taus[nz] / running[nz]

        valid_slice = cmndf[min_tau : max_tau + 1]
        min_rel = int(np.argmin(valid_slice))
        min_cmndf = float(valid_slice[min_rel])
        if min_cmndf < 0.35:
            periodicity = max(0.0, min(1.0, 1.0 - min_cmndf))
            score = periodicity * rms
            if score > best_voiced_score:
                best_voiced_score = score
                best_voiced_center = center

    chosen_center = best_voiced_center if best_voiced_score > 0.0 else best_rms_center
    half = max(64, hop // 2)
    s_lo = max(lo, chosen_center - half)
    s_hi = min(hi, chosen_center + half)
    peak_rel = int(np.argmax(np.abs(arr[s_lo : s_hi + 1])))
    return s_lo + peak_rel


def align_word_tokens(
    pcm44k: np.ndarray,
    clause_text: str,
    *,
    whisper_word_spans: Sequence[tuple[str, int, int]] | None = None,
    sample_rate_hz: int = SAMPLE_RATE_HZ,
    scan_radius: int = ZERO_CROSS_SCAN_SAMPLES,
) -> tuple[list[dict[str, Any]], float]:
    """Produce schema-compliant `WordToken`s with `±88` zero-crossings + `f0PeakSample` + max phoneme ms."""
    arr = np.asarray(pcm44k, dtype=np.float32).reshape(-1)
    n = int(arr.size)
    if n == 0:
        return [], 0.0

    last_idx = n - 1
    raw_spans: list[tuple[str, int, int]] = []
    if whisper_word_spans:
        for word, s0, s1 in whisper_word_spans:
            w = word.strip()
            if not w:
                continue
            cs0 = max(0, min(last_idx, int(s0)))
            cs1 = max(cs0, min(last_idx, int(s1)))
            raw_spans.append((w, cs0, cs1))

    if not raw_spans:
        words = [w for w in re.findall(r"[A-Za-z0-9']+", clause_text) if w]
        if not words:
            words = [clause_text.strip() or "clause"]
        act_start, act_end, act_len = sar.measure_active_speech_samples(
            arr, sample_rate_hz=sample_rate_hz
        )
        act_start = max(0, min(last_idx, act_start))
        act_end = max(act_start, min(last_idx, act_end - 1))
        span_len = max(1, act_end - act_start)
        weights = [max(1, sar.count_word_syllables(w)) for w in words]
        total_w = float(sum(weights))
        cursor = float(act_start)
        for idx, (word, w) in enumerate(zip(words, weights)):
            s0 = int(round(cursor))
            cursor += (float(w) / total_w) * float(span_len)
            s1 = act_end if idx == len(words) - 1 else max(s0, int(round(cursor)))
            raw_spans.append((word, max(0, min(last_idx, s0)), max(0, min(last_idx, s1))))

    tokens: list[dict[str, Any]] = []
    max_phoneme_ms = 0.0
    prev_zc_out = 0

    for word, start_s, end_s in raw_spans:
        s0 = max(0, min(last_idx, int(start_s)))
        s1 = max(s0, min(last_idx, int(end_s)))
        zc_in = find_nearest_zero_crossing(arr, s0, scan_radius=scan_radius)
        if zc_in < prev_zc_out and (prev_zc_out - s0) <= scan_radius:
            zc_in = prev_zc_out
        zc_out = find_nearest_zero_crossing(arr, s1, scan_radius=scan_radius)
        if zc_out < zc_in:
            zc_out = zc_in
        prev_zc_out = zc_out

        f0_peak = find_f0_peak_sample(arr, s0, s1, sample_rate_hz=sample_rate_hz)
        word_dur_ms = (float(max(1, zc_out - zc_in)) * 1000.0) / float(sample_rate_hz)
        est_phonemes = max(2, len(re.sub(r"[^a-z]", "", word.lower())))
        max_phoneme_ms = max(max_phoneme_ms, word_dur_ms / float(est_phonemes))

        tokens.append(
            {
                "word": word,
                "startSample": int(s0),
                "endSample": int(s1),
                "zeroCrossIn": int(zc_in),
                "zeroCrossOut": int(zc_out),
                "f0PeakSample": int(f0_peak),
                "videoAnchorId": None,
            }
        )

    return tokens, round(float(max_phoneme_ms), 2)


def _calibrate_room_tone_bed(
    room_tone_44k: np.ndarray | None,
    total_samples: int,
    *,
    noise_floor_dbfs: float = DEFAULT_NOISE_FLOOR_DBFS,
    crossfade_samples: int = EDL_CROSSFADE_SAMPLES,
) -> np.ndarray:
    """Synthesize a continuous, seamlessly looped `-58 dBFS` studio room-tone bed."""
    if total_samples <= 0:
        return np.zeros(0, dtype=np.float32)
    if room_tone_44k is None:
        return np.zeros(total_samples, dtype=np.float32)
    bed = np.asarray(room_tone_44k, dtype=np.float64).reshape(-1)
    if bed.size == 0:
        return np.zeros(total_samples, dtype=np.float32)
    rms = float(np.sqrt(np.mean(bed * bed)))
    if rms <= 1e-12 or not math.isfinite(rms):
        return np.zeros(total_samples, dtype=np.float32)

    target_rms = 10.0 ** (float(noise_floor_dbfs) / 20.0)
    scaled = bed * (target_rms / rms)
    rt_len = int(scaled.size)
    out = np.empty(total_samples, dtype=np.float64)

    if rt_len > 2 * crossfade_samples:
        loop_len = rt_len - crossfade_samples
        k = np.arange(crossfade_samples, dtype=np.float64)
        theta = (math.pi * 0.5) * (k + 1.0) / float(crossfade_samples + 1)
        cos_w = np.cos(theta)
        sin_w = np.sin(theta)
        pos = 0
        cycle = 0
        while pos < total_samples:
            chunk = min(loop_len, total_samples - pos)
            seg = scaled[:chunk].copy()
            if cycle > 0:
                xf = min(chunk, crossfade_samples)
                seg[:xf] = cos_w[:xf] * scaled[loop_len : loop_len + xf] + sin_w[:xf] * scaled[:xf]
            out[pos : pos + chunk] = seg
            pos += chunk
            cycle += 1
    else:
        reps = (total_samples + rt_len - 1) // rt_len
        out[:] = np.tile(scaled, reps)[:total_samples]

    return out.astype(np.float32)


def _equal_power_cosine_weights(length: int) -> tuple[np.ndarray, np.ndarray]:
    """Exact mirror of `EdlRenderer::EqualPowerCrossfade` in `audio-edl/src/main/cpp/edl_player.cc`."""
    if length <= 0:
        return np.zeros(0, dtype=np.float64), np.zeros(0, dtype=np.float64)
    k = np.arange(length, dtype=np.float64)
    theta = (math.pi * 0.5) * (k + 1.0) / float(length + 1)
    return np.cos(theta), np.sin(theta)


def splice_clauses_edl(
    slices: Sequence[SpliceSlice],
    *,
    room_tone_44k: np.ndarray | None = None,
    room_ir_44k: np.ndarray | None = None,
    global_gap_scale: float = 1.0,
    crossfade_samples: int = EDL_CROSSFADE_SAMPLES,
    scan_radius: int = ZERO_CROSS_SCAN_SAMPLES,
    target_lufs: float = DEFAULT_TARGET_LUFS,
    noise_floor_dbfs: float = DEFAULT_NOISE_FLOOR_DBFS,
    apply_room_ir: bool = False,
    apply_lufs_normalization: bool = True,
    sample_rate_hz: int = SAMPLE_RATE_HZ,
) -> tuple[np.ndarray, dict[str, Any]]:
    """Splice clause slices at exact `±88` zero-crossings with `660`-sample equal-power crossfades.

    Matches `:audio-edl`'s `EdlRenderer` + `:core-engine`'s `dsp_mastering.cc`:
    - Snaps every clause's `zero_cross_in` and `zero_cross_out` within `±88` samples.
    - Applies `660`-sample (`15 ms @ 44.1 kHz`) equal-power cosine crossfades into/out of
      the continuous `-58 dBFS` `room_tone_44k.pcm` bed (or direct slice-to-slice when gap == 0).
    - Scales inter-clause gaps by `global_gap_scale` (`0.25 .. 1.0`).
    - Optionally applies `12 ms` `room_ir_44k` convolution & final `-16.0 LUFS` normalization.
    """
    if not (MIN_GLOBAL_GAP_SCALE <= float(global_gap_scale) <= MAX_GLOBAL_GAP_SCALE):
        raise ValueError(
            f"global_gap_scale must be in [{MIN_GLOBAL_GAP_SCALE}, {MAX_GLOBAL_GAP_SCALE}], "
            f"got {global_gap_scale}"
        )
    if not slices:
        return np.zeros(0, dtype=np.float32), {
            "total_samples": 0,
            "duration_sec": 0.0,
            "global_gap_scale": float(global_gap_scale),
            "clause_boundaries": [],
            "seam_sample_indices": [],
            "max_seam_step": 0.0,
            "mastered_lufs": -120.0,
            "peak_amplitude": 0.0,
        }

    prepared: list[tuple[str, np.ndarray, int, int, int]] = []
    for idx, sl in enumerate(slices):
        arr = np.asarray(sl.pcm44k, dtype=np.float32).reshape(-1).copy()
        if arr.size == 0:
            continue
        if apply_room_ir and room_ir_44k is not None and room_ir_44k.size > 0:
            ir = np.asarray(room_ir_44k, dtype=np.float64).reshape(-1)
            conv = scipy.signal.fftconvolve(arr.astype(np.float64), ir, mode="full")
            arr = conv[: arr.size].astype(np.float32)

        zc_in = find_nearest_zero_crossing(arr, sl.zero_cross_in, scan_radius=scan_radius)
        zc_out = find_nearest_zero_crossing(arr, sl.zero_cross_out, scan_radius=scan_radius)
        if zc_out <= zc_in:
            zc_in = 0
            zc_out = int(arr.size - 1)
        is_last = idx == len(slices) - 1
        scaled_gap = 0 if is_last else max(0, int(round(sl.base_gap_after_samples * float(global_gap_scale))))
        prepared.append((sl.clause_id, arr, zc_in, zc_out, scaled_gap))

    if not prepared:
        return np.zeros(0, dtype=np.float32), {
            "total_samples": 0,
            "duration_sec": 0.0,
            "global_gap_scale": float(global_gap_scale),
            "clause_boundaries": [],
            "seam_sample_indices": [],
            "max_seam_step": 0.0,
            "mastered_lufs": -120.0,
            "peak_amplitude": 0.0,
        }

    # Compute exact total timeline length
    total_samples = 0
    for _, _, zc_in, zc_out, gap in prepared:
        active_len = max(1, zc_out - zc_in + 1)
        total_samples += active_len + gap

    bed = _calibrate_room_tone_bed(
        room_tone_44k,
        total_samples,
        noise_floor_dbfs=noise_floor_dbfs,
        crossfade_samples=crossfade_samples,
    )
    out = bed.astype(np.float64).copy()

    clause_boundaries: list[dict[str, Any]] = []
    seam_indices: list[int] = []
    cursor = 0

    for i, (cid, arr, zc_in, zc_out, gap) in enumerate(prepared):
        seg = arr[zc_in : zc_out + 1].astype(np.float64)
        seg_len = int(seg.size)
        rt_seg = bed[cursor : cursor + seg_len].astype(np.float64)

        fade_in_len = min(crossfade_samples, seg_len // 2)
        fade_out_len = min(crossfade_samples, seg_len - fade_in_len)

        rendered = seg.copy()
        if fade_in_len > 0:
            cos_w, sin_w = _equal_power_cosine_weights(fade_in_len)
            rendered[:fade_in_len] = cos_w * rt_seg[:fade_in_len] + sin_w * seg[:fade_in_len]
        if fade_out_len > 0:
            cos_w, sin_w = _equal_power_cosine_weights(fade_out_len)
            rendered[seg_len - fade_out_len :] = (
                cos_w * seg[seg_len - fade_out_len :] + sin_w * rt_seg[seg_len - fade_out_len :]
            )

        out[cursor : cursor + seg_len] = rendered

        start_global = cursor
        end_global = cursor + seg_len
        clause_boundaries.append(
            {
                "clauseId": cid,
                "startSample": int(start_global),
                "activeEndSample": int(end_global),
                "gapAfterSamples": int(gap),
                "zeroCrossIn": int(zc_in),
                "zeroCrossOut": int(zc_out),
            }
        )

        if i > 0:
            seam_indices.append(start_global)
        if i + 1 < len(prepared):
            seam_indices.append(end_global)

        cursor = end_global + gap

    stitched = out.astype(np.float32)

    if apply_lufs_normalization and stitched.size > 0:
        for pass_idx in range(2):
            cur_lufs = sar.measure_integrated_lufs(stitched, sample_rate_hz)
            if cur_lufs <= -100.0:
                break
            delta_db = float(target_lufs) - cur_lufs
            if pass_idx > 0 and abs(delta_db) <= 0.15:
                break
            clamped_db = max(-36.0, min(36.0, delta_db))
            stitched = sar.apply_gain_and_soft_limiter(stitched, 10.0 ** (clamped_db / 20.0))
            if abs(delta_db) <= 0.15:
                break

    # Measure worst sample-to-sample derivative across +/- 16 samples around every clause seam
    max_seam_step = 0.0
    for seam in seam_indices:
        lo = max(1, seam - 16)
        hi = min(int(stitched.size) - 1, seam + 16)
        if hi >= lo:
            diffs = np.abs(np.diff(stitched[lo - 1 : hi + 1].astype(np.float64)))
            if diffs.size > 0:
                max_seam_step = max(max_seam_step, float(np.max(diffs)))

    final_lufs = sar.measure_integrated_lufs(stitched, sample_rate_hz)
    peak_amp = float(np.max(np.abs(stitched))) if stitched.size > 0 else 0.0
    return stitched, {
        "total_samples": int(stitched.size),
        "duration_sec": round(float(stitched.size) / float(sample_rate_hz), 3),
        "global_gap_scale": float(global_gap_scale),
        "crossfade_samples": int(crossfade_samples),
        "clause_boundaries": clause_boundaries,
        "seam_sample_indices": seam_indices,
        "max_seam_step": round(max_seam_step, 6),
        "mastered_lufs": round(final_lufs, 3),
        "peak_amplitude": round(peak_amp, 5),
    }


# ---------------------------------------------------------------------------
# VoiceProjectManifest.json Builder & Schema Validator
# ---------------------------------------------------------------------------


def infer_lora_version(lora_dir: Path | None, explicit_version: int | None = None) -> int:
    if explicit_version is not None:
        return max(0, int(explicit_version))
    if lora_dir is None:
        return 0
    m = re.search(r"v(\d+)", str(lora_dir), flags=re.IGNORECASE)
    return int(m.group(1)) if m else 1


def build_voice_project_manifest(
    *,
    project_id: str,
    global_gap_scale: float,
    active_lora_version: int,
    clauses: Sequence[dict[str, Any]],
) -> dict[str, Any]:
    """Construct strict `VoiceProjectManifest.json` matching `clause_node.schema.json`."""
    return {
        "projectId": str(project_id),
        "sampleRate": SAMPLE_RATE_HZ,
        "globalGapScale": float(global_gap_scale),
        "activeLoraVersion": int(active_lora_version),
        "clauses": list(clauses),
    }


def validate_manifest_against_schema(
    manifest: Mapping[str, Any],
    schema_path: Path = DEFAULT_SCHEMA_PATH,
) -> None:
    """Validate `VoiceProjectManifest.json` strictly against `clause_node.schema.json`."""
    import jsonschema

    schema = json.loads(Path(schema_path).read_text(encoding="utf-8"))
    validator = jsonschema.Draft202012Validator(schema)
    errors = sorted(validator.iter_errors(dict(manifest)), key=lambda e: list(e.absolute_path))
    if errors:
        first = errors[0]
        loc = ".".join(str(p) for p in first.absolute_path) or "<root>"
        raise ValueError(f"VoiceProjectManifest schema validation failed at {loc}: {first.message}")


# ---------------------------------------------------------------------------
# Real Whisper Word-Timestamp Extraction & Video Stitching Helpers
# ---------------------------------------------------------------------------


def transcribe_pcm16k_whisper_with_words(
    pcm16k: np.ndarray,
    *,
    whisper_bin: Path = sar.DEFAULT_WHISPER_BIN,
    whisper_model: Path = sar.DEFAULT_WHISPER_MODEL,
    sample_rate_hz: int = SAMPLE_RATE_HZ,
) -> tuple[str, list[tuple[str, int, int]]]:
    """Transcribe 16 kHz mono audio offline with `whisper-cli -ojf` and extract word sample spans."""
    bin_path = Path(whisper_bin)
    if not bin_path.is_file():
        found = shutil.which("whisper-cli")
        if not found:
            raise FileNotFoundError(f"whisper-cli not found at {whisper_bin} or on PATH")
        bin_path = Path(found)
    if not Path(whisper_model).is_file():
        raise FileNotFoundError(f"Missing whisper model: {whisper_model}")

    with tempfile.TemporaryDirectory(prefix="studio_whisper_") as tmp_str:
        tmp_dir = Path(tmp_str)
        wav16 = tmp_dir / "take_16k.wav"
        out_prefix = tmp_dir / "whisper_out"
        sf.write(str(wav16), np.clip(pcm16k, -1.0, 1.0), VERIFIER_SR_HZ, subtype="PCM_16")
        proc = subprocess.run(
            [
                str(bin_path),
                "-m",
                str(whisper_model),
                "-l",
                "en",
                "-f",
                str(wav16),
                "-otxt",
                "-ojf",
                "-of",
                str(out_prefix),
                "-np",
            ],
            capture_output=True,
            text=True,
        )
        if proc.returncode != 0:
            raise RuntimeError(
                f"whisper-cli failed (rc={proc.returncode}): {proc.stderr.strip()}"
            )

        txt_file = Path(f"{out_prefix}.txt")
        transcript = (
            s2_pro_infer.clean_text_for_s2_pro(txt_file.read_text(encoding="utf-8"))
            if txt_file.is_file()
            else ""
        )

        word_spans: list[tuple[str, int, int]] = []
        json_file = Path(f"{out_prefix}.json")
        if json_file.is_file():
            try:
                data = json.loads(json_file.read_text(encoding="utf-8"))
                cur_word = ""
                cur_start_ms: int | None = None
                cur_end_ms = 0

                def _flush_word() -> None:
                    nonlocal cur_word, cur_start_ms, cur_end_ms
                    cleaned_w = re.sub(r"[^A-Za-z0-9']", "", cur_word)
                    if cleaned_w and cur_start_ms is not None:
                        s0 = int(round((cur_start_ms / 1000.0) * sample_rate_hz))
                        s1 = max(s0, int(round((cur_end_ms / 1000.0) * sample_rate_hz)))
                        word_spans.append((cleaned_w, s0, s1))
                    cur_word = ""
                    cur_start_ms = None
                    cur_end_ms = 0

                for seg in data.get("transcription", []):
                    for tok in seg.get("tokens", []):
                        piece = str(tok.get("text", ""))
                        if not piece or (piece.startswith("[") and piece.endswith("]")):
                            continue
                        offsets = tok.get("offsets") or {}
                        t_from = int(offsets.get("from", 0))
                        t_to = int(offsets.get("to", t_from))
                        if piece[0].isspace():
                            _flush_word()
                            cur_word = piece.strip()
                            cur_start_ms = t_from
                            cur_end_ms = t_to
                        else:
                            if cur_start_ms is None:
                                cur_start_ms = t_from
                            cur_word += piece
                            cur_end_ms = max(cur_end_ms, t_to)
                _flush_word()
            except Exception:
                word_spans = []

        return transcript, word_spans


def concat_mp4_clips_with_master_audio(
    clip_mp4s: Sequence[Path],
    master_wav: Path,
    output_mp4: Path,
) -> Path:
    """Concatenate per-clause talking-head MP4 clips and mux the exact stitched master WAV."""
    if not clip_mp4s:
        raise ValueError("clip_mp4s must not be empty")
    output_mp4.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="studio_concat_") as tmp_str:
        list_file = Path(tmp_str) / "concat_list.txt"
        lines = [f"file '{Path(p).resolve()}'" for p in clip_mp4s]
        list_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
        proc = subprocess.run(
            [
                "ffmpeg",
                "-y",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                str(list_file),
                "-i",
                str(master_wav),
                "-map",
                "0:v:0",
                "-map",
                "1:a:0",
                "-c:v",
                "libx264",
                "-pix_fmt",
                "yuv420p",
                "-c:a",
                "aac",
                "-b:a",
                "192k",
                "-shortest",
                str(output_mp4),
            ],
            capture_output=True,
            text=True,
        )
        if proc.returncode != 0 or not output_mp4.is_file() or output_mp4.stat().st_size == 0:
            raise RuntimeError(
                f"ffmpeg clip concatenation failed (rc={proc.returncode}): {proc.stderr.strip()}"
            )
    return output_mp4


# ---------------------------------------------------------------------------
# Full Multi-Clause Broadcast Studio Orchestrator
# ---------------------------------------------------------------------------


def render_broadcast_studio(
    *,
    script_text: str,
    project_id: str = "broadcast_studio",
    output_dir: Path,
    takes_per_clause: int = 4,
    base_seed: int = 948101,
    temperature: float = 0.72,
    top_p: float = 0.80,
    top_k: int = 30,
    max_new_tokens: int = 320,
    global_gap_scale: float = 1.0,
    base_gap_after_samples: int = DEFAULT_BASE_GAP_SAMPLES,
    lexicon_path: Path | None = DEFAULT_LEXICON_PATH,
    phonetic_overrides: Mapping[str, str] | None = None,
    s2_pro_dir: Path = s2_pro_infer.DEFAULT_S2_PRO_HF_DIR,
    lora_dir: Path | None = s2_pro_infer.DEFAULT_LORA_DIR,
    lora_scale: float = 1.0,
    lora_version: int | None = None,
    codec_pth: Path = s2_pro_infer.DEFAULT_CODEC_PTH,
    dac_tflite: Path = s2_pro_infer.DEFAULT_CODEC_TFLITE,
    ref_wav: Path = sar.DEFAULT_REF_WAV,
    ref_text: str | None = sar.DEFAULT_REF_TEXT,
    extra_ref_wav: Path | None = sar.DEFAULT_EXTRA_REF_WAV,
    extra_ref_text: str | None = sar.DEFAULT_EXTRA_REF_TEXT,
    prompt_style: str = "multiturn",
    master_centroid_path: Path = sar.DEFAULT_MASTER_CENTROID,
    wespeaker_onnx: Path = eva.DEFAULT_WESPEAKER_ONNX,
    dnsmos_onnx: Path = eva.DEFAULT_DNSMOS_P808_ONNX,
    whisper_bin: Path = sar.DEFAULT_WHISPER_BIN,
    whisper_model: Path = sar.DEFAULT_WHISPER_MODEL,
    room_ir_path: Path | None = sar.DEFAULT_ROOM_IR,
    room_tone_path: Path | None = sar.DEFAULT_ROOM_TONE,
    target_lufs: float = DEFAULT_TARGET_LUFS,
    noise_floor_dbfs: float = DEFAULT_NOISE_FLOOR_DBFS,
    device: str = "mps",
    avatar_path: Path = gth.DEFAULT_AVATAR,
    video_mode: str = "stitched",
    resolution: str = "720P",
    ratio: str = "9:16",
    max_clip_duration: int = 10,
    skip_video: bool = False,
    schema_path: Path = DEFAULT_SCHEMA_PATH,
    # Dependency-injection hooks strictly for automated unit testing (None = real models)
    synthesizer_fn: Callable[[PlannedClause, int], Sequence[SynthesizedTakeData]] | None = None,
    verifier_fn: Callable[[np.ndarray, str], dict[str, Any]] | None = None,
    video_renderer_fn: Callable[[Path, Path, str], dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Execute the complete multi-clause broadcast script studio pipeline."""
    if not (MIN_GLOBAL_GAP_SCALE <= float(global_gap_scale) <= MAX_GLOBAL_GAP_SCALE):
        raise ValueError(
            f"global_gap_scale must be in [{MIN_GLOBAL_GAP_SCALE}, {MAX_GLOBAL_GAP_SCALE}], "
            f"got {global_gap_scale}"
        )
    if takes_per_clause <= 0 or takes_per_clause > 16:
        raise ValueError(f"takes_per_clause must be in [1, 16], got {takes_per_clause}")
    if video_mode not in ("stitched", "per-clause"):
        raise ValueError(f"Unsupported video_mode: {video_mode!r} (expected 'stitched' or 'per-clause')")

    out_root = Path(output_dir)
    takes_dir = out_root / "takes"
    out_root.mkdir(parents=True, exist_ok=True)
    takes_dir.mkdir(parents=True, exist_ok=True)

    # 1. Load OTA lexicon.json and split script into < 14s clauses.
    lexicon = load_lexicon(lexicon_path)
    planned_clauses = split_script_into_clauses(
        script_text,
        lexicon=lexicon,
        phonetic_overrides=phonetic_overrides,
        takes_dir_prefix="takes",
        base_gap_after_samples=base_gap_after_samples,
    )
    if not planned_clauses:
        raise ValueError("Script produced zero spoken clauses.")

    room_ir_arr = (
        eva.read_pcm_44k(Path(room_ir_path))
        if (room_ir_path is not None and Path(room_ir_path).is_file())
        else None
    )
    room_tone_arr = (
        eva.read_pcm_44k(Path(room_tone_path))
        if (room_tone_path is not None and Path(room_tone_path).is_file())
        else None
    )

    # 2. Synthesize N takes per clause (real s2_pro_infer.py + LiteRT DAC when synthesizer_fn is None).
    clause_raw_takes: dict[str, list[SynthesizedTakeData]] = {}
    lora_stats_dict: dict[str, Any] | None = None

    if synthesizer_fn is not None:
        for clause in planned_clauses:
            clause_raw_takes[clause.clause_id] = list(synthesizer_fn(clause, takes_per_clause))
    else:
        ref_specs: list[tuple[Path, str | None]] = [(Path(ref_wav), ref_text)]
        if extra_ref_wav is not None and str(extra_ref_wav).strip() and Path(extra_ref_wav).is_file():
            ref_specs.append((Path(extra_ref_wav), extra_ref_text))

        ref_turns: list[tuple[str, np.ndarray]] = []
        for r_wav, r_txt in ref_specs:
            r_codes, _ = s2_pro_infer.encode_reference_audio_to_codes(
                r_wav, codec_pth=Path(codec_pth), device=device
            )
            resolved_txt = (r_txt or "").strip()
            if not resolved_txt:
                r_pcm16, _ = eva.load_audio_mono(r_wav, target_sr=VERIFIER_SR_HZ)
                resolved_txt = sar.transcribe_pcm16k_whisper(
                    r_pcm16, whisper_bin=whisper_bin, whisper_model=whisper_model
                )
            ref_turns.append((resolved_txt, r_codes))

        effective_lora_dir = None if (lora_dir is None or lora_scale == 0.0) else Path(lora_dir)
        model, tokenizer, lora_stats = s2_pro_infer.load_s2_pro_dual_ar(
            Path(s2_pro_dir),
            lora_dir=effective_lora_dir,
            lora_scale=lora_scale,
            device=device,
        )
        if lora_stats is not None:
            lora_stats_dict = dataclasses.asdict(lora_stats)

        all_gen_items: list[tuple[str, s2_pro_infer.GenerationResult]] = []
        for clause in planned_clauses:
            prompt = s2_pro_infer.build_s2_pro_prompt_tensor(
                tokenizer,
                clause.raw_text,
                ref_turns=ref_turns,
                prompt_style=prompt_style,
            )
            for t_idx in range(takes_per_clause):
                seed = int(base_seed) + clause.index * 100 + t_idx
                gen_res = s2_pro_infer.generate_dual_ar_codes(
                    model,
                    prompt,
                    seed=seed,
                    max_new_tokens=max_new_tokens,
                    temperature=temperature,
                    top_p=top_p,
                    top_k=top_k,
                )
                all_gen_items.append((clause.clause_id, gen_res))

        del model
        import torch

        if device == "mps" and torch.backends.mps.is_available():
            torch.mps.empty_cache()

        decoded_batch = s2_pro_infer.decode_codes_batch_tflite(
            [g.codes for _, g in all_gen_items],
            tflite_path=Path(dac_tflite),
        )
        for (cid, gen_res), (pcm_raw, dec_meta) in zip(all_gen_items, decoded_batch):
            # Log codebook-0 semantic token IDs (151678 + code0) for Tinker OPSD/GRPO parity.
            sem_tokens = [
                int(s2_pro_infer.SEMANTIC_START_TOKEN_ID + int(c))
                for c in np.asarray(gen_res.codes[0], dtype=np.int64).reshape(-1).tolist()
            ]
            clause_raw_takes.setdefault(cid, []).append(
                SynthesizedTakeData(
                    pcm44k=np.asarray(pcm_raw, dtype=np.float32),
                    discrete_audio_tokens=sem_tokens,
                    seed=int(gen_res.seed),
                    temperature=float(gen_res.temperature),
                    generation_meta={
                        "generated_frames": int(gen_res.generated_frames),
                        "hit_eos": bool(gen_res.hit_eos),
                        "elapsed_sec": float(gen_res.elapsed_sec),
                        "frames_per_sec": float(gen_res.frames_per_sec),
                    },
                    decode_meta=dict(dec_meta),
                )
            )

    # 3. Initialize real 4-Gate Verifier backends if no test hook was injected.
    master_centroid = None
    wespeaker = None
    dnsmos = None
    if verifier_fn is None:
        master_centroid = sar.load_master_centroid_bin(Path(master_centroid_path))
        wespeaker = eva.WeSpeakerEngine(Path(wespeaker_onnx))
        dnsmos = eva.DnsmosP808Engine(Path(dnsmos_onnx))

    # 4. Master each take, score all takes through the 4-Gate Verifier, pick winners,
    #    persist 64-byte-aligned .pcm takes + 1.5s acoustic prefix tails, and build ClauseNodes.
    manifest_clauses: list[dict[str, Any]] = []
    winning_slices: list[SpliceSlice] = []
    clause_reports: list[dict[str, Any]] = []
    clause_winner_wavs: list[tuple[PlannedClause, Path]] = []

    for clause in planned_clauses:
        takes = clause_raw_takes.get(clause.clause_id, [])
        if not takes:
            raise RuntimeError(f"Zero synthesized takes produced for clause {clause.clause_id}")

        candidate_manifest_entries: list[dict[str, Any]] = []
        candidate_scoring_records: list[dict[str, Any]] = []
        mastered_take_pcms: list[np.ndarray] = []

        for t_idx, take in enumerate(takes):
            take_id = f"{clause.clause_id}_t{t_idx}"
            mastered_pcm, master_meta = sar.master_synthesized_pcm(
                take.pcm44k,
                room_ir_path=Path(room_ir_path) if (room_ir_path and Path(room_ir_path).is_file()) else None,
                room_tone_path=Path(room_tone_path) if (room_tone_path and Path(room_tone_path).is_file()) else None,
                target_lufs=target_lufs,
                noise_floor_dbfs=noise_floor_dbfs,
            )

            pcm_rel_path = f"takes/{take_id}.pcm"
            pcm_abs_path = out_root / pcm_rel_path
            pcm_bytes = write_aligned_pcm_44k(pcm_abs_path, mastered_pcm)

            whisper_word_spans: list[tuple[str, int, int]] | None = None
            if verifier_fn is not None:
                gate_res = dict(verifier_fn(mastered_pcm, clause.raw_text))
            else:
                assert master_centroid is not None and wespeaker is not None and dnsmos is not None
                pcm16k = eva.resample_44k_to_16k_android_exact(
                    mastered_pcm, SAMPLE_RATE_HZ, VERIFIER_SR_HZ
                )
                transcript, whisper_word_spans = transcribe_pcm16k_whisper_with_words(
                    pcm16k, whisper_bin=whisper_bin, whisper_model=whisper_model
                )
                cer = float(eva.compute_cer(clause.raw_text, transcript))
                emb = wespeaker.extract_embedding(pcm16k)
                speaker_cos = float(eva.cosine_similarity(emb, master_centroid))
                dnsmos_p808 = float(dnsmos.score_p808(pcm16k))
                tail_rms_db = sar.compute_tail_rms_dbfs(mastered_pcm, tail_ms=80, sample_rate_hz=SAMPLE_RATE_HZ)
                _, _, active_samples = sar.measure_active_speech_samples(
                    mastered_pcm, sample_rate_hz=SAMPLE_RATE_HZ
                )
                total_syllables = sar.count_transcript_syllables(clause.raw_text)
                syllables_per_sec = float(total_syllables) / max(
                    float(active_samples) / float(SAMPLE_RATE_HZ), 1e-6
                )
                norm_mos = max(0.0, min(1.0, dnsmos_p808 / 5.0))
                ranking_score = 1.5 * speaker_cos + 0.5 * norm_mos - 3.0 * cer
                gate_res = {
                    "recognized_text": transcript,
                    "cer": round(cer, 5),
                    "wespeaker_cosine": round(speaker_cos, 5),
                    "dnsmos_p808": round(dnsmos_p808, 4),
                    "tail_rms_dbfs": round(tail_rms_db, 2),
                    "syllables_per_sec": round(syllables_per_sec, 3),
                    "ranking_score": round(ranking_score, 5),
                }

            word_tokens, computed_max_phoneme_ms = align_word_tokens(
                mastered_pcm,
                clause.raw_text,
                whisper_word_spans=whisper_word_spans,
                sample_rate_hz=SAMPLE_RATE_HZ,
                scan_radius=ZERO_CROSS_SCAN_SAMPLES,
            )

            cer_val = max(0.0, float(gate_res["cer"]))
            max_ph_ms = max(0.0, float(gate_res.get("max_phoneme_duration_ms", computed_max_phoneme_ms)))
            spk_cos = max(-1.0, min(1.0, float(gate_res["wespeaker_cosine"])))
            mos_val = max(0.0, min(5.0, float(gate_res["dnsmos_p808"])))
            tail_db = float(gate_res["tail_rms_dbfs"])
            syl_s = max(0.0, float(gate_res["syllables_per_sec"]))

            passed_all = (
                cer_val <= sar.GATE_MAX_CER
                and max_ph_ms <= DEFAULT_MAX_PHONEME_MS
                and spk_cos >= sar.GATE_MIN_SPEAKER_COS
                and mos_val >= sar.GATE_MIN_DNSMOS_P808
                and tail_db <= sar.GATE_MAX_TAIL_RMS_DBFS
                and (sar.GATE_MIN_SYLLABLES_PER_SEC <= syl_s <= sar.GATE_MAX_SYLLABLES_PER_SEC)
            )
            ranking_score = float(
                gate_res.get(
                    "ranking_score",
                    1.5 * spk_cos + 0.5 * max(0.0, min(1.0, mos_val / 5.0)) - 3.0 * cer_val,
                )
            )

            metrics_obj = {
                "cer": round(cer_val, 5),
                "maxPhonemeDurationMs": round(max_ph_ms, 2),
                "speakerCosineSim": round(spk_cos, 5),
                "dnsmosP808": round(mos_val, 4),
                "tailRmsDb": round(tail_db, 2),
                "syllablesPerSec": round(syl_s, 3),
                "passedAllGates": bool(passed_all),
            }

            candidate_manifest_entries.append(
                {
                    "takeId": take_id,
                    "pcmFilePath": pcm_rel_path,
                    "totalSamples": int(mastered_pcm.size),
                    "discreteAudioTokens": [int(x) for x in take.discrete_audio_tokens],
                    "seed": int(take.seed),
                    "temperature": float(max(0.0, min(2.0, take.temperature))),
                    "metrics": metrics_obj,
                    "tokens": word_tokens,
                }
            )
            candidate_scoring_records.append(
                {
                    "take_index": t_idx,
                    "take_id": take_id,
                    "pcm_bytes": pcm_bytes,
                    "mastering": master_meta,
                    "gates": {
                        "passed_all_strict_gates": bool(passed_all),
                        "ranking_score": ranking_score,
                        "cer": cer_val,
                        "wespeaker_cosine": spk_cos,
                        "dnsmos_p808": mos_val,
                        "tail_rms_dbfs": tail_db,
                        "syllables_per_sec": syl_s,
                        "max_phoneme_duration_ms": max_ph_ms,
                    },
                }
            )
            mastered_take_pcms.append(mastered_pcm)

        winner_rec = sar.select_best_candidate(candidate_scoring_records)
        win_idx = int(winner_rec["take_index"])
        win_pcm = mastered_take_pcms[win_idx]
        win_entry = candidate_manifest_entries[win_idx]
        win_tokens = win_entry["tokens"]

        # Persist 64-byte-aligned trailing 1.5s acoustic prefix tail for the next clause.
        tail_pcm = extract_acoustic_prefix_tail(win_pcm, ACOUSTIC_PREFIX_TAIL_SAMPLES)
        tail_path = takes_dir / f"{clause.clause_id}_winner_tail_1500ms.pcm"
        write_aligned_pcm_44k(tail_path, tail_pcm)

        # Save winning clause WAV (for inspection & per-clause talking-head rendering).
        clause_wav_path = out_root / f"{clause.clause_id}_winner.wav"
        sf.write(str(clause_wav_path), np.clip(win_pcm, -1.0, 1.0), SAMPLE_RATE_HZ, subtype="PCM_16")
        clause_winner_wavs.append((clause, clause_wav_path))

        zc_in = int(win_tokens[0]["zeroCrossIn"]) if win_tokens else 0
        zc_out = int(win_tokens[-1]["zeroCrossOut"]) if win_tokens else int(win_pcm.size - 1)
        winning_slices.append(
            SpliceSlice(
                clause_id=clause.clause_id,
                pcm44k=win_pcm,
                zero_cross_in=zc_in,
                zero_cross_out=zc_out,
                base_gap_after_samples=clause.base_gap_after_samples,
            )
        )

        manifest_clauses.append(
            {
                "clauseId": clause.clause_id,
                "index": int(clause.index),
                "rawText": clause.raw_text,
                "normalizedPhonemes": clause.normalized_phonemes,
                "acousticPrefixPcmPath": clause.acoustic_prefix_pcm_path,
                "selectedTakeIndex": win_idx,
                "baseGapAfterSamples": int(clause.base_gap_after_samples),
                "candidates": candidate_manifest_entries,
            }
        )
        clause_reports.append(
            {
                "clause_id": clause.clause_id,
                "raw_text": clause.raw_text,
                "normalized_phonemes": clause.normalized_phonemes,
                "estimated_duration_sec": clause.estimated_duration_sec,
                "selected_take_index": win_idx,
                "winner_take_id": win_entry["takeId"],
                "winner_wav": str(clause_wav_path),
                "winner_metrics": win_entry["metrics"],
                "candidates": candidate_scoring_records,
            }
        )

    # 5. Splice all winning clauses with +/-88 zero-crossings & 660-sample equal-power crossfades.
    stitched_pcm, splice_meta = splice_clauses_edl(
        winning_slices,
        room_tone_44k=room_tone_arr,
        room_ir_44k=room_ir_arr,
        global_gap_scale=global_gap_scale,
        crossfade_samples=EDL_CROSSFADE_SAMPLES,
        scan_radius=ZERO_CROSS_SCAN_SAMPLES,
        target_lufs=target_lufs,
        noise_floor_dbfs=noise_floor_dbfs,
        apply_room_ir=False,  # Each winning take already convolved room_ir_44k.pcm
        apply_lufs_normalization=True,
        sample_rate_hz=SAMPLE_RATE_HZ,
    )

    master_wav_path = out_root / f"{project_id}_master.wav"
    master_pcm_path = out_root / f"{project_id}_master.pcm"
    sf.write(str(master_wav_path), np.clip(stitched_pcm, -1.0, 1.0), SAMPLE_RATE_HZ, subtype="PCM_16")
    master_pcm_bytes = write_aligned_pcm_44k(master_pcm_path, stitched_pcm)

    # 6. Emit strict schema-validated VoiceProjectManifest.json ready for Android Hairline Clipper.
    resolved_lora_ver = infer_lora_version(lora_dir, explicit_version=lora_version)
    manifest = build_voice_project_manifest(
        project_id=project_id,
        global_gap_scale=global_gap_scale,
        active_lora_version=resolved_lora_ver,
        clauses=manifest_clauses,
    )
    validate_manifest_against_schema(manifest, schema_path=schema_path)

    manifest_path = out_root / "VoiceProjectManifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    # 7. Optional talking-head video rendering via generate_talking_head.py.
    video_meta: dict[str, Any] | None = None
    if not skip_video:
        final_mp4 = out_root / f"{project_id}_talking_head.mp4"
        renderer = video_renderer_fn or (
            lambda wav_p, mp4_p, txt: gth.generate_talking_head(
                voice_input=wav_p,
                avatar_path=Path(avatar_path),
                output_mp4=mp4_p,
                transcript=txt,
                resolution=resolution,
                ratio=ratio,
                max_duration=max_clip_duration,
            )
        )
        effective_mode = video_mode
        if effective_mode == "stitched" and splice_meta["duration_sec"] > float(max_clip_duration):
            effective_mode = "per-clause"

        if effective_mode == "stitched":
            full_transcript = " ".join(c.raw_text for c in planned_clauses)
            video_meta = {
                "mode": "stitched",
                "output_mp4": str(final_mp4),
                "result": renderer(master_wav_path, final_mp4, full_transcript),
            }
        else:
            clip_results: list[dict[str, Any]] = []
            clip_paths: list[Path] = []
            for clause, c_wav in clause_winner_wavs:
                c_mp4 = out_root / f"{clause.clause_id}_talking_head.mp4"
                clip_results.append(renderer(c_wav, c_mp4, clause.raw_text))
                clip_paths.append(c_mp4)
            if video_renderer_fn is None:
                concat_mp4_clips_with_master_audio(clip_paths, master_wav_path, final_mp4)
            video_meta = {
                "mode": "per-clause",
                "output_mp4": str(final_mp4),
                "clause_clips": [str(p) for p in clip_paths],
                "results": clip_results,
            }

    report = {
        "timestamp_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "project_id": project_id,
        "output_dir": str(out_root),
        "manifest_path": str(manifest_path),
        "master_wav_path": str(master_wav_path),
        "master_pcm_path": str(master_pcm_path),
        "master_pcm_bytes": master_pcm_bytes,
        "active_lora_version": resolved_lora_ver,
        "lora_dir": str(lora_dir) if lora_dir is not None else None,
        "dac_tflite": str(dac_tflite),
        "lora_merge_stats": lora_stats_dict,
        "clause_count": len(planned_clauses),
        "takes_per_clause": int(takes_per_clause),
        "splice": splice_meta,
        "clauses": clause_reports,
        "video": video_meta,
    }
    report_path = out_root / "studio_report.json"
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return report


def _parse_phonetic_overrides(items: Sequence[str]) -> dict[str, str]:
    out: dict[str, str] = {}
    for item in items:
        if "=" not in item:
            raise argparse.ArgumentTypeError(
                f"Invalid --phonetic-override {item!r}: expected 'TERM=IPA'"
            )
        k, v = item.split("=", 1)
        if not k.strip() or not v.strip():
            raise argparse.ArgumentTypeError(
                f"Empty term or IPA in --phonetic-override {item!r}"
            )
        out[k.strip()] = v.strip()
    return out


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Aircheck / VoiceStudio Multi-Clause Broadcast Script Studio"
    )
    src = parser.add_mutually_exclusive_group(required=True)
    src.add_argument("--script", type=str, help="Inline multi-clause broadcast script text")
    src.add_argument("--script-file", type=Path, help="Path to UTF-8 multi-clause script file")

    parser.add_argument("--project-id", type=str, default="broadcast_studio")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=sar.DEFAULT_RENDERS_DIR / "broadcast_studio_out",
    )
    parser.add_argument("--takes-per-clause", "-n", type=int, default=4)
    parser.add_argument("--base-seed", type=int, default=948101)
    parser.add_argument("--temperature", type=float, default=0.72)
    parser.add_argument("--top-p", type=float, default=0.80)
    parser.add_argument("--top-k", type=int, default=30)
    parser.add_argument("--max-new-tokens", type=int, default=320)
    parser.add_argument(
        "--global-gap-scale",
        type=float,
        default=1.0,
        help="Inter-clause breath gap compression scale in [0.25, 1.0]",
    )
    parser.add_argument("--base-gap-samples", type=int, default=DEFAULT_BASE_GAP_SAMPLES)
    parser.add_argument("--lexicon", type=Path, default=DEFAULT_LEXICON_PATH)
    parser.add_argument(
        "--phonetic-override",
        action="append",
        default=[],
        help="Repeatable inline IPA override formatted as TERM=IPA",
    )

    parser.add_argument("--s2-pro-dir", type=Path, default=s2_pro_infer.DEFAULT_S2_PRO_HF_DIR)
    parser.add_argument(
        "--lora-dir",
        type=str,
        default=str(s2_pro_infer.DEFAULT_LORA_DIR),
        help="PEFT LoRA adapter dir (v001 or v002), or 'none' for base s2-pro",
    )
    parser.add_argument("--lora-scale", type=float, default=1.0)
    parser.add_argument("--lora-version", type=int, default=None)
    parser.add_argument("--codec-pth", type=Path, default=s2_pro_infer.DEFAULT_CODEC_PTH)
    parser.add_argument(
        "--dac-tflite",
        type=Path,
        default=s2_pro_infer.DEFAULT_CODEC_TFLITE,
        help="Exported FP16 LiteRT DAC decoder path (v001 or v002)",
    )

    parser.add_argument("--ref-wav", type=Path, default=sar.DEFAULT_REF_WAV)
    parser.add_argument("--ref-text", type=str, default=sar.DEFAULT_REF_TEXT)
    parser.add_argument("--extra-ref-wav", type=Path, default=sar.DEFAULT_EXTRA_REF_WAV)
    parser.add_argument("--extra-ref-text", type=str, default=sar.DEFAULT_EXTRA_REF_TEXT)
    parser.add_argument("--prompt-style", choices=["multiturn", "system"], default="multiturn")

    parser.add_argument("--master-centroid", type=Path, default=sar.DEFAULT_MASTER_CENTROID)
    parser.add_argument("--wespeaker-onnx", type=Path, default=eva.DEFAULT_WESPEAKER_ONNX)
    parser.add_argument("--dnsmos-onnx", type=Path, default=eva.DEFAULT_DNSMOS_P808_ONNX)
    parser.add_argument("--whisper-bin", type=Path, default=sar.DEFAULT_WHISPER_BIN)
    parser.add_argument("--whisper-model", type=Path, default=sar.DEFAULT_WHISPER_MODEL)
    parser.add_argument("--room-ir", type=Path, default=sar.DEFAULT_ROOM_IR)
    parser.add_argument("--room-tone", type=Path, default=sar.DEFAULT_ROOM_TONE)
    parser.add_argument("--target-lufs", type=float, default=DEFAULT_TARGET_LUFS)
    parser.add_argument("--noise-floor-dbfs", type=float, default=DEFAULT_NOISE_FLOOR_DBFS)
    parser.add_argument("--device", type=str, default="mps")

    parser.add_argument("--avatar", type=Path, default=gth.DEFAULT_AVATAR)
    parser.add_argument("--video-mode", choices=["stitched", "per-clause"], default="stitched")
    parser.add_argument("--resolution", choices=["480P", "720P", "1080P"], default="720P")
    parser.add_argument("--ratio", choices=["9:16", "16:9", "1:1"], default="9:16")
    parser.add_argument("--max-clip-duration", type=int, default=10)
    parser.add_argument("--skip-video", action="store_true", help="Skip talking-head MP4 rendering")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    script_text = (
        args.script_file.read_text(encoding="utf-8")
        if args.script_file is not None
        else str(args.script)
    )
    overrides = _parse_phonetic_overrides(args.phonetic_override)
    lora_dir_arg = (args.lora_dir or "").strip()
    lora_dir = None if (not lora_dir_arg or lora_dir_arg.lower() == "none") else Path(lora_dir_arg)

    report = render_broadcast_studio(
        script_text=script_text,
        project_id=args.project_id,
        output_dir=args.output_dir,
        takes_per_clause=args.takes_per_clause,
        base_seed=args.base_seed,
        temperature=args.temperature,
        top_p=args.top_p,
        top_k=args.top_k,
        max_new_tokens=args.max_new_tokens,
        global_gap_scale=args.global_gap_scale,
        base_gap_after_samples=args.base_gap_samples,
        lexicon_path=args.lexicon,
        phonetic_overrides=overrides,
        s2_pro_dir=args.s2_pro_dir,
        lora_dir=lora_dir,
        lora_scale=args.lora_scale,
        lora_version=args.lora_version,
        codec_pth=args.codec_pth,
        dac_tflite=args.dac_tflite,
        ref_wav=args.ref_wav,
        ref_text=args.ref_text,
        extra_ref_wav=args.extra_ref_wav,
        extra_ref_text=args.extra_ref_text,
        prompt_style=args.prompt_style,
        master_centroid_path=args.master_centroid,
        wespeaker_onnx=args.wespeaker_onnx,
        dnsmos_onnx=args.dnsmos_onnx,
        whisper_bin=args.whisper_bin,
        whisper_model=args.whisper_model,
        room_ir_path=args.room_ir,
        room_tone_path=args.room_tone,
        target_lufs=args.target_lufs,
        noise_floor_dbfs=args.noise_floor_dbfs,
        device=args.device,
        avatar_path=args.avatar,
        video_mode=args.video_mode,
        resolution=args.resolution,
        ratio=args.ratio,
        max_clip_duration=args.max_clip_duration,
        skip_video=args.skip_video,
    )
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
