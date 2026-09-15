package io.theficos.ereader.ui.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.ui.catalog.FakeAndroidKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
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

    @Before fun setUp() {
        FakeAndroidKeyStore.setup()
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        store = CalibreCredentialStore(context)
        store.clear()
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
        Dispatchers.resetMain()
    }

    private fun newViewModel(): ConnectServerViewModel = ConnectServerViewModel(
        credentialStore = store,
        ioDispatcher = testDispatcher,
    )

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
        vm.verifyAndSaveOpds(server.url("/api/opds/KEY").toString(), null, null)
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(ConnectServerViewModel.UiState.Completed)
        val saved = store.getAccount() as AccountCredentials.OpdsOnly
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
        vm.verifyAndSaveOpds(server.url("/feed").toString(), "alice", "hunter2")
        advanceUntilIdle()

        val saved = store.getAccount() as AccountCredentials.OpdsOnly
        assertThat(saved.username).isEqualTo("alice")
        assertThat(saved.password).isEqualTo("hunter2")
        assertThat(server.takeRequest().getHeader("Authorization")).isNotNull()
    }

    @Test fun `an empty feed is rejected`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody(emptyFeed)
        )
        val vm = newViewModel()
        vm.verifyAndSaveOpds(server.url("/feed").toString(), null, null)
        advanceUntilIdle()

        assertThat(vm.state.value)
            .isInstanceOf(ConnectServerViewModel.UiState.VerificationFailed::class.java)
        assertThat(store.getAccount()).isNull()
    }

    @Test fun `a 401 is reported and nothing is saved`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val vm = newViewModel()
        vm.verifyAndSaveOpds(server.url("/feed").toString(), "alice", "wrong")
        advanceUntilIdle()

        val state = vm.state.value as ConnectServerViewModel.UiState.VerificationFailed
        assertThat(state.message).contains("rejected")
        assertThat(store.getAccount()).isNull()
    }

    @Test fun `a failure message never echoes the catalog url`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val vm = newViewModel()
        vm.verifyAndSaveOpds(server.url("/api/opds/SUPER-SECRET").toString(), null, null)
        advanceUntilIdle()

        val state = vm.state.value as ConnectServerViewModel.UiState.VerificationFailed
        assertThat(state.message).doesNotContain("SUPER-SECRET")
    }

    @Test fun `a 404 from the calibre verification suggests pasting the catalog url`() = runTest {
        // Komga's shape: Basic challenge everywhere, but no catalog at /opds.
        server.enqueue(MockResponse().setResponseCode(404))
        val vm = newViewModel()
        vm.verifyAndSaveBasic(server.url("/").toString().trimEnd('/'), "alice", "hunter2")
        advanceUntilIdle()

        val state = vm.state.value as ConnectServerViewModel.UiState.VerificationFailed
        assertThat(state.message).contains("catalog URL")
        assertThat(state.message).doesNotContain("Unexpected response")
        assertThat(store.getAccount()).isNull()
    }

    @Test fun `other unexpected codes keep the generic calibre message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(418))
        val vm = newViewModel()
        vm.verifyAndSaveBasic(server.url("/").toString().trimEnd('/'), "alice", "hunter2")
        advanceUntilIdle()

        val state = vm.state.value as ConnectServerViewModel.UiState.VerificationFailed
        assertThat(state.message).contains("418")
    }
}
