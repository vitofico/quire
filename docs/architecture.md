# Architecture

This document describes how the Quire Android app, the `opds-sync` server, and
calibre-web fit together. For the REST surface in detail, see
[`sync-api.md`](sync-api.md).

## Components

| Component | What it does | Stack |
|---|---|---|
| **Quire** (Android) | Browses OPDS, downloads EPUBs, renders them, tracks progress, syncs. | Kotlin, Jetpack Compose, Readium Kotlin Toolkit, Room, WorkManager, OkHttp |
| **opds-sync** (server) | REST API for reading state. Single source of truth. | Python 3.12, FastAPI, SQLAlchemy 2 + Alembic, Postgres 16 |
| **calibre-web** | OPDS catalog and book downloads. Unchanged. | Existing self-hosted instance |

calibre-web is stateless from the reader's perspective — no reading state ever
lives there. (A planned read-only Calibre plugin will pull progress from
`opds-sync` into Calibre custom columns.)

## Module layout (Android)

```
:app           Compose UI, navigation, DI wiring, the Application class
:core:identity Document identity (hash, dc:identifier normalization)
:core:model    Domain types: Document, Progress, Bookmark
:data:local    Room database + DAOs; pending_sync_ops outbox; sync_state
:data:opds     calibre-web OPDS client
:data:sync     opds-sync REST client + WorkManager sync job
:reader        Readium navigator integration, font/theme controls
:auth          Keystore-backed calibre-web Basic credential store
```

The split is deliberate: `:core:identity` exists as its own module because the
identity rules are shared spec with the server (see below) — both sides have
unit tests against the same fixture set.

## Document identity

A document is identified by **two** independent keys; either resolves a record:

| Column | Meaning |
|---|---|
| `metadata_id` | Normalized first non-empty `dc:identifier` from the EPUB OPF. Nullable. |
| `content_hash` | KOReader-style sampled MD5. Always present. |

Both are indexed.

### Sampled hash

Matches KOReader's "binary" hash for potential interop. Fast (64 KB read total
regardless of file size); stable across filename changes and most EPUB metadata
edits. Does **not** survive Calibre re-encoding the file — that's what
`metadata_id` is for.

```
step  = max(1, filesize // 1024)
buf   = bytearray()
for i in 0..1023:
    seek(i * step)
    buf.extend(read(64))
content_hash = md5(buf).hexdigest()
```

### `metadata_id` normalization

Both client and server apply identical rules:

1. First `<dc:identifier>` whose trimmed value is non-empty.
2. Lowercase.
3. Strip leading `urn:` and any scheme prefix (`isbn:`, `uuid:`, `calibre:`,
   `mobi-asin:`, …). Keep only the bare value.
4. Remove all whitespace and hyphens.
5. Empty after normalization → treat as missing.

Examples:

| Input | Output |
|---|---|
| `urn:uuid:550E8400-E29B-41D4-A716-446655440000` | `550e8400e29b41d4a716446655440000` |
| `ISBN: 978-0-14-103614-4` | `9780141036144` |
| `calibre:42` | `42` |

Reference implementation: `core/identity` (Kotlin) and
`server/opds_sync/core/identity.py` (Python). Both are tested against the same
fixture set so they cannot drift.

### Lookup precedence

1. If `metadata_id` is present → look up by `metadata_id`. Match wins.
2. Else → look up by `content_hash`. Match wins.
3. Else → no match; create new record.

### Identity hierarchy

The two canonical columns above always identify a row. On the AI surface the
server additionally accepts a wider set of identity hints so pre-download
flows (catalog preview, library upload) work before the EPUB body is on the
device. PR2 (2026-05-16) materialized this as the `insight_identity_aliases`
table and the `resolve_identity` / `register_alias` / `reconcile_aliases`
API in `server/opds_sync/core/ai/identity.py`.

1. **`metadata_id`** — canonical. Normalized first non-empty `dc:identifier`
   from the EPUB OPF. Stable across re-downloads.
2. **`content_hash`** — canonical. KOReader-style sampled MD5 of the EPUB
   body. Stable across metadata edits.
3. **`opds_dc_id`** — alias. `dc:identifier` from the catalog entry.
   Pre-download.
