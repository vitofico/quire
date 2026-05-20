"""Multi-tenant convergence + concurrent-coalesce: integration tests using
SEPARATE AsyncSession instances per waiter.

Two tests:
1. Sequential two-tenant convergence — cross-session FK visibility (tenant B's
   session must see the committed book_insights row from tenant A's session).
2. Concurrent N-waiter coalesce — N waiters with separate sessions race for
   the per-identity lock; only one model call fires; N log rows are produced.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

import pytest
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from quire_server.api.ai_schemas import DocumentIdentity, MetadataBundle
from quire_server.core.ai.service import InsightOrchestrator
from quire_server.db.models import AIGenerationLog, BookInsight


class _FakeAIClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []
        self.next_payload = {
            "schema_version": 2,
            "intro": "Two-tenant convergence.",
            "confidence": "high",
        }

    async def chat_structured(self, *, system, user, schema, timeout_s):
        self.calls.append({"system": system, "user": user})
        return schema.model_validate(self.next_payload)


class _SlowAIClient:
    """Fake AI client that blocks until released, then returns once.

    Lets us prove that concurrent generate() calls actually serialize through
    the per-identity lock (rather than racing to multiple model calls).
    """

    def __init__(self) -> None:
        self.calls: list[dict] = []
        self.release = asyncio.Event()
        self.next_payload = {
            "schema_version": 2,
            "intro": "Coalesced.",
            "confidence": "high",
        }

    async def chat_structured(self, *, system, user, schema, timeout_s):
        self.calls.append({"system": system, "user": user})
        await self.release.wait()
        return schema.model_validate(self.next_payload)


class _FakeRetriever:
    async def lookup_wikipedia(self, **kw):
        return []

    async def lookup_openlibrary(self, **kw):
        return []


@pytest.fixture
async def session_factory(engine) -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    yield async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)


async def _wipe(session_factory) -> None:
    async with session_factory() as cleanup:
        await cleanup.execute(
            text(
                "TRUNCATE TABLE ai_generation_log, external_source_cache, "
                "book_insights, ai_usage_daily CASCADE"
            )
        )
        await cleanup.commit()


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_two_tenants_share_one_insight_two_log_rows(session_factory):
    """Tenant A misses + generates; Tenant B hits the same row. Two log entries."""
    await _wipe(session_factory)

    orch = InsightOrchestrator(
        ai=_FakeAIClient(),
        retriever_factory=lambda s: _FakeRetriever(),
        sources_enabled=(),
        model_id="multi-tenant-model",
        prompt_version="mt1",
        max_concurrency=2,
        ai_timeout_s=5.0,
    )

    ident = DocumentIdentity(metadata_id="tenant-shared", content_hash="ch-multi")
    bundle = MetadataBundle(title="Shared")

    # Tenant A: cold miss (own session)
    async with session_factory() as s_a:
        out_a = await orch.generate(
            s_a, ident, bundle, user_id="alice@tenant-a", tenant_id="tenant-a"
        )

    # Tenant B: hits A's cache row (own session)
    async with session_factory() as s_b:
        out_b = await orch.generate(
            s_b, ident, bundle, user_id="bob@tenant-b", tenant_id="tenant-b"
        )

    # Cross-tenant cache hit confirmed
    assert out_a.payload.intro == out_b.payload.intro
    assert len(orch.ai.calls) == 1  # one model call total
    # (generated_by is grandfathered: don't assert on it — keeps the "no read
    # sites" grep clean.)

    # Inspect the persisted state from a third session
    async with session_factory() as s_check:
        insights = (
            (
                await s_check.execute(
                    select(BookInsight).where(BookInsight.content_hash == "ch-multi")
                )
            )
            .scalars()
            .all()
        )
        assert len(insights) == 1
        insight = insights[0]
        # generated_by intentionally NOT asserted: PR-C stops READING the
        # column; an assertion here would re-introduce a read site that the
        # cache-key audit grep is meant to catch.

        logs = (
            (await s_check.execute(select(AIGenerationLog).order_by(AIGenerationLog.id)))
            .scalars()
            .all()
        )
        assert len(logs) == 2
        assert {log.tenant_id for log in logs} == {"tenant-a", "tenant-b"}
        assert [log.status for log in logs] == ["miss", "hit"]
        assert {log.book_insight_id for log in logs} == {insight.id}
        assert {log.subject for log in logs} == {"alice@tenant-a", "bob@tenant-b"}


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_concurrent_waiters_coalesce_across_sessions(session_factory):
    """N concurrent waiters with separate sessions → 1 miss + (N-1) hits.

    The slow fake AI client blocks on an asyncio.Event so all waiters reach
    the lock at the same time. We poll until exactly one model call is
    in-flight (proving lock contention rather than racing), then release.
    """
    await _wipe(session_factory)

    slow_client = _SlowAIClient()
    orch = InsightOrchestrator(
        ai=slow_client,
        retriever_factory=lambda s: _FakeRetriever(),
        sources_enabled=(),
        model_id="coalesce-model",
        prompt_version="c1",
        max_concurrency=4,
        ai_timeout_s=5.0,
    )

    ident = DocumentIdentity(metadata_id=None, content_hash="ch-conc-coalesce")
    bundle = MetadataBundle(title="Conc")

    async def _one_waiter(user_id: str, tenant_id: str):
        async with session_factory() as s:
            return await orch.generate(s, ident, bundle, user_id=user_id, tenant_id=tenant_id)

    # Launch three waiters concurrently. They will all queue on the lock;
    # the lock-holder will block on slow_client.release.
    tasks = [
        asyncio.create_task(_one_waiter("u1", "t1")),
        asyncio.create_task(_one_waiter("u2", "t2")),
        asyncio.create_task(_one_waiter("u3", "t3")),
    ]

    # Wait until exactly one model call is in flight (the lock-holder reached
    # chat_structured). Bounded loop avoids `sleep(0.05)` flakiness.
    deadline = asyncio.get_event_loop().time() + 5.0
    while len(slow_client.calls) == 0 and asyncio.get_event_loop().time() < deadline:  # noqa: ASYNC110 — poll a counter, not a single Event
        await asyncio.sleep(0.005)
    # Give other tasks one more scheduling slice so any racing waiter would
    # have had time to also enter chat_structured. Then assert exactly one.
    await asyncio.sleep(0.02)
    assert len(slow_client.calls) == 1, (
        f"expected 1 in-flight model call, got {len(slow_client.calls)}; "
        "waiters didn't coalesce on the lock"
    )

    # Release the lock-holder so it can complete; the two waiters then hit.
    slow_client.release.set()
    results = await asyncio.gather(*tasks)
    assert len(results) == 3
    # Still exactly one model call total.
    assert len(slow_client.calls) == 1

    async with session_factory() as s_check:
        insights = (
            (
                await s_check.execute(
                    select(BookInsight).where(BookInsight.content_hash == "ch-conc-coalesce")
                )
            )
            .scalars()
            .all()
        )
        assert len(insights) == 1

        logs = (
            (await s_check.execute(select(AIGenerationLog).order_by(AIGenerationLog.id)))
            .scalars()
            .all()
        )
        assert len(logs) == 3
        statuses = sorted(log.status for log in logs)
        assert statuses == ["hit", "hit", "miss"]
        assert {log.book_insight_id for log in logs} == {insights[0].id}
        assert sorted(log.subject for log in logs) == ["u1", "u2", "u3"]
        assert sorted(log.tenant_id for log in logs) == ["t1", "t2", "t3"]
