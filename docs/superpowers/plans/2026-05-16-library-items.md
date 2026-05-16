# PR1 — `library_items` table + Android upload — Implementation Plan

**Spec:** `docs/superpowers/specs/2026-05-16-library-items-design.md`
**Branch:** `feat/library-items` (in worktree `.claude/worktrees/pr1-library-items`)
**Base:** `main` at `769e9e4`

## Status legend

- `[ ]` pending
- `[x]` done

## Stage 1 — Server: schema + ORM

Goal: a new `library_items` table on the `progress` alembic branch, plus its SQLAlchemy model.

- [ ] **1.1 Author migration `progress_001_library_items`.**
  - File: `server/migrations/versions/progress_001_library_items.py`.
  - `revision = "progress_001"`, `down_revision = "0004"`, `branch_labels = ("progress",)`.
  - Columns per spec (`user_id`, `metadata_id`, `content_hash`, `title`, `authors` jsonb, `series_name`, `series_index` numeric, `isbn`, `language`, `subjects` jsonb, `opds_href`, `created_at`, `updated_at`, `deleted_at`). Surrogate `pk` (BigInteger autoincrement) primary key.
  - Indexes: `uq_library_items_user_content` unique on `(user_id, content_hash)`; `uq_library_items_user_metadata` partial unique on `(user_id, metadata_id) WHERE metadata_id IS NOT NULL`; `ix_library_items_user_series_alive` partial on `(user_id, series_name) WHERE deleted_at IS NULL`; `ix_library_items_user_updated` on `(user_id, updated_at)`.
  - `downgrade()` drops indexes then the table.
  - Verify: `cd server && uv run alembic heads --verbose` shows both `progress@head` and `ai@head` after this lands.

- [ ] **1.2 Add `LibraryItem` ORM model in `server/opds_sync/db/models.py`.**
  - Mirror the migration. Use SQLAlchemy `JSONB` (via `from sqlalchemy.dialects.postgresql import JSONB`) for `authors`/`subjects`. Use `Numeric` for `series_index`.
  - The model lives next to `Document`/`Progress` (progress-mode neighbours).
  - **No relationship to `Document`** — `library_items` is keyed by `(user_id, content_hash)`, not by `documents.pk`. They will converge in PR2 via identity aliases; PR1 keeps them independent.

**Verification**: `cd server && uv run pytest tests/integration/test_schema.py -v`. The new test in stage 4 will exercise this.

## Stage 2 — Server: API + Pydantic schemas

Goal: `/library/v1/items` PUT/GET/DELETE, mode-gated.

- [ ] **2.1 Create `server/opds_sync/api/library_schemas.py`.**
  - `LibraryItemRequest` (the body of a PUT): `metadata_id: str | None`, `content_hash: str`, `title: str`, `authors: list[str]`, `series_name: str | None`, `series_index: float | None`, `isbn: str | None`, `language: str | None`, `subjects: list[str] = []`, `opds_href: str | None`.
  - `LibraryItemPutBody { item: LibraryItemRequest }`.
  - `LibraryItemIdentity { content_hash: str }`.
  - `LibraryItemDeleteBody { item: LibraryItemIdentity }`.
  - `LibraryItemResponse` (the persisted row): all request fields PLUS `created_at`, `updated_at`, `deleted_at` (datetimes; nullable for `deleted_at`).
  - `LibraryItemListResponse { items: list[LibraryItemResponse], server_time: datetime }`.
  - Use `pydantic.field_serializer` for datetimes (match `progress.py` style).

- [ ] **2.2 Create `server/opds_sync/api/library.py`.**
  - `router = APIRouter(tags=["library"])`.
  - `PUT /items` handler:
    - Resolve `user_id` via `current_user_id` dep.
    - Find by `(user_id, content_hash)`. If found and `metadata_id` differs and `body.metadata_id` matches another existing row, return `409` with `{"error": "metadata_id_conflict", "existing_content_hash": "<other>"}`.
    - Else upsert: update all payload fields, set `updated_at = now()`, clear `deleted_at`.
    - Return `LibraryItemResponse`.
  - `GET /items` handler:
    - Query params: `since: str | None`, `limit: int = 200` (cap 1000), `offset: int = 0`.
    - `server_time = datetime.now(UTC)` (captured BEFORE the SELECT).
    - Base query filtered to `user_id`; if `since` absent, also filter `deleted_at IS NULL`.
    - Else: parse `since` with `datetime.fromisoformat(since.replace(" ", "+"))`, filter `updated_at > since_dt`, AND `updated_at <= server_time`.
    - `ORDER BY updated_at ASC, pk ASC`; apply `limit` + `offset`.
    - Return `LibraryItemListResponse`.
  - `DELETE /items` handler:
    - Body shape `LibraryItemDeleteBody`. Find by `(user_id, content_hash)`.
    - `404` if absent.
    - If already deleted: return existing row UNCHANGED (no timestamp refresh).
    - Else: `deleted_at = now()`, `updated_at = now()`. Return updated row.

