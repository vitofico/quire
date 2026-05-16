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

### Reconciliation

When a record was first written by hash alone and the client later learns the
metadata-id, the client calls `POST /sync/v1/documents/alias` once per
document. In one transaction the server merges any pre-existing
metadata-id-keyed and hash-keyed records: record-level LWW for scalars, set
union + tombstone resolution for bookmarks.

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

| Mode             | `OPDS_SYNC_PROGRESS_ENABLED` | `OPDS_SYNC_AI_ENABLED` | Mounted endpoints                                |
| ---------------- | ---------------------------- | ---------------------- | ------------------------------------------------ |
| Full stack       | `true` (default)             | `true` (default)       | `/health`, `/readyz`, `/sync/v1/*`, `/ai/v1/*`   |
| Sync only        | `true`                       | `false`                | `/health`, `/readyz`, `/sync/v1/*`               |
| AI only          | `false`                      | `true`                 | `/health`, `/readyz`, `/ai/v1/*`                 |

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
