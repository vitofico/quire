# Phase 2.1 — CWA-as-source-of-truth Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Phase 2's Authentik OIDC + JWT auth with CWA-Basic auth proxied through opds-sync. Drop the Android app's SSO machinery; share the existing `OkHttpClient` (and its `BasicAuthInterceptor`) between OPDS and sync clients. Deploy opds-sync into CWA's namespace, path-routed under `ebooks.theficos.dedyn.io/sync/v1`.

**Architecture:** opds-sync's auth becomes a `CalibreAuthValidator` that forwards the incoming `Authorization: Basic` header to CWA's `/opds` and trusts CWA's verdict. A small TTL cache prevents CWA hammering. The Android app keeps `CalibreCredentialStore` + `BasicAuthInterceptor` and passes the same `OkHttpClient` to `SyncClient`. **`SyncClient` derives its base URL at call time from `CalibreCredentialStore.baseUrl`** — no separate BuildConfig field. AppAuth, `AuthentikAuthenticator`, `AuthTokenStore`, `SyncAuthInterceptor`, and the related Settings UI are deleted.

**Cluster details (verified against `theficos-cluster/applications/calibre-web/`):** namespace `calibre-web`, service `calibre-web:8083` (selector `app: calibre-web`), ingress host `ebooks.theficos.dedyn.io`. opds-sync co-deploys into `calibre-web` namespace and is path-routed under `ebooks.theficos.dedyn.io/sync/`.

**Tech Stack:**
- **Server:** existing FastAPI/SQLAlchemy stack. Adds `httpx.AsyncClient` for the CWA probe (already a runtime dep). Tests use `httpx.MockTransport` to fake CWA.
- **Android:** existing Kotlin stack, with deletions only. No new modules.
- **Cluster:** opds-sync moves into the CWA namespace (`calibre-web`); ingress extends CWA's existing host with a path rule.

**Spec:** `docs/superpowers/specs/2026-05-06-phase-2-1-cwa-auth-proxy.md`

