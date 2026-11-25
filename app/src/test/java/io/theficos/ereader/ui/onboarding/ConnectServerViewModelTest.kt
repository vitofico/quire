package io.theficos.ereader.ui.onboarding

import io.theficos.ereader.testing.awaitRequest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.data.opds.CatalogSyncClient
import io.theficos.ereader.data.opds.CatalogSyncRepository
import io.theficos.ereader.data.opds.ServerProbeResult
import io.theficos.ereader.ui.catalog.FakeAndroidKeyStore
import io.theficos.ereader.ui.catalog.useCatalog
import io.theficos.ereader.ui.englishContext
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification-and-save tests for [ConnectServerViewModel].
 *
 * The view model hops to an injected dispatcher for its blocking HTTP call.
 * The tests hand it the same [StandardTestDispatcher] that backs `Dispatchers.Main`,
 * so `advanceUntilIdle()` drives the whole flow to a terminal state with no
 * real-time waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ConnectServerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var store: CalibreCredentialStore
    private val viewModels = mutableListOf<ConnectServerViewModel>()

    @Before fun setUp() {
        FakeAndroidKeyStore.setup()
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        store = CalibreCredentialStore(context)
        store.clear()
    }

    @After fun tearDown() {
        try {
            runTest { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        } finally {
            runCatching { server.shutdown() }
            Dispatchers.resetMain()
        }
    }

    private fun newViewModel(
        legacyHighlightScopeFor: (AccountCredentials?, AccountCredentials.Bearer, String?) -> String? = { _, _, _ -> null },
    ): ConnectServerViewModel = ConnectServerViewModel(
        credentialStore = store,
        catalogs = CatalogSyncRepository(
            store,
            CatalogSyncClient({ (store.getAccount() as? AccountCredentials.Bearer)?.baseUrl }, OkHttpClient()),
        ) { _, _ -> },
        ioDispatcher = testDispatcher,
        legacyHighlightScopeFor = legacyHighlightScopeFor,
    ).also { viewModels += it }

    private fun feed(path: String) = ServerProbeResult.GenericOpds(server.url(path).toString(), requiresAuth = false)

    private fun calibre() = ServerProbeResult.Calibre(server.url("/").toString().trimEnd('/'))

    // ---------- issue #101 ----------

    private val navigationOnlyFeed =
        """<?xml version="1.0" encoding="utf-8"?>
           <feed xmlns="http://www.w3.org/2005/Atom">
             <title>Kavita</title>
             <entry><title>Libraries</title>
               <link rel="subsection" type="application/atom+xml;profile=opds-catalog;kind=navigation" href="/api/opds/KEY/libraries"/>
             </entry>
           </feed>"""

    private val emptyFeed =
        """<?xml version="1.0" encoding="utf-8"?>
           <feed xmlns="http://www.w3.org/2005/Atom"><title>Nothing</title></feed>"""

    @Test fun `a navigation-only catalog verifies and saves`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody(navigationOnlyFeed)
        )
        val vm = newViewModel()
        vm.saveCatalog(feed("/api/opds/KEY"), null, null)
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(ConnectServerViewModel.UiState.Completed)
        val saved = store.catalogAccount()!!
        assertThat(saved.baseUrl).isEqualTo(server.url("/api/opds/KEY").toString())
        assertThat(saved.username).isNull()
    }

    @Test fun `a catalog with credentials saves them`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody(navigationOnlyFeed)
        )
        val vm = newViewModel()
        vm.saveCatalog(feed("/feed"), "alice", "hunter2")
        advanceUntilIdle()

        val saved = store.catalogAccount()!!
        assertThat(saved.username).isEqualTo("alice")
        assertThat(saved.password).isEqualTo("hunter2")
        assertThat(server.awaitRequest().getHeader("Authorization")).isNotNull()
    }

    @Test fun `an empty feed is rejected`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody(emptyFeed)
        )
        val vm = newViewModel()
        vm.saveCatalog(feed("/feed"), null, null)
        advanceUntilIdle()

        assertThat(vm.state.value)
            .isInstanceOf(ConnectServerViewModel.UiState.VerificationFailed::class.java)
        assertThat(store.getAccount()).isNull()
    }

    @Test fun `a 401 is reported and nothing is saved`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val vm = newViewModel()
        vm.saveCatalog(feed("/feed"), "alice", "wrong")
        advanceUntilIdle()

        val state = vm.state.value as ConnectServerViewModel.UiState.VerificationFailed
        assertThat(state.message.asString(englishContext())).contains("rejected")
        assertThat(store.getAccount()).isNull()
    }

    @Test fun `a failure message never echoes the catalog url`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val vm = newViewModel()
        vm.saveCatalog(feed("/api/opds/SUPER-SECRET"), null, null)
        advanceUntilIdle()

        val state = vm.state.value as ConnectServerViewModel.UiState.VerificationFailed
        assertThat(state.message.asString(englishContext())).doesNotContain("SUPER-SECRET")
    }

    @Test fun `a 404 from the calibre verification suggests pasting the catalog url`() = runTest {
        // Komga's shape: Basic challenge everywhere, but no catalog at /opds.
        server.enqueue(MockResponse().setResponseCode(404))
        val vm = newViewModel()
        vm.saveCatalog(calibre(), "alice", "hunter2")
        advanceUntilIdle()

        val state = vm.state.value as ConnectServerViewModel.UiState.VerificationFailed
        assertThat(state.message.asString(englishContext())).contains("catalog URL")
        assertThat(state.message.asString(englishContext())).doesNotContain("Unexpected response")
        assertThat(store.getAccount()).isNull()
    }

    @Test fun `a Calibre-Web server becomes a catalog, not a sign-in`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/atom+xml").setBody(emptyFeed))
        val vm = newViewModel()
        vm.saveCatalog(calibre(), "alice", "hunter2")
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(ConnectServerViewModel.UiState.Completed)
        assertThat(store.getAccount()).isNull()
        val saved = store.catalogAccount()!!
        assertThat(saved.baseUrl).isEqualTo(server.url("/opds").toString())
        assertThat(saved.calibre).isTrue()
        assertThat(saved.username).isEqualTo("alice")
        assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo(Credentials.basic("alice", "hunter2"))
    }

    @Test fun `half a login is refused before any request`() = runTest {
        val vm = newViewModel()
        vm.saveCatalog(feed("/feed"), "alice", null)
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(
            ConnectServerViewModel.UiState.VerificationFailed(uiText(R.string.catalogs_failed_login_incomplete)),
        )
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(store.catalogs.value).isEmpty()
    }

    @Test fun `other unexpected codes keep the generic calibre message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(418))
        val vm = newViewModel()
        vm.saveCatalog(calibre(), "alice", "hunter2")
        advanceUntilIdle()

        val state = vm.state.value as ConnectServerViewModel.UiState.VerificationFailed
        assertThat(state.message.asString(englishContext())).contains("418")
    }
    @Test fun `pairing ticket exchanges once and saves a bearer session`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"token":"synthetic-device-token","account_id":"native:42","expires_at":"2027-01-01T00:00:00Z"}"""))
        val vm = newViewModel()
        vm.verifyAndSavePairing(server.url("/").toString().trimEnd('/'), "synthetic-ticket-1234567890")
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(ConnectServerViewModel.UiState.Completed)
        val saved = store.getAccount() as AccountCredentials.Bearer
        assertThat(saved.token).isEqualTo("synthetic-device-token")
        assertThat(saved.email).isEqualTo("native:42")
        val request = server.awaitRequest()
        assertThat(request.path).isEqualTo("/auth/v1/pairing/exchange")
        assertThat(request.body.readUtf8()).contains("synthetic-ticket-1234567890")
    }

    @Test fun `expired pairing ticket never replaces saved credentials`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val vm = newViewModel()
        vm.verifyAndSavePairing(server.url("/").toString().trimEnd('/'), "synthetic-expired-1234567890")
        advanceUntilIdle()
        val result = vm.state.value as ConnectServerViewModel.UiState.VerificationFailed
        assertThat(result.message.asString(englishContext())).contains("expired")
        assertThat(store.getAccount()).isNull()
    }

    @Test fun `native sign in keeps an independent OPDS catalog`() = runTest {
        val catalog = "https://books.example/reader/catalog"
        store.useCatalog(AccountCredentials.OpdsOnly(catalog, "alice", "catalog-password"))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"token":"quire-token","account_id":"native:42"}"""),
        )

        val vm = newViewModel()
        vm.verifyAndSaveBearer(server.url("/quire/").toString(), "alice@example.com", "quire-password")
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(ConnectServerViewModel.UiState.Completed)
        val saved = store.getAccount() as AccountCredentials.Bearer
        assertThat(saved.accountId).isEqualTo("native:42")
        assertThat(store.catalogAccount()?.baseUrl).isEqualTo(catalog)
        assertThat(store.catalogAccount()?.password).isEqualTo("catalog-password")
        assertThat(server.awaitRequest().path).isEqualTo("/quire/auth/v1/login")
    }

    private val boundIdentity =
        """{"user":{"id":"native:42","backend":"native","legacy_calibre_user_id":"alice"}}"""

    @Test fun `a legacy device whose reader already migrated signs in without typing anything`() = runTest {
        val serverUrl = server.url("/quire").toString()
        store.saveBasicAccount("https://books.example", "alice", "catalog-password", serverUrl)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"token":"claimed-token","account_id":"native:42","account_subject":"alice@example.com"}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(boundIdentity))
        val vm = newViewModel { _, _, _ -> "legacy-highlight-scope" }

        vm.claimLegacyAccount()
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(ConnectServerViewModel.UiState.Completed)
        val saved = store.getAccount() as AccountCredentials.Bearer
        assertThat(saved.token).isEqualTo("claimed-token")
        assertThat(saved.email).isEqualTo("alice@example.com")
        assertThat(store.catalogAccount()?.baseUrl).isEqualTo("https://books.example/opds")
        assertThat(saved.previousHighlightScopes).containsExactly("legacy-highlight-scope")
        val claim = server.awaitRequest()
        assertThat(claim.path).isEqualTo("/quire/auth/v1/migrate/claim")
        assertThat(claim.getHeader("Authorization")).isEqualTo(Credentials.basic("alice", "catalog-password"))
    }

    @Test fun `an unmigrated legacy reader is offered migration and keeps their data`() = runTest {
        val serverUrl = server.url("/quire").toString()
        store.saveBasicAccount("https://books.example", "alice", "catalog-password", serverUrl)
        val prior = store.getAccount()
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"not_migrated"}"""))
        val vm = newViewModel()

        vm.claimLegacyAccount()
        advanceUntilIdle()

        assertThat(vm.legacyMigration.value).isEqualTo(ConnectServerViewModel.LegacyMigration.OFFERED)
        assertThat(vm.state.value).isEqualTo(ConnectServerViewModel.UiState.Idle)
        assertThat(store.getAccount()).isEqualTo(prior)

        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"email_unavailable"}"""))
        vm.migrateLegacyAccount("taken@example.com", "a-long-new-password")
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(
            ConnectServerViewModel.UiState.VerificationFailed(uiText(R.string.quire_migration_email_unavailable)),
        )
        assertThat(store.getAccount()).isEqualTo(prior)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"token":"new-token","account_id":"native:42","account_subject":"alice@example.com"}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(boundIdentity))
        vm.migrateLegacyAccount(" alice@example.com ", "a-long-new-password")
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(ConnectServerViewModel.UiState.Completed)
        assertThat((store.getAccount() as AccountCredentials.Bearer).token).isEqualTo("new-token")
        server.awaitRequest()
        server.awaitRequest()
        val migrate = server.awaitRequest()
        assertThat(migrate.path).isEqualTo("/quire/auth/v1/migrate")
        assertThat(migrate.getHeader("Authorization")).isEqualTo(Credentials.basic("alice", "catalog-password"))
        assertThat(migrate.body.readUtf8()).contains("\"email\":\"alice@example.com\"")
    }

    @Test fun `a server without self-service migration leaves a legacy device alone`() = runTest {
        val serverUrl = server.url("/quire").toString()
        store.saveBasicAccount("https://books.example", "alice", "catalog-password", serverUrl)
        val prior = store.getAccount()
        val expected = mapOf(
            404 to ConnectServerViewModel.LegacyMigration.UNAVAILABLE,
            405 to ConnectServerViewModel.LegacyMigration.UNAVAILABLE,
            403 to ConnectServerViewModel.LegacyMigration.UNKNOWN,
            401 to ConnectServerViewModel.LegacyMigration.UNKNOWN,
            500 to ConnectServerViewModel.LegacyMigration.UNKNOWN,
        )
        for ((code, migration) in expected) {
            server.enqueue(MockResponse().setResponseCode(code))
            val vm = newViewModel()
            vm.claimLegacyAccount()
            advanceUntilIdle()
            assertThat(vm.legacyMigration.value).isEqualTo(migration)
            assertThat(vm.state.value).isEqualTo(ConnectServerViewModel.UiState.Idle)
        }
        assertThat(store.getAccount()).isEqualTo(prior)
    }

    @Test fun `same-server legacy sign in uses authenticated binding and keeps calibre web`() = runTest {
        val serverUrl = server.url("/quire").toString()
        val calibreUrl = "https://books.example"
        store.saveBasicAccount(calibreUrl, "alice", "catalog-password", serverUrl)
        val prior = store.getAccount()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"token":"quire-token","account_id":"native:42"}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"user":{"id":"native:42","backend":"native","legacy_calibre_user_id":"alice"}}"""),
        )
        val vm = newViewModel { previous, candidate, legacyId ->
            assertThat(previous).isEqualTo(prior)
            assertThat(candidate.accountId).isEqualTo("native:42")
            assertThat(legacyId).isEqualTo("alice")
            "legacy-highlight-scope"
        }

        vm.verifyAndSaveBearer(serverUrl, "alice@example.com", "quire-password")
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(ConnectServerViewModel.UiState.Completed)
        val saved = store.getAccount() as AccountCredentials.Bearer
        assertThat(store.catalogAccount()?.baseUrl).isEqualTo("$calibreUrl/opds")
        assertThat(store.catalogAccount()?.calibre).isTrue()
        assertThat(saved.previousHighlightScopes).containsExactly("legacy-highlight-scope")
        server.awaitRequest()
        val me = server.awaitRequest()
        assertThat(me.path).isEqualTo("/quire/auth/v1/me")
        assertThat(me.getHeader("Authorization")).isEqualTo("Bearer quire-token")
    }

    @Test fun `legacy identity check failure leaves the previous account available for retry`() = runTest {
        val serverUrl = server.url("/quire").toString()
        store.saveBasicAccount("https://books.example", "alice", "catalog-password", serverUrl)
        val prior = store.getAccount()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"token":"quire-token","account_id":"native:42"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(503))
        val vm = newViewModel { _, _, _ -> error("Adoption requires authenticated identity") }

        vm.verifyAndSaveBearer(serverUrl, "alice@example.com", "quire-password")
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(ConnectServerViewModel.UiState.VerificationFailed::class.java)
        assertThat(store.getAccount()).isEqualTo(prior)
    }

    @Test fun `unbound or differently bound native login keeps legacy sync connection`() = runTest {
        val serverUrl = server.url("/quire").toString()
        store.saveBasicAccount("https://books.example", "Alice", "catalog-password", serverUrl)
        val prior = store.getAccount()
        for (verifiedLegacyId in listOf("null", "\"another-reader\"")) {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"token":"quire-token","account_id":"native:42"}"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"user":{"id":"native:42","backend":"native","legacy_calibre_user_id":$verifiedLegacyId}}"""),
            )
            val vm = newViewModel { _, _, _ -> error("An unrelated account must leave local highlights untouched") }

            vm.verifyAndSaveBearer(serverUrl, "alice@example.com", "quire-password")
            advanceUntilIdle()

            assertThat(vm.state.value).isEqualTo(
                ConnectServerViewModel.UiState.VerificationFailed(uiText(R.string.onboarding_legacy_binding_required))
            )
            assertThat(store.getAccount()).isEqualTo(prior)
        }
    }

    @Test fun `reauthentication cannot replace the expired account with another user`() = runTest {
        val serverUrl = server.url("/quire").toString()
        store.saveBearerAccount(serverUrl, "alice@example.com", "old-token", accountId = "native:42")
        store.notifyUnauthorized()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"token":"other-token","account_id":"native:43"}"""),
        )
        val vm = newViewModel()

        assertThat(vm.isReauthentication).isTrue()
        vm.verifyAndSaveBearer(serverUrl, "other@example.com", "quire-password")
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(ConnectServerViewModel.UiState.VerificationFailed::class.java)
        assertThat((store.getAccount() as AccountCredentials.Bearer).token).isEqualTo("old-token")
    }

}
