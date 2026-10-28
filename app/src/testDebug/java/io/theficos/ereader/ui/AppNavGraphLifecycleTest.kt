package io.theficos.ereader.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.di.AppContainer
import io.theficos.ereader.ui.catalog.FakeAndroidKeyStore
import io.theficos.ereader.ui.onboarding.ConnectServerViewModel
import io.theficos.ereader.ui.theme.AppTheme
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AppNavGraphLifecycleTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var container: AppContainer
    private var server: MockWebServer? = null
    private lateinit var workManager: WorkManager

    @Before fun setUp() {
        // Plain Application keeps process startup jobs out of this navigation test;
        // the library's ambient sync still needs an initialized scheduler.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        workManager = try {
            WorkManager.getInstance(context)
        } catch (_: IllegalStateException) {
            WorkManager.initialize(context, Configuration.Builder().build())
            WorkManager.getInstance(context)
        }
    }

    @After fun tearDown() {
        try {
            if (::workManager.isInitialized) workManager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        } finally {
            if (::container.isInitialized) container.libraryUploaderScope.cancel()
            server?.shutdown()
        }
    }

    @Test fun `connection model survives a covered destination and clears when popped`() {
        FakeAndroidKeyStore.setup()
        container = AppContainer(ApplicationProvider.getApplicationContext()).also {
            it.credentialStore.clear()
            it.welcomePreferencesStore.reset()
        }
        lateinit var nav: NavHostController
        composeRule.setContent {
            nav = rememberNavController()
            AppNavGraph(container, AppTheme(), nav)
        }
        composeRule.runOnIdle { nav.navigate("connect-server") }
        composeRule.waitForIdle()
        lateinit var model: ConnectServerViewModel
        composeRule.runOnIdle {
            model = ViewModelProvider(nav.getBackStackEntry("connect-server"))[ConnectServerViewModel::class.java]
            nav.navigate("cloud-coming-soon")
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { nav.popBackStack() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertThat(ViewModelProvider(nav.getBackStackEntry("connect-server"))[ConnectServerViewModel::class.java])
                .isSameInstanceAs(model)
            assertThat(model.viewModelScope.coroutineContext[Job]?.isActive).isTrue()
            nav.popBackStack()
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertThat(model.viewModelScope.coroutineContext[Job]?.isActive).isFalse()
        }
    }
    @Test fun `successful reauthentication from a cold home removes the completed connection and old home`() {
        assertSuccessfulConnectionReplacesBackStack(startAtHome = true)
    }

    @Test fun `successful first connection removes welcome and the completed connection`() {
        assertSuccessfulConnectionReplacesBackStack(startAtHome = false)
    }

    private fun assertSuccessfulConnectionReplacesBackStack(startAtHome: Boolean) {
        val loginServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/auth/v1/login" -> MockResponse().setBody(
                        """{"token":"new-token","expires_at":"2099-01-01T00:00:00Z"}""",
                    )
                    "/opds" -> MockResponse().setBody(
                        """<feed xmlns="http://www.w3.org/2005/Atom"><id>urn:test</id><title>Catalog</title><updated>2026-10-28T00:00:00Z</updated></feed>""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        server = loginServer
        val baseUrl = loginServer.url("/").toString().trimEnd('/')
        FakeAndroidKeyStore.setup()
        container = AppContainer(ApplicationProvider.getApplicationContext()).also {
            it.credentialStore.clear()
            it.welcomePreferencesStore.reset()
            if (startAtHome) {
                it.credentialStore.saveBearerAccount(baseUrl, "reader@example.com", "old-token", null)
                it.welcomePreferencesStore.markCompleted()
            }
        }
        lateinit var nav: NavHostController
        composeRule.setContent {
            nav = rememberNavController()
            AppNavGraph(container, AppTheme(), nav)
        }
        var originalHomeId: String? = null
        composeRule.runOnIdle {
            if (startAtHome) {
                originalHomeId = nav.getBackStackEntry("home").id
                container.credentialStore.notifyUnauthorized()
            } else {
                nav.navigate("connect-server")
            }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.runOnIdle { nav.currentDestination?.route == "connect-server" }
        }
        lateinit var connection: ConnectServerViewModel
        composeRule.runOnIdle {
            connection = ViewModelProvider(nav.getBackStackEntry("connect-server"))[ConnectServerViewModel::class.java]
            connection.verifyAndSaveBearer(baseUrl, "reader@example.com", "password")
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.runOnIdle {
                val state = connection.state.value
                check(state !is ConnectServerViewModel.UiState.VerificationFailed) { "Login failed: $state" }
                nav.currentDestination?.route == "home"
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertThat(nav.previousBackStackEntry).isNull()
            if (startAtHome) assertThat(nav.getBackStackEntry("home").id).isNotEqualTo(originalHomeId)
            assertThat(connection.viewModelScope.coroutineContext[Job]?.isActive).isFalse()
            assertThat(container.welcomePreferencesStore.isCompleted()).isTrue()
            assertThat(container.credentialStore.needsReauth.value).isFalse()
        }
    }
}
