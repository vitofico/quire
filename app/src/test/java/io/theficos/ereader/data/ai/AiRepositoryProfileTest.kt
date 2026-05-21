package io.theficos.ereader.data.ai

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.local.db.ProgressEntity
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * pr-α: cache-only reader-profile fetch + abandon/un-abandon DAO delegation
 * on [AiRepository].
 */
class AiRepositoryProfileTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AiClient
    private lateinit var insightDao: FakeInsightDao
    private lateinit var progressDao: FakeProgressDao
    private lateinit var repo: AiRepository

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = AiClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        )
        insightDao = FakeInsightDao()
        progressDao = FakeProgressDao()
        repo = AiRepository(
            client = client,
            insightDao = insightDao,
            progressDao = progressDao,
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

    @Test fun `markAbandoned delegates to ProgressDao with the supplied timestamp`() = runTest {
        progressDao.seed(
            ProgressEntity(
                documentId = 7L,
                locator = "x",
                percent = 0.3,
                updatedAt = 100L,
                localUpdatedAt = 100L,
                syncedAt = 100L,
            )
        )
        repo.markAbandoned(documentId = 7L, now = 4_242L)
        val row = progressDao.byDocument(7L)
        assertThat(row?.abandonedAt).isEqualTo(4_242L)
        assertThat(row?.finishedAt).isNull()
        assertThat(row?.updatedAt).isEqualTo(4_242L)
        assertThat(row?.localUpdatedAt).isEqualTo(4_242L)
    }

    @Test fun `unmarkAbandoned delegates to ProgressDao`() = runTest {
        progressDao.seed(
            ProgressEntity(
                documentId = 9L,
                locator = "x",
                percent = 0.4,
                updatedAt = 100L,
                localUpdatedAt = 100L,
                syncedAt = 100L,
                abandonedAt = 200L,
            )
        )
        repo.unmarkAbandoned(documentId = 9L, now = 555L)
        val row = progressDao.byDocument(9L)
        assertThat(row?.abandonedAt).isNull()
        assertThat(row?.updatedAt).isEqualTo(555L)
    }
}