**Branch:** `phase-2-progress-sync` (continuing on the same branch — Phase 2 hasn't merged).

---

## File structure (delta)

### Server

- **Modified:** `server/opds_sync/core/auth.py` — replaced. `CalibreAuthValidator` (httpx + TTL cache) and a `current_user_id` FastAPI dep that uses it. JWT/JWKS classes removed.
- **Modified:** `server/opds_sync/config.py` — `cwa_base_url` field added; `authentik_*` fields removed.
- **Modified:** `server/opds_sync/main.py` — instantiate validator with httpx + cwa_base_url; drop JWKS wiring.
- **Modified:** `server/pyproject.toml` — `pyjwt[crypto]` moved from runtime to dev deps (still used in tests). `cryptography` already dev-only.
- **Modified:** `server/tests/conftest.py` — fixtures: `mock_cwa_creds` (dict), `mock_cwa_transport` (httpx.MockTransport), `app_under_test` (uses mock validator).
- **Modified:** `server/tests/unit/test_auth.py` — replaced with tests for `CalibreAuthValidator`: cache hit, cache miss, 401 from CWA, 503 on CWA unavailable, negative-cache TTL.
- **Modified:** `server/tests/integration/test_progress.py` — Basic-auth `_basic(user, pass)` helper instead of `_bearer(token)`.
- **Modified:** `server/tests/integration/test_health.py` — drop the `cache_clear` dance for `authentik_*` env (no longer set).

### Android

- **Deleted:** `auth/src/main/java/io/theficos/ereader/auth/AuthentikConfig.kt`
- **Deleted:** `auth/src/main/java/io/theficos/ereader/auth/AuthentikAuthenticator.kt`
- **Deleted:** `auth/src/main/java/io/theficos/ereader/auth/AuthState.kt`
- **Deleted:** `auth/src/main/java/io/theficos/ereader/auth/AuthTokenStore.kt`
- **Deleted:** `auth/src/main/java/io/theficos/ereader/auth/SyncAuthInterceptor.kt`
- **Deleted:** `auth/src/test/java/io/theficos/ereader/auth/AuthTokenStoreTest.kt`
- **Deleted:** `auth/src/test/java/io/theficos/ereader/auth/SyncAuthInterceptorTest.kt`
- **Deleted:** `auth/src/test/java/io/theficos/ereader/auth/FakeAndroidKeyStore.kt`
- **Modified:** `auth/build.gradle.kts` — drop `appauth` dep, drop `manifestPlaceholders`, drop `okhttp` impl + `okhttp.mockwebserver` test dep (only the deleted `SyncAuthInterceptor` needed those).
- **Modified:** `auth/src/test/java/io/theficos/ereader/auth/CalibreCredentialStoreTest.kt` — revert the `@Before fun setUp() { FakeAndroidKeyStore.setup() }` line; the test passed without it before T10.
- **Modified:** `data/sync/build.gradle.kts` — drop `manifestPlaceholders`, drop `:auth` dep (no longer needed), drop `okhttp.mockwebserver` test (still needed for SyncClientTest — keep).
- **Modified:** `data/opds/build.gradle.kts` — drop `manifestPlaceholders["appAuthRedirectScheme"]`.
- **Modified:** `app/build.gradle.kts` — drop `manifestPlaceholders["appAuthRedirectScheme"]`, drop `AUTHENTIK_*` and `SYNC_BASE_URL` BuildConfig fields. The sync URL is derived at runtime from `CalibreCredentialStore.baseUrl`.
- **Modified:** `data/sync/src/main/java/io/theficos/ereader/data/sync/SyncClient.kt` — constructor takes `baseUrlProvider: () -> String?` instead of a fixed `String`. When the provider returns null, methods short-circuit to `SyncResult.Unauthorized`.
- **Modified:** `data/sync/src/test/java/io/theficos/ereader/data/sync/SyncClientTest.kt` — pass a `{ "<server-url>" }` lambda; add a "no creds → Unauthorized" case.
- **Modified:** `app/src/main/AndroidManifest.xml` — drop the `RedirectUriReceiverActivity` block. Keep the `xmlns:tools` declaration if still referenced elsewhere.
- **Modified:** `app/src/main/java/io/theficos/ereader/di/AppContainer.kt` — drop Authentik wiring (`AuthTokenStore`, `AuthentikAuthenticator`, `tokenProvider`, `syncOkHttp`, `AuthSnapshot`); pass `opdsHttp.okHttp` to `SyncClient`.
- **Modified:** `app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt` — drop sign-in/sign-out, drop `SyncUiState.account`; keep `syncNow(context)`, add `lastSyncedAt: StateFlow<Long?>` derived from `SyncStateDao`.
- **Modified:** `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt` — Sync card simplifies: status text + "Sync now" button.
- **Modified:** `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt` — `SettingsViewModel(...)` factory drops `authStore` + `authenticator` args.

### Cluster

- **Deleted:** `deploy/k8s/opds-sync/namespace.yaml` (use existing `calibre-web` namespace).
- **Modified:** `deploy/k8s/opds-sync/kustomization.yaml` — `namespace: calibre-web`; remove `namespace.yaml` from resources.
- **Modified:** `deploy/k8s/opds-sync/deployment.yaml` — drop `OPDS_SYNC_AUTHENTIK_ISSUER` and `OPDS_SYNC_AUTHENTIK_AUDIENCE` env, add `OPDS_SYNC_CWA_BASE_URL: http://calibre-web.calibre-web.svc.cluster.local:8083`.
- **Modified:** `deploy/k8s/opds-sync/secret.example.yaml` — drop `authentik-issuer`, `authentik-audience`.
- **Modified:** `deploy/k8s/opds-sync/ingress.yaml` — repurposed: matches `ebooks.theficos.dedyn.io` host with `path: /sync/`. (May be merged into the CWA Ingress instead — see Task 7.)
- **Modified:** `deploy/k8s/opds-sync/network-policies.yaml` — egress allows port 8083 to CWA pod label.
- **Modified:** `deploy/k8s/opds-sync/postgres-{statefulset,service,pvc}.yaml` — namespace is now `calibre-web` (one-line change each).
- **Modified:** `deploy/k8s/opds-sync/README.md` — drop Authentik prerequisite.
- **Modified:** `docs/operations/2026-05-05-phase-2-authentik-and-deploy.md` — superseded; either delete or replace with a CWA-co-deploy doc.

---

## Task 1: Strip Android `:auth` of Authentik machinery

**Files:**
- Delete: `auth/src/main/java/io/theficos/ereader/auth/AuthentikConfig.kt`
- Delete: `auth/src/main/java/io/theficos/ereader/auth/AuthentikAuthenticator.kt`
- Delete: `auth/src/main/java/io/theficos/ereader/auth/AuthState.kt`
- Delete: `auth/src/main/java/io/theficos/ereader/auth/AuthTokenStore.kt`
- Delete: `auth/src/main/java/io/theficos/ereader/auth/SyncAuthInterceptor.kt`
- Delete: `auth/src/test/java/io/theficos/ereader/auth/AuthTokenStoreTest.kt`
- Delete: `auth/src/test/java/io/theficos/ereader/auth/SyncAuthInterceptorTest.kt`
- Delete: `auth/src/test/java/io/theficos/ereader/auth/FakeAndroidKeyStore.kt`
- Modify: `auth/build.gradle.kts`
- Modify: `auth/src/test/java/io/theficos/ereader/auth/CalibreCredentialStoreTest.kt`

- [ ] **Step 1: Delete the 8 files**

```bash
rm auth/src/main/java/io/theficos/ereader/auth/AuthentikConfig.kt
rm auth/src/main/java/io/theficos/ereader/auth/AuthentikAuthenticator.kt
rm auth/src/main/java/io/theficos/ereader/auth/AuthState.kt
rm auth/src/main/java/io/theficos/ereader/auth/AuthTokenStore.kt
rm auth/src/main/java/io/theficos/ereader/auth/SyncAuthInterceptor.kt
rm auth/src/test/java/io/theficos/ereader/auth/AuthTokenStoreTest.kt
rm auth/src/test/java/io/theficos/ereader/auth/SyncAuthInterceptorTest.kt
rm auth/src/test/java/io/theficos/ereader/auth/FakeAndroidKeyStore.kt
```

- [ ] **Step 2: `auth/build.gradle.kts`** — drop `appauth`, `manifestPlaceholders`, `okhttp`, `okhttp.mockwebserver`. Final shape:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.theficos.ereader.auth"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 3: Revert `CalibreCredentialStoreTest.kt`** — drop the `@Before` line that referenced the deleted `FakeAndroidKeyStore`.

Read the current file. Remove:
```kotlin
import org.junit.Before
...
    @Before fun setUp() { FakeAndroidKeyStore.setup() }
```

- [ ] **Step 4: Verify**

```sh
./scripts/dgradle :auth:test
```

Expected: BUILD SUCCESSFUL with `CalibreCredentialStoreTest` (2 tests) green.

If the test fails (e.g., requires Robolectric AndroidKeyStore shadow), restore `FakeAndroidKeyStore.kt` and the `@Before` line — but this is unexpected since the test passed in Phase 1 without the helper. Verify by running `./scripts/dgradle :auth:test` on the parent commit before T10 (`bf7d425~1`).

- [ ] **Step 5: Drop `appauth` from version catalog**

Edit `gradle/libs.versions.toml`:
- Remove the `appauth = "0.11.1"` line from `[versions]`.
- Remove the `appauth = { module = "net.openid:appauth", version.ref = "appauth" }` line from `[libraries]`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m ":fire: refactor(auth): drop Authentik/AppAuth machinery

Phase 2.1 unifies auth on CWA Basic. AppAuth, the AuthentikAuthenticator,
AuthTokenStore, SyncAuthInterceptor, and FakeAndroidKeyStore are no
longer needed.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Drop Authentik from `:data:sync`, `:data:opds`, `:app`

**Files:**
- Modify: `data/sync/build.gradle.kts`
- Modify: `data/opds/build.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: `data/sync/build.gradle.kts`** — drop `manifestPlaceholders`, drop `:auth` dep (sync no longer needs auth machinery; the OkHttp interceptor lives in `:data:opds`).

Read the current file. Remove:
- The `manifestPlaceholders["appAuthRedirectScheme"] = "quire"` line in `defaultConfig`.
- The `implementation(project(":auth"))` line.

Verify final `dependencies {}`:
```kotlin
dependencies {
    api(project(":core:model"))
    api(project(":data:local"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
}
```

- [ ] **Step 2: `data/opds/build.gradle.kts`** — drop the `manifestPlaceholders` line (added in T21's CI fix to silence AppAuth's transitive manifest).

Read current file. Remove:
```kotlin
        manifestPlaceholders["appAuthRedirectScheme"] = "quire"
```

(`:data:opds` still depends on `:auth`, but `:auth` no longer pulls AppAuth, so the placeholder is no longer required.)

- [ ] **Step 3: `app/build.gradle.kts`** — drop the `manifestPlaceholders["appAuthRedirectScheme"]` line and **all three** BuildConfig fields (`SYNC_BASE_URL`, `AUTHENTIK_ISSUER`, `AUTHENTIK_CLIENT_ID`). The sync URL is now derived at runtime from `CalibreCredentialStore.baseUrl` (Task 3).

The `defaultConfig {}` block should keep only the existing pre-Phase-2 lines (applicationId, minSdk, etc.). Remove:

```kotlin
        manifestPlaceholders["appAuthRedirectScheme"] = "quire"
        buildConfigField("String", "SYNC_BASE_URL", "\"...\"")
        buildConfigField("String", "AUTHENTIK_ISSUER", "\"...\"")
        buildConfigField("String", "AUTHENTIK_CLIENT_ID", "\"...\"")
```

`buildFeatures { ... buildConfig = true }` can stay (cheap), or be removed if no other BuildConfig fields exist.

- [ ] **Step 4: `app/src/main/AndroidManifest.xml`** — remove the `<activity android:name="net.openid.appauth.RedirectUriReceiverActivity" ...>` block. Keep `xmlns:tools` if it's referenced anywhere else; otherwise it can also go.

- [ ] **Step 5: Verify**

```sh
./scripts/dgradle :app:assembleDebug :data:sync:test :data:opds:test
```

Expected: BUILD SUCCESSFUL. `:app` will fail to compile because `AppContainer` still references `AuthTokenStore` etc. — that's Task 3. Until Task 3 lands, run only library-module tests:

```sh
./scripts/dgradle :data:sync:test :data:opds:test :auth:test
```

Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add data/sync/build.gradle.kts data/opds/build.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml
git commit -m ":fire: refactor(build): drop appAuth manifest placeholders + AUTHENTIK BuildConfig

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Rewire `SyncClient` (URL provider), `AppContainer`, Settings UI

**Files:**
- Modify: `data/sync/src/main/java/io/theficos/ereader/data/sync/SyncClient.kt`
- Modify: `data/sync/src/test/java/io/theficos/ereader/data/sync/SyncClientTest.kt`
- Modify: `app/src/main/java/io/theficos/ereader/di/AppContainer.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`

- [ ] **Step 0a: Update `SyncClient.kt`** — accept a base-URL provider:

```kotlin
package io.theficos.ereader.data.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class SyncClient(
    private val baseUrlProvider: () -> String?,
    private val okHttp: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    suspend fun pushProgress(body: ProgressPushBody): SyncResult<ProgressPushResponse> {
        val base = baseUrlProvider() ?: return SyncResult.Unauthorized
        return post(base, SyncApi.PATH_PROGRESS_PUSH, body, ProgressPushBody.serializer(), ProgressPushResponse.serializer())
    }

    suspend fun pullProgress(sinceIso8601: String): SyncResult<ProgressPullResponse> {
        val base = baseUrlProvider() ?: return SyncResult.Unauthorized
        val url = (base.trimEnd('/') + SyncApi.PATH_PROGRESS_PULL).toHttpUrl()
            .newBuilder().addQueryParameter("since", sinceIso8601).build()
        val req = Request.Builder().url(url).get().build()
        return execute(req, ProgressPullResponse.serializer())
    }

    private fun <Req, Resp> post(
        baseUrl: String,
        path: String,
        body: Req,
        reqSerializer: KSerializer<Req>,
        respSerializer: KSerializer<Resp>,
    ): SyncResult<Resp> {
        val payload = json.encodeToString(reqSerializer, body)
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(payload)
            .build()
        return execute(req, respSerializer)
    }

    private fun <T> execute(req: Request, serializer: KSerializer<T>): SyncResult<T> = try {
        okHttp.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            when {
                resp.code == 401 -> SyncResult.Unauthorized
                resp.isSuccessful -> SyncResult.Success(json.decodeFromString(serializer, raw))
                else -> SyncResult.HttpFailure(resp.code, raw)
            }
        }
    } catch (e: IOException) {
        SyncResult.NetworkFailure(e)
    }
}
```

- [ ] **Step 0b: Update `SyncClientTest.kt`** — pass a lambda to the constructor; add a "no creds → Unauthorized" case.

Replace the `setUp()` block:

```kotlin
    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = SyncClient(
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            okHttp = OkHttpClient(),
        )
    }
```

Add a new test:

```kotlin
    @Test fun `null base url short-circuits to Unauthorized`() = runTest {
        val nullClient = SyncClient(baseUrlProvider = { null }, okHttp = OkHttpClient())
        val r = nullClient.pullProgress("2026-01-01T00:00:00Z")
        assertThat(r).isInstanceOf(SyncResult.Unauthorized::class.java)
        // No network call was made — server queue still empty
        assertThat(server.requestCount).isEqualTo(0)
    }
```

Verify:

```sh
./scripts/dgradle :data:sync:test
```

Expected: BUILD SUCCESSFUL with the new test included.

- [ ] **Step 1: Replace `AppContainer.kt`**

```kotlin
package io.theficos.ereader.di

import android.content.Context
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.opds.BookDownloader
import io.theficos.ereader.data.opds.OpdsClient
import io.theficos.ereader.data.opds.OpdsHttpClient
import io.theficos.ereader.data.sync.SyncClient
import io.theficos.ereader.data.sync.SyncDependencies
import io.theficos.ereader.data.sync.SyncOrchestrator
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import java.io.File

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val credentialStore: CalibreCredentialStore = CalibreCredentialStore(appContext)

    private val opdsHttp = OpdsHttpClient(credentialStore)
    val opdsClient: OpdsClient = OpdsClient(opdsHttp.okHttp)
    val bookDownloader: BookDownloader = BookDownloader(
        okHttp = opdsHttp.okHttp,
        booksDir = File(appContext.filesDir, "books"),
    )

    private val db: EReaderDatabase = EReaderDatabase.build(appContext)
    val documentRepository = DocumentRepository(db.documentDao())
    val progressRepository = ProgressRepository(db.progressDao())
    val syncStateDao = db.syncStateDao()
    val readiumFactory = ReadiumFactory(appContext)
    val readerPreferencesStore = ReaderPreferencesStore(appContext)

    val syncClient: SyncClient = SyncClient(
        baseUrlProvider = { credentialStore.get()?.baseUrl },
        okHttp = opdsHttp.okHttp,
    )
    val syncOrchestrator: SyncOrchestrator = SyncOrchestrator(
        client = syncClient,
        progressRepo = progressRepository,
        progressDao = db.progressDao(),
        documentRepo = documentRepository,
        syncState = syncStateDao,
    )

    init {
        SyncDependencies.holder = SyncDependencies.Holder(syncOrchestrator)
    }
}
```

(Notice: `AuthSnapshot` is gone, `authState()` is gone, the dedicated `syncOkHttp` is gone — `SyncClient` shares the OPDS client's interceptor stack.)

- [ ] **Step 2: Replace `SettingsViewModel.kt`**

```kotlin
package io.theficos.ereader.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.CalibreCredentials
import io.theficos.ereader.data.local.db.SyncStateDao
import io.theficos.ereader.data.sync.SyncEnqueuer
import io.theficos.ereader.reader.ReaderFontFamily
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SyncUiState(
    val hasCredentials: Boolean,
    val lastSyncedAtMs: Long?,
)

