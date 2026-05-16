"""Identity-resolution integration tests for the insight orchestrator (PR2).

These exercise the full alias → canonical → cache → reconcile flow at the
orchestrator level, using the same FakeAIClient / FakeRetriever pattern
as `test_ai_service.py`. They are the load-bearing tests for PR7's
catalog-preview-then-download flow.

Tests (in TDD order):
  1. `test_reconciliation_collision_metadata_id_wins` — the trickiest
     edge case; written FIRST. Two pre-existing insights collide; the
     metadata_id-keyed row wins; the content_hash-keyed row is
     superseded; lineage is preserved.
  2. `test_catalog_preview_then_download_converges_to_one_row` —
     pre-download request uses opds_href/opds_dc_id; post-download
     request supplies metadata_id+content_hash; one cache row total,
     under the canonical metadata_id.
  3. `test_user_scoped_alias_does_not_bleed` — two users with the same
     opds_href see independent canonicals.
  4. `test_generate_raises_identity_unresolvable_when_no_canonical` —
     a request with only opds_href and no alias row pre-registered
     fails with 422-equivalent.
"""

from __future__ import annotations

from typing import Any

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.api.ai_schemas import DocumentIdentity, MetadataBundle
from opds_sync.core.ai.identity import (
    CanonicalIdentity,
    register_alias,
)
from opds_sync.core.ai.service import (
    IdentityUnresolvable,
    InsightOrchestrator,
)
from opds_sync.db.models import BookInsight, InsightIdentityAlias


# ---- Fakes (mirror of test_ai_service.py) ----------------------------------


class FakeAIClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []
        self.next_payload: dict[str, Any] = {
            "schema_version": 2,
            "intro": "A novel.",
            "confidence": "high",
        }

    async def chat_structured(self, *, system, user, schema, timeout_s):
        self.calls.append({"system": system, "user": user})
        return schema.model_validate(self.next_payload)


class FakeRetriever:
    async def lookup_wikipedia(self, **kw):
        return []

    async def lookup_openlibrary(self, **kw):
        return []


@pytest.fixture
def make_orch(session):
    def _make(model_id="test-model", prompt_version="t1"):
        return InsightOrchestrator(
            ai=FakeAIClient(),
            retriever_factory=lambda s: FakeRetriever(),
            sources_enabled=("wikipedia", "openlibrary"),
            model_id=model_id,
            prompt_version=prompt_version,
            max_concurrency=4,
            ai_timeout_s=5.0,
        )

    return _make


# ============================================================================
# 1. Reconciliation collision (TDD-first per the plan)
# ============================================================================


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_reconciliation_collision_metadata_id_wins(
    session: AsyncSession, make_orch
) -> None:
    """Two pre-existing live insights belong to the same book. A later
    request supplies BOTH hints. The metadata_id-keyed row wins; the
    other row is superseded; lineage on the winner includes the loser's
    id and any earlier lineage from the loser.
    """
    orch = make_orch()

    # Seed insight A: metadata_id-keyed, no content_hash collision yet.
    ident_a = DocumentIdentity(metadata_id="meta-x", content_hash="real-hash-A")
    await orch.generate(session, ident_a, MetadataBundle(title="X"), user_id="alice")

    # Seed insight B: content_hash-only (no metadata_id). Different value
    # because the EPUB body actually differs at this stage.
    orch_b = make_orch()
    ident_b = DocumentIdentity(metadata_id=None, content_hash="other-hash-B")
    await orch_b.generate(session, ident_b, MetadataBundle(title="X"), user_id="bob")

    # Confirm two live insights exist.
    live_rows = (
        (
            await session.execute(
                select(BookInsight).where(BookInsight.superseded_at.is_(None))
            )
        )
        .scalars()
        .all()
    )
    assert len(live_rows) == 2

    # Register an alias linking the second canonical (content_hash=other-hash-B)
    # to the first canonical (metadata_id=meta-x). This simulates the
    # post-download discovery that hash-B actually belongs to meta-x.
    await register_alias(
        session,
        alias_scheme="content_hash",
        alias_value="other-hash-B",
        canonical=CanonicalIdentity(scheme="metadata_id", value="meta-x"),
        source="opf_extracted",
        user_id=None,
    )
    await session.commit()

    # Now a request supplies BOTH hints (canonical metadata_id and the
    # aliased content_hash). The orchestrator must detect the collision
    # and supersede the loser.
    orch_c = make_orch()
    ident_c = DocumentIdentity(metadata_id="meta-x", content_hash="other-hash-B")
    out = await orch_c.get(session, ident_c, user_id="carol")

    # After collision-resolution, the metadata_id-keyed row is the
    # winner and is returned (or a generate would hit it).
    assert out is not None

    # One live row remains.
    live_after = (
        (
            await session.execute(
                select(BookInsight).where(BookInsight.superseded_at.is_(None))
            )
        )
        .scalars()
        .all()
    )
    assert len(live_after) == 1
    winner = live_after[0]
    assert winner.metadata_id == "meta-x"

    # The loser's id is in the winner's previous_insight_ids.
    superseded = (
        (
            await session.execute(
                select(BookInsight).where(BookInsight.superseded_at.is_not(None))
            )
        )
        .scalars()
        .all()
    )
    assert len(superseded) == 1
    loser_id = superseded[0].id
    assert loser_id in (winner.previous_insight_ids or [])