4. **`isbn`** — alias. Global (a given ISBN means the same book everywhere).
5. **`calibre_book_id`** — alias. Pre-download. User-scoped (book #42 in one
   calibre-web is not book #42 in another).
6. **`opds_href`** — alias. Last-resort fallback. User-scoped.

Scope policy (`SCOPE_BY_SCHEME` in `core/ai/identity.py`): `metadata_id`,
`content_hash`, `isbn` are GLOBAL aliases (`user_id IS NULL` in the alias
row). `opds_href`, `opds_dc_id`, `calibre_book_id` are USER-SCOPED — the same
OPDS string can mean different books on different calibre-web instances and
must not cross-contaminate.

`InsightLookupBody.identity.content_hash` is now optional (PR2) so catalog-
preview requests can resolve via any alias hint. The orchestrator's
`_resolve_canonical` step walks the hierarchy and raises 422 if no hint
resolves on a write path. Title+author hashes are explicitly NOT identity —
they collide on common titles.

### Reconciliation

Two distinct surfaces today:

- **Sync (`/sync/v1/documents/alias`, planned).** When a `progress` row was
  first written by hash alone and the client later learns the metadata-id,
  the client calls this endpoint once per document. In one transaction the
  server merges any pre-existing metadata-id-keyed and hash-keyed records:
  record-level LWW for scalars, set union + tombstone resolution for
  bookmarks.
- **AI (`insight_identity_aliases`, shipped PR2 2026-05-16).** The AI
  surface accepts any of `metadata_id`, `content_hash`, `opds_dc_id`,
  `isbn`, `calibre_book_id`, `opds_href` and resolves to a canonical via
  the alias table before touching the shared cache. See
  `server/opds_sync/core/ai/identity.py` for the resolver + register +
  reconcile API. Per-user OPDS aliases stay user-scoped; global hints like
  ISBN are shared.

### Known limitations

- **`calibre:N` collisions across libraries.** v1 assumes one calibre-web; book
  #42 in two different Calibre instances would alias to one record. Mitigation
  if it ever matters: prefix `metadata_id` with a library scope.
- **Re-anchoring after EPUB republish.** Different EPUB copies can produce
  shifted CFIs. Progress is approximate by nature; bookmarks have a snippet
  fallback (see below).
- **Reused `dc:identifier`s in pirated EPUBs.** Different books with identical
  identifiers will alias. Workaround: edit the OPF in Calibre.

## Sync model

### Progress (current)

Record-level last-writer-wins on `updated_at`. Each device pushes its current
locator/percent and pulls deltas since its high-water mark.

The client maintains:

- `pending_sync_ops` — outbox of writes that haven't reached the server.
- `sync_state` — per-table `last_pulled_at` high-water marks.

A WorkManager job drains the outbox (push), pulls deltas (pull), updates
high-water marks. Triggered on app foreground, network reconnect, and
pull-to-refresh.

### Bookmarks (designed, not built)

```
id              uuid          -- client-generated
user_id         text          -- Lowercased calibre-web username
document_pk     bigint        -- FK to documents
cfi             text          -- bookmark location
text_snippet    text          -- ≤512 chars, for re-anchoring fallback
created_at      timestamptz   -- server-assigned, immutable
updated_at      timestamptz   -- server-assigned on every write
deleted_at      timestamptz   -- tombstone marker
```

Conflict resolution: record-level LWW on `updated_at`, same as progress.
Bookmarks are immutable in shape (a location + a snapshot of the
surrounding text), so per-field LWW would be overkill.

**Tombstones.** Delete is `deleted_at = now()`; row remains. Sync filters
`WHERE deleted_at IS NULL` unless the client opts in to tombstones (it does, so
deletes propagate). Nightly GC purges rows older than 90 days. Clients offline
more than 90 days will resurrect deleted bookmarks on next sync. Documented
and accepted.

**Anchoring fallback.** When the client renders a bookmark:

1. Resolve CFI; read surrounding text.
2. If surrounding text doesn't contain `text_snippet` (case-insensitive,
   whitespace-normalized, ~32-char prefix), fall to step 3.
3. Search the spine item for `text_snippet`. If exactly one match, use it; the
   server's CFI stays authoritative for other clients.
4. If not found or ambiguous, mark the bookmark **orphaned** in a sidebar.
   User can manually re-anchor or delete.

This handles "EPUB republished with shifted CFIs" without silent data loss.

## Authentication

One credential, one mental model. The user gives the Android app their
calibre-web username and password; everything else flows from that.

### calibre-web (OPDS) — HTTP Basic