class SettingsViewModel(
    private val store: CalibreCredentialStore,
    private val readerStore: ReaderPreferencesStore,
    private val syncStateDao: SyncStateDao,
) : ViewModel() {
    private val _calibre = MutableStateFlow(loadInitialCalibre())
    val calibre: StateFlow<CalibreUiState> = _calibre.asStateFlow()

    val readerPreferences: StateFlow<ReaderPreferences> = readerStore.flow

    private val _sync = MutableStateFlow(loadInitialSync())
    val sync: StateFlow<SyncUiState> = _sync.asStateFlow()

    private fun loadInitialCalibre(): CalibreUiState {
        val creds = store.get()
        return CalibreUiState(
            baseUrl = creds?.baseUrl.orEmpty(),
            username = creds?.username.orEmpty(),
            password = creds?.password.orEmpty(),
            saved = creds != null,
        )
    }

    private fun loadInitialSync(): SyncUiState =
        SyncUiState(hasCredentials = store.get() != null, lastSyncedAtMs = null)

    fun onBaseUrlChange(value: String) { _calibre.value = _calibre.value.copy(baseUrl = value, saved = false) }
    fun onUsernameChange(value: String) { _calibre.value = _calibre.value.copy(username = value, saved = false) }
    fun onPasswordChange(value: String) { _calibre.value = _calibre.value.copy(password = value, saved = false) }

    fun saveCalibre() {
        val s = _calibre.value
        if (s.baseUrl.isBlank() || s.username.isBlank() || s.password.isBlank()) return
        viewModelScope.launch {
            store.put(CalibreCredentials(s.baseUrl.trim().trimEnd('/'), s.username, s.password))
            _calibre.value = s.copy(saved = true)
            _sync.value = _sync.value.copy(hasCredentials = true)
        }
    }

    fun setFontScale(value: Double) { readerStore.update { it.copy(fontScale = value.coerceIn(0.5, 2.0)) } }
    fun setTheme(theme: ReaderTheme) { readerStore.update { it.copy(theme = theme) } }
    fun setFontFamily(family: ReaderFontFamily) { readerStore.update { it.copy(fontFamily = family) } }
    fun setLineSpacing(value: Double) { readerStore.update { it.copy(lineSpacing = value.coerceIn(1.0, 1.8)) } }

    fun syncNow(context: Context) {
        if (!_sync.value.hasCredentials) return
        SyncEnqueuer.enqueue(context, expedited = true)
        viewModelScope.launch {
            // Best-effort: refresh last-synced from DB after the worker has a chance to run.
            // For Phase 2.1 we just bump UI state; a real "watching" version can use a Flow from SyncStateDao.
            val ts = syncStateDao.lastPulled("progress")
            if (ts != null) _sync.value = _sync.value.copy(lastSyncedAtMs = ts)
        }
    }
}

