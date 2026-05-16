"""Unit tests for the identity-resolution seam (PR2).

Covers:
- Canonical short-circuit (no DB read for metadata_id/content_hash inputs)
- Global alias lookup
- User-scoped alias lookup (and isolation between users)
- `register_alias` idempotency under repeated calls
- `register_alias` conflict raises `AliasConflict`
- `reconcile_aliases` writes all in one transaction
- `reconcile_aliases` atomicity: a single failure rolls back the whole batch
"""

from __future__ import annotations

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.core.ai.identity import (
    AliasConflict,
    CanonicalIdentity,
    reconcile_aliases,
    register_alias,
    resolve_identity,
)
from opds_sync.db.models import InsightIdentityAlias


# ---- Canonical short-circuit -----------------------------------------------


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_canonical_metadata_id_short_circuits(session: AsyncSession) -> None:
    """A `metadata_id` input returns immediately, never touching the DB."""
    c = await resolve_identity(
        session, alias_scheme="metadata_id", alias_value="9780553293357"
    )
    assert c == CanonicalIdentity(scheme="metadata_id", value="9780553293357")
    # No alias row was written.
    rows = (await session.execute(select(InsightIdentityAlias))).all()
    assert rows == []


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_canonical_content_hash_short_circuits(session: AsyncSession) -> None:
    c = await resolve_identity(session, alias_scheme="content_hash", alias_value="abc123")
    assert c == CanonicalIdentity(scheme="content_hash", value="abc123")


# ---- Global alias lookup ---------------------------------------------------


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_global_alias_lookup(session: AsyncSession) -> None:
    """An ISBN alias resolves to the canonical metadata_id (global)."""
    await register_alias(
        session,
        alias_scheme="isbn",
        alias_value="9780553293357",
        canonical=CanonicalIdentity(scheme="metadata_id", value="meta-foundation"),
        source="manual",
        user_id=None,
    )
    await session.commit()

    c = await resolve_identity(
        session, alias_scheme="isbn", alias_value="9780553293357", user_id="alice"
    )
    assert c == CanonicalIdentity(scheme="metadata_id", value="meta-foundation")


# ---- User-scoped alias lookup ----------------------------------------------


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_user_scoped_alias_lookup(session: AsyncSession) -> None:
    """A `opds_href` alias resolves to the canonical only for the registering user."""
    await register_alias(
        session,
        alias_scheme="opds_href",
        alias_value="hash-of-href",
        canonical=CanonicalIdentity(scheme="metadata_id", value="meta-X"),
        source="opds_feed",
        user_id="alice",
    )
    await session.commit()

    c = await resolve_identity(
        session, alias_scheme="opds_href", alias_value="hash-of-href", user_id="alice"
    )
    assert c == CanonicalIdentity(scheme="metadata_id", value="meta-X")


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_user_scoped_alias_does_not_match_other_user(session: AsyncSession) -> None:
    """User B must NOT see User A's user-scoped alias."""
    await register_alias(
        session,
        alias_scheme="opds_href",
        alias_value="shared-href",
        canonical=CanonicalIdentity(scheme="metadata_id", value="meta-A"),
        source="opds_feed",
        user_id="alice",
    )
    await session.commit()

    c = await resolve_identity(
        session, alias_scheme="opds_href", alias_value="shared-href", user_id="bob"
    )
    assert c is None  # bob has no alias for "shared-href"


# ---- Idempotency / conflict -------------------------------------------------


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_register_alias_is_idempotent(session: AsyncSession) -> None:
    """Writing the same alias twice produces exactly one row."""
    canonical = CanonicalIdentity(scheme="metadata_id", value="meta-Y")
    for _ in range(3):
        await register_alias(
            session,
            alias_scheme="isbn",
            alias_value="9781234567890",
            canonical=canonical,
            source="manual",
            user_id=None,
        )
    await session.commit()

    rows = (
        (await session.execute(select(InsightIdentityAlias)))
        .scalars()
        .all()
    )
    assert len(rows) == 1
    assert rows[0].canonical_value == "meta-Y"


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_register_alias_conflict_raises(session: AsyncSession) -> None:
    """Re-registering an alias with a DIFFERENT canonical raises AliasConflict."""
    await register_alias(
        session,
        alias_scheme="isbn",
        alias_value="9780000000001",
        canonical=CanonicalIdentity(scheme="metadata_id", value="meta-original"),
        source="manual",
        user_id=None,
    )
    await session.commit()

    with pytest.raises(AliasConflict) as excinfo:
        await register_alias(
            session,
            alias_scheme="isbn",
            alias_value="9780000000001",
            canonical=CanonicalIdentity(scheme="metadata_id", value="meta-different"),
            source="manual",
            user_id=None,
        )
    assert excinfo.value.existing.value == "meta-original"
    assert excinfo.value.proposed.value == "meta-different"


# ---- Reconciliation --------------------------------------------------------


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_reconcile_aliases_writes_multiple(session: AsyncSession) -> None:
    """`reconcile_aliases` writes alias rows for every non-canonical hint."""
    hints = {
        "metadata_id": "meta-foundation",
        "opds_href": "hash-of-href",
        "opds_dc_id": "urn:isbn:9780553293357",
        "isbn": "9780553293357",
    }
    canonical = CanonicalIdentity(scheme="metadata_id", value="meta-foundation")

    await reconcile_aliases(
        session,
        hints=hints,
        canonical=canonical,
        source="opf_extracted",
        user_id="alice",
    )
    await session.commit()

    rows = (
        (await session.execute(select(InsightIdentityAlias)))
        .scalars()
        .all()
    )
    # The canonical itself (metadata_id) is skipped; we expect 3 aliases:
    # opds_href (user-scoped), opds_dc_id (user-scoped), isbn (global).
    schemes = {r.alias_scheme for r in rows}
    assert schemes == {"opds_href", "opds_dc_id", "isbn"}

    by_scheme = {r.alias_scheme: r for r in rows}
    assert by_scheme["opds_href"].user_id == "alice"
    assert by_scheme["opds_dc_id"].user_id == "alice"
    assert by_scheme["isbn"].user_id is None  # global scheme


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_reconcile_aliases_atomicity_on_conflict(session: AsyncSession) -> None:
    """If a SECOND alias in the batch conflicts, NONE of the batch's
    rows land after rollback.

    Setup: pre-seed one alias (isbn -> meta-X). Then reconcile a batch
    that includes both a fresh alias (opds_href -> meta-Y) and a
    conflicting one (isbn -> meta-Y). The conflict must raise; the
    caller rolls back; the fresh opds_href alias must not persist.
    """
    # Pre-existing alias.
    await register_alias(
        session,
        alias_scheme="isbn",
        alias_value="9780553293357",
        canonical=CanonicalIdentity(scheme="metadata_id", value="meta-X"),
        source="manual",
        user_id=None,
    )
    await session.commit()

    canonical = CanonicalIdentity(scheme="metadata_id", value="meta-Y")
    hints = {
        "opds_href": "fresh-href",
        "isbn": "9780553293357",  # conflicts with pre-seeded
    }
    with pytest.raises(AliasConflict):
        await reconcile_aliases(
            session,
            hints=hints,
            canonical=canonical,
            source="opf_extracted",
            user_id="alice",
        )
    await session.rollback()

    # After rollback the fresh opds_href alias must NOT be present.
    rows = (
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
    assert rows == [], "fresh opds_href alias leaked across the rolled-back conflict"