- A dedicated `android-reader` user, not the admin account.
- Username + password stored in **Android Keystore** (hardware-backed where
  available) by `:auth` (`CalibreCredentialStore`).
- Every OPDS request sends `Authorization: Basic ...`.

### opds-sync — Basic auth proxied to calibre-web

- The Android app sends the **same** Basic header it uses for OPDS.
- The server has no user database. On each request it forwards the header
  to calibre-web's `/opds` endpoint and treats `200` as authenticated,
  `401` as not.
- Results are TTL-cached: 60 s positive, 10 s negative, LRU-bounded to
  1024 entries (configurable via `OPDS_SYNC_AUTH_CACHE_*`).
- `user_id` on every persisted row is the lowercased calibre-web username
  (extracted from the decoded Basic header). **Multi-user from day one.**
- Reference: `server/opds_sync/core/auth.py` (`CalibreAuthValidator`).

### Why this shape

- No external IdP to deploy, configure, or maintain.
- The user already has calibre-web credentials; nothing new to manage.
- The sync server is stateless w.r.t. identity — no password storage,
  no session state, no token rotation.
- Failure mode: if calibre-web is unreachable, the sync server returns
  `503` on auth-required endpoints. Documented and accepted.

### Credential handling on Android

- Basic credentials live in Keystore; never on disk in plaintext.
- On `401` the app prompts re-auth (no refresh token to rotate).
- Logout clears the Keystore entry.

## Deploy modes

The opds-sync server supports three deploy modes from a single codebase and
container image. Modes are controlled by two env-var flags:

| Mode             | `OPDS_SYNC_PROGRESS_ENABLED` | `OPDS_SYNC_AI_ENABLED` | Mounted endpoints                                                          |
| ---------------- | ---------------------------- | ---------------------- | -------------------------------------------------------------------------- |
| Full stack       | `true` (default)             | `true` (default)       | `/health`, `/readyz`, `/sync/v1/*`, `/library/v1/*`, `/ai/v1/*`            |
| Sync only        | `true`                       | `false`                | `/health`, `/readyz`, `/sync/v1/*`, `/library/v1/*`                        |
| AI only          | `false`                      | `true`                 | `/health`, `/readyz`, `/ai/v1/*`                                            |

`/health` and `/readyz` are always mounted on the root path (no prefix) so k8s
liveness/readiness probes work in every mode. `/health` returns liveness plus
the active modes list. `/readyz` performs DB connectivity + an alembic-head
check that verifies all required migration heads (per the enabled modes and
the currently-materialized branches) are applied.

The container entrypoint runs `python /app/scripts/migrate.py`, which:

- Always upgrades the unlabeled migration backbone (today: revision `0004`).
- Per enabled+materialized branch (`core`, `progress`, `ai`), runs
  `alembic upgrade <branch>@head`. Branches that don't exist yet are skipped
  silently.

Migrations split into three forward-only branches off the `0001..0004` linear
backbone. See `server/migrations/README.md` for the branch-label convention
and the splice rule for adding the first migration on a new branch.

### Provider lazy-import boundary

Modules importing AI provider clients (httpx-based today; the `openai` SDK
when it lands) and Wikipedia/OpenLibrary retrieval HTTP clients load lazily
inside `create_app()` under the AI-enabled guard. Sync-only deploys never pull
those modules. The test suite enforces this via subprocess introspection of
`sys.modules`.

### Request middleware

Every inbound HTTP request passes through (innermost first):

1. `RequestSizeMiddleware` — rejects bodies larger than
   `OPDS_SYNC_MAX_REQUEST_BYTES` (default 1 MiB) with HTTP 413.
2. `RequestIDMiddleware` — reads or generates `X-Request-ID`, binds it to a
   `contextvars` ContextVar so logs include it, and echoes it back on every
   response (including 413 / 4xx / 5xx).

### AI cache integrity

`book_insights` and `external_source_cache` are a **shared cache**: one row
serves every tenant who requests the same identity + model + prompt_version +
tone + language. The cross-tenant cache-hit property is load-bearing for
hosted Quire Cloud AI economics, so those tables MUST NOT carry `user_id`,
`tenant_id`, or any other principal column read for cache decisions.

