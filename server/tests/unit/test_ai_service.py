import asyncio
import time as _time
from typing import Any

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.api.ai_schemas import (
    AiStyle,
    DocumentIdentity,
    MetadataBundle,
)
from opds_sync.core.ai.service import InsightOrchestrator, QuotaExceeded, TokenBucket
from opds_sync.db.models import BookInsight


class FakeAIClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []
        self.next_payload: dict[str, Any] = {
            "schema_version": 2,
            "intro": "A foundational sci-fi novel.",
            "analysis": "Asimov's Foundation imagines a galactic empire on the brink of collapse.",
            "confidence": "high",
        }

    async def chat_structured(self, *, system, user, schema, timeout_s):
        self.calls.append({"system": system, "user": user})
        return schema.model_validate(self.next_payload)


class FakeRetriever:
    def __init__(self) -> None:
        self.wiki_calls: int = 0
        self.ol_calls: int = 0

    async def lookup_wikipedia(self, **kw):
        self.wiki_calls += 1
        return []

    async def lookup_openlibrary(self, **kw):
        self.ol_calls += 1
        return []


@pytest.fixture
def make_orchestrator(session):
    def _make(sources_enabled=("wikipedia", "openlibrary"), max_concurrency=4):
        fake_retriever = FakeRetriever()
        orch = InsightOrchestrator(
            ai=FakeAIClient(),
            retriever_factory=lambda s: fake_retriever,
            sources_enabled=tuple(sources_enabled),
            model_id="test-model",
            prompt_version="t1",
            max_concurrency=max_concurrency,
            ai_timeout_s=5.0,
        )
        orch.retriever = fake_retriever  # type: ignore[attr-defined]
        return orch

    return _make


@pytest.mark.asyncio
async def test_cache_hit_short_circuits(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id="9780553293357", content_hash="ch1")
    bundle = MetadataBundle(title="Foundation", author="Isaac Asimov")

    first = await orch.generate(session, ident, bundle, user_id="u1")
    second_orch = make_orchestrator()
    second = await second_orch.generate(session, ident, bundle, user_id="u2")

    assert first.payload.intro == second.payload.intro
    assert second_orch.ai.calls == []
    assert second_orch.retriever.wiki_calls == 0


