# Phase 2.1 — CWA-as-source-of-truth Auth

**Date:** 2026-05-06
**Status:** Draft, supersedes Phase 2 §8 auth
**Scope:** Replace Phase 2's Authentik OIDC + JWT auth path with HTTP Basic delegated to Calibre-Web-Automated (CWA). Single credential surface; no SSO dependency in the Android app.
**Parent specs:**
- [Phase 1+2 design](./2026-04-26-opds-ereader-design.md)
- [Phase 2 progress sync](./2026-05-05-phase-2-progress-sync.md)

---

## 1. Goals

- The Android app uses **one credential**: HTTP Basic against CWA. Same creds work for OPDS (already true in Phase 1) and for opds-sync (new).
- opds-sync is **stateless about auth** — it forwards the Basic header to CWA's `/opds` endpoint, takes CWA's verdict as truth, and uses the CWA username as `user_id`.
- The app has **no SSO dependency**. It does not know Authentik (or any other OIDC provider) exists.
- The architecture survives a future replacement for CWA: if a new ebook-management app exposes any auth-check endpoint, swap a config value and continue.

## 2. Non-goals

- Single sign-on at the app layer.
- API tokens / refresh tokens / bearer tokens.
- Solving the "OIDC-only user can't use OPDS" caveat. That's a CWA limitation; users in that situation set a local OPDS password in CWA admin (existing CWA workaround).
- Multi-tenancy. Single-Calibre, multi-user via CWA's user store.
- Authentik proxy outpost / forward-auth pattern. Considered and rejected: introduces an SSO dependency.

## 3. Decisions

| # | Decision | Rationale |
|---|---|---|
| 1 | Drop Authentik integration on both sides | App must not depend on an OIDC server. |
| 2 | opds-sync validates by proxying to CWA `/opds` | CWA is the user source of truth; "what auth method works for OPDS?" is what works for sync. |
| 3 | TTL cache on opds-sync (60s, keyed by `sha256(auth-header)`) | Prevents CWA hammering on bursty pulls; 60s tolerable staleness. |
| 4 | `user_id` = lowercased CWA username (the Basic header user) | Avoids needing CWA to expose a user-id endpoint. |
| 5 | opds-sync deploys in CWA's namespace | Same trust boundary, simpler network policy. |
| 6 | Keep PyJWT/cryptography only as test deps (or drop entirely) | They're not used at runtime any more. |
| 7 | Reuse the existing Android `BasicAuthInterceptor` for opds-sync | The same OkHttp interceptor that drives OPDS now also drives sync. |
| 7b | Sync URL derives from `CalibreCredentialStore.baseUrl` (not a BuildConfig) | One auth surface = one URL. Re-pointing the app at a new server is one Settings edit. |
| 8 | Drop AppAuth, `AuthentikAuthenticator`, `AuthTokenStore`, `AuthState`, `SyncAuthInterceptor`, `FakeAndroidKeyStore` | None are needed in the new model. |
| 9 | Settings → Sync section keeps "Sync now" + last-synced; loses sign-in/out buttons | Sync state derives from "are CWA creds present?". |
| 10 | OIDC-only-on-CWA users: documented as a CWA-side prerequisite | CWA's compromise; documented in ops, not coded around. |

## 4. Architecture

```
        ┌──────────────────────┐
Quire → │ Calibre-Web-Auto     │  ← user logs in via internal/LDAP/OIDC (admin choice)
        │ /opds (Basic)        │
        └──────────────────────┘
                ▲
                │ HTTP Basic (validation only)
                │
        ┌──────────────────────┐
Quire → │ opds-sync            │  ← /sync/v1/progress with same Basic header
        │ /sync/v1/* (Basic)   │
        └──────────────────────┘
```

Both services live in CWA's namespace. The Android app holds one set of credentials in `CalibreCredentialStore` (existing) and presents them to both endpoints via the same `OkHttpClient`.

## 5. Server design (opds-sync changes)

### 5.1 Auth flow

For each request to a protected endpoint:

1. Extract `Authorization: Basic <b64>` from the request. If missing → 401.
2. Look up `(scheme, value)` in TTL cache. On hit, use cached `user_id` (or 401 on cached negative).
3. On miss: `await httpx_client.get(f"{CWA_BASE_URL}/opds", headers={"Authorization": <header>}, follow_redirects=False)` — `httpx_client` is an `AsyncClient` owned by the validator (single instance, configurable timeout ~3s).
   - 200 → decode the username from the Basic header (`base64.b64decode(value).decode().split(":", 1)[0]`), lowercase it, cache and return.
   - 401 → cache negative, return 401.
   - Anything else → 503 ("upstream auth unavailable"); do not cache.
4. Use `user_id` as today (per-row scoping unchanged).

### 5.2 Cache

