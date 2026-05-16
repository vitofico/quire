# Plan — Generic identity aliases (PR2)

**Spec:** `docs/superpowers/specs/2026-05-16-identity-aliases-design.md`
**Branch:** `feat/identity-aliases`
**Worktree:** `.claude/worktrees/pr2-identity-aliases`
**Status:** In progress

## Atomic tasks

### 1. Migration `ai_003_identity_aliases`

- File: `server/migrations/versions/ai_003_identity_aliases.py`
- `revision = "ai_003"`, `down_revision = "ai_002"`, `branch_labels = None`.
- Create table `insight_identity_aliases` with surrogate `id bigserial PRIMARY KEY` plus the columns from §3.1 of the spec.
- Two partial unique indexes (`WHERE user_id IS NOT NULL`, `WHERE user_id IS NULL`).
- One canonical-lookup index.
- Two `CHECK` constraints (canonical scheme allowlist; alias ≠ canonical).
- Downgrade drops both indexes and the table.

**Verify:** `alembic upgrade ai@head` then `alembic downgrade -1` clean against a fresh Postgres.

### 2. ORM model `InsightIdentityAlias`

- Add to `server/opds_sync/db/models.py`.
- Use surrogate `id` BigInteger PK; mirror columns from migration.
- Declare both partial unique indexes and the canonical index in `__table_args__` (these are read by the audit test and by Alembic autogenerate diff checks).
- Block comment: SCOPED ALIAS TABLE, `user_id` intentional (text from §3.2 of spec).

### 3. Module `opds_sync/core/ai/identity.py`

- `CanonicalIdentity` frozen dataclass.
- `AliasConflict(Exception)` raised when a register/reconcile would overwrite with a different canonical.
- `resolve_identity(session, *, alias_scheme, alias_value, user_id=None) -> CanonicalIdentity | None`:
  - If `alias_scheme in {"metadata_id", "content_hash"}`: return canonical short-circuit.
  - Else: `SELECT canonical_scheme, canonical_value FROM insight_identity_aliases WHERE alias_scheme = ? AND alias_value = ? AND (user_id = ? OR user_id IS NULL) ORDER BY (user_id IS NOT NULL) DESC LIMIT 1`.
  - Returns `None` if no match (caller decides whether to fall back to generation).
- `register_alias(session, *, alias_scheme, alias_value, canonical, source, user_id=None)`:
  - Read existing row by `(alias_scheme, alias_value, user_id)` using the same partial-index semantics.
  - If exists and disagrees with `canonical`: raise `AliasConflict`.
  - If exists and agrees: no-op.
  - Else: `session.add(...)`; caller commits.
- `reconcile_aliases(session, *, hints, canonical, source, user_id=None)`:
  - For each `(scheme, value)` in `hints` where `scheme != canonical.scheme or value != canonical.value`:
    - call `register_alias`.
  - Any `AliasConflict` propagates; transaction stays open for the caller to roll back.

### 4. Extend `DocumentIdentity` with optional alias fields

- In `server/opds_sync/api/ai_schemas.py`: add `opds_href: str | None = None`, `opds_dc_id: str | None = None`, `calibre_book_id: str | None = None`, `isbn: str | None = None`.
- Pydantic validates types; missing/unknown fields fall through (no breaking change for PR1).

### 5. Wire `resolve_identity` into `service.py`

For each of `get`, `generate`, `regenerate`, `invalidate`:

- New private helper `_resolve_canonical(session, ident, user_id) -> DocumentIdentity`:
  - If `ident.metadata_id` is set, treat as canonical metadata_id (no DB read).
  - Else if `ident.content_hash` is set, treat as canonical content_hash (no DB read).
  - Else, walk alias fields in identity-hierarchy order and call `resolve_identity` for each one until a canonical is found.
  - If the resolver returns `metadata_id`, populate `ident.metadata_id`. If `content_hash`, populate `ident.content_hash`. If none, return the original ident (the caller's existing cache lookup will miss and fall through to generation under whatever canonical the request implies — for `generate` with no canonical and no resolvable alias, we raise 422 in the API layer rather than letting the service inventing one).
- Apply this helper at the top of `get` / `generate` / `regenerate` / `invalidate` BEFORE cache lookup or lock acquisition.
- Lock key continues to use the canonical (metadata_id-preferred) — no change once `_resolve_canonical` runs first.

### 6. Reconciliation on successful generate

- In `_do_generate`, after the insight row is persisted (`session.flush()`), call `reconcile_aliases` with:
  - `hints = {scheme: value for scheme, value in ident.alias_dict().items() if value is not None}` — collected from the original (pre-resolution) `DocumentIdentity`.
  - `canonical = CanonicalIdentity("metadata_id", row.metadata_id)` if metadata_id else `("content_hash", row.content_hash)`.
  - `source = "opf_extracted"` if a metadata_id is present; else `"opds_feed"`.
  - `user_id = None` (global) for content_hash/metadata_id/isbn aliases; `user_id = user_id` for OPDS-scoped aliases (`opds_href`, `opds_dc_id`, `calibre_book_id`).
- All in the same transaction as the insight insert. If any reconciliation step raises, the transaction is rolled back and the insight is not committed.

### 7. Reconciliation collision handling

- Add a separate helper `_handle_collision(session, *, winning, losing)`:
  - Marks the losing row's `superseded_at = now()`.
  - Appends `losing.id` (plus any of `losing.previous_insight_ids`) to `winning.previous_insight_ids`.
- Called from `_resolve_canonical` when both `metadata_id` and `content_hash` resolve to LIVE insight rows that disagree (different `id`s). The metadata_id-keyed row wins per §3.6.

### 8. Audit test split

- Edit `server/tests/integration/test_cache_key_audit.py`:
  - Keep `SHARED_CACHE_TABLES` as a list of strictly-shared cache tables (currently `BookInsight`, `ExternalSourceCacheEntry`).
  - Add new `SCOPED_ALIAS_TABLES` parametrize list containing `InsightIdentityAlias`.
  - New test `test_scoped_alias_table_carries_user_id` asserts the inverse: that `user_id` IS present (so a future refactor that removes the scoping fails this test loudly).
  - Update module docstring to document the split.

### 9. Tests

**Unit `tests/unit/test_ai_identity.py`** (NEW):
- `test_canonical_short_circuit_no_db_read`
- `test_global_alias_lookup`
- `test_user_scoped_alias_lookup`
- `test_user_scoped_alias_does_not_match_other_user`
- `test_register_alias_idempotent`
- `test_register_alias_conflict_raises`
- `test_reconcile_aliases_writes_all`
- `test_reconcile_aliases_atomicity_on_conflict`

**Integration `tests/integration/test_ai_identity_resolution.py`** (NEW):
- `test_catalog_preview_then_download_converges_to_one_row`
- `test_reconciliation_collision_metadata_id_wins`
- `test_user_scoped_alias_does_not_bleed`

**TDD-FIRST:** Write `test_reconciliation_collision_metadata_id_wins` FIRST. This is the load-bearing edge case. Implementation follows.

**Audit `tests/integration/test_cache_key_audit.py`**:
- Add `test_scoped_alias_table_carries_user_id` (parametrized over `SCOPED_ALIAS_TABLES`).
- Confirm existing `test_shared_cache_table_has_no_tenant_columns` still passes (`InsightIdentityAlias` does NOT appear there).

### 10. Docs

- `docs/sync-api.md`: under the AI section, add a "Identity resolution" subsection documenting the resolution order, the new optional fields on `DocumentIdentity`, and the post-generation reconciliation.

### 11. GPT review (mid-implementation)

After §1–§3 land but before §5–§7 wiring, send the architect a focused review with:
- The migration text (NULL-in-PK pattern).
- The resolver pseudocode.
- The reconciliation collision rule and proposed test.
- The audit-test split.

Max 3 rounds. Capture verdict.

### 12. Verify locally

- 3 modes: `OPDS_SYNC_PROGRESS_ENABLED=true OPDS_SYNC_AI_ENABLED=true`, `progress=true ai=false`, `progress=false ai=true`. The PR2 tests are `requires_ai` so the sync-only mode skips them; AI-only mode runs them. All three matrices green.
- Audit test: shared-cache invariant still holds, scoped-alias invariant new.
- Lint/format via pre-commit.

### 13. Commit, push, PR

- Single commit: `:sparkles: feat(server): generic identity aliases + cache-resolve seam`.
- NO Claude attribution.
- Push: `git push -u origin feat/identity-aliases`.
- `gh pr create --base main --head feat/identity-aliases` with body: summary, schema, reconciliation algorithm, test plan, audit-test split rationale, GPT verdict, downstream PR7 note.

## Order of operations

1. Worktree + branch created.
2. Spec written (this doc and the design doc).
3. Migration + model (§1, §2) → run alembic up/down dry against fresh PG.
4. Unit test for collision (TDD-first per spec).
5. `identity.py` module (§3).
6. `DocumentIdentity` extension (§4).
7. Service wiring (§5–§7).
8. Audit-test split (§8).
9. Remaining tests (§9).
10. Docs (§10).
11. GPT review.
12. Verify in 3 modes.
13. Commit + push + PR.
