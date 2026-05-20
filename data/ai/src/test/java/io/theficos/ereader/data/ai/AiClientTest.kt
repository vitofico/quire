package io.theficos.ereader.data.ai

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AiClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = OkHttpClient.Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `getConfig parses response with quota fields`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"configured":true,"base_url_host":"ollama.lan","model_id":"llama3.1:8b","sources_enabled":["wikipedia"],"daily_budget":200,"regen_daily_limit":3}"""
            )
        )
        val cfg = client.getConfig()
        assertThat(cfg.configured).isTrue()
        assertThat(cfg.baseUrlHost).isEqualTo("ollama.lan")
        assertThat(cfg.modelId).isEqualTo("llama3.1:8b")
        assertThat(cfg.sourcesEnabled).containsExactly("wikipedia")
        assertThat(cfg.dailyBudget).isEqualTo(200)
        assertThat(cfg.regenDailyLimit).isEqualTo(3)
    }

    @Test
    fun `getPreferences parses style`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ai_enabled":true,"style":{"tone":"scholarly","language":"it"}}"""
            )
        )
        val prefs = client.getPreferences()
        assertThat(prefs.aiEnabled).isTrue()
        assertThat(prefs.style.tone).isEqualTo("scholarly")
        assertThat(prefs.style.language).isEqualTo("it")
    }

    @Test
    fun `getPreferences defaults language to auto when server omits it`() = runTest {
        // Forward-compat: legacy/older server responses without `language` must
        // still deserialize, with the kotlinx default ("auto") filling in.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ai_enabled":true,"style":{"tone":"neutral"}}"""
            )
        )
        val prefs = client.getPreferences()
        assertThat(prefs.style.language).isEqualTo("auto")
    }

    @Test
    fun `setPreferences with language sends it in body`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ai_enabled":true,"style":{"tone":"neutral","language":"es"}}"""
            )
        )
        client.setPreferences(style = AiStyle(language = "es"))
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertThat(body).contains("\"language\":\"es\"")
    }

    @Test
    fun `setPreferences sends PUT with body`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ai_enabled":true,"style":{"tone":"neutral"}}"""
            )
        )
        val out = client.setPreferences(enabled = true)
        val req: RecordedRequest = server.takeRequest()
        assertThat(req.method).isEqualTo("PUT")
        assertThat(req.path).isEqualTo("/ai/v1/preferences")
        assertThat(req.body.readUtf8()).contains("\"ai_enabled\":true")
        assertThat(out.aiEnabled).isTrue()
    }

    @Test
    fun `setPreferences with style only`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ai_enabled":true,"style":{"tone":"casual"}}"""
            )
        )
        client.setPreferences(style = AiStyle(tone = "casual"))
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertThat(body).contains("\"tone\":\"casual\"")
        // ai_enabled should NOT be in the body when not sent
        // (encodeDefaults will write null, that's acceptable)
    }

    @Test
    fun `lookupInsight serializes identity and bundle`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"payload":{"schema_version":2,"intro":"hi","confidence":"high"},"sources":[],"model_id":"m","prompt_version":"2","generated_at":"2026-05-09T00:00:00+00:00"}"""
            )
        )
        val bundle = MetadataBundle(title = "Foundation", author = "Isaac Asimov")
        val out = client.lookupInsight(
            DocumentIdentity(metadataId = "x", contentHash = "ch"),
            bundle,
        )
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/ai/v1/insights/lookup")
        assertThat(req.body.readUtf8()).contains("Foundation")
        assertThat(out.payload.intro).isEqualTo("hi")
    }

    @Test
    fun `429 raises AiQuotaException with parsed info`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"detail":{"used":200,"limit":200,"resets_at":"2026-05-10T00:00:00+00:00"}}"""
            )
        )
        try {
            client.lookupInsight(
                DocumentIdentity(null, "ch"),
                MetadataBundle(title = "X"),
            )
            error("expected throw")
        } catch (e: AiQuotaException) {
            assertThat(e.info.used).isEqualTo(200)
            assertThat(e.info.limit).isEqualTo(200)
            assertThat(e.info.resetsAt).contains("2026-05-10")
        }
    }

    @Test
    fun `getInsight throws InsightNotCachedException on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"not_cached"}"""))
        try {
            client.getInsight(DocumentIdentity(null, "ch"))
            error("expected throw")
        } catch (e: InsightNotCachedException) {
            // expected
        }
    }

    @Test
    fun `non 2xx other than 404 or 429 throws AiHttpException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"ai_not_opted_in"}"""))
        try {
            client.lookupInsight(
                DocumentIdentity(null, "ch"),
                MetadataBundle(title = "X"),
            )
            error("expected throw")
        } catch (e: AiHttpException) {
            assertThat(e.code).isEqualTo(409)
        }
    }

    @Test
    fun `empty baseUrl raises AiHttpException with code 0`() = runTest {
        val nullClient = AiClient(
            baseUrlProvider = { null },
            http = OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build(),
        )
        try {
            nullClient.getConfig()
            error("expected throw")
        } catch (e: AiHttpException) {
            assertThat(e.code).isEqualTo(0)
            assertThat(e.body).contains("baseUrl not configured")
        }
    }
}