data class CalibreUiState(
    val baseUrl: String,
    val username: String,
    val password: String,
    val saved: Boolean,
)
```

- [ ] **Step 3: Replace the Sync section in `SettingsScreen.kt`**

Locate the existing "Sync" section (added in Phase 2 T18). Replace with:

```kotlin
        SectionLabel("Sync")
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            val syncState by viewModel.sync.collectAsState()
            val context = LocalContext.current

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!syncState.hasCredentials) {
                    Text(
                        "Configure calibre-web above to enable sync.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val ts = syncState.lastSyncedAtMs
                    Text(
                        if (ts == null) "Not synced yet" else "Last synced: ${formatRelative(ts)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    onClick = { viewModel.syncNow(context) },
                    enabled = syncState.hasCredentials,
                ) { Text("Sync now") }
            }
        }
```

Add this helper at the bottom of the file (private):

```kotlin
private fun formatRelative(epochMs: Long): String {
    val deltaSec = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        deltaSec < 60 -> "just now"
        deltaSec < 3600 -> "${deltaSec / 60}m ago"
        deltaSec < 86_400 -> "${deltaSec / 3600}h ago"
        else -> "${deltaSec / 86_400}d ago"
    }
}
```

Drop the `LocalContext`-only-used-by-launcher import if it's now redundant; keep `LocalContext` for `viewModel.syncNow(context)`. Drop:
- `import android.app.Activity`
- `import androidx.activity.compose.rememberLauncherForActivityResult`
- `import androidx.activity.result.contract.ActivityResultContracts`
- `import androidx.compose.material3.OutlinedButton`
- `import androidx.compose.runtime.rememberCoroutineScope`
- `import io.theficos.ereader.di.AuthSnapshot`
- `import kotlinx.coroutines.launch`

- [ ] **Step 4: Update `AppNavGraph.kt`**

Find the `SettingsViewModel(...)` factory call and replace with:

```kotlin
                SettingsViewModel(
                    store = appContainer.credentialStore,
                    readerStore = appContainer.readerPreferencesStore,
                    syncStateDao = appContainer.syncStateDao,
                )
```

(Drop `authStore` and `authenticator`; add `syncStateDao`.)

- [ ] **Step 5: Verify**

```sh
./scripts/dgradle :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

```sh
./scripts/dgradle test
```

Expected: all module tests green.

- [ ] **Step 6: Commit**

```bash
git add app
git commit -m ":sparkles: feat(app): single-credential sync UI (no Authentik)

AppContainer shares the OPDS OkHttpClient with SyncClient, so the same
BasicAuthInterceptor authenticates both. Settings → Sync becomes a
status row + "Sync now" button.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Replace server auth with `CalibreAuthValidator`

**Files:**
- Modify: `server/opds_sync/config.py`
- Modify: `server/opds_sync/core/auth.py` (full replace)
- Modify: `server/opds_sync/main.py`
- Modify: `server/pyproject.toml` (move `pyjwt[crypto]` to dev)

- [ ] **Step 1: `server/opds_sync/config.py`** — replace fields:

```python
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="OPDS_SYNC_", env_file=".env", extra="ignore")

    database_url: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/opds_sync"
    cwa_base_url: str = "http://calibre-web.calibre-web.svc.cluster.local:8083"
    cwa_probe_path: str = "/opds"
    cwa_probe_timeout_s: float = 3.0
    auth_cache_positive_ttl_s: int = 60
    auth_cache_negative_ttl_s: int = 10
    auth_cache_max_entries: int = 1024
    log_level: str = "INFO"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
```

- [ ] **Step 2: `server/opds_sync/core/auth.py`** — full replace:

```python
import base64
import hashlib
import logging
import time
from collections import OrderedDict
from typing import Annotated

import httpx
from fastapi import Depends, HTTPException, Request, status

logger = logging.getLogger(__name__)


class _CacheEntry:
    __slots__ = ("user_id", "is_valid", "expires_at")

    def __init__(self, user_id: str | None, is_valid: bool, expires_at: float) -> None:
        self.user_id = user_id
        self.is_valid = is_valid
        self.expires_at = expires_at


