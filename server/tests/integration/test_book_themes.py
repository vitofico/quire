"""Integration tests for `book_themes` side-table writes + cascade (PR3).

Covers:
- Vocab and off-vocab themes persist with correct confidence bands.
- Empty / literal-"other" inputs fold into the OTHER band.
- Model duplicates dedup before hitting the composite PK.
- Cascade-on-delete drops theme rows when the insight is invalidated.
- Regenerate is supersede-not-delete: old theme rows survive.
- `_do_generate` pins `schema_version=3` server-side over whatever the
  model returned.
- Cache-key audit (no `user_id`) at the table inspection level.
"""

from __future__ import annotations

from collections.abc import AsyncIterator

import pytest
from sqlalchemy import inspect, select, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from quire_server.api.ai_schemas import DocumentIdentity, MetadataBundle
from quire_server.core.ai.service import InsightOrchestrator
from quire_server.db.models import BookInsight, BookTheme


class _FakeAIClient:
    """Returns a configurable themes payload on each `chat_structured` call."""

    def __init__(self, themes: list[str] | None = None, schema_version: int = 3) -> None:
        self.calls: list[dict] = []
        self.themes = themes
        self.schema_version = schema_version

    async def chat_structured(self, *, system, user, schema, timeout_s):
        self.calls.append({"system": system, "user": user})
        return schema.model_validate(
            {
                "schema_version": self.schema_version,
                "intro": "Fake insight for themes test.",
                "confidence": "high",
                "themes": self.themes,
            }
        )


class _FakeRetriever:
    async def lookup_wikipedia(self, **kw):
        return []

    async def lookup_openlibrary(self, **kw):
        return []


@pytest.fixture
async def session_factory(engine) -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    yield async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)


async def _wipe(session_factory) -> None:
    """Truncate the AI-side tables so each test starts clean.

    `book_themes` is cascade-truncated by the `book_insights` CASCADE
    chain (FK ON DELETE CASCADE + TRUNCATE CASCADE).
    """
    async with session_factory() as cleanup:
        await cleanup.execute(
            text(
                "TRUNCATE TABLE ai_generation_log, external_source_cache, "
                "book_insights, book_themes, ai_usage_daily CASCADE"
            )
        )
        await cleanup.commit()


def _orchestrator(ai: _FakeAIClient, *, prompt_version: str = "4") -> InsightOrchestrator:
    return InsightOrchestrator(
        ai=ai,
        retriever_factory=lambda s: _FakeRetriever(),
        sources_enabled=(),
        model_id="themes-test-model",
        prompt_version=prompt_version,
        max_concurrency=2,
        ai_timeout_s=5.0,
    )


