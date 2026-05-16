# PR1 — `library_items` table + Android upload

> Shipped in 4a79c7c on 2026-05-16 as PR #16.

**Date:** 2026-05-16
**Status:** Design locked in `.claude/local/quire-ai/2026-05-16-next-deliverables.md` §"PR1". This spec captures execution-level detail. No re-design is in scope; only the small implementation choices needed to write the plan and code.

## Problem

The server today knows progress (`documents` + `progress`) but has no library metadata. AI Phase 2, library stats (PR9), the series-continuity shelf (PR8), and any future cross-device library view all need a server-side mirror of what's on each user's device.

A `library_items` table fixes that. It is **user-scoped** (unlike `book_insights`, which is a shared cache) and lives on the new `progress` alembic branch introduced by PR-A. Android uploads one row per downloaded book after the download settles.

## Non-goals

- Eager backfill of every existing library on first run. The reconcile pass (see "Retry semantics") catches local rows missing on the server lazily — over a few sync cycles a long-running install will converge — but PR1 does not block the first sync on a full upload. PR9 stats tolerate partial coverage.
- Server-side OPF parsing. Android sends pre-extracted `MetadataBundle` fields; the server stores them verbatim.
- Hard deletes. PR1 ships soft-delete only.
- Sync of bookmarks, annotations, or reader state.
- Multi-replica coordination (the in-process lock the rest of the server uses is fine).
- Any AI work — `library_items` is progress-mode only.

## Schema

New table on the `progress` alembic branch (first migration on that branch):

```
user_id        text         NOT NULL
metadata_id    text         NULL
content_hash   text         NOT NULL
title          text         NOT NULL
authors        jsonb        NOT NULL DEFAULT '[]'
series_name    text         NULL
series_index   numeric      NULL
isbn           text         NULL
language       text         NULL
subjects       jsonb        NOT NULL DEFAULT '[]'
opds_href      text         NULL
created_at     timestamptz  NOT NULL DEFAULT now()
updated_at     timestamptz  NOT NULL DEFAULT now()
deleted_at     timestamptz  NULL

UNIQUE (user_id, content_hash)                                  → uq_library_items_user_content
UNIQUE (user_id, metadata_id) WHERE metadata_id IS NOT NULL     → uq_library_items_user_metadata (partial)
INDEX  (user_id, series_name) WHERE deleted_at IS NULL          → ix_library_items_user_series_alive (partial)
INDEX  (user_id, updated_at)                                    → ix_library_items_user_updated (for since= queries)

PRIMARY KEY: surrogate bigint `pk` (autoincrement). Matches the `documents` table convention.
```

Identity contract per `2026-05-16-next-deliverables.md` §"Identity hierarchy":
- `content_hash` is mandatory and is the cache-key floor.
- `metadata_id` is opportunistic; client backfills it the moment OPF parsing yields one.
- Neither is part of the primary key. The two uniqueness constraints above prevent duplicates.

`authors` and `subjects` are `jsonb` arrays (not `text[]`) so a future PR can add per-author metadata (role, sort key) without a column-type migration. Default empty array, not NULL, so client code never branches on `null` vs `[]`.

