# Sync API

REST surface of the `opds-sync` server. All endpoints are versioned under
`/sync/v1`, all request and response bodies are JSON, and all sync endpoints
require an HTTP Basic header valid against the upstream calibre-web instance.

For the rationale behind the conflict-resolution model, see
[`architecture.md`](architecture.md).

## Authentication

```
Authorization: Basic <base64(username:password)>
```

The same calibre-web Basic credentials the Android app uses for OPDS
browsing. The server validates each header by probing
`{OPDS_SYNC_CWA_BASE_URL}{OPDS_SYNC_CWA_PROBE_PATH}` (default `/opds`)
with the incoming `Authorization` header and treats `200` as
authenticated, `401` as not. Results are TTL-cached (60 s positive,
10 s negative).

The `user_id` recorded on every persisted row is the lowercased
calibre-web username extracted from the Basic header. The system is
multi-user from day one.

A failed lookup returns `401`. If calibre-web is unreachable the server
returns `503`.

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/healthz` | none | Liveness probe |
| `GET` | `/readyz` | none | Readiness probe (checks Postgres) |
| `POST` | `/sync/v1/progress` | yes | Push progress for one or more documents |
| `GET` | `/sync/v1/progress` | yes | Pull progress deltas |
| `POST` | `/sync/v1/documents/alias` | yes | Reconcile a hash-keyed record with a newly-known metadata-id (planned) |
| `POST` | `/sync/v1/annotations` | yes | Push annotation create/update/delete (Phase 3+) |
| `GET` | `/sync/v1/annotations` | yes | Pull annotation deltas (Phase 3+) |

Currently shipped: health probes and progress. The rest are designed; see
phasing in the project README.

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
record-level last-writer-wins on the client-provided `updated_at`.

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
      "updated_at": "2026-05-07T09:14:32Z"
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
      "server_updated_at": "2026-05-07T09:14:32Z"
    }
  ]
}
```

`status` values:

- `accepted` — the incoming row was newer; server stored it.
- `rejected_stale` — server already had a newer row; nothing changed.
  `server_updated_at` reflects the server's authoritative state.
- `merged` — server reconciled identities (e.g. a hash-only row was rolled
  into a metadata-id row).

### `GET /sync/v1/progress`

Pull progress rows updated after the client's high-water mark.

```http
GET /sync/v1/progress?since=2026-05-07T09:14:32Z[&document=...]
Authorization: Basic ...
```

- `since` — ISO 8601, required. Server returns rows with `updated_at > since`.
- `document` — optional. Either a metadata-id or a content-hash; the server
  resolves it the same way as a push reference. Omit to pull everything for
  the user.

Response:

```json
{
  "items": [
    {
      "document": { "metadata_id": "9780141036144", "content_hash": "8e3a..." },
      "locator": "epubcfi(/6/4!/4/2/2[ch01]/2/1:0)",
      "percent": 0.382,
      "updated_at": "2026-05-07T09:14:32Z"
    }
  ],
  "high_water_mark": "2026-05-07T09:14:32Z"
}
```

The client persists `high_water_mark` and uses it as `since` next time.

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
3. If both exist and differ, merges: per-field LWW for scalars, set union with
   tombstone resolution for annotations.
4. Deletes the orphan, returns the surviving record's identity.

## Annotations (Phase 3+)

### `POST /sync/v1/annotations`

```json
{
  "annotations": [
    {
      "id": "8b2a3f10-...",
      "document": { "metadata_id": "...", "content_hash": "..." },
      "kind": "highlight",
      "cfi_start": "epubcfi(/6/4!/...)",
      "cfi_end":   "epubcfi(/6/4!/...)",
      "text_snippet": "...",
      "body": null,
      "color": "yellow",
      "field_versions": {
        "cfi_start":    "2026-04-26T10:15:00Z",
        "cfi_end":      "2026-04-26T10:15:00Z",
        "text_snippet": "2026-04-26T10:15:00Z",
        "body":         "2026-04-26T11:42:00Z",
        "color":        "2026-04-26T12:01:00Z",
        "deleted_at":   null
      },
      "deleted": false
    }
  ]
}
```

IDs are client-generated UUIDs. The server never reassigns them. This lets
the client create annotations offline and reference them locally before first
sync.

Response per annotation:

```json
{
  "id": "8b2a3f10-...",
  "status": "accepted" | "rejected_stale" | "merged",
  "field_versions": { "...": "..." }
}
```

`field_versions` in the response is the server's authoritative state after
the merge. `rejected_stale` means every incoming field had an older timestamp
than the stored one; the client should update its local cache from the
returned `field_versions`.

Conflict resolution is per-field LWW (see
[`architecture.md`](architecture.md#sync-model)).

### `GET /sync/v1/annotations`

```
GET /sync/v1/annotations?since=<ISO8601>[&document=<id>]
```

Returns rows where `updated_at > since`, **including tombstones**
(`deleted: true`), so deletes propagate.

## Health

```
GET /healthz
GET /readyz
```

`/healthz` is liveness; it returns 200 unless the process is broken.
`/readyz` opens a Postgres connection and returns 200 only if the database is
reachable.

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
| 503 | Database unavailable (`/readyz`) or calibre-web unreachable for auth probes. |
