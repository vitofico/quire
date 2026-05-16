# Spec — Generic identity aliases (PR2)

> Shipped in 193a01d on 2026-05-16 as PR #17.

**Date:** 2026-05-16
**Branch:** `feat/identity-aliases` (stacked on `main` after PR-A / PR-C / PR4)
**Roadmap reference:** `.claude/local/quire-ai/2026-05-16-next-deliverables.md` §PR2
**Status:** Draft for architect review

## 1. Motivation

Today every AI cache lookup, generation-lock acquisition, regenerate, and invalidate operates on a hard-coded `DocumentIdentity{metadata_id, content_hash}` pair. This works after a book is downloaded (the EPUB OPF supplies `metadata_id`, and we hash the body), but it breaks down for the **catalog-preview** flow planned in PR7:

- The user opens a catalog tile and asks for insight **before** downloading. There is no `content_hash` yet; the strongest stable identifier in the OPDS entry is `dc:identifier` (when present), then the Atom entry id or calibre book id, then a fallback `opds_href:<sha256(canonical acquisition href)>`.
- After the user actually downloads the same book, the EPUB OPF yields a real `metadata_id` and we now compute `content_hash`. We must converge the **existing** preview-generated insight onto the post-download identity, not generate a second cache row.

The roadmap (§"Identity hierarchy") makes this resolution chain explicit; this PR puts it in code as `resolve_identity(any_scheme, any_value, user_id) -> CanonicalIdentity`, backed by a new `insight_identity_aliases` table. PR7 then reads that resolver before mounting `CatalogDetailScreen`.

## 2. Identity hierarchy (locked)

When the server needs to find or create a cache row, it resolves identity in this order:

1. **`metadata_id`** — derived from EPUB OPF (`dc:identifier` or computed from OPF metadata). Stable across re-downloads.
2. **`content_hash`** — sha256 of the EPUB body. Stable across metadata edits.
3. **OPDS `dc:identifier`** — when present in the catalog entry, normalized with the same logic as EPUB OPF. *Pre-download.*
4. **Atom entry id / calibre book id** — scoped alias when present. *Pre-download.*
5. **`opds-href:<sha256(canonical acquisition href)>`** — fallback. *Pre-download.*

`metadata_id` and `content_hash` are **canonical** schemes. All others are **alias** schemes that must resolve to one of the two canonicals.

## 3. Server changes

### 3.1 Migration `ai_003_identity_aliases`

Third migration on the `ai` branch. Per PR-A convention:

```python
revision = "ai_003"
down_revision = "ai_002"
branch_labels = None        # PR-C owns the "ai" label on ai_001
depends_on = None
```

Upgrade creates the table:

```sql
CREATE TABLE insight_identity_aliases (
    id                bigserial    PRIMARY KEY,
    alias_scheme      text         NOT NULL,
    alias_value       text         NOT NULL,
    canonical_scheme  text         NOT NULL,
    canonical_value   text         NOT NULL,
    source            text         NOT NULL,
    user_id           text         NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),

    CHECK (canonical_scheme IN ('metadata_id', 'content_hash')),
    CHECK (alias_scheme <> canonical_scheme OR alias_value <> canonical_value)
);

-- NULL-in-PK pitfall: Postgres treats NULL values as distinct in unique
-- constraints, so a composite (alias_scheme, alias_value, user_id) PRIMARY
-- KEY would allow duplicate rows where user_id IS NULL. We split the
-- uniqueness into two partial indexes:

CREATE UNIQUE INDEX uq_insight_identity_aliases_scoped
    ON insight_identity_aliases (alias_scheme, alias_value, user_id)
    WHERE user_id IS NOT NULL;

CREATE UNIQUE INDEX uq_insight_identity_aliases_global
    ON insight_identity_aliases (alias_scheme, alias_value)
    WHERE user_id IS NULL;

CREATE INDEX ix_insight_identity_aliases_canonical
    ON insight_identity_aliases (canonical_scheme, canonical_value);
```