`series_index` is `numeric` (not `int`) because EPUB 3 `group-position` allows fractional positions (`1.5` for novellas). Android-side this carries through as Kotlin `Double?` end-to-end:
- Wire DTO: `series_index: Double?`.
- Room column: `seriesIndex` `REAL` (nullable).
- `MetadataBundle.seriesPosition` is widened from `Int?` to `Double?` (small breaking change inside `:core:metadata`; the only existing reader is `BookDetailViewModel` which doesn't surface it numerically).
- `OpfMetadataExtractor.parseSeries` is updated to return `Double?` (was `Int?`); the `toFloatOrNull()?.toInt()` truncation is removed and `toDoubleOrNull()` replaces it. Existing tests get adjusted.
- Server stores `numeric`; SQLAlchemy maps to `Decimal` on read, but JSON serialisation coerces to a number. Round-trip precision on the few-decimal-place values that EPUB practice uses is fine.

`opds_href` is a free-form text field carrying the catalog acquisition URL that produced this download. Optional. Useful for PR7 catalog-detail screen reconciliation.

## API

Mounted under `/library/v1/*`. Gated behind `OPDS_SYNC_PROGRESS_ENABLED` — sync-only and full-stack deploys mount it; AI-only deploys do not.

Identity travels in the JSON body, never the path. URL-encoded sha256s are a footgun (`+`/`/`/`=`).

### `PUT /library/v1/items` — upsert

```jsonc
{
  "item": {
    "metadata_id": "abc-123",      // optional
    "content_hash": "sha256...",   // required
    "title": "Foundation",         // required
    "authors": ["Isaac Asimov"],   // required (may be empty list)
    "series_name": "Foundation",   // optional
    "series_index": 1,             // optional, numeric
    "isbn": "9780553293357",       // optional
    "language": "en",              // optional, ISO 639-1
    "subjects": ["Science Fiction"],
    "opds_href": "https://..."     // optional
  }
}
```

- Idempotent. Server resolves on `(user_id, content_hash)`. If a row exists with `deleted_at` set, clears it (reactivates) AND refreshes `updated_at = now()`.
- **Every state-changing path advances `updated_at = now()`**: create, payload update (even if values are byte-equal to existing — keeps the contract simple), reactivation (`deleted_at` cleared), and soft-delete. This is load-bearing for `GET ?since=` tombstone delivery; see GET semantics.
- If a different existing row collides on `(user_id, metadata_id)` (rare: client learned a stronger metadata_id for a row keyed under the old content_hash), this PR keeps it simple: return `409 Conflict` with body `{"error": "metadata_id_conflict", "existing_content_hash": "..."}`. PR2 identity-aliases is the proper fix; PR1 just refuses to silently merge.
- Returns `200 OK` with a `LibraryItemResponse` (the persisted row, including `created_at`, `updated_at`, `deleted_at`).
- `422` on missing required fields.
- `401` if Basic auth fails.

### `GET /library/v1/items?since=<ISO8601>&limit=<int>&offset=<int>` — list

- `since` is optional. When present, returns rows whose `updated_at > since` (server-side comparison after normalising to UTC). When given, includes soft-deleted rows so clients can mirror tombstones. (Both create/update and soft-delete bump `updated_at`, so a tombstone is just another row whose `updated_at` advanced past the client's last cursor.)
- `since` absent → returns alive rows only (`deleted_at IS NULL`). This is the reconcile-pass shape; see "Retry semantics".
- **Ordering and high-water bound:** rows are returned `ORDER BY updated_at ASC, pk ASC` (the `pk` tiebreaker prevents same-timestamp collisions from skipping rows). The server captures `server_time = now()` BEFORE executing the query and additionally filters `updated_at <= server_time` so concurrent writes don't leak into the current page.
- `limit` defaults to 200, hard-capped at 1000.
- `offset` defaults to 0. Clients page until they get a short page (`len(items) < limit`), then persist `server_time` as their next `since` cursor. Mid-page cursor persistence is unsafe — a write during pagination can shift offsets — so clients MUST drain to a short page before advancing the cursor.
- Returns `{"items": [...], "server_time": "ISO8601"}`.
- User-scoped: only items where `user_id == authenticated user`.

### `DELETE /library/v1/items` — soft delete

Body identifies which item:

```jsonc
{"item": {"content_hash": "sha256..."}}
```

- First DELETE on an alive row: sets `deleted_at = now()` AND `updated_at = now()`. The `updated_at` bump is what lets the next `GET ?since=<old_cursor>` deliver the tombstone.
- Idempotent — `DELETE` on an already-deleted row returns `200`; **both `deleted_at` and `updated_at` are preserved** (not refreshed) so the tombstone doesn't spuriously re-appear in every subsequent `since=` window.
- Returns `200 OK` with the persisted `LibraryItemResponse` (including `deleted_at`).
- `404` if no such row for this user.
- A subsequent `PUT` for the same `content_hash` clears `deleted_at`, refreshes `updated_at`, and updates the payload (reactivation).

### Mode-gating

- `OPDS_SYNC_PROGRESS_ENABLED=false` → router not mounted at all; the path 404s.
- `OPDS_SYNC_PROGRESS_ENABLED=true` → router mounted; migration `progress_001_library_items` applied by the existing `scripts/migrate.py` wrapper.

## Server module layout

```
server/migrations/versions/progress_001_library_items.py    # alembic, splice, branch_labels=("progress",)
server/opds_sync/db/models.py                                # add `LibraryItem` declarative model
server/opds_sync/api/library.py                              # new router, body-keyed identity
server/opds_sync/api/library_schemas.py                      # Pydantic LibraryItem{Request,Response}
server/opds_sync/main.py                                     # mount router under PROGRESS_ENABLED gate
server/tests/integration/test_library_items.py               # endpoint round-trip suite
server/tests/integration/test_schema.py                      # extend with library_items table introspection
```

The router uses the existing `current_user_id` dependency for Basic auth — the same one progress uses.

## Android changes

Three modules touch the wire:

### 1. `:data:local` — Room migration v4 → v5

Add `seriesName` (TEXT, NULL) and `seriesIndex` (REAL, NULL) columns to `documents`. Bump database version to 5 and add `MIGRATION_4_5`. This lets PR8 (series shelf) query Room without another migration.

The local schema does NOT mirror the full `library_items` shape — Android already has `documents` carrying the on-device truth. Series fields are the only additions because PR8 needs them.

Implementation checklist:
- `DocumentEntity`: add `seriesName: String?` and `seriesIndex: Double?` fields.
- `EReaderDatabase`: bump `version = 5`, append `MIGRATION_4_5` to the migrations list.
- `MIGRATION_4_5`: two `ALTER TABLE documents ADD COLUMN ...` statements; both nullable, no default.
- Exported Room schema: regenerate `data/local/schemas/io.theficos.ereader.data.local.db.EReaderDatabase/5.json` by running the tests (or `:data:local:assembleDebug`); commit the generated file.
- `Document` domain model (in `:core:model`): add `seriesName: String?` and `seriesIndex: Double?` fields with default `null`.
- `DocumentRepository.toDomain()` + `.insert()` propagate the new fields.
- `DocumentDao`: no new methods needed; the existing `findById`/`observeAll` queries return the full entity.

### 2. `:data:sync` — LibraryItem DTOs + client method

Two distinct DTOs so request and response shapes don't drift:

- `LibraryItemRequestDto` — what the client PUTs. Fields: `metadata_id`, `content_hash`, `title`, `authors` (List<String>), `series_name`, `series_index` (Double?), `isbn`, `language`, `subjects` (List<String>), `opds_href`. Wrapped in `LibraryItemPutBody { item: LibraryItemRequestDto }`.
- `LibraryItemResponseDto` — what the server returns. Superset: same payload fields PLUS `created_at`, `updated_at`, `deleted_at` (all ISO-8601 strings; `deleted_at` nullable).
- `LibraryItemsListResponse { items: List<LibraryItemResponseDto>, server_time: String }`.
- `LibraryItemIdentityDto { content_hash: String }` and `LibraryItemDeleteBody { item: LibraryItemIdentityDto }`.
- `SyncApi.PATH_LIBRARY_ITEMS = "/library/v1/items"`.
- `SyncClient.putLibraryItem(body)`, `SyncClient.deleteLibraryItem(body)`, `SyncClient.pullLibraryItems(sinceIso?, limit, offset)`.
- DELETE must send a body — use OkHttp's `Request.Builder.method("DELETE", jsonBody)` because `.delete()` overloads vary in body acceptance.
- `SyncOrchestrator` gets two new helpers (see "Retry semantics" below):
  - `uploadLibraryItem(documentId, bundle, opdsHref)` — single PUT, best-effort.
  - `reconcileLibraryItems()` — full alive-list scan (no `since=`), uploads local rows the server doesn't have.

### 3. App-level wiring

**Download path** — `CatalogViewModel.download()` is where downloads complete today. After `docs.insert(...)` returns the local id and before `syncEnqueuer(context)` fires:

1. Extract OPF bytes from the downloaded EPUB (reuse `AppContainer.readOpfBytes(...)` — promote it to a public helper or move it to `:core:metadata` as `EpubOpfReader`).
2. Run `OpfMetadataExtractor.extract(bytes, fallbackTitle = pub.title)`.
3. Backfill the local `documents` row with `seriesName` / `seriesIndex` from the bundle.
4. Build a `LibraryItemRequestDto` from `(identity, bundle, pub.epubDownloadHref)` (see "Author mapping" below).
5. Fire `syncOrchestrator.uploadLibraryItem(...)` — best-effort; failure logs but does not break the download flow.

**Delete path** — local deletes must propagate so server rows don't strand:

- `LibraryViewModel.delete(document)` and any other call site that ends up at `DocumentRepository.delete(...)` or `.deleteAll(...)` must, **before** the local row is removed, fire `syncOrchestrator.deleteLibraryItem(document.identity)`. Best-effort: a network failure logs and continues with the local delete. The server row remains alive in that case; future PRs can clean up stranded rows, but the F-Droid posture is "user's local device is authoritative", so a stranded server row is annoying but not corrupting.
- `deleteAll` iterates one DELETE per document; this is fine for PR1 (a "wipe library" action is rare). A bulk-delete endpoint can come later.

**Author mapping** — server requires `authors: list[str]`; current `MetadataBundle` has singular `author: String?`. PR1 mapping:
- Prefer `bundle.author` (from OPF), else `pub.author` (from OPDS), else empty list.
- Single-element list when present; we do NOT split on `;` / `&` / `and` in PR1 (calibre-web already splits, and OPDS feeds have wildly inconsistent author syntax — wrong-splitting is worse than under-splitting).
- DTO test covers all three branches.

### Retry semantics

For PR1, library-item PUTs are **fire-and-forget on download** with a **reconcile-on-sync** safety net:

- **Immediate path:** `CatalogViewModel.download()` triggers an in-process upload right after `docs.insert(...)`. Failure logs a warning.
- **Reconcile path:** `SyncOrchestrator.runOnce()` (already called periodically by `SyncWorker`) calls `reconcileLibraryItems()` after the progress pull. This pulls the server's alive set (`GET /library/v1/items` with NO `since=`, paging by `limit/offset` until short page) and PUTs any local `documents` rows whose `content_hash` is missing from the server set.
  - Why no `since=`: a PUT that never reached the server will never appear in a delta-only view. The full alive-list scan is the only way to prove membership without per-document upload state, and per-document upload state was rejected (no new persistent queue).
  - Cost: one paged GET per `runOnce()`. For typical libraries (≤500 books) that's one or two pages. Acceptable.
  - This makes the reconcile pass functionally a lazy backfill — a fresh install of the app on an existing local library will, over a few sync cycles, upload everything. The non-goals section calls this out explicitly.
- **409 handling:** `metadata_id_conflict` is treated as terminal — log and move on. Do NOT retry indefinitely; PR2 ships the proper fix. Orchestrator and `SyncClient` tests cover this code path.

The reconcile pass is the next `runOnce()` after the failure, not a new background queue.

### Identity binding

The `documents` table's `(metadata_id, content_hash)` is the local identity. `LibraryItemDto.content_hash` always uses `documents.contentHash` (computed by `core.identity.extractIdentity`). `LibraryItemDto.metadata_id` mirrors `documents.metadataId` when non-null. This is the only mapping the orchestrator does — it does not derive identity from the EPUB at upload time, because the source of truth lives in `documents` already.

## Tests

### Server (pytest, all three modes)

In `test_library_items.py` (new):
- `test_put_creates_then_returns_row` — PUT new item, expect 200; GET lists it.
- `test_put_idempotent_updates_payload` — PUT twice, second time with a different title, expect updated title and same `created_at` but newer `updated_at`.
- `test_delete_soft_deletes_then_put_reactivates` — PUT → DELETE → GET (without since) excludes it → PUT clears deleted_at → GET includes it.
- `test_get_since_includes_tombstones` — soft-deleted row appears when `since` precedes the deletion.
- `test_user_isolation` — alice's row invisible to bob.
- `test_large_arrays_round_trip` — 50-element authors and 50-element subjects survive byte-for-byte.
- `test_put_missing_required_fields_returns_422` — missing `content_hash`, missing `title`, missing `authors`.
- `test_metadata_id_conflict_returns_409` — pre-existing row A with metadata_id "X", second PUT with different content_hash but same metadata_id "X" gets 409.
- `test_pagination_limit_and_offset` — 5 items, page through with `limit=2`.
- `test_get_ordering_stable_with_pk_tiebreaker` — three rows with identical `updated_at` (same-second writes) page deterministically across two `limit=2` pages without dropping or duplicating any row.
- `test_get_server_time_bounds_concurrent_writes` — capture `server_time` before a query, write a new row with a later `updated_at`, confirm it does NOT appear in the response page bounded by that `server_time`.
- `test_delete_bumps_updated_at_for_tombstone_delivery` — PUT at t0, capture `t1=now`, DELETE at t2 > t1, GET with `since=t1` returns the tombstone.
- `test_delete_idempotent_does_not_refresh_updated_at` — DELETE twice; the second response's `updated_at` equals the first's.

In `test_schema.py` (extend):
- `test_library_items_table_exists` (marked `requires_progress`) — introspect columns, partial unique index, partial series-name index.

In `test_modes.py` (extend): assert `/library/v1/items` returns 404 when `PROGRESS_ENABLED=false`. The existing mode-matrix already covers gating; add the path to its enumeration.

The existing cache-key audit test (`test_cache_key_audit.py`) does NOT need to be extended — `library_items` is user-scoped and explicitly outside that audit's scope.

### Android (`scripts/dgradle :data:local:testDebugUnitTest` and `:data:sync:testDebugUnitTest`)

- `MigrationTest`: extend with `migrate 4 to 5 adds series columns` — pre-populate v4 data, run `MIGRATION_4_5`, assert the new columns exist with nullable storage and old rows have NULL series fields.
- `DocumentRepositoryTest` (or a new `DocumentSeriesTest`): inserting a document with `seriesName` and `seriesIndex` round-trips them.
- `OpfMetadataExtractorTest`: extend existing tests; assert `parseSeries` returns `Double?` and `1.5` survives round-trip.
- `SyncClientTest`:
  - `putLibraryItem` returns 200 → `Success` with parsed `LibraryItemResponseDto` (including `created_at`).
  - `putLibraryItem` returns 401 → `Unauthorized`.
  - `putLibraryItem` returns 422 → `HttpFailure(422)`.
  - `putLibraryItem` returns 409 → `HttpFailure(409)` (orchestrator interprets this as terminal).
  - `deleteLibraryItem` with body PUTs the right JSON (asserts the request body, since DELETE-with-body is unusual).
  - `pullLibraryItems` round-trips `server_time` and `items`.
- `SyncOrchestratorTest`:
  - `uploadLibraryItem` invokes `SyncClient.putLibraryItem` once with the expected payload.
  - `reconcileLibraryItems` pulls the alive set, diffs against local `documents`, and PUTs exactly the missing rows.
  - `reconcileLibraryItems` handles 409 by logging and NOT retrying.
  - `runOnce()` calls reconcile after progress pull (sequence test).

## Rollout

PR-A is in flight. PR1 waits for it to land before merging because it depends on the `scripts/migrate.py` wrapper and the `PROGRESS_ENABLED` flag. The migration file itself can be authored against PR-A's branching convention while PR-A is open.

PR1 ships:
- `progress_001_library_items` migration.
- `LibraryItem` ORM model.
- `/library/v1/items` PUT/GET/DELETE endpoints, gated.
- Android Room v4→v5 migration.
- Android sync DTOs + client + orchestrator hook.
- Catalog-download path uploads after a successful download.
- Tests above.
- `docs/sync-api.md` update.

## Resolved execution choices

- **`numeric` vs `real` for `series_index`** — postgres `numeric`, Room `REAL`, wire `Double?`. Sqlite `REAL` is float-64; postgres `numeric` is exact. EPUB practice uses small fractions (`1.5`); precision loss on a few-decimal-place value is undetectable in practice. The architect call-out about `MetadataBundle.seriesPosition: Int?` truncating is handled by widening that field to `Double?` and updating the extractor (see the Android section).
- **`409` vs silent merge on metadata_id conflict** — `409`. PR2 fixes this properly with aliases; until then, surfacing the conflict to the client is safer than silently picking a winner. Orchestrator treats 409 as terminal (log, don't retry).
- **`limit` default and cap** — 200 / 1000. Matches typical library sizes and keeps a single page under ~200 KB.
- **`since=` parsing** — accept ISO-8601 with `Z` or `+HH:MM`. Use the same `since.replace(" ", "+")` trick `progress.py` uses for httpx-encoded `+`.
- **GET ordering and bounding** — `ORDER BY updated_at ASC, pk ASC`; server captures `server_time = now()` before the query and bounds with `updated_at <= server_time`. Clients persist `server_time` only after draining to a short page.
- **`updated_at` invariant** — every state change advances `updated_at = now()`, INCLUDING soft-delete and reactivation. Idempotent DELETE on an already-deleted row does NOT refresh either timestamp.
- **`docs.insert` ordering** — do the in-process PUT after `docs.insert(...)` returns the local id, but BEFORE `syncEnqueuer(context)` fires. This keeps the upload synchronous-looking to the user and lets the orchestrator's reconcile pass catch the failure case.
- **Identity-in-body shape** — PUT and DELETE use `{"item": {...}}` (nested under "item") so a future bulk endpoint can ship as `{"items": [...]}` without breaking clients. Single-item-per-request only in PR1.
- **Reconcile shape** — full alive-list scan (no `since=`), because a never-uploaded row is missing from any delta view by definition. The non-goals section calls out that this functionally serves as lazy backfill.
- **Author mapping** — single-element list from `bundle.author ?: pub.author`, no splitting. PR1 does not interpret multi-author OPF/OPDS metadata.
- **DELETE with body on Android** — use `Request.Builder.method("DELETE", jsonBody)`; the `.delete()` convenience overload is intentionally avoided.

## What downstream PRs need from this one

- **PR8 (series shelf)** consumes Room columns `seriesName`/`seriesIndex` on `documents`. PR1 ships the migration.
- **PR9 (library stats)** consumes server-side `library_items` for `total_books` / `top_authors`. PR1 ships the table and the upload hook.
- **Phase 2 (library intelligence)** consumes the per-user library mirror without further schema work.

## What PR1 does NOT solve

- Existing libraries on devices that update don't auto-backfill. A separate follow-up PR (not in this batch) could walk `documents` once and upload everything; for now, the reconcile pass handles it lazily on the next sync.
- Catalog-side preview metadata (PR7) — `library_items` covers downloaded books; catalog tiles live elsewhere.
- The `opds_href` field exists but PR1 does not yet teach the server to do anything clever with it. It's stored for PR2/PR7 to consume.
