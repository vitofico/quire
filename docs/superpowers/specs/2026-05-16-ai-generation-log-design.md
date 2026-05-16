# Spec — `ai_generation_log` table + cache-key tenant audit (PR-C)

**Date:** 2026-05-16
**Branch:** `feat/ai-generation-log` (stacked on `feat/alembic-mode-split` / PR-A)
**Roadmap reference:** `.claude/local/quire-ai/2026-05-16-next-deliverables.md` §PR-C
**Status:** Draft for architect review

## 1. Motivation

`book_insights` is a **shared cache**: a single row keyed by `(metadata_id|content_hash, model_id, prompt_version, tone)` serves every tenant who asks for that identity. The cross-tenant cache-hit property is load-bearing — it is the only reason hosted Quire Cloud AI is economically viable on commodity LLM pricing.

Today there is exactly one user-leaked column on the shared cache: `book_insights.generated_by`, which records "the first user_id that triggered generation". Two problems:

1. **Audit ambiguity.** A cache HIT by tenant B against a row whose `generated_by = "tenant_A_user"` produces a useless audit trail. Billing-wise this is fatal: you cannot count tenant B's API consumption.
2. **Cross-tenant leakage seam.** As long as the column exists and is *read for decisions*, every code path that consults it risks deciding things ("did THIS user generate this?") that bleed identity across tenants.

PR-A landed the deploy-mode infrastructure (alembic branches, mode flags). PR-C is the first migration on the `ai` branch. It does two intertwined things:

1. Introduces `ai_generation_log` — a per-call audit log indexed by tenant, foreign-keyed to `book_insights.id`. Future billing rollups query this table; the shared cache stays tenant-blind.
2. Stops reading `book_insights.generated_by` for any decision. The column stays for now (read by no one). A later PR nulls it; a later still PR may drop it.

Additionally, this PR codifies the **cache-integrity invariant** as a test and as a code comment, so future schema PRs (PR2 aliases, PR3 themes) can't accidentally re-introduce a `user_id`/`tenant_id` column on a shared cache table.

## 2. Cache-integrity invariant

Stated formally:

> The shared-cache tables — `book_insights`, `external_source_cache`, and (in future PRs) `book_themes`, `insight_identity_aliases` whose `user_id` is NULL — MUST NOT carry any user-identifying column (`user_id`, `tenant_id`, `subject`, `principal_id`, or equivalent) that participates in their cache key or that is read for any cache decision.
>
> Per-tenant audit and billing live in `ai_generation_log`, which references `book_insights.id` and carries the tenant/subject/request-id fields.

PR-C ships a parametrized test (`tests/integration/test_cache_key_audit.py`) that introspects each shared-cache table via SQLAlchemy reflection and asserts the absence of forbidden columns. The test is mechanically future-proof: a future schema PR that adds `user_id` to (say) `book_themes` makes the audit test fail at CI time.

The same invariant is documented as a docstring comment on `BookInsight` and `ExternalSourceCacheEntry` in `db/models.py`.

`generated_by` is grandfathered: the audit test ignores it explicitly with a comment pointing to the deprecation plan ("no longer read for any decision; follow-up nulls then drops").

## 3. Schema: `ai_generation_log`

### 3.1 Migration

