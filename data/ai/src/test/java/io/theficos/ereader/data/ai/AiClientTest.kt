package io.theficos.ereader.data.ai

import io.theficos.ereader.testing.awaitRequest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            language = { "en" },
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `every call tells the server the app language`() = runTest {
        val italian = AiClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
            language = { "it" },
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ai_enabled":true,"style":{"tone":"neutral"}}"""))
        italian.getPreferences()
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Accept-Language")).isEqualTo("it")
    }

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
        val req = server.awaitRequest()
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
        val req: RecordedRequest = server.awaitRequest()
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
        val req = server.awaitRequest()
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
        val req = server.awaitRequest()
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
            language = { "en" },
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

    private val profileBody =
        """{"payload":{"schema_version":1,"stats":{"total_books":1,"finished_count":0,"in_progress_count":0,"abandoned_count":0}},"schema_version":1,"model_id":"m","prompt_version":"2","generated_at":"2026-05-09T00:00:00+00:00"}"""

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
    fun `getConfig parses profile_timeout_s and tolerates its absence`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"configured":true,"profile_timeout_s":90}"""
            )
        )
        assertThat(client.getConfig().profileTimeoutS).isEqualTo(90)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":true}"""))
        assertThat(client.getConfig().profileTimeoutS).isNull()
    }

    @Test
    fun `profile refresh uses its advertised budget then falls back to generation`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"configured":true,"generation_timeout_s":120,"profile_timeout_s":240}""",
        ))
        client.getConfig()
        assertThat(client.profileRefreshBudgetS).isEqualTo(240)

        server.enqueue(MockResponse().setBody(
            """{"configured":true,"generation_timeout_s":150}""",
        ))
        client.getConfig()
        assertThat(client.profileRefreshBudgetS).isEqualTo(150)

        server.enqueue(MockResponse().setBody("""{"configured":true}"""))
        client.getConfig()
        assertThat(client.profileRefreshBudgetS).isNull()
        assertThat(AiClient.computeLongCallTimeoutS(client.profileRefreshBudgetS)).isEqualTo(270L)
    }

    @Test
    fun `refreshProfile posts its request and outlives the shared read timeout`() = runTest {
        // Issue #102: the shared OPDS client would time out before this body arrives.
        val impatient = AiClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build(),
            language = { "en" },
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"configured":true,"generation_timeout_s":5,"profile_timeout_s":30}"""
            )
        )
        impatient.getConfig()
        assertThat(server.awaitRequest().requestUrl?.encodedPath).isEqualTo("/ai/v1/config")
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBodyDelay(2, TimeUnit.SECONDS)
                .setBody(profileBody)
        )
        val resp = impatient.refreshProfile()
        assertThat(resp.modelId).isEqualTo("m")
        val request = server.awaitRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.requestUrl?.encodedPath).isEqualTo("/ai/v1/profile/refresh")
        assertThat(request.body.readUtf8()).isEqualTo("{}")
    }

    @Test
    fun `lookupInsight outlives the shared client's read timeout`() = runTest {
        // Issue #102: the shared OkHttpClient is tuned for OPDS (short reads).
        // A generation must not inherit that limit.
        val impatient = AiClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build(),
            language = { "en" },
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
        // Delay the headers, as the real server does while it generates. A
        // delayed body would not do: its headers arrive at once, the call
        // counts as answered, and the test then times the body read, which
        // cancelling cannot cut short. The delay stays under the 5 s that
        // MockWebServer.shutdown() waits for its threads, and over the 2 s
        // bound below, so a call that ignores cancelling still fails fast.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeadersDelay(3, TimeUnit.SECONDS)
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

    private val detailsBody = DetailsLookupBody(
        identity = DocumentIdentity(metadataId = "m", contentHash = "h"),
        book = DetailsBook(title = "dune_frank_herbert"),
        source = ORIGIN_FILE,
        cover = null,
    )

    @Test
    fun `book details are looked up with the insights identity and every field the server takes`() = runTest {
        server.enqueue(MockResponse().setBody("""{"key":"k","book":{"title":"Dune"},"origins":{"title":"ai"},"tidy":"done"}"""))

        val answer = client.lookupDetails(detailsBody)

        val sent = server.awaitRequest()
        assertThat(sent.path).isEqualTo("/details/v1/lookup")
        val json = kotlinx.serialization.json.Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        // The server forbids keys it does not know, so these must be exactly its fields.
        assertThat(json.keys).containsExactly("identity", "book", "source", "cover")
        assertThat(json["book"]!!.jsonObject.keys).containsExactly(
            "title", "authors", "series_name", "series_index", "description", "subjects",
            "language", "isbn", "year", "publisher",
        )
        assertThat(json["identity"]!!.jsonObject["metadata_id"]!!.jsonPrimitive.content).isEqualTo("m")
        assertThat(json["identity"]!!.jsonObject["content_hash"]!!.jsonPrimitive.content).isEqualTo("h")
        assertThat(json["source"]!!.jsonPrimitive.content).isEqualTo("file")
        assertThat(answer.book.title).isEqualTo("Dune")
    }

    @Test
    fun `a details 429 carries its Retry-After, quota body or not`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30").setBody("""{"detail":"rate_limited"}"""))
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "86400")
                .setBody("""{"detail":{"used":5,"limit":5,"resets_at":"2026-12-01T00:00:00+00:00"}}"""),
        )

        val limited = runCatching { client.lookupDetails(detailsBody) }.exceptionOrNull() as AiHttpException
        val spent = runCatching { client.tidyDetails(detailsBody) }.exceptionOrNull() as AiQuotaException

        assertThat(limited.code).isEqualTo(429)
        assertThat(limited.retryAfterSeconds).isEqualTo(30)
        assertThat(spent.retryAfterSeconds).isEqualTo(86400)
    }

    @Test
    fun `a generated cover comes only from the cover endpoint`() = runTest {
        server.enqueue(MockResponse().setBody("png"))
        val key = "0123456789abcdef".repeat(4)

        assertThat(String(client.detailsCover("/details/v1/covers/$key?v=2"))).isEqualTo("png")
        assertThat(server.awaitRequest().path).isEqualTo("/details/v1/covers/$key?v=2")
        listOf(
            "/ai/v1/profile",
            "/details/v1/covers/../../../ai/v1/profile",
            "/details/v1/covers/%2e%2e/%2e%2e/%2e%2e/ai/v1/profile",
            "/details/v1/covers/k?v=2",
            "@elsewhere.example/details/v1/covers/$key",
        ).forEach { path ->
            assertWithMessage(path).that(runCatching { client.detailsCover(path) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(server.requestCount).isEqualTo(1)
    }
}