# ============================================================================
# 2. Catalog-preview-then-download convergence
# ============================================================================


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_catalog_preview_then_download_converges_to_one_row(
    session: AsyncSession, make_orch
) -> None:
    """The PR7 load-bearing case:

    1. User opens a catalog tile (no download yet). Request supplies
       only `opds_href` and `opds_dc_id`. The orchestrator generates an
       insight using a synthetic content_hash; it also writes alias
       rows for the opds_* hints pointing at the canonical (derived
       from opds_dc_id, which normalizes to a metadata_id-like value).
    2. User downloads the book. Request supplies BOTH `metadata_id`
       and the real `content_hash`. The orchestrator resolves via the
       canonical metadata_id and finds the existing insight row from
       step 1. One row total in `book_insights`.
    """
    orch = make_orch()

    # Step 1: catalog-preview generate. No metadata_id, no real content_hash.
    # We pre-register the opds_dc_id alias so the resolver finds a canonical.
    await register_alias(
        session,
        alias_scheme="opds_dc_id",
        alias_value="urn:isbn:9780553293357",
        canonical=CanonicalIdentity(
            scheme="metadata_id", value="9780553293357"
        ),
        source="opds_feed",
        user_id="alice",
    )
    await session.commit()

    preview_ident = DocumentIdentity(
        metadata_id=None,
        content_hash=None,
        opds_href="sha-of-href",
        opds_dc_id="urn:isbn:9780553293357",
    )
    bundle = MetadataBundle(title="Foundation", author="Isaac Asimov")
    first = await orch.generate(session, preview_ident, bundle, user_id="alice")
    assert first is not None

    # Confirm one insight exists, under the canonical metadata_id.
    rows = (
        (
            await session.execute(
                select(BookInsight).where(BookInsight.superseded_at.is_(None))
            )
        )
        .scalars()
        .all()
    )
    assert len(rows) == 1
    assert rows[0].metadata_id == "9780553293357"
    # The synthetic content_hash should obviously not be a sha256.
    assert rows[0].content_hash.startswith("synthetic:")

    # The opds_href alias should have been written by reconcile_aliases.
    aliases = (
        (
            await session.execute(
                select(InsightIdentityAlias).where(
                    InsightIdentityAlias.alias_scheme == "opds_href"
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(aliases) == 1
    assert aliases[0].canonical_value == "9780553293357"
    assert aliases[0].user_id == "alice"

    # Step 2: post-download generate. Supplies metadata_id + real content_hash.
    orch_b = make_orch()
    download_ident = DocumentIdentity(
        metadata_id="9780553293357", content_hash="real-sha256"
    )
    second = await orch_b.generate(session, download_ident, bundle, user_id="alice")
    assert second is not None

    # The AI client should NOT have been called (cache hit).
    assert orch_b.ai.calls == []

    # Still one live insight. The synthetic-keyed row is still the live
    # row; in a full follow-up the second request would backfill its
    # content_hash with the real sha256 (out of scope for PR2 — we
    # care about convergence, not backfill semantics).
    live = (
        (
            await session.execute(
                select(BookInsight).where(BookInsight.superseded_at.is_(None))
            )
        )
        .scalars()
        .all()
    )
    assert len(live) == 1


# ============================================================================
# 3. User-scoped alias does not bleed
# ============================================================================


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_user_scoped_alias_does_not_bleed(
    session: AsyncSession, make_orch
) -> None:
    """Alice and Bob register the SAME opds_href but on different
    calibre-web instances pointing at different books. Bob's resolver
    must NOT see Alice's alias.
    """
    await register_alias(
        session,
        alias_scheme="opds_href",
        alias_value="ambiguous-href",
        canonical=CanonicalIdentity(scheme="metadata_id", value="alice-book"),
        source="opds_feed",
        user_id="alice",
    )
    await register_alias(
        session,
        alias_scheme="opds_href",
        alias_value="ambiguous-href",
        canonical=CanonicalIdentity(scheme="metadata_id", value="bob-book"),
        source="opds_feed",
        user_id="bob",
    )
    await session.commit()

    # Alice generates; should land under "alice-book".
    orch_a = make_orch()
    ident_a = DocumentIdentity(
        metadata_id=None, content_hash=None, opds_href="ambiguous-href"
    )
    await orch_a.generate(
        session, ident_a, MetadataBundle(title="Alice's Book"), user_id="alice"
    )

    # Bob generates; should land under "bob-book", NOT alice-book.
    orch_b = make_orch()
    ident_b = DocumentIdentity(
        metadata_id=None, content_hash=None, opds_href="ambiguous-href"
    )
    await orch_b.generate(
        session, ident_b, MetadataBundle(title="Bob's Book"), user_id="bob"
    )

    rows = (
        (
            await session.execute(
                select(BookInsight).where(BookInsight.superseded_at.is_(None))
            )
        )
        .scalars()
        .all()
    )
    metadata_ids = sorted(r.metadata_id for r in rows if r.metadata_id)
    assert metadata_ids == ["alice-book", "bob-book"]


# ============================================================================
# 4. Identity unresolvable on write
# ============================================================================


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_generate_raises_identity_unresolvable_when_no_canonical(
    session: AsyncSession, make_orch
) -> None:
    """A request with only an unresolved alias hint and no canonical
    must raise IdentityUnresolvable on the write path.
    """
    orch = make_orch()
    ident = DocumentIdentity(
        metadata_id=None,
        content_hash=None,
        opds_href="unregistered-href",
    )
    with pytest.raises(IdentityUnresolvable):
        await orch.generate(
            session, ident, MetadataBundle(title="Unknown"), user_id="alice"
        )