Per-call audit and (future) billing attribution live in **`ai_generation_log`**
— one row per `lookup` / `generate` / `regenerate` call with
`book_insight_id` (FK, cascade), `tenant_id`, `subject`, `request_id`,
`model_id`, `prompt_version`, `latency_ms`, `status` (`hit` / `miss` /
`error`), `error_class`, `created_at`. Indexed by `(tenant_id, created_at)`
for future billing rollups and by `book_insight_id` for the audit UI.

`book_insights.generated_by` is a grandfathered NOT NULL column from before
this invariant existed. PR-C stopped reading it; it remains write-only legacy
until a follow-up migration nulls and drops it. A regression test
(`tests/integration/test_cache_key_audit.py`) asserts no new tenant column
sneaks onto the shared-cache tables — keep it green.

PR2 (2026-05-16) split that audit into two parametrize lists:

- **`SHARED_CACHE_TABLES`** (`book_insights`, `external_source_cache`): rows
  reused across every tenant requesting the same identity + model + prompt +
  tone + language. MUST NOT carry `user_id`, `tenant_id`, `subject`, or
  `principal_id`.
- **`SCOPED_ALIAS_TABLES`** (`insight_identity_aliases`): rows whose
  `user_id` is INTENTIONAL cache-key scoping, NOT a tenant-leak. An inverse-
  property test asserts `user_id` IS present on these tables, so a future
  refactor that removes the scoping fails loudly. Tenant columns
  (`tenant_id`, `subject`, `principal_id`) remain forbidden.

### AI auth seam (PR-B, 2026-05-16)

`/ai/v1/*` routes depend on `AiPrincipal{subject, tenant_id, scopes,
auth_mode, request_id}` via an `AiAuthenticator` Protocol, not on
`current_user_id` directly. Two implementations ship today:

- **`BasicAuthAiAuthenticator`** — wraps the existing calibre-web Basic-auth
  verifier. `tenant_id` is always `"local"`. Default.
- **`TokenAiAuthenticator`** — HMAC-SHA256 bearer-token verifier with `kid`
  rotation. Wire format: header `{alg=HS256, kid}` + payload
  `{iss, aud, exp, iat, sub, tenant_id, scope?}`, each segment URL-safe
  base64 with no padding. Issuance is out of scope; the server only
  verifies. Token-mode misconfiguration (missing `OPDS_SYNC_AI_TOKEN_SECRETS`,
  short secret, missing issuer/audience) crashloops the process — never
  silently downgrades to basic.

`AiPrincipal.tenant_id` flows ONLY into `ai_generation_log` for per-call
audit. It MUST NOT participate in any shared-cache key. Sync routes
(`/sync/v1/*`, `/library/v1/*`) continue to depend on `current_user_id`
directly — the seam only swings on `/ai/v1/*`.

Env vars: `OPDS_SYNC_AI_AUTH_MODE` (`basic|token`, default `basic`),
`OPDS_SYNC_AI_TOKEN_SECRETS` (JSON object `{kid: secret}`),
`OPDS_SYNC_AI_TOKEN_ISSUER`, `OPDS_SYNC_AI_TOKEN_AUDIENCE`. See
`server/opds_sync/api/ai_auth.py` for the verifier and
`server/README.md` for rotation guidance.

### AI provider health (PR5, 2026-05-16)

`GET /ai/v1/health` returns a process-local snapshot of the most recently
observed reachability of the AI provider and configured retrieval sources.
Unauthenticated by design — operators and the Android Settings screen poll
it without going through Basic auth (consistent with the always-on root
`/health` and `/readyz` probes; nothing in the body is more sensitive than
`/ai/v1/config` already exposes).

Mounted only when `OPDS_SYNC_AI_ENABLED=true`. State is held in
`AiHealthState` (`server/opds_sync/core/ai/health_state.py`), updated as a
side effect of real user-driven chat-completion and retrieval calls. We
never actively ping providers. Reachability is tri-state (`None` until first
observation, then `True`/`False`); reset to all-null on process restart.
Multi-replica deployments report per-replica state — multi-replica
observability is out of scope (in-process state stays).

### Per-user library mirror (PR1, 2026-05-16)

`/library/v1/items` (PUT / GET / DELETE) mounts when
`OPDS_SYNC_PROGRESS_ENABLED=true`. The `library_items` table (on the
`progress` alembic branch) is the server-side per-user mirror of every book
on the device, populated by Android's `DocumentRepository` after every
successful download via the existing sync retry queue. Identity travels in
the request body. Soft-deletes via `deleted_at`; `GET ?since=<ISO>` returns
delta rows including tombstones for sync. The table is USER-SCOPED — not
shared cache — so the cache-key audit does not cover it.