class CalibreAuthValidator:
    """Validates incoming Basic auth headers by probing CWA. TTL-cached."""

    def __init__(
        self,
        client: httpx.AsyncClient,
        cwa_base_url: str,
        probe_path: str = "/opds",
        positive_ttl_s: int = 60,
        negative_ttl_s: int = 10,
        max_entries: int = 1024,
        clock: callable = time.monotonic,
    ) -> None:
        self._client = client
        self._cwa = cwa_base_url.rstrip("/")
        self._probe_path = probe_path
        self._pos_ttl = positive_ttl_s
        self._neg_ttl = negative_ttl_s
        self._max = max_entries
        self._cache: OrderedDict[bytes, _CacheEntry] = OrderedDict()
        self._clock = clock

    async def validate(self, auth_header: str) -> str:
        """Returns the user_id (lowercased CWA username) or raises HTTPException(401)."""
        if not auth_header.lower().startswith("basic "):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="basic auth required")

        b64 = auth_header[6:].strip()
        key = hashlib.sha256(b64.encode("ascii")).digest()
        now = self._clock()

        cached = self._cache.get(key)
        if cached and cached.expires_at > now:
            self._cache.move_to_end(key)
            if cached.is_valid:
                return cached.user_id  # type: ignore[return-value]
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid credentials")

        # Cache miss or expired; probe CWA.
        try:
            resp = await self._client.get(
                f"{self._cwa}{self._probe_path}",
                headers={"Authorization": auth_header},
                follow_redirects=False,
            )
        except httpx.RequestError as e:
            logger.warning("upstream auth unavailable: %s", e)
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="upstream auth unavailable"
            ) from e

        if resp.status_code == 200:
            user_id = self._extract_username(b64)
            self._put(key, _CacheEntry(user_id, True, now + self._pos_ttl))
            return user_id
        if resp.status_code == 401:
            self._put(key, _CacheEntry(None, False, now + self._neg_ttl))
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid credentials"
            )
        # Other CWA responses (5xx, 403, etc.) — treat as transient; don't cache.
        logger.warning("CWA returned %s on auth probe", resp.status_code)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="upstream auth unavailable"
        )

    def _put(self, key: bytes, entry: _CacheEntry) -> None:
        self._cache[key] = entry
        self._cache.move_to_end(key)
        while len(self._cache) > self._max:
            self._cache.popitem(last=False)

    @staticmethod
    def _extract_username(b64_value: str) -> str:
        try:
            decoded = base64.b64decode(b64_value, validate=True).decode("utf-8", errors="strict")
        except (ValueError, UnicodeDecodeError) as e:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="malformed credentials"
            ) from e
        if ":" not in decoded:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="malformed credentials"
            )
        return decoded.split(":", 1)[0].lower()


async def get_validator(request: Request) -> CalibreAuthValidator:
    return request.app.state.auth_validator


async def current_user_id(
    request: Request,
    validator: Annotated[CalibreAuthValidator, Depends(get_validator)],
) -> str:
    auth = request.headers.get("authorization")
    if not auth:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="missing credentials")
    return await validator.validate(auth)
```

- [ ] **Step 3: `server/opds_sync/main.py`** — replace:

```python
import logging

import httpx
from fastapi import FastAPI

from opds_sync.api import health, progress
from opds_sync.config import get_settings
from opds_sync.core.auth import CalibreAuthValidator
from opds_sync.db.session import configure, make_engine


def create_app() -> FastAPI:
    settings = get_settings()
    logging.basicConfig(level=settings.log_level)

    configure(make_engine(settings.database_url))

    app = FastAPI(title="opds-sync", version="0.2.0")

    httpx_client = httpx.AsyncClient(timeout=settings.cwa_probe_timeout_s)
    app.state.httpx_client = httpx_client
    app.state.auth_validator = CalibreAuthValidator(
        client=httpx_client,
        cwa_base_url=settings.cwa_base_url,
        probe_path=settings.cwa_probe_path,
        positive_ttl_s=settings.auth_cache_positive_ttl_s,
        negative_ttl_s=settings.auth_cache_negative_ttl_s,
        max_entries=settings.auth_cache_max_entries,
    )

    @app.on_event("shutdown")
    async def _close():
        await httpx_client.aclose()

    app.include_router(health.router, prefix="/sync/v1")
    app.include_router(progress.router, prefix="/sync/v1")
    return app


app = create_app()
```

- [ ] **Step 4: `server/pyproject.toml`** — move `pyjwt[crypto]` from runtime to dev (still useful for tooling) or drop entirely. Drop entirely:

Locate `dependencies = [...]`. Remove the `"pyjwt[crypto]>=2.9",` line. (cryptography stays in dev.)

In `[project.optional-dependencies.dev]` no change unless `pyjwt` was already there — if it was, leave; otherwise no addition.

- [ ] **Step 5: Re-resolve venv**

```sh
cd server && rm -rf .venv && uv venv && uv pip install -e ".[dev]"
```

- [ ] **Step 6: Compile/import check**

```sh
cd server && uv run python -c "from opds_sync.main import create_app; app = create_app(); print(app.title, app.version)"
```

Expected: `opds-sync 0.2.0`. (Tests will be updated in Task 5; this just confirms imports.)

- [ ] **Step 7: Commit**

```bash
git add server/opds_sync server/pyproject.toml
git commit -m ":sparkles: feat(server): replace JWT auth with CWA-proxy validator

Drop Authentik/JWKS/PyJWT runtime dependency. opds-sync now forwards
the incoming Basic header to CWA's /opds and caches the verdict (60s
positive, 10s negative).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Update server tests for the new auth model

**Files:**
- Modify: `server/tests/conftest.py` — replace JWT fixtures with httpx MockTransport.
- Modify: `server/tests/unit/test_auth.py` — full rewrite for `CalibreAuthValidator`.
- Modify: `server/tests/integration/test_progress.py` — Basic auth helper.
- Modify: `server/tests/integration/test_health.py` — drop authentik env override.

- [ ] **Step 1: `server/tests/conftest.py`**

Full replace (preserves Postgres + alembic + ordering hook fixtures; replaces JWT fixtures):

