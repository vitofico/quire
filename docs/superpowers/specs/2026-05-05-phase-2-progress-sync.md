# Phase 2 — Progress Sync

**Date:** 2026-05-05
**Status:** Draft, pending implementation plan
**Scope:** Stand up `opds-sync` server with Authentik auth and progress-only API; wire WorkManager-based sync client on Android.
**Parent spec:** [`2026-04-26-opds-ereader-design.md`](./2026-04-26-opds-ereader-design.md)

---

## 1. Goals

Per the parent spec's Phase 2 ship gate:

> Progress on device A appears on device B within one foreground sync.

This phase introduces the sync server and wires the client to it. Annotations are out of scope; this phase is about getting end-to-end auth + sync infrastructure in place, exercised against the simplest possible payload (record-level progress).

## 2. Non-goals

- Annotations (highlights, notes, bookmarks) — Phase 3.
- `/documents/alias` reconciliation endpoint — Phase 3.
- `pending_sync_ops` outbox table — Phase 3.
- Periodic / network-reconnect sync triggers — only foreground triggers in Phase 2.
- Multi-Calibre library scoping.
- GitOps deploy automation; initial deploy is manual `kubectl apply -k`.
- Prometheus metrics; JSON logs only.

## 3. Decisions taken in brainstorming

| # | Question | Decision |
|---|---|---|
| 1 | Repo structure | Monorepo: server source under `server/`, manifests under `deploy/k8s/opds-sync/` in this repo |
| 2 | Server document schema | Identity-only (`metadata_id`, `content_hash`); display metadata stays client-local |
| 3 | Auth UX entry point | Sync off by default; opt-in via Settings → Sync |
| 4 | Outbox shape | Implicit dirty flag on `progress` (`localUpdatedAt > syncedAt`); the `pending_sync_ops` table arrives in Phase 3 with annotations |
| 5 | Sync triggers | Foreground-only: library `LaunchedEffect` (pull), reader `onPause` (push), Settings → Sync now (manual) |
| 6 | Document scoping on server | User-scoped: unique indexes are `(user_id, metadata_id)` and `(user_id, content_hash)` |
| 7 | LWW timestamp source | Client-supplied `client_updated_at`; server records a separate `received_at` for audit |
| 8 | Multi-user | Schema is multi-user from day one (`user_id` column on every row); the Android app stays single-account at a time |
| 9 | Redirect URI | `quire://oauth` (matches the `io.theficos.quire` applicationId) |
| 10 | Identity parity tests | Shared JSON fixtures loaded by both Kotlin and Python tests; CI runs both |

## 4. Top-level architecture

```
[calibre-web] ──OPDS+Basic──> [Quire Android]
                                     │
                                     │  HTTPS + JWT (Authentik)
                                     ▼
                              [opds-sync (FastAPI)]
                                     │
                                     ▼
                                [Postgres 16]
```

One sync replica, one Postgres StatefulSet, behind Traefik + cert-manager at `sync.theficos.dedyn.io`. Authentik JWKS cached in-memory, refreshed on `kid` miss.

## 5. Repository layout

```
opds-ereader-android-app/
├── app/, auth/, core/, data/, reader/      (existing Android)
├── data/sync/                              (NEW Android module)
│   └── src/main/java/io/theficos/ereader/data/sync/
│       ├── SyncApi.kt
│       ├── SyncClient.kt
│       ├── SyncOrchestrator.kt
│       ├── ProgressDtos.kt
│       └── SyncWorker.kt
├── server/                                 (NEW Python service)
│   ├── pyproject.toml
│   ├── opds_sync/
│   │   ├── api/{progress.py, documents.py, health.py}
│   │   ├── core/{auth.py, identity.py, merge.py}
│   │   ├── db/{models.py, session.py}
│   │   └── main.py
│   ├── migrations/                         Alembic
│   ├── tests/{unit,integration}/
│   ├── fixtures/identity/                  symlink → core/identity/.../identity-fixtures
│   └── Dockerfile
├── core/identity/src/test/resources/identity-fixtures/   (canonical fixtures)
├── deploy/k8s/opds-sync/
│   ├── kustomization.yaml
│   ├── namespace.yaml, deployment.yaml, service.yaml, ingress.yaml
│   ├── secret.yaml                         SOPS-encrypted
│   ├── postgres-{statefulset,pvc}.yaml
│   └── network-policies.yaml
└── docs/superpowers/specs/2026-05-05-phase-2-progress-sync.md
```

