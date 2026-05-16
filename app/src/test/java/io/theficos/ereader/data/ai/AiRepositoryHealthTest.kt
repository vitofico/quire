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
 * Verifies that [AiRepository.fetchHealth] deserializes the server response
 * shape correctly and returns null on errors instead of throwing.
 *
 * The server contract is documented in
 * `docs/superpowers/specs/2026-05-16-ai-health-endpoint-design.md`.
 */
class AiRepositoryHealthTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AiClient
    private lateinit var repo: AiRepository

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
        repo = AiRepository(client)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `fetchHealth deserializes a typical response`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "provider_reachable": true,
                  "provider_last_checked_at": "2026-05-16T14:32:11Z",
                  "model_id": "llama3.1:8b",
                  "last_failure_at": null,
                  "last_failure_class": null,
                  "retrieval_sources": [
                    {"name": "wikipedia", "reachable": true, "last_checked_at": "2026-05-16T14:32:09Z"},
                    {"name": "openlibrary", "reachable": null, "last_checked_at": null}
                  ]
                }
                """.trimIndent()
            )
        )

        val health = repo.fetchHealth()
        assertThat(health).isNotNull()
        assertThat(health!!.providerReachable).isTrue()
        assertThat(health.modelId).isEqualTo("llama3.1:8b")
        assertThat(health.providerLastCheckedAt).isEqualTo("2026-05-16T14:32:11Z")
        assertThat(health.lastFailureClass).isNull()
        assertThat(health.retrievalSources).hasSize(2)
        assertThat(health.retrievalSources[0].name).isEqualTo("wikipedia")
        assertThat(health.retrievalSources[0].reachable).isTrue()
        assertThat(health.retrievalSources[1].name).isEqualTo("openlibrary")
        assertThat(health.retrievalSources[1].reachable).isNull()
        assertThat(health.retrievalSources[1].lastCheckedAt).isNull()
    }

    @Test
    fun `fetchHealth deserializes an all-null response`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "provider_reachable": null,
                  "provider_last_checked_at": null,
                  "model_id": null,
                  "last_failure_at": null,
                  "last_failure_class": null,
                  "retrieval_sources": []
                }
                """.trimIndent()
            )
        )

        val health = repo.fetchHealth()
        assertThat(health).isNotNull()
        assertThat(health!!.providerReachable).isNull()
        assertThat(health.modelId).isNull()
        assertThat(health.retrievalSources).isEmpty()
    }

    @Test
    fun `fetchHealth deserializes a failure response with error class`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "provider_reachable": false,
                  "provider_last_checked_at": "2026-05-16T15:00:00Z",
                  "model_id": "llama3.1:8b",
                  "last_failure_at": "2026-05-16T15:00:00Z",
                  "last_failure_class": "ProviderTimeout",
                  "retrieval_sources": []
                }
                """.trimIndent()
            )
        )

        val health = repo.fetchHealth()
        assertThat(health).isNotNull()
        assertThat(health!!.providerReachable).isFalse()
        assertThat(health.lastFailureClass).isEqualTo("ProviderTimeout")
        // model_id preserved from the prior success even though current state is failed.
        assertThat(health.modelId).isEqualTo("llama3.1:8b")
    }

    @Test
    fun `fetchHealth returns null on 404 (server in AI-disabled mode)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        val health = repo.fetchHealth()
        assertThat(health).isNull()
    }

    @Test
    fun `fetchHealth returns null on 500`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        val health = repo.fetchHealth()
        assertThat(health).isNull()
    }
}
