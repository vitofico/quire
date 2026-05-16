"""Cache-integrity invariant: shared-cache tables MUST NOT carry tenant columns.

PR2 (2026-05-16) splits the audit into two parametrize lists:

- `SHARED_CACHE_TABLES`: cross-tenant shared cache. These rows are reused
  across every tenant requesting the same identity+model+prompt+tone+
  language. They MUST NOT carry `user_id`, `tenant_id`, `subject`, or
  `principal_id`. The existing test enforces this.

- `SCOPED_ALIAS_TABLES`: rows whose `user_id` is INTENTIONAL cache-key
  scoping, NOT a tenant-leak. `insight_identity_aliases` is the first
  member: per-user OPDS aliases must not cross-contaminate (the same
  OPDS string can mean different books on different calibre-web
  instances). A new inverse-property test asserts `user_id` IS present
  on these tables, so a future refactor that removes the scoping fails
  loudly. Tenant columns (`tenant_id`, `subject`, `principal_id`) are
  still forbidden — only `user_id` is allow-listed.

`generated_by` on BookInsight is grandfathered and explicitly allow-listed
with a comment pointing to the deprecation plan (PR-C stops reading it; a
follow-up nulls it; a later migration drops it).
"""

from __future__ import annotations

import pytest
from sqlalchemy import inspect

from opds_sync.db.models import (
    BookInsight,
    ExternalSourceCacheEntry,
    InsightIdentityAlias,
)

# `user_id` is forbidden on shared-cache tables; `tenant_id` / `subject` /
# `principal_id` are forbidden on ALL tables in this audit (shared OR
# scoped — tenant attribution belongs in `ai_generation_log`).
FORBIDDEN_ON_SHARED = frozenset({"user_id", "tenant_id", "subject", "principal_id"})
FORBIDDEN_ON_SCOPED = frozenset({"tenant_id", "subject", "principal_id"})

# Grandfathered legacy columns per table. PR-C stops READING `generated_by`;
# a follow-up will null then drop it. Until dropped, it stays in the schema.
GRANDFATHERED: dict[str, frozenset[str]] = {
    "book_insights": frozenset({"generated_by"}),
}

# Strictly cross-tenant shared cache. No `user_id`, no tenant audit.
SHARED_CACHE_TABLES = [
    pytest.param(BookInsight, id="book_insights"),
    pytest.param(ExternalSourceCacheEntry, id="external_source_cache"),
    # Future entries (add when the model lands):
    # pytest.param(BookTheme, id="book_themes"),           # PR3
]

# Tables where `user_id` is INTENTIONAL cache-key scoping, NOT a tenant-leak.
# These rows fragment lookups on purpose: per-user OPDS aliases must not
# cross-contaminate because the same OPDS string can mean different books
# on different calibre-web instances.
SCOPED_ALIAS_TABLES = [
    pytest.param(InsightIdentityAlias, id="insight_identity_aliases"),  # PR2
]


@pytest.mark.requires_ai
@pytest.mark.parametrize("model_cls", SHARED_CACHE_TABLES)
async def test_shared_cache_table_has_no_tenant_columns(engine, model_cls) -> None:
    table_name = model_cls.__tablename__

    def _columns(sync_conn) -> set[str]:
        insp = inspect(sync_conn)
        return {c["name"] for c in insp.get_columns(table_name)}

    async with engine.connect() as conn:
        cols = await conn.run_sync(_columns)

    grandfathered = GRANDFATHERED.get(table_name, frozenset())
    offending = (cols & FORBIDDEN_ON_SHARED) - grandfathered
    assert not offending, (
        f"shared-cache table {table_name!r} carries forbidden tenant columns: "
        f"{sorted(offending)}. Per the cache-integrity invariant, tenant audit "
        f"belongs in ai_generation_log, not on the shared cache row."
    )


@pytest.mark.requires_ai
@pytest.mark.parametrize("model_cls", SCOPED_ALIAS_TABLES)
async def test_scoped_alias_table_carries_user_id(engine, model_cls) -> None:
    """Inverse property: scoped tables MUST have `user_id`, but no other
    tenant columns. A future refactor that removes `user_id` would silently
    let user A's OPDS aliases bleed into user B's catalog — this test
    catches that.
    """
    table_name = model_cls.__tablename__

    def _columns(sync_conn) -> set[str]:
        insp = inspect(sync_conn)
        return {c["name"] for c in insp.get_columns(table_name)}

    async with engine.connect() as conn:
        cols = await conn.run_sync(_columns)

    assert "user_id" in cols, (
        f"scoped-alias table {table_name!r} is missing `user_id`. Per-user "
        f"scoping is load-bearing for the cache-integrity invariant: removing "
        f"it would let one tenant's OPDS aliases match another tenant's lookup."
    )
    offending = cols & FORBIDDEN_ON_SCOPED
    assert not offending, (
        f"scoped-alias table {table_name!r} carries forbidden tenant columns: "
        f"{sorted(offending)}. Only `user_id` is allow-listed on scoped tables; "
        f"tenant audit still belongs in ai_generation_log."
    )
