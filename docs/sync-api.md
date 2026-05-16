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

### AI auth mode (PR-B, 2026-05-16)

`/sync/v1/*` and `/library/v1/*` always use the Basic auth flow above.
`/ai/v1/*` routes go through an `AiAuthenticator` seam that selects the
verifier per the `OPDS_SYNC_AI_AUTH_MODE` env var:

| Mode (`OPDS_SYNC_AI_AUTH_MODE`) | Header                            | Principal                              |
| ------------------------------- | --------------------------------- | -------------------------------------- |
| `basic` (default)               | `Authorization: Basic …`          | `tenant_id="local"`, `auth_mode=basic` |
| `token`                         | `Authorization: Bearer <jwt-ish>` | claims from token; `auth_mode=token`   |

Token mode is a stub for the hosted-AI future: HMAC-SHA256 over `header.payload`
with header `{alg=HS256, kid}` and payload claims `{iss, aud, exp, iat, sub,
tenant_id, scope?}`. Each segment is URL-safe base64 with no padding. The
server only verifies — issuance is out of scope. Multiple `kid → secret`
entries (set via the JSON env var `OPDS_SYNC_AI_TOKEN_SECRETS`) enable
rotation: tokens signed under any registered kid are accepted; mint with
the newest. `OPDS_SYNC_AI_TOKEN_ISSUER` and `OPDS_SYNC_AI_TOKEN_AUDIENCE`
must match `iss` / `aud` exactly. Verification failures all collapse to a
single `401 invalid credentials` — failure reasons live in structured logs
only.

Token-mode misconfiguration (missing secrets, secret shorter than 32 bytes,
missing issuer/audience) crashloops the process on startup. There is no
silent downgrade to basic.

`AiPrincipal.tenant_id` flows ONLY into `ai_generation_log` per-call audit;
it never participates in any shared-cache key. See `docs/architecture.md`
§"AI auth seam" for the rationale and `server/opds_sync/api/ai_auth.py` for
the implementation.

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
| `PUT` | `/library/v1/items` | yes | Upsert one library item (mode-gated on `OPDS_SYNC_PROGRESS_ENABLED`) |
| `GET` | `/library/v1/items` | yes | List items, optional `since=<ISO>` cursor with tombstones |
| `DELETE` | `/library/v1/items` | yes | Soft-delete one library item by `content_hash` |
| `GET` | `/ai/v1/health` | none | AI provider + retrieval reachability snapshot (mode-gated on `OPDS_SYNC_AI_ENABLED`) |

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

## Library items (`/library/v1`)

Per-user mirror of the on-device library. Mounted only when
`OPDS_SYNC_PROGRESS_ENABLED=true`. USER-SCOPED — `library_items.user_id` is
part of every uniqueness constraint and not shared cache.

Identity travels in the request body (URL-encoded sha256s in paths are a
footgun). Single-item-per-request today; a future bulk endpoint can ship
as `{"items": [...]}` without breaking clients.

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
    "opds_href": "/opds/book/42/download"
  }
}
```

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

**Identity fields (PR2 update, 2026-05-16).** `identity.content_hash` is now
**optional** so the catalog-preview flow can request insights before the EPUB
body is downloaded. The full set of accepted hints is:

| Field             | Scope          | Notes                                              |
| ----------------- | -------------- | -------------------------------------------------- |
| `metadata_id`     | canonical      | Normalized OPF `dc:identifier`.                    |
| `content_hash`    | canonical      | sha256 of the EPUB body. Optional since PR2.       |
| `opds_dc_id`      | user-scoped    | `dc:identifier` from the catalog entry.            |
| `isbn`            | global         | Same ISBN means the same book everywhere.          |
| `calibre_book_id` | user-scoped    | calibre-web book id; not portable across servers.  |
| `opds_href`       | user-scoped    | OPDS acquisition href; last-resort fallback.       |

At least one hint must be present. The server walks the hierarchy in the
order above and resolves to a canonical via the `insight_identity_aliases`
table. If no hint resolves on a write path the server returns `422`.
`reconcile_aliases` writes alias rows for every hint that is NOT already the
canonical, so subsequent calls with weaker hints hit the same cache row.

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

### `GET /ai/v1/health`

Operational visibility for AI provider + retrieval reachability. **Unauthenticated**
by design — operators and the Android Settings status row poll it without going
through Basic auth (consistent with the always-on root `/health` and `/readyz`
probes; nothing in the body is more sensitive than `/ai/v1/config` already
exposes). Mounted only when `OPDS_SYNC_AI_ENABLED=true`.

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

`retrieval_sources` is seeded from `OPDS_SYNC_AI_SOURCES` so the UI always
has a row per configured source even before the first call. `model_id` is the
most recently observed model on a successful chat completion (not necessarily
equal to `AI_MODEL`; see `/ai/v1/config` for the configured value). On
failure, `last_failure_at` and `last_failure_class` are set and cleared on
the next success.
