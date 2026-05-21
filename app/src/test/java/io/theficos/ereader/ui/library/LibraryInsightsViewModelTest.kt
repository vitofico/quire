package io.theficos.ereader.ui.library

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.ai.AiClient
import io.theficos.ereader.data.ai.AiConfig
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.ai.FakeInsightDao
import io.theficos.ereader.data.ai.FakeProgressDao
import io.theficos.ereader.data.library.LibraryClient
import io.theficos.ereader.data.local.db.ProgressEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * PR-γ unit tests for [LibraryInsightsViewModel].
 *
 * These tests drive a real MockWebServer over real network sockets via the
 * production AiClient + LibraryClient (no mocking of suspend functions). We
 * therefore use `runBlocking` with a real Main dispatcher and poll the
 * [LibraryInsightsViewModel.state] flow until it settles. `StandardTest`
 * dispatchers don't wait for I/O completion on background threads, so they're
 * a poor fit here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryInsightsViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var aiClient: AiClient
    private lateinit var libraryClient: LibraryClient
    private lateinit var repo: AiRepository
    private lateinit var insightDao: FakeInsightDao
    private lateinit var progressDao: FakeProgressDao

    @Before fun setUp() {
        // Bind Main to a real dispatcher so viewModelScope.launch executes.
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.start()
        val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        aiClient = AiClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = ok,
        )
        libraryClient = LibraryClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = ok,
        )
        insightDao = FakeInsightDao()
        progressDao = FakeProgressDao()
        repo = AiRepository(
            client = aiClient,
            insightDao = insightDao,
            progressDao = progressDao,
        )
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        runCatching { server.shutdown() }
    }

    @Test fun `Disabled AiOff when aiEnabled is false`() = runBlocking {
        val vm = LibraryInsightsViewModel(
            ai = repo,
            libraryClient = libraryClient,
            progressDao = progressDao,
            aiConfigFlow = MutableStateFlow(configured()),
            aiEnabledFlow = MutableStateFlow(false),
        )
        vm.reload()
        val s = awaitTerminal(vm)
        assertThat(s).isEqualTo(LibraryInsightsUiState.Disabled.AiOff)
    }

    @Test fun `Disabled AiOff when config is missing`() = runBlocking {
        val vm = LibraryInsightsViewModel(
            ai = repo,
            libraryClient = libraryClient,
            progressDao = progressDao,
            aiConfigFlow = MutableStateFlow(null),
            aiEnabledFlow = MutableStateFlow(true),
        )
        vm.reload()
        val s = awaitTerminal(vm)
        assertThat(s).isEqualTo(LibraryInsightsUiState.Disabled.AiOff)
    }

    @Test fun `Disabled ProgressUnsupported when config flag is false`() = runBlocking {
        val vm = LibraryInsightsViewModel(
            ai = repo,
            libraryClient = libraryClient,
            progressDao = progressDao,
            aiConfigFlow = MutableStateFlow(configured().copy(progressSupported = false)),
            aiEnabledFlow = MutableStateFlow(true),
        )
        vm.reload()
        val s = awaitTerminal(vm)
        assertThat(s).isEqualTo(LibraryInsightsUiState.Disabled.ProgressUnsupported)
    }

    @Test fun `Empty state when profile is 404 and stats load`() = runBlocking {
        server.dispatcher = pathDispatcher(
            "/ai/v1/profile" to { MockResponse().setResponseCode(404).setBody("""{"detail":"no_profile"}""") },
            "/library/v1/stats" to { MockResponse().setResponseCode(200).setBody(STATS_JSON) },
        )
        val vm = newVm()
        vm.reload()
        val s = awaitTerminal(vm)
        assertThat(s).isInstanceOf(LibraryInsightsUiState.Empty::class.java)
        val empty = s as LibraryInsightsUiState.Empty
        assertThat(empty.statsPreview).isNotNull()
        assertThat(empty.statsPreview!!.totalBooks).isEqualTo(12)
        assertThat(empty.statsPreview!!.finished).isEqualTo(5)
    }

    @Test fun `Loaded state when profile is 200`() = runBlocking {
        server.dispatcher = pathDispatcher(
            "/ai/v1/profile" to { MockResponse().setResponseCode(200).setBody(PROFILE_JSON) },
            "/library/v1/stats" to { MockResponse().setResponseCode(200).setBody(STATS_JSON) },
        )
        val vm = newVm()
        vm.reload()
        val s = awaitTerminal(vm) as LibraryInsightsUiState.Loaded
        assertThat(s.profile.payload.stats.finishedCount).isEqualTo(3)
        assertThat(s.profile.payload.inLibraryRecommendations).hasSize(1)
        assertThat(s.profile.payload.discoveryRecommendations).hasSize(1)
    }

    @Test fun `local fingerprint matches when inputs equal server recipe`() = runBlocking {
        progressDao.seed(progressRow(updatedAt = 1_000L))
        // Canonical recipe: epoch millis (or "none") — must agree with the
        // Python-side `_compute_input_fingerprint`. Using ISO-8601 here
        // would diverge: Python emits `+00:00`, Java emits `Z`.
        val seed = "3|2|1|1000|12|7"
        val expectedFp = sha256Hex(seed).take(16)
        server.dispatcher = pathDispatcher(
            "/ai/v1/profile" to { MockResponse().setResponseCode(200).setBody(profileJsonWithFp(expectedFp)) },
            "/library/v1/stats" to { MockResponse().setResponseCode(200).setBody(STATS_JSON) },
        )
        val vm = newVm()
        vm.reload()
        val s = awaitTerminal(vm) as LibraryInsightsUiState.Loaded
        assertThat(s.stale).isFalse()
    }

    @Test fun `local fingerprint uses none token when no progress rows exist`() = runBlocking {
        // progressDao is empty — maxUpdatedAt() returns null, so the
        // canonical token MUST be the literal "none" (NOT "" or "null").
        val seed = "3|2|1|none|12|7"
        val expectedFp = sha256Hex(seed).take(16)
        server.dispatcher = pathDispatcher(
            "/ai/v1/profile" to { MockResponse().setResponseCode(200).setBody(profileJsonWithFp(expectedFp)) },
            "/library/v1/stats" to { MockResponse().setResponseCode(200).setBody(STATS_JSON) },
        )
        val vm = newVm()
        vm.reload()
        val s = awaitTerminal(vm) as LibraryInsightsUiState.Loaded
        assertThat(s.stale).isFalse()
    }

    @Test fun `local fingerprint mismatch produces stale=true`() = runBlocking {
        progressDao.seed(progressRow(updatedAt = 1_000L))
        server.dispatcher = pathDispatcher(
            "/ai/v1/profile" to { MockResponse().setResponseCode(200).setBody(profileJsonWithFp("deadbeefdeadbeef")) },
            "/library/v1/stats" to { MockResponse().setResponseCode(200).setBody(STATS_JSON) },
        )
        val vm = newVm()
        vm.reload()
        val s = awaitTerminal(vm) as LibraryInsightsUiState.Loaded
        assertThat(s.stale).isTrue()
    }

    @Test fun `null server fingerprint never reports stale`() = runBlocking {
        server.dispatcher = pathDispatcher(
            "/ai/v1/profile" to { MockResponse().setResponseCode(200).setBody(PROFILE_JSON) },
            "/library/v1/stats" to { MockResponse().setResponseCode(200).setBody(STATS_JSON) },
        )
        val vm = newVm()
        vm.reload()
        val s = awaitTerminal(vm) as LibraryInsightsUiState.Loaded
        assertThat(s.stale).isFalse()
    }

    @Test fun `mapErrorToState routes AiHttpException 409 to OptedOut`() = runBlocking {
        val vm = newVm()
        vm.mapErrorToState(io.theficos.ereader.data.ai.AiHttpException(409, "ai_not_opted_in"))
        assertThat(vm.state.value).isEqualTo(LibraryInsightsUiState.Disabled.OptedOut)
    }

    @Test fun `409 on refresh maps to Disabled OptedOut`() = runBlocking {
        server.dispatcher = pathDispatcher(
            "/ai/v1/profile" to { MockResponse().setResponseCode(404) },
            "/library/v1/stats" to { MockResponse().setResponseCode(200).setBody(STATS_JSON) },
            "/ai/v1/profile/refresh" to {
                MockResponse().setResponseCode(409).setBody("""{"detail":"ai_not_opted_in"}""")
            },
        )
        val vm = newVm()
        vm.reload()
        awaitState<LibraryInsightsUiState.Empty>(vm)
        vm.refresh()
        val s = awaitTerminal(vm)
        assertThat(s).isEqualTo(LibraryInsightsUiState.Disabled.OptedOut)
    }

    @Test fun `429 on refresh maps to RateLimit not retryable`() = runBlocking {
        server.dispatcher = pathDispatcher(
            "/ai/v1/profile" to { MockResponse().setResponseCode(404) },
            "/library/v1/stats" to { MockResponse().setResponseCode(200).setBody(STATS_JSON) },
            "/ai/v1/profile/refresh" to {
                MockResponse().setResponseCode(429).setBody(
                    """{"detail":{"used":5,"limit":5,"resets_at":"2026-05-22T00:00:00Z"}}""",
                )
            },
        )
        val vm = newVm()
        vm.reload()
        awaitState<LibraryInsightsUiState.Empty>(vm)
        vm.refresh()
        val s = awaitTerminal(vm)
        assertThat(s).isInstanceOf(LibraryInsightsUiState.Error.RateLimit::class.java)
        assertThat((s as LibraryInsightsUiState.Error.RateLimit).retryable).isFalse()
    }

    // ---------- helpers ----------

    private fun newVm() = LibraryInsightsViewModel(
        ai = repo,
        libraryClient = libraryClient,
        progressDao = progressDao,
        aiConfigFlow = MutableStateFlow(configured()),
        aiEnabledFlow = MutableStateFlow(true),
    )

    private fun configured(): AiConfig = AiConfig(
        configured = true,
        baseUrlHost = "ai.example",
        modelId = "test-model",
        sourcesEnabled = emptyList(),
        dailyBudget = 10,
        regenDailyLimit = 3,
        promptVersion = "1",
        progressSupported = true,
    )

    private fun progressRow(updatedAt: Long) = ProgressEntity(
        id = 0L,
        documentId = 1L,
        locator = "",
        percent = 0.0,
        updatedAt = updatedAt,
        localUpdatedAt = updatedAt,
        syncedAt = updatedAt,
        finishedAt = null,
        abandonedAt = null,
    )

    /** Wait until the VM state is terminal (anything except Loading). */
    private suspend fun awaitTerminal(vm: LibraryInsightsViewModel): LibraryInsightsUiState =
        withTimeout(5_000) {
            vm.state.first { it !is LibraryInsightsUiState.Loading }
        }

    private suspend inline fun <reified T : LibraryInsightsUiState> awaitState(
        vm: LibraryInsightsViewModel,
    ): T = withTimeout(5_000) {
        vm.state.first { it is T } as T
    }

    private fun pathDispatcher(
        vararg routes: Pair<String, () -> MockResponse>,
    ): okhttp3.mockwebserver.Dispatcher {
        // Longest prefix wins so `/ai/v1/profile/refresh` isn't shadowed by
        // `/ai/v1/profile`.
        val sorted = routes.sortedByDescending { it.first.length }
        return object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path ?: return MockResponse().setResponseCode(500)
                for ((prefix, factory) in sorted) {
                    if (path.startsWith(prefix)) return factory()
                }
                return MockResponse().setResponseCode(500).setBody("unexpected path=$path")
            }
        }
    }

    private companion object {
        const val STATS_JSON = """
            {
                "total_books": 12,
                "finished_count": 5,
                "in_progress_count": 2,
                "top_authors": [{"name":"Le Guin","count":4}],
                "top_themes": [],
                "themes_caveat": "based on insights"
            }
        """

        const val PROFILE_JSON = """
            {
                "payload": {
                    "schema_version": 1,
                    "stats": {
                        "total_books": 12,
                        "finished_count": 3,
                        "in_progress_count": 2,
                        "abandoned_count": 1,
                        "books_with_themes_count": 7
                    },
                    "narrative": "A vivid reader",
                    "in_library_recommendations": [
                        {
                            "title": "The Dispossessed",
                            "author": "Le Guin",
                            "identity": {"metadata_id": null, "content_hash": "abc"},
                            "source_type": "in_library",
                            "owned_state": "owned_unread",
                            "rationale": "You finished similar"
                        }
                    ],
                    "discovery_recommendations": [
                        {
                            "title": "Solaris",
                            "author": "Lem",
                            "source_type": "discovery_openlibrary",
                            "source_url": "https://openlibrary.org/works/OL1W",
                            "owned_state": "not_owned",
                            "rationale": "Adjacent author"
                        }
                    ],
                    "confidence": "high"
                },
                "schema_version": 1,
                "model_id": "test-model",
                "prompt_version": "1",
                "generated_at": "2026-05-20T10:00:00Z"
            }
        """

        fun profileJsonWithFp(fp: String): String = """
            {
                "payload": {
                    "schema_version": 1,
                    "stats": {
                        "total_books": 12,
                        "finished_count": 3,
                        "in_progress_count": 2,
                        "abandoned_count": 1,
                        "books_with_themes_count": 7
                    },
                    "in_library_recommendations": [],
                    "discovery_recommendations": []
                },
                "schema_version": 1,
                "model_id": "test-model",
                "prompt_version": "1",
                "input_fingerprint": "$fp",
                "generated_at": "2026-05-20T10:00:00Z"
            }
        """
    }
}
