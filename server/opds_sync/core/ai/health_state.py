"""In-memory holder for AI-provider and retrieval-source reachability state.

Process-local. Multi-replica observability is out of scope per the project's
"in-process lock stays" non-goal.

Tri-state semantics
-------------------
Every reachability flag is `None | bool`:

* ``None``  → never observed. ``last_checked_at`` MUST also be None.
* ``True``  → last network call succeeded. ``last_checked_at`` MUST be set.
* ``False`` → last network call failed. ``last_checked_at``,
              ``last_failure_at``, and ``last_failure_class`` MUST be set.

A success-after-failure clears ``last_failure_at`` and ``last_failure_class``
to None — the holder reflects the CURRENT state, not history. Historical
failures live in ``ai_generation_log`` (PR-C) and structured warning logs.

Concurrency
-----------
FastAPI handlers run on a single asyncio event loop per worker. Single-attribute
writes are technically safe under the GIL, but a coherent multi-field snapshot
requires a critical section. We use ``asyncio.Lock`` to guard both writers and
reads. The lock is uncontended in practice (one writer per AI call), and
snapshots are O(few fields), so the cost is negligible.

Process restart
---------------
All fields reset to None. The endpoint's tri-state contract surfaces this
faithfully as "not yet checked" until the first real call lands.
"""

from __future__ import annotations

import asyncio
from copy import copy
from dataclasses import dataclass, field
from datetime import UTC, datetime


@dataclass
class RetrievalSourceState:
    """Reachability state for one named retrieval source (wikipedia, openlibrary, ...)."""

    reachable: bool | None = None
    last_checked_at: datetime | None = None


@dataclass
class AiHealthSnapshot:
    """Point-in-time copy of ``AiHealthState``. Safe to mutate by callers."""

    provider_reachable: bool | None = None
    provider_last_checked_at: datetime | None = None
    model_id: str | None = None
    last_failure_at: datetime | None = None
    last_failure_class: str | None = None
    retrieval_sources: dict[str, RetrievalSourceState] = field(default_factory=dict)


class AiHealthState:
    """Mutable, async-safe holder for the most recently observed AI reachability state.

    Public API is intentionally tiny: three writers (provider success, provider
    failure, retrieval) and a snapshot reader. All methods are coroutines so
    callers can compose them with other async work without thinking about
    sync-vs-async barriers.
    """

    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._provider_reachable: bool | None = None
        self._provider_last_checked_at: datetime | None = None
        self._model_id: str | None = None
        self._last_failure_at: datetime | None = None
        self._last_failure_class: str | None = None
        self._retrieval: dict[str, RetrievalSourceState] = {}

    async def record_provider_success(self, *, model_id: str) -> None:
        """Record a successful provider chat completion.

        Sets ``provider_reachable=True`` and ``model_id``. Clears the failure
        fields — the holder reflects current state, not history.
        """
        now = datetime.now(UTC)
        async with self._lock:
            self._provider_reachable = True
            self._provider_last_checked_at = now
            self._model_id = model_id
            self._last_failure_at = None
            self._last_failure_class = None

    async def record_provider_failure(self, *, error_class: str) -> None:
        """Record a failed provider chat completion.

        Sets ``provider_reachable=False``, ``provider_last_checked_at``,
        ``last_failure_at``, and ``last_failure_class``. ``model_id`` is
        preserved from any prior success (gives the operator "last seen
        working model" context).
        """
        now = datetime.now(UTC)
        async with self._lock:
            self._provider_reachable = False
            self._provider_last_checked_at = now
            self._last_failure_at = now
            self._last_failure_class = error_class

    async def record_retrieval(self, *, name: str, success: bool) -> None:
        """Record the outcome of one retrieval-source HTTP call.

        ``name`` is one of the canonical lowercase identifiers used by the
        orchestrator (``"wikipedia"``, ``"openlibrary"``). Callers pass bare
        string literals — no config-driven naming, so a typo in ``ai_sources``
        cannot leak into the health response.
        """
        now = datetime.now(UTC)
        async with self._lock:
            self._retrieval[name] = RetrievalSourceState(
                reachable=success,
                last_checked_at=now,
            )

    async def snapshot(self) -> AiHealthSnapshot:
        """Return an independent copy of the current state.

        The returned object can be freely mutated by the caller; subsequent
        writes to the holder will not affect it, and vice versa.
        """
        async with self._lock:
            return AiHealthSnapshot(
                provider_reachable=self._provider_reachable,
                provider_last_checked_at=self._provider_last_checked_at,
                model_id=self._model_id,
                last_failure_at=self._last_failure_at,
                last_failure_class=self._last_failure_class,
                retrieval_sources={name: copy(state) for name, state in self._retrieval.items()},
            )