### Structured themes + `book_themes` (PR3, 2026-05-17)

`BookInsightPayload.themes` is back as a first-class field at schema v3 —
a list of 1–5 topic tags drawn from a controlled vocabulary (~57 entries in
`opds_sync/core/ai/themes.py::CONTROLLED_THEMES`, covering broad fiction
buckets, speculative subgenres, genre fiction, and nonfiction categories).
`PROMPT_VERSION` bumped to `"4"`. Vocab hits are normalized to snake_case
and persisted to the side table `book_themes(book_insight_id, theme,
confidence)` at `confidence=1.0`; off-vocab strings are preserved verbatim
at `confidence=0.5` so future vocabulary evolution doesn't lose data.

`book_themes` lives on the `ai` alembic branch (`ai_004_themes`) and joins
**`SHARED_CACHE_TABLES`** in the cache-key audit — no `user_id`/`tenant_id`
columns. Per-user filtering happens at query time by joining
`book_themes → book_insights → library_items` on
`metadata_id`/`content_hash`. PR9 library stats (future) MUST also filter
`book_insights.superseded_at IS NULL` to avoid double-counting regenerated
insights, and `confidence >= 1.0` if it wants vocab-only aggregation.

The payload's `themes` field is the source of truth for the client;
`book_themes` is the SQL-queryable mirror. Old cached v2 payloads (no
`themes` key) deserialize cleanly with `themes=null` and contribute zero
rows to `book_themes` until regenerated. `schema_version=3` is pinned by
the server after model return so cache rows never reflect a model's
accidental version emission.

### Android UI surfaces (batch 3, 2026-05-17)

- **Inspect insight screen (PR6).** Book-detail overflow → "Inspect
  insight" opens `InsightAuditScreen` (route `book/{id}/inspect-insight`,
  `InsightAuditViewModel`). Shows `model_id`, `prompt_version`,
  `schema_version`, `tone`, `language`, `generated_at`, sources list with
  tappable URLs, and an Invalidate button that reuses the existing
  body-based `/ai/v1/insights/invalidate` endpoint. No new server endpoint.
- **Series continuity shelf (PR8).** Horizontal shelf "Continue your
  series" on the library home, populated by a Room CTE join on
  `documents.series_name` against `progress`. Pure deterministic — no AI
  call. Room schema 4→5 adds `seriesName`/`seriesIndex` columns and a
  `(seriesName, seriesIndex)` index; opportunistic OPF series extraction
  is wired into the catalog download path (`CatalogViewModel`).
- **Regenerate dropped (PR11).** The book-detail overflow no longer
  exposes "Regenerate insights" — the cache key already invalidates
  naturally on `tone`/`language`/`model_id`/`prompt_version` changes, and
  the "ask again" case is now served by the Inspect-insight Invalidate
  button (one AI call instead of two). The
  `POST /ai/v1/insights/regenerate` server endpoint is retained for
  admin/cluster tooling.

## Decision log

| # | Decision | Rationale |
|---|---|---|
| 1 | Composite document identity (metadata-id + content-hash) | Survives both Calibre re-encodes and sideloaded files without metadata. |
| 2 | Server-side merge on alias | Keeps the system tidy long-term; one transaction. |
| 3 | 90-day tombstone GC | Bounded storage; documented edge case for long-offline clients. |
| 4 | CFI + text snippet anchoring | Survives EPUB republishing without silent data loss. |
| 5 | One credential, sync server proxies Basic auth to calibre-web | No second IdP to deploy; no token state on the server; the user already has the credential. Replaced an earlier OIDC/Authentik design. |
| 6 | Python FastAPI for the sync server | Fastest to write; traffic is trivial; deploys cleanly into the existing cluster. |
| 7 | Bookmarks are the only synced reading artifact beyond progress | Smallest scope that's actually useful; record-level LWW is sufficient; matches the author's actual reading workflow. |
| 8 | Calibre plugin as a later read-only consumer | Validates API shape; non-blocking; clean separation. |
| 9 | Single image, three deploy modes (full / sync-only / AI-only) gated by env-var flags | One codebase, one container; lets the future hosted Quire Cloud AI ship the same image. Migrations branch per-domain so each mode applies only what it needs. |
