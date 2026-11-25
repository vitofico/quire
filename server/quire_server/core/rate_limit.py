"""A per-reader requests-per-minute ceiling, kept in this process."""

from __future__ import annotations

import time
from collections import deque


class RateLimit:
    """Requests each reader may make per minute, counted over the last 60 s
    in this process. Readers idle for a minute are forgotten once more than
    ``max_readers`` are tracked, so the table stays bounded."""

    def __init__(self, *, max_readers: int = 10_000) -> None:
        self._seen: dict[str, deque[float]] = {}
        self._max_readers = max_readers

    def retry_after(self, subject: str, per_minute: int) -> int | None:
        """None when this request is allowed, else the seconds to wait."""
        now = time.monotonic()
        if subject not in self._seen and len(self._seen) >= self._max_readers:
            self._forget_idle(now)
        seen = self._seen.setdefault(subject, deque())
        while seen and seen[0] <= now - 60:
            seen.popleft()
        if len(seen) >= max(per_minute, 1):
            return max(1, int(seen[0] + 60 - now) + 1)
        seen.append(now)
        return None

    def _forget_idle(self, now: float) -> None:
        for subject in [s for s, seen in self._seen.items() if not seen or seen[-1] <= now - 60]:
            del self._seen[subject]

    def __len__(self) -> int:
        return len(self._seen)