- Filename: `migrations/versions/ai_001_generation_log.py`.
- Convention (per PR-A's `migrations/README.md`):
  - `revision = "ai_001"`
  - `down_revision = "0004"` (split point on the backbone)
  - `branch_labels = ("ai",)` (first migration on the `ai` branch)
  - `depends_on = None`
- Created via `alembic revision --head=0004 --splice --branch-label=ai -m "ai_001 generation_log"` (or hand-authored to the same shape).

### 3.2 DDL

```sql
CREATE TABLE ai_generation_log (
    id              BIGSERIAL    PRIMARY KEY,
    book_insight_id BIGINT       NOT NULL REFERENCES book_insights(id) ON DELETE CASCADE,
    tenant_id       TEXT         NOT NULL DEFAULT 'local',
    subject         TEXT         NOT NULL,
    request_id      TEXT         NULL,
    model_id        TEXT         NOT NULL,
    prompt_version  TEXT         NOT NULL,
    latency_ms      INTEGER      NULL,
    status          TEXT         NOT NULL,  -- 'hit' | 'miss' | 'error'
    error_class     TEXT         NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_ai_generation_log_status
        CHECK (status IN ('hit', 'miss', 'error'))
);

CREATE INDEX ix_ai_generation_log_tenant_created
    ON ai_generation_log (tenant_id, created_at);
CREATE INDEX ix_ai_generation_log_book_insight
    ON ai_generation_log (book_insight_id);
```

Field rationale:

| Column | Why |
|--------|-----|
| `book_insight_id` (FK CASCADE) | Anchors the audit row to the actual cache row served. Invalidating an insight cleans up its history. |
| `tenant_id` (default `'local'`) | Single-tenant deployments (today's 99%) keep emitting `'local'`. Hosted Quire Cloud AI emits the real tenant id via `AiPrincipal.tenant_id` (PR-B). |
| `subject` | The principal (user_id today, JWT/HMAC sub tomorrow). Required, non-null — every call is attributable to someone. |
| `request_id` | Nullable because background/internal flows may have none. Populated from the `request_id_var` ContextVar set by PR-A's `RequestIDMiddleware`. |
| `model_id`, `prompt_version` | Captured at log-write time so the audit row survives prompt/model bumps even if the parent `book_insights` row is later regenerated. |
| `latency_ms` | Wall-clock generation latency for `miss`/`error`. For `hit`, the cache-lookup cost (or 0). Nullable to keep the column honest if we can't measure. |
| `status` | Tri-state: `hit` (served from cache), `miss` (we called the model), `error` (we tried and failed). Check constraint enforces. |
| `error_class` | Class name of the exception that escaped `chat_structured`. Null on success. |
| `created_at` | `now()` server default; index supports `(tenant_id, created_at)` for billing rollups. |

The `(tenant_id, created_at)` index is the **future billing path**: "sum `status='miss'` per tenant per day". The `(book_insight_id)` index supports the audit-UI use case (PR6).

### 3.3 What is NOT on this table

- No `user_id` column distinct from `subject` — they're the same thing under different names; `subject` is the forward-compatible spelling.
- No `cost_*` columns — pricing varies by provider and changes over time; compute downstream from `(model_id, status)`.
- No `prompt_text` or `payload` — those go in the cache row or in structured logs, not the audit table. Storing them here would balloon the table and re-introduce PII concerns.
- No `cache_key_hash` — the FK to `book_insights.id` is the cache-key handle.

## 4. ORM model

A new `AIGenerationLog` SQLAlchemy declarative model is added to `opds_sync/db/models.py`. It mirrors the migration exactly:

```python
class AIGenerationLog(Base):
    __tablename__ = "ai_generation_log"
    __table_args__ = (
        CheckConstraint(
            "status IN ('hit', 'miss', 'error')",
            name="ck_ai_generation_log_status",
        ),
        Index("ix_ai_generation_log_tenant_created", "tenant_id", "created_at"),
        Index("ix_ai_generation_log_book_insight", "book_insight_id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    book_insight_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("book_insights.id", ondelete="CASCADE"), nullable=False
    )
    tenant_id: Mapped[str] = mapped_column(
        String, nullable=False, server_default=text("'local'"), default="local"
    )
    subject: Mapped[str] = mapped_column(String, nullable=False)
    request_id: Mapped[str | None] = mapped_column(String, nullable=True)
    model_id: Mapped[str] = mapped_column(String, nullable=False)
    prompt_version: Mapped[str] = mapped_column(String, nullable=False)
    latency_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    status: Mapped[str] = mapped_column(String, nullable=False)
    error_class: Mapped[str | None] = mapped_column(String, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
```

The PR also adds a comment block above `BookInsight` declaring the cache-integrity invariant in code, and a similar comment above `ExternalSourceCacheEntry`.

## 5. Service-layer changes (`opds_sync/core/ai/service.py`)

### 5.1 Logging semantics — one row per waiter

The orchestrator emits exactly one `ai_generation_log` row per `generate()` / `regenerate()` / `get()` *call* (i.e., per waiter), regardless of cache state. This is the explicit design from the roadmap: cache hits and misses both count, billing decides how to weight them.

Per-call-shape:

| Call site | When the row is written | `status` | `latency_ms` | `error_class` |
|-----------|------------------------|----------|--------------|---------------|
| `generate()` / `regenerate()` cache HIT (first lookup) | After the hit, before returning | `'hit'` | 0 (or short lookup time) | NULL |
| `generate()` cache HIT under the lock (a coalesced waiter) | After the second lookup | `'hit'` | 0 | NULL |
| `generate()` / `regenerate()` cache MISS — model call succeeds | After `BookInsight` row is committed; FK is the new row's id | `'miss'` | wall-clock around `chat_structured` (already measured today) | NULL |
| `generate()` / `regenerate()` cache MISS — model call raises | **No DB row written** (no FK target); see §5.3 for the structured-log substitute. | n/a (no row) | n/a (logged) | n/a (logged) |
| `get()` cache HIT | After the hit, before returning | `'hit'` | 0 | NULL |
| `get()` cache MISS | No log row (no work was done, no decision to audit) | — | — | — |

The `get()`-miss case is deliberately silent: `get()` is a pure cache probe (used by the GET endpoint that returns 404 when there's nothing cached), and emitting a log row per probe would make the table grow with frontend polling rather than with billable AI work. (We can revisit if we later need probe-rate visibility — it's an additive change.)

### 5.2 Coalesced waiters

When N concurrent `generate()` callers hit the same identity+tone, today's lock serializes them; the first acquires the model call, the remaining N-1 see the cache row on their second lookup and return.

PR-C preserves this semantics and emits **N rows total**: one `'miss'` for the generator + (N-1) `'hit'` for the waiters. Each waiter row carries the waiter's own `(tenant_id, subject, request_id)` and FKs to the same `book_insight_id`. This is exactly what billing needs: tenant B gets a "hit" charge for a row that tenant A's "miss" populated.

The existing test `test_concurrent_generations_collapse_to_one_model_call` already asserts "one model call". PR-C adds a sibling test that also asserts "three `ai_generation_log` rows: one miss + two hits, all FKing to the same insight".

### 5.3 Error rows and the FK problem

A model-call failure has no `book_insights` row to FK to (the row is committed *after* `chat_structured` returns). Options:

1. **Make `book_insight_id` nullable.** Simple, but weakens the schema (most rows will FK, a minority won't, and the FK loses meaning for `error` rows).
2. **Don't log errors at the `ai_generation_log` level.** Errors already produce a structured log line; downstream operators query logs, not this table.
3. **Synthesize a sentinel row.** Adds complexity for one rare case.

Decision: **Option 2.** `ai_generation_log` rows always have a non-null FK to a real `book_insights` row. Errors are logged via the structured logger (this PR keeps the existing `logger.info(...)` and adds a `logger.warning("ai.generate.error ...")` on the exception path that emits `event=ai.generate.error`, `tenant_id`, `subject`, `model_id`, `prompt_version`, `error_class`, and reads `request_id` from the `request_id_var` ContextVar via the existing log filter) and are out of scope for the FK-anchored audit table.

This means the `status` check constraint accepts `'hit' | 'miss' | 'error'` for forward-compat, but `'error'` rows are never written in PR-C. We keep the constraint permissive so a future PR (when we add nullable-FK or sentinel rows) doesn't require a schema bump for the values. The migration comment documents this.

PR-C adds a test (`test_generate_error_emits_structured_log`) that injects a fake AI client raising an exception and asserts the structured log line is emitted with the right fields. This closes the observability gap so error rates remain queryable from log aggregation even though they don't appear in `ai_generation_log`.

Trade-off considered and accepted: we lose per-error tenant attribution in the audit table. Operators get it from logs (structured, includes `tenant_id` and `request_id` via the log filter), which is a more natural fit for error analysis anyway.

### 5.4 ContextVar plumbing and tenant_id parameter

`tenant_id`, `subject`, and `request_id` arrive at the orchestrator via different paths:

- `request_id`: `request_id_var.get()` (already imported by PR-A's logging filter). No signature change required.
- `subject`: today this is `user_id`, the same string already threaded through `generate(user_id=...)`. Renaming the parameter is out of scope; we use `user_id` internally and write it to the `subject` column.
- `tenant_id`: **Per-call kwarg** on `get()`, `generate()`, and `regenerate()`, defaulting to `"local"`. PR-B will pass `AiPrincipal.tenant_id` from the API layer; until then every caller passes `"local"` (or omits the kwarg).

The per-call kwarg approach replaces an earlier draft that put `default_tenant_id` on the orchestrator constructor. The constructor-level default is wrong because the orchestrator is a long-lived singleton on `app.state` shared across requests — a single process-wide default can't carry per-waiter tenant info. The per-call kwarg matches how `user_id` is already threaded.

Internal helper `_log_generation(session, *, book_insight_id, subject, tenant_id, status, latency_ms=None, error_class=None)` writes the row using `request_id_var.get()` for the `request_id` column.

For coalesced waiters: each waiter calls `generate(..., user_id=..., tenant_id=...)` with its own values; the lock-holder's `miss` row uses the lock-holder's `(user_id, tenant_id)`, each waiter's `hit` row uses that waiter's own `(user_id, tenant_id)`. This is exactly the billing semantic we want.

### 5.5 Stop reading `generated_by`

Audit: grep the codebase for `generated_by` and confirm no read sites remain.

```bash
$ rg "generated_by" server/
opds_sync/core/ai/service.py:    generated_by=user_id,  # WRITE site — kept until follow-up migration nulls the column
opds_sync/db/models.py:    generated_by: Mapped[str] ...   # column declaration
migrations/versions/0003_ai_tables.py: sa.Column("generated_by", ...)  # historical
```

There are no read sites today (the column is write-only). PR-C therefore does NOT modify any code that "stops reading" `generated_by` — there's nothing to remove. The PR keeps the write site intact (the column is NOT NULL; removing the write would break inserts until a follow-up nulls/drops) and adds a code comment marking the column as deprecated-write-only.

A follow-up PR will:
1. Add a migration that makes `generated_by` NULLABLE (and stop the orchestrator from writing it).
2. A still-later migration drops the column.

This phased approach keeps every PR in this batch independently reversible.

## 6. Tests

### 6.1 Unit (`server/tests/unit/test_ai_service.py` — additions)

- `test_generate_writes_miss_then_subsequent_call_writes_hit`: one `generate()` produces a `'miss'` row; a second `generate()` on the same identity produces a `'hit'` row; both FK the same `book_insight_id`; both rows carry their respective `user_id` in `subject`.
- `test_concurrent_generations_emit_one_miss_and_n_minus_one_hits`: extend the existing coalescing test to also assert exactly 1 `'miss'` and N-1 `'hit'` rows.
- `test_get_hit_writes_log_row`: `get()` against a pre-populated insight writes a `'hit'` row.
- `test_get_miss_writes_no_log_row`: `get()` against an empty cache returns `None` and writes nothing.
- `test_log_carries_request_id_when_set`: with `request_id_var` set, the log row's `request_id` matches.
- `test_log_default_tenant_id_is_local`: default deployments produce `tenant_id='local'` (when `tenant_id` kwarg omitted).
- `test_log_uses_passed_tenant_id`: when `generate(..., tenant_id="acme")` is called, the row carries `tenant_id='acme'`.
- `test_generate_error_emits_structured_log`: inject a fake AI client that raises; assert a `logger.warning` is emitted with `event=ai.generate.error`, `tenant_id`, `subject`, and `error_class` fields populated; assert no `ai_generation_log` row was written. Use `caplog` fixture.

#### Unit-conftest cleanup (`server/tests/unit/conftest.py`)

The autouse `_truncate_external_source_cache` fixture (despite its name) truncates `external_source_cache, book_insights, ai_usage_daily`. After PR-C, `ai_generation_log` is a FK child of `book_insights`, so truncating `book_insights` without `CASCADE` will raise `Cannot truncate a table referenced in a foreign key constraint`. The fixture must add `ai_generation_log` to the list AND use `CASCADE`, or list children first. PR-C goes with `TRUNCATE TABLE ai_generation_log, external_source_cache, book_insights, ai_usage_daily CASCADE` for simplicity and order-independence.

### 6.2 Integration — multi-tenant audit (`server/tests/integration/test_ai_audit_log.py`)

- `test_two_tenants_share_one_insight_two_log_rows`: simulate two requests with different `subject` and different `tenant_id` (passed via the per-call kwarg) for the same `DocumentIdentity`, **each using its own `AsyncSession`** (separate session factories from the shared engine). Assert exactly one `book_insights` row and exactly two `ai_generation_log` rows; one `miss` + one `hit`; both FK the same id; tenant IDs distinct.

The separate-session pattern matters: it exercises real transaction-visibility (waiter B's session must see the committed `book_insights` row from waiter A's session). The orchestrator is constructed once and shared, mirroring the production app-state singleton.

### 6.3 Integration — cache-key audit (`server/tests/integration/test_cache_key_audit.py`)

Parametrized over `[BookInsight, ExternalSourceCacheEntry]` (and forward-compat tags for `BookTheme` and `InsightIdentityAlias` once they exist):

- `test_shared_cache_table_has_no_tenant_columns`: SQLAlchemy reflection of each table's columns; assert that none of `{"user_id", "tenant_id", "subject", "principal_id"}` appear, with `generated_by` allow-listed for `BookInsight` with a comment.

This test is **future-proof**: PR2 (`insight_identity_aliases`) and PR3 (`book_themes`) will need to add themselves to the parametrize list. The test docstring states this explicitly so the next PR author can't miss it.

### 6.4 Mode matrix and PR-A test fallout

The CI matrix PR-A landed runs the suite under three flag combinations. PR-C's tests are marked `requires_ai` (they need the AI router and orchestrator), so:

- Full-stack: tests run.
- Sync-only (`AI_ENABLED=false`): tests skip cleanly.
- AI-only (`PROGRESS_ENABLED=false`): tests run.

`requires_progress` tests continue to skip in AI-only mode (no change from PR-A).

**Fallout from materializing the first `ai` branch migration:** PR-A's session-scoped `alembic_upgrade` fixture in `tests/conftest.py` runs `command.upgrade(cfg, "head")` unconditionally — bypassing the mode-aware `scripts/migrate.py::run_migrations` wrapper. With `ai_001` present, "head" advances the DB to `ai_001`, which breaks:

- `tests/integration/test_readyz_migration_state.py::test_readyz_200_when_at_backbone_no_labels` — asserts `body["heads_applied"] == ["0004"]`, but the DB is now at `ai_001`.
- `tests/integration/test_readyz_migration_state.py::test_readyz_200_with_neither_mode_at_backbone` — same: `body["heads_applied"]` is now `["ai_001"]`, not `["0004"]`. The readyz check also incorrectly reports the backbone as "missing" because `_required_heads` falls back to `{0004}` when no branch is enabled, but `current = {ai_001}`.

**Fix (smaller blast radius than rewriting `_required_heads`):**

1. Change `tests/conftest.py::alembic_upgrade` to invoke the mode-aware wrapper:

   ```python
   from scripts.migrate import run_migrations
   run_migrations(cfg, progress_enabled=True, ai_enabled=True)
   ```

   This still applies all branches for tests that need them (the default for session-scoped fixtures), but uses the production code path so any future divergence is caught.

2. Update the two readyz assertions:
   - `test_readyz_200_when_at_backbone_no_labels`: expect `["ai_001"]` (the new `ai@head`). Rename to `test_readyz_200_when_at_ai_head`.
   - `test_readyz_200_with_neither_mode_at_backbone`: this test must explicitly stamp the DB to `0004` (because the session-scoped fixture brings everything up), so the assertion `["0004"]` makes sense again. Use the existing `_stamp(postgres_url, "0004")` helper.

3. `test_readyz_503_when_db_below_backbone` keeps working — it explicitly stamps to `0003` then checks for 503 with `0004` missing.

This keeps `_required_heads` exactly as PR-A wrote it (don't fix what isn't broken in production) and only adjusts test setup. The production wrapper `run_migrations` is already correct; the test fixture was the outlier.

## 7. Downgrade story

The migration's `downgrade()`:

```python
def downgrade() -> None:
    op.drop_index("ix_ai_generation_log_book_insight", table_name="ai_generation_log")
    op.drop_index("ix_ai_generation_log_tenant_created", table_name="ai_generation_log")
    op.drop_table("ai_generation_log")
```

The CASCADE FK means deleting a `book_insights` row already cleans up its log children — no cross-branch coordination needed when this migration is rolled back independently.

Per PR-A's wrapper, downgrades are manual:

```bash
alembic downgrade ai@-1
```

This removes `ai_001_generation_log` and returns `ai@head` to `0004` (i.e., the `ai` branch no longer has a head; only the backbone remains). Subsequent `ai` migrations (PR4 `ai_002_insight_language`, PR2's aliases, PR3's themes) chain off `ai_001`, so they downgrade in the usual reverse order before this one.

## 8. Risks and mitigations

| Risk | Mitigation |
|------|-----------|
| Doubled writes on every AI call (cache row + log row) increase commit cost. | Both writes happen in the same `session.commit()` block for `miss` rows. `hit` rows are a single-row insert and a single commit — negligible. |
| Log table grows unbounded. | Out of scope here; future operator concern. Schema supports straightforward `DELETE FROM ai_generation_log WHERE created_at < now() - interval '90 days'`. |
| FK CASCADE accidentally deletes audit history on `invalidate()`. | Intended: invalidating a cache row also invalidates its audit story; billing rollups should snapshot/aggregate before invalidation if they need durable history. Documented in code comment near the FK. |
| Future PR adds `user_id` to a shared cache table by accident. | Cache-key audit test fails at CI. |
| `tenant_id` default `'local'` masks a missed PR-B integration. | Acceptable: PR-B will exercise the override; until then `'local'` is the only correct value (single-tenant). |
| Coalesced waiter "hit" rows could race with the lock-holder's "miss" row commit (FK violation). | Waiters' `_cache_lookup` happens AFTER the lock-holder's `session.commit()` releases the lock — by the time a waiter writes its `hit` row, the FK target is committed and visible. The existing serialization is sufficient; we add a regression test covering it. |

## 9. Out of scope

- PR-B's `AiPrincipal`/`tenant_id` real plumbing (`'local'` is hardcoded).
- Nulling or dropping `book_insights.generated_by` (separate follow-up PRs).
- Cost computation, billing rollups, retention policy.
- `'error'` rows in `ai_generation_log` (constraint allows them; PR keeps the column shape stable so the row schema doesn't need to bump when we add nullable-FK or sentinel rows).
- Android UI changes (PR-C is server-only; PR6 will surface log data in the audit UI).

## 10. Acceptance checklist

- [ ] `migrations/versions/ai_001_generation_log.py` exists with correct branch labels.
- [ ] `AIGenerationLog` model added to `db/models.py`.
- [ ] Cache-integrity invariant comment added above `BookInsight` and `ExternalSourceCacheEntry` in `models.py`.
- [ ] `service.py` writes one log row per call (hit / miss per §5.1 table; errors emit structured logs only).
- [ ] `tenant_id` is a per-call kwarg on `get()`, `generate()`, `regenerate()`, defaulting to `"local"`.
- [ ] `tests/unit/conftest.py` truncate fixture updated to include `ai_generation_log` and `CASCADE`.
- [ ] `tests/conftest.py::alembic_upgrade` uses `scripts.migrate.run_migrations` instead of `command.upgrade(cfg, "head")`.
- [ ] `tests/integration/test_readyz_migration_state.py` assertions updated for `ai_001` head.
- [ ] No code path reads `book_insights.generated_by`.
- [ ] API layer (`opds_sync/api/ai.py`) passes `tenant_id="local"` (today's default) on all `orch.get/generate/regenerate` calls, AND passes `user_id` into `orch.get()` for hit auditing.
- [ ] Tests added in §6 all pass.
- [ ] All existing tests pass under all three mode-matrix combinations.
- [ ] `requires_ai` tests skip in sync-only mode; `requires_progress` tests skip in ai-only mode.
- [ ] Spec + plan committed under `docs/superpowers/`.
- [ ] PR body documents the cache-integrity invariant and lists future PRs that must respect it (PR2 aliases, PR3 themes, PR4 language).
