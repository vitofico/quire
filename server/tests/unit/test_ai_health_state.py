"""Unit tests for opds_sync.core.ai.health_state.

These tests pin down the tri-state contract exposed by ``AiHealthState`` so
the endpoint, the orchestrator, and the Android UI all share one definition
of what each combination of fields means.
"""

from __future__ import annotations

import asyncio

import pytest

from opds_sync.core.ai.health_state import AiHealthState

pytestmark = pytest.mark.asyncio


async def test_fresh_state_is_all_null() -> None:
    state = AiHealthState()
    snap = await state.snapshot()
    assert snap.provider_reachable is None
    assert snap.provider_last_checked_at is None
    assert snap.model_id is None
    assert snap.last_failure_at is None
    assert snap.last_failure_class is None
    assert snap.retrieval_sources == {}


async def test_record_provider_success_populates_fields() -> None:
    state = AiHealthState()
    await state.record_provider_success(model_id="llama3.1:8b")
    snap = await state.snapshot()
    assert snap.provider_reachable is True
    assert snap.provider_last_checked_at is not None
    assert snap.model_id == "llama3.1:8b"
    # Success clears failure fields (contract: holder reflects CURRENT state).
    assert snap.last_failure_at is None
    assert snap.last_failure_class is None


async def test_record_provider_failure_on_fresh_state() -> None:
    """A first observed failure must produce coherent state.

    ``provider_reachable=False`` MUST come with both timestamps and the error
    class — never ``False + null`` for ``provider_last_checked_at``.
    """
    state = AiHealthState()
    await state.record_provider_failure(error_class="ProviderTimeout")
    snap = await state.snapshot()
    assert snap.provider_reachable is False
    assert snap.provider_last_checked_at is not None
    assert snap.last_failure_at is not None
    assert snap.last_failure_class == "ProviderTimeout"
    # No prior success: model_id stays null. The operator hasn't seen the
    # provider work yet.
    assert snap.model_id is None


async def test_record_provider_failure_preserves_prior_model_id() -> None:
    """After a success, a subsequent failure preserves the last-seen model."""
    state = AiHealthState()
    await state.record_provider_success(model_id="llama3.1:8b")
    await state.record_provider_failure(error_class="ProviderUnreachable")
    snap = await state.snapshot()
    assert snap.provider_reachable is False
    assert snap.model_id == "llama3.1:8b"
    assert snap.last_failure_class == "ProviderUnreachable"


async def test_recovery_clears_failure_fields() -> None:
    """A success after a failure clears the failure fields.

    The contract is "current state, not history". Historical failures live
    in ai_generation_log and structured logs.
    """
    state = AiHealthState()
    await state.record_provider_failure(error_class="ProviderTimeout")
    await state.record_provider_success(model_id="llama3.1:8b")
    snap = await state.snapshot()
    assert snap.provider_reachable is True
    assert snap.last_failure_at is None
    assert snap.last_failure_class is None
    assert snap.model_id == "llama3.1:8b"


async def test_record_retrieval_creates_entry() -> None:
    state = AiHealthState()
    await state.record_retrieval(name="wikipedia", success=True)
    snap = await state.snapshot()
    assert "wikipedia" in snap.retrieval_sources
    wiki = snap.retrieval_sources["wikipedia"]
    assert wiki.reachable is True
    assert wiki.last_checked_at is not None


async def test_record_retrieval_failure() -> None:
    state = AiHealthState()
    await state.record_retrieval(name="openlibrary", success=False)
    snap = await state.snapshot()
    ol = snap.retrieval_sources["openlibrary"]
    assert ol.reachable is False
    assert ol.last_checked_at is not None


async def test_multiple_retrieval_sources_are_independent() -> None:
    state = AiHealthState()
    await state.record_retrieval(name="wikipedia", success=True)
    await state.record_retrieval(name="openlibrary", success=False)
    snap = await state.snapshot()
    assert snap.retrieval_sources["wikipedia"].reachable is True
    assert snap.retrieval_sources["openlibrary"].reachable is False


async def test_retrieval_update_replaces_prior_state() -> None:
    """A second record_retrieval for the same name overwrites the prior entry."""
    state = AiHealthState()
    await state.record_retrieval(name="wikipedia", success=False)
    snap1 = await state.snapshot()
    first_ts = snap1.retrieval_sources["wikipedia"].last_checked_at
    # Sleep a hair so the timestamp can advance even on fast machines.
    await asyncio.sleep(0.001)
    await state.record_retrieval(name="wikipedia", success=True)
    snap2 = await state.snapshot()
    second = snap2.retrieval_sources["wikipedia"]
    assert second.reachable is True
    assert second.last_checked_at is not None
    assert second.last_checked_at >= first_ts


async def test_snapshot_is_independent_copy() -> None:
    """Mutating a snapshot must not affect the holder, and vice versa."""
    state = AiHealthState()
    await state.record_provider_success(model_id="m1")
    await state.record_retrieval(name="wikipedia", success=True)
    snap = await state.snapshot()

    # Mutate the snapshot.
    snap.provider_reachable = False
    snap.retrieval_sources["wikipedia"].reachable = False
    snap.retrieval_sources["injected"] = snap.retrieval_sources["wikipedia"]

    # Holder unaffected.
    snap2 = await state.snapshot()
    assert snap2.provider_reachable is True
    assert snap2.retrieval_sources["wikipedia"].reachable is True
    assert "injected" not in snap2.retrieval_sources


async def test_concurrent_writers_preserve_tri_state_invariants() -> None:
    """Hammer the holder from many coroutines.

    After 1000 mixed writes the holder must still satisfy:
      - provider_reachable in {None, True, False}
      - if True: provider_last_checked_at is not None and last_failure_* are None
      - if False: provider_last_checked_at, last_failure_at, last_failure_class all set
    """
    state = AiHealthState()

    async def successes() -> None:
        for _ in range(500):
            await state.record_provider_success(model_id="m")

    async def failures() -> None:
        for _ in range(500):
            await state.record_provider_failure(error_class="ProviderUnreachable")

    async def retrievals() -> None:
        for i in range(1000):
            await state.record_retrieval(
                name="wikipedia" if i % 2 == 0 else "openlibrary",
                success=bool(i % 3),
            )

    await asyncio.gather(successes(), failures(), retrievals())

    snap = await state.snapshot()
    assert snap.provider_reachable in (True, False)
    if snap.provider_reachable is True:
        assert snap.provider_last_checked_at is not None
        assert snap.last_failure_at is None
        assert snap.last_failure_class is None
    else:
        assert snap.provider_last_checked_at is not None
        assert snap.last_failure_at is not None
        assert snap.last_failure_class is not None

    # Both retrieval sources should be present.
    assert set(snap.retrieval_sources.keys()) == {"wikipedia", "openlibrary"}
    for source in snap.retrieval_sources.values():
        assert source.reachable in (True, False)
        assert source.last_checked_at is not None