- [ ] **2.3 Mount the router in `server/opds_sync/main.py`.**
  - Inside `if settings.progress_enabled:` block, after the existing progress router mount:
    ```python
    from opds_sync.api.library import router as library_router
    app.include_router(library_router, prefix="/library/v1")
    ```
  - Verify lazy-import boundary: the import sits inside the `if` block.

## Stage 3 — Server: tests

- [ ] **3.1 Extend `server/tests/integration/test_schema.py`.**
  - Add `test_library_items_table_exists` marked `@pytest.mark.requires_progress`.
  - Introspect columns, partial unique index on `(user_id, metadata_id)`, partial series-name index, `(user_id, updated_at)` index.

- [ ] **3.2 Create `server/tests/integration/test_library_items.py`.**
  - Module-level `pytestmark = pytest.mark.requires_progress`.
  - Tests (every spec'd test from §"Tests / Server"):
    - `test_put_creates_then_returns_row`
    - `test_put_idempotent_updates_payload`
    - `test_delete_soft_deletes_then_put_reactivates`
    - `test_get_since_includes_tombstones`
    - `test_user_isolation`
    - `test_large_arrays_round_trip` (50 authors, 50 subjects)
    - `test_put_missing_required_fields_returns_422` (3 sub-cases)
    - `test_metadata_id_conflict_returns_409`
    - `test_pagination_limit_and_offset`
    - `test_get_ordering_stable_with_pk_tiebreaker`
    - `test_get_server_time_bounds_concurrent_writes`
    - `test_delete_bumps_updated_at_for_tombstone_delivery`
    - `test_delete_idempotent_does_not_refresh_updated_at`
  - Use `app_under_test` fixture + httpx `AsyncClient` + ASGITransport, basic auth headers via `_basic("alice", "alicepass")`.

- [ ] **3.3 Extend `server/tests/integration/test_modes.py`.**
  - Existing test enumerates mode-gated paths. Add `/library/v1/items` to the list so AI-only mode confirms it returns 404.

**Verification (all stages 1–3):**

```bash
cd server && uv run pytest -v
# All three modes (the existing matrix CI runs these via env-var flips):
cd server && OPDS_SYNC_PROGRESS_ENABLED=true OPDS_SYNC_AI_ENABLED=true uv run pytest -v
cd server && OPDS_SYNC_PROGRESS_ENABLED=true OPDS_SYNC_AI_ENABLED=false uv run pytest -v
cd server && OPDS_SYNC_PROGRESS_ENABLED=false OPDS_SYNC_AI_ENABLED=true uv run pytest -v
```

All three modes must be green. Cache-key audit (`test_cache_key_audit.py`) remains untouched and must still pass — `library_items` is user-scoped and out of scope for that audit.

## Stage 4 — Android: Room v4 → v5

- [ ] **4.1 Update `DocumentEntity`.** Add `val seriesName: String? = null, val seriesIndex: Double? = null`.

- [ ] **4.2 Update `EReaderDatabase`.** Bump `version = 5`. Add `MIGRATION_4_5`:
  ```kotlin
  internal val MIGRATION_4_5 = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE documents ADD COLUMN seriesName TEXT")
          db.execSQL("ALTER TABLE documents ADD COLUMN seriesIndex REAL")
      }
  }
  ```
  Append to `addMigrations(...)`.

- [ ] **4.3 Update `core/model/Document.kt`.** Add `val seriesName: String? = null, val seriesIndex: Double? = null` to the data class.

- [ ] **4.4 Update `DocumentRepository.toDomain()` and `.insert()`.**
  - `toDomain` propagates the two new fields.
  - `.insert(...)` grows two new parameters with defaults `null`.

- [ ] **4.5 Update `MetadataBundle`.** Change `seriesPosition: Int? = null` to `seriesPosition: Double? = null`.

- [ ] **4.6 Update `OpfMetadataExtractor.parseSeries()`.** Return `Pair<String?, Double?>` instead of `Pair<String?, Int?>`. Replace `toFloatOrNull()?.toInt()` with `toDoubleOrNull()` in both branches.

- [ ] **4.7 Regenerate exported Room schema.** Running tests writes `data/local/schemas/io.theficos.ereader.data.local.db.EReaderDatabase/5.json`. Commit it.

- [ ] **4.8 Migration test.**
  - Extend `data/local/src/test/java/io/theficos/ereader/data/local/db/MigrationTest.kt` with `migrate 4 to 5 adds series columns`:
    - Create v4 DB, insert a document row.
    - Run `MIGRATION_4_5`.
    - Assert `seriesName IS NULL` and `seriesIndex IS NULL` on the migrated row.
    - Assert a fresh insert can write non-null series values.

- [ ] **4.9 Repository round-trip test.**
  - Extend `DocumentRepositoryTest` (or new file) to insert a document with `seriesName="Foundation"`, `seriesIndex=1.5`, read back, assert equality.

- [ ] **4.10 OPF extractor test.** Extend `OpfMetadataExtractorTest` with a fixture containing `<meta name="calibre:series_index" content="1.5"/>`; assert `bundle.seriesPosition == 1.5`.

**Verification:**
```bash
scripts/dgradle :data:local:testDebugUnitTest :core:metadata:testDebugUnitTest
```

## Stage 5 — Android: sync DTOs + client

- [ ] **5.1 New file `data/sync/src/main/java/io/theficos/ereader/data/sync/LibraryItemDtos.kt`.**
  - `LibraryItemRequestDto` (data class, `@Serializable`): all payload fields, `@SerialName` mappings to snake_case.
  - `LibraryItemPutBody { val item: LibraryItemRequestDto }`.
  - `LibraryItemIdentityDto { @SerialName("content_hash") val contentHash: String }`.
  - `LibraryItemDeleteBody { val item: LibraryItemIdentityDto }`.
  - `LibraryItemResponseDto` — same fields as request plus `@SerialName("created_at") val createdAt: String`, `@SerialName("updated_at") val updatedAt: String`, `@SerialName("deleted_at") val deletedAt: String?`.
  - `LibraryItemListResponse { val items: List<LibraryItemResponseDto>, @SerialName("server_time") val serverTime: String }`.

- [ ] **5.2 Update `SyncApi.kt`.** Add `const val PATH_LIBRARY_ITEMS = "/library/v1/items"`.

- [ ] **5.3 Extend `SyncClient.kt`.**
  - `putLibraryItem(body: LibraryItemPutBody): SyncResult<LibraryItemResponseDto>` — PUT with JSON body.
  - `deleteLibraryItem(body: LibraryItemDeleteBody): SyncResult<LibraryItemResponseDto>` — DELETE with body via `Request.Builder.method("DELETE", payload)`.
  - `pullLibraryItems(sinceIso8601: String?, limit: Int = 200, offset: Int = 0): SyncResult<LibraryItemListResponse>` — GET with query params.
  - Add a `put(...)` helper symmetric with the existing `post(...)` private helper.

- [ ] **5.4 Tests in `SyncClientTest`.**
  - 200 → Success (parses response, including timestamps).
  - 401 → Unauthorized.
  - 422 → HttpFailure(422).
  - 409 → HttpFailure(409).
  - DELETE-with-body sends body bytes (capture the request and assert on the JSON shape).
  - `pullLibraryItems` round-trips `server_time` and an item list.

**Verification:**
```bash
scripts/dgradle :data:sync:testDebugUnitTest
```

## Stage 6 — Android: orchestrator wiring

- [ ] **6.1 Add `SyncOrchestrator.uploadLibraryItem(documentId, bundle, opdsHref)`.**
  - Looks up the document via `documentRepo.findById(documentId)`.
  - Builds a `LibraryItemRequestDto`:
    - `contentHash = doc.identity.contentHash` (always).
    - `metadataId = doc.identity.metadataId`.
    - `title = bundle.title.ifBlank { doc.title }`.
    - `authors = listOfNotNull(bundle.author ?: doc.author).filter { it.isNotBlank() }` — single-element list per spec.
    - `seriesName = bundle.seriesName`, `seriesIndex = bundle.seriesPosition`.
    - `isbn = bundle.isbn`, `language = bundle.language`, `subjects = bundle.subjects`.
    - `opdsHref = opdsHref`.
  - Calls `client.putLibraryItem(...)`. On `HttpFailure(409)` log and return — terminal.
  - Other failures log and return (download path is fire-and-forget; reconcile pass picks up later).

- [ ] **6.2 Add `SyncOrchestrator.deleteLibraryItem(identity)`.**
  - Calls `client.deleteLibraryItem(LibraryItemDeleteBody(LibraryItemIdentityDto(contentHash = identity.contentHash)))`.
  - Returns `SyncResult<Unit>` (the response body is the soft-deleted row, but callers don't need it).
  - 404 is benign — treat as success-ish (log, return Success).

- [ ] **6.3 Add `SyncOrchestrator.reconcileLibraryItems()`.**
  - Pulls the server alive set: page through `client.pullLibraryItems(sinceIso8601 = null, limit = 200, offset = ...)` until short page.
  - Collects server `content_hash` set.
  - Loads local `documentRepo.observeLibrary().first()` (or a snapshot dao method).
  - For each local doc whose `contentHash` is missing from the server set, opens the EPUB OPF, extracts metadata via `OpfMetadataExtractor`, calls `uploadLibraryItem(...)`.
  - Reconcile uses no OPDS `opdsHref` — that's an immediate-path field; reconcile sends `null`.
  - Errors logged and swallowed; the next `runOnce()` will retry.

- [ ] **6.4 Call reconcile in `runOnce()`.**
  - After the existing pull block, before `return SyncResult.Success(Unit)`, call `runCatching { reconcileLibraryItems() }.onFailure { /* log */ }`.

- [ ] **6.5 Inject `openOpfBytes` / extractor into `SyncOrchestrator`.**
  - The current orchestrator has no metadata extraction dependency. Add a `epubOpfReader: (Document) -> ByteArray?` constructor param (default delegating to a `:core:metadata`-side helper).
  - Promote `AppContainer.readOpfBytes` to a public `EpubOpfReader` object in `:core:metadata` so both `BookDetailViewModel` and `SyncOrchestrator` use it.

- [ ] **6.6 Tests in `SyncOrchestratorTest`.**
  - `uploadLibraryItem invokes putLibraryItem with mapped payload`.
  - `uploadLibraryItem treats 409 as terminal (does not retry)`.
  - `reconcileLibraryItems pulls then puts missing locals`.
  - `reconcileLibraryItems pages until short page`.
  - `runOnce calls reconcile after progress pull` (verify call sequence with a mock client).

**Verification:**
```bash
scripts/dgradle :data:sync:testDebugUnitTest
```

## Stage 7 — Android: app-level wiring

- [ ] **7.1 Promote `readOpfBytes` to `:core:metadata`.**
  - New file `core/metadata/src/main/java/io/theficos/ereader/core/metadata/EpubOpfReader.kt` with an `object EpubOpfReader { suspend fun read(localPath: String): ByteArray? }`.
  - Move the implementation from `AppContainer.readOpfBytes`.
  - Update `AppContainer` to call `EpubOpfReader.read(doc.localPath)`.
  - Update `BookDetailViewModel` wiring via `AppContainer` (already uses `openOpfBytes`; just route through the new object).

- [ ] **7.2 Wire orchestrator with `EpubOpfReader`.**
  - In `AppContainer.kt`, pass an `epubOpfReader = { doc -> EpubOpfReader.read(doc.localPath) }` to `SyncOrchestrator(...)`.

- [ ] **7.3 Update `CatalogViewModel.download()` success path.**
  - After `docs.insert(...)` returns the local id, before `syncEnqueuer(context)`:
    - Read OPF bytes from `file.absolutePath` via `EpubOpfReader.read(...)`.
    - Extract `MetadataBundle` via `OpfMetadataExtractor.extract(bytes, fallbackTitle = pub.title)`.
    - If `bundle.seriesName != null || bundle.seriesPosition != null`, update the local `documents` row with the new series fields (add `DocumentDao.updateSeries(id, seriesName, seriesIndex)` query; small SQL update).
    - Call `syncOrchestrator.uploadLibraryItem(documentId = localId, bundle, opdsHref = pub.epubDownloadHref)` inside a `runCatching` — log failures.
  - This requires plumbing `syncOrchestrator` into `CatalogViewModel`. Constructor injection per existing pattern.

- [ ] **7.4 Wire delete propagation.**
  - In `DocumentRepository.delete(...)`, before the `dao.deleteById(...)` call, attempt the server DELETE via an injected callback. Simplest shape: add an optional `onDelete: suspend (DocumentIdentity) -> Unit = {}` constructor param to the repository, wired in `AppContainer` to call `syncOrchestrator.deleteLibraryItem(identity)`. This keeps `:data:local` from gaining a `:data:sync` dependency.
  - Same hook for `deleteAll` — iterate local docs, fire deletes, then bulk-clear local rows.
  - Best-effort — server failures log but don't block local delete.

- [ ] **7.5 Update `AppContainer.kt` and `CatalogViewModelFactory` / call sites.** Pass orchestrator/repository wiring through. Existing tests for `CatalogViewModel` may need `SyncOrchestrator` mocks — handle minimally (no-op stub works).

**Verification:**
```bash
scripts/dgradle :app:testDebugUnitTest :data:local:testDebugUnitTest :data:sync:testDebugUnitTest :core:metadata:testDebugUnitTest
```

## Stage 8 — Docs

- [ ] **8.1 Update `docs/sync-api.md`.**
  - Add `/library/v1/items` PUT, GET, DELETE rows to the endpoints table.
  - New section describing the body shape, `since=` cursor semantics, pagination contract, and reactivation flow.
  - Mention the `OPDS_SYNC_PROGRESS_ENABLED` gate.

- [ ] **8.2 No README changes** unless docs review (batch closer) finds anything.

## Stage 9 — Verify, commit, push, PR

- [ ] **9.1 Full server test run, three modes.**
- [ ] **9.2 Full Android test run.**
  ```bash
  scripts/dgradle test
  ```
- [ ] **9.3 Cache-key audit still passes** (sanity — not extended, but should stay green).
- [ ] **9.4 Commit.**
  - Message: `:sparkles: feat: library_items table + Android upload`.
  - No Claude attribution (no `Co-Authored-By: Claude…`, no generated-with footer).
  - Pre-commit hooks will run.
- [ ] **9.5 Push.**
  ```bash
  git push -u origin feat/library-items
  ```
- [ ] **9.6 Open PR.**
  ```bash
  gh pr create --base main --head feat/library-items \
    --title "feat: library_items table + Android upload" \
    --body "..."
  ```
  Body: summary, schema diff, test plan, GPT verdict, `docs/sync-api.md` additions. No Claude attribution.

## Risks and notes

- **PR-A dependency.** This PR relies on `scripts/migrate.py` and `PROGRESS_ENABLED`. Both shipped in PR-A which is already in `main` (the worktree is based on `769e9e4`). No coordination needed beyond rebasing if PR-A's followups land.
- **Concurrent batch-2 PRs.** PR-B, PR5, docs-review are running. Each works in its own worktree. The only shared file PR1 touches that they also might is `docs/sync-api.md` — the merge order resolves it. None of them touch `db/models.py`, `main.py`'s mode-gating block (PR1 adds *inside* the existing `progress_enabled` block), or any Android module we modify.
- **Reconcile pass cost.** One paged GET per `runOnce()`. For ≤500-book libraries that's ≤3 pages of 200 each. Acceptable; revisit if metrics show otherwise.
- **F-Droid posture.** All new traffic targets the existing calibre-web / opds-sync host using existing Basic auth. No new destinations.

## Definition of done

- Migration applied cleanly on a fresh DB in `progress_enabled=true` modes; not applied in `progress_enabled=false` mode.
- All three server test modes green.
- All Android unit tests green.
- `feat/library-items` pushed to `origin`; PR open against `main`.
- Spec and plan committed.
- `docs/sync-api.md` updated.
- No Claude attribution anywhere.
