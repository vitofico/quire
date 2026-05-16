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
| `GET` | `/health` | none | Liveness probe; returns `{ready, modes}` (always mounted) |
| `GET` | `/readyz` | none | Readiness probe; checks Postgres and that enabled-branch migrations are applied (always mounted) |
| `POST` | `/sync/v1/progress` | yes | Push progress for one or more documents |
| `GET` | `/sync/v1/progress` | yes | Pull progress deltas |
| `POST` | `/sync/v1/documents/alias` | yes | Reconcile a hash-keyed record with a newly-known metadata-id (planned) |
| `POST` | `/sync/v1/bookmarks` | yes | Push bookmark create/delete (planned) |
| `GET` | `/sync/v1/bookmarks` | yes | Pull bookmark deltas (planned) |

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
3. If both exist and differ, merges: record-level LWW for scalars, set union
   with tombstone resolution for bookmarks.
4. Deletes the orphan, returns the surviving record's identity.

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

## Health

```
GET /health
GET /readyz
```

Both endpoints mount on the root path (no `/sync/v1` prefix) and are always
available regardless of deploy mode. The previous `/sync/v1/healthz` was
removed in PR-A; cluster manifests must point at `/health` going forward.

- `/health` is liveness. Returns `{ "ready": true, "modes": ["progress","ai"] }`
  where `modes` reflects `OPDS_SYNC_PROGRESS_ENABLED` and `OPDS_SYNC_AI_ENABLED`.
  Returns 200 unless the process is broken.
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
| 503 | Database unavailable (`/readyz`) or calibre-web unreachable for auth probes. |

## AI endpoints (`/ai/v1`)

Optional. Returns 503 when the server is not configured for AI
(`OPDS_SYNC_AI_ENABLED=false` or missing `AI_BASE_URL`/`AI_MODEL`).

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

### `GET /ai/v1/config`

Returns the user-visible AI configuration. Public to authed users.

```json
{
  "configured": true,
  "base_url_host": "ollama.example.lan",
  "model_id": "llama3.1:8b",
  "sources_enabled": ["wikipedia", "openlibrary"],
  "daily_budget": 200,
  "regen_daily_limit": 3
}
```

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
header if the user's daily budget is exhausted. Body:

```json
{
  "identity": { "metadata_id": "9780553293357", "content_hash": "abc..." },
  "bundle":   { "title": "Foundation", "author": "Isaac Asimov", "...": "..." }
}
```

Response: a `BookInsight` with `payload`, `sources`, `model_id`,
`prompt_version`, `generated_at`. See `opds_sync/api/ai_schemas.py` for
the full payload schema.

`payload` is the structured `BookInsightPayload` (schema v2, the model
generates keys in this order):

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
  "confidence": "high|medium|low",
  "schema_version": 2
}
```

`content_warnings` is scoped to concrete reader-safety concerns
(violence, sexual content, abuse, self-harm, slurs, addiction, body horror) —
**not** themes, genre, politics, or plot mechanics.

### `POST /ai/v1/insights/regenerate`

Force a fresh generation. The existing live row is marked `superseded_at` (kept
for audit; queries return the new live row only). The new row records the
previous row's id in `previous_insight_ids`.

Requires opt-in. Subject to a tighter daily ceiling
(`AI_REGEN_DAILY_LIMIT`, default 3 per user). Returns 429 with the same body
shape as `lookup` when exceeded.

Body adds a required `reason`:

```json
{
  "identity": { "metadata_id": "...", "content_hash": "..." },
  "bundle":   { "title": "...", "...": "..." },
  "reason":   "Author bio claimed they were French; Asimov was Russian-American."
}
```

The reason is included in the prompt sent to the model so it knows what to fix.

### `POST /ai/v1/insights/get`

Cache-only read. 404 on miss. Does NOT require opt-in (cached results are
shared across users and remain readable by users who later opt out).

### `POST /ai/v1/insights/invalidate`

Drops the cached row for the current `(model_id, prompt_version)`. Use this
for admin-style cache busting; users hit `regenerate` instead, which is
budgeted. Requires opt-in. Returns `{"deleted": <int>}`.
