import asyncio
import time as _time
from typing import Any

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.api.ai_schemas import (
    AiStyle,
    BookInsightPayload,
    DocumentIdentity,
    MetadataBundle,
)
from opds_sync.core.ai.service import InsightOrchestrator, QuotaExceeded, TokenBucket
from opds_sync.db.models import AIUsageDaily, BookInsight


class FakeAIClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []
        self.next_payload: dict[str, Any] = {
            "schema_version": 1,
            "summary": "A foundational sci-fi novel.",
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

    assert first.payload.summary == second.payload.summary
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

    rows = (await session.execute(select(BookInsight).where(BookInsight.content_hash == "ch-foo"))).scalars().all()
    assert len(rows) == 1
    assert rows[0].metadata_id == "urn-foo"


@pytest.mark.asyncio
async def test_concurrent_generations_collapse_to_one_model_call(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-coalesce")
    bundle = MetadataBundle(title="Coalesce")

    results = await asyncio.gather(
        orch.generate(session, ident, bundle, user_id="u1"),
        orch.generate(session, ident, bundle, user_id="u2"),
        orch.generate(session, ident, bundle, user_id="u3"),
    )
    assert len(orch.ai.calls) == 1
    assert {r.payload.summary for r in results} == {"A foundational sci-fi novel."}


@pytest.mark.asyncio
async def test_invalidate_drops_cached_row(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-invalidate")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="u1")

    n = await orch.invalidate(session, ident)
    assert n == 1

    second = make_orchestrator()
    second.ai.next_payload = {"schema_version": 1, "summary": "fresh", "confidence": "low"}
    out = await second.generate(session, ident, MetadataBundle(title="X"), user_id="u1")
    assert out.payload.summary == "fresh"


@pytest.mark.asyncio
async def test_get_returns_none_on_miss(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-miss")
    assert await orch.get(session, ident) is None


@pytest.mark.asyncio
async def test_series_from_bundle_persists_into_payload(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    orch.ai.next_payload = {
        "schema_version": 1,
        "summary": "ok",
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
    assert out.payload.summary == "A foundational sci-fi novel."


@pytest.mark.asyncio
async def test_regenerate_supersedes_and_records_lineage(session, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-regen")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="u1")
    orch.ai.next_payload = {"schema_version": 1, "summary": "fixed", "confidence": "high"}
    second = await orch.regenerate(
        session, ident, MetadataBundle(title="X"),
        user_id="u1", reason="Author bio was wrong.",
    )
    assert second.payload.summary == "fixed"

    rows = (await session.execute(
        select(BookInsight).where(BookInsight.content_hash == "ch-regen").order_by(BookInsight.id)
    )).scalars().all()
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
    await orch.regenerate(session, ident, MetadataBundle(title="X"),
                          user_id="u-regen", reason="no good")
    with pytest.raises(QuotaExceeded):
        await orch.regenerate(session, ident, MetadataBundle(title="X"),
                              user_id="u-regen", reason="still no good")


@pytest.mark.asyncio
async def test_style_threaded_into_prompt(session, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-style")
    await orch.generate(
        session, ident, MetadataBundle(title="X"), user_id="u-style",
        style=AiStyle(tone="scholarly", include_spoilers=True),
    )
    assert any("scholarly" in call["user"].lower() for call in orch.ai.calls)
