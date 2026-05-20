package io.theficos.ereader.data.ai

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.DocumentIdentity
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AiClientPromoteTest {

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
    fun `promoteInsight 200 returns parsed response`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"promoted":true,"insight_id":42,"already_promoted":false}""",
            ),
        )
        val resp = client.promoteInsight(
            from = DocumentIdentity(metadataId = "opds-href:abc"),
            to = DocumentIdentity(metadataId = "urn-xyz", contentHash = "ch"),
        )
        assertThat(resp).isNotNull()
        assertThat(resp!!.promoted).isTrue()
        assertThat(resp.insightId).isEqualTo(42L)
        assertThat(resp.alreadyPromoted).isFalse()
    }

    @Test
    fun `promoteInsight serializes from-to and tone-language in body`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"promoted":true,"insight_id":1,"already_promoted":false}""",
            ),
        )
        client.promoteInsight(
            from = DocumentIdentity(metadataId = "opds-href:abc"),
            to = DocumentIdentity(metadataId = "to-id", contentHash = "ch"),
            tone = "scholarly",
            language = "fr",
        )
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/ai/v1/insights/promote")
        val body = req.body.readUtf8()
        assertThat(body).contains("\"from\":")
        assertThat(body).contains("\"to\":")
        assertThat(body).contains("\"tone\":\"scholarly\"")
        assertThat(body).contains("\"language\":\"fr\"")
    }

    @Test
    fun `promoteInsight 204 returns null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val resp = client.promoteInsight(
            from = DocumentIdentity(metadataId = "f"),
            to = DocumentIdentity(metadataId = "t", contentHash = "ch"),
        )
        assertThat(resp).isNull()
    }

    @Test
    fun `promoteInsight 409 throws AiHttpException`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"detail":"ai_not_opted_in"}""",
            ),
        )
        try {
            client.promoteInsight(
                from = DocumentIdentity(metadataId = "f"),
                to = DocumentIdentity(metadataId = "t", contentHash = "ch"),
            )
            error("expected throw")
        } catch (e: AiHttpException) {
            assertThat(e.code).isEqualTo(409)
            assertThat(e.body).contains("ai_not_opted_in")
        }
    }

    @Test
    fun `promoteInsight idempotent response carries already_promoted`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"promoted":true,"insight_id":7,"already_promoted":true}""",
            ),
        )
        val resp = client.promoteInsight(
            from = DocumentIdentity(metadataId = "f"),
            to = DocumentIdentity(metadataId = "t", contentHash = "ch"),
        )
        assertThat(resp).isNotNull()
        assertThat(resp!!.alreadyPromoted).isTrue()
    }
}