## 6. Server design

### 6.1 Stack

- Python 3.12, FastAPI, SQLAlchemy 2.x async with `asyncpg`.
- Alembic for migrations.
- PyJWT + httpx for Authentik JWKS validation.
- uvicorn behind Traefik. Single replica.
- Postgres 16.

### 6.2 Schema (Alembic v1)

```sql
documents (
  pk             bigserial primary key,
  user_id        text not null,
  metadata_id    text,
  content_hash   text not null,
  created_at     timestamptz not null default now(),
  unique (user_id, metadata_id),
  unique (user_id, content_hash)
);
create index on documents (user_id);

progress (
  document_pk         bigint primary key references documents(pk) on delete cascade,
  locator             text not null,
  percent             double precision not null check (percent >= 0 and percent <= 1),
  client_updated_at   timestamptz not null,   -- LWW key, from device
  received_at         timestamptz not null default now()
);
create index on progress (document_pk, client_updated_at);
```

One progress row per document (record-level LWW: server stores only the winner). Every query filters by `WHERE user_id = :sub`. Phase 3 will add `annotations`; not now.

### 6.3 Endpoints

All under `/sync/v1`. JSON bodies. Auth required except `/healthz` and `/readyz`.

| Method | Path | Body / Query | Purpose |
|---|---|---|---|
| GET  | `/healthz`  | — | liveness, no auth |
| GET  | `/readyz`   | — | readiness, checks Postgres, no auth |
| POST | `/progress` | `{ items: [...] }` | upsert documents (if needed), then upsert progress with LWW |
| GET  | `/progress` | `?since=<iso8601>` | rows where `client_updated_at > since` |

**POST `/progress` request:**
```json
{
  "items": [
    {
      "document": { "metadata_id": "9780141036144", "content_hash": "abc..." },
      "locator": "epubcfi(/6/4!/4/2/2:35)",
      "percent": 0.42,
      "client_updated_at": "2026-05-05T12:34:56.789Z"
    }
  ]
}
```

**POST `/progress` response:**
```json
{
  "results": [
    {
      "document": { "metadata_id": "9780141036144", "content_hash": "abc..." },
      "status": "accepted",
      "server_client_updated_at": "2026-05-05T12:34:56.789Z"
    }
  ]
}
```

`status` is `"accepted"` if the incoming `client_updated_at` was strictly newer, `"stale"` otherwise. `server_client_updated_at` is the timestamp on the surviving row — the client uses it to set `syncedAt` so it stops re-pushing.

**GET `/progress` response:**
```json
{
  "items": [
    {
      "document": { "metadata_id": "9780141036144", "content_hash": "abc..." },
      "locator": "...",
      "percent": 0.42,
      "client_updated_at": "2026-05-05T12:34:56.789Z"
    }
  ],
  "server_time": "2026-05-05T12:35:00.000Z"
}
```

The client persists `server_time` as its high-water mark (uses server clock to avoid skew on the *boundary* of pull queries).

### 6.4 Auth

PyJWT validates against Authentik JWKS:
- httpx fetch from `${AUTHENTIK_ISSUER}/jwks/`, in-memory cache, refresh on `kid` miss.
- Validates `iss`, `aud`, `exp`, `nbf`. Rejects everything else.
- `sub` claim is the user identity, stored as `user_id` on every row.

Authentik OAuth2 application config (manual prerequisite, not automated):
- Public client, no client secret.
- PKCE required.
- Redirect URI `quire://oauth`.
- Issued audience matches `AUTHENTIK_AUDIENCE` env var.

### 6.5 Deployment

Kustomize app under `deploy/k8s/opds-sync/`:
- `Deployment` — one replica, image from GHCR tagged with git SHA.
- `Service` — ClusterIP.
- `Ingress` — Traefik, `sync.theficos.dedyn.io`, cert-manager TLS.
- `StatefulSet` for Postgres + PVC.
- `NetworkPolicy` — only the sync deployment can reach Postgres; only Traefik can reach the sync service.
- `Secret` (SOPS-encrypted): `DATABASE_URL`, `AUTHENTIK_ISSUER`, `AUTHENTIK_AUDIENCE`.

