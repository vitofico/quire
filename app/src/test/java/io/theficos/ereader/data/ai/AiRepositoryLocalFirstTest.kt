package io.theficos.ereader.data.ai

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.local.db.InsightEntity
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * PR-η: local-cache-first behavior on [AiRepository] for `lookupInsight`
 * and `getCachedInsight`. Uses a [FakeInsightDao] so we can assert exact
 * write-back behavior, and [MockWebServer] so we can count network calls
 * and inject error responses.
 */
class AiRepositoryLocalFirstTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AiClient
    private lateinit var dao: FakeInsightDao
    private lateinit var repo: AiRepository

    private val identity = DocumentIdentity(metadataId = "m1", contentHash = "h1")
    private val bundle = MetadataBundle(title = "T", author = null)

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = AiClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        )
        dao = FakeInsightDao()
        repo = AiRepository(client = client, insightDao = dao, clock = { 1_000_000L })
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
    }

    private suspend fun bootstrapPrefs(
        modelId: String = "llama3.1",
        promptVersion: String = "4",
        tone: String = "neutral",
        language: String = "auto",
    ) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"configured":true,"model_id":"$modelId","prompt_version":"$promptVersion","sources_enabled":[],"daily_budget":0,"regen_daily_limit":0}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ai_enabled":true,"style":{"tone":"$tone","language":"$language"}}"""
            )
        )
        repo.refresh()
        // Drain the two requests so subsequent assertions count from zero.
        server.takeRequest()
        server.takeRequest()
    }

    private fun seedRow(
        modelId: String = "llama3.1",
        promptVersion: String = "4",
        tone: String = "neutral",
        language: String = "auto",
        payloadJson: String = """{"schema_version":4}""",
    ) {
        dao.seed(
            InsightEntity(
                identityKey = "m1",
                metadataId = "m1",
                contentHash = "h1",
                modelId = modelId,
                promptVersion = promptVersion,
                tone = tone,
                language = language,
                payloadJson = payloadJson,
                sourcesJson = "[]",
                schemaVersion = 4,
                serverId = 42L,
                generatedAt = 999L,
                syncedAt = 1_000L,
            )
        )
    }

    @Test fun `lookupInsight short-circuits on cache hit`() = runTest {
        bootstrapPrefs()
        seedRow()
        val resp = repo.lookupInsight(identity, bundle)
        assertThat(resp.modelId).isEqualTo("llama3.1")
        assertThat(resp.promptVersion).isEqualTo("4")
        // No additional request beyond the two bootstrap calls.
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `lookupInsight falls through on cache miss and writes back`() = runTest {
        bootstrapPrefs()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"payload":{"schema_version":4},"sources":[],"model_id":"llama3.1","prompt_version":"4","generated_at":"2026-05-01T00:00:00Z"}"""
            )
        )
        val resp = repo.lookupInsight(identity, bundle)
        assertThat(resp.modelId).isEqualTo("llama3.1")
        // Wrote back to local cache.
        val cached = dao.getByIdentity("m1", "llama3.1", "4", "neutral", "auto")
        assertThat(cached).isNotNull()
        assertThat(cached!!.promptVersion).isEqualTo("4")
    }

    @Test fun `getCachedInsight short-circuits on cache hit`() = runTest {
        bootstrapPrefs()
        seedRow()
        val resp = repo.getCachedInsight(identity)
        assertThat(resp).isNotNull()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `getCachedInsight writes back on client_getInsight 200`() = runTest {
        bootstrapPrefs()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"payload":{"schema_version":4},"sources":[],"model_id":"llama3.1","prompt_version":"4","generated_at":"2026-05-01T00:00:00Z"}"""
            )
        )
        val resp = repo.getCachedInsight(identity)
        assertThat(resp).isNotNull()
        // The write-back is the v2 fix: getCachedInsight() MUST cache successful gets.
        val cached = dao.getByIdentity("m1", "llama3.1", "4", "neutral", "auto")
        assertThat(cached).isNotNull()
    }

    @Test fun `getCachedInsight returns null on InsightNotCachedException`() = runTest {
        bootstrapPrefs()
        server.enqueue(MockResponse().setResponseCode(404))
        val resp = repo.getCachedInsight(identity)
        assertThat(resp).isNull()
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test fun `getCachedInsight on IOException returns findAnyForIdentity row`() = runTest {
        bootstrapPrefs()
        // Seed a row at a DIFFERENT style than current prefs — exact lookup misses,
        // network call then "fails" (we hijack via socket disconnect).
        seedRow(tone = "scholarly")
        // Shut down the server so the next call throws a connection IOException.
        server.shutdown()
        val resp = repo.getCachedInsight(identity)
        // findAnyForIdentity returned the scholarly-tone stale row.
        assertThat(resp).isNotNull()
        assertThat(resp!!.modelId).isEqualTo("llama3.1")
    }

    @Test fun `getCachedInsight on 409 rethrows`() = runTest {
        bootstrapPrefs()
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"ai_not_opted_in"}"""))
        val ex = runCatching { repo.getCachedInsight(identity) }.exceptionOrNull()
        assertThat(ex).isInstanceOf(AiHttpException::class.java)
        assertThat((ex as AiHttpException).code).isEqualTo(409)
    }

    @Test fun `getCachedInsight on 401 rethrows`() = runTest {
        bootstrapPrefs()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"unauthorized"}"""))
        val ex = runCatching { repo.getCachedInsight(identity) }.exceptionOrNull()
        assertThat(ex).isInstanceOf(AiHttpException::class.java)
        assertThat((ex as AiHttpException).code).isEqualTo(401)
    }

    @Test fun `getCachedInsight on 500 rethrows`() = runTest {
        bootstrapPrefs()
        server.enqueue(MockResponse().setResponseCode(500))
        val ex = runCatching { repo.getCachedInsight(identity) }.exceptionOrNull()
        assertThat(ex).isInstanceOf(AiHttpException::class.java)
        assertThat((ex as AiHttpException).code).isEqualTo(500)
    }

    @Test fun `prompt-version change invalidates local cache via key`() = runTest {
        // Seed a row at promptVersion=4; bootstrap prefs at promptVersion=5.
        seedRow(promptVersion = "4")
        bootstrapPrefs(promptVersion = "5")
        // Local read misses; client returns a v5 row.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"payload":{"schema_version":4},"sources":[],"model_id":"llama3.1","prompt_version":"5","generated_at":"2026-05-01T00:00:00Z"}"""
            )
        )
        val resp = repo.lookupInsight(identity, bundle)
        assertThat(resp.promptVersion).isEqualTo("5")
        // Both old and new rows coexist on disk.
        assertThat(dao.getByIdentity("m1", "llama3.1", "4", "neutral", "auto")).isNotNull()
        assertThat(dao.getByIdentity("m1", "llama3.1", "5", "neutral", "auto")).isNotNull()
    }

    @Test fun `AiConfig defaults promptVersion to 1 when server omits the field`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"configured":true,"model_id":"m","sources_enabled":[],"daily_budget":0,"regen_daily_limit":0}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ai_enabled":false,"style":{"tone":"neutral","language":"auto"}}"""
            )
        )
        repo.refresh()
        assertThat(repo.config.value).isNotNull()
        assertThat(repo.config.value!!.promptVersion).isEqualTo("1")
    }
}