In-memory `dict[bytes, CacheEntry]` with TTL = 60s. Key is `sha256(scheme + value)` (avoid keeping the raw secret in memory). Negative entries cached for 10s only (don't persist failed credentials too long after a password change).

LRU eviction at 1024 entries. Process-local cache is fine for single-replica deployments; if we ever scale, replace with Redis.

### 5.3 Schema

Unchanged from Phase 2. `Document.user_id` and `Progress` are by-username strings.

### 5.4 Config

New env: `OPDS_SYNC_CWA_BASE_URL` (e.g. `http://calibre-web.calibre-web.svc.cluster.local:8083`).

Removed env: `OPDS_SYNC_AUTHENTIK_ISSUER`, `OPDS_SYNC_AUTHENTIK_AUDIENCE`.

### 5.5 Auth endpoint choice

Use `GET /opds` (CWA's OPDS root) as the validation probe. Reasons:
- It's already protected by `requires_basic_auth_if_no_ano`.
- Returns a small Atom XML body (~500 bytes) — cheap.
- No CWA-internal endpoint needed; same surface OPDS clients already hit.

Alternatives considered:
- `HEAD /opds` — CWA may not support HEAD on opds; not worth the test surface.
- `/api/me` or similar — CWA doesn't have one.

## 6. Client design (Android changes)

### 6.1 Files removed

- `auth/src/main/java/io/theficos/ereader/auth/AuthentikConfig.kt`
- `auth/src/main/java/io/theficos/ereader/auth/AuthentikAuthenticator.kt`
- `auth/src/main/java/io/theficos/ereader/auth/AuthState.kt`
- `auth/src/main/java/io/theficos/ereader/auth/AuthTokenStore.kt`
- `auth/src/main/java/io/theficos/ereader/auth/SyncAuthInterceptor.kt`
- `auth/src/test/java/io/theficos/ereader/auth/AuthTokenStoreTest.kt`
- `auth/src/test/java/io/theficos/ereader/auth/SyncAuthInterceptorTest.kt`
- `auth/src/test/java/io/theficos/ereader/auth/FakeAndroidKeyStore.kt`

`CalibreCredentialStoreTest.kt` keeps its `FakeAndroidKeyStore.setup()` `@Before` line — but since `FakeAndroidKeyStore.kt` is gone, either restore the helper or revert that test to not need it. Verify the existing test still passes without the fake provider before removing it; if it doesn't, keep `FakeAndroidKeyStore` as a one-test helper.

### 6.2 Files modified

- `auth/build.gradle.kts` — drop `appauth` dep + `manifestPlaceholders`.
- `data/sync/src/main/java/io/theficos/ereader/data/sync/SyncClient.kt` — no change in shape; the `OkHttpClient` it gets from `AppContainer` already has `BasicAuthInterceptor` attached.
- `data/sync/build.gradle.kts` — drop the `manifestPlaceholders` line.
- `data/opds/build.gradle.kts` — drop the `manifestPlaceholders` line.
- `app/build.gradle.kts` — drop `manifestPlaceholders["appAuthRedirectScheme"]`, drop `AUTHENTIK_*` and `SYNC_BASE_URL` BuildConfig fields. The sync URL is now derived from `CalibreCredentialStore.baseUrl` at runtime.
- `app/src/main/AndroidManifest.xml` — drop `RedirectUriReceiverActivity`.
- `app/src/main/java/io/theficos/ereader/di/AppContainer.kt` — remove Authentik wiring; the OPDS client's existing `OkHttpClient` (with `BasicAuthInterceptor`) is shared with `SyncClient`.
- `app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt` — drop sign-in / sign-out / `SyncUiState.account` / sync auth state. Keep `syncNow(context)` and a derived `lastSyncedAt`.
- `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt` — Sync card becomes: status row ("Configure calibre-web above to enable sync" if no creds; "Last synced 2 minutes ago" otherwise) + "Sync now" button (disabled when no creds).
- `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt` — drop `authStore`/`authenticator` args from `SettingsViewModel(...)` factory.

### 6.3 Files added

None.

### 6.4 Wire shape

`SyncClient` is changed to accept a base-URL provider rather than a fixed string:

```kotlin
class SyncClient(
    private val baseUrlProvider: () -> String?,
    private val okHttp: OkHttpClient,
    ...
)
```

If the provider returns null at call time (no creds saved), every method short-circuits to `SyncResult.Unauthorized`.

`AppContainer`:

```kotlin
private val opdsHttp = OpdsHttpClient(credentialStore)              // existing
val opdsClient = OpdsClient(opdsHttp.okHttp)                        // existing
val bookDownloader = BookDownloader(opdsHttp.okHttp, ...)           // existing
val syncClient = SyncClient(
    baseUrlProvider = { credentialStore.get()?.baseUrl },
    okHttp = opdsHttp.okHttp,                                       // SAME OkHttpClient as OPDS
)
val syncOrchestrator = SyncOrchestrator(syncClient, ...)            // unchanged
```

The `OkHttpClient` inside `OpdsHttpClient` already attaches Basic via `BasicAuthInterceptor`. Sharing it means sync requests are authenticated identically to OPDS requests, with zero new code. The base URL flows from the same `CalibreCredentialStore` the user already configures in Settings.

### 6.5 Sync triggers

Unchanged from Phase 2: library `LaunchedEffect`, reader `ON_PAUSE`, Settings → Sync now.

### 6.6 Empty-credentials behavior

If `CalibreCredentialStore.get() == null` when a sync runs:
- `BasicAuthInterceptor` doesn't attach a header.
- opds-sync returns 401.
- `SyncOrchestrator` returns `SyncResult.Unauthorized`.
- `SyncWorker` returns `Result.failure()` (no infinite retry).

Settings UI shows "Configure calibre-web above to enable sync" until creds are saved.

## 7. Deploy changes

### 7.1 Namespace and host (verified against `theficos-cluster/applications/calibre-web/`)

CWA in this cluster:
- Namespace: `calibre-web`
- Service: `calibre-web` (port 8083, target named `http`)
- Pod selector: `app: calibre-web`
- Ingress host: `ebooks.theficos.dedyn.io`

Move opds-sync into the `calibre-web` namespace. Concretely:
- `deploy/k8s/opds-sync/namespace.yaml` is **deleted** (use existing `calibre-web` namespace).
- `kustomization.yaml` sets `namespace: calibre-web`.
- opds-sync served under `https://ebooks.theficos.dedyn.io/sync/v1` (path-routed under CWA's existing host).

In-cluster URL for opds-sync → CWA: `http://calibre-web.calibre-web.svc.cluster.local:8083`.

### 7.2 Ingress

If option A: extend CWA's existing Ingress with a `path: /sync/` rule that routes to the opds-sync Service. CWA already serves at `/`; opds-sync mounts under `/sync/v1`. Both sit behind the same TLS cert.

### 7.3 Network policy

opds-sync needs egress to:
- CWA Service (port 8083).
- Postgres (its own).

Ingress to opds-sync only from Traefik (or whichever ingress controller).

### 7.4 Secrets

`opds-sync-secrets` shrinks: drop `authentik-issuer`, `authentik-audience`. Add nothing — `CWA_BASE_URL` lives in the deployment env directly (it's not sensitive).

## 8. Testing strategy

### 8.1 Server

Replace JWT-based test fixtures with `httpx.MockTransport` that pretends to be CWA:

```python
@pytest.fixture
def mock_cwa(monkeypatch):
    valid = {"alice": "alicepass", "bob": "bobpass"}
    def handler(request):
        auth = request.headers.get("authorization", "")
        # decode and check
        if matches: return httpx.Response(200, text="<feed/>")
        return httpx.Response(401)
    transport = httpx.MockTransport(handler)
    # Inject into CalibreAuthValidator's httpx client
```

The integration progress tests stay the same shape but use CWA Basic creds instead of JWTs.

### 8.2 Client

`OpdsHttpClient` test (existing) still exercises `BasicAuthInterceptor`. No new test files needed for the sync interceptor since we're reusing the same client.

`SyncClientTest` (existing MockWebServer-based) doesn't change — it tests the wire layer, not auth.

### 8.3 Identity parity

Unchanged. Kotlin ↔ Python parity tests for `metadata_id` normalization continue.

## 9. Migration / cleanup

This is a **breaking change** vs Phase 2's branch (`phase-2-progress-sync`). Two options:

- **A. Amend the branch.** Add a Phase 2.1 commit that does the rip-out before merging to main. Cleaner history.
- **B. New branch from main.** Cherry-pick the bits of Phase 2 we keep; redo the rest. Higher overhead.

Recommend **A**: the Phase 2 branch hasn't merged yet. A single `:bug: refactor: replace Authentik with CWA-proxy auth` commit (or a small series) on top of the existing branch keeps the history compact, and the squash on merge can collapse it to one feature commit.

## 10. Open questions

- **CWA CSRF / cookies:** does `GET /opds` ever reject Basic-only requests because of CSRF? Per the source we read, no — `requires_basic_auth_if_no_ano` is checked unconditionally. Test on a real CWA instance to confirm.
- **CWA admin-disabling-user lag:** if an admin disables a user in CWA, opds-sync's cache means up to 60s of continued access. Acceptable.
- **Rate limiting:** CWA has its own per-IP `limiter.check()` on failed Basic auth. opds-sync repeatedly probing CWA from the same source IP could trip this on bad creds. Negative-cache TTL of 10s helps; we may need to back off further if it bites.

## 11. Decision log (delta vs Phase 2)

| Item | Phase 2 | Phase 2.1 |
|---|---|---|
| Auth on opds-sync | Authentik JWT (OIDC PKCE) | HTTP Basic, validated by CWA |
| App's auth UI | Settings → Sync sign-in via Authentik | None (calibre-web form is the only auth) |
| Token storage | `AuthTokenStore` (Keystore) | None (just `CalibreCredentialStore`) |
| New ingress | `sync.theficos.dedyn.io` | `ebooks.theficos.dedyn.io/sync/v1` (path-routed under CWA) |
| Sync URL config | `BuildConfig.SYNC_BASE_URL` | Derived at runtime from `CalibreCredentialStore.baseUrl` |
| Authentik dep | Required (server prereq) | None |
| OIDC-only CWA users | Worked via OIDC PKCE | Need a local OPDS password (CWA caveat) |