CI (GitHub Actions) builds and pushes the Docker image. Initial deploy is `kubectl apply -k deploy/k8s/opds-sync/`. GitOps wiring deferred.

### 6.6 Observability

Structured JSON logs to stdout: request id, user id, latency, status. No Prometheus in Phase 2.

## 7. Client design

### 7.1 Auth (`:auth` module additions)

New types alongside existing `CalibreCredentialStore` (which is untouched):

```kotlin
AuthentikConfig(issuer, clientId, redirectUri = "quire://oauth")
AuthentikAuthenticator     // AppAuth wrapper: Custom Tab + PKCE
AuthTokenStore             // EncryptedSharedPreferences, holds access + refresh
SyncAuthInterceptor        // OkHttp Interceptor:
                           //   attach Bearer; on 401 refresh once + retry once;
                           //   on second 401 clear refresh token, emit NeedsReauth
sealed class AuthState { SignedOut; SignedIn(sub, email, expiresAt); NeedsReauth }
```

Refresh tokens never leave Keystore-backed storage. Logout clears both Keystore entries (Calibre + Authentik) — Calibre creds remain because they're in a separate store; only Authentik tokens are wiped on sync sign-out.

### 7.2 Sync orchestration (`:data:sync` module)

```kotlin
SyncOrchestrator.runOnce() {
    // 1. push: every progress row where localUpdatedAt > syncedAt
    val dirty = progressDao.dirty()
    if (dirty.isNotEmpty()) {
        val resp = api.pushProgress(dirty.toDtos())
        progressDao.markSynced(resp.results)   // sets syncedAt = server_client_updated_at
    }
    // 2. pull: rows updated since last high-water mark
    val since = syncStateDao.lastPulled("progress")
    val pulled = api.pullProgress(since)
    db.withTransaction {
        pulled.items.forEach { upsertLocalProgress(it) }
        syncStateDao.setLastPulled("progress", pulled.serverTime)
    }
}
```

**Conflict on pull:** if a pulled row's `client_updated_at` ≤ local row's `localUpdatedAt`, keep local. The next push will carry it forward.

**Triggers:**
- `Library` screen `LaunchedEffect(Unit)` on first composition → enqueue `SyncWorker`.
- `Reader` screen `onPause` (lifecycle event) → enqueue `SyncWorker`.
- Settings → "Sync now" button → enqueue `SyncWorker`.

`SyncWorker` is a `CoroutineWorker`:
- Network constraint: `NetworkType.CONNECTED`.
- Backoff: exponential, 3 retries.
- Existing-work policy: `KEEP` — multiple enqueues collapse.
- `setExpedited` when triggered from foreground events.

### 7.3 Room migrations

- **v2:** add `localUpdatedAt`, `syncedAt` to `progress`. Backfill `localUpdatedAt = updatedAt`, `syncedAt = 0`. Reader writes bump `localUpdatedAt`.
- **v3:** new table `sync_state(tableName TEXT PRIMARY KEY, lastPulledAt INTEGER NOT NULL)`.

### 7.4 Settings UI

A new `SettingsSyncScreen` containing:
- Account state row: "Signed out" or "Signed in as `email`".
- `Sign in` / `Sign out` button (mutually exclusive with state).
- `Sync now` button — disabled when signed out or while a sync is in flight.
- Last-synced timestamp (relative, e.g. "2 minutes ago").
- Server URL, read-only (from `BuildConfig`).

Sign-out clears local Room rows for the signed-out user (one-local-user assumption; see Risks). Server data is retained.

## 8. Identity parity testing

Single canonical fixture file at `core/identity/src/test/resources/identity-fixtures/normalize-cases.json`:

```json
[
  { "input": "urn:uuid:550E8400-E29B-41D4-A716-446655440000",
    "expected": "550e8400e29b41d4a716446655440000" },
  { "input": "ISBN: 978-0-14-103614-4", "expected": "9780141036144" },
  { "input": "calibre:42", "expected": "42" },
  { "input": "  ", "expected": null }
]
```