Downgrade drops everything.

### 3.2 ORM model `InsightIdentityAlias`

New class in `opds_sync/db/models.py`. Surrogate `id` PK, `__table_args__` declares both partial unique indexes plus the canonical-lookup index.

The cache-integrity invariant comment for this table reads:

> **Scoped alias table — `user_id` is intentional.** Most rows have `user_id = NULL` (global alias, e.g. ISBN → metadata_id, or OPF-extracted dc:identifier → metadata_id). Per-user OPDS aliases (`opds_href`, `opds_dc_id`, `calibre_book_id`) carry `user_id` because the same OPDS catalog entry on two different calibre-web instances may point to two different books, and the alias must not cross-contaminate. The unique key is `(alias_scheme, alias_value, user_id)` with NULL meaning "global" — the two partial unique indexes enforce this correctly.

### 3.3 New module `opds_sync/core/ai/identity.py`

Public surface:

```python
from dataclasses import dataclass
from typing import Literal

CanonicalScheme = Literal["metadata_id", "content_hash"]
AliasScheme = Literal[
    "metadata_id", "content_hash",
    "opds_href", "opds_dc_id", "calibre_book_id", "isbn",
]
AliasSource = Literal["opds_feed", "opf_extracted", "manual"]

# Scope convention enforced by `reconcile_aliases` and the resolver:
#   - `metadata_id`, `content_hash`, `isbn` aliases are GLOBAL (user_id=None).
#   - `opds_href`, `opds_dc_id`, `calibre_book_id` aliases are USER-SCOPED
#     (user_id=<caller>), because the same OPDS string can mean different
#     books on different calibre-web instances.
SCOPE_BY_SCHEME: dict[str, bool] = {
    "metadata_id": False,
    "content_hash": False,
    "isbn": False,
    "opds_href": True,
    "opds_dc_id": True,
    "calibre_book_id": True,
}


class AliasConflict(Exception):
    """Raised when register/reconcile would overwrite with a different canonical."""


@dataclass(frozen=True, slots=True)
class CanonicalIdentity:
    scheme: CanonicalScheme
    value: str


async def resolve_identity(
    session: AsyncSession,
    *,
    alias_scheme: str,
    alias_value: str,
    user_id: str | None = None,
) -> CanonicalIdentity | None:
    """Resolve any alias to its canonical (metadata_id or content_hash).

    Lookup order:
      1. If alias_scheme is already canonical, return (scheme, value) directly.
      2. If the scheme is user-scoped, look up the user-scoped alias row first.
      3. Fall back to a global alias row.
      4. Return None if no alias row matches.

    NOTE: This function does NOT short-circuit the orchestrator's collision
    check. The orchestrator must still load live insight rows for ALL supplied
    hints and detect divergence (see §3.6).
    """


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

    Concurrency-safe: uses the partial unique index that matches the
    `user_id IS NULL` / `IS NOT NULL` branch as the conflict target. A
    parallel writer racing the same alias loses the race silently; both
    end up with the same row.

    Conflict semantics: if the conflicting existing row's canonical
    disagrees with the new canonical, raise `AliasConflict`. The caller
    decides whether to log+skip or escalate. The detection is via
    `RETURNING canonical_scheme, canonical_value` (when an actual row
    inserts) or a follow-up SELECT (when ON CONFLICT DO NOTHING
    short-circuited). Caller commits the surrounding tx.
    """


async def reconcile_aliases(
    session: AsyncSession,
    *,
    hints: dict[str, str],
    canonical: CanonicalIdentity,
    source: str,
    user_id: str | None = None,
) -> None:
    """Write aliases for every hint that is NOT already the canonical.

    Scope decision is per-scheme via `SCOPE_BY_SCHEME`:
      - Global schemes ignore the `user_id` argument (alias row has
        user_id=NULL).
      - User-scoped schemes require `user_id` to be non-None; if `user_id`
        is None for a user-scoped hint, that hint is SKIPPED with a
        structured log line (we cannot scope without a user).

    Atomic: caller wraps this call in the same transaction as the insight
    row write. Any `AliasConflict` propagates; caller must roll back.
    """
```

