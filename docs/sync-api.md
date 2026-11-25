# Sync API

REST surface of the `quire-server` server. Endpoints live under
prefixes — `/sync/v1` (progress, highlights + planned bookmarks), `/library/v1`
(per-user library mirror, including the Phase 0 push-model
`POST /library/v1/sync` bulk endpoint), `/ai/v1` (optional book insights
and reader profile), `/agent/v1` (read-only highlights), `/catalogs/v1`
(book catalogs saved to a Quire account), and the unprefixed
`/health` / `/readyz` operational probes. All request and response bodies are JSON. Authenticated
endpoints accept the same credential as calibre-web by default; see
[Authentication](#authentication) for the Phase 0 pluggable backend.

For the rationale behind the conflict-resolution model, see
[`architecture.md`](architecture.md).

## Authentication

```
Authorization: Basic <base64(username:password)>
```

The same calibre-web Basic credentials the Android app uses for OPDS
browsing. The server validates each header by probing
`{QUIRE_SERVER_CWA_BASE_URL}{QUIRE_SERVER_CWA_PROBE_PATH}` (default `/opds`)
with the incoming `Authorization` header and treats `200` as
authenticated, `401` as not. Results are TTL-cached (60 s positive,
10 s negative).

The `user_id` recorded on every persisted row is the lowercased
calibre-web username extracted from the Basic header. The system is
multi-user from day one.

A failed lookup returns `401`. If calibre-web is unreachable the server
returns `503`.

### AI auth mode (PR-B, 2026-05-16)

`/sync/v1/*` and `/library/v1/*` always use the Basic auth flow above.
`/ai/v1/*` routes go through an `AiAuthenticator` seam that selects the
verifier per the `QUIRE_SERVER_AI_AUTH_MODE` env var:

| Mode (`QUIRE_SERVER_AI_AUTH_MODE`) | Header                            | Principal                              |
| ------------------------------- | --------------------------------- | -------------------------------------- |
| `basic` (default)               | `Authorization: Basic …`          | `tenant_id="local"`, `auth_mode=basic` |
| `token`                         | `Authorization: Bearer <jwt-ish>` | claims from token; `auth_mode=token`   |

Token mode is a stub for the hosted-AI future: HMAC-SHA256 over `header.payload`
with header `{alg=HS256, kid}` and payload claims `{iss, aud, exp, iat, sub,
tenant_id, scope?}`. Each segment is URL-safe base64 with no padding. The
server only verifies — issuance is out of scope. Multiple `kid → secret`
entries (set via the JSON env var `QUIRE_SERVER_AI_TOKEN_SECRETS`) enable
rotation: tokens signed under any registered kid are accepted; mint with
the newest. `QUIRE_SERVER_AI_TOKEN_ISSUER` and `QUIRE_SERVER_AI_TOKEN_AUDIENCE`
must match `iss` / `aud` exactly. Verification failures all collapse to a
single `401 invalid credentials` — failure reasons live in structured logs
only.

Token-mode misconfiguration (missing secrets, secret shorter than 32 bytes,
missing issuer/audience) crashloops the process on startup. There is no
silent downgrade to basic.

`AiPrincipal.tenant_id` flows ONLY into `ai_generation_log` per-call audit;
it never participates in any shared-cache key. See `docs/architecture.md`
§"AI auth seam" for the rationale and `server/quire_server/api/ai_auth.py` for
the implementation.

> The `token` mode above is **deprecated** since the 2026.05.22 release
> and will be removed two minor releases later. New deployments that need
> session-token primary authentication should use the
> `AuthBackend` interface via `QUIRE_SERVER_AUTH_BACKEND=native`
> (`NativeAuth`); see `docs/architecture.md` §"AuthBackend abstraction"
> and the [deprecations table](#compatibility-and-deprecations) below.

## Push model

Phase 0 (2026-05-22) pins the data-flow shape that Quire Cloud will reuse:
**the Android client is the only thing that touches local EPUB files and
the user's calibre-web instance.** The server consumes whatever the client
chooses to push; the new `POST /library/v1/sync` endpoint exists to make
that a first-class flow. Existing per-item `PUT /library/v1/items` and
`POST /sync/v1/progress` paths remain shipped and unchanged.

Outbound-call posture, by surface:

- **`/sync/v1/*`, `/library/v1/*`** — no outbound calls into the user's
  environment. The one acknowledged exception is the optional
  `CalibreWebBasicAuth` probe to `{CWA_BASE_URL}/opds` for credential
  validation (default OSS auth backend).
- **`/ai/v1/*`** — outbound calls to the configured AI provider and the
  retrieval sources (`wikipedia`, `openlibrary`) continue to fire, gated
  by per-user opt-in. Unchanged from earlier releases.

### Wire-vs-attribute naming (X-1)

The new `POST /library/v1/sync` endpoint uses **`identity_hash`** on the
JSON wire. The persisted DB column on `library_items` stays
**`content_hash`** for historical compatibility — no rename, no
migration. The translation happens at the handler boundary
(`server/quire_server/api/library.py`). Do not rename either side: the
wire name is a stable Cloud-bound contract; the column name is a stable
on-disk contract. Pre-Phase-0 endpoints (`PUT/GET/DELETE
/library/v1/items`) keep `content_hash` on their wire too — only the new
push-model endpoint canonicalizes to `identity_hash`.

The hashing algorithm itself is unchanged in Phase 0 — KOReader-style
sampled MD5 over the EPUB body (see
[`architecture.md`](architecture.md#sampled-hash)). The new
`identity_hash_version` field on every record (default `1` for all
existing rows) is the version handle that lets a future hash algorithm
change cleanly; the server applies `max(existing, incoming)` on write so
older clients cannot downgrade a row.

## Compatibility and deprecations

Operator-facing config flags scheduled for removal. Each row's removal
window starts at the listed release.

| Setting (env var)                                | Default   | Deprecated since   | Removal target              | Recommended replacement                                                                  | Notes                                                                                                          |
| ------------------------------------------------ | --------- | ------------------ | --------------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `QUIRE_SERVER_AI_METADATA_SERVER_LOOKUP_ENABLED` | `false`   | 2026.05.22 release | +2 minor releases           | Client always sends `bundle` in `POST /ai/v1/insights/{lookup,regenerate}` body (push model). | When enabled, server reconstructs the bundle from the caller's `library_items` row. Emits `DeprecationWarning` + `logging.warning` at boot. |
| `QUIRE_SERVER_AI_AUTH_MODE=token`                | `basic`   | 2026.05.22 release | +2 minor releases           | `QUIRE_SERVER_AUTH_BACKEND=native` (`NativeAuth`).                                       | Token-mode code path remains functional during the window; existing HS256 tokens continue to validate until normal expiry. Emits `DeprecationWarning` + `logging.warning` at startup. |

A removed flag is a hard error — the server will refuse to start until
the operator migrates. Plan cutover within the two-minor-release window.

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/health` | none | Liveness probe; returns `{ready, version, modes}` (always mounted) |
| `GET` | `/readyz` | none | Readiness probe; checks Postgres and that enabled-branch migrations are applied (always mounted) |
| `GET` | `/quire-admin/v1/status` | admin | Version, modes, boot warnings, AI health, migration heads and every setting with secrets masked; mounted only when `QUIRE_SERVER_ADMIN_USERS` is set |
| `POST` | `/quire-admin/v1/ai/probe` | admin | Sends one short structured request to the AI provider; returns `{ok, model, elapsed_ms, error}` with `error` shaped like the AI failure `detail` |
| `PUT` | `/quire-admin/v1/settings` | admin | Changes AI settings, keyed by variable name, `null` for the default; all or nothing, `422` with `detail.errors` per variable; answers `{editable}` as in the status |
| `GET` | `/quire-admin` | admin | The status as an HTML page with a "Test AI connection" button (`POST /quire-admin/probe`) and an AI settings form (`POST /quire-admin/settings`) |
| `POST` | `/sync/v1/progress` | yes | Push progress for one or more documents |
| `GET` | `/sync/v1/progress` | yes | Pull progress deltas |
| `POST` | `/mcp` | yes | Remote read-only highlight MCP tools (Streamable HTTP) |
| `GET` | `/agent/v1/highlights` | yes | List/search active highlight previews |
| `GET` | `/agent/v1/highlights/{highlight_id}` | yes | Read one full active highlight |
| `POST` | `/sync/v1/highlights` | yes | Push saved passages and tombstones |
| `GET` | `/sync/v1/highlights` | yes | Pull per-user highlights by revision cursor |
| `POST` | `/sync/v1/documents/alias` | yes | Reconcile a hash-keyed record with a newly-known metadata-id (planned) |
| `POST` | `/sync/v1/bookmarks` | yes | Push bookmark create/delete (planned) |
| `GET` | `/sync/v1/bookmarks` | yes | Pull bookmark deltas (planned) |
| `POST` | `/library/v1/sync` | yes | Push-model bulk upsert + tombstone batch (Phase 0; wire field `identity_hash`) |
| `PUT` | `/library/v1/items` | yes | Upsert one library item (mode-gated on `QUIRE_SERVER_PROGRESS_ENABLED`) |
| `GET` | `/library/v1/items` | yes | List items, optional `since=<ISO>` cursor with tombstones |
| `DELETE` | `/library/v1/items` | yes | Soft-delete one library item by `content_hash` |
| `GET` | `/library/v1/stats` | yes | Per-user library roll-up: totals + top authors + top themes |
| `GET` | `/catalogs/v1/catalogs` | Quire session | Saved catalogs without addresses or logins, with a `revision` and `ETag` |
| `GET` | `/catalogs/v1/catalogs/{id}/secret` | Quire Bearer token | One catalog's address and login |
| `PUT` | `/catalogs/v1/catalogs/{id}` | Quire session | Create or replace one catalog |
| `DELETE` | `/catalogs/v1/catalogs/{id}` | Quire session | Remove one catalog |
| `PUT` | `/catalogs/v1/order` | Quire session | Reorder the whole list |
| `GET` | `/catalogs/v1/catalogs/{id}/fetch` | Quire session | A page, cover or book from a saved catalog, fetched by the server with its login (Quire Web) |
| `GET` | `/convert/v1/capabilities` | yes | Whether PDF conversion is available here, and its limits |
| `POST` | `/convert/v1/jobs` | yes | Start a conversion: idempotency key, SHA-256 and size of the PDF |
| `PUT` | `/convert/v1/jobs/{id}/source` | yes | Upload the PDF as the raw request body |
| `GET` | `/convert/v1/jobs` | yes | The caller's conversions, newest first |
| `GET` | `/convert/v1/jobs/{id}` | yes | One conversion's state, result, review pages or failure |
| `GET` | `/convert/v1/jobs/{id}/epub` | yes | Download the converted EPUB |
| `POST` | `/convert/v1/jobs/{id}/cancel` | yes | Stop a conversion that has not finished |
| `POST` | `/convert/v1/jobs/{id}/acknowledge` | yes | Done with a finished conversion: the server deletes the PDF and the EPUB |
| `GET` | `/ai/v1/health` | none | AI provider + retrieval reachability snapshot (mode-gated on `QUIRE_SERVER_AI_ENABLED`) |
| `GET` | `/ai/v1/config` | yes | User-visible AI configuration |
| `GET`/`PUT` | `/ai/v1/preferences` | yes | Per-user opt-in + tone/language |
| `POST` | `/ai/v1/insights/lookup` | yes | Book insight: cache hit or synchronous generation |
| `POST` | `/ai/v1/insights/get` | yes | Cache-only read; 404 on miss |
| `POST` | `/ai/v1/insights/regenerate` | yes | Force a fresh insight (audit-only retention of previous row) |
| `POST` | `/ai/v1/insights/invalidate` | yes | Drop the cached insight for the caller's current cache key |
| `POST` | `/ai/v1/insights/promote` | yes | Copy a catalog-side insight onto the downloaded canonical identity |
| `GET` | `/ai/v1/insights/sync` | yes | Bulk pull of the caller's owned-book insights with tuple cursor |
| `GET` | `/ai/v1/profile` | yes | Cached reader profile (narrative + recommendations); 404 if never refreshed |
| `POST` | `/ai/v1/profile/refresh` | yes | Recompute the reader profile (budgeted) |
| `DELETE` | `/ai/v1/profile` | yes | Wipe the caller's reader profile row |
| `POST` | `/details/v1/lookup` | yes | Fill a book's missing details from Open Library; tidy them when due |
| `POST` | `/details/v1/tidy` | yes | Tidy a book's details now, replacing the stored record (budgeted) |
| `GET` | `/details/v1/covers/{key}` | yes | The designed cover of a tidied record, as PNG (JPEG on painted artwork) |

`/sync/v1/documents/alias` and `/sync/v1/bookmarks` are designed but not
shipped. Everything else above is on `main`.

## Read-only Agent and MCP access

`GET /agent/v1/highlights` lists or searches the authenticated account's active
highlights using bounded previews and UUID pagination. `GET
/agent/v1/highlights/{highlight_id}` retrieves one complete saved quotation and
locator. The remote `/mcp` Streamable HTTP endpoint exposes the same service
through `list_highlights`, `search_highlights` and `get_highlight` tools, and
adds read-only `get_highlights` (several by ID), `list_highlighted_books`,
`list_library` (the shelf with reading progress) and `get_book` (one book with
its tidied details). The server calls no model for any of them.

Both surfaces follow the progress/full-mode gate. MCP requires an explicit
existing Basic or NativeAuth Bearer Authorization header on every request;
cookie authentication and OAuth discovery are not provided by that endpoint.
Public deployments configure `QUIRE_SERVER_MCP_PUBLIC_URL` and proxy both routes.
See [Agent and MCP access](agent-access.md) for filters, previews, cursors, errors
and client setup. These reads do not advance sync cursors or expose tombstones.

## Document references

Every per-document request identifies its document by composite key:

```json
{
  "metadata_id": "9780141036144",
  "content_hash": "8e3a..."
}
```

At least one of `metadata_id` or `content_hash` must be present. The server
applies the same lookup precedence as the client:

1. If `metadata_id` is present → match by `metadata_id`.
2. Else → match by `content_hash`.
3. Else → create a new record (push paths only; pulls 404).

## Progress

### `POST /sync/v1/progress`

Push current progress for one or more documents. Conflict resolution is
record-level last-writer-wins on `client_updated_at`. Pushes for the same
user serialize in one transaction, including first-time document creation,
so concurrent devices cannot overwrite newer progress with an older state.

```http
POST /sync/v1/progress
Authorization: Basic ...
Content-Type: application/json

{
  "items": [
    {
      "document": { "metadata_id": "9780141036144", "content_hash": "8e3a..." },
      "locator": "epubcfi(/6/4!/4/2/2[ch01]/2/1:0)",
      "percent": 0.382,
      "client_updated_at": "2026-05-07T09:14:32Z"
    }
  ]
}
```

Response:

```json
{
  "results": [
    {
      "document": { "metadata_id": "9780141036144", "content_hash": "8e3a..." },
      "status": "accepted",
      "server_client_updated_at": "2026-05-07T09:14:32Z"
    }
  ]
}
```

`status` is `accepted` for a new or newer row, and `stale` when the server
already has an equal or newer timestamp. `server_client_updated_at` reflects
the stored progress timestamp.

### `GET /sync/v1/progress`

Pull the authenticated user's full progress snapshot. `since` remains a
required ISO 8601 parameter for existing clients, but does not filter rows.
An offline device may upload progress whose client timestamp predates a
previous pull, and an in-flight write may commit after that pull's SELECT.
Returning the snapshot ensures the next pull can see both kinds of update.

```http
GET /sync/v1/progress?since=2026-05-07T09:14:32Z
Authorization: Basic ...
```

Response:

```json
{
  "items": [
    {
      "document": { "metadata_id": "9780141036144", "content_hash": "8e3a..." },
      "locator": "epubcfi(/6/4!/4/2/2[ch01]/2/1:0)",
      "percent": 0.382,
      "client_updated_at": "2026-05-07T09:14:32Z",
      "finished_at": null,
      "abandoned_at": null
    }
  ],
  "server_time": "2026-05-07T09:15:00Z"
}
```

Existing clients can keep persisting `server_time` and sending it as `since`.
Clients merge rows using `client_updated_at`, retaining newer local progress.
This unpaginated response costs O(the user's progress rows) on every pull;
it favors reliable offline convergence at the current personal-library scale.
A future delta protocol needs a commit-safe cursor before restoring filtering.

## Highlights

These endpoints use the configured authentication backend and are available in
full and sync-only deployments (`QUIRE_SERVER_PROGRESS_ENABLED=true`). They are
not mounted in AI-only mode. Rows belong to the authenticated user; the client
cannot choose a user ID. Highlights have no dependency on a downloaded document.

### `POST /sync/v1/highlights`

Push up to 500 items in one transaction. Example:

```json
{
  "items": [
    {
      "id": "14300000-0000-4000-8000-000000000001",
      "document": {
        "metadata_id": "9780553293357",
        "content_hash": "abc123",
        "identity_hash_version": 1
      },
      "locator": "  { \"href\": \"chapter\\\"1.xhtml\", \"text\": {\"highlight\":\"Café \\u263a\"} }\n",
      "text": "“Café ☺ — 读书”",
      "colour": "YELLOW",
      "comment": "Revisit this passage.",
      "book_title": "Fóundation",
      "book_author": null,
      "created_at": 1700000000000,
      "updated_at": 1700000000100,
      "deleted_at": null
    }
  ]
}
```

`id` is a canonical UUID. `document` follows the existing normalized identity
rules. `locator` is an opaque string, preserved byte for byte; `text` is the
selected quotation. The optional `comment` holds up to 16,384 Unicode code points.
Omitting it preserves the stored comment. Sending `null` explicitly clears it when
the edit wins conflict resolution. Colours are `YELLOW`, `GREEN` or `PINK`. Timestamps are
nonnegative signed 64-bit epoch milliseconds; `created_at` and a non-null
`deleted_at` must not exceed `updated_at`. `book_author` and `metadata_id` may be
null. Quote text and locator must be nonblank.

The response is `{"results":[{"status":"accepted","item":{...}}]}` in input
order. A losing mutation has status `stale`; both statuses include the complete
winning row. An identical replay is accepted without allocating a new revision.
Acknowledgement must match the exact sent local snapshot so an edit made during
the request remains pending.

Before comparison, both candidates use the maximum of their
`identity_hash_version` values, so older clients cannot lower the learned version.
Conflicts then select the maximum tuple `(updated_at, deleted_at != null,
deleted_at or 0, metadata_id or "", content_hash, identity_hash_version,
locator, text, colour, book_title, book_author != null, book_author or "",
created_at, comment != null, comment or "")` after identity normalization. Strings compare by unsigned UTF-8
order. This makes deletion win an equal-time tie and makes replay order
irrelevant. A version-only upgrade preserves the winning payload and allocates
a new revision even when the incoming edit is stale. Device clocks affect
conflict resolution. Tombstones are retained.

### `GET /sync/v1/highlights?since=0&limit=500`

`since` is a decimal integer revision cursor, initially `0`; `limit` is 1–500.
The response is `{"items":[...],"next_cursor":"2","has_more":false,"supports_comments":true}`.
Clients use `supports_comments` to detect servers that can store personal comments.
Continue with `next_cursor` while `has_more` is true. Each changed row receives
a distinct per-user revision under the same transaction lock used by pulls.
This prevents a later commit from appearing behind an already returned cursor.
Persist the cursor only after applying the complete page locally. Always pull
after pushing; a push response does not advance the pull cursor.

`409` with detail `highlight_cursor_reset_required` means the server's revision
history is behind the supplied cursor: reset to `0` and pull again. An older
server returning `404` leaves local edits pending while progress sync continues.
The shared contract fixture is `server/tests/fixtures/highlight-sync.json`;
Python and Kotlin tests consume the same Unicode quote and opaque locator.

## Documents (planned)

### `POST /sync/v1/documents/alias`

Used exactly once per document, the first time the client has both a
`content_hash` and a `metadata_id` for a record originally written by hash
alone.

```http
POST /sync/v1/documents/alias
Authorization: Basic ...
Content-Type: application/json

{ "content_hash": "8e3a...", "metadata_id": "9780141036144" }
```

In one transaction the server:

1. Finds the record keyed by `content_hash`.
2. Finds the record keyed by `metadata_id` (may not exist).
3. If both exist and differ, merges: record-level LWW for scalars, set union
   with tombstone resolution for bookmarks.
4. Deletes the orphan, returns the surviving record's identity.

## Library items (`/library/v1`)

Per-user mirror of the on-device library. Mounted only when
`QUIRE_SERVER_PROGRESS_ENABLED=true`. USER-SCOPED — `library_items.user_id` is
part of every uniqueness constraint and not shared cache.

Identity travels in the request body (URL-encoded sha256s in paths are a
footgun). Two write surfaces ship today: the per-item `PUT/GET/DELETE
/library/v1/items` flow (legacy, wire-field `content_hash`) and the
push-model `POST /library/v1/sync` bulk batch introduced in Phase 0
(wire-field `identity_hash`). Both write to the same `library_items`
table; the wire-vs-attribute naming split is documented in
[Push model § Wire-vs-attribute naming](#wire-vs-attribute-naming-x-1).

### `POST /library/v1/sync`

> **Status.** Phase 0 / pre-Cloud push-model contract, shipped
> 2026-05-22. Android and Quire Web use the same endpoint. Pinned
> fields (`identity_hash`, `identity_hash_version`, `status`) are stable;
> the rest of the request and response shape may evolve before the Cloud
> private beta as new fields land.

Bulk upsert + tombstone batch. The client pushes whatever entries it
believes need attention; the server merges them into `library_items` in
one transaction.

Quire Web sends an optional `Idempotency-Key` header with the queued operation's
UUID and exactly one item. Keep the key and payload unchanged when retrying.
The server commits a receipt in the same transaction as the mutation and
returns its original summary on replay, even if another device has since
changed the item. Reusing the key with a different payload returns
`409 idempotency_key_conflict`. Receipts are scoped to the authenticated user.
Requests without the header retain the bulk push behavior.

```http
POST /library/v1/sync
Authorization: Basic ...   (or Bearer ... under NativeAuth)
Content-Type: application/json

{
  "items": [
    {
      "identity_hash": "8e3a...",
      "identity_hash_version": 1,
      "status": "present",
      "title": "Crime and Punishment",
      "metadata_id": "9780141036144",
      "authors": ["Fyodor Dostoevsky"],
      "series_name": null,
      "series_index": null,
      "isbn": "9780141036144",
      "language": "en",
      "subjects": ["Fiction", "Classics"],
      "opds_href": "/opds/book/42/download",
      "catalog_id": "3f2b9c1e-8d4a-4f6b-9a7e-2c5d1e0b4a93",
      "last_seen_at": "2026-05-22T10:15:32+00:00"
    },
    {
      "identity_hash": "f04c...",
      "identity_hash_version": 1,
      "status": "deleted"
    }
  ]
}
```

Per-entry fields:

| Field                   | Required when…             | Notes                                                                                       |
| ----------------------- | -------------------------- | ------------------------------------------------------------------------------------------- |
| `identity_hash`         | always                     | sha256/MD5 hex of the EPUB body — same value the legacy `content_hash` column holds.        |
| `identity_hash_version` | always                     | Integer `>= 1`. Server applies `max(existing, incoming)`; older clients cannot downgrade.   |
| `status`                | always                     | `"present"` (upsert) or `"deleted"` (tombstone).                                            |
| `title`                 | `status == "present"`      | Required to populate the `NOT NULL` column.                                                 |
| `metadata_id`           | optional                   | Normalized OPF `dc:identifier`. Conflict with a different `identity_hash` aborts the batch. |
| `authors`               | optional                   | List of strings.                                                                            |
| `series_name`           | optional                   |                                                                                             |
| `series_index`          | optional                   |                                                                                             |
| `isbn`                  | optional                   |                                                                                             |
| `language`              | optional                   |                                                                                             |
| `subjects`              | optional                   | List of strings.                                                                            |
| `opds_href`             | optional                   |                                                                                             |
| `catalog_id`            | optional                   | UUID of the saved catalog the book came from; stored lowercase. Not a foreign key. Unlike every other field, omitted keeps the stored value; explicit `null` clears it (see PUT below). |
| `last_seen_at`          | optional                   | ISO-8601, **tz-aware** (naive rejected). Validated but not persisted in v1.                 |

Batch semantics:

- **All-or-nothing.** Any `409 metadata_id_conflict` or any `422`
  aborts the entire batch — no partial commit. Atomicity is enforced
  by a single end-of-handler commit.
- **Bounded.** `QUIRE_SERVER_LIBRARY_SYNC_MAX_ITEMS` (default 500) caps
  the parsed entry count; `QUIRE_SERVER_MAX_REQUEST_BYTES` (default
  1 MiB) caps the raw body. Either limit returns `422` /`413`
  respectively.
- **Diff-skip idempotency.** A `present` entry whose persisted row
  already matches every field (and would not move
  `identity_hash_version`) is counted as `skipped`, NOT rewritten.
  Bumping `updated_at` on every replay would flood
  `GET /library/v1/items?since=` with the entire library each cycle.
- **No soft-delete heuristic.** Missing entries are NEVER auto-tombstoned.
  Deletes travel as explicit `status="deleted"` commands.
- **Unknown-hash tombstones are no-ops.** A `status="deleted"` entry
  for an `identity_hash` the server has never seen is counted as
  `missing_deleted` and produces nothing (the `title` column is
  `NOT NULL` and we refuse to materialize phantom rows).
- **Reactivation.** A `present` entry whose row exists with
  `deleted_at IS NOT NULL` reactivates the row (counted as
  `reactivated`).
- **Intra-payload duplicates.** Two entries with the same
  `identity_hash` collapse if identical; otherwise `422
  duplicate_identity_hash_conflict`. Two entries claiming the same
  `metadata_id` for different `identity_hash`es → `422
  metadata_id_collision_in_payload`.
- **Strict request schema.** `LibrarySyncEntry` /
  `LibrarySyncRequest` set `extra="forbid"`. Legacy clients sending the
  old `content_hash` field fail loudly with `422 extra_forbidden` rather
  than silently dropping their identity payload.

Response (`LibrarySyncSummary`):

```json
{
  "received": 2,
  "processed": 2,
  "created": 1,
  "updated": 0,
  "reactivated": 0,
  "deleted": 1,
  "skipped": 0,
  "missing_deleted": 0,
  "server_time": "2026-05-22T10:15:32.000+00:00"
}
```

Error codes specific to this endpoint:

| Status | Body `detail.error`                  | Trigger                                                              |
| ------ | ------------------------------------ | -------------------------------------------------------------------- |
| 409    | `metadata_id_conflict`               | A persisted row has the same `metadata_id` under a different `identity_hash`. Detail also carries `existing_identity_hash` + `incoming_identity_hash`. |
| 422    | `too_many_items`                     | `len(items) > library_sync_max_items`. Detail carries `limit` + `received`. |
| 422    | `duplicate_identity_hash_conflict`   | Same `identity_hash` appears twice in the payload with conflicting status/metadata. |
| 422    | `metadata_id_collision_in_payload`   | Two payload entries claim the same `metadata_id` for different `identity_hash` values. |
| 422    | `extra_forbidden`                    | Schema rejection (e.g. legacy `content_hash` field). |

### `PUT /library/v1/items`

Idempotent upsert keyed by `(user_id, content_hash)`. Soft-deleted rows
reactivate on PUT. If the payload supplies a `metadata_id` that conflicts
with a different content-hash row, the server returns `409` with
`{ "error": "metadata_id_conflict", "existing_content_hash": "..." }`
(PR2's identity aliases own the merge case; PR1 refuses to silently merge).

```json
{
  "item": {
    "metadata_id": "9780141036144",
    "content_hash": "8e3a...",
    "title": "Crime and Punishment",
    "authors": ["Fyodor Dostoevsky"],
    "series_name": null,
    "series_index": null,
    "isbn": "9780141036144",
    "language": "en",
    "subjects": ["Fiction", "Classics"],
    "opds_href": "/opds/book/42/download",
    "catalog_id": "3f2b9c1e-8d4a-4f6b-9a7e-2c5d1e0b4a93"
  }
}
```

`catalog_id` is optional and validated as a UUID (`422` otherwise). It is
the one field PUT and sync do not replace when omitted: leaving it out keeps
the stored value, and only an explicit `null` clears it. Clients that predate
saved catalogs never send it and re-push the whole library, so full replace
would keep wiping the value a newer client set. An omitted `catalog_id` is
also not a change, so a replay from such a client is still skipped.

Response: the persisted row, including server-assigned `created_at`,
`updated_at`, and `deleted_at` (null on a live row).

### `GET /library/v1/items`

```
GET /library/v1/items[?since=<ISO8601>][&limit=200][&offset=0]
```

- Without `since`: returns alive rows only (`deleted_at IS NULL`) —
  reconcile-pass shape.
- With `since`: returns rows where `updated_at > since`, **including
  tombstones** (`deleted_at IS NOT NULL`) so clients can mirror deletes.
- Ordering is `(updated_at ASC, pk ASC)`; the pk tiebreaker prevents
  same-timestamp collisions from skipping rows across pages.
- `server_time` in the response is captured BEFORE the SELECT and bounds
  the current page so concurrent writes don't leak in. Clients persist it
  and use it as the next `since`.
- `limit` defaults to 200, capped at 1000. `offset` defaults to 0.

```json
{
  "items": [ ... ],
  "server_time": "2026-05-16T21:33:25.000+00:00"
}
```

### `DELETE /library/v1/items`

```json
{ "item": { "content_hash": "8e3a..." } }
```

Sets `deleted_at = now()` on the matching row. `404` if no row matches.
Idempotent: DELETE on an already-deleted row is a no-op (both timestamps
preserved — refreshing `updated_at` here would re-deliver the tombstone on
every subsequent `GET ?since=<old_cursor>`).

### `GET /library/v1/stats`

Per-user library roll-up. Joins `library_items` with `progress` for
the read-state counts and with the live `book_insights` cache for the
theme leaderboard. Mode-gated on `QUIRE_SERVER_PROGRESS_ENABLED`.

```http
GET /library/v1/stats
Authorization: Basic ...
```

Response:

```json
{
  "total_books": 142,
  "finished_count": 37,
  "in_progress_count": 5,
  "abandoned_count": 3,
  "top_authors": [
    { "name": "Isaac Asimov", "count": 12 },
    { "name": "Ursula K. Le Guin", "count": 7 }
  ],
  "top_themes": [
    { "theme": "science_fiction", "count": 28, "note": "v3+ insights only" },
    { "theme": "epic", "count": 11, "note": "v3+ insights only" }
  ],
  "themes_caveat": "Theme stats include books with AI theme data; older cached insights may be missing until regenerated."
}
```

- `abandoned_count` counts library items whose Progress row has
  `abandoned_at IS NOT NULL` (introduced alongside the
  `progress.abandoned_at` column, PR-α). The three count buckets
  (`finished_count`, `in_progress_count`, `abandoned_count`) are
  mutually disjoint: `in_progress_count` excludes books marked
  abandoned, even if their progress percent > 0.
- `themes_caveat` is a constant server-emitted string the client renders
  verbatim. Sourcing it server-side means the wording can change without
  an app release.
- The theme query uses a DISTINCT-ON CTE that picks one
  `book_insights` row per `library_item` (mirroring
  `service.py::_lookup_live`: `metadata_id`-priority match →
  `content_hash` fallback → most-recent `generated_at` tiebreaker)
  filtered by `superseded_at IS NULL` and `BookTheme.confidence >= 1.0`,
  then `COUNT(DISTINCT picked.library_item_pk)` per theme. Three filters
  are load-bearing — they protect against regenerate-double-counting
  and off-vocab pollution. See
  [`architecture.md`](architecture.md#library-stats-v0-pr9-2026-05-17)
  for the rationale.

## Bookmarks (planned)

### `POST /sync/v1/bookmarks`

```json
{
  "bookmarks": [
    {
      "id": "8b2a3f10-...",
      "document": { "metadata_id": "...", "content_hash": "..." },
      "cfi": "epubcfi(/6/4!/4/2/2[ch01]/2/1:0)",
      "text_snippet": "It was the best of times, it was the worst of times...",
      "updated_at": "2026-04-26T10:15:00Z",
      "deleted": false
    }
  ]
}
```

IDs are client-generated UUIDs. The server never reassigns them. This lets
the client create bookmarks offline and reference them locally before first
sync.

Response per bookmark:

```json
{
  "id": "8b2a3f10-...",
  "status": "accepted" | "rejected_stale",
  "server_updated_at": "2026-04-26T10:15:00Z"
}
```

Conflict resolution is record-level LWW on `updated_at` (see
[`architecture.md`](architecture.md#bookmarks-designed-not-built)).

### `GET /sync/v1/bookmarks`

```
GET /sync/v1/bookmarks?since=<ISO8601>[&document=<id>]
```

Returns rows where `updated_at > since`, **including tombstones**
(`deleted: true`), so deletes propagate.

## Saved catalogs (`/catalogs/v1`)

A reader signed in with a Quire account saves the book catalogs they use
(an address, plus a login when the catalog needs one), and every device
signed in to that account gets the same list. The server keeps each address
and login together in one encrypted value (see
[Synced catalogs](configuration.md#synced-catalogs) for the key). The full
address counts as a secret, because some servers (Kavita) put an account key
in the URL path.

Who can call it: a Quire account session only, Bearer token or browser
cookie (`/secret` takes a Bearer token only). A Calibre-Web Basic login gets `401 native_login_required`, and
legacy-only (`calibreweb`) deployments do not mount these routes. While the
server has no catalog key, every route answers `404 {"detail": "Not Found"}`,
the same as a route that does not exist, and `GET /quire/v1/bootstrap`
reports `catalogSync: false`. `catalogSync` is `true` only when the key is
present and this browser can sign in to a Quire account on this host.
`catalogProxy` is `true` when, on top of that, `/fetch` is on
(`QUIRE_SERVER_CATALOG_PROXY_ENABLED`).

Rules for every route:

- Every response, errors included, is `Cache-Control: no-store`.
- Rows belong to the account's storage scope. Another account's catalog is
  indistinguishable from one that does not exist.
- A `422` never repeats what was sent. Schema errors come back as
  `{"detail": [{"type", "loc", "msg"}]}`, without FastAPI's usual `input`.
  Rule errors come back as `{"detail": "<code>"}`.
- The server logs a catalog by its id only.
- The browser's account binding (`X-Quire-Account`) covers `/catalogs/v1/`:
  a mismatch is `409 account_changed`, as on `/library/v1/`.

### `GET /catalogs/v1/catalogs`

```json
{
  "revision": "6f1c0d9e2b7a4c3f8e5d1a0b9c8d7e6f",
  "catalogs": [
    {
      "id": "3f2b9c1e-8d4a-4f6b-9a7e-2c5d1e0b4a93",
      "name": "Kavita",
      "kind": "opds",
      "host": "https://books.example.org",
      "has_login": true,
      "needs_login": false,
      "position": 0,
      "updated_at": "2026-11-25T10:15:32.123456+00:00"
    }
  ]
}
```

In `position` order. No address, username or password, only `host`: scheme,
host and a non-default port, never the path. A name with non-ASCII letters
shows in its ASCII (`xn--`) form. `kind` is `calibre` for a
Calibre-Web catalog and `opds` for any other OPDS server. `updated_at` moves
on every change to that catalog, its position included, so a device fetches a
catalog's `/secret` when the catalog is new or its `updated_at` changed.

`needs_login: true` means the server cannot decrypt the stored address and
login, typically after a database restore without its key file. The row is
still listed (one unreadable row never fails the list), `has_login` is then
`false` because the server cannot tell, and the reader has to enter the
catalog again with a `PUT`.

The response carries `ETag: "<revision>"`. Send it back as `If-None-Match`
and an unchanged list is a `304` with no body. The revision changes on every
create, edit, reorder and delete, and when a row becomes readable or
unreadable. A save that changes nothing keeps it, even when it moves the row
to a newer key.

### `GET /catalogs/v1/catalogs/{id}/secret`

```json
{ "url": "https://books.example.org/api/opds/<key>", "username": "reader", "password": "..." }
```

`username` and `password` are both `null` for a catalog without a login.
Bearer token only: a browser cookie session gets `403 token_required`, so a
script running on Quire's origin cannot read saved logins (Quire Web browses
saved catalogs through `/fetch` instead). `404 not_found` for an unknown id. `409 needs_login` when the stored value
cannot be decrypted: ask the reader for the catalog's address and login again
and `PUT` them.

### `PUT /catalogs/v1/catalogs/{id}`

```json
{
  "name": "Kavita",
  "kind": "opds",
  "url": "https://books.example.org/api/opds/<key>",
  "username": "reader",
  "password": "..."
}
```

Creates or replaces the catalog with this id, which the client chooses
(a UUID, otherwise `422`), so a retried create lands on the same row.
Answers `200` with the catalog as listed. A save identical to what is stored
writes nothing. A new catalog goes last; a replaced one keeps its place.

`username` and `password` are optional, and blank counts as absent. On an
existing catalog, sending `username` without `password` keeps the stored
password; sending neither removes the login. The password is kept only while
the address stays on the same scheme, host and port: a new path is fine, but
moving to another server needs the password again (`422 login_incomplete`),
so a stored password is never sent somewhere it was not entered for. Keeping
it also needs the stored value, so on a row that cannot be decrypted that
save is `409 needs_login`: send the password again.

| Status | `detail` | When |
|---|---|---|
| 409 | `catalog_limit_reached` | Creating a 21st catalog. An account holds at most 20; replacing one at the limit is fine. |
| 409 | `needs_login` | `password` omitted and the stored one cannot be read. |
| 422 | `invalid_name` | `name` is blank after trimming (at most 60 characters). |
| 422 | `invalid_url` | `url` does not parse, has an invalid port, contains spaces, control characters or `\`, or is not `http` or `https`; or its host is not an IPv4 address, a bracketed IPv6 address (no zone) or a name valid under IDNA 2008 (letters, digits, `-` and `.` once in ASCII form). At most 2048 characters. |
| 422 | `https_required` | `url` is `http` and `QUIRE_SERVER_CATALOG_ALLOW_HTTP` is not `true`. |
| 422 | `credentials_in_url` | `url` carries a login (`user:pass@host`); send it as `username` and `password`. |
| 422 | `login_incomplete` | Only one of `username` and `password`, with no stored password to keep, or the address moved to another scheme, host or port. |
| 422 | schema list | Missing or unknown field, `kind` other than `calibre` or `opds`, `username` over 256 or `password` over 1024 characters. |

### `DELETE /catalogs/v1/catalogs/{id}`

`204` whether or not the catalog existed, so a retry is safe.

### `PUT /catalogs/v1/order`

```json
{ "ids": ["3f2b9c1e-8d4a-4f6b-9a7e-2c5d1e0b4a93", "0c8e6a4f-2d1b-4e9a-8c7f-5b3a1d0e9f82"] }
```

The account's whole list in its new order. Answers `200` with the list as
`GET` returns it, new `revision` and `ETag` included. A list that is not
exactly the account's current catalogs (one missing, one extra, a duplicate)
is `409 order_mismatch`: fetch the list and try again.

### `GET /catalogs/v1/catalogs/{id}/fetch`

For Quire Web, which cannot read most third-party catalogs itself (they send
no CORS headers) and must not hold their logins. The server fetches the page
with the catalog's saved login and passes the answer on. Android talks to its
catalogs directly and does not use this.

```http
GET /catalogs/v1/catalogs/3f2b9c1e-8d4a-4f6b-9a7e-2c5d1e0b4a93/fetch?url=https%3A%2F%2Fbooks.example.org%2Fopds%2Fnew
```

`url` is optional; without it the server fetches the saved address, the
catalog's home. With it, `url` must be on the saved address's origin: the
same scheme, host and port. A link in a feed that points anywhere else is the
browser's to fetch directly, without the login. Only `GET`. A browser cookie
session must also send `X-Quire-Account` (as for any account route, it must
match the signed-in account); without it the answer is
`403 account_header_required`. A Bearer token needs no such header.

The page sees the catalog's paths (in `url` and `Content-Location`), never
its saved login.

The server then:

- resolves the host and refuses the request when any address it gets is not
  globally routable. `QUIRE_SERVER_CATALOG_PROXY_ALLOW_PRIVATE=true` allows
  RFC 1918, CGNAT and IPv6 unique local addresses as well, nothing more
  (loopback and link-local never; the full list is in
  [configuration](configuration.md#quire_server_catalog_proxy_allow_private)).
  A refused address answers the same `502 catalog_unreachable` as a name that
  does not resolve, so the answer does not reveal what a name resolves to
  inside the server's network. It connects to an address it checked, trying
  at most 2, while TLS still verifies the real host name, so a DNS answer
  that changes in between cannot redirect it.
- follows at most 3 redirects itself, each on the same origin and checked
  again. A redirect to another origin is refused, so the login never leaves
  the catalog's origin.
- sends the catalog only `Accept`, `Accept-Language`, `Range`, `If-Range`,
  `If-None-Match` and `If-Modified-Since` from the browser, plus the saved
  login as `Authorization: Basic`. Never the browser's cookies or its own
  `Authorization`.
- asks for an uncompressed answer and refuses one with any `Content-Encoding`
  other than `identity` (`502 catalog_compressed`). The body is relayed as
  the raw bytes received, never decoded, so the caps below count what
  crosses the wire.
- reads feeds and covers (Atom, XML, OpenSearch, AVIF, WebP, PNG, JPEG, GIF)
  whole, up to 5 MB, and treats anything else as a download, up to 200 MB.
  The caps count the bytes received, whatever `Content-Length` says. A
  download the catalog frames by `Content-Length` is streamed as it arrives,
  and one that stops short leaves the browser a broken transfer. Any other
  download is gathered first, then sent with its `Content-Length`, so a cut
  one is an error (`catalog_too_large` or `catalog_unreachable`) and never a
  shorter book.
- gives up when the catalog's response headers have not all arrived 20
  seconds after the request started (DNS, connecting and redirects
  included), or a feed or cover is not whole after 60 seconds. A download has
  no total limit; it ends when the catalog sends nothing for 30 seconds.
- runs at most 4 fetches at once for one account and
  `QUIRE_SERVER_CATALOG_PROXY_MAX_CONCURRENT` for the whole server, and holds
  no database connection while it waits for the catalog.
- after 5 `catalog_login_rejected` answers in a row for one catalog, stops
  asking that catalog for 5 minutes, or until its login is saved again, and
  answers `catalog_login_rejected` straight away.

A success is the catalog's `200`, `206` or `304`, with its `ETag`,
`Last-Modified`, `Accept-Ranges` and `Content-Range`, each only when it is
printable ASCII. No other catalog header comes through (`Set-Cookie`
included). A browser request header that is not printable ASCII is dropped
too. `Content-Location` is the catalog
address that answered, after redirects, so the browser can resolve the page's
relative links. The body is another site's content served from Quire's
origin, so it can never act as a page there: every answer is
`X-Content-Type-Options: nosniff`,
`Content-Security-Policy: sandbox; default-src 'none'`,
`Cross-Origin-Resource-Policy: same-origin` and `Cache-Control: no-store`.
A download keeps its type only when it is EPUB, PDF, ZIP or
`application/octet-stream` (anything else, `text/html` and SVG included,
becomes `application/octet-stream`) and always gets the server's own
`Content-Disposition: attachment`, with the catalog's file name, cleaned up,
as `filename*`, and always carries `Content-Length`.

| Status | `detail` | When |
|---|---|---|
| 401 | `native_login_required` | A Calibre-Web Basic login, or no session. |
| 403 | `account_header_required` | A browser cookie session without `X-Quire-Account`. |
| 404 | `Not Found` | `QUIRE_SERVER_CATALOG_PROXY_ENABLED=false`, or no catalog key. |
| 404 | `not_found` | No such catalog on this account. |
| 409 | `needs_login` | The saved address and login cannot be decrypted. |
| 422 | `other_origin` | `url` is not on the saved address's origin. |
| 422 | `invalid_url`, `https_required`, `credentials_in_url` | As for `PUT`. `https_required` also applies to a saved `http` address once `QUIRE_SERVER_CATALOG_ALLOW_HTTP` is off. |
| 429 | `rate_limited` | More than `QUIRE_SERVER_CATALOG_PROXY_RATE_PER_MIN` fetches this minute. `Retry-After` says when to try again. |
| 429 | `too_many_fetches` | 4 fetches already running for this account, or the server's limit reached. `Retry-After: 1`. |
| 502 | `catalog_login_rejected` | The catalog answered `401` or `403`: the saved login no longer works (or the catalog needs one). Also while the server has stopped asking a catalog that kept refusing its login. |
| 502 | `catalog_compressed` | The catalog sent a compressed (`Content-Encoding`) answer. |
| 502 | `catalog_error` | The catalog answered another error; the body also carries its `status`, as in `{"detail": "catalog_error", "status": 404}`. |
| 502 | `catalog_redirect_refused` | A redirect to another origin, a malformed one, or more than 3. |
| 502 | `catalog_too_large` | A feed or cover over 5 MB, a `Content-Length` over the cap, or a download without one that passes the cap. |
| 502 | `catalog_unreachable` | The name does not resolve or resolves to a refused address, no address connects, TLS fails, or the catalog misses a deadline. |

The server logs these by catalog id and reason only (a refused address is
logged as `address_refused`), never the address or login.

### In account export and deletion

`GET /auth/v1/account/export` includes `account_catalogs`, one entry per
catalog with its readable `url`, `username` and `password` in place of the
encrypted value. A catalog that cannot be decrypted is exported with
`secret_unreadable: true` and no address or login. Purging an account deletes
its catalogs.

## Health

```
GET /health
GET /readyz
```

Both endpoints mount on the root path (no `/sync/v1` prefix) and are always
available regardless of deploy mode. The previous `/sync/v1/healthz` was
removed in PR-A; cluster manifests must point at `/health` going forward.

- `/health` is liveness. Returns
  `{ "ready": true, "version": "dev", "modes": ["progress","ai"], "warnings": [] }`
  where `version` is `QUIRE_SERVER_VERSION` (the commit sha the running
  image was built from; `"dev"` outside a built image), `modes`
  reflects `QUIRE_SERVER_PROGRESS_ENABLED` and `QUIRE_SERVER_AI_ENABLED`, and
  `warnings` lists the plain-language configuration problems the server
  found at boot (an unknown `QUIRE_SERVER_*` variable, AI enabled without
  `QUIRE_SERVER_AI_BASE_URL` and `QUIRE_SERVER_AI_MODEL`, a provider URL
  without `/v1`, or both `QUIRE_SERVER_PROGRESS_ENABLED` and
  `QUIRE_SERVER_AI_ENABLED` false). Empty when the configuration is clean.
  The warnings name variables and never echo values, which matters because
  `/health` needs no authentication and the full stack serves it at the
  public origin. Returns 200 unless the process is broken.
- `/readyz` is readiness. Opens a Postgres connection and verifies that all
  required migration heads (for the enabled modes) are present in
  `alembic_version`. Returns 200 with `heads_applied` listing current heads,
  or 503 with `missing` listing migrations the DB has not yet applied.

## Errors

Standard FastAPI shape:

```json
{ "detail": "..." }
```

| Status | Meaning |
|---|---|
| 400 | Malformed request (missing fields, bad types, neither identifier supplied). |
| 401 | Missing or invalid Basic credentials, or calibre-web rejected them. |
| 404 | Document not found (pull paths). |
| 409 | Identity conflict the server cannot auto-resolve (rare; mostly future-proofing for the alias endpoint). |
| 422 | Validation failure (FastAPI default). |
| 500 | Server error. |
| 502 | An AI provider call failed; `detail` is an object, see [AI provider errors](#ai-provider-errors), or a plain string on `/profile/refresh` when the failure is not a provider error. |
| 503 | Database unavailable (`/readyz`) or calibre-web unreachable for auth probes. |
| 504 | The AI provider did not answer within `QUIRE_SERVER_AI_TIMEOUT_S`; same `detail` object. |

## AI endpoints (`/ai/v1`)

Optional. Returns 503 when the server is not configured for AI
(`QUIRE_SERVER_AI_ENABLED=false` or missing `AI_BASE_URL`/`AI_MODEL`).

### Quota model

Two layers protect the configured AI endpoint:

- **`AI_RATE_PER_MIN`** (default 10): process-wide token bucket against the
  AI provider. Cache reads bypass it; first-time generations may queue
  briefly under load.
- **`AI_DAILY_BUDGET`** (default 200) and **`AI_REGEN_DAILY_LIMIT`**
  (default 3): per-user counters in `ai_usage_daily`. Exceeding either
  returns **429** with a JSON body `{ "detail": { "used", "limit", "resets_at" } }`
  and a `Retry-After` header (seconds until next UTC midnight). Set
  `AI_DAILY_BUDGET=0` to disable the per-user cap.

### AI provider errors

When the model call behind `/insights/lookup`, `/insights/regenerate`,
`/profile/refresh` or `/highlights/discuss` fails, the response carries a structured `detail`
instead of the bare 500 that older servers returned (issue #102):

```json
{
  "detail": {
    "code": "provider_timeout",
    "message": "The AI provider did not answer within 120 seconds.",
    "hint": "Raise QUIRE_SERVER_AI_TIMEOUT_S for slow local models, or pick a faster model.",
    "provider_status": null
  }
}
```

`message` is written for the reader. `hint` (nullable) is written for the
operator and names the variable to change. `provider_status` is the
upstream HTTP status when the provider answered. No field ever contains
the API key, the prompt, or provider response text.

| Status | `code` | When |
|---|---|---|
| 504 | `provider_timeout` | No answer within `QUIRE_SERVER_AI_TIMEOUT_S` (`QUIRE_SERVER_AI_PROFILE_TIMEOUT_S` for the profile). The server has stopped waiting; let the user retry in a minute. |
| 502 | `provider_unreachable` | Connection error, or a 5xx from the provider. `provider_status` stays null even for that 5xx: the server treats an upstream 5xx like an outage rather than an answer. |
| 502 | `provider_rejected` | The provider answered 4xx; `provider_status` carries it (401/403 credentials, 404 unknown model). |
| 502 | `provider_invalid_output` | The provider answered 200 but not with parseable structured JSON, even after one retry, or the body was not a JSON object at all (for example a proxy answering with a web page in place of the provider). |
| 502 | `provider_error` | Any other provider failure. |

Clients that predate this section keep working: they see a 502 or 504
with a JSON body where they used to see a 500 without one.

### `GET /ai/v1/config`

Returns the user-visible AI configuration. Public to authed users.

```json
{
  "configured": true,
  "base_url_host": "ollama.example.lan",
  "model_id": "llama3.1:8b",
  "sources_enabled": ["wikipedia", "openlibrary"],
  "daily_budget": 200,
  "regen_daily_limit": 3,
  "prompt_version": "5",
  "progress_supported": true,
  "generation_timeout_s": 120,
  "profile_timeout_s": 90,
  "details_supported": true,
  "conversion_supported": false,
  "highlights_discuss": true,
  "highlights_discuss_limits": {
    "max_highlights": 20,
    "max_text_chars": 2000,
    "max_note_chars": 1000,
    "max_title_chars": 500,
    "max_authors": 10,
    "max_author_chars": 200,
    "max_question_chars": 1000,
    "max_answer_chars": 4000,
    "max_questions": 6,
    "max_total_chars": 60000,
    "questions_per_minute": 10
  }
}
```

`prompt_version` (PR-η, Lock #24) is the runtime-resolved value (post-PR-ε
sentinel resolution). The Android client reads it so its local-cache PK
aligns with the server's. Older deploys that don't emit the field decode
safely on the client side because the DTO default is `"1"` (the legacy
sentinel which means "use the in-code constant").

`progress_supported` (pr-β, Lock #15 / coordinator §3.5) surfaces the
deployment's `PROGRESS_ENABLED` flag. The Android Insights screen reads
this to suppress the reader-profile UI on AI-only deploys (where
`POST /ai/v1/profile/refresh` would 503 with
`{"error": "profile_requires_progress_data"}`). Older deploys that don't
emit the field decode safely on the client because the DTO default is
`true`.

`generation_timeout_s` (issue #102) is `QUIRE_SERVER_AI_TIMEOUT_S` rounded
up to whole seconds, `null` when AI is disabled. The Android client sizes
its HTTP timeout for `/insights/lookup` and `/profile/refresh` as twice
this value plus 30 seconds, clamped to 60 to 600, so the phone never gives
up before the server does. Older deploys omit the field and the client
assumes 120.

`profile_timeout_s` (issue #102) is `QUIRE_SERVER_AI_PROFILE_TIMEOUT_S`
rounded up to whole seconds, `null` when AI is disabled. The reader-profile
refresh (`POST /ai/v1/profile/refresh`) runs on a separate, shorter budget
than a regular insight generation; this field lets the Android client size
its wait for that call from the server's actual profile timeout instead of
`generation_timeout_s`, which is never too short but was longer than
needed. Older deploys omit the field; the client falls back to sizing the
profile-refresh wait off `generation_timeout_s`.

`details_supported` is true when the server mounts
[`/details/v1`](#book-details-and-covers-details-v1), that is when
`QUIRE_SERVER_DETAILS_ENABLED` is on. Like the insights, the details also
need `configured` and the reader's opt-in. Older servers omit the field and
clients read it as false. Browser clients find the same flag as
`capabilities.book_details` in the session response.

`conversion_supported` is true when the server mounts
[`/convert/v1`](#pdf-conversion-convert-v1). Conversion does not need AI,
so this is only a hint for clients that already read this response;
`capabilities.pdf_conversion` in `GET /auth/v1/me` and
`GET /convert/v1/capabilities` say the same without AI. Older servers omit
the field and clients read it as false.

`highlights_discuss` is true when
[`POST /ai/v1/highlights/discuss`](#post-aiv1highlightsdiscuss) answers,
that is when `configured` is true. `highlights_discuss_limits` then carries
the limits that endpoint enforces, so clients never hard-code them; it is
`null` when `highlights_discuss` is false. Older servers omit both fields:
clients read them as false and null, and hide the action.

### `GET /ai/v1/preferences` / `PUT /ai/v1/preferences`

Per-user opt-in flag plus two personalization knobs: `tone` and `language`.
Both participate in the cache key for `book_insights` (via the `tone` and
`language` columns), so users on the same instance with different combinations
get their own cached generations rather than one bleeding into the other.

```json
{
  "ai_enabled": true,
  "style": {
    "tone": "neutral",
    "language": "auto"
  }
}
```

| Field            | Default     | Allowed values                                                   |
| ---------------- | ----------- | ---------------------------------------------------------------- |
| `style.tone`     | `"neutral"` | `neutral`, `enthusiastic`, `scholarly`, `casual`                 |
| `style.language` | `"auto"`    | `auto` plus any lowercase ISO 639-1 code (`en`, `it`, `es`, …)   |

`language="auto"` (the default) emits no language clause in the prompt and
preserves byte-for-byte the pre-PR4 prompt body. Any other value sends a
`'Respond in the language identified by ISO 639-1 code "<code>".'` line to
the model. Non-ISO-639-1 codes return **422**.

`PUT` accepts either field independently — send `{ "ai_enabled": true }` to flip
the toggle without changing style, or `{ "style": { "tone": "scholarly" } }`
to update tone without touching opt-in (or `language`). Response always returns
the full resolved state.

**Cache key:** `book_insights` uniqueness is
`(metadata_id|content_hash, model_id, prompt_version, tone, language)`. Bumping
any of these dimensions (e.g. via PR-level `PROMPT_VERSION` bumps) does not
delete old rows — they simply stop being read because the lookup filters on the
new dimensions.

### `POST /ai/v1/insights/lookup`

Cache hit returns the existing insight; cache miss generates synchronously.
Requires opt-in. May return **429** with the quota body shape and `Retry-After`
header if the user's daily budget is exhausted.

A failed model call returns **502** or **504** with the body described under
[AI provider errors](#ai-provider-errors).

Body:

```json
{
  "identity": { "metadata_id": "9780553293357", "content_hash": "abc..." },
  "bundle":   { "title": "Foundation", "author": "Isaac Asimov", "...": "..." }
}
```

**Push-model contract (Phase 0, 2026-05-22).** `bundle` is **required** on
the request body — the server is the consumer, not the source, of book
metadata. Omitted `bundle` is rejected with `400 metadata_required`. The
client computes the metadata on the device (from the EPUB OPF or the OPDS
entry) and ships it as part of the same request that asks for an insight.

`MetadataBundle` field caps are enforced server-side:
`title <= 512`, `author <= 512`, `language <= 32`, `isbn <= 64`,
`publisher <= 256`, `publish_date <= 32`, `subjects <= 64 × 80`,
`description <= 4096`, `series_name <= 256`, `series_position 0–10000`.
Both `InsightLookupBody` and `InsightRegenerateBody` set `extra="forbid"`.

**Cache-key invariant.** The shared `book_insights` cache is keyed on
`(identity, model_id, prompt_version, tone, language)` — `bundle` does
**NOT** participate. Divergent metadata for the same canonical identity
share the cache row (first-writer-wins). Bumping any of the cache-key
dimensions still invalidates naturally; bundle drift does not.

The operator-controlled
`QUIRE_SERVER_AI_METADATA_SERVER_LOOKUP_ENABLED` flag (default `false`)
restores the legacy server-side fallback that reconstructs the bundle
from the caller's `library_items` row. **The flag is deprecated since
the 2026.05.22 release and will be removed two minor releases later**
— see the [Compatibility and deprecations table](#compatibility-and-deprecations).
When enabled, the server emits a `DeprecationWarning` + `logging.warning`
at boot. Plan client cutover to the push model within that window. See
`server/README.md` ("Push-model API: deprecated server-side metadata
fallback") for the operator-side detail.

**Identity fields (PR2 update, 2026-05-16).** `identity.content_hash` is now
**optional** so the catalog-preview flow can request insights before the EPUB
body is downloaded. The full set of accepted hints is:

| Field             | Scope          | Notes                                              |
| ----------------- | -------------- | -------------------------------------------------- |
| `metadata_id`     | canonical      | Normalized OPF `dc:identifier`.                    |
| `content_hash`    | canonical      | sha256 of the EPUB body. Optional since PR2.       |
| `opds_dc_id`      | user-scoped    | `dc:identifier` from the catalog entry.            |
| `isbn`            | global         | Same ISBN means the same book everywhere.          |
| `opds_href`       | user-scoped    | OPDS acquisition href; last-resort fallback.       |

`calibre_book_id` (the calibre-web book number) is accepted and ignored: the
same number names different books on different servers, and `opds_href`
already identifies the catalog entry.

At least one hint must be present. The server walks the hierarchy in the
order above and resolves to a canonical via the `insight_identity_aliases`
table. If no hint resolves on a write path the server returns `422`.
`reconcile_aliases` writes alias rows for every hint that is NOT already the
canonical, so subsequent calls with weaker hints hit the same cache row.

Response: a `BookInsight` with `payload`, `sources`, `model_id`,
`prompt_version`, `generated_at`. See `quire_server/api/ai_schemas.py` for
the full payload schema.

`payload` is the structured `BookInsightPayload` (schema v5 since
2026-09-29, which added `curiosities`; schema v4 since PR-ε on 2026-05-19;
old cached v4, v3 and v2 rows remain valid). The model generates keys
in this order:

```json
{
  "intro": "1-2 sentences saying what the book is and why it matters.",
  "author": {
    "bio": "Concise paragraph if confidence is high; otherwise null.",
    "notable_works": ["…"]
  },
  "series": {
    "name": "Foundation",
    "position": 1,
    "context": "Optional one-liner on how this volume fits."
  },
  "analysis": "One compact paragraph (~80–130 words) weaving synopsis, themes, tone, and reader fit.",
  "content_warnings": ["graphic violence", "sexual content"],
  "themes": ["science_fiction", "epic"],
  "theme_analysis": { "science_fiction": "How that theme manifests in THIS book (2-4 sentences)." },
  "craft_notes": "POV / pacing / structural choices and prose qualities (3-5 sentences).",
  "comparative_anchors": [
    { "book": "Dune", "author": "Frank Herbert", "similar_in": "Both build a future political theology", "different_in": "Dune foregrounds religion" }
  ],
  "distinctive_take": "1-2 sentences on what this book does that others in its themes don't.",
  "curiosities": ["A short, well-documented fact about the book or its author."],
  "discussion_prompts": ["Open-ended question 1?", "Open-ended question 2?"],
  "confidence": "high|medium|low",
  "schema_version": 5
}
```

`content_warnings` is scoped to concrete reader-safety concerns
(violence, sexual content, abuse, self-harm, slurs, addiction, body horror) —
**not** themes, genre, politics, or plot mechanics.

`themes` (PR3, schema v3) is a list of 1-5 topic tags drawn from a controlled
vocabulary (~57 entries — see `quire_server/core/ai/themes.py::CONTROLLED_THEMES`,
covering broad fiction buckets, speculative subgenres, genre fiction, and
nonfiction categories). Vocab hits land in the side table `book_themes` at
`confidence=1.0`; off-vocab strings are preserved verbatim at `confidence=0.5`
so future vocabulary evolution doesn't lose data. The payload field is the
source of truth for the client; `book_themes` is the SQL-queryable mirror
that PR9 library stats reads. Old cached v2 payloads (no `themes` key)
deserialize cleanly with `themes=null`; they contribute zero rows to
`book_themes` until regenerated. The server pins `schema_version=5` after
model return so cache rows never reflect a model's accidental version
emission.

`theme_analysis` (PR-ε, schema v4) is a dict of up to **two** entries keyed
by theme name; each value is 2-4 sentences on how that theme manifests in
THIS specific book. The server REJECTS payloads with more than two keys via
a Pydantic `model_validator`. `craft_notes` is 3-5 sentences combining POV /
pacing / structure with prose qualities (null for ordinary-craft books or
nonfiction). `comparative_anchors` is a list of `{book, author, similar_in,
different_in?}` entries sanitized server-side (blank-field drop, cap at 4)
and display-only. When the `openlibrary` source is enabled, the server looks
each one up on Open Library by title and author surname and drops the ones it
cannot find, which catches invented titles and real books credited to the
wrong author. When Open Library is unreachable the anchors are kept unchecked.
`distinctive_take` is 1-2 sentences differentiating the book from others in
its themes. `discussion_prompts` is 3-5 book-club-style questions (no plot
reveals past the inciting incident, per Lock #7 soft mitigation). All v4
fields are optional; old cached v3 rows (no v4 keys) deserialize cleanly.

`curiosities` (schema v5) is a small "Curiosities" section: 1-3 one-sentence
facts about the book or its author (awards, sales, translations, adaptations,
first publication, the author's life), taken from the external sources first.
From its own knowledge the model may add only widely known, checkable facts,
never where the idea came from or what inspired the book unless a source says
so, and it returns `null` when it has none.

For a work the model does not recognise, when neither the sources nor the
publisher description say what it is about, the prompt asks for an `intro`
built from the metadata alone, `null` for every other content field, and
`confidence: "low"`, rather than a description of a book the model knows
nothing about. Sanitized server-side (blank-entry drop, cap at 3); old v4 rows
deserialize with `curiosities=null`.

`prompt_version` on the response reflects the runtime resolution of
`core/ai/prompts.py::PROMPT_VERSION` via
`core/ai/_compat.py::_resolve_prompt_version` (Lock #19: the legacy default
value `"1"` is treated as "unset" so the constant wins). The env var
`QUIRE_SERVER_AI_PROMPT_VERSION` is an emergency rollback override only —
set it to a non-default value (e.g. `"4"`) to pin an older version during
incident response (Lock #2). Stale rows at the previous `prompt_version`
survive in storage (no migration) and are never queried again after the
runtime resolves to a newer value.

Side-table schema:

```sql
CREATE TABLE book_themes (
    book_insight_id  bigint  NOT NULL REFERENCES book_insights(id) ON DELETE CASCADE,
    theme            text    NOT NULL,
    confidence       real    NOT NULL DEFAULT 1.0,
    PRIMARY KEY (book_insight_id, theme)
);
CREATE INDEX ix_book_themes_theme ON book_themes (theme);
```

`book_themes` is SHARED cache (no `user_id`/`tenant_id`); per-user filtering
in PR9 happens by joining through `book_insights → library_items` on
`metadata_id`/`content_hash`. PR9 MUST also filter
`book_insights.superseded_at IS NULL` to avoid double-counting regenerated
insights.

### `POST /ai/v1/insights/regenerate`

Force a fresh generation. The existing live row is marked `superseded_at` (kept
for audit; queries return the new live row only). The new row records the
previous row's id in `previous_insight_ids`.

Requires opt-in. Subject to a tighter daily ceiling
(`AI_REGEN_DAILY_LIMIT`, default 3 per user). Returns 429 with the same body
shape as `lookup` when exceeded.

A failed model call returns **502** or **504** with the body described under
[AI provider errors](#ai-provider-errors).

Body adds a required `reason`:

```json
{
  "identity": { "metadata_id": "...", "content_hash": "..." },
  "bundle":   { "title": "...", "...": "..." },
  "reason":   "Author bio claimed they were French; Asimov was Russian-American."
}
```

The reason is included in the prompt sent to the model so it knows what to fix.

`bundle` follows the same push-model contract as `/insights/lookup`: required
on the request body, with the deprecated
`QUIRE_SERVER_AI_METADATA_SERVER_LOOKUP_ENABLED` fallback as the only
escape hatch and the same 2-minor-release removal window
(see [Compatibility and deprecations](#compatibility-and-deprecations)).

### `POST /ai/v1/insights/get`

Cache-only read. 404 on miss. Does NOT require opt-in (cached results are
shared across users and remain readable by users who later opt out).

### `POST /ai/v1/insights/invalidate`

Drops the cached row for the current `(model_id, prompt_version)`. Used by
the app's "Invalidate insight" action (PR6 audit UI) and by admin-style cache
busting. After invalidation the next `lookup` regenerates naturally, which is
the user-facing path now that the in-app "Regenerate" affordance has been
removed (PR11). The budgeted `POST /ai/v1/insights/regenerate` endpoint
remains available for admin/cluster tooling. Requires opt-in. Returns
`{"deleted": <int>}`.

### `POST /ai/v1/insights/promote`

Copy a cached catalog-side insight onto the post-download canonical identity
without firing the model. Used by the Android catalog flow: when a user views
an insight under an OPDS identity (`metadata_id="opds-href:<sha>"`) and then
downloads the EPUB, the client calls promote with `from=<catalog identity>`
and `to=<downloaded canonical>` so the BookDetail screen shows the insight
immediately. PR-ζ / Locks #1, #11 amendment, #13, #23.

Request body:

```json
{
  "from": {"metadata_id": "opds-href:abc..."},
  "to":   {"metadata_id": "urn-xyz", "content_hash": "sha256:..."},
  "tone": "neutral",
  "language": "auto"
}
```

`tone` and `language` mirror the cache-key knobs and default to the universal
defaults. The source row at `from` is looked up for the requested
`(model_id, prompt_version, tone, language)` variant. The copy keeps every
payload field verbatim with two exceptions: `generated_at = NOW()` (Lock #23
— promote rows participate in PR-η's sync cursor as fresh events) and
`previous_insight_ids=[<src.id>]` for lineage audit.

Idempotency: the `(from, to, user_id, source='promoted_on_download')` row in
`insight_identity_aliases` is the anchor. A second identical call returns
`already_promoted=true` with the same `insight_id`. A call with a different
`(tone, language)` re-copies a new variant under the same alias.

Responses:

| Status | Meaning |
|--------|---------|
| `200 {"promoted":true,"insight_id":N,"already_promoted":false}` | Fresh copy created. |
| `200 {"promoted":true,"insight_id":N,"already_promoted":true}` | Idempotent re-promote. |
| `204` | Nothing to promote — no source row at `from` for the requested variant. |
| `403 {"detail":"not_owned"}` | The caller does not own a `library_items` row at `to`. |
| `409 {"detail":"ai_not_opted_in"}` | User has not opted in (Lock #10). |
| `429` | Promote daily limit exceeded; body and `Retry-After` mirror the `lookup` 429. |
| `503 {"detail":"ai_disabled"}` | AI is disabled or unconfigured on this deploy. |

Rate limit: `QUIRE_SERVER_AI_PROMOTE_DAILY_LIMIT` (default 100). Process-local
counter; pod restart resets — acceptable because promote has no LLM cost.

Audit: emits a structured stdout line `event=ai.promote ...` with the source
and copy ids, latency, and outcome. Once ai_006 is applied (pr-β) the
promote path also writes a `kind='promote'` row to `ai_generation_log`
(Lock #11 amendment); the stdout line is retained for operator-grep
convenience.

### `POST /ai/v1/profile/refresh`

Pr-β orchestrator: refresh (regenerate) the per-user reader profile.

Empty body. Returns the full `ReaderProfilePayload`:

```jsonc
{
  "schema_version": 1,
  "stats": { /* ReaderStats */ },
  "narrative": "...",
  "confidence": "medium",
  "in_library_recommendations": [
    {
      "candidate_id": "lib-001",
      "title": "...",
      "author": "...",
      "identity": { "metadata_id": "...", "content_hash": "..." },
      "source_type": "in_library",
      "owned_state": "owned_unread",
      "rationale": "..."
    }
  ],
  "discovery_recommendations": [
    {
      "candidate_id": "dis-001",
      "title": "...",
      "author": "...",
      "source_type": "discovery_openlibrary",
      "source_url": "https://openlibrary.org/works/OL...W",
      "owned_state": "not_owned",
      "rationale": "...",
      "sources": [{"kind": "openlibrary", "title": "...", "url": "..."}]
    }
  ],
  "ai_suggested_recommendations": [
    {"title": "...", "author": "...", "source_type": "ai_suggested",
     "owned_state": "not_owned", "rationale": "..."}
  ],
  "input_fingerprint": "abcd1234ef567890"
}
```

Status codes (Lock #10 closes the opt-out question on 409):

| Code | Body / detail | Meaning |
|------|---------------|---------|
| `200` | `ReaderProfilePayload` | Success (live recompute, low-data short-circuit, or singleflight collapse). |
| `404` | `{"detail":"ai_disabled"}` | AI disabled or unconfigured on this deploy. |
| `409` | `{"detail":"ai_not_opted_in"}` | Caller has not opted in (Lock #10). |
| `429` | `QuotaResponse` + `Retry-After` | Daily refresh cap exceeded. |
| `502` | provider error object, or `{"detail":"..."}` | The model call failed; see [AI provider errors](#ai-provider-errors). Failures that are not the provider's keep the plain string. |
| `503` | `{"error":"profile_requires_progress_data"}` | `PROGRESS_ENABLED=false`. |
| `504` | provider error object | No answer within `QUIRE_SERVER_AI_PROFILE_TIMEOUT_S`. |

Rate limit: `QUIRE_SERVER_AI_PROFILE_REFRESH_DAILY_LIMIT` (default 3). Stored
on `ai_usage_daily.profile_count`; resets at UTC midnight. **Weight = 0**
in the low-data short-circuit (`stats.finished_count == 0`) so a user with
no finished books still receives a stats-only payload even at quota cap.

Singleflight: concurrent POSTs from the same `(tenant_id, subject)`
serialize through a per-user in-process lock. Each collapsed waiter writes
its own `kind='profile' status='hit'` row to `ai_generation_log`; only the
winner writes a `status='miss'` row + bumps `profile_count`.

Discovery candidates come from OpenLibrary author bibliographies,
sequential per author, capped at 5 authors per refresh. Positive cache TTL
30d; 404 / empty negative cache TTL 24h; 429 is honored via `Retry-After`
capped at 6h with stale-if-error fallback (coordinator §3.7).

Known limitations:

- Budget row is keyed on `principal.subject` only — no tenant-aware quota
  migration in this batch.
- No automatic profile invalidation. The `input_fingerprint` (16 hex chars
  of a sha256 over stats + library size + latest progress timestamp +
  themed-book count) lets the client detect when the snapshot diverges
  from underlying data; the client-side staleness UX lands in pr-γ.

### `GET /ai/v1/profile`

Cache-only read of the caller's `reader_profiles` row. **Weight = 0** — no
LLM call, no budget charge. Requires opt-in.

```http
GET /ai/v1/profile
Authorization: Basic ...
```

Response: the same `ReaderProfilePayload` documented for
`POST /ai/v1/profile/refresh` (stats, narrative, in-library /
discovery / ai-suggested recommendations, `input_fingerprint`).

| Status | Body | Meaning |
|--------|------|---------|
| `200` | `ReaderProfilePayload` | Cached row returned verbatim. |
| `404` | `{"detail":"no_profile"}` | No row yet — client should call refresh. |
| `404` | `{"detail":"ai_disabled"}` | AI disabled or unconfigured on this deploy. |
| `409` | `{"detail":"ai_not_opted_in"}` | Caller has not opted in. |
| `503` | `{"error":"profile_requires_progress_data"}` | `PROGRESS_ENABLED=false`. |

The Android Library Insights screen calls this on first paint and falls
through to `POST /ai/v1/profile/refresh` on a `no_profile` 404.

### `DELETE /ai/v1/profile`

Erase the caller's `reader_profiles` row. Idempotent: returns `204` whether
or not a row existed. **Weight = 0**. Wired to the Settings "Delete
profile" action; recommended after toggling opt-out so a stale cached
profile cannot resurface if the user opts back in later.

```http
DELETE /ai/v1/profile
Authorization: Basic ...
```

| Status | Meaning |
|--------|---------|
| `204` | Row deleted (or no-op if none existed). |
| `404` | `{"detail":"ai_disabled"}` on AI-disabled deploys. |

This endpoint does not require opt-in — a user who has opted out can
still purge a previously-generated row.

### `GET /ai/v1/insights/sync`

PR-η bulk read of the caller's owned-book insights, joined through
`library_items` (alive rows only) at the caller's current
`(model_id, prompt_version, tone, language)`. Requires opt-in (Lock #10).
**Weight = 0** — never charges against `ai_daily_budget`, never acquires a
generation lock, never calls the model.

Query parameters:

| Name        | Type       | Required? | Notes                                              |
| ----------- | ---------- | --------- | -------------------------------------------------- |
| `since_ts`  | ISO-8601   | optional¹ | Cursor — last item's `generated_at` from prior page. |
| `since_id`  | integer    | optional¹ | Cursor — last item's `id` from prior page.        |
| `limit`     | integer    | optional  | Page size (1..200, default 50).                    |

¹ `since_ts` and `since_id` are tuple cursor coordinates (Lock #23): they
must be supplied together. Half-supplied returns 400.

Response body:

```json
{
  "items": [
    {
      "id": 42,
      "identity": {"metadata_id": "...", "content_hash": "..."},
      "payload":  { ... BookInsightPayload at schema_version 5 ... },
      "sources":  [ ... Citations ... ],
      "model_id": "...",
      "prompt_version": "9",
      "schema_version": 5,
      "tone": "neutral",
      "language": "auto",
      "generated_at": "2026-05-19T00:00:00+00:00"
    }
  ],
  "server_time": "2026-05-19T00:01:00+00:00",
  "next_cursor": {"generated_at": "...", "id": 42}
}
```

`next_cursor` is `null` ⇔ end of stream. The client persists the cursor
between pages and walks until exhausted. Sort order is `(generated_at ASC,
id ASC)`; the filter on a non-null cursor is the strict-lexicographic
`>` comparison so identical timestamps don't drop rows.

Filters applied (in addition to `LibraryItem.user_id == subject`):

- `library_items.deleted_at IS NULL` — soft-deleted books drop out.
- `book_insights.superseded_at IS NULL` — only the live row per identity surfaces.
- PR9 priority `case`: `BookInsight.metadata_id`-match wins over content-hash-only.
- `current_model = settings.ai_model`, `current_pv = _resolve_prompt_version()`.

Promoted rows (PR-ζ) carry `generated_at = NOW()` at copy time so they enter
the cursor stream as fresh events.

### `POST /ai/v1/highlights/discuss`

Think through a few highlights with the server's AI. The reader ticks up to
20 highlights, possibly from different books, and asks a question ("what
connects these?", "turn these into a note I can send"). They may follow up,
up to 6 questions in all, and share the last answer as a note.

The server keeps no conversation and stores nothing of it: the client
sends the passages and every turn so far with each question. No passage,
note, question or answer is logged or written to the database; the server
logs only counts and timings.

Show the action only when `highlights_discuss` is true in
[`GET /ai/v1/config`](#get-aiv1config), and read the limits from
`highlights_discuss_limits` there.

#### Request

```json
{
  "highlights": [
    {
      "n": 1,
      "text": "Fear is the mind-killer.",
      "note": "Compare with the Stoics.",
      "title": "Dune",
      "authors": ["Frank Herbert"]
    },
    { "n": 2, "text": "The spice must flow.", "title": "Dune", "authors": ["Frank Herbert"] }
  ],
  "messages": [
    { "role": "reader", "content": "What connects these?" },
    { "role": "assistant", "content": "Both are about control..." },
    { "role": "reader", "content": "Turn this into a short note I can send." }
  ],
  "provider": "default"
}
```

| Field | Type | Rules |
|---|---|---|
| `highlights` | array, required | 1 to 20 items. Send them again, unchanged, on every turn. |
| `highlights[].n` | integer, required | The client's own number for the passage: 1 or more, unique in the request. Number the picked highlights 1, 2, 3 in the order shown; `cited` answers with these numbers. |
| `highlights[].text` | string, required | The highlighted text. Not blank. At most 2,000 characters. |
| `highlights[].note` | string or null | The reader's own note on the highlight. At most 1,000 characters. Omit or send null when there is none; a blank note counts as none. |
| `highlights[].title` | string or null | The book's title. At most 500 characters. |
| `highlights[].authors` | array of strings | The book's authors, at most 10, each at most 200 characters. Default `[]`. |
| `messages` | array, required | The turns so far, oldest first, strictly alternating: `reader`, `assistant`, `reader`, ... It starts and ends with a `reader` turn, the last being the new question. At most 6 `reader` turns, so at most 11 items. |
| `messages[].role` | `"reader"` or `"assistant"` | `assistant` turns are the server's earlier `answer` values, sent back unchanged. |
| `messages[].content` | string, required | Not blank. A `reader` turn is at most 1,000 characters, an `assistant` turn at most 4,000. |
| `provider` | string or null | Optional. One of the `providers[].id` values from `GET /ai/v1/config`, to use for this question only. Omitted or null: the reader's provider from `PUT /ai/v1/preferences`. |

Characters are Unicode code points. On top of the per-field limits, all
`text`, `note`, `title`, `authors` and `content` strings together may hold
at most 60,000 characters. Every limit is enforced whatever the client
sends: a value over a limit is refused with a 422 naming the field, never
trimmed. Unknown fields are refused too.

#### Response

```json
{
  "answer": "Both passages are about control: one over fear, one over a resource...",
  "cited": [1, 2]
}
```

- `answer`: text to be rendered as plain text. It may contain URLs.
  Clients must never render it as Markdown or HTML: the model is asked
  for plain text, but the server does not rewrite what it returns, so
  stray marks such as `**` may appear and must show as typed. Paragraphs
  are separated by a blank line; a list may use plain hyphens. The model
  is asked to leave out passage numbers, so the answer reads on its own
  when shared. It is in the language of the reader's latest question,
  about 150 words unless the reader asked for more, and never longer than
  4,000 characters, so it can always be sent back as an `assistant` turn:
  in the rare case of a longer answer the server cuts it, at a sentence
  end when one falls in the second half of the limit, and adds "…". When
  the passages cannot answer the question, the answer says so rather than
  guessing.
- `cited`: the `n` values of the passages the answer relies on, in the
  order it uses them. Every value is one of the request's `n`; the server
  drops any other number and any repeat. It may be empty.

Render `answer` as plain text only. Responses carry
`Cache-Control: no-store`.

#### Allowance, rate and log

Each question is one generation: it counts once against the reader's
`AI_DAILY_BUDGET` ([quota model](#quota-model)), and on the hosted service
against the monthly allowance. It is not a regeneration. It waits for the
process-wide `AI_RATE_PER_MIN` bucket like every model call. On top, each
reader may ask at most `questions_per_minute` questions per minute
(`QUIRE_SERVER_AI_DISCUSS_RATE_PER_MIN`, default 10, counted per server
process). Each answered question writes one `ai_generation_log` row of
kind `discuss`, with the model, the prompt version, the latency and
nothing of the content.

#### Passages are material, not instructions

Highlights, notes and titles may contain anything, including text such as
"ignore previous instructions". The server sends them to the model as JSON
string values inside the user message. Quoting stops a passage from
breaking the structure of the message (it cannot close its string and
pose as a new field or turn), but it does not stop the model from reading
the words. That part is the system prompt's job: it says the passages are
material to reason about and never instructions, that answers must be
grounded in them, and that "I can't tell from these passages" beats
invention. `cited` is checked against the request whatever the model
returns.

#### Errors

Checked in this order: authentication, the body, whether AI is
configured, the provider, the opt-in, the per-minute rate, the allowance,
the model call.

| Status | Body | When |
|---|---|---|
| 401 | | Not authenticated. |
| 404 | | AI is off (`QUIRE_SERVER_AI_ENABLED=false`): `/ai/v1` is not mounted. |
| 413 | | The body is larger than `QUIRE_SERVER_MAX_REQUEST_BYTES` (default 1 MiB), refused before it is read. |
| 422 | `{"detail": {"code": ..., "field": ..., "limit": ...}}` | A value over a limit or a malformed conversation; see below. Nothing is counted. |
| 422 | `{"detail": [...]}` (FastAPI validation list) | The body is not JSON of the shape above: unparseable JSON (`type` `json_invalid`), a missing or mistyped field (an `n` must be a JSON integer, not `"1"` or `1.0`), an unknown field, a `role` other than `reader` or `assistant`, or a `provider` that is not a lowercase id. Each item has `type`, `loc` and `msg` only: the values sent are never echoed back. |
| 503 | `{"detail": "ai_disabled"}` | AI is on but no provider is configured (`configured` is false). |
| 422 | `{"detail": "ai_provider_unknown"}` | `provider` names no provider this server offers. |
| 409 | `{"detail": "ai_provider_unavailable"}` | The reader's chosen provider is no longer on this server. |
| 409 | `{"detail": "ai_not_opted_in"}` | The reader has not opted in to AI. |
| 429 | `{"detail": "discuss_rate_limited"}`, `Retry-After` | More than `questions_per_minute` questions in the last minute. `Retry-After` is a whole number of seconds, at least 1. Nothing is counted against the allowance. |
| 429 | `{"detail": {"used": 200, "limit": 200, "resets_at": "..."}}`, `Retry-After` | The daily allowance is spent ([quota model](#quota-model)). |
| 429 | `{"detail": "monthly_ai_quota_exhausted"}` | Hosted service only: the monthly allowance is spent. No `Retry-After`. |
| 503 | `{"detail": "ai_policy_unavailable"}` | Hosted service only: the allowance service cannot answer. |
| 502, 504 | [provider error](#ai-provider-errors) | The model call failed. `provider_invalid_output` also covers an empty answer. The question was counted. |

The limit 422 `detail` object:

- `code`: `"too_many"` (more items than allowed: `highlights`,
  `highlights[i].authors`, or reader turns in `messages`), `"too_long"`
  (one string over its limit), `"too_large"` (the 60,000-character total)
  or `"invalid"` (a problem that is not a size: no highlights, an `n` below
  1 or repeated, a blank `text`, a blank turn, turns that do not
  alternate starting with `reader`, or a conversation that is empty or
  ends with an `assistant` turn).
- `field`: where, as a path into the request: `"highlights"`,
  `"highlights[3].text"`, `"highlights[0].note"`, `"highlights[0].title"`,
  `"highlights[0].authors"`, `"highlights[0].authors[2]"`,
  `"highlights[1].n"`, `"messages"`, `"messages[4]"` (a turn in the wrong
  place or blank), `"messages[4].content"`, or `"request"` for the total.
  Indexes count from 0.
- `limit`: the number that was exceeded, or null for `"invalid"`.

The server reports the first problem it finds, checking the highlights in
order and then the turns in order.

Clients should check the limits before sending, using
`highlights_discuss_limits`, and count only `reader` turns against
`max_questions`. When a conversation reaches it, offer to start a new one.

### `GET /ai/v1/health`

Operational visibility for AI provider + retrieval reachability. **Unauthenticated**
by design — operators and the Android Settings status row poll it without going
through Basic auth (consistent with the always-on root `/health` and `/readyz`
probes; nothing in the body is more sensitive than `/ai/v1/config` already
exposes). Mounted only when `QUIRE_SERVER_AI_ENABLED=true`.

Snapshot semantics:

- **Process-local.** Each replica reports its own state. Reset to all-null on
  process restart.
- **Passive.** State updates only as a side effect of real user-driven
  chat-completion + retrieval calls. The server never actively pings providers.
- **Tri-state `reachable`.** `null` = never observed by this process;
  `true` = last call succeeded; `false` = last call failed.

```json
{
  "provider_reachable": true,
  "provider_last_checked_at": "2026-05-16T21:25:00+00:00",
  "model_id": "gpt-oss:120b-cloud",
  "last_failure_at": null,
  "last_failure_class": null,
  "retrieval_sources": [
    { "name": "wikipedia",   "reachable": true,  "last_checked_at": "2026-05-16T21:24:30+00:00" },
    { "name": "openlibrary", "reachable": null,  "last_checked_at": null }
  ]
}
```

`retrieval_sources` is seeded from `QUIRE_SERVER_AI_SOURCES` so the UI always
has a row per configured source even before the first call. `model_id` is the
most recently observed model on a successful chat completion (not necessarily
equal to `AI_MODEL`; see `/ai/v1/config` for the configured value). On
failure, `last_failure_at` and `last_failure_class` are set and cleared on
the next success.

## Book details and covers (`/details/v1`)

When a book has no cover, no description or a title that is really a file
name ("dune_frank_herbert_(retail).epub"), the server can fill in what is
missing and tidy what is messy. It runs in two steps:

1. **Find.** If the book lacks a cover, a description, subjects, a
   language, an ISBN or a year, the server looks it up on Open Library,
   with the same check the insights use that the result is this book: the
   title forms, the volume number, the format and, when an author is
   given and the match came from the title rather than the ISBN, that the
   result credits the same person (any name word of three letters or more,
   ignoring accents, word order, initials and words such as "ed." or
   "Various"). A title alone is never looked up. No match means nothing is
   filled, never a guess. This step makes no model call
   and costs nothing.
2. **Tidy.** One model call reads the book's own fields and the Open
   Library record and returns a cleaned title, authors, series and
   description, plus a cover design. The server then removes anything the
   model may not say: the answer has no field for a year, an ISBN, a
   publisher or a language, which only ever come from the client or Open
   Library; a title, author or series whose words are not whole words of
   the input is refused, and a title that was not a file name keeps the
   input's words in their order, losing only a series, the author's name
   or a volume word; a series number must appear in the input; and
   description sentences that state a publication detail, carry a web
   address or hold a number the sources do not are dropped. A tidy counts as one generation against the
   reader's allowance ([quota model](#quota-model)); serving a stored tidy
   is free.

The endpoints are mounted only when AI is on
(`QUIRE_SERVER_AI_ENABLED=true`) and `QUIRE_SERVER_DETAILS_ENABLED` is
true (the default); otherwise every path answers **404**. Clients check
`details_supported` in [`GET /ai/v1/config`](#get-aiv1config), or
`capabilities.book_details` in a browser session. They use the same
authentication as `/ai/v1` and require the reader's AI opt-in, because the
book's details reach the model provider.

### When a lookup tidies on its own

`POST /details/v1/lookup` spends a generation only when one of these holds
and no stored record exists for the same input:

- the title looks like a file name: underscores, an ebook extension, a
  release tag such as "(retail)", "[epub]" or "v5", words joined by dots or
  dashes, no capital letter at all, or the author's surname inside a title
  of several words;
- the book has no cover, from the client or from Open Library, so the tidy
  is needed to design one;
- Open Library matched but the client sent no authors, or neither the
  client nor Open Library has a description.

Otherwise the answer is the client's fields plus what the find added, with
`tidy: "not_needed"`. `POST /details/v1/tidy` always tidies, for a reader
who asks.

A stored record is served with the Open Library result found when it was
tidied, so serving it makes no Open Library request. When a tidy fails
for a reason other than the allowance, the server remembers the record key
for about 15 minutes (in each server process): lookups of that book answer
`tidy: "failed"` at once without calling the model again, while
`POST /details/v1/tidy` still tries.

### Sharing and keying

Records live in `book_details`, shared by every account like
`book_insights`: there is no user column, and a second reader with the same
book gets the stored record free. The key is a SHA-256 over the book's
canonical identity (`metadata_id:...` or `content_hash:...`, resolved from
alias hints as the insights do), every field of `book`, and the tidy's
prompt version. Because every input field is part of the key, a client that
sends false fields only writes a record that no client with the true fields
will be served. The model id is stored with the record but is not part of
the key. Each tidy, and each record served from the store, writes one
`ai_generation_log` row of kind `details` for cost attribution.

The same reader's devices often send different fields for one book (the
phone reads them from the file, the web from the catalog), which gives a
different key. So the server also keeps, per reader, a pointer from the
book's canonical identity to the record that reader was last given
(`book_details_reader`). A `/lookup` checks it first, before Open Library
or the model: when the pointed record exists, the answer is that record,
with `cached: true` and `tidy: "done"`, free, even though the fields sent
differ. Every lookup that serves or stores a record, and every `/tidy`,
moves the pointer to that record. The pointer belongs to one reader and
only ever picks among records made for their own lookups, so it cannot
change what any other reader is served. It is deleted with the account and
included in the account export.

### `POST /details/v1/lookup` and `POST /details/v1/tidy`

Body:

```json
{
  "identity": { "metadata_id": "urn:uuid:4b6f...", "content_hash": "abc..." },
  "book": {
    "title": "dune_frank_herbert_(retail)",
    "authors": ["Herbert, Frank"],
    "series_name": null,
    "series_index": null,
    "description": null,
    "subjects": [],
    "language": null,
    "isbn": "9780441013593",
    "year": null,
    "publisher": null
  },
  "source": "file",
  "cover": null
}
```

- `identity`: as for [`/ai/v1/insights/lookup`](#post-aiv1insightslookup).
- `book`: the details the client holds. Only `title` is required. Caps:
  `title <= 512`, `authors <= 16 x 256`, `series_name <= 256`,
  `series_index 0-10000`, `description <= 8192`, `subjects <= 64 x 80`,
  `language <= 32`, `isbn <= 64`, `year 0-9999`, `publisher <= 256`.
  Runs of whitespace collapse to one space and blank values count as
  missing. Unknown keys are rejected.
- `source`: where `book` came from, `"file"` (the book file) or
  `"catalog"` (its catalog entry). It is echoed as the origin of the
  fields the client sent.
- `cover`: `"file"` or `"catalog"` when the client already shows a real
  cover, else `null`. A generated cover never replaces a real one.

Response:

```json
{
  "key": "3f1c...64 hex characters",
  "book": {
    "title": "Dune",
    "authors": ["Frank Herbert"],
    "series_name": null,
    "series_index": null,
    "description": "...",
    "subjects": ["Science fiction", "Desert planets"],
    "language": null,
    "isbn": "9780441013593",
    "year": 1965,
    "publisher": null
  },
  "origins": {
    "title": "ai",
    "authors": "ai",
    "description": "openlibrary",
    "subjects": "openlibrary",
    "isbn": "file",
    "year": "openlibrary",
    "cover": "openlibrary"
  },
  "cover": {
    "source": "openlibrary",
    "url": "https://covers.openlibrary.org/b/id/12345-L.jpg",
    "generated": false
  },
  "tidy": "done",
  "cached": false,
  "openlibrary_url": "https://openlibrary.org/works/OL893415W"
}
```

- `book`: the merged details. The client's own values always win for
  facts; the tidy replaces only the title, authors, series and
  description; the find fills only fields the client left empty.
- `origins`: for every field that has a value, where it came from:
  `"file"` or `"catalog"` (echoing `source`), `"openlibrary"` or `"ai"`.
  For `description` there are two model origins: `"ai"` when the model
  cleaned a description the client or Open Library had (whenever the text
  differs from that source at all, since a cleaned text may still say
  something the source did not), and `"ai_written"` when neither had one
  and the model wrote it from the Open Library record, which happens only
  when Open Library matched.
  `cover` is `"file"`/`"catalog"` for the client's own, `"openlibrary"`,
  or `"ai"` for a generated cover.
- `cover`: the cover to show, in the order the client's own, Open
  Library's, then a generated one. `source` is `null` when there is none
  (no tidy ran). For a generated cover, `url` is a server path such as
  `/details/v1/covers/<key>?v=<etag>` and `generated` is true, so the
  client can label it. Two more flags, both false unless the server paints
  cover artwork ([below](#painted-cover-artwork)): `painted` when the
  cover is drawn on painted artwork, and `painting` when artwork for it is
  on its way, so a later lookup will answer with a new `url`.
- `tidy`: `"done"` when the answer includes a tidy, `"not_needed"` when
  nothing called for one, and on a lookup `"quota_exceeded"` or
  `"failed"` when one was due but the allowance was spent or the model
  call failed. A lookup never fails because of the tidy: it answers with
  what the find gave.
- `cached`: true when the tidy was served from the shared store, at no
  cost, including a record found through the reader's pointer.
- `openlibrary_url`: the matched Open Library work, for attribution;
  `null` when nothing matched.

Every text field is plain text: the server strips HTML tags, Markdown
links and emphasis, and web addresses from descriptions. Clients must still
render every field as plain text, never as HTML or Markdown, since a
record holds text written by other readers and by the model.

ISBN and publisher are never filled from Open Library, because a work has
many editions; `year` is the work's first publication year; `language`
is filled only from the edition of the book's own ISBN.

| Status | Body | When |
|---|---|---|
| 200 | the response above | Success. On `/lookup` also when the tidy could not run; see `tidy`. |
| 401 | | Not authenticated. |
| 404 | | The feature is off (`QUIRE_SERVER_DETAILS_ENABLED=false`) or AI is off. |
| 409 | `{"detail": "ai_not_opted_in"}` | The reader has not opted in to AI. |
| 409 | `{"detail": "ai_provider_unavailable"}` | The reader picked a provider this server no longer has. |
| 422 | | The body fails validation, or no canonical identity resolves. |
| 429 | `{"detail": "details_rate_limited"}`, `Retry-After` | The reader made more than `QUIRE_SERVER_DETAILS_LOOKUP_RATE_PER_MIN` lookups and tidies together in the last minute (counted per server process). Checked before Open Library or the model is reached, so it costs nothing. The `detail` code is stable. |
| 429 | quota body, `Retry-After` | `/tidy` only: the daily allowance or the regeneration limit is spent ([quota model](#quota-model)), or the hosted monthly allowance (`{"detail": "monthly_ai_quota_exhausted"}`). |
| 502, 504 | [provider error](#ai-provider-errors) | `/tidy` only: the model call failed. |
| 503 | `{"detail": "ai_disabled"}` | AI is on but no provider is configured. Also when the hosted allowance service cannot answer. |

Wherever a 429 here or on the cover endpoint below carries `Retry-After`,
it is a whole number of seconds, at least 1, never an HTTP date. The
rate-limit 429s always carry it; the hosted monthly allowance's does not. The rate-limit body is
exactly `{"detail": "details_rate_limited"}` (or `"cover_rate_limited"`),
so a client can tell it from the allowance's 429 by `detail` alone: wait
`Retry-After` seconds and send the same request again.

A forced tidy of a book that already has a stored record counts as a
regeneration (`AI_REGEN_DAILY_LIMIT`); the first tidy of a book counts as
a generation.

### `GET /details/v1/covers/{key}`

The designed cover of the record with this `key`, as a 600 x 900 PNG, or
a JPEG when it is drawn on painted artwork. It is drawn on request from
the stored design, title and authors (and artwork), so the same record
always gives the same image. Use the `url` from the lookup response: it
carries the current `ETag` as `?v=`, so a changed cover has a new address.
Once artwork is painted, both the drawn cover's address and the painted
one's are served, each with its own picture, so an address a client
already holds keeps working.

Response headers: `Content-Type: image/png` (or `image/jpeg`), `ETag`,
`Cache-Control: private, max-age=31536000, immutable` for an address with
`?v=` (`private, no-cache` without one), and `X-Quire-Cover: generated`, which marks the image as drawn by the server
rather than a real cover.

| Status | When |
|---|---|
| 200 | The PNG or JPEG. |
| 304 | `If-None-Match` matches the current `ETag`. |
| 401 | Not authenticated. |
| 404 | `{"detail": "cover_not_found"}`: no record has this key, or `?v=` names a cover since redrawn (look the book up again for the new address). Also when the feature or AI is off. |
| 409 | `{"detail": "ai_not_opted_in"}`: the reader has not opted in to AI. |
| 429 | `{"detail": "cover_rate_limited"}` with `Retry-After`: the reader fetched more than `QUIRE_SERVER_DETAILS_COVER_RATE_PER_MIN` covers in the last minute (counted per server process; a `304` does not count). |

### Painted cover artwork

Off by default (`QUIRE_SERVER_COVER_ART_ENABLED`, see
[configuration](configuration.md#quire_server_cover_art_enabled)). When an
operator turns it on, an image model paints a background for generated
covers: an illustration with no text, made from the record's title,
subjects and a trimmed description. The server still sets the title and
author itself, on a solid band over the lower part of the artwork, so the
type keeps the design's contrast whatever the painting looks like.

Painting never happens on a request. A lookup or tidy that answers with a
generated cover queues a painting and answers at once with the drawn cover
and `cover.painting: true`. When the artwork is stored, the next lookup of
that book answers with a new `url` (a new `?v=`) and `cover.painted: true`;
that lookup is served from the stored record, so it costs nothing. Each
book is painted once and the artwork is shared by every reader of the
record, like the record itself.

A painting that fails (the provider refused, timed out or answered with no
usable image) is not retried on every lookup: after a timeout or a busy or
unavailable provider a later lookup may try again a day later, otherwise
after 30 days. The server paints at most `QUIRE_SERVER_COVER_ART_DAILY_BUDGET`
covers a day in all. None of this is charged to the reader's allowance.

`painting: true` means "ask again later": look the book up again (an hour
is plenty), which is free because the record is stored, and fetch the new
`url` when it changed. A client that keeps the first cover it was given and
never looks the book up again keeps showing the drawn cover.

The artwork comes only as base64 image data in the provider's answer; the
server never fetches an image address, so a provider that answers with
one paints nothing.

## PDF conversion (`/convert/v1`)

A reader picks a PDF in the app; the server turns it into an EPUB and the app
downloads the book. The converting is done by a separate worker container
running `quire-convert` from `converter/` (Calibre, Poppler, EPUBCheck). It
uses no model and sends nothing off the server, so it works with AI switched
off. Setup is in [PDF conversion](configuration.md#pdf-conversion).

Who can call it: any login the server accepts (a Quire account session,
Bearer token or browser cookie, or a Calibre-Web Basic login). Cookie
sessions send `X-CSRF-Token` on `POST` and `PUT` as everywhere else, and
`X-Quire-Account` is checked on `/convert/v1/` like on `/library/v1/`.

The routes exist only while `QUIRE_SERVER_CONVERT_ENABLED` is on and the job
folder is usable; otherwise every one of them is `404 {"detail": "Not Found"}`.
Clients find out with any of:

- `GET /convert/v1/capabilities`: `200` when conversion is on (any login).
- `capabilities.pdf_conversion` in `GET /auth/v1/me` (browser sessions and
  Quire accounts). A Calibre-Web browser session (`/quire/v1/session`)
  carries no capabilities, so a client signed in that way asks
  `GET /convert/v1/capabilities`.
- `conversion_supported` in `GET /ai/v1/config`, when AI is on.

Rules for every route:

- Every response, errors included, is `Cache-Control: no-store`.
- Jobs belong to the account's storage scope. Another account's job answers
  `404 {"detail": "job_not_found"}`, exactly like one that does not exist.
- A `422` never repeats what was sent. Schema errors come back as
  `{"detail": [{"type", "loc", "msg"}]}`; rule errors as `{"detail": "<code>"}`.
- The server logs a job by id, state, codes and sizes, never a file name or
  any text from the book.
- Two kinds of `404`: `{"detail": "Not Found"}` means conversion is off on
  this server (or the path is wrong); `{"detail": "job_not_found"}` means
  this account has no such job (never created, another account's, or purged
  48 hours after creation).
- Text the worker supplies (`failure.detail`, `review.reason`, an issue's
  `code`, `message` and `page_label`, a page's `label`, `engine_revision`)
  comes out of converting an untrusted PDF. Show it as plain text, never as
  HTML or markup.

### The flow

1. `POST /convert/v1/jobs` with the PDF's SHA-256 and size: `201` and a job
   in state `created`.
2. `PUT /convert/v1/jobs/{id}/source` with the PDF as the body: `202`, state
   `queued`.
3. Poll `GET /convert/v1/jobs/{id}`, at most every 5 seconds (the server
   moves jobs along about that often; a conversion takes seconds to
   minutes), through `running` until a finished state.
4. `ready` or `review`: `GET /convert/v1/jobs/{id}/epub`, then
   `POST /convert/v1/jobs/{id}/acknowledge`. `failed`: show the reason, then
   acknowledge.

| State | Meaning | Next |
|---|---|---|
| `created` | Waiting for the PDF. After an hour without it the job expires. | `queued`, `cancelled`, `expired` |
| `queued` | The PDF is in and waits for the worker. | `running`, `failed`, `cancelled`, `expired` |
| `running` | The worker is converting it. | `ready`, `review`, `failed`, `queued` (worker restarted), `cancelled`, `expired` |
| `ready` | Converted and checked; the EPUB can be downloaded. | `acknowledged`, `expired` |
| `review` | Converted, but some pages need a look (`review` lists them). The EPUB can be downloaded. | `acknowledged`, `expired` |
| `failed` | Not converted; `failure.code` says why. | `acknowledged`, `expired` |
| `cancelled` | Stopped by the reader. | none |
| `acknowledged` | The reader took the result; both files are deleted. | none |
| `expired` | 24 hours passed since creation; both files are deleted. | none |

The PDF is deleted as soon as the job reaches any state after `running`. A
finished job keeps its EPUB until it is acknowledged, or for at most 24 hours
from creation (not from when it finished: `expires_at` says when). Jobs stay
listed for another day after that (so the daily limit can count them) and
then disappear.

**After an app restart.** Save the idempotency key before the `POST` and the
job id from its answer before starting the `PUT`. To pick up again, `POST`
the same key with the same values: the answer is the job in its current
state, whatever it is. If it is still `created`, upload again. A retried
`PUT` that answers `409 not_accepting_source` means the first upload arrived
and only its answer was lost: `GET` the job. `409 upload_in_progress` means
an earlier upload of the job is still arriving (or went quiet less than two
minutes ago): wait, then `GET` the job. If it is still `created` two minutes
later, that upload died: upload again.

**When no worker runs.** A queued job waits for a worker and expires 24 hours
after creation if none comes. Offer conversion only while `available` is
`true` in the capabilities, and show a queued job as waiting.

### Job object

Every job route answers with this shape:

```json
{
  "id": "0b7f8a8e-5f0c-4c1e-9f3a-2f9a1e0c6d11",
  "state": "review",
  "mode": "local",
  "attempt": 1,
  "created_at": "2026-11-25T10:00:00+00:00",
  "updated_at": "2026-11-25T10:01:12+00:00",
  "expires_at": "2026-11-26T10:00:00+00:00",
  "source": {"sha256": "<64 hex>", "bytes": 1843200},
  "engine_revision": "quire-convert 0.1.0; calibre 9.19.0; epubcheck 5.4.0",
  "result": {
    "sha256": "<64 hex>",
    "bytes": 912345,
    "rendition_id": "urn:uuid:5a0d...",
    "issue_counts": {"critical": 0, "major": 1, "minor": 2, "info": 0}
  },
  "review": {
    "reason": "1 page needs review",
    "affected_pages": [{"page": 12, "label": "x"}],
    "issues": [
      {"code": "...", "severity": "major", "page": 12, "page_label": "x", "message": "..."}
    ]
  },
  "failure": null
}
```

- `result` is set in `ready` and `review`, `null` otherwise. `sha256` and
  `bytes` describe exactly the bytes `/epub` returns.
- `result.rendition_id` is the book's first `dc:identifier`. It is derived
  from the PDF's SHA-256 and the converter's fixed settings, and the EPUB is
  packaged deterministically, so converting the same PDF again gives the
  same identifier (and, with the same `engine_revision`, the same bytes). A
  client that keys books by that identifier sees the same book, not a copy.
- `result.issue_counts` counts the converter's findings by severity, with the
  keys `critical`, `major`, `minor` and `info`.
- `review` is set only in `review`: the physical pages (`page`, 1-based) and
  printed labels (`label`, may be `null`) that kept the book from `ready`,
  and up to 100 of the issues that did, each with a `severity` of
  `critical` or `major`. A `review` book is usable: import it like a `ready`
  one and tell the reader which pages to check.
- `failure` is set only in `failed`:
  `{"code": "<code>", "retryable": false, "detail": "<short reason or null>"}`.
  `retryable: true` means the same PDF may convert on another try (start a
  new job with a new idempotency key); `false` means it will fail the same
  way. `worker_error`, `worker_lost` and `invalid_result` are faults on the
  server's side and always retryable; `conversion_failed` is when the
  converter says so; the other codes never are.
- `engine_revision` is the worker's tools when the job was created, replaced
  by the ones that actually converted it.
- `attempt` counts the server's tries: a worker that disappears mid-run gets
  the job taken back and run once more (attempt 2) before it fails
  `worker_lost`.
- `mode` is always `"local"` for now: converted on this server, nothing sent
  elsewhere. The field is reserved for other ways of converting.

| `failure.code` | Meaning |
|---|---|
| `encrypted` | The PDF is encrypted or password-protected (any encryption, even with no open password). |
| `too_many_pages` | More pages than `max_pages`. |
| `unreadable_pdf` | Poppler could not read it as a PDF. |
| `unsupported` | A kind of PDF the converter does not handle (`detail` says which). |
| `conversion_failed` | A conversion stage failed; `detail` names the stage. |
| `timed_out` | The conversion ran past `time_limit_s`. |
| `worker_error` | The worker stopped unexpectedly or could not read its job. |
| `worker_lost` | No worker finished the job, twice. |
| `invalid_result` | The worker's result did not check out (a forged or malformed report, a file that is not a regular file, an EPUB whose size, checksum or structure is wrong), or the server could not reconcile the job; nothing was kept. |

### `GET /convert/v1/capabilities`

```json
{
  "available": true,
  "engine_revision": "quire-convert 0.1.0; calibre 9.19.0; epubcheck 5.4.0",
  "accepted_media_types": ["application/pdf"],
  "max_bytes": 104857600,
  "max_pages": 1000,
  "time_limit_s": 1200,
  "max_active_jobs": 2,
  "daily_limit": 10,
  "retention_hours": 24,
  "modes": [{"id": "local", "sends_content_off_server": false}]
}
```

`available` is `false` while no worker has checked in during the last two
minutes; creating a job then answers `503 converter_unavailable`. A client
can check `max_bytes` before uploading; `max_pages` is checked by the worker
(`failed`, `too_many_pages`).

### `POST /convert/v1/jobs`

```json
{"idempotency_key": "b2f0c1d4-7c7e-4d0e-a8f2-1c9b0e3d2a10", "sha256": "<64 hex>", "bytes": 1843200, "mode": "local"}
```

- `idempotency_key`: 8 to 128 characters from `A-Z a-z 0-9 _ -`, chosen by
  the client, new for every PDF the reader picks. Sending the same key with
  the same `sha256`, `bytes` and `mode` again returns the existing job
  (`200`) in whatever state it is, finished, cancelled and expired included;
  with different values it is `409`. Once the job's row is purged (48 hours
  after creation) the key is free and starts a new job.
- `sha256`: lowercase hex SHA-256 of the exact bytes the upload will send.
- `bytes`: their count.
- `mode`: optional, `"local"` (the only value).

| Status | When |
|---|---|
| 201 | A new job, state `created`. |
| 200 | The same key with the same values: the existing job. |
| 403 | `{"detail": "hosting_subscription_required"}`: on a hosted server, the account's plan does not include writes (the same rule as sync writes). |
| 409 | `{"detail": "idempotency_conflict"}`: the key was used for another PDF. |
| 413 | `{"detail": "source_too_large"}`: `bytes` is over `max_bytes`. |
| 422 | Schema errors (bad key, checksum or mode). |
| 429 | `{"detail": "too_many_active_jobs"}` with `Retry-After: 60`: the account already has `max_active_jobs` jobs in `created`, `queued` or `running`. `{"detail": "daily_limit_reached"}` with `Retry-After` in seconds: the account started `daily_limit` jobs in the last 24 hours (cancelled and failed ones count). |
| 503 | `{"detail": "converter_unavailable"}` with `Retry-After: 60`: no worker is running. |

### `PUT /convert/v1/jobs/{id}/source`

The PDF as the raw body (not multipart), `Content-Type: application/pdf`.
`Content-Length` is optional: a chunked upload works too. This route alone
accepts bodies up to `max_bytes`; every other route keeps the global request
limit. The server stores the body, then checks that its size and SHA-256 are
the ones the job was created with and that it looks like a PDF (`%PDF-`
within the first KiB, `%%EOF` within the last). Only then is it queued.
One upload per job runs at a time, and an upload that sends nothing for 60
seconds is dropped.

| Status | When |
|---|---|
| 202 | Stored and queued: the job, state `queued`. |
| 408 | `{"detail": "upload_timed_out"}`: no bytes for 60 seconds. The job stays `created`; upload again. |
| 409 | `{"detail": "not_accepting_source"}`: the job is past `created` (the source already arrived, or it was cancelled or expired). |
| 409 | `{"detail": "upload_in_progress"}`: another upload of this job is under way, or went quiet less than two minutes ago. |
| 413 | `{"detail": "request body exceeds <max_bytes> bytes"}`: the body went over `max_bytes`, with or without `Content-Length`. The detail is free text, so match on the status. |
| 415 | `{"detail": "unsupported_media_type"}`: not `application/pdf`. |
| 422 | `{"detail": "size_mismatch"}` (more or fewer bytes than `bytes`, or a `Content-Length` that says so), `{"detail": "checksum_mismatch"}`, `{"detail": "not_a_pdf"}`. The job stays `created`, so the client can upload again. A client that will not upload again (the file is not a PDF, or is too large) should cancel the job: a `created` job counts toward `max_active_jobs` until it expires. |

### `GET /convert/v1/jobs` and `GET /convert/v1/jobs/{id}`

`{"jobs": [<job>, ...]}` with the caller's jobs newest first, every state
included; or one job. The single-job route is the one to poll.

### `GET /convert/v1/jobs/{id}/epub`

The EPUB, `Content-Type: application/epub+zip`, with `Content-Length`,
`ETag: "<result.sha256>"` and
`Content-Disposition: attachment; filename="<id>.epub"`. Repeat downloads
return identical bytes until the job is acknowledged or expires, so an
interrupted download can simply start over. A download already under way
finishes even if the job is acknowledged meanwhile. There are no `Range`
requests (every download sends the whole file), and the `ETag` is for
comparing only: `If-None-Match` never yields `304`.

The EPUB comes from converting an untrusted PDF. The server checks that it
is the file the worker reported and a plain EPUB container (`mimetype`
first, no entry name that leaves the book, bounded size), not what is
inside: open it the way the app opens any sideloaded book.

| Status | When |
|---|---|
| 200 | The EPUB. |
| 409 | `{"detail": "not_ready"}`: the job is not `ready` or `review`. |
| 410 | `{"detail": "result_gone"}`: acknowledged or expired. |

### `POST /convert/v1/jobs/{id}/cancel`

No body. Moves a `created`, `queued` or `running` job to `cancelled` and
deletes its PDF; a running conversion is stopped within seconds, and a
result it publishes after that is discarded. Cancelling a cancelled job
answers `200` again; a finished one is `409 {"detail": "not_cancellable"}`.

### `POST /convert/v1/jobs/{id}/acknowledge`

No body. Moves a `ready`, `review` or `failed` job to `acknowledged` and
deletes the EPUB; call it once the book is saved on the device. Answers `200`
with the job, also when it is already `acknowledged`, `cancelled` or
`expired`. A job still in `created`, `queued` or `running` is
`409 {"detail": "not_finished"}`.

### In account export and deletion

`GET /auth/v1/account/export` includes `convert_jobs`, one entry per job with
its checksums, sizes, states and summary (no PDF or EPUB). Purging an account
deletes its jobs. While conversion is on, the server clears their files on
its next pass; while it is off, they stay until it is on again or the
folder is deleted (see `QUIRE_SERVER_CONVERT_ENABLED`).
