# OPDS eReader — Design Spec

**Date:** 2026-04-26
**Status:** Draft, pending review
**Scope:** Full system spec, phased build

---

## 1. Goals

Build a native Android ebook reader that:

- Browses and downloads books from a self-hosted calibre-web instance over OPDS.
- Reads EPUBs via Readium with a modern Material UI.
- Syncs reading progress and annotations across devices through a self-hosted sync server.
- Eventually pushes reading state back into Calibre's `metadata.db` via an optional plugin.

The sync server is the **single source of truth for reading state**. calibre-web remains stateless from the reader's perspective — it serves books, nothing more.

## 2. Non-goals

- Multi-library Calibre support (one calibre-web instance assumed).
- Server-side full-text search.
- Reading-stats analytics or social features.
- Pushing state from the sync server back into Calibre's database in v1 (deferred to Phase 6 as a separate plugin).
- iOS or desktop clients in v1 (the API is shaped to allow them later).

## 3. Components & data flow

Three components:

1. **`opds-reader`** — Android app, Kotlin, Readium-based. Browses calibre-web OPDS, downloads EPUBs to local storage, renders them, tracks progress and annotations locally, syncs to the sync server.
2. **`opds-sync`** — Python FastAPI service deployed to the existing K3s cluster (Kustomize manifests under `applications/opds-sync/`). REST API behind Authentik. Postgres for storage. Stateless application tier.
3. **calibre-web** — existing, unchanged. Source of OPDS catalog and book downloads. Authenticated via HTTP Basic.

```
[calibre-web]  ──OPDS + HTTP Basic──>  [Android: Readium]
                                              │
                                              │  HTTPS + JWT (Authentik)
                                              ▼
                                       [opds-sync]
                                              │
                                              ▼
                                        [Postgres]
```

## 4. Phasing

Each phase is independently shippable with an explicit ship gate.

| Phase | Scope | Ship gate |
|---|---|---|
| **1 — Local reader** | OPDS browse + download, EPUB rendering, local progress in Room. No server. | Read a book end-to-end; progress survives app restart. |
| **2 — Progress sync** | Stand up `opds-sync` with Authentik auth and progress-only API. Wire WorkManager sync client. Document-identity logic (composite id) lands here. | Progress on device A appears on device B within one foreground sync. |
| **3 — Highlights sync** | Add `highlight` annotation type. Server tables, client UI, field-level LWW + tombstones. | Highlight on A, see on B; delete on A, gone on B. |
| **4 — Notes & bookmarks** | Add `note` and `bookmark` types. Same merge model. | Same as Phase 3 for both new types. |
| **5 — PDF support** *(deferred, may never ship)* | Readium PDF navigator. Identity uses content hash only. | Read a PDF, progress survives. |
| **6 — Calibre plugin** | Python plugin reads from `opds-sync`, writes to Calibre `metadata.db` custom columns. Read-only consumer of the sync API. | Open Calibre, see per-book reading progress in a custom column. |

The Phase 6 plugin is also an architectural smoke test: a third independent client dropping into the API cleanly is evidence the API is well-shaped.

## 5. Document identity

### 5.1 Schema

Every document record on the server has two identity columns; either can resolve a record.

| Column | Meaning |
|---|---|
| `metadata_id` | Normalized first non-empty `dc:identifier` from the EPUB OPF. Nullable. |
| `content_hash` | KOReader-style sampled MD5 of the file. Always present. |

Both are indexed.

### 5.2 Sampled hash algorithm

Matches KOReader's "binary" hash (potential interop). Fast (64 KB read total regardless of file size), stable across filename changes and most EPUB metadata edits.

```
step  = max(1, filesize // 1024)
buf   = bytearray()
for i in 0..1023:
    seek(i * step)
    buf.extend(read(64))
content_hash = md5(buf).hexdigest()
```

Does **not** survive Calibre re-encoding the EPUB. That's exactly what `metadata_id` covers.

### 5.3 `metadata_id` normalization

Every client must apply these rules identically:

1. Take the first `<dc:identifier>` element in `content.opf` whose trimmed value is non-empty.
2. Lowercase.
3. Strip a leading `urn:` prefix and a leading scheme prefix (`isbn:`, `uuid:`, `calibre:`, `mobi-asin:`, etc.). Keep only the bare value.
4. Remove all whitespace and hyphens.
5. If empty after normalization, treat as missing.

