"""Identity-resolution seam (PR2).

This module sits between the `/ai/v1/*` API and the insight orchestrator.
It maps non-canonical identity hints (`opds_href`, `opds_dc_id`,
`calibre_book_id`, `isbn`) to canonical schemes (`metadata_id`,
`content_hash`) via the `insight_identity_aliases` table.

Resolution order (per the roadmap "identity hierarchy" §):
  1. metadata_id          -- canonical
  2. content_hash         -- canonical
  3. opds_dc_id           -- alias (pre-download)
  4. isbn                 -- alias
  5. calibre_book_id      -- alias (pre-download)
  6. opds_href            -- alias fallback (pre-download)

`metadata_id`, `content_hash`, `isbn` aliases are GLOBAL (`user_id=NULL`).
`opds_href`, `opds_dc_id`, `calibre_book_id` aliases are USER-SCOPED — the
same OPDS string can mean different books on different calibre-web
instances and must not cross-contaminate.

The resolver short-circuits canonical-in/canonical-out: a request that
already supplies `metadata_id` does not hit the DB. For alias inputs,
the lookup is a single indexed read on `(alias_scheme, alias_value,
user_id)`.

`register_alias` uses INSERT ... ON CONFLICT DO NOTHING for
concurrency safety: a parallel writer racing the same alias loses
silently. Disagreement on the canonical raises `AliasConflict` — the
caller decides whether to log+skip or escalate (the orchestrator's
collision-handling path catches it and merges the two existing
insight rows; see service.py `_resolve_canonical`).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Literal

from sqlalchemy import and_, or_, select, text
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.db.models import InsightIdentityAlias

logger = logging.getLogger(__name__)

CanonicalScheme = Literal["metadata_id", "content_hash"]

CANONICAL_SCHEMES: frozenset[str] = frozenset({"metadata_id", "content_hash"})
ALIAS_SCHEMES: frozenset[str] = frozenset(
    {"metadata_id", "content_hash", "opds_href", "opds_dc_id", "calibre_book_id", "isbn"}
)

# Resolution priority: canonicals first (no DB read), then alias schemes
# in strongest-to-weakest order. The orchestrator walks this list at the
# top of every cache-touching method.
IDENTITY_HIERARCHY: tuple[str, ...] = (
    "metadata_id",
    "content_hash",
    "opds_dc_id",
    "isbn",
    "calibre_book_id",
    "opds_href",
)

# Scope: True means user-scoped (alias row carries user_id), False means
# global (alias row has user_id=NULL). Per §3.3 of the spec.
SCOPE_BY_SCHEME: dict[str, bool] = {
    "metadata_id": False,
    "content_hash": False,
    "isbn": False,
    "opds_href": True,
    "opds_dc_id": True,
    "calibre_book_id": True,
}


class AliasConflict(Exception):
    """A register/reconcile call would overwrite an existing alias row with
    a different canonical. The caller decides whether to log+skip or
    escalate (the orchestrator escalates to the collision-handling path).
    """

    def __init__(
        self,
        *,
        alias_scheme: str,
        alias_value: str,
        user_id: str | None,
        existing: "CanonicalIdentity",
        proposed: "CanonicalIdentity",
    ) -> None:
        self.alias_scheme = alias_scheme
        self.alias_value = alias_value
        self.user_id = user_id
        self.existing = existing
        self.proposed = proposed
        super().__init__(
            f"alias ({alias_scheme}, {alias_value}, user_id={user_id}) "
            f"already maps to {existing}; refusing to overwrite with {proposed}"
        )


@dataclass(frozen=True, slots=True)
class CanonicalIdentity:
    scheme: CanonicalScheme
    value: str

    def __str__(self) -> str:  # noqa: D401
        return f"{self.scheme}:{self.value}"


def _is_scoped(scheme: str) -> bool:
    """Return True if `scheme` is user-scoped per the alias-scope convention.

    Unknown schemes default to scoped (conservative: don't accidentally
    publish a never-seen-before alias to all users).
    """
    return SCOPE_BY_SCHEME.get(scheme, True)


async def resolve_identity(
    session: AsyncSession,
    *,
    alias_scheme: str,
    alias_value: str,
    user_id: str | None = None,
) -> CanonicalIdentity | None:
    """Resolve any alias to its canonical (metadata_id or content_hash).

    1. Canonical short-circuit: if `alias_scheme` is already canonical,
       return `(alias_scheme, alias_value)` directly (no DB read).
    2. User-scoped schemes: prefer a row with `user_id = <caller>`; fall
       back to a global row only if the scheme allows it (currently no
       user-scoped scheme has a global twin, but the fallback is cheap
       and future-proof).
    3. Global schemes: read the global row.
    4. Return None if no alias row matches.
    """
    if alias_scheme in CANONICAL_SCHEMES:
        return CanonicalIdentity(scheme=alias_scheme, value=alias_value)  # type: ignore[arg-type]

    Alias = InsightIdentityAlias  # noqa: N806
    base = select(Alias.canonical_scheme, Alias.canonical_value).where(
        Alias.alias_scheme == alias_scheme,
        Alias.alias_value == alias_value,
    )

    # User-scoped read FIRST (when applicable), then global fallback.
    if _is_scoped(alias_scheme) and user_id is not None:
        row = (
            await session.execute(base.where(Alias.user_id == user_id).limit(1))
        ).one_or_none()
        if row is not None:
            return CanonicalIdentity(scheme=row[0], value=row[1])

    # Global lookup. For user-scoped schemes this is a deliberate
    # fallback (in case the alias was registered globally somehow);
    # for global schemes this is the primary lookup.
    row = (await session.execute(base.where(Alias.user_id.is_(None)).limit(1))).one_or_none()
    if row is not None:
        return CanonicalIdentity(scheme=row[0], value=row[1])

    return None


async def register_alias(
    session: AsyncSession,
    *,
    alias_scheme: str,
    alias_value: str,
    canonical: CanonicalIdentity,
    source: str,
    user_id: str | None = None,
) -> None:
    """Idempotent INSERT ... ON CONFLICT DO NOTHING.

    Concurrency-safe: a parallel writer racing the same (alias_scheme,
    alias_value, user_id) loses silently; both end up with the same row.

    If the alias_scheme is canonical AND the value matches `canonical`,
    we no-op (registering metadata_id->metadata_id is meaningless).

    Disagreement detection: after the insert attempt, we read back the
    current canonical for this alias. If it disagrees with `canonical`,
    we raise `AliasConflict`.

    Caller commits the surrounding tx.
    """
    if alias_scheme in CANONICAL_SCHEMES:
        if alias_scheme == canonical.scheme and alias_value == canonical.value:
            return  # registering canonical-to-self is a no-op
        # Else fall through and store the row — e.g. content_hash -> metadata_id
        # is a legitimate canonical-to-canonical alias used by §3.6 reconciliation.

    Alias = InsightIdentityAlias  # noqa: N806
    scoped = _is_scoped(alias_scheme)
    effective_user_id = user_id if scoped else None
    if scoped and user_id is None:
        logger.info(
            "alias.skip scheme=%s value=%s reason=user_scoped_but_no_user_id",
            alias_scheme,
            alias_value,
        )
        return

    # Use ON CONFLICT against the matching partial unique index. Postgres
    # picks the right partial index by predicate match on `user_id IS
    # NULL` / `IS NOT NULL`.
    index_where = text(
        "user_id IS NOT NULL" if effective_user_id is not None else "user_id IS NULL"
    )
    stmt = (
        pg_insert(Alias)
        .values(
            alias_scheme=alias_scheme,
            alias_value=alias_value,
            canonical_scheme=canonical.scheme,
            canonical_value=canonical.value,
            source=source,
            user_id=effective_user_id,
        )
        .on_conflict_do_nothing(
            index_elements=(
                ["alias_scheme", "alias_value", "user_id"]
                if effective_user_id is not None
                else ["alias_scheme", "alias_value"]
            ),
            index_where=index_where,
        )
    )
    await session.execute(stmt)

    # Read back to detect conflict.
    existing = (
        await session.execute(
            select(Alias.canonical_scheme, Alias.canonical_value).where(
                Alias.alias_scheme == alias_scheme,
                Alias.alias_value == alias_value,
                (
                    Alias.user_id == effective_user_id
                    if effective_user_id is not None
                    else Alias.user_id.is_(None)
                ),
            )
        )
    ).one_or_none()
    if existing is None:
        # Shouldn't happen unless someone deleted the row between INSERT
        # and SELECT. Treat as success: the caller's intent was met by
        # the (transient) row.
        return
    if existing[0] != canonical.scheme or existing[1] != canonical.value:
        raise AliasConflict(
            alias_scheme=alias_scheme,
            alias_value=alias_value,
            user_id=effective_user_id,
            existing=CanonicalIdentity(scheme=existing[0], value=existing[1]),
            proposed=canonical,
        )


async def reconcile_aliases(
    session: AsyncSession,
    *,
    hints: dict[str, str],
    canonical: CanonicalIdentity,
    source: str,
    user_id: str | None = None,
) -> None:
    """Write aliases for every hint that is NOT already the canonical.

    Scope is decided per-scheme via `SCOPE_BY_SCHEME`:
      - Global schemes ignore the `user_id` argument.
      - User-scoped schemes use `user_id`; if `user_id` is None for a
        user-scoped hint, the hint is SKIPPED with a log line.

    Atomicity is the caller's responsibility: wrap this call in the same
    transaction as the insight row write. `AliasConflict` propagates;
    caller must roll back.
    """
    for scheme, value in hints.items():
        if value is None:
            continue
        # Skip the canonical itself (don't store canonical->self).
        if scheme == canonical.scheme and value == canonical.value:
            continue
        # Skip unknown schemes loudly: we don't silently accept arbitrary
        # alias schemes from the API surface.
        if scheme not in ALIAS_SCHEMES:
            logger.warning("alias.skip scheme=%s reason=unknown_scheme", scheme)
            continue
        await register_alias(
            session,
            alias_scheme=scheme,
            alias_value=value,
            canonical=canonical,
            source=source,
            user_id=user_id,
        )


async def load_live_insight_ids_for_canonicals(
    session: AsyncSession,
    *,
    canonicals: list[CanonicalIdentity],
    model_id: str,
    prompt_version: str,
    tone: str,
    language: str,
):
    """Helper for the collision-detection path.

    Loads up to one live BookInsight row per canonical. Used by
    `service._resolve_canonical` to detect the case where two pre-
    existing insights live under different canonicals that we now know
    belong together.

    Returns a list of (canonical, BookInsight) tuples in the same order
    as `canonicals`; entries with no row are omitted.
    """
    from opds_sync.db.models import BookInsight

    out = []
    for c in canonicals:
        col = BookInsight.metadata_id if c.scheme == "metadata_id" else BookInsight.content_hash
        row = (
            await session.execute(
                select(BookInsight)
                .where(
                    col == c.value,
                    BookInsight.model_id == model_id,
                    BookInsight.prompt_version == prompt_version,
                    BookInsight.tone == tone,
                    BookInsight.language == language,
                    BookInsight.superseded_at.is_(None),
                )
                .limit(1)
            )
        ).scalar_one_or_none()
        if row is not None:
            out.append((c, row))
    return out


# Silence import-time linter for unused symbols re-exported for tests:
__all__ = [
    "ALIAS_SCHEMES",
    "AliasConflict",
    "CANONICAL_SCHEMES",
    "CanonicalIdentity",
    "IDENTITY_HIERARCHY",
    "SCOPE_BY_SCHEME",
    "load_live_insight_ids_for_canonicals",
    "reconcile_aliases",
    "register_alias",
    "resolve_identity",
]

# Stub uses to keep linters happy:
_ = and_, or_