- Kotlin: existing `MetadataIdNormalizerTest` refactored to load this file.
- Python: new `tests/unit/test_identity.py` loads the same file via `server/fixtures/identity/` symlink.
- Content hash: a small fixture binary is committed once; both sides assert identical MD5.

CI runs both test suites on every PR; a drift between Kotlin and Python implementations fails the build.

## 9. Phase 2 ship gate

> Progress on device A appears on device B within one foreground sync.

Concretely, with two emulators (or one phone + one emulator), both signed in as the same Authentik user:

1. On A, open a book, advance ~10%, close the book. Push fires on `onPause`.
2. On B, foreground the app and open the library. Pull fires on `LaunchedEffect`. Open the same book → reader resumes within ~1 page of A's position.
3. Force-close A, on B advance to 50%, close book on B, then foreground A and open library → A's progress now reads 50%.
4. Sign out on B → all server rows for that user remain; local Room rows on B are cleared. Sign back in → state restored from server.

Acceptance also requires:
- `/healthz` and `/readyz` return 200 from the deployed instance.
- Identity parity tests pass on both sides in CI.
- Authentik flow completes end-to-end with `quire://oauth` redirect.

## 10. Risks & explicit deferrals

| Item | Status in Phase 2 | When it activates |
|---|---|---|
| `pending_sync_ops` outbox table | not created | Phase 3 (annotations need ordered events) |
| `/documents/alias` endpoint + client call | not built; schema supports a future `alias_merged_at` column | Phase 3 or when sideloaded EPUBs become a real flow |
| Annotation tables | not created | Phase 3 |
| Tombstone GC job | n/a (no tombstones yet) | Phase 3 |
| Periodic `WorkManager` job + `ConnectivityManager` reconnect listener | not wired | If foreground-only proves insufficient |
| Multi-Calibre `metadata_id` library scoping | bare `metadata_id` | If/when a second calibre-web is added |
| Prometheus / metrics | not exposed (JSON logs only) | When traffic justifies it |
| GitOps for `deploy/k8s/opds-sync/` | manual `kubectl apply -k` | When `theficos-cluster` adds opds-sync to its sync loop |
| Pre-existing local progress on first sign-in | attributed to whoever signs in (one-local-user assumption) | Revisit if multi-account-on-device ever appears |
| Sign-out data policy on server | server retains; local cleared | Consider "wipe server data" UX in a later polish phase |

**Risk: Authentik client config drift.** The OAuth2 application (public client, PKCE, redirect URI `quire://oauth`) must exist in Authentik before first sign-in. Manual setup step.

**Risk: Custom Tab unavailable.** AppAuth falls back to a system browser; if neither is present, sign-in fails with a clear error.

**Risk: Clock skew on devices.** Client-supplied `client_updated_at` means a wrongly-clocked device can starve correct writes. Acceptable for homelab; if it bites, Phase 3 can layer a server-issued lamport stamp.

## 11. Decision log

| # | Decision | Rationale |
|---|---|---|
| 1 | Monorepo (server + manifests + Android) | Cross-cutting changes during Phase 2 stay atomic; one PR |
| 2 | Server documents identity-only (no title/author) | calibre-web is the source of truth for metadata; avoids "whose title wins" merge |
| 3 | Sync opt-in via Settings, off by default | Preserves Phase 1 experience; one local user makes attribution-on-sign-in trivial |
| 4 | Implicit dirty flag instead of `pending_sync_ops` | Progress is record-level LWW — only the latest local value matters; outbox table is YAGNI for Phase 2 |
| 5 | Foreground-only sync triggers | Solo homelab user; covers the realistic flow without periodic wakeups |
| 6 | User-scoped `documents` rows | No cross-user merge surprises; multi-user enforced at the unique index |
| 7 | Client-supplied `client_updated_at` | Two devices may push at the same server-time but represent distinct events |
| 8 | Defer `/documents/alias` to Phase 3 | Every download already produces both identifiers; retroactive reconciliation never fires in Phase 2 |
| 9 | Manual `kubectl apply -k` for first deploy | GitOps is a Phase-N concern, not a Phase 2 concern |
| 10 | Shared JSON fixtures for identity parity | Symlink prevents Kotlin/Python drift; CI fails on divergence |