Examples:

| Input | Output |
|---|---|
| `urn:uuid:550E8400-E29B-41D4-A716-446655440000` | `550e8400e29b41d4a716446655440000` |
| `ISBN: 978-0-14-103614-4` | `9780141036144` |
| `calibre:42` | `42` |

A reference implementation lives in a shared spec section that both Android and the sync server import (Android: a Kotlin function under `:core:identity`; server: a Python function in `opds_sync.identity`). Both sides have unit tests against an identical fixture set.

### 5.4 Lookup precedence

Client and server both apply this order:

1. If `metadata_id` is present → look up by `metadata_id`. Match wins.
2. Else → look up by `content_hash`. Match wins.
3. Else → no match; create new record.

### 5.5 Reconciliation

When a record was first written by hash alone and the client later learns the metadata-id, it calls:

```
POST /sync/v1/documents/alias
{ "content_hash": "...", "metadata_id": "..." }
```

In one transaction the server:

1. Finds the record keyed by `content_hash`.
2. Finds the record keyed by `metadata_id` (may not exist).
3. If both exist and differ, merges: per-field LWW for scalar fields (progress, locator), set union with tombstone resolution for annotations (per §6.3).
4. Deletes the orphan, returns the surviving record's ID.

Client calls this exactly once per document, the first time it has both identifiers in hand.

### 5.6 Known limitations (documented, not fixed)

- **`calibre:N` collisions across libraries.** If you ever sync against two different Calibre instances, book #42 in each will collide. v1 assumes one calibre-web. Mitigation if needed: prefix `metadata_id` with a library scope. Deferred.
- **Re-anchoring after EPUB republish.** If Calibre re-converts a book and CFI offsets shift, the progress locator may be off by a page. Acceptable. Annotations have a snippet fallback (§6.4).
- **Pirated EPUBs with reused UUIDs.** Different books with identical `dc:identifier` values will alias to one record. Rare. Workaround: edit the OPF in Calibre to give it a unique identifier. Not a code problem.

## 6. Annotations

### 6.1 Types

| Kind | Shape | Phase |
|---|---|---|
| `highlight` | Range with optional color. | 3 |
| `note` | Range with body text (highlight + body). | 4 |
| `bookmark` | Single point with optional label. | 4 |

One table with a `kind` discriminator and nullable optional fields. Merge logic is identical for all three; "everything for this document" is the dominant access pattern.

### 6.2 Server schema

```
id              uuid PRIMARY KEY        -- client-generated
user_id         text NOT NULL           -- Authentik 'sub' claim
document_pk     bigint NOT NULL REFERENCES documents(pk)
kind            text NOT NULL           -- 'highlight' | 'note' | 'bookmark'
cfi_start       text NOT NULL           -- EPUB CFI
cfi_end         text                    -- null for bookmarks
text_snippet    text NOT NULL           -- ≤512 chars, for re-anchoring
body            text                    -- note body, null otherwise
color           text                    -- e.g. 'yellow', null otherwise
created_at      timestamptz NOT NULL    -- server-assigned, immutable
updated_at      timestamptz NOT NULL    -- server-assigned on every write
deleted_at      timestamptz             -- tombstone marker
field_versions  jsonb NOT NULL          -- per-field client timestamps
```

Index: `(user_id, document_pk, updated_at)` for incremental sync queries.

Client-generated UUIDs let the client create annotations offline and reference them locally before first sync. The server never reassigns IDs.

### 6.3 Field-level LWW

`field_versions` holds per-field client timestamps:

```json
{
  "cfi_start":    "2026-04-26T10:15:00Z",
  "cfi_end":      "2026-04-26T10:15:00Z",
  "text_snippet": "2026-04-26T10:15:00Z",
  "body":         "2026-04-26T11:42:00Z",
  "color":        "2026-04-26T12:01:00Z",
  "deleted_at":   null
}
```

On write, for each field the client provides:

- If incoming `field_versions[f]` > stored `field_versions[f]` → accept new value.
- Else → keep stored value.

Editing a note's body on device A and its color on device B while both are offline: both edits land. Last-writer-wins is per field, not per record.

### 6.4 Tombstones

Delete is `deleted_at = now()`; row remains. Sync queries filter `WHERE deleted_at IS NULL` unless the client opts in to tombstones (it does, so it can propagate deletes).