async def _themes_for(session: AsyncSession, insight_id: int) -> list[tuple[str, float]]:
    rows = (
        await session.execute(
            select(BookTheme.theme, BookTheme.confidence).where(
                BookTheme.book_insight_id == insight_id
            )
        )
    ).all()
    return sorted((r[0], float(r[1])) for r in rows)


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_book_themes_persisted_on_generate(session_factory):
    await _wipe(session_factory)
    ai = _FakeAIClient(themes=["mystery", "noir"])
    orch = _orchestrator(ai)
    ident = DocumentIdentity(metadata_id="t-vocab", content_hash="ch-vocab")
    bundle = MetadataBundle(title="Vocab Themes")

    async with session_factory() as s:
        await orch.generate(s, ident, bundle, user_id="alice", tenant_id="local")

    async with session_factory() as s:
        insight = (
            await s.execute(select(BookInsight).where(BookInsight.content_hash == "ch-vocab"))
        ).scalar_one()
        themes = await _themes_for(s, insight.id)
    assert themes == [("mystery", 1.0), ("noir", 1.0)]


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_book_themes_non_vocab_falls_to_other_confidence(session_factory):
    await _wipe(session_factory)
    ai = _FakeAIClient(themes=["dystopia", "noir western", "Cyberpunk"])
    orch = _orchestrator(ai)
    ident = DocumentIdentity(metadata_id="t-mixed", content_hash="ch-mixed")
    bundle = MetadataBundle(title="Mixed Themes")

    async with session_factory() as s:
        await orch.generate(s, ident, bundle, user_id="alice", tenant_id="local")

    async with session_factory() as s:
        insight = (
            await s.execute(select(BookInsight).where(BookInsight.content_hash == "ch-mixed"))
        ).scalar_one()
        themes = await _themes_for(s, insight.id)
    assert themes == [
        ("cyberpunk", 1.0),
        ("dystopia", 1.0),
        ("noir western", 0.5),  # off-vocab passthrough, spaces preserved
    ]


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_book_themes_dedup_on_model_duplicate(session_factory):
    await _wipe(session_factory)
    ai = _FakeAIClient(themes=["Mystery", "mystery", "MYSTERY"])
    orch = _orchestrator(ai)
    ident = DocumentIdentity(metadata_id="t-dup", content_hash="ch-dup")
    bundle = MetadataBundle(title="Dup Themes")

    async with session_factory() as s:
        await orch.generate(s, ident, bundle, user_id="alice", tenant_id="local")

    async with session_factory() as s:
        insight = (
            await s.execute(select(BookInsight).where(BookInsight.content_hash == "ch-dup"))
        ).scalar_one()
        themes = await _themes_for(s, insight.id)
    assert themes == [("mystery", 1.0)]


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_book_themes_literal_other_low_confidence(session_factory):
    """Empty inputs collapse to literal "other"; literal "other" from the
    model lands at OTHER_CONFIDENCE (not VOCAB) so PR9's confidence filter
    excludes it from the controlled-vocab top-N query.
    """
    await _wipe(session_factory)
    ai = _FakeAIClient(themes=["", "  ", "other"])
    orch = _orchestrator(ai)
    ident = DocumentIdentity(metadata_id="t-other", content_hash="ch-other")
    bundle = MetadataBundle(title="Other Themes")

    async with session_factory() as s:
        await orch.generate(s, ident, bundle, user_id="alice", tenant_id="local")

    async with session_factory() as s:
        insight = (
            await s.execute(select(BookInsight).where(BookInsight.content_hash == "ch-other"))
        ).scalar_one()
        themes = await _themes_for(s, insight.id)
    # All three inputs deduped onto a single ("other", 0.5) row.
    assert themes == [("other", 0.5)]


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_book_themes_cascade_on_insight_delete(session_factory):
    await _wipe(session_factory)
    ai = _FakeAIClient(themes=["mystery", "noir"])
    orch = _orchestrator(ai)
    ident = DocumentIdentity(metadata_id="t-cascade", content_hash="ch-cascade")
    bundle = MetadataBundle(title="Cascade Themes")

    async with session_factory() as s:
        await orch.generate(s, ident, bundle, user_id="alice", tenant_id="local")

    # Verify themes exist
    async with session_factory() as s:
        insight = (
            await s.execute(select(BookInsight).where(BookInsight.content_hash == "ch-cascade"))
        ).scalar_one()
        assert len(await _themes_for(s, insight.id)) == 2

    # Invalidate (DELETE on the parent)
    async with session_factory() as s:
        n = await orch.invalidate(s, ident, user_id="alice")
        assert n == 1

    # FK cascade should have dropped the children
    async with session_factory() as s:
        remaining = (
            (await s.execute(select(BookTheme).where(BookTheme.book_insight_id == insight.id)))
            .scalars()
            .all()
        )
    assert remaining == []


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_book_themes_regenerate_keeps_superseded_themes(session_factory):
    """Regenerate is supersede-not-delete. Old theme rows survive for audit
    alongside the new live row's themes. PR9 must filter superseded_at IS NULL
    on the join to avoid double-counting.
    """
    await _wipe(session_factory)
    ai = _FakeAIClient(themes=["mystery", "noir"])
    orch = _orchestrator(ai)
    ident = DocumentIdentity(metadata_id="t-regen", content_hash="ch-regen")
    bundle = MetadataBundle(title="Regen Themes")

    async with session_factory() as s:
        await orch.generate(s, ident, bundle, user_id="alice", tenant_id="local")

    # Reconfigure the fake to produce different themes on regen
    ai.themes = ["thriller", "crime"]
    async with session_factory() as s:
        await orch.regenerate(s, ident, bundle, user_id="alice", reason="redo", tenant_id="local")

    async with session_factory() as s:
        insights = (
            (await s.execute(select(BookInsight).where(BookInsight.content_hash == "ch-regen")))
            .scalars()
            .all()
        )
        assert len(insights) == 2
        live = [i for i in insights if i.superseded_at is None]
        old = [i for i in insights if i.superseded_at is not None]
        assert len(live) == 1 and len(old) == 1

        old_themes = await _themes_for(s, old[0].id)
        live_themes = await _themes_for(s, live[0].id)

    assert old_themes == [("mystery", 1.0), ("noir", 1.0)]
    assert live_themes == [("crime", 1.0), ("thriller", 1.0)]


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_schema_version_pinned_to_4_server_side(session_factory):
    """The model may emit schema_version=2 by mistake. _do_generate forces it
    to 4 (PR-ε bump) before model_dump() so the cache row reflects the real
    schema.
    """
    await _wipe(session_factory)
    ai = _FakeAIClient(themes=["mystery"], schema_version=2)
    orch = _orchestrator(ai)
    ident = DocumentIdentity(metadata_id="t-sv", content_hash="ch-sv")
    bundle = MetadataBundle(title="SV Themes")

    async with session_factory() as s:
        await orch.generate(s, ident, bundle, user_id="alice", tenant_id="local")

    async with session_factory() as s:
        insight = (
            await s.execute(select(BookInsight).where(BookInsight.content_hash == "ch-sv"))
        ).scalar_one()
        # payload is JSON-stored; the dict's schema_version must be the
        # server-pinned 4, not the model's emitted 2.
        assert insight.payload["schema_version"] == 4


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_book_themes_table_has_no_user_id(engine):
    """Belt-and-braces local check; the parametrized cache-key audit test
    covers the same ground but this lives next to the feature for grep-ability.
    """

    def _columns(sync_conn) -> set[str]:
        insp = inspect(sync_conn)
        return {c["name"] for c in insp.get_columns("book_themes")}

    async with engine.connect() as conn:
        cols = await conn.run_sync(_columns)
    assert {"book_insight_id", "theme", "confidence"} <= cols
    assert "user_id" not in cols
    assert "tenant_id" not in cols