```python
import base64
import time
from collections.abc import AsyncIterator, Iterator

import httpx
import pytest
from alembic import command
from alembic.config import Config as AlembicConfig
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from testcontainers.postgres import PostgresContainer


def pytest_collection_modifyitems(items):
    """Ensure test_schema runs before test_progress to avoid committed-row cross-pollution."""

    def _key(item):
        path = item.nodeid
        if "test_schema" in path:
            return 0
        if "test_progress" in path:
            return 1
        return 2

    items.sort(key=_key)


@pytest.fixture(scope="session")
def postgres_url() -> Iterator[str]:
    with PostgresContainer("postgres:16-alpine") as pg:
        sync_url = pg.get_connection_url()
        async_url = sync_url.replace("postgresql+psycopg2://", "postgresql+asyncpg://")
        yield async_url


@pytest.fixture(scope="session")
def alembic_upgrade(postgres_url: str) -> None:
    cfg = AlembicConfig("alembic.ini")
    cfg.set_main_option("sqlalchemy.url", postgres_url)
    command.upgrade(cfg, "head")


@pytest.fixture
async def engine(postgres_url: str, alembic_upgrade: None) -> AsyncIterator[AsyncEngine]:
    eng = create_async_engine(postgres_url, future=True)
    yield eng
    await eng.dispose()


@pytest.fixture
async def session(engine: AsyncEngine) -> AsyncIterator[AsyncSession]:
    factory = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
    async with factory() as s:
        yield s
        await s.rollback()


# ---- CWA mock --------------------------------------------------------------

@pytest.fixture
def cwa_users() -> dict[str, str]:
    """Mutable per-test dict of valid CWA username → password."""
    return {"alice": "alicepass", "bob": "bobpass"}


@pytest.fixture
def cwa_transport(cwa_users: dict[str, str]) -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        if not request.url.path.endswith("/opds"):
            return httpx.Response(404)
        auth = request.headers.get("authorization", "")
        if not auth.lower().startswith("basic "):
            return httpx.Response(401)
        try:
            decoded = base64.b64decode(auth[6:].strip()).decode("utf-8")
            user, pw = decoded.split(":", 1)
        except Exception:
            return httpx.Response(401)
        if cwa_users.get(user) == pw:
            return httpx.Response(200, text="<feed/>")
        return httpx.Response(401)

    return httpx.MockTransport(handler)


@pytest.fixture
def basic_header():
    def _make(user: str, pw: str) -> str:
        token = base64.b64encode(f"{user}:{pw}".encode()).decode("ascii")
        return f"Basic {token}"

    return _make


@pytest.fixture
def app_under_test(postgres_url, alembic_upgrade, monkeypatch, cwa_transport):
    """A FastAPI app wired to the test Postgres + a mock CWA transport."""
    monkeypatch.setenv("OPDS_SYNC_DATABASE_URL", postgres_url)
    monkeypatch.setenv("OPDS_SYNC_CWA_BASE_URL", "http://test-cwa")

    from opds_sync.config import get_settings
    get_settings.cache_clear()

    import httpx as _httpx
    from opds_sync.core.auth import CalibreAuthValidator
    from opds_sync.main import create_app

    app = create_app()
    # Replace the real httpx client with one bound to the mock transport.
    test_client = _httpx.AsyncClient(transport=cwa_transport, base_url="http://test-cwa", timeout=3.0)
    app.state.httpx_client = test_client
    app.state.auth_validator = CalibreAuthValidator(
        client=test_client,
        cwa_base_url="http://test-cwa",
    )
    return app
```

- [ ] **Step 2: `server/tests/unit/test_auth.py`** — full rewrite:

```python
import base64

import httpx
import pytest

from opds_sync.core.auth import CalibreAuthValidator


def _basic(user: str, pw: str) -> str:
    return "Basic " + base64.b64encode(f"{user}:{pw}".encode()).decode("ascii")


@pytest.fixture
def fake_clock():
    state = {"now": 1000.0}

    def clock() -> float:
        return state["now"]

    return state, clock


@pytest.fixture
def transport():
    calls = {"count": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["count"] += 1
        auth = request.headers.get("authorization", "")
        b64 = auth[6:]
        try:
            user, pw = base64.b64decode(b64).decode().split(":", 1)
        except Exception:
            return httpx.Response(401)
        if user == "alice" and pw == "alicepass":
            return httpx.Response(200, text="<feed/>")
        return httpx.Response(401)

    return httpx.MockTransport(handler), calls


async def _validator(transport, *, clock=None, pos_ttl=60, neg_ttl=10):
    client = httpx.AsyncClient(transport=transport, base_url="http://cwa")
    return CalibreAuthValidator(
        client=client,
        cwa_base_url="http://cwa",
        positive_ttl_s=pos_ttl,
        negative_ttl_s=neg_ttl,
        clock=clock or (lambda: 0.0),
    )


async def test_valid_creds_return_username(transport, fake_clock):
    state, clock = fake_clock
    t, _ = transport
    v = await _validator(t, clock=clock)
    assert (await v.validate(_basic("alice", "alicepass"))) == "alice"


async def test_invalid_creds_raise_401(transport, fake_clock):
    state, clock = fake_clock
    t, _ = transport
    v = await _validator(t, clock=clock)
    from fastapi import HTTPException
    with pytest.raises(HTTPException) as exc:
        await v.validate(_basic("alice", "wrong"))
    assert exc.value.status_code == 401


async def test_positive_cache_hit_avoids_cwa(transport, fake_clock):
    state, clock = fake_clock
    t, calls = transport
    v = await _validator(t, clock=clock, pos_ttl=60)
    await v.validate(_basic("alice", "alicepass"))
    await v.validate(_basic("alice", "alicepass"))
    await v.validate(_basic("alice", "alicepass"))
    assert calls["count"] == 1


async def test_positive_cache_expires(transport, fake_clock):
    state, clock = fake_clock
    t, calls = transport
    v = await _validator(t, clock=clock, pos_ttl=60)
    await v.validate(_basic("alice", "alicepass"))
    state["now"] += 61.0
    await v.validate(_basic("alice", "alicepass"))
    assert calls["count"] == 2


async def test_negative_cache_short_ttl(transport, fake_clock):
    state, clock = fake_clock
    t, calls = transport
    v = await _validator(t, clock=clock, neg_ttl=10)
    from fastapi import HTTPException
    for _ in range(3):
        with pytest.raises(HTTPException):
            await v.validate(_basic("alice", "wrong"))
    assert calls["count"] == 1
    state["now"] += 11.0
    with pytest.raises(HTTPException):
        await v.validate(_basic("alice", "wrong"))
    assert calls["count"] == 2


async def test_cwa_unreachable_returns_503(fake_clock):
    state, clock = fake_clock

    def boom(request):
        raise httpx.ConnectError("nope")

    t = httpx.MockTransport(boom)
    v = await _validator(t, clock=clock)
    from fastapi import HTTPException
    with pytest.raises(HTTPException) as exc:
        await v.validate(_basic("alice", "alicepass"))
    assert exc.value.status_code == 503


async def test_non_basic_header_rejected(fake_clock):
    state, clock = fake_clock
    t = httpx.MockTransport(lambda r: httpx.Response(200))
    v = await _validator(t, clock=clock)
    from fastapi import HTTPException
    with pytest.raises(HTTPException) as exc:
        await v.validate("Bearer xyz")
    assert exc.value.status_code == 401
```

- [ ] **Step 3: `server/tests/integration/test_progress.py`** — convert from `_bearer(token)` to `_basic(user, pw)`. Read the current file to find the helper definition; replace it and all callers:

```python
import base64

# Replace _bearer with _basic.
def _basic(user: str, pw: str) -> dict[str, str]:
    token = base64.b64encode(f"{user}:{pw}".encode()).decode("ascii")
    return {"Authorization": f"Basic {token}"}
```

Update each test:
- `_bearer(make_token("alice"))` → `_basic("alice", "alicepass")`
- `_bearer(make_token("bob"))` → `_basic("bob", "bobpass")`

The `app_under_test` and `cwa_users` fixtures from the new `conftest.py` provide alice/bob with passwords. Drop the now-unused `make_token` parameter from each test.

- [ ] **Step 4: `server/tests/integration/test_health.py`** — drop the authentik-issuer/audience env overrides. The healthz/readyz tests don't need them. Replace:

```python
async def test_healthz_returns_200(app_under_test):
    transport = ASGITransport(app=app_under_test)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        r = await client.get("/sync/v1/healthz")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


async def test_readyz_returns_200_when_db_reachable(app_under_test):
    transport = ASGITransport(app=app_under_test)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        r = await client.get("/sync/v1/readyz")
    assert r.status_code == 200
```