GC: nightly job deletes rows where `deleted_at < now() - interval '90 days'`. Clients offline more than 90 days will resurrect deleted annotations on next sync. Documented and accepted.

### 6.5 Anchoring & fallback rendering

When the Android client renders an annotation:

1. Resolve CFI. Read surrounding text.
2. If surrounding text does not contain `text_snippet` (case-insensitive, whitespace-normalized, prefix match of first ~32 chars), fall through to step 3.
3. Search the spine item for `text_snippet`. If found at exactly one location, use that. Update local CFI to match (do not push the update — the server's CFI is authoritative across clients with potentially different EPUB copies).
4. If not found or ambiguous, mark the annotation **orphaned** and surface it in a sidebar with the snippet text. User can manually re-anchor or delete.

This handles "EPUB republished with shifted CFIs" without silently dropping data.

### 6.6 Annotation wire format — push

```
POST /sync/v1/annotations
Authorization: Bearer <Authentik JWT>

{
  "annotations": [
    {
      "id": "uuid",
      "document": { "metadata_id": "...", "content_hash": "..." },
      "kind": "highlight",
      "cfi_start": "epubcfi(/6/4!/...)",
      "cfi_end":   "epubcfi(/6/4!/...)",
      "text_snippet": "...",
      "body": null,
      "color": "yellow",
      "field_versions": { "cfi_start": "...", ... },
      "deleted": false
    }
  ]
}
```

Response:

```
{
  "results": [
    {
      "id": "uuid",
      "status": "accepted" | "rejected_stale" | "merged",
      "field_versions": { ... }   // server's authoritative state after merge
    }
  ]
}
```

`rejected_stale` means every field on the incoming record had an older timestamp than what the server already had. The client updates its local cache from `field_versions`.

### 6.7 Annotation wire format — pull

```
GET /sync/v1/annotations?since=<ISO8601>&document=<metadata_id_or_hash>
```

`document` is optional; omit to pull everything for the user. `since` is the high-water mark from the last successful pull. Server returns rows where `updated_at > since`, including tombstones (`deleted: true`) so deletes propagate.

### 6.8 Out of scope

- Annotation groups or folders.
- Comment threads on annotations.
- Rich text in `body` — plain text only.
- Server-side CFI validation. Client is trusted; bad CFI surfaces as orphaned.
- Export endpoints (CSV/Markdown). Future work.

## 7. Sync API surface

All endpoints under `/sync/v1`. All require `Authorization: Bearer <JWT>`. All bodies are JSON.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/documents/alias` | Reconcile a hash-keyed record with a newly-known metadata-id (§5.5). |
| `POST` | `/progress` | Push current progress (locator, percent) for one or more documents. |
| `GET` | `/progress?since=...&document=...` | Pull progress updates. |
| `POST` | `/annotations` | Push annotation create/update/delete (§6.6). |
| `GET` | `/annotations?since=...&document=...` | Pull annotation deltas (§6.7). |
| `GET` | `/healthz` | Liveness, no auth. |
| `GET` | `/readyz` | Readiness, no auth, checks Postgres. |

Conflict resolution:
- **Progress:** record-level LWW on `updated_at`.
- **Annotations:** field-level LWW per §6.3.

## 8. Auth

Two credentials, two protocols. No clever unification.

### 8.1 Calibre-web OPDS — HTTP Basic

- Create a dedicated `android-reader` user in calibre-web (not your admin account).
- Android stores the username + password in **Android Keystore** (encrypted, hardware-backed where available).
- Every OPDS HTTP request sends `Authorization: Basic <base64(user:pass)>`.
- Reason: calibre-web's OIDC integration is browser-only; OPDS clients can't ride the redirect flow. Basic against the built-in user store is the supported path.

### 8.2 Sync server — Authentik OIDC + PKCE

- Add a new OAuth2/OIDC application in Authentik for `opds-sync`.
- **Public client** (no client secret), redirect URI `eink-reader://oauth`. Mirrors the existing AudioBookShelf mobile pattern in this cluster.
- Android does **Authorization Code + PKCE** against `https://auth.theficos.dedyn.io`.
- Refresh token stored in Android Keystore.
- Sync server validates JWTs against Authentik's JWKS (cached, refreshed on `kid` miss). Validates `iss`, `aud`, `exp`, `nbf`. Rejects anything else.
- `sub` claim is the user identity. Stored as `user_id` on every row. **Multi-user from day one.**

### 8.3 Token handling on Android

- Access token used per-request. On 401, refresh once, retry once. On second 401, clear refresh token and require re-auth.
- Refresh tokens never leave Keystore; rotation honored if Authentik issues new refresh tokens on use.
- Logout clears both Keystore entries (calibre-web Basic creds and Authentik tokens).

## 9. Android app architecture

### 9.1 Stack

- **Kotlin**, **Jetpack Compose** for UI.
- **Readium Kotlin Toolkit** (`readium-streamer`, `readium-navigator`, `readium-opds`) for parsing, rendering, OPDS feed consumption.
- **Room** for local DB.
- **WorkManager** for sync orchestration.
- **OkHttp + kotlinx.serialization** for HTTP / JSON.
- **AppAuth** for OIDC + PKCE.

### 9.2 Module layout

```
:app                  -- Compose UI, navigation, DI wiring
:core:identity        -- doc identity (hash, normalization) — shared spec
:core:model           -- domain types (Document, Annotation, Progress)
:data:local           -- Room database, DAOs
:data:opds            -- calibre-web OPDS client
:data:sync            -- opds-sync REST client
:reader               -- Readium navigator integration, annotation overlay
:auth                 -- AppAuth wrapper, Keystore-backed credential store
```

### 9.3 Local DB (Room)

Tables mirror the server, with two extras:

- `pending_sync_ops` — outbox of writes that haven't reached the server yet.
- `sync_state` — per-table `last_pulled_at` high-water marks.

Sync is a one-shot WorkManager job: drain outbox (push), then pull deltas, then update high-water marks. Triggered on app foreground, on network reconnect, and on user-initiated pull-to-refresh.

### 9.4 Reader UI scope (Phase 1)

- Library shelf (downloaded books).
- Catalog browser (live OPDS).
- Reader view (Readium navigator).
- Settings (font size, theme, sync toggle, account).

Polish (custom fonts, page-turn animations, themes beyond light/dark/sepia) is iterative and not gated by this spec.

## 10. Sync server architecture

### 10.1 Stack

- **Python 3.12 + FastAPI** for the HTTP layer.
- **SQLAlchemy 2.x** + **Alembic** for ORM and migrations.
- **PyJWT + httpx** for Authentik JWKS validation.
- **Postgres 16** (managed by the cluster).
- **uvicorn** behind the Traefik ingress; one replica is enough for v1.

### 10.2 Module layout

```
opds_sync/
  api/
    progress.py
    annotations.py
    documents.py
    health.py
  core/
    auth.py          -- JWT validation, JWKS cache
    identity.py      -- shared identity normalization (matches Android)
    merge.py         -- field-level LWW + alias merge
  db/
    models.py        -- SQLAlchemy models
    session.py
  main.py            -- FastAPI app factory
migrations/          -- Alembic
tests/
  unit/
  integration/       -- spin up Postgres in a container, hit real endpoints
```

### 10.3 Deployment

Kustomize app under `theficos-cluster/applications/opds-sync/`, mirroring existing apps:

```
applications/opds-sync/
  kustomization.yaml
  namespace.yaml
  deployment.yaml
  service.yaml
  ingress.yaml          # sync.theficos.dedyn.io, Traefik + cert-manager
  secret.yaml           # SOPS-encrypted: DB URL, Authentik issuer/audience
  postgres-pvc.yaml
  postgres-statefulset.yaml
  network-policies.yaml
```

Deployed via `make deploy APP=opds-sync`.

### 10.4 Observability

- Structured JSON logs to stdout (request id, user id, latency, status).
- `/healthz` and `/readyz` probes.
- Postgres connection pool metrics via SQLAlchemy events to logs (no Prometheus in v1; add later if needed).

## 11. Calibre plugin (Phase 6, sketch)

Out-of-process consumer of `opds-sync`. Polls the sync API on a timer (or on user-triggered "Refresh from sync server"), maps documents back to Calibre books via `metadata_id`, writes:

- `#last_read_position` (custom column, text) — locator string.
- `#read_progress` (custom column, float) — 0–1.
- `#highlight_count` (custom column, int) — count of non-deleted highlights.

Read-only against the sync API; no writes, no merge logic, no opinions. If the API is well-shaped this plugin is ~200 lines of Python.

## 12. Open questions & risks

- **Readium PDF navigator maturity.** Validate before Phase 5; may push it to v∞.
- **EPUB CFI portability.** Different EPUB readers can produce subtly different CFIs for the same logical position. Mitigated by snippet fallback for annotations; progress is approximate by nature.
- **Annotation orphaning UX.** Sidebar of orphans is a placeholder; needs a real flow when we get there.
- **Rate limiting.** Single-user homelab, but if the plugin polls aggressively it's worth a basic per-user rate limit on the sync API. Add when needed.
- **Backup of Postgres.** Out of this spec — leverages the cluster's existing backup story.

---

## Appendix A: Worked example — document identity

Same book, three identification scenarios:

1. **Calibre-converted EPUB** (typical case). OPF contains `<dc:identifier scheme="calibre">42</dc:identifier>` and `<dc:identifier opf:scheme="UUID">urn:uuid:...</dc:identifier>`. Spec rule §5.3.1 picks the first one. `metadata_id = "42"`. Hash also computed.

2. **Sideloaded EPUB with ISBN**. OPF contains only `<dc:identifier>ISBN 978-0-14-103614-4</dc:identifier>`. After normalization, `metadata_id = "9780141036144"`.

3. **Sideloaded EPUB, no usable identifier**. OPF identifier is missing or blank after normalization. `metadata_id = NULL`. Lookup falls through to `content_hash`.

If case 1 is later re-downloaded after Calibre re-converts the file:
- `content_hash` differs (re-encoded).
- `metadata_id` is still `"42"`.
- Lookup by `metadata_id` matches the existing record. Progress and annotations carry over.

If case 3's record exists keyed by hash, and the user later edits the OPF in Calibre to add a UUID:
- Next download: client computes both `metadata_id` and `content_hash`.
- Lookup by `metadata_id` returns no match (it's new).
- Lookup by `content_hash` returns the old record.
- Client calls `POST /documents/alias`. Server merges. Future syncs use the metadata-id.

## Appendix B: Worked example — annotation merge

Device A and Device B both have a highlight `id=H1` on the same paragraph. Both go offline.

- Device A at `t=10:00` changes `color` from `yellow` to `green`.
- Device B at `t=10:05` changes `body` from `null` to `"interesting"` (becoming a note).

Both come online. Both push to server.

A's push first:

```json
{
  "id": "H1",
  "color": "green",
  "field_versions": { "color": "10:00", "body": "<earlier>" }
}
```

Server: incoming `color@10:00 > stored color@<earlier>` → accept. Stored `body` unchanged.

B's push second:

```json
{
  "id": "H1",
  "body": "interesting",
  "field_versions": { "color": "<earlier>", "body": "10:05" }
}
```

Server: incoming `color@<earlier> < stored color@10:00` → keep stored `green`. Incoming `body@10:05 > stored body@<earlier>` → accept `"interesting"`.

Final state: `color=green`, `body="interesting"`. Both edits preserved.

## Appendix C: Decision log

| # | Decision | Rationale |
|---|---|---|
| 1 | Full spec, phased build (option C from brainstorm Q1) | Forces hard problems on paper before code; ships value early. |
| 2 | Composite document identity (metadata-id + content-hash) | Survives both Calibre re-encodes and sideloaded files without metadata. |
| 3 | Server-side merge on alias | Keeps the system tidy long-term; one transaction. |
| 4 | Per-annotation field-level LWW with tombstones | Standard, correct, fits relational schema. CRDTs unnecessary with single server. |
| 5 | 90-day tombstone GC | Bounded storage; documented edge case for long-offline clients. |
| 6 | CFI + text snippet anchoring | Survives EPUB republishing without silent data loss. |
| 7 | Split auth (OPDS Basic, sync OIDC+PKCE) | Each protocol gets the auth that fits its nature. Clever unification leaks. |
| 8 | Python FastAPI for sync server | Fastest to write; traffic is trivial; deploys cleanly into existing cluster. |
| 9 | Single annotations table with `kind` discriminator | Identical merge logic across kinds; queries are document-scoped. |
| 10 | Calibre plugin as Phase 6 read-only consumer | Validates API shape; non-blocking; clean separation from the sync server. |
