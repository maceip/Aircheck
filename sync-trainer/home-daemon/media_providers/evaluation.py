from __future__ import annotations

import math
from typing import Sequence


def compare_contours(reference: Sequence[float], candidate: Sequence[float]) -> dict[str, float | int | None]:
    """Compare pre-aligned energy or voiced-F0 measurements, not raw audio quality."""
    left, right = list(reference), list(candidate)
    if not left or len(left) != len(right):
        raise ValueError("Contours must be nonempty and pre-aligned with equal lengths")
    if not all(math.isfinite(x) for x in left + right):
        raise ValueError("Contour values must be finite")
    n = len(left)
    rmse = math.sqrt(sum((a - b) ** 2 for a, b in zip(left, right)) / n)
    mean_left, mean_right = sum(left) / n, sum(right) / n
    centered_left = [x - mean_left for x in left]
    centered_right = [x - mean_right for x in right]
    denominator = math.sqrt(sum(x * x for x in centered_left) * sum(x * x for x in centered_right))
    correlation = sum(a * b for a, b in zip(centered_left, centered_right)) / denominator if denominator else None
    return {"samples": n, "rmse": rmse, "pearson": None if correlation is None else max(-1.0, min(1.0, correlation))}


def timing_metrics(*, elapsed_seconds: float, audio_seconds: float) -> dict[str, float]:
    if not math.isfinite(elapsed_seconds) or not math.isfinite(audio_seconds) or elapsed_seconds < 0 or audio_seconds <= 0:
        raise ValueError("Timing measurements must be finite with positive audio duration")
    return {"elapsed_seconds": elapsed_seconds, "audio_seconds": audio_seconds, "real_time_factor": elapsed_seconds / audio_seconds}
