package io.theficos.ereader.data.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Cache-only reader-profile fetch on [AiRepository].
 */
class AiRepositoryProfileTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AiClient
    private lateinit var insightDao: FakeInsightDao
    private lateinit var repo: AiRepository

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = AiClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        )
        insightDao = FakeInsightDao()
        repo = AiRepository(
            client = client,
            insightDao = insightDao,
            clock = { 5_000L },
        )
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test fun `fetchProfile returns null on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"no_profile"}"""))
        val r = repo.fetchProfile()
        assertThat(r).isNull()
    }

    @Test fun `fetchProfile returns parsed envelope on 200 including booksWithThemesCount`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "payload": {
                        "schema_version": 1,
                        "stats": {
                            "total_books": 4,
                            "finished_count": 2,
                            "in_progress_count": 1,
                            "abandoned_count": 1,
                            "avg_session_minutes": null,
                            "finish_rate_by_theme": {"noir": 0.5},
                            "most_read_authors": [{"name": "X", "count": 2}],
                            "books_with_themes_count": 3
                        },
                        "narrative": "hello",
                        "in_library_recommendations": [],
                        "discovery_recommendations": [],
                        "confidence": "medium"
                    },
                    "schema_version": 1,
                    "model_id": "test-model",
                    "prompt_version": "1",
                    "input_fingerprint": null,
                    "generated_at": "2026-05-20T00:00:00Z"
                }
                """.trimIndent()
            )
        )
        val r = repo.fetchProfile()
        assertThat(r).isNotNull()
        assertThat(r!!.modelId).isEqualTo("test-model")
        assertThat(r.payload.stats.booksWithThemesCount).isEqualTo(3)
        assertThat(r.payload.stats.totalBooks).isEqualTo(4)
        assertThat(r.payload.stats.finishRateByTheme["noir"]).isEqualTo(0.5)
        assertThat(r.payload.narrative).isEqualTo("hello")
        assertThat(r.payload.confidence).isEqualTo("medium")
    }

}