@pytest.mark.asyncio
async def test_alias_reconciliation_backfills_metadata_id(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident_hash = DocumentIdentity(metadata_id=None, content_hash="ch-foo")
    bundle = MetadataBundle(title="Foundation")
    await orch.generate(session, ident_hash, bundle, user_id="u1")

    second = make_orchestrator()
    ident_full = DocumentIdentity(metadata_id="urn-foo", content_hash="ch-foo")
    await second.generate(session, ident_full, bundle, user_id="u2")
    assert second.ai.calls == []

    rows = (
        (await session.execute(select(BookInsight).where(BookInsight.content_hash == "ch-foo")))
        .scalars()
        .all()
    )
    assert len(rows) == 1
    assert rows[0].metadata_id == "urn-foo"


@pytest.mark.asyncio
async def test_concurrent_generations_collapse_to_one_model_call(
    session: AsyncSession, make_orchestrator
):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-coalesce")
    bundle = MetadataBundle(title="Coalesce")

    results = await asyncio.gather(
        orch.generate(session, ident, bundle, user_id="u1"),
        orch.generate(session, ident, bundle, user_id="u2"),
        orch.generate(session, ident, bundle, user_id="u3"),
    )
    assert len(orch.ai.calls) == 1
    assert {r.payload.intro for r in results} == {"A foundational sci-fi novel."}


@pytest.mark.asyncio
async def test_invalidate_drops_cached_row(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-invalidate")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="u1")

    n = await orch.invalidate(session, ident)
    assert n == 1

    second = make_orchestrator()
    second.ai.next_payload = {"schema_version": 2, "intro": "fresh", "confidence": "low"}
    out = await second.generate(session, ident, MetadataBundle(title="X"), user_id="u1")
    assert out.payload.intro == "fresh"


@pytest.mark.asyncio
async def test_get_returns_none_on_miss(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-miss")
    assert await orch.get(session, ident) is None


@pytest.mark.asyncio
async def test_series_from_bundle_persists_into_payload(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    orch.ai.next_payload = {
        "schema_version": 2,
        "intro": "ok",
        "series": {"name": "WrongName", "position": 99},
        "confidence": "low",
    }
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-series")
    bundle = MetadataBundle(title="X", series_name="Foundation", series_position=1)
    out = await orch.generate(session, ident, bundle, user_id="u1")
    assert out.payload.series.name == "Foundation"
    assert out.payload.series.position == 1


@pytest.mark.asyncio
async def test_token_bucket_smooths_bursts():
    bucket = TokenBucket(rate_per_min=60)
    start = _time.monotonic()
    for _ in range(3):
        await bucket.acquire()
    assert _time.monotonic() - start < 0.05


@pytest.mark.asyncio
async def test_daily_budget_blocks_after_limit(session, make_orchestrator):
    orch = make_orchestrator()
    orch._daily_budget = 2
    for i in range(2):
        ident = DocumentIdentity(metadata_id=None, content_hash=f"ch-budget-{i}")
        await orch.generate(session, ident, MetadataBundle(title=f"B{i}"), user_id="u-quota")
    with pytest.raises(QuotaExceeded) as exc:
        await orch.generate(
            session,
            DocumentIdentity(metadata_id=None, content_hash="ch-budget-3"),
            MetadataBundle(title="B3"),
            user_id="u-quota",
        )
    assert exc.value.used == 2
    assert exc.value.limit == 2


@pytest.mark.asyncio
async def test_cache_hits_bypass_budget(session, make_orchestrator):
    orch = make_orchestrator()
    orch._daily_budget = 1
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-cache-hit")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="u-cache")
    out = await orch.generate(session, ident, MetadataBundle(title="X"), user_id="u-cache")
    assert out.payload.intro == "A foundational sci-fi novel."


@pytest.mark.asyncio
async def test_regenerate_supersedes_and_records_lineage(session, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-regen")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="u1")
    orch.ai.next_payload = {"schema_version": 2, "intro": "fixed", "confidence": "high"}
    second = await orch.regenerate(
        session,
        ident,
        MetadataBundle(title="X"),
        user_id="u1",
        reason="Author bio was wrong.",
    )
    assert second.payload.intro == "fixed"

    rows = (
        (
            await session.execute(
                select(BookInsight)
                .where(BookInsight.content_hash == "ch-regen")
                .order_by(BookInsight.id)
            )
        )
        .scalars()
        .all()
    )
    assert len(rows) == 2
    assert rows[0].superseded_at is not None
    assert rows[1].superseded_at is None
    assert rows[1].previous_insight_ids == [rows[0].id]


@pytest.mark.asyncio
async def test_regen_has_tighter_daily_limit(session, make_orchestrator):
    orch = make_orchestrator()
    orch._regen_daily_limit = 1
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-regen-limit")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="u-regen")
    await orch.regenerate(
        session, ident, MetadataBundle(title="X"), user_id="u-regen", reason="no good"
    )
    with pytest.raises(QuotaExceeded):
        await orch.regenerate(
            session, ident, MetadataBundle(title="X"), user_id="u-regen", reason="still no good"
        )


@pytest.mark.asyncio
async def test_style_threaded_into_prompt(session, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-style")
    await orch.generate(
        session,
        ident,
        MetadataBundle(title="X"),
        user_id="u-style",
        style=AiStyle(tone="scholarly"),
    )
    assert any(
        "scholarly" in call["user"].lower() or "analytical" in call["user"].lower()
        for call in orch.ai.calls
    )


@pytest.mark.asyncio
async def test_different_tones_generate_separate_cache_rows(session, make_orchestrator):
    """Two users with different tones must get their own rows — no cross-tone leak."""
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-tones")
    bundle = MetadataBundle(title="X")

    await orch.generate(session, ident, bundle, user_id="u1", style=AiStyle(tone="neutral"))
    await orch.generate(session, ident, bundle, user_id="u2", style=AiStyle(tone="scholarly"))

    rows = (
        (await session.execute(select(BookInsight).where(BookInsight.content_hash == "ch-tones")))
        .scalars()
        .all()
    )
    assert {r.tone for r in rows} == {"neutral", "scholarly"}
    assert len(orch.ai.calls) == 2


@pytest.mark.asyncio
async def test_same_tone_shares_cache_across_users(session, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-shared-tone")
    bundle = MetadataBundle(title="X")

    await orch.generate(session, ident, bundle, user_id="u1", style=AiStyle(tone="scholarly"))
    await orch.generate(session, ident, bundle, user_id="u2", style=AiStyle(tone="scholarly"))

    assert len(orch.ai.calls) == 1


@pytest.mark.asyncio
async def test_different_languages_generate_separate_cache_rows(session, make_orchestrator):
    """Two users with different languages must get their own rows — no cross-language leak."""
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-languages")
    bundle = MetadataBundle(title="X")

    await orch.generate(session, ident, bundle, user_id="u1", style=AiStyle(language="auto"))
    await orch.generate(session, ident, bundle, user_id="u2", style=AiStyle(language="it"))

    rows = (
        (
            await session.execute(
                select(BookInsight).where(BookInsight.content_hash == "ch-languages")
            )
        )
        .scalars()
        .all()
    )
    assert {r.language for r in rows} == {"auto", "it"}
    assert len(orch.ai.calls) == 2


@pytest.mark.asyncio
async def test_same_language_shares_cache_across_users(session, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-shared-language")
    bundle = MetadataBundle(title="X")

    await orch.generate(session, ident, bundle, user_id="u1", style=AiStyle(language="it"))
    await orch.generate(session, ident, bundle, user_id="u2", style=AiStyle(language="it"))

    assert len(orch.ai.calls) == 1


@pytest.mark.asyncio
async def test_tone_and_language_orthogonal(session, make_orchestrator):
    """Same identity, different (tone, language) combos → separate cache rows."""
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-orthogonal")
    bundle = MetadataBundle(title="X")

    await orch.generate(
        session, ident, bundle, user_id="u1", style=AiStyle(tone="neutral", language="auto")
    )
    await orch.generate(
        session, ident, bundle, user_id="u2", style=AiStyle(tone="neutral", language="it")
    )
    await orch.generate(
        session, ident, bundle, user_id="u3", style=AiStyle(tone="scholarly", language="auto")
    )
    await orch.generate(
        session, ident, bundle, user_id="u4", style=AiStyle(tone="scholarly", language="it")
    )

    rows = (
        (
            await session.execute(
                select(BookInsight).where(BookInsight.content_hash == "ch-orthogonal")
            )
        )
        .scalars()
        .all()
    )
    assert len(rows) == 4
    assert {(r.tone, r.language) for r in rows} == {
        ("neutral", "auto"),
        ("neutral", "it"),
        ("scholarly", "auto"),
        ("scholarly", "it"),
    }


@pytest.mark.asyncio
async def test_invalidate_does_not_touch_old_prompt_version_rows(
    session: AsyncSession, make_orchestrator
):
    """Invalidate at prompt_version `t1` must leave rows at older prompt_versions alone.

    Regression for the cache-version-bump contract: a user invalidate with the
    new PROMPT_VERSION must not delete rows from before the bump. Models a
    user on the new server invalidating their freshly-generated v3 row while
    a stale v2 row from a previous deploy lingers (it'll never be read again
    because the v3 lookup filters on prompt_version, but it must not be GC'd
    by an unrelated invalidate call).
    """
    orch = make_orchestrator()
    # Insert an "old" row directly with a stale prompt_version.
    old_row = BookInsight(
        metadata_id=None,
        content_hash="ch-invalidate",
        model_id="test-model",
        prompt_version="t0_legacy",
        tone="neutral",
        language="auto",
        sources_used=[],
        payload={"schema_version": 2, "confidence": "low"},
        sources=[],
        generated_by="legacy",
    )
    session.add(old_row)
    await session.commit()

    # Generate a fresh row at the orchestrator's current prompt_version ("t1").
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-invalidate")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="u1")

    # Invalidate via the orchestrator (scoped to current prompt_version).
    deleted = await orch.invalidate(session, ident)
    assert deleted == 1  # only the new row

    rows = (
        (
            await session.execute(
                select(BookInsight).where(BookInsight.content_hash == "ch-invalidate")
            )
        )
        .scalars()
        .all()
    )
    # Only the old row survives.
    assert len(rows) == 1
    assert rows[0].prompt_version == "t0_legacy"


# ---- PR-C: ai_generation_log assertions ------------------------------------

from opds_sync.db.models import AIGenerationLog  # noqa: E402


@pytest.mark.asyncio
async def test_generate_miss_writes_log_row(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-log-miss")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="alice")

    rows = (await session.execute(select(AIGenerationLog))).scalars().all()
    assert len(rows) == 1
    row = rows[0]
    assert row.status == "miss"
    assert row.subject == "alice"
    assert row.tenant_id == "local"  # default when kwarg omitted
    assert row.model_id == "test-model"
    assert row.prompt_version == "t1"
    assert row.latency_ms is not None and row.latency_ms >= 0
    assert row.error_class is None
    assert row.book_insight_id is not None


@pytest.mark.asyncio
async def test_second_generate_writes_hit_row_with_same_fk(
    session: AsyncSession, make_orchestrator
):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-log-hit")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="alice")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="bob")

    rows = (
        (await session.execute(select(AIGenerationLog).order_by(AIGenerationLog.id)))
        .scalars()
        .all()
    )
    assert len(rows) == 2
    assert [r.status for r in rows] == ["miss", "hit"]
    assert [r.subject for r in rows] == ["alice", "bob"]
    assert rows[0].book_insight_id == rows[1].book_insight_id
    assert rows[1].latency_ms == 0  # cache lookup cost


@pytest.mark.asyncio
async def test_log_uses_passed_tenant_id(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-tenant-kwarg")
    await orch.generate(
        session, ident, MetadataBundle(title="X"), user_id="alice", tenant_id="acme"
    )

    rows = (await session.execute(select(AIGenerationLog))).scalars().all()
    assert len(rows) == 1
    assert rows[0].tenant_id == "acme"


@pytest.mark.asyncio
async def test_get_hit_writes_log_row(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-get-hit")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="alice")
    # baseline: one miss row from the generate
    assert len((await session.execute(select(AIGenerationLog))).scalars().all()) == 1

    out = await orch.get(session, ident, user_id="alice")
    assert out is not None

    rows = (
        (await session.execute(select(AIGenerationLog).order_by(AIGenerationLog.id)))
        .scalars()
        .all()
    )
    assert len(rows) == 2
    assert rows[1].status == "hit"
    assert rows[1].latency_ms == 0


@pytest.mark.asyncio
async def test_get_miss_writes_no_log_row(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-get-miss")
    assert await orch.get(session, ident) is None

    rows = (await session.execute(select(AIGenerationLog))).scalars().all()
    assert rows == []


@pytest.mark.asyncio
async def test_concurrent_generations_emit_one_miss_and_n_minus_one_hits(
    session: AsyncSession, make_orchestrator
):
    """Coalesced waiters: one model call but N log rows, one per waiter."""
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-coalesce-log")
    bundle = MetadataBundle(title="Coalesce")

    await asyncio.gather(
        orch.generate(session, ident, bundle, user_id="u1"),
        orch.generate(session, ident, bundle, user_id="u2"),
        orch.generate(session, ident, bundle, user_id="u3"),
    )

    assert len(orch.ai.calls) == 1

    rows = (
        (await session.execute(select(AIGenerationLog).order_by(AIGenerationLog.id)))
        .scalars()
        .all()
    )
    assert len(rows) == 3
    statuses = sorted(r.status for r in rows)
    assert statuses == ["hit", "hit", "miss"]
    # All three FK the same insight
    assert len({r.book_insight_id for r in rows}) == 1
    # Three distinct subjects
    assert sorted(r.subject for r in rows) == ["u1", "u2", "u3"]


@pytest.mark.asyncio
async def test_log_carries_request_id_when_set(session: AsyncSession, make_orchestrator):
    from opds_sync.core.logging_ctx import request_id_var

    token = request_id_var.set("test-req-abc123")
    try:
        orch = make_orchestrator()
        ident = DocumentIdentity(metadata_id=None, content_hash="ch-req-id")
        await orch.generate(session, ident, MetadataBundle(title="X"), user_id="alice")
    finally:
        request_id_var.reset(token)

    rows = (await session.execute(select(AIGenerationLog))).scalars().all()
    assert len(rows) == 1
    assert rows[0].request_id == "test-req-abc123"


@pytest.mark.asyncio
async def test_log_default_tenant_id_is_local(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-tenant-default")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="alice")

    rows = (await session.execute(select(AIGenerationLog))).scalars().all()
    assert len(rows) == 1
    assert rows[0].tenant_id == "local"


class _ExplodingAIClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    async def chat_structured(self, *, system, user, schema, timeout_s):
        self.calls.append({"system": system, "user": user})
        raise RuntimeError("simulated provider failure")


@pytest.mark.asyncio
async def test_generate_error_emits_structured_log(session: AsyncSession, caplog):
    """Errors emit a structured `event=ai.generate.error` warning carrying
    tenant_id, subject, model, prompt_version, error_class. The request_id
    ContextVar is read by the log filter (attached to caplog's handler to
    mirror the production handler-level attachment) and surfaces as
    record.request_id. No ai_generation_log row is written.
    """
    import logging

    from opds_sync.core.ai.service import InsightOrchestrator
    from opds_sync.core.logging_ctx import RequestIdLogFilter, request_id_var

    # Set level on BOTH the root and the specific service logger; pytest-asyncio
    # plus testcontainers fixtures can leave child-logger levels in unexpected
    # states across tests, so be explicit.
    caplog.set_level(logging.WARNING, logger="opds_sync.core.ai.service")
    caplog.set_level(logging.WARNING)
    filt = RequestIdLogFilter()
    caplog.handler.addFilter(filt)

    token = request_id_var.set("req-err-xyz")
    try:
        orch = InsightOrchestrator(
            ai=_ExplodingAIClient(),
            retriever_factory=lambda s: FakeRetriever(),
            sources_enabled=(),
            model_id="boom-model",
            prompt_version="t1",
            max_concurrency=1,
            ai_timeout_s=5.0,
        )
        ident = DocumentIdentity(metadata_id=None, content_hash="ch-error")

        with pytest.raises(RuntimeError, match="simulated provider failure"):
            await orch.generate(
                session,
                ident,
                MetadataBundle(title="X"),
                user_id="alice",
                tenant_id="acme",
            )
    finally:
        request_id_var.reset(token)
        caplog.handler.removeFilter(filt)

    rows = (await session.execute(select(AIGenerationLog))).scalars().all()
    assert rows == []  # no DB row for errors

    error_records = [r for r in caplog.records if "event=ai.generate.error" in r.getMessage()]
    assert len(error_records) == 1, (
        f"expected exactly one ai.generate.error log record, got {len(error_records)} "
        f"out of {[r.getMessage() for r in caplog.records]}"
    )
    rec = error_records[0]
    msg = rec.getMessage()
    assert "tenant_id=acme" in msg
    assert "subject=alice" in msg
    assert "model=boom-model" in msg
    assert "prompt_version=t1" in msg
    assert "error_class=RuntimeError" in msg
    # request_id surfaces on the record because the filter is on caplog.handler.
    assert getattr(rec, "request_id", "") == "req-err-xyz"


# ---- Per-task AsyncSession in _retrieve ------------------------------------
#
# Regression for the asyncpg "This session is provisioning a new connection;
# concurrent operations are not permitted" race. Pre-fix, both
# `lookup_wikipedia` and `lookup_openlibrary` received the SAME AsyncSession
# and ran concurrently under asyncio.gather. The loser raised on
# `self._session.execute(...)` inside `_read_cache`; the exception was
# swallowed by `gather(..., return_exceptions=True)`. In prod that meant
# openlibrary never issued an HTTP call, never recorded reachability via
# AiHealthState.record_retrieval, and never wrote a row to
# external_source_cache. Verified live: wikipedia=7 cache rows /
# openlibrary=0 rows despite 7 generated book_insights.

import httpx as _httpx  # noqa: E402

from opds_sync.core.ai.health_state import AiHealthState  # noqa: E402


class _SessionRecordingRetriever:
    """Retriever stub that records the AsyncSession it was constructed with
    and invokes record_retrieval(success=True) for each lookup, mirroring
    the real Retriever's reachability bookkeeping."""

    def __init__(self, *, session, health: AiHealthState) -> None:
        self.session = session
        self.health = health
        self.wiki_calls = 0
        self.ol_calls = 0

    async def lookup_wikipedia(self, *, author, title):
        self.wiki_calls += 1
        await self.health.record_retrieval(name="wikipedia", success=True)
        return []

    async def lookup_openlibrary(self, *, author, title, isbn):
        self.ol_calls += 1
        await self.health.record_retrieval(name="openlibrary", success=True)
        return []


@pytest.mark.asyncio
async def test_retrieve_uses_per_task_sessions_so_both_sources_run(session: AsyncSession, engine):
    """Both wikipedia and openlibrary must record reachability after one
    generation. Pre-fix this failed because the shared-session race
    swallowed openlibrary's task before it could record."""
    from sqlalchemy.ext.asyncio import async_sessionmaker

    health = AiHealthState()
    factory = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
    built_retrievers: list[_SessionRecordingRetriever] = []

    def _retriever_factory(s):
        r = _SessionRecordingRetriever(session=s, health=health)
        built_retrievers.append(r)
        return r

    orch = InsightOrchestrator(
        ai=FakeAIClient(),
        retriever_factory=_retriever_factory,
        sources_enabled=("wikipedia", "openlibrary"),
        model_id="test-model",
        prompt_version="t1",
        max_concurrency=4,
        ai_timeout_s=5.0,
        health_state=health,
        session_factory=factory,
    )

    ident = DocumentIdentity(metadata_id=None, content_hash="ch-per-task-session")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="u1")

    # Both sources observably called.
    wiki_total = sum(r.wiki_calls for r in built_retrievers)
    ol_total = sum(r.ol_calls for r in built_retrievers)
    assert wiki_total == 1, f"expected 1 wikipedia call, got {wiki_total}"
    assert ol_total == 1, f"expected 1 openlibrary call, got {ol_total}"

    # Both record_retrieval calls landed in the health snapshot.
    snap = await health.snapshot()
    assert "wikipedia" in snap.retrieval_sources
    assert "openlibrary" in snap.retrieval_sources
    assert snap.retrieval_sources["wikipedia"].reachable is True
    assert snap.retrieval_sources["openlibrary"].reachable is True

    # Each task got its own AsyncSession (distinct instances), not the
    # shared request-scoped one.
    retriever_sessions = [r.session for r in built_retrievers]
    assert len(retriever_sessions) >= 2
    assert len({id(s) for s in retriever_sessions}) == len(retriever_sessions), (
        "retriever tasks must not share an AsyncSession"
    )
    assert session not in retriever_sessions, (
        "per-task sessions must be freshly minted, not the orchestrator's session"
    )


class _PartialFailRetriever:
    """One source raises httpx.ConnectError; the other succeeds. Lets us
    confirm the gather-with-return_exceptions pattern still gives partial
    success after the per-task-session refactor."""

    def __init__(self, *, session, health: AiHealthState, fail: str) -> None:
        self.session = session
        self.health = health
        self.fail = fail
        self.wiki_called = False
        self.ol_called = False

    async def lookup_wikipedia(self, *, author, title):
        self.wiki_called = True
        if self.fail == "wikipedia":
            raise _httpx.ConnectError("simulated wiki outage")
        await self.health.record_retrieval(name="wikipedia", success=True)
        return []

    async def lookup_openlibrary(self, *, author, title, isbn):
        self.ol_called = True
        if self.fail == "openlibrary":
            raise _httpx.ConnectError("simulated ol outage")
        await self.health.record_retrieval(name="openlibrary", success=True)
        return []


@pytest.mark.asyncio
async def test_retrieve_partial_failure_does_not_kill_sibling(session: AsyncSession, engine):
    """If one retrieval task raises, the other must still complete and
    record reachability. The gather(..., return_exceptions=True) pattern
    must survive the per-task-session refactor."""
    from sqlalchemy.ext.asyncio import async_sessionmaker

    health = AiHealthState()
    factory = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
    built: list[_PartialFailRetriever] = []

    def _factory(s):
        r = _PartialFailRetriever(session=s, health=health, fail="wikipedia")
        built.append(r)
        return r

    orch = InsightOrchestrator(
        ai=FakeAIClient(),
        retriever_factory=_factory,
        sources_enabled=("wikipedia", "openlibrary"),
        model_id="test-model",
        prompt_version="t1",
        max_concurrency=4,
        ai_timeout_s=5.0,
        health_state=health,
        session_factory=factory,
    )

    ident = DocumentIdentity(metadata_id=None, content_hash="ch-partial-fail")
    # Generate must succeed even though wikipedia raised.
    out = await orch.generate(session, ident, MetadataBundle(title="X"), user_id="u1")
    assert out.payload.intro  # AI step still ran

    assert any(r.wiki_called for r in built), "wikipedia task should still have been invoked"
    assert any(r.ol_called for r in built), "openlibrary task should still have been invoked"

    snap = await health.snapshot()
    # openlibrary recorded success; wikipedia did not record (it raised
    # before reaching record_retrieval, which mirrors the real Retriever
    # behavior where httpx.ConnectError records success=False — here our
    # stub just raises raw to exercise the gather path).
    assert "openlibrary" in snap.retrieval_sources
    assert snap.retrieval_sources["openlibrary"].reachable is True