The resolver short-circuits canonical-in / canonical-out (a request that already supplies `metadata_id` doesn't trigger a DB read). For alias-in inputs, the lookup is a single indexed read against the canonical-lookup index.

Idempotency: `register_alias` uses `INSERT ... ON CONFLICT DO NOTHING` on the relevant partial unique index — re-registering the same alias is a no-op even under concurrent writers. If the conflicting existing row's canonical disagrees with the new canonical, we **do not** overwrite silently; we raise `AliasConflict`.

### 3.4 Wiring `resolve_identity` into the orchestrator

The orchestrator's current `DocumentIdentity` stays the contract between API and service, but PR2 makes `content_hash` **optional** (an alias-only catalog-preview request has no content_hash yet). At least one of `metadata_id`, `content_hash`, or a resolvable alias hint MUST be present, else the API layer returns 422.

PR2 introduces a pre-resolution step at every entry point:

- **`generate()`**, **`get()`**, **`regenerate()`**, **`invalidate()`**: before the existing cache lookup or lock acquisition, the orchestrator runs `_resolve_canonical(session, ident, user_id)` which:
  1. For each supplied identity hint, in identity-hierarchy order (`metadata_id`, `content_hash`, `opds_dc_id`, `isbn`, `calibre_book_id`, `opds_href`), calls `resolve_identity` and collects the resulting canonical(s).
  2. If the collection contains exactly one canonical, returns a `DocumentIdentity` populated accordingly (this is the common case).
  3. If the collection contains multiple distinct canonicals, runs the collision-handling path (§3.6).
  4. If empty (no alias resolved, no canonical supplied), returns the original ident unchanged — the downstream cache lookup will miss and the API layer will 422 if neither canonical is populated for a write path.

- After the cache lookup falls through to actual generation and the row is staged via `session.add(...)` + `session.flush()` (NOT committed), `reconcile_aliases` writes alias rows for any non-canonical hints in the SAME transaction. A single `session.commit()` lands the insight row, the alias rows, and the per-call `ai_generation_log` row together.

The API surface change is additive: `DocumentIdentity` gains optional alias fields (`opds_href`, `opds_dc_id`, `calibre_book_id`, `isbn`) and makes `content_hash` optional. Existing callers (the post-download flow from PR1) keep working with just `metadata_id + content_hash`.

### 3.5 Atomicity refactor in `service.py`

The current service has several commit-points that violate the "one transaction per request" rule once aliases enter the picture:

- `_do_generate` commits the insight row and the audit log together (line 381) — keep, but extend to also commit alias rows in the same transaction (call `reconcile_aliases` before `session.commit()`).
- `_cache_lookup` commits a backfill of `metadata_id` onto a content_hash-keyed row (line 490) — replace with the resolver/collision path. The cache-lookup function returns a row; backfill becomes a separate step at a call-site that owns the transaction.
- `regenerate` commits the supersede of the existing row before generating the replacement (line 233) — keep this commit as a separate phase. The replacement generate+alias write commits in its own transaction. This is acceptable because: (a) if the replacement-generation step fails, the operator-visible state is "old row is gone, new row didn't land" — the user retries and the regen budget will hit, but the cache is empty rather than inconsistent. The alternative (one giant transaction holding the AI call's wall-clock duration) holds a row lock across an external API call, which is worse.
- `_reserve_budget` commits the daily budget counter (line 423) — keep as-is. Budget reservation is intentionally pessimistic: a generation failure does NOT refund the slot (this is the existing PR-C/PR4 behavior and is the right tradeoff to prevent abuse).

The PR2 atomicity invariant in practice: **alias writes always commit in the same transaction as the insight row they reference**. The supersede-and-merge collision path commits in its own transaction (a SEPARATE one from the new generation that triggered it) because the merge resolves on existing rows, not on the row being generated.

### 3.6 Reconciliation collision

The trickiest case: two **separate** live insights exist for what turns out to be the same book. Concretely: an earlier request supplied only `content_hash="Y"` and seeded an insight under `(metadata_id=NULL, content_hash="Y")`. A later, unrelated request supplied `metadata_id="X"` and seeded an insight under `(metadata_id="X", content_hash="Z")`. Now a third request arrives with **both** `content_hash="Y"` AND `metadata_id="X"`, and we discover the two existing rows belong together.

**Rule:** `metadata_id` outranks `content_hash`. The metadata_id-keyed row wins:

1. Load both candidate rows (`(model_id, prompt_version, tone, language)` matching), in the same session.
2. If both are live and distinct: set `loser.superseded_at = now()`, and `winner.previous_insight_ids = stable_dedupe(winner.previous_insight_ids + loser.previous_insight_ids + [loser.id])`.
3. Commit, then return the winner.

Algorithm in pseudocode:

```
def _resolve_canonical(session, ident, user_id):
    # 1. Walk all supplied hints, resolve to canonicals.
    canonicals = set()
    for scheme in IDENTITY_HIERARCHY:
        value = ident.get(scheme)
        if value is None: continue
        c = await resolve_identity(session, scheme, value, user_id)
        if c is not None: canonicals.add(c)

    # 2. Load all live rows matching any canonical.
    rows = await load_live_rows(session, canonicals, model_id, prompt_version, tone, language)

    # 3. If 0 or 1 row: return ident populated with the canonical(s) we have.
    if len(rows) <= 1: return _merge_canonicals_into_ident(ident, canonicals)

    # 4. If 2+ rows: collision. Winner = the one with metadata_id; loser = others.
    winner = next((r for r in rows if r.metadata_id), rows[0])
    losers = [r for r in rows if r.id != winner.id]
    for loser in losers:
        loser.superseded_at = now()
        winner.previous_insight_ids = stable_dedupe(
            (winner.previous_insight_ids or []) + (loser.previous_insight_ids or []) + [loser.id]
        )
    await session.commit()
    return _ident_from_row(winner)
```

The `stable_dedupe` preserves insertion order, so the lineage timeline is preserved. The collision detection runs at `_resolve_canonical` time, **before** the cache lookup short-circuit — this is the architect's correction: the old plan short-circuited when `metadata_id` was present and would have missed the content_hash-keyed row.

This collision is rare. We test it explicitly in §5 because it's the kind of bug that only surfaces in production.

## 4. Cache-key audit test split

The existing `tests/integration/test_cache_key_audit.py` parametrizes `SHARED_CACHE_TABLES` and asserts no `user_id`/`tenant_id` columns. `InsightIdentityAlias` has `user_id`, but the column is intentional cache-key scoping (per-user OPDS aliases), not a tenant-leak. Adding it naively to `SHARED_CACHE_TABLES` would fail the test for the wrong reason.

The fix: split into two parametrize lists with explanatory comments.

```python
SHARED_CACHE_TABLES = [
    pytest.param(BookInsight, id="book_insights"),
    pytest.param(ExternalSourceCacheEntry, id="external_source_cache"),
    # PR3 adds book_themes here.
]

# Tables where `user_id` is INTENTIONAL — per-user scoping of an alias or
# audit row, NOT a tenant-leak on the shared cache. These tables do not
# participate in the cross-tenant cache-hit invariant; their `user_id`
# fragments lookups on purpose.
SCOPED_ALIAS_TABLES = [
    pytest.param(InsightIdentityAlias, id="insight_identity_aliases"),
]
```

The shared-cache test stays as-is. A new test asserts that the scoped tables **do** carry `user_id` (the inverse property: catch a refactor that accidentally removes the scoping), AND that they do NOT carry the other forbidden columns (`tenant_id`, `subject`, `principal_id`). Only `user_id` is allow-listed on scoped tables; tenant audit still belongs in `ai_generation_log`.

## 5. Tests

### 5.1 Unit (`tests/unit/test_ai_identity.py`)

- `resolve_identity` returns the canonical when an alias row exists (global).
- `resolve_identity` returns the canonical when a user-scoped alias row exists for that user.
- `resolve_identity` does NOT return another user's scoped alias.
- `resolve_identity` falls through to global alias when no user-scoped row matches.
- `resolve_identity` returns `(metadata_id, value)` directly when called with a canonical scheme and no row exists (canonical short-circuit).
- `register_alias` is idempotent (writing the same row twice produces one row).
- `register_alias` raises `AliasConflict` when the new canonical disagrees with an existing alias.
- `reconcile_aliases` writes multiple aliases in one transaction.
- `reconcile_aliases` rolls back ALL aliases if any single insert raises (atomicity).

### 5.2 Integration (`tests/integration/test_ai_identity_resolution.py`)

- **Catalog preview then download:** request 1 supplies `(opds_href=<sha>, opds_dc_id="urn:isbn:9780553293357")`, no `metadata_id` / `content_hash`. The server generates an insight under canonical `(metadata_id, "9780553293357")` (because `opds_dc_id` is the strongest hint and dc:identifier normalizes to a metadata_id). Request 2, after download, supplies `(metadata_id="9780553293357", content_hash="abc123")`. The server finds the existing insight via the canonical metadata_id, reconciles by writing a `content_hash`-alias row, and returns the cached insight. One row total in `book_insights`.
- **Reconciliation collision:** seed two live insights (one with metadata_id=X, one with content_hash=Y but metadata_id=NULL). A request that supplies both X and Y triggers supersede-and-merge: the metadata_id row stays live; the content_hash row's `superseded_at` is set; its id appears in the metadata_id row's `previous_insight_ids`.
- **User-scoped alias does not bleed:** user A registers `(opds_href, "shared-href")` → metadata_id X. User B requests with `(opds_href, "shared-href")` and finds no canonical (returns None) — the global path is not touched.
- **Cache-key audit test passes:** both parametrize lists run green; `InsightIdentityAlias` appears in `SCOPED_ALIAS_TABLES`, not `SHARED_CACHE_TABLES`.

## 6. Documentation

- `docs/sync-api.md`: new "Identity resolution" subsection under the AI endpoints. Document the alias schemes accepted in `DocumentIdentity` and the resolution order.
- `docs/superpowers/specs/2026-05-16-identity-aliases-design.md`: this file.
- `docs/superpowers/plans/2026-05-16-identity-aliases.md`: execution plan.

## 7. Risks

- **NULL-in-PK** — addressed by surrogate id + two partial unique indexes (`WHERE user_id IS NOT NULL` / `WHERE user_id IS NULL`).
- **Reconciliation atomicity** — addressed by wrapping the alias write + insight supersede in one transaction; abort rolls everything back.
- **Audit test misclassification** — addressed by splitting `SHARED_CACHE_TABLES` / `SCOPED_ALIAS_TABLES`.
- **Two pre-existing insights collide after reconciliation** — addressed by the metadata_id-wins rule in §3.6 and an explicit test in §5.2.
- **API surface ambiguity** — `DocumentIdentity` adds optional fields; existing callers keep working. Field types are validated by Pydantic; unknown alias schemes raise 422 (not silently ignored).

## 8. Downstream usage

- **PR7 (catalog detail screen)** is the consumer. It calls `POST /ai/v1/insights/lookup` with `(opds_href, opds_dc_id)` only (no `content_hash` yet) and expects the same cache row to come back after the user downloads.
- **PR3 (themes)** is independent — themes hang off `book_insights.id`, which is unaffected by the alias layer.
