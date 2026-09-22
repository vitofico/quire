package io.theficos.ereader.data.ai

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

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

    private val insightBody =
        """{"payload":{"schema_version":2,"intro":"hi","confidence":"high"},"sources":[],"model_id":"m","prompt_version":"2","generated_at":"2026-05-09T00:00:00+00:00"}"""

    @Test
    fun `getConfig parses generation_timeout_s and tolerates its absence`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"configured":true,"generation_timeout_s":300}"""
            )
        )
        assertThat(client.getConfig().generationTimeoutS).isEqualTo(300)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":true}"""))
        assertThat(client.getConfig().generationTimeoutS).isNull()
    }

    @Test
    fun `computeLongCallTimeoutS falls back to 270s when the server does not advertise`() {
        assertThat(AiClient.computeLongCallTimeoutS(null)).isEqualTo(270L)
    }

    @Test
    fun `computeLongCallTimeoutS doubles the server timeout plus margin and clamps`() {
        assertThat(AiClient.computeLongCallTimeoutS(60)).isEqualTo(150L)
        assertThat(AiClient.computeLongCallTimeoutS(5)).isEqualTo(60L) // 40 clamped up
        assertThat(AiClient.computeLongCallTimeoutS(300)).isEqualTo(600L) // 630 clamped down
    }

    @Test
    fun `lookupInsight outlives the shared client's read timeout`() = runTest {
        // Issue #102: the shared OkHttpClient is tuned for OPDS (short reads).
        // A generation must not inherit that limit.
        val impatient = AiClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build(),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"configured":true,"generation_timeout_s":30}""")
        )
        impatient.getConfig()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBodyDelay(2, TimeUnit.SECONDS)
                .setBody(insightBody)
        )
        val resp = impatient.lookupInsight(
            DocumentIdentity(metadataId = "m"),
            MetadataBundle(title = "T", author = "A"),
        )
        assertThat(resp.modelId).isEqualTo("m")
    }

    @Test
    fun `cancelling the coroutine cancels the underlying HTTP call`() = runTest {
        // Issue #102: a long call must give up its thread and socket the
        // moment the caller stops waiting, not after the full timeout.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBodyDelay(5, TimeUnit.SECONDS)
                .setBody(insightBody)
        )
        val job = launch(Dispatchers.Default) {
            client.lookupInsight(
                DocumentIdentity(metadataId = "m"),
                MetadataBundle(title = "T", author = "A"),
            )
        }
        // Block (real time, off the test scheduler) until the request actually
        // reaches the server, so we know the call is in flight before cancelling.
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertThat(request).isNotNull()

        val elapsedMs = measureTimeMillis { job.cancelAndJoin() }
        // Well under the 5 s body delay: proof the OkHttp call was cancelled
        // rather than left to run out the clock.
        assertThat(elapsedMs).isLessThan(2000)
    }

    @Test
    fun `lookupInsight maps a structured provider error to AiProviderException`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(504).setBody(
                """{"detail":{"code":"provider_timeout","message":"The AI provider did not answer within 120 seconds.","hint":"Raise QUIRE_SERVER_AI_TIMEOUT_S for slow local models, or pick a faster model.","provider_status":null}}"""
            )
        )
        val e = runCatching {
            client.lookupInsight(DocumentIdentity(metadataId = "m"), MetadataBundle(title = "T", author = "A"))
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(AiProviderException::class.java)
        e as AiProviderException
        assertThat(e.code).isEqualTo(504)
        assertThat(e.errorCode).isEqualTo("provider_timeout")
        assertThat(e.serverMessage).isEqualTo("The AI provider did not answer within 120 seconds.")
        assertThat(e.hint).isEqualTo("Raise QUIRE_SERVER_AI_TIMEOUT_S for slow local models, or pick a faster model.")
        assertThat(e.providerStatus).isNull()
    }

    @Test
    fun `lookupInsight keeps provider_status from a rejected error`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(502).setBody(
                """{"detail":{"code":"provider_rejected","message":"The AI provider rejected the server's credentials.","hint":"Check QUIRE_SERVER_AI_API_KEY.","provider_status":401}}"""
            )
        )
        val e = runCatching {
            client.lookupInsight(DocumentIdentity(metadataId = "m"), MetadataBundle(title = "T", author = "A"))
        }.exceptionOrNull() as AiProviderException
        assertThat(e.providerStatus).isEqualTo(401)
        assertThat(e.hint).isEqualTo("Check QUIRE_SERVER_AI_API_KEY.")
    }

    @Test
    fun `lookupInsight keeps AiHttpException for a plain string detail`() = runTest {
        // An older server, or a non-provider failure: no structured body.
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"detail":"boom"}"""))
        val e = runCatching {
            client.lookupInsight(DocumentIdentity(metadataId = "m"), MetadataBundle(title = "T", author = "A"))
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(AiHttpException::class.java)
        assertThat(e).isNotInstanceOf(AiProviderException::class.java)
        assertThat((e as AiHttpException).code).isEqualTo(502)
    }

    @Test
    fun `a 429 quota body still becomes AiQuotaException`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"detail":{"used":3,"limit":3,"resets_at":"2026-09-17T00:00:00+00:00"}}"""
            )
        )
        val e = runCatching {
            client.lookupInsight(DocumentIdentity(metadataId = "m"), MetadataBundle(title = "T", author = "A"))
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(AiQuotaException::class.java)
        assertThat((e as AiQuotaException).info.limit).isEqualTo(3)
    }

    @Test
    fun `a 429 whose detail carries a code stays a plain AiHttpException`() = runTest {
        // The provider shape is only recognised when the status is not 429.
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"detail":{"code":"rate_limited","message":"Slow down.","hint":null,"provider_status":null}}"""
            )
        )
        val e = runCatching {
            client.lookupInsight(DocumentIdentity(metadataId = "m"), MetadataBundle(title = "T", author = "A"))
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(AiHttpException::class.java)
        assertThat(e).isNotInstanceOf(AiProviderException::class.java)
        assertThat(e).isNotInstanceOf(AiQuotaException::class.java)
        assertThat((e as AiHttpException).code).isEqualTo(429)
    }
}
