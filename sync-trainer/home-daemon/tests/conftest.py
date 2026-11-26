"""Shared test guards."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


@pytest.fixture(autouse=True)
def _never_call_real_heygen(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    """Tests must never upload to or bill the real HeyGen account; tests that need it install a fake."""
    import generate_talking_head as gth

    monkeypatch.setattr(gth, "HEYGEN_BIN", tmp_path / "heygen-not-installed-in-tests")
