from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from typing import Callable


@dataclass(frozen=True)
class MediaStamp:
    session_id: str
    utterance_id: str
    generation: int
    sample_index: int
    sample_rate: int

    @property
    def presentation_time_ns(self) -> int:
        if self.sample_index < 0 or self.sample_rate <= 0:
            raise ValueError("Invalid media sample clock")
        return self.sample_index * 1_000_000_000 // self.sample_rate


class MediaSession:
    """Cancellation generations prevent late work from a prior utterance being played."""

    def __init__(self, session_id: str, clock: Callable[[], int] = time.monotonic_ns) -> None:
        if not session_id:
            raise ValueError("session_id must not be empty")
        self.session_id = session_id
        self._clock = clock
        self._origin = clock()
        self._generation = 0
        self._utterance = ""
        self._lock = threading.Lock()

    def begin(self, utterance_id: str) -> int:
        if not utterance_id:
            raise ValueError("utterance_id must not be empty")
        with self._lock:
            self._generation += 1
            self._utterance = utterance_id
            return self._generation

    def cancel(self) -> None:
        with self._lock:
            self._generation += 1
            self._utterance = ""

    def accepts(self, stamp: MediaStamp) -> bool:
        with self._lock:
            return bool(self._utterance) and (stamp.session_id, stamp.utterance_id, stamp.generation) == (self.session_id, self._utterance, self._generation)

    def elapsed_ns(self) -> int:
        return max(0, self._clock() - self._origin)
