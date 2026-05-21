# Sync API

REST surface of the `quire-server` server. All endpoints are versioned under
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
| `PUT` | `/library/v1/items` | yes | Upsert one library item (mode-gated on `QUIRE_SERVER_PROGRESS_ENABLED`) |
| `GET` | `/library/v1/items` | yes | List items, optional `since=<ISO>` cursor with tombstones |
| `DELETE` | `/library/v1/items` | yes | Soft-delete one library item by `content_hash` |
| `GET` | `/library/v1/stats` | yes | Per-user library roll-up: totals + top authors + top themes |
| `GET` | `/ai/v1/health` | none | AI provider + retrieval reachability snapshot (mode-gated on `QUIRE_SERVER_AI_ENABLED`) |

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
`QUIRE_SERVER_PROGRESS_ENABLED=true`. USER-SCOPED — `library_items.user_id` is
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

- No `abandoned_count`: there is no explicit abandoned status yet.
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

## Health

```
GET /health
GET /readyz
```

Both endpoints mount on the root path (no `/sync/v1` prefix) and are always
available regardless of deploy mode. The previous `/sync/v1/healthz` was
removed in PR-A; cluster manifests must point at `/health` going forward.

- `/health` is liveness. Returns `{ "ready": true, "modes": ["progress","ai"] }`
  where `modes` reflects `QUIRE_SERVER_PROGRESS_ENABLED` and `QUIRE_SERVER_AI_ENABLED`.
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
  "progress_supported": true
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
`prompt_version`, `generated_at`. See `quire_server/api/ai_schemas.py` for
the full payload schema.

`payload` is the structured `BookInsightPayload` (schema v4 since PR-ε on
2026-05-19; old cached v3 and v2 rows remain valid). The model generates keys
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
  "discussion_prompts": ["Open-ended question 1?", "Open-ended question 2?"],
  "confidence": "high|medium|low",
  "schema_version": 4
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
`book_themes` until regenerated. The server pins `schema_version=4` after
model return so cache rows never reflect a model's accidental version
emission.

`theme_analysis` (PR-ε, schema v4) is a dict of up to **two** entries keyed
by theme name; each value is 2-4 sentences on how that theme manifests in
THIS specific book. The server REJECTS payloads with more than two keys via
a Pydantic `model_validator`. `craft_notes` is 3-5 sentences combining POV /
pacing / structure with prose qualities (null for ordinary-craft books or
nonfiction). `comparative_anchors` is a list of `{book, author, similar_in,
different_in?}` entries sanitized server-side (blank-field drop, cap at 4);
display-only — the server cannot verify the referenced books exist.
`distinctive_take` is 1-2 sentences differentiating the book from others in
its themes. `discussion_prompts` is 3-5 book-club-style questions (no plot
reveals past the inciting incident, per Lock #7 soft mitigation). All v4
fields are optional; old cached v3 rows (no v4 keys) deserialize cleanly.

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
| `502` | `{"detail":"..."}` | LLM call failed mid-flight. |
| `503` | `{"error":"profile_requires_progress_data"}` | `PROGRESS_ENABLED=false`. |

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
      "payload":  { ... BookInsightPayload at schema_version 4 ... },
      "sources":  [ ... Citations ... ],
      "model_id": "...",
      "prompt_version": "5",
      "schema_version": 4,
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
