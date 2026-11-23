"""VoiceStudio Nightly Tinker Cookbook Daemon & OTA Bundle Manifest Builder.

Implements Milestone 5 (Home Rig / Gate 5a) for the rectified Aircheck / VoiceStudio
architecture:
- Strict preferences.jsonl ingestion & validation against
  docs/swarm/schemas/preference_tuple.schema.json
- SingleSpeakerAudioEnv targeting thinking-machines-lab/tinker-cookbook
- Exact SPEC reward function (hard -2.0 penalty on CER > 0.0)
- Deterministic OPSD (teacher conditioned on edited cadence + lexicon.json;
  student conditioned only on raw_text) + GRPO preference & gate-failure dataset builder
- Fail-fast run_nightly_update orchestrating Rank-32 LoRA OPSD + GRPO + GGUF export
  via export_lora_gguf.sh (ZERO fabricated weights or fake success)
- build_bundle_manifest computing real SHA-256 digests and enforcing 44.1 kHz + <=9500 MB
  RAM ceiling matching docs/swarm/schemas/ota_bundle_manifest.schema.json
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime
import hashlib
import importlib
import json
import math
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Callable, Mapping, Protocol, Sequence

# ---------------------------------------------------------------------------
# Frozen Architecture & Schema Constants
# ---------------------------------------------------------------------------

SCHEMA_VERSION: int = 1
SAMPLE_RATE_HZ: int = 44_100
MIN_FOREGROUND_RAM_CEILING_MB: int = 512
FOREGROUND_RAM_CEILING_MB: int = 9_500

OPSD_DEFAULT_STEPS: int = 10
OPSD_LORA_RANK: int = 32
OPSD_LEARNING_RATE: float = 1e-4

GRPO_DEFAULT_STEPS: int = 15
GRPO_KL_COEF: float = 0.04

USER_GAP_SCALE_MIN: float = 0.25
USER_GAP_SCALE_MAX: float = 1.0

VALID_USER_ACTIONS: frozenset[str] = frozenset(
    {
        "TAKE_SWAP",
        "PACE_EDIT",
        "TAKE_SWAP_AND_PACE_EDIT",
        "PHONEME_OVERRIDE",
        "VIDEO_ANCHOR_SNAP",
        "PROJECT_EXPORT",
    }
)

VALID_ARTIFACT_ROLES: frozenset[str] = frozenset(
    {
        "s2_pro_base_gguf",
        "voice_lora_gguf",
        "codec_decoder_tflite",
        "sherpa_ctc_aligner",
        "wespeaker_resnet34_onnx",
        "master_centroid_bin",
        "dnsmos_p808_onnx",
        "room_ir_pcm",
        "room_tone_pcm",
        "g2p_lexicon",
    }
)

VALID_ARTIFACT_FORMATS: frozenset[str] = frozenset(
    {
        "gguf",
        "tflite",
        "onnx",
        "bin",
        "pcm",
        "json",
        "tar_gz",
    }
)

VALID_UPDATE_CADENCES: frozenset[str] = frozenset(
    {
        "static_once",
        "nightly_delta",
    }
)

_PREFERENCE_REQUIRED_KEYS: frozenset[str] = frozenset(
    {
        "timestamp",
        "clause_id",
        "lora_version",
        "raw_text",
        "normalized_phonemes",
        "user_action",
        "user_gap_scale",
        "chosen_tokens",
        "rejected_tokens",
        "gate_failures",
    }
)

_GATE_FAILURE_REQUIRED_KEYS: frozenset[str] = frozenset(
    {"seed", "cer", "reason", "tokens"}
)

_MANIFEST_REQUIRED_KEYS: frozenset[str] = frozenset(
    {
        "schema_version",
        "bundle_version",
        "lora_version",
        "created_at_utc",
        "sample_rate_hz",
        "foreground_ram_ceiling_mb",
        "artifacts",
    }
)

_ARTIFACT_REQUIRED_KEYS: frozenset[str] = frozenset(
    {
        "role",
        "filename",
        "format",
        "sha256",
        "size_bytes",
        "estimated_ram_mb",
        "update_cadence",
    }
)

_ARTIFACT_ALLOWED_KEYS: frozenset[str] = _ARTIFACT_REQUIRED_KEYS | {"download_path"}

_SHA256_RE = re.compile(r"^[a-f0-9]{64}$")
_LORA_FILENAME_RE = re.compile(r"^voice_lora_v0*(\d+)\.gguf$")
_WORD_RE = re.compile(r"[A-Za-z0-9']+")

# ---------------------------------------------------------------------------
# Optional Tinker Cookbook RL Base Import (Protocol Fallback ONLY for Typing)
# ---------------------------------------------------------------------------

try:
    from tinker_cookbook.rl import Env as _TinkerEnvBase  # type: ignore[import-not-found]

    TINKER_COOKBOOK_AVAILABLE = True
except ImportError:
    try:
        from tinker_cookbook.rl.types import Env as _TinkerEnvBase  # type: ignore[import-not-found]

        TINKER_COOKBOOK_AVAILABLE = True
    except ImportError:
        TINKER_COOKBOOK_AVAILABLE = False

        class _TinkerEnvBase(Protocol):
            """Typing-only fallback matching tinker_cookbook.rl.Env when H100 wheels are absent."""

            async def initial_observation(self) -> tuple[Any, Any]: ...

            async def step(
                self, action: Sequence[int], *, extra: Mapping[str, Any] | None = None
            ) -> Any: ...


# ---------------------------------------------------------------------------
# Exceptions & Data Models
# ---------------------------------------------------------------------------


class SchemaValidationError(ValueError):
    """Raised when a preference tuple or OTA bundle manifest violates its frozen JSON schema."""


@dataclasses.dataclass(frozen=True)
class GateFailure:
    seed: int
    cer: float
    reason: str
    tokens: tuple[int, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "seed": self.seed,
            "cer": self.cer,
            "reason": self.reason,
            "tokens": list(self.tokens),
        }


@dataclasses.dataclass(frozen=True)
class PreferenceRecord:
    timestamp: int
    clause_id: str
    lora_version: int
    raw_text: str
    normalized_phonemes: str
    user_action: str
    user_gap_scale: float
    chosen_tokens: tuple[int, ...]
    rejected_tokens: tuple[int, ...]
    gate_failures: tuple[GateFailure, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        return {
            "timestamp": self.timestamp,
            "clause_id": self.clause_id,
            "lora_version": self.lora_version,
            "raw_text": self.raw_text,
            "normalized_phonemes": self.normalized_phonemes,
            "user_action": self.user_action,
            "user_gap_scale": self.user_gap_scale,
            "chosen_tokens": list(self.chosen_tokens),
            "rejected_tokens": list(self.rejected_tokens),
            "gate_failures": [gf.to_dict() for gf in self.gate_failures],
        }


@dataclasses.dataclass(frozen=True)
class AudioGateMetrics:
    cer: float
    speaker_cosine_sim: float
    dnsmos_p808: float
    max_phoneme_duration_ms: float = 0.0
    tail_rms_db: float = -58.0
    syllables_per_sec: float = 5.0


# Alias matching Kotlin SynthesisContracts.kt naming
GateMetrics = AudioGateMetrics


@dataclasses.dataclass(frozen=True)
class AudioStepResult:
    reward: float
    episode_done: bool
    next_observation: Any
    next_stop_condition: list[int]
    metrics: dict[str, float]


@dataclasses.dataclass(frozen=True)
class OpsdPair:
    clause_id: str
    timestamp: int
    lora_version: int
    raw_text: str
    normalized_phonemes: str
    user_action: str
    user_gap_scale: float
    lexicon_overrides: dict[str, str]
    student_prompt: str
    teacher_prompt: str
    target_tokens: tuple[int, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "clause_id": self.clause_id,
            "timestamp": self.timestamp,
            "lora_version": self.lora_version,
            "raw_text": self.raw_text,
            "normalized_phonemes": self.normalized_phonemes,
            "user_action": self.user_action,
            "user_gap_scale": self.user_gap_scale,
            "lexicon_overrides": dict(self.lexicon_overrides),
            "student_prompt": self.student_prompt,
            "teacher_prompt": self.teacher_prompt,
            "target_tokens": list(self.target_tokens),
        }


@dataclasses.dataclass(frozen=True)
class GrpoCandidate:
    role: str  # "chosen" | "rejected" | "gate_failure"
    tokens: tuple[int, ...]
    human_chosen: bool
    is_gate_failure: bool
    known_cer: float | None = None
    fixed_reward: float | None = None
    failure_reason: str | None = None
    seed: int | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "role": self.role,
            "tokens": list(self.tokens),
            "human_chosen": self.human_chosen,
            "is_gate_failure": self.is_gate_failure,
            "known_cer": self.known_cer,
            "fixed_reward": self.fixed_reward,
            "failure_reason": self.failure_reason,
            "seed": self.seed,
        }


@dataclasses.dataclass(frozen=True)
class PreferencePair:
    chosen_tokens: tuple[int, ...]
    rejected_tokens: tuple[int, ...]
    source: str


@dataclasses.dataclass(frozen=True)
class GrpoGroup:
    clause_id: str
    timestamp: int
    lora_version: int
    raw_text: str
    normalized_phonemes: str
    user_action: str
    user_gap_scale: float
    candidates: tuple[GrpoCandidate, ...]
    preference_pairs: tuple[PreferencePair, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "clause_id": self.clause_id,
            "timestamp": self.timestamp,
            "lora_version": self.lora_version,
            "raw_text": self.raw_text,
            "normalized_phonemes": self.normalized_phonemes,
            "user_action": self.user_action,
            "user_gap_scale": self.user_gap_scale,
            "candidates": [c.to_dict() for c in self.candidates],
            "preference_pairs": [
                {
                    "chosen_tokens": list(p.chosen_tokens),
                    "rejected_tokens": list(p.rejected_tokens),
                    "source": p.source,
                }
                for p in self.preference_pairs
            ],
        }


@dataclasses.dataclass(frozen=True)
class OpsdGrpoDataset:
    opsd_pairs: tuple[OpsdPair, ...]
    grpo_groups: tuple[GrpoGroup, ...]
    record_count: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "record_count": self.record_count,
            "opsd_pairs": [p.to_dict() for p in self.opsd_pairs],
            "grpo_groups": [g.to_dict() for g in self.grpo_groups],
        }


@dataclasses.dataclass(frozen=True)
class NightlyUpdateResult:
    lora_version: int
    lora_gguf_path: Path
    sha256: str
    size_bytes: int
    opsd_steps: int
    grpo_steps: int
    record_count: int


# ---------------------------------------------------------------------------
# Strict Type Helpers & Schema Validators
# ---------------------------------------------------------------------------


def _is_strict_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _is_strict_number(value: Any) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(float(value))
    )


def _validate_int_array(value: Any, field_name: str) -> tuple[int, ...]:
    if not isinstance(value, list):
        raise SchemaValidationError(f"Field '{field_name}' must be an array of integers")
    result: list[int] = []
    for idx, item in enumerate(value):
        if not _is_strict_int(item):
            raise SchemaValidationError(
                f"Field '{field_name}[{idx}]' must be an integer, got {type(item).__name__}"
            )
        result.append(int(item))
    return tuple(result)


def validate_preference_record(obj: Any) -> PreferenceRecord:
    """Strictly validates a decoded JSON object against preference_tuple.schema.json."""
    if not isinstance(obj, dict):
        raise SchemaValidationError(
            f"Preference record must be a JSON object, got {type(obj).__name__}"
        )

    keys = set(obj.keys())
    missing = _PREFERENCE_REQUIRED_KEYS - keys
    if missing:
        raise SchemaValidationError(
            f"Missing required preference fields: {sorted(missing)}"
        )
    extra = keys - _PREFERENCE_REQUIRED_KEYS
    if extra:
        raise SchemaValidationError(
            f"Unexpected additional preference fields: {sorted(extra)}"
        )

    timestamp = obj["timestamp"]
    if not _is_strict_int(timestamp) or timestamp < 0:
        raise SchemaValidationError("Field 'timestamp' must be an integer >= 0")

    clause_id = obj["clause_id"]
    if not isinstance(clause_id, str) or len(clause_id) < 1:
        raise SchemaValidationError("Field 'clause_id' must be a non-empty string")

    lora_version = obj["lora_version"]
    if not _is_strict_int(lora_version) or lora_version < 0:
        raise SchemaValidationError("Field 'lora_version' must be an integer >= 0")

    raw_text = obj["raw_text"]
    if not isinstance(raw_text, str) or len(raw_text) < 1:
        raise SchemaValidationError("Field 'raw_text' must be a non-empty string")

    normalized_phonemes = obj["normalized_phonemes"]
    if not isinstance(normalized_phonemes, str):
        raise SchemaValidationError("Field 'normalized_phonemes' must be a string")

    user_action = obj["user_action"]
    if not isinstance(user_action, str) or user_action not in VALID_USER_ACTIONS:
        raise SchemaValidationError(
            f"Field 'user_action' must be one of {sorted(VALID_USER_ACTIONS)}, got {user_action!r}"
        )

    user_gap_scale = obj["user_gap_scale"]
    if not _is_strict_number(user_gap_scale):
        raise SchemaValidationError("Field 'user_gap_scale' must be a finite number")
    gap_scale_f = float(user_gap_scale)
    if gap_scale_f < USER_GAP_SCALE_MIN or gap_scale_f > USER_GAP_SCALE_MAX:
        raise SchemaValidationError(
            f"Field 'user_gap_scale' must be within [{USER_GAP_SCALE_MIN}, {USER_GAP_SCALE_MAX}], got {gap_scale_f}"
        )

    chosen_tokens = _validate_int_array(obj["chosen_tokens"], "chosen_tokens")
    rejected_tokens = _validate_int_array(obj["rejected_tokens"], "rejected_tokens")

    raw_failures = obj["gate_failures"]
    if not isinstance(raw_failures, list):
        raise SchemaValidationError("Field 'gate_failures' must be an array")

    validated_failures: list[GateFailure] = []
    for idx, entry in enumerate(raw_failures):
        if not isinstance(entry, dict):
            raise SchemaValidationError(
                f"Field 'gate_failures[{idx}]' must be an object"
            )
        entry_keys = set(entry.keys())
        entry_missing = _GATE_FAILURE_REQUIRED_KEYS - entry_keys
        if entry_missing:
            raise SchemaValidationError(
                f"Field 'gate_failures[{idx}]' missing required keys: {sorted(entry_missing)}"
            )
        entry_extra = entry_keys - _GATE_FAILURE_REQUIRED_KEYS
        if entry_extra:
            raise SchemaValidationError(
                f"Field 'gate_failures[{idx}]' has unexpected keys: {sorted(entry_extra)}"
            )
        seed = entry["seed"]
        if not _is_strict_int(seed):
            raise SchemaValidationError(
                f"Field 'gate_failures[{idx}].seed' must be an integer"
            )
        cer = entry["cer"]
        if not _is_strict_number(cer) or float(cer) < 0.0:
            raise SchemaValidationError(
                f"Field 'gate_failures[{idx}].cer' must be a finite number >= 0.0"
            )
        reason = entry["reason"]
        if not isinstance(reason, str) or len(reason) < 1:
            raise SchemaValidationError(
                f"Field 'gate_failures[{idx}].reason' must be a non-empty string"
            )
        tokens = _validate_int_array(entry["tokens"], f"gate_failures[{idx}].tokens")
        validated_failures.append(
            GateFailure(
                seed=int(seed),
                cer=float(cer),
                reason=reason,
                tokens=tokens,
            )
        )

    return PreferenceRecord(
        timestamp=int(timestamp),
        clause_id=clause_id,
        lora_version=int(lora_version),
        raw_text=raw_text,
        normalized_phonemes=normalized_phonemes,
        user_action=user_action,
        user_gap_scale=gap_scale_f,
        chosen_tokens=chosen_tokens,
        rejected_tokens=rejected_tokens,
        gate_failures=tuple(validated_failures),
    )


def parse_preferences_ndjson(payload_text: str) -> list[PreferenceRecord]:
    """Parses and strictly validates NDJSON/JSONL preference lines."""
    records: list[PreferenceRecord] = []
    for line_no, raw_line in enumerate(payload_text.splitlines(), start=1):
        stripped = raw_line.strip()
        if not stripped:
            continue
        try:
            decoded = json.loads(stripped)
        except json.JSONDecodeError as exc:
            raise SchemaValidationError(
                f"Malformed JSON on line {line_no}: {exc.msg}"
            ) from exc
        try:
            records.append(validate_preference_record(decoded))
        except SchemaValidationError as exc:
            raise SchemaValidationError(f"Line {line_no}: {exc}") from exc
    return records


def load_preferences_jsonl(jsonl_path: Path | str) -> list[PreferenceRecord]:
    """Loads and validates every record from a preferences.jsonl file."""
    path = Path(jsonl_path)
    if not path.is_file():
        raise FileNotFoundError(f"preferences.jsonl not found: {path}")
    return parse_preferences_ndjson(path.read_text(encoding="utf-8"))


def validate_bundle_manifest(obj: Any) -> dict[str, Any]:
    """Strictly validates a manifest dict against ota_bundle_manifest.schema.json."""
    if not isinstance(obj, dict):
        raise SchemaValidationError(
            f"Bundle manifest must be a JSON object, got {type(obj).__name__}"
        )

    keys = set(obj.keys())
    missing = _MANIFEST_REQUIRED_KEYS - keys
    if missing:
        raise SchemaValidationError(f"Manifest missing required fields: {sorted(missing)}")
    extra = keys - _MANIFEST_REQUIRED_KEYS
    if extra:
        raise SchemaValidationError(
            f"Manifest contains unexpected fields: {sorted(extra)}"
        )

    schema_version = obj["schema_version"]
    if not _is_strict_int(schema_version) or schema_version != SCHEMA_VERSION:
        raise SchemaValidationError(
            f"Manifest 'schema_version' must be {SCHEMA_VERSION}, got {schema_version!r}"
        )

    bundle_version = obj["bundle_version"]
    if not isinstance(bundle_version, str) or len(bundle_version) < 1:
        raise SchemaValidationError("Manifest 'bundle_version' must be a non-empty string")

    lora_version = obj["lora_version"]
    if not _is_strict_int(lora_version) or lora_version < 0:
        raise SchemaValidationError("Manifest 'lora_version' must be an integer >= 0")

    created_at_utc = obj["created_at_utc"]
    if not isinstance(created_at_utc, str) or len(created_at_utc) < 1:
        raise SchemaValidationError("Manifest 'created_at_utc' must be a non-empty string")

    sample_rate_hz = obj["sample_rate_hz"]
    if not _is_strict_int(sample_rate_hz) or sample_rate_hz != SAMPLE_RATE_HZ:
        raise SchemaValidationError(
            f"Manifest 'sample_rate_hz' must be {SAMPLE_RATE_HZ}, got {sample_rate_hz!r}"
        )

    ram_ceiling = obj["foreground_ram_ceiling_mb"]
    if (
        not _is_strict_int(ram_ceiling)
        or ram_ceiling < MIN_FOREGROUND_RAM_CEILING_MB
        or ram_ceiling > FOREGROUND_RAM_CEILING_MB
    ):
        raise SchemaValidationError(
            f"Manifest 'foreground_ram_ceiling_mb' must be in "
            f"[{MIN_FOREGROUND_RAM_CEILING_MB}, {FOREGROUND_RAM_CEILING_MB}], got {ram_ceiling!r}"
        )

    artifacts = obj["artifacts"]
    if not isinstance(artifacts, list) or len(artifacts) < 1:
        raise SchemaValidationError("Manifest 'artifacts' must be a non-empty array")

    total_ram_mb = 0
    for idx, art in enumerate(artifacts):
        if not isinstance(art, dict):
            raise SchemaValidationError(f"Manifest 'artifacts[{idx}]' must be an object")
        art_keys = set(art.keys())
        art_missing = _ARTIFACT_REQUIRED_KEYS - art_keys
        if art_missing:
            raise SchemaValidationError(
                f"Manifest 'artifacts[{idx}]' missing required keys: {sorted(art_missing)}"
            )
        art_extra = art_keys - _ARTIFACT_ALLOWED_KEYS
        if art_extra:
            raise SchemaValidationError(
                f"Manifest 'artifacts[{idx}]' has unexpected keys: {sorted(art_extra)}"
            )

        role = art["role"]
        if not isinstance(role, str) or role not in VALID_ARTIFACT_ROLES:
            raise SchemaValidationError(
                f"Manifest 'artifacts[{idx}].role' invalid: {role!r}"
            )

        filename = art["filename"]
        if (
            not isinstance(filename, str)
            or len(filename) < 1
            or "/" in filename
            or "\\" in filename
            or ".." in filename
        ):
            raise SchemaValidationError(
                f"Manifest 'artifacts[{idx}].filename' must be a safe basename, got {filename!r}"
            )

        fmt = art["format"]
        if not isinstance(fmt, str) or fmt not in VALID_ARTIFACT_FORMATS:
            raise SchemaValidationError(
                f"Manifest 'artifacts[{idx}].format' invalid: {fmt!r}"
            )

        sha256 = art["sha256"]
        if not isinstance(sha256, str) or not _SHA256_RE.match(sha256):
            raise SchemaValidationError(
                f"Manifest 'artifacts[{idx}].sha256' must be 64 lowercase hex chars"
            )

        size_bytes = art["size_bytes"]
        if not _is_strict_int(size_bytes) or size_bytes < 1:
            raise SchemaValidationError(
                f"Manifest 'artifacts[{idx}].size_bytes' must be an integer >= 1"
            )

        estimated_ram_mb = art["estimated_ram_mb"]
        if not _is_strict_int(estimated_ram_mb) or estimated_ram_mb < 1:
            raise SchemaValidationError(
                f"Manifest 'artifacts[{idx}].estimated_ram_mb' must be an integer >= 1"
            )

        cadence = art["update_cadence"]
        if not isinstance(cadence, str) or cadence not in VALID_UPDATE_CADENCES:
            raise SchemaValidationError(
                f"Manifest 'artifacts[{idx}].update_cadence' invalid: {cadence!r}"
            )

        if "download_path" in art:
            dp = art["download_path"]
            if dp is not None and not isinstance(dp, str):
                raise SchemaValidationError(
                    f"Manifest 'artifacts[{idx}].download_path' must be string or null"
                )

        total_ram_mb += int(estimated_ram_mb)

    if total_ram_mb > ram_ceiling:
        raise SchemaValidationError(
            f"Total estimated artifact RAM ({total_ram_mb} MB) exceeds "
            f"foreground_ram_ceiling_mb ({ram_ceiling} MB)"
        )

    return obj


# ---------------------------------------------------------------------------
# Exact SPEC Reward Function & SingleSpeakerAudioEnv
# ---------------------------------------------------------------------------


def compute_audio_reward(
    metrics: AudioGateMetrics, *, human_chosen: bool = False
) -> float:
    """Computes the exact rectified SPEC reward for a discrete-token audio rollout.

    Hard pronunciation gate: any CER > 0.0 yields -2.0 regardless of voice similarity,
    DNSMOS quality, or human selection flag.
    """
    if not _is_strict_number(metrics.cer) or metrics.cer < 0.0:
        raise ValueError(f"CER must be a finite number >= 0.0, got {metrics.cer!r}")
    if not _is_strict_number(metrics.speaker_cosine_sim):
        raise ValueError(
            f"speaker_cosine_sim must be finite, got {metrics.speaker_cosine_sim!r}"
        )
    if not _is_strict_number(metrics.dnsmos_p808):
        raise ValueError(f"dnsmos_p808 must be finite, got {metrics.dnsmos_p808!r}")

    if metrics.cer > 0.0:
        return -2.0

    return (
        1.5 * float(metrics.speaker_cosine_sim)
        + 0.5 * (float(metrics.dnsmos_p808) / 5.0)
        + (1.0 if human_chosen else 0.0)
    )


class SingleSpeakerAudioEnv(_TinkerEnvBase):
    """Single-episode RL environment for discrete audio-token GRPO in tinker-cookbook."""

    def __init__(
        self,
        record: PreferenceRecord,
        *,
        verifier_fn: Callable[[Sequence[int], PreferenceRecord], AudioGateMetrics] | None = None,
        lexicon: Mapping[str, str] | None = None,
        stop_token_ids: Sequence[int] = (),
    ) -> None:
        self.record = record
        self.verifier_fn = verifier_fn
        self.lexicon = dict(lexicon) if lexicon is not None else {}
        self.stop_token_ids = [int(t) for t in stop_token_ids]

    @staticmethod
    def compute_reward(
        metrics: AudioGateMetrics, *, human_chosen: bool = False
    ) -> float:
        return compute_audio_reward(metrics, human_chosen=human_chosen)

    async def initial_observation(self) -> tuple[dict[str, Any], list[int]]:
        """Student rollout observation sees raw_text only (never teacher cadence hints)."""
        observation = {
            "clause_id": self.record.clause_id,
            "lora_version": self.record.lora_version,
            "raw_text": self.record.raw_text,
        }
        return observation, list(self.stop_token_ids)

    async def step(
        self,
        action: Sequence[int],
        *,
        extra: Mapping[str, Any] | None = None,
    ) -> Any:
        """Scores a single discrete audio-token sequence using real measured gate metrics."""
        token_tuple = tuple(int(t) for t in action)

        metrics: AudioGateMetrics | None = None
        if self.verifier_fn is not None:
            metrics = self.verifier_fn(token_tuple, self.record)
        elif extra is not None and isinstance(extra.get("metrics"), AudioGateMetrics):
            metrics = extra["metrics"]
        else:
            # Allow exact deterministic penalty lookup ONLY for logged CER > 0 gate failures.
            for failure in self.record.gate_failures:
                if failure.tokens == token_tuple and failure.cer > 0.0:
                    metrics = AudioGateMetrics(
                        cer=failure.cer,
                        speaker_cosine_sim=0.0,
                        dnsmos_p808=0.0,
                    )
                    break

        if metrics is None:
            raise RuntimeError(
                "SingleSpeakerAudioEnv.step() requires a real verifier_fn or measured "
                "AudioGateMetrics in extra['metrics']; refusing to fabricate verification metrics."
            )

        if extra is not None and "human_chosen" in extra:
            human_chosen = bool(extra["human_chosen"])
        else:
            human_chosen = (
                len(self.record.chosen_tokens) > 0
                and token_tuple == self.record.chosen_tokens
            )

        reward = compute_audio_reward(metrics, human_chosen=human_chosen)
        step_metrics = {
            "cer": float(metrics.cer),
            "speaker_cosine_sim": float(metrics.speaker_cosine_sim),
            "dnsmos_p808": float(metrics.dnsmos_p808),
            "human_chosen": 1.0 if human_chosen else 0.0,
            "reward": float(reward),
        }

        if TINKER_COOKBOOK_AVAILABLE:
            try:
                from tinker_cookbook.rl.types import StepResult  # type: ignore[import-not-found]
                import tinker  # type: ignore[import-not-found]

                return StepResult(
                    reward=reward,
                    episode_done=True,
                    next_observation=tinker.ModelInput.from_ints([]),
                    next_stop_condition=[],
                    metrics=step_metrics,
                )
            except Exception:
                pass

        return AudioStepResult(
            reward=reward,
            episode_done=True,
            next_observation={"tokens": []},
            next_stop_condition=[],
            metrics=step_metrics,
        )


# ---------------------------------------------------------------------------
# Deterministic OPSD + GRPO Dataset Builder
# ---------------------------------------------------------------------------


def load_lexicon(lexicon: Mapping[str, str] | Path | str | None) -> dict[str, str]:
    """Loads lexicon.json IPA phoneme overrides from a mapping or file path."""
    if lexicon is None:
        return {}
    if isinstance(lexicon, Mapping):
        return {str(k): str(v) for k, v in lexicon.items()}

    path = Path(lexicon)
    if not path.is_file():
        raise FileNotFoundError(f"lexicon.json not found: {path}")
    decoded = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(decoded, dict) and isinstance(decoded.get("overrides"), dict):
        decoded = decoded["overrides"]
    if not isinstance(decoded, dict):
        raise SchemaValidationError("lexicon.json must decode to a JSON object")
    return {str(k): str(v) for k, v in decoded.items()}


def _match_lexicon_overrides(raw_text: str, lexicon_map: Mapping[str, str]) -> dict[str, str]:
    """Deterministically selects lexicon IPA overrides matching words in raw_text."""
    if not lexicon_map:
        return {}
    lower_lookup = {k.lower(): (k, v) for k, v in lexicon_map.items()}
    matched: dict[str, str] = {}
    for word in _WORD_RE.findall(raw_text):
        hit = lower_lookup.get(word.lower())
        if hit is not None:
            canon_key, ipa = hit
            matched[canon_key] = ipa
    return dict(sorted(matched.items(), key=lambda kv: kv[0].lower()))


def _format_student_prompt(raw_text: str) -> str:
    """Student conditioning sees raw_text ONLY (no edited cadence or phoneme overrides)."""
    return f"<|text|>{raw_text}<|/text|><|audio|>"


def _format_teacher_prompt(
    raw_text: str,
    normalized_phonemes: str,
    user_gap_scale: float,
    lexicon_overrides: Mapping[str, str],
) -> str:
    """Teacher conditioning receives edited cadence (user_gap_scale) + lexicon IPA phonemes."""
    lexicon_str = (
        "; ".join(f"{word}={ipa}" for word, ipa in sorted(lexicon_overrides.items()))
        if lexicon_overrides
        else "none"
    )
    return (
        f"<|cadence_gap_scale|>{user_gap_scale:.4f}<|/cadence_gap_scale|>"
        f"<|lexicon|>{lexicon_str}<|/lexicon|>"
        f"<|phonemes|>{normalized_phonemes}<|/phonemes|>"
        f"<|text|>{raw_text}<|/text|><|audio|>"
    )


def build_opsd_and_grpo_dataset(
    records: Sequence[PreferenceRecord | Mapping[str, Any]],
    *,
    lexicon: Mapping[str, str] | Path | str | None = None,
) -> OpsdGrpoDataset:
    """Builds deterministic OPSD distillation pairs and GRPO preference/gate-failure groups.

    OPSD:
      - Teacher sees edited cadence (`user_gap_scale`) + `normalized_phonemes` +
        matched `lexicon.json` phoneme overrides.
      - Student sees `raw_text` ONLY.
    GRPO:
      - Groups human-chosen tokens, human-rejected tokens, and automated stage-B
        `gate_failures` (assigning exact -2.0 fixed reward when `cer > 0.0`).
    """
    validated: list[PreferenceRecord] = [
        rec if isinstance(rec, PreferenceRecord) else validate_preference_record(rec)
        for rec in records
    ]
    # Canonical deterministic ordering regardless of upload shard order.
    ordered = sorted(
        validated,
        key=lambda r: (
            r.timestamp,
            r.clause_id,
            r.lora_version,
            r.user_action,
            r.raw_text,
        ),
    )

    lexicon_map = load_lexicon(lexicon)
    opsd_pairs: list[OpsdPair] = []
    grpo_groups: list[GrpoGroup] = []

    for rec in ordered:
        matched_lexicon = _match_lexicon_overrides(rec.raw_text, lexicon_map)

        if rec.chosen_tokens:
            opsd_pairs.append(
                OpsdPair(
                    clause_id=rec.clause_id,
                    timestamp=rec.timestamp,
                    lora_version=rec.lora_version,
                    raw_text=rec.raw_text,
                    normalized_phonemes=rec.normalized_phonemes,
                    user_action=rec.user_action,
                    user_gap_scale=rec.user_gap_scale,
                    lexicon_overrides=matched_lexicon,
                    student_prompt=_format_student_prompt(rec.raw_text),
                    teacher_prompt=_format_teacher_prompt(
                        raw_text=rec.raw_text,
                        normalized_phonemes=rec.normalized_phonemes,
                        user_gap_scale=rec.user_gap_scale,
                        lexicon_overrides=matched_lexicon,
                    ),
                    target_tokens=rec.chosen_tokens,
                )
            )

        candidates: list[GrpoCandidate] = []
        pref_pairs: list[PreferencePair] = []

        if rec.chosen_tokens:
            candidates.append(
                GrpoCandidate(
                    role="chosen",
                    tokens=rec.chosen_tokens,
                    human_chosen=True,
                    is_gate_failure=False,
                    known_cer=0.0,
                )
            )

        if rec.rejected_tokens:
            candidates.append(
                GrpoCandidate(
                    role="rejected",
                    tokens=rec.rejected_tokens,
                    human_chosen=False,
                    is_gate_failure=False,
                )
            )
            if rec.chosen_tokens:
                pref_pairs.append(
                    PreferencePair(
                        chosen_tokens=rec.chosen_tokens,
                        rejected_tokens=rec.rejected_tokens,
                        source="human_take_swap",
                    )
                )

        # Deterministic ordering of gate failures by (seed, reason, cer, tokens)
        ordered_failures = sorted(
            rec.gate_failures,
            key=lambda gf: (gf.seed, gf.reason, gf.cer, gf.tokens),
        )
        for gf in ordered_failures:
            if not gf.tokens:
                continue
            fixed_reward = -2.0 if gf.cer > 0.0 else None
            candidates.append(
                GrpoCandidate(
                    role="gate_failure",
                    tokens=gf.tokens,
                    human_chosen=False,
                    is_gate_failure=True,
                    known_cer=gf.cer,
                    fixed_reward=fixed_reward,
                    failure_reason=gf.reason,
                    seed=gf.seed,
                )
            )
            if rec.chosen_tokens:
                pref_pairs.append(
                    PreferencePair(
                        chosen_tokens=rec.chosen_tokens,
                        rejected_tokens=gf.tokens,
                        source=f"gate_failure:{gf.reason}",
                    )
                )

        if len(candidates) >= 2 or any(c.is_gate_failure for c in candidates):
            grpo_groups.append(
                GrpoGroup(
                    clause_id=rec.clause_id,
                    timestamp=rec.timestamp,
                    lora_version=rec.lora_version,
                    raw_text=rec.raw_text,
                    normalized_phonemes=rec.normalized_phonemes,
                    user_action=rec.user_action,
                    user_gap_scale=rec.user_gap_scale,
                    candidates=tuple(candidates),
                    preference_pairs=tuple(pref_pairs),
                )
            )

    return OpsdGrpoDataset(
        opsd_pairs=tuple(opsd_pairs),
        grpo_groups=tuple(grpo_groups),
        record_count=len(ordered),
    )


# ---------------------------------------------------------------------------
# Nightly OPSD + GRPO LoRA Update & GGUF Export (Zero Fabrication)
# ---------------------------------------------------------------------------


def compute_file_sha256(path: Path | str, chunk_size: int = 1024 * 1024) -> str:
    """Computes real lowercase SHA-256 hex digest via streaming reads."""
    digest = hashlib.sha256()
    with Path(path).open("rb") as fh:
        while True:
            chunk = fh.read(chunk_size)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _require_module(module_name: str) -> Any:
    try:
        return importlib.import_module(module_name)
    except ImportError as exc:
        raise RuntimeError(
            f"Required training dependency '{module_name}' is not installed; "
            f"install optional '[train]' dependencies before running nightly LoRA updates "
            f"(refusing to fabricate LoRA weights)."
        ) from exc


def run_nightly_update(
    jsonl_path: Path | str,
    base_ckpt: Path | str,
    lora_out_dir: Path | str,
    next_version: int,
    llama_cpp_dir: Path | str,
    *,
    lexicon_path: Path | str | None = None,
    opsd_steps: int = OPSD_DEFAULT_STEPS,
    lora_rank: int = OPSD_LORA_RANK,
    opsd_lr: float = OPSD_LEARNING_RATE,
    grpo_steps: int = GRPO_DEFAULT_STEPS,
    grpo_kl_coef: float = GRPO_KL_COEF,
    outtype: str = "f16",
) -> NightlyUpdateResult:
    """Executes real Tinker Cookbook OPSD (10 steps, Rank-32, lr=1e-4) + GRPO (15 steps,
    kl_coef=0.04) LoRA fine-tuning and exports `voice_lora_vXXX.gguf` via
    `export_lora_gguf.sh`.

    Constitutional Law 2: If `preferences.jsonl`, `base_ckpt`, `llama.cpp/convert_lora_to_gguf.py`,
    `torch`, or `tinker_cookbook` is missing at runtime, raises an explicit `RuntimeError`
    immediately and NEVER writes dummy or placeholder `.gguf` weights.
    """
    if not _is_strict_int(next_version) or next_version < 0:
        raise ValueError(f"next_version must be an integer >= 0, got {next_version!r}")
    if not _is_strict_int(opsd_steps) or opsd_steps < 1:
        raise ValueError(f"opsd_steps must be >= 1, got {opsd_steps!r}")
    if not _is_strict_int(lora_rank) or lora_rank < 1:
        raise ValueError(f"lora_rank must be >= 1, got {lora_rank!r}")
    if not _is_strict_int(grpo_steps) or grpo_steps < 1:
        raise ValueError(f"grpo_steps must be >= 1, got {grpo_steps!r}")
    if not _is_strict_number(opsd_lr) or opsd_lr <= 0.0:
        raise ValueError(f"opsd_lr must be > 0, got {opsd_lr!r}")
    if not _is_strict_number(grpo_kl_coef) or grpo_kl_coef < 0.0:
        raise ValueError(f"grpo_kl_coef must be >= 0, got {grpo_kl_coef!r}")

    prefs_file = Path(jsonl_path)
    if not prefs_file.is_file():
        raise RuntimeError(
            f"preferences.jsonl not found at '{prefs_file}'; refusing to run empty update."
        )
    records = load_preferences_jsonl(prefs_file)
    if not records:
        raise RuntimeError(
            f"No preference records found in '{prefs_file}'; refusing to fabricate LoRA update."
        )

    base_ckpt_path = Path(base_ckpt)
    if not base_ckpt_path.exists():
        raise RuntimeError(
            f"Base s2-pro PyTorch checkpoint not found at '{base_ckpt_path}'; "
            f"refusing to fabricate LoRA weights."
        )

    llama_cpp_path = Path(llama_cpp_dir)
    converter_script = llama_cpp_path / "convert_lora_to_gguf.py"
    if not converter_script.is_file():
        raise RuntimeError(
            f"llama.cpp converter script missing at '{converter_script}'; "
            f"refusing to fabricate GGUF LoRA weights."
        )

    export_script = Path(__file__).resolve().parent / "export_lora_gguf.sh"
    if not export_script.is_file():
        raise RuntimeError(f"export_lora_gguf.sh not found at '{export_script}'")

    # Verify real ML runtime wheels BEFORE creating any output directories/artifacts.
    torch = _require_module("torch")
    tinker_cookbook = _require_module("tinker_cookbook")
    tinker = _require_module("tinker")

    dataset = build_opsd_and_grpo_dataset(records, lexicon=lexicon_path)
    if not dataset.opsd_pairs and not dataset.grpo_groups:
        raise RuntimeError(
            "Preference dataset yielded zero OPSD pairs and zero GRPO groups; "
            "refusing to train an empty LoRA adapter."
        )

    out_dir = Path(lora_out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    peft_adapter_dir = out_dir / f"peft_lora_v{next_version:03d}"
    target_gguf_path = out_dir / f"voice_lora_v{next_version:03d}.gguf"

    # Execute real Tinker training client: Stage A (OPSD) -> Stage B (GRPO)
    service_client_cls = getattr(tinker, "ServiceClient", None)
    if service_client_cls is None:
        raise RuntimeError(
            "Installed 'tinker' package does not expose ServiceClient; "
            "cannot run OPSD/GRPO training loop."
        )

    service_client = service_client_cls()
    training_client = service_client.create_lora_training_client(
        base_model=str(base_ckpt_path),
        rank=lora_rank,
    )
    tokenizer = training_client.get_tokenizer()

    # Stage A: On-Policy Self-Distillation (OPSD)
    # Teacher conditioned on edited cadence + lexicon IPA; student conditioned on raw_text.
    if dataset.opsd_pairs:
        adam_params = tinker.types.AdamParams(learning_rate=opsd_lr)
        for step_idx in range(opsd_steps):
            pair = dataset.opsd_pairs[step_idx % len(dataset.opsd_pairs)]
            student_ids = tokenizer.encode(pair.student_prompt)
            teacher_ids = tokenizer.encode(pair.teacher_prompt)
            target_ids = list(pair.target_tokens)

            student_seq = student_ids + target_ids
            teacher_seq = teacher_ids + target_ids

            student_weights = [0.0] * len(student_ids) + [1.0] * len(target_ids)
            datum = tinker.types.Datum(
                model_input=tinker.ModelInput.from_ints(student_seq[:-1]),
                loss_fn_inputs={
                    "target_tokens": student_seq[1:],
                    "weights": student_weights[1:],
                    "teacher_input_tokens": teacher_seq[:-1],
                },
            )
            fwdbwd_future = training_client.forward_backward(
                [datum], loss_fn="cross_entropy"
            )
            optim_future = training_client.optim_step(adam_params)
            fwdbwd_future.result()
            optim_future.result()

    # Stage B: Group Relative Policy Optimization (GRPO) with KL regularization
    if dataset.grpo_groups:
        grpo_adam = tinker.types.AdamParams(learning_rate=opsd_lr * 0.5)
        for step_idx in range(grpo_steps):
            group = dataset.grpo_groups[step_idx % len(dataset.grpo_groups)]
            prompt_ids = tokenizer.encode(_format_student_prompt(group.raw_text))
            group_rewards: list[float] = []
            group_seqs: list[tuple[list[int], int]] = []
            for cand in group.candidates:
                if cand.fixed_reward is not None:
                    reward = cand.fixed_reward
                elif cand.human_chosen:
                    reward = 1.0
                else:
                    reward = 0.0
                group_rewards.append(reward)
                group_seqs.append((prompt_ids + list(cand.tokens), len(prompt_ids)))

            mean_r = sum(group_rewards) / len(group_rewards)
            std_r = math.sqrt(
                sum((r - mean_r) ** 2 for r in group_rewards) / len(group_rewards)
            )
            denom = std_r if std_r > 1e-6 else 1.0

            grpo_data = []
            for (seq, prompt_len), reward in zip(group_seqs, group_rewards):
                advantage = (reward - mean_r) / denom
                adv_weights = [0.0] * prompt_len + [advantage] * (len(seq) - prompt_len)
                grpo_data.append(
                    tinker.types.Datum(
                        model_input=tinker.ModelInput.from_ints(seq[:-1]),
                        loss_fn_inputs={
                            "target_tokens": seq[1:],
                            "advantages": adv_weights[1:],
                            "kl_coef": [grpo_kl_coef] * (len(seq) - 1),
                        },
                    )
                )

            fwdbwd_future = training_client.forward_backward(
                grpo_data, loss_fn="importance_sampling"
            )
            optim_future = training_client.optim_step(grpo_adam)
            fwdbwd_future.result()
            optim_future.result()

    # Persist real trained PEFT LoRA checkpoint
    peft_adapter_dir.mkdir(parents=True, exist_ok=True)
    save_fn = getattr(training_client, "save_weights_for_sampler", None) or getattr(
        training_client, "save_state", None
    )
    if save_fn is None:
        raise RuntimeError(
            f"tinker training client ({type(training_client).__name__}) exposes neither "
            f"save_weights_for_sampler nor save_state; cannot export PEFT adapter."
        )
    save_future = save_fn(name=str(peft_adapter_dir))
    if hasattr(save_future, "result"):
        save_future.result()

    # Keep torch/tinker_cookbook explicitly referenced for static analyzers
    _ = (torch.__version__, getattr(tinker_cookbook, "__name__", "tinker_cookbook"))

    # Invoke shell wrapper to convert PEFT LoRA -> GGUF (~35 MB Rank-32 adapter)
    subprocess.run(
        [
            str(export_script),
            "--adapter-dir",
            str(peft_adapter_dir),
            "--base-model",
            str(base_ckpt_path),
            "--llama-cpp-dir",
            str(llama_cpp_path),
            "--output",
            str(target_gguf_path),
            "--outtype",
            outtype,
            "--python",
            sys.executable,
        ],
        check=True,
    )

    if not target_gguf_path.is_file() or target_gguf_path.stat().st_size == 0:
        raise RuntimeError(f"Expected exported GGUF file missing or empty: {target_gguf_path}")

    with target_gguf_path.open("rb") as fh:
        if fh.read(4) != b"GGUF":
            raise RuntimeError(f"Exported file lacks valid GGUF magic header: {target_gguf_path}")

    return NightlyUpdateResult(
        lora_version=next_version,
        lora_gguf_path=target_gguf_path,
        sha256=compute_file_sha256(target_gguf_path),
        size_bytes=target_gguf_path.stat().st_size,
        opsd_steps=opsd_steps,
        grpo_steps=grpo_steps,
        record_count=len(records),
    )


# ---------------------------------------------------------------------------
# OTA Bundle Manifest Builder
# ---------------------------------------------------------------------------


def _classify_artifact(
    filename: str,
    target_lora_version: int,
) -> tuple[str, str, int, str] | None:
    """Maps a bundle artifact filename to (role, format, default_ram_mb, update_cadence).

    Returns None for non-artifact metadata files (e.g. bundle_manifest.json, .tmp files)
    or older LoRA versions not matching target_lora_version.
    """
    if filename == "bundle_manifest.json" or filename.startswith(".") or filename.endswith(".tmp"):
        return None

    lower = filename.lower()

    lora_match = _LORA_FILENAME_RE.match(lower)
    if lora_match:
        file_ver = int(lora_match.group(1))
        if file_ver != target_lora_version:
            return None
        return ("voice_lora_gguf", "gguf", 64, "nightly_delta")

    if lower.endswith(".gguf"):
        if "lora" in lower:
            return None
        return ("s2_pro_base_gguf", "gguf", 3200, "static_once")

    if lower.endswith(".tflite"):
        return ("codec_decoder_tflite", "tflite", 420, "static_once")

    if "sherpa" in lower and lower.endswith(".tar.gz"):
        return ("sherpa_ctc_aligner", "tar_gz", 160, "static_once")
    if "sherpa" in lower and lower.endswith(".onnx"):
        return ("sherpa_ctc_aligner", "onnx", 160, "static_once")

    if "wespeaker" in lower and lower.endswith(".onnx"):
        return ("wespeaker_resnet34_onnx", "onnx", 80, "static_once")

    if "dnsmos" in lower and lower.endswith(".onnx"):
        return ("dnsmos_p808_onnx", "onnx", 24, "static_once")

    if "centroid" in lower and lower.endswith(".bin"):
        return ("master_centroid_bin", "bin", 1, "static_once")

    if "room_ir" in lower and lower.endswith(".pcm"):
        return ("room_ir_pcm", "pcm", 4, "static_once")

    if "room_tone" in lower and lower.endswith(".pcm"):
        return ("room_tone_pcm", "pcm", 8, "static_once")

    if lower == "lexicon.json" or ("lexicon" in lower and lower.endswith(".json")):
        return ("g2p_lexicon", "json", 4, "static_once")

    return None


def build_bundle_manifest(
    bundle_dir: Path | str,
    bundle_version: str,
    lora_version: int,
    *,
    created_at_utc: str | None = None,
    sample_rate_hz: int = SAMPLE_RATE_HZ,
    foreground_ram_ceiling_mb: int = FOREGROUND_RAM_CEILING_MB,
    artifact_ram_overrides: Mapping[str, int] | None = None,
    download_path_prefix: str | None = None,
) -> dict[str, Any]:
    """Scans bundle_dir, computes real SHA-256 digests, enforces 44.1 kHz & <=9500 MB
    RAM ceiling, validates against ota_bundle_manifest.schema.json, and atomically
    writes bundle_manifest.json.
    """
    bdir = Path(bundle_dir)
    if not bdir.is_dir():
        raise FileNotFoundError(f"Bundle directory does not exist: {bdir}")

    if not isinstance(bundle_version, str) or not bundle_version.strip():
        raise ValueError("bundle_version must be a non-empty string")
    if not _is_strict_int(lora_version) or lora_version < 0:
        raise ValueError(f"lora_version must be an integer >= 0, got {lora_version!r}")
    if not _is_strict_int(sample_rate_hz) or sample_rate_hz != SAMPLE_RATE_HZ:
        raise ValueError(
            f"sample_rate_hz must be strictly {SAMPLE_RATE_HZ} Hz (44.1 kHz); got {sample_rate_hz!r}"
        )
    if (
        not _is_strict_int(foreground_ram_ceiling_mb)
        or foreground_ram_ceiling_mb < MIN_FOREGROUND_RAM_CEILING_MB
        or foreground_ram_ceiling_mb > FOREGROUND_RAM_CEILING_MB
    ):
        raise ValueError(
            f"foreground_ram_ceiling_mb must be within "
            f"[{MIN_FOREGROUND_RAM_CEILING_MB}, {FOREGROUND_RAM_CEILING_MB}], "
            f"got {foreground_ram_ceiling_mb!r}"
        )

    ram_overrides = dict(artifact_ram_overrides) if artifact_ram_overrides else {}
    prefix = (
        download_path_prefix.rstrip("/")
        if download_path_prefix is not None
        else f"/v1/bundles/{bundle_version}"
    )

    artifacts: list[dict[str, Any]] = []
    seen_roles: set[str] = set()

    for entry in sorted(bdir.iterdir(), key=lambda p: p.name):
        if not entry.is_file():
            continue
        classified = _classify_artifact(entry.name, lora_version)
        if classified is None:
            continue

        role, fmt, default_ram_mb, cadence = classified
        if role in seen_roles:
            raise ValueError(
                f"Duplicate artifact role '{role}' detected in bundle directory '{bdir}' ({entry.name})"
            )
        seen_roles.add(role)

        size_bytes = entry.stat().st_size
        if size_bytes < 1:
            raise ValueError(f"Bundle artifact '{entry.name}' is empty (0 bytes)")

        est_ram_mb = ram_overrides.get(
            entry.name, ram_overrides.get(role, default_ram_mb)
        )
        if not _is_strict_int(est_ram_mb) or est_ram_mb < 1:
            raise ValueError(
                f"estimated_ram_mb for '{entry.name}' must be an integer >= 1, got {est_ram_mb!r}"
            )

        artifacts.append(
            {
                "role": role,
                "filename": entry.name,
                "format": fmt,
                "sha256": compute_file_sha256(entry),
                "size_bytes": size_bytes,
                "estimated_ram_mb": int(est_ram_mb),
                "update_cadence": cadence,
                "download_path": f"{prefix}/{entry.name}",
            }
        )

    if not artifacts:
        raise ValueError(f"No recognized VoiceStudio OTA artifacts found in '{bdir}'")

    total_ram_mb = sum(a["estimated_ram_mb"] for a in artifacts)
    if total_ram_mb > foreground_ram_ceiling_mb or total_ram_mb > FOREGROUND_RAM_CEILING_MB:
        raise ValueError(
            f"Bundle RAM budget exceeded: {total_ram_mb} MB > "
            f"{min(foreground_ram_ceiling_mb, FOREGROUND_RAM_CEILING_MB)} MB ceiling"
        )

    timestamp_utc = (
        created_at_utc
        if created_at_utc is not None
        else datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )

    manifest: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "bundle_version": bundle_version,
        "lora_version": lora_version,
        "created_at_utc": timestamp_utc,
        "sample_rate_hz": sample_rate_hz,
        "foreground_ram_ceiling_mb": foreground_ram_ceiling_mb,
        "artifacts": artifacts,
    }
    validate_bundle_manifest(manifest)

    manifest_path = bdir / "bundle_manifest.json"
    tmp_path = bdir / f".bundle_manifest.json.tmp.{os.getpid()}"
    encoded = json.dumps(manifest, indent=2, sort_keys=False) + "\n"
    try:
        with tmp_path.open("w", encoding="utf-8") as fh:
            fh.write(encoded)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp_path, manifest_path)
    finally:
        if tmp_path.exists():
            tmp_path.unlink(missing_ok=True)

    return manifest


# ---------------------------------------------------------------------------
# CLI Entrypoint
# ---------------------------------------------------------------------------


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="VoiceStudio Tinker Cookbook nightly LoRA updater & OTA manifest tool"
    )
    sub = parser.add_subparsers(dest="command", required=True)

    train_p = sub.add_parser("nightly-update", help="Run OPSD + GRPO LoRA update and export GGUF")
    train_p.add_argument("--preferences", required=True, help="Path to preferences.jsonl")
    train_p.add_argument("--base-ckpt", required=True, help="Path to s2-pro PyTorch base checkpoint")
    train_p.add_argument("--lora-out-dir", required=True, help="Output directory for voice_lora_vXXX.gguf")
    train_p.add_argument("--next-version", type=int, required=True, help="Next integer LoRA version")
    train_p.add_argument("--llama-cpp-dir", required=True, help="Path to llama.cpp checkout")
    train_p.add_argument("--lexicon", default=None, help="Optional path to lexicon.json")
    train_p.add_argument("--opsd-steps", type=int, default=OPSD_DEFAULT_STEPS)
    train_p.add_argument("--lora-rank", type=int, default=OPSD_LORA_RANK)
    train_p.add_argument("--opsd-lr", type=float, default=OPSD_LEARNING_RATE)
    train_p.add_argument("--grpo-steps", type=int, default=GRPO_DEFAULT_STEPS)
    train_p.add_argument("--grpo-kl-coef", type=float, default=GRPO_KL_COEF)

    manifest_p = sub.add_parser("build-manifest", help="Generate verified bundle_manifest.json")
    manifest_p.add_argument("--bundle-dir", required=True, help="Directory holding OTA artifacts")
    manifest_p.add_argument("--bundle-version", required=True, help="Bundle version string")
    manifest_p.add_argument("--lora-version", type=int, required=True, help="Active LoRA version int")

    args = parser.parse_args(argv)
    if args.command == "nightly-update":
        result = run_nightly_update(
            jsonl_path=args.preferences,
            base_ckpt=args.base_ckpt,
            lora_out_dir=args.lora_out_dir,
            next_version=args.next_version,
            llama_cpp_dir=args.llama_cpp_dir,
            lexicon_path=args.lexicon,
            opsd_steps=args.opsd_steps,
            lora_rank=args.lora_rank,
            opsd_lr=args.opsd_lr,
            grpo_steps=args.grpo_steps,
            grpo_kl_coef=args.grpo_kl_coef,
        )
        print(
            json.dumps(
                {
                    "lora_version": result.lora_version,
                    "lora_gguf_path": str(result.lora_gguf_path),
                    "sha256": result.sha256,
                    "size_bytes": result.size_bytes,
                    "opsd_steps": result.opsd_steps,
                    "grpo_steps": result.grpo_steps,
                    "record_count": result.record_count,
                },
                indent=2,
            )
        )
        return 0

    if args.command == "build-manifest":
        manifest = build_bundle_manifest(
            bundle_dir=args.bundle_dir,
            bundle_version=args.bundle_version,
            lora_version=args.lora_version,
        )
        print(json.dumps(manifest, indent=2))
        return 0

    return 2


if __name__ == "__main__":
    raise SystemExit(main())