- [ ] **Step 5: Run the full server suite**

```sh
cd server && uv run pytest -v 2>&1 | tail -30
```

Expected: all tests pass. Count varies — at minimum the 2 identity + 1 schema + 2 health + 4 progress (= 9) plus the 7 new auth-validator tests = 16 tests.

If any test fails because of fixture ordering or stale `lru_cache`, ensure `get_settings.cache_clear()` is called in the fixture. The provided conftest already does this.

- [ ] **Step 6: Lint + format**

```sh
cd server && uv run ruff check . && uv run ruff format --check .
```

If format complains, run `uv run ruff format .` once and commit.

- [ ] **Step 7: Commit**

```bash
git add server/tests
git commit -m ":white_check_mark: test(server): CWA-proxy auth tests + Basic-auth integration

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Cluster manifests + ops doc

**Files:**
- Delete: `deploy/k8s/opds-sync/namespace.yaml`
- Modify: `deploy/k8s/opds-sync/kustomization.yaml`
- Modify: `deploy/k8s/opds-sync/deployment.yaml`
- Modify: `deploy/k8s/opds-sync/secret.example.yaml`
- Modify: `deploy/k8s/opds-sync/ingress.yaml`
- Modify: `deploy/k8s/opds-sync/network-policies.yaml`
- Modify: `deploy/k8s/opds-sync/postgres-statefulset.yaml`
- Modify: `deploy/k8s/opds-sync/postgres-service.yaml`
- Modify: `deploy/k8s/opds-sync/postgres-pvc.yaml`
- Modify: `deploy/k8s/opds-sync/service.yaml`
- Modify: `deploy/k8s/opds-sync/README.md`
- Replace: `docs/operations/2026-05-05-phase-2-authentik-and-deploy.md` with `2026-05-06-phase-2-1-cwa-codeploy.md`

- [ ] **Step 1: Delete `deploy/k8s/opds-sync/namespace.yaml`**

```sh
rm deploy/k8s/opds-sync/namespace.yaml
```

- [ ] **Step 2: `kustomization.yaml`** — set namespace to `calibre-web`, drop the deleted file from resources:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: calibre-web

resources:
  - postgres-pvc.yaml
  - postgres-service.yaml
  - postgres-statefulset.yaml
  - service.yaml
  - deployment.yaml
  - ingress.yaml
  - network-policies.yaml

images:
  - name: ghcr.io/REPLACE_OWNER/opds-sync
    newName: ghcr.io/vito/opds-sync
    newTag: latest
```

- [ ] **Step 3: All postgres + service files** — change `namespace: opds-sync` to `namespace: calibre-web`. Files: `postgres-pvc.yaml`, `postgres-service.yaml`, `postgres-statefulset.yaml`, `service.yaml`. (Kustomize would override these via `namespace: calibre-web` in the kustomization, but explicit is clearer.)

- [ ] **Step 4: `deployment.yaml`** — namespace + env update. Replace the env block of the `opds-sync` container:

```yaml
          env:
            - name: OPDS_SYNC_DATABASE_URL
              valueFrom: { secretKeyRef: { name: opds-sync-secrets, key: database-url } }
            - name: OPDS_SYNC_CWA_BASE_URL
              value: http://calibre-web.calibre-web.svc.cluster.local:8083
            - name: OPDS_SYNC_LOG_LEVEL
              value: INFO
```

(Drop `OPDS_SYNC_AUTHENTIK_ISSUER` and `OPDS_SYNC_AUTHENTIK_AUDIENCE`.)

Also update `metadata.namespace: calibre-web` and the init-container's `OPDS_SYNC_DATABASE_URL` reference (no change to that env, just confirm it's there).

- [ ] **Step 5: `secret.example.yaml`** — drop authentik keys + namespace:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: opds-sync-secrets
  namespace: calibre-web
type: Opaque
stringData:
  postgres-user: opds_sync
  postgres-password: REPLACE_ME
  database-url: postgresql+asyncpg://opds_sync:REPLACE_ME@postgres-opds-sync.calibre-web.svc.cluster.local:5432/opds_sync
```

(Note: postgres service name changes — the StatefulSet's service is in `calibre-web` namespace and probably collides with calibre-web's own postgres if any. Rename the StatefulSet's `serviceName` to `postgres-opds-sync` to avoid collision. Apply the matching change to `postgres-statefulset.yaml` `spec.serviceName`, `postgres-service.yaml` `metadata.name`, and the `database-url` host.)

- [ ] **Step 6: `postgres-statefulset.yaml`** + `postgres-service.yaml`** — rename to `postgres-opds-sync` to avoid name collision in CWA's namespace:

In `postgres-statefulset.yaml`: change `metadata.name: postgres` → `postgres-opds-sync`; change `spec.serviceName: postgres` → `postgres-opds-sync`; change `spec.selector.matchLabels.app: postgres` → `postgres-opds-sync`; change `spec.template.metadata.labels.app` accordingly.

In `postgres-service.yaml`: change `metadata.name: postgres` → `postgres-opds-sync`; change `spec.selector.app` → `postgres-opds-sync`.

- [ ] **Step 7: `network-policies.yaml`** — adapt to the namespace move. The `deny-all` no longer applies (CWA's namespace has its own policies). Replace with:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: opds-sync-allow
  namespace: calibre-web
spec:
  podSelector:
    matchLabels:
      app: opds-sync
  policyTypes: [Ingress, Egress]
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: traefik
      ports:
        - port: 8000
  egress:
    - to:
        - podSelector:
            matchLabels:
              app: postgres-opds-sync
      ports:
        - port: 5432
    # Talk to CWA in the same namespace.
    - to:
        - podSelector:
            matchLabels:
              app: calibre-web
      ports:
        - port: 8083
    # DNS
    - to: []
      ports:
        - port: 53
          protocol: UDP
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: postgres-opds-sync-allow
  namespace: calibre-web
spec:
  podSelector:
    matchLabels:
      app: postgres-opds-sync
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: opds-sync
      ports:
        - port: 5432
```

(Verify the actual CWA pod label — adjust `app: calibre-web` to whatever is in your existing CWA deploy.)

- [ ] **Step 8: `ingress.yaml`** — repurpose to path-route under CWA's host, OR drop in favor of merging into CWA's existing Ingress. Recommend the latter — simpler and avoids two Ingress objects on the same host. Replace contents with a comment if so:

```yaml
# opds-sync is served by the CWA Ingress on path /sync/. See
# applications/calibre-web/ingress.yaml in the cluster repo (or the local
# CWA deploy) — add a path rule:
#
#   - path: /sync/
#     pathType: Prefix
#     backend:
#       service:
#         name: opds-sync
#         port:
#           number: 80
#
# This file is intentionally a placeholder; remove from kustomization.yaml
# resources if you don't want a separate Ingress object.
```

