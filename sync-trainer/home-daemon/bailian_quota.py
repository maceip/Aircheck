"""Shared Bailian (`bl`) HTTP 429 quota cooldown state for LLM and image generation.

Records ONLY real observed Bailian 429 quota-exhaustion responses (e.g.
``insufficient_quota`` / ``Throttling.AllocationQuota``) along with the exact reset
timestamp returned by Bailian, so:
  - ``GET /v1/llm/providers`` reports ``remote_bailian`` as ``available: false`` with
    the real reset time instead of falsely reporting ``available: true``.
  - ``studio_cut._generate_image_into()`` short-circuits directly to OpenAI image
    generation when ``OPENAI_API_KEY`` is configured instead of burning ~1.8 s on
    every call hitting a known-exhausted Bailian quota.
  - Once the observed reset timestamp passes (or any Bailian call succeeds), the
    cooldown clears automatically.
"""

from __future__ import annotations

import datetime as dt
import json
import os
import re
import time
from pathlib import Path
from typing import Any

DEFAULT_BAILIAN_QUOTA_STATE_PATH = Path.home() / ".bailian" / "aircheck_quota_state.json"
DEFAULT_FALLBACK_COOLDOWN_SEC = 900  # 15 minutes when a 429 omits a reset timestamp

_QUOTA_HINT_RE = re.compile(
    r"insufficient_quota|Throttling\.AllocationQuota|quota has been exhausted|\"http_status\"\s*:\s*429",
    re.IGNORECASE,
)
_RESET_UTC_RE = re.compile(
    r"reset at\s+(?:(?P<year>\d{4})-)?(?P<month>\d{2})-(?P<day>\d{2})\s+"
    r"(?P<hour>\d{2}):(?P<minute>\d{2}):(?P<second>\d{2})\s*UTC",
    re.IGNORECASE,
)


def default_quota_state_path() -> Path:
    override = os.environ.get("AIRCHECK_BAILIAN_QUOTA_FILE", "").strip()
    if override:
        return Path(override)
    return DEFAULT_BAILIAN_QUOTA_STATE_PATH


def parse_bailian_quota_error(
    error_text: str,
    *,
    now_ts: float | None = None,
) -> tuple[bool, float | None, str | None]:
    """Detects a real Bailian 429 quota error and extracts its reset UTC timestamp."""
    raw = str(error_text or "")
    if not _QUOTA_HINT_RE.search(raw):
        return (False, None, None)

    now = time.time() if now_ts is None else float(now_ts)
    m = _RESET_UTC_RE.search(raw)
    if m:
        now_dt = dt.datetime.fromtimestamp(now, tz=dt.timezone.utc)
        year = int(m.group("year")) if m.group("year") else now_dt.year
        month = int(m.group("month"))
        day = int(m.group("day"))
        hour = int(m.group("hour"))
        minute = int(m.group("minute"))
        second = int(m.group("second"))
        try:
            reset_dt = dt.datetime(year, month, day, hour, minute, second, tzinfo=dt.timezone.utc)
            if not m.group("year") and (now_dt - reset_dt).total_seconds() > 86400:
                reset_dt = dt.datetime(year + 1, month, day, hour, minute, second, tzinfo=dt.timezone.utc)
            label = reset_dt.strftime("%Y-%m-%d %H:%M:%S UTC")
            return (True, reset_dt.timestamp(), label)
        except ValueError:
            pass

    fallback_ts = now + DEFAULT_FALLBACK_COOLDOWN_SEC
    label = dt.datetime.fromtimestamp(fallback_ts, tz=dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    return (True, fallback_ts, label)


def record_bailian_quota_exhausted(
    error_text: str,
    *,
    state_path: Path | None = None,
    now_ts: float | None = None,
) -> dict[str, Any] | None:
    """Persists an observed Bailian 429 quota block atomically; no-op for non-quota errors."""
    is_quota, until_ts, label = parse_bailian_quota_error(error_text, now_ts=now_ts)
    if not is_quota or until_ts is None:
        return None
    target = state_path or default_quota_state_path()
    now = time.time() if now_ts is None else float(now_ts)
    record = {
        "exhausted_until_ts": round(until_ts, 3),
        "reset_label": label or "",
        "detail": f"Bailian quota exhausted (HTTP 429) until {label}",
        "raw_error": " ".join(str(error_text or "").split())[:400],
        "recorded_at_ts": round(now, 3),
    }
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        tmp = target.with_suffix(".tmp")
        tmp.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
        os.replace(tmp, target)
    except OSError:
        pass
    return record


def clear_bailian_quota_state(*, state_path: Path | None = None) -> None:
    target = state_path or default_quota_state_path()
    try:
        if target.exists():
            target.unlink()
    except OSError:
        pass


def active_bailian_quota_block(
    *,
    state_path: Path | None = None,
    now_ts: float | None = None,
) -> dict[str, Any] | None:
    """Returns the persisted 429 quota block if still active, else None."""
    target = state_path or default_quota_state_path()
    if not target.is_file():
        return None
    try:
        data = json.loads(target.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(data, dict):
        return None
    try:
        until_ts = float(data.get("exhausted_until_ts") or 0.0)
    except (TypeError, ValueError):
        return None
    now = time.time() if now_ts is None else float(now_ts)
    if now >= until_ts:
        return None
    return data
