"""Cache-integrity invariant: shared-cache tables MUST NOT carry tenant columns.

This regression test is *future-proof*: as PR2 (insight_identity_aliases) and
PR3 (book_themes) land, their model classes must be added to the parametrize
list. If a future PR accidentally adds `user_id` to a shared cache table,
this test fails at CI time, by design.

`generated_by` on BookInsight is grandfathered and explicitly allow-listed
with a comment pointing to the deprecation plan (PR-C stops reading it; a
follow-up nulls it; a later migration drops it).
"""

from __future__ import annotations

import pytest
from sqlalchemy import inspect

from opds_sync.db.models import BookInsight, ExternalSourceCacheEntry

FORBIDDEN_COLUMNS = frozenset({"user_id", "tenant_id", "subject", "principal_id"})

# Grandfathered legacy columns per table. PR-C stops READING `generated_by`;
# a follow-up will null then drop it. Until dropped, it stays in the schema.
GRANDFATHERED: dict[str, frozenset[str]] = {
    "book_insights": frozenset({"generated_by"}),
}

SHARED_CACHE_TABLES = [
    pytest.param(BookInsight, id="book_insights"),
    pytest.param(ExternalSourceCacheEntry, id="external_source_cache"),
    # Future entries (add when the model lands):
    # pytest.param(BookTheme, id="book_themes"),           # PR3
    # pytest.param(InsightIdentityAlias, id="insight_identity_aliases"),  # PR2
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
    offending = (cols & FORBIDDEN_COLUMNS) - grandfathered
    assert not offending, (
        f"shared-cache table {table_name!r} carries forbidden tenant columns: "
        f"{sorted(offending)}. Per the cache-integrity invariant, tenant audit "
        f"belongs in ai_generation_log, not on the shared cache row."
    )