If you'd rather keep a separate Ingress (simpler if you don't own the CWA Ingress), use a real Ingress with `host: ebooks.theficos.dedyn.io` and `path: /sync/`. But that conflicts with CWA's `path: /` Ingress unless they share a host with different paths and the cluster ingress controller permits that.

- [ ] **Step 9: `README.md`** — replace Authentik prerequisite with CWA prerequisite:

```markdown
# opds-sync deploy

Co-deployed with Calibre-Web-Automated in the `calibre-web` namespace.
opds-sync proxies user authentication to CWA.

## Prerequisites

- CWA running in the `calibre-web` namespace, exposing port 8083 to the
  `app: calibre-web` pod label.
- The Android client uses the same Basic credentials for CWA OPDS and
  for opds-sync; no separate sign-in.

## Apply order

1. Create the encrypted secret (SOPS) and apply it:

       cp secret.example.yaml secret.yaml
       sops --encrypt --in-place secret.yaml
       sops --decrypt secret.yaml | kubectl apply -f -

2. Apply Kustomize manifests:

       kubectl apply -k .

3. Verify:

       kubectl -n calibre-web rollout status deploy/opds-sync
       curl https://ebooks.theficos.dedyn.io/sync/v1/healthz

## Adding the path rule to CWA's Ingress

If using a single Ingress (recommended), add:

       - path: /sync/
         pathType: Prefix
         backend:
           service:
             name: opds-sync
             port:
               number: 80

to the existing CWA Ingress's `paths` list, before the catch-all `/` rule.
```

- [ ] **Step 10: Delete the old ops doc**

```sh
git rm docs/operations/2026-05-05-phase-2-authentik-and-deploy.md
```

- [ ] **Step 11: Write the new ops doc** at `docs/operations/2026-05-06-phase-2-1-cwa-codeploy.md`:

```markdown
# Phase 2.1 — CWA co-deploy & first opds-sync rollout

## 1. Prerequisites

- CWA running in the `calibre-web` namespace.
- A user account in CWA that has either:
  - a local password set, or
  - LDAP creds, or
  - OIDC linked **and** a local OPDS password set (CWA's own caveat —
    OPDS does not accept OIDC bearer tokens).

## 2. Build the server image

```sh
cd server
docker build -t ghcr.io/<owner>/opds-sync:$(git rev-parse --short HEAD) .
docker push ghcr.io/<owner>/opds-sync:<sha>
```

CI does this on `main` automatically.

## 3. Apply manifests

```sh
cd deploy/k8s/opds-sync
cp secret.example.yaml secret.yaml
sops --encrypt --in-place secret.yaml
sops --decrypt secret.yaml | kubectl apply -f -

kubectl apply -k .
kubectl -n calibre-web rollout status deploy/opds-sync
```

Add the `/sync/` path rule to CWA's Ingress (see `deploy/k8s/opds-sync/README.md`).

## 4. Verify

```sh
curl https://ebooks.theficos.dedyn.io/sync/v1/healthz
# {"status":"ok"}
curl -u alice:alicepass https://ebooks.theficos.dedyn.io/sync/v1/progress?since=2026-01-01T00:00:00Z
# {"items":[],"server_time":"..."}
curl -u alice:WRONG https://ebooks.theficos.dedyn.io/sync/v1/progress?since=2026-01-01T00:00:00Z
# 401
```

## 5. Wire the Android app

No BuildConfig changes needed. The app's `Settings → calibre-web` form is the
only auth surface. Enter `https://ebooks.theficos.dedyn.io` as the calibre-web
URL — `SyncClient` derives `<that-url>/sync/v1/...` automatically.
```

- [ ] **Step 12: Validate manifests**

```sh
kubectl kustomize deploy/k8s/opds-sync >/tmp/rendered.yaml && head -40 /tmp/rendered.yaml
```

Expected: rendered YAML, no Kustomize errors. Verify the namespace is `calibre-web` everywhere.

- [ ] **Step 13: Commit**

```bash
git add deploy/k8s/opds-sync docs/operations
git commit -m ":wrench: chore(deploy): co-deploy opds-sync with CWA, drop Authentik

Manifests move into the calibre-web namespace; opds-sync is path-routed
under CWA's existing host. Operations doc rewritten.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: End-to-end verification

**No code changes.** Verify the rebuilt branch.

- [ ] **Step 1: Full Android suite + assemble**

```sh
./scripts/dgradle test assembleDebug
```

Expected: BUILD SUCCESSFUL with all tests green.

- [ ] **Step 2: Full server suite**

```sh
cd server && uv run pytest -v
```

Expected: all tests pass.

- [ ] **Step 3: Lint + format**

```sh
cd server && uv run ruff check . && uv run ruff format --check .
```

- [ ] **Step 4: Push the branch and watch CI**

```sh
git push
gh run watch $(gh run list --branch phase-2-progress-sync --limit 1 --json databaseId -q '.[0].databaseId') --exit-status
```

Expected: CI green.

- [ ] **Step 5: Manual device test (you do this)**

1. `./scripts/dgradle :app:installDebug`
2. Open Settings → calibre-web. Enter URL (`https://ebooks.theficos.dedyn.io`), CWA username, CWA password. Save.
3. Library tab loads books from CWA OPDS.
4. Open a book, advance ~10%, close.
5. On a second device (or fresh install) with the same creds, open Library — progress sync brings the position over.
6. Verify "Sync now" in Settings produces `Last synced: just now`.

If steps 1–4 work, Phase 2.1 ships.

- [ ] **Step 6: Tag (optional)**

```sh
git tag -a phase-2.1 -m "Phase 2.1: CWA-as-source-of-truth auth"
```

---

## Self-review

### Spec coverage

- §1 single-credential, opds-sync defers to CWA: Tasks 4, 5.
- §1 no SSO dependency: Tasks 1, 2, 3 (Android rip-out), Task 4 (server).
- §1 future replacement-friendly: covered by `cwa_base_url` + `cwa_probe_path` config.
- §5.1 auth flow: Task 4.
- §5.2 cache: Task 4 + Task 5 unit tests.
- §6 client design: Tasks 1, 2, 3.
- §7 deploy: Task 6.
- §8 testing: Task 5.
- §9 migration on same branch: implicit — all tasks commit on `phase-2-progress-sync`.

### Placeholder scan

- All steps contain actual code.
- One ambiguity: Task 6 Step 8 (Ingress merge) requires the user to know the existing CWA Ingress object — flagged, not a code placeholder.

### Type/method consistency

- `CalibreAuthValidator` defined in Task 4, used in Task 5 tests.
- `app.state.auth_validator` set in `main.py` (Task 4) and overridden in `app_under_test` (Task 5 conftest).
- `SettingsViewModel(store, readerStore, syncStateDao)` defined in Task 3, called in Task 3 Step 4.
- `SyncEnqueuer.enqueue(context, expedited = true)` unchanged from Phase 2; the worker still uses `SyncDependencies.holder` set in `AppContainer`.

### Scope check

Single phase, one branch, ~60 minute total subagent time. No decomposition needed.
