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

class AiClientSyncTest {

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
    fun `syncInsights without cursor sends only limit query param`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[],"server_time":"2026-05-19T00:00:00+00:00","next_cursor":null}""",
            ),
        )
        val resp = client.syncInsights(cursor = null, limit = 50)
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("GET")
        assertThat(req.path).contains("/ai/v1/insights/sync")
        assertThat(req.requestUrl?.queryParameter("limit")).isEqualTo("50")
        assertThat(req.requestUrl?.queryParameter("since_ts")).isNull()
        assertThat(req.requestUrl?.queryParameter("since_id")).isNull()
        assertThat(resp.items).isEmpty()
        assertThat(resp.nextCursor).isNull()
    }

    @Test
    fun `syncInsights with cursor percent-encodes timestamp plus`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[],"server_time":"2026-05-19T00:01:00+00:00","next_cursor":null}""",
            ),
        )
        client.syncInsights(
            cursor = InsightSyncCursor(
                generatedAt = "2026-05-19T00:00:00+00:00",
                id = 42L,
            ),
            limit = 25,
        )
        val req = server.takeRequest()
        // The raw line preserves percent-encoding of the `+`.
        assertThat(req.path).contains("since_ts=2026-05-19T00%3A00%3A00%2B00%3A00")
        assertThat(req.path).contains("since_id=42")
        assertThat(req.path).contains("limit=25")
    }

    @Test
    fun `syncInsights parses items and next_cursor`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "items":[
                    {
                      "id":7,
                      "identity":{"metadata_id":"m-1","content_hash":"ch-1"},
                      "payload":{"intro":"i","confidence":"low","schema_version":4},
                      "sources":[],
                      "model_id":"test-model",
                      "prompt_version":"5",
                      "schema_version":4,
                      "tone":"neutral",
                      "language":"auto",
                      "generated_at":"2026-05-19T00:00:00+00:00"
                    }
                  ],
                  "server_time":"2026-05-19T00:01:00+00:00",
                  "next_cursor":{"generated_at":"2026-05-19T00:00:00+00:00","id":7}
                }
                """.trimIndent(),
            ),
        )
        val resp = client.syncInsights()
        assertThat(resp.items).hasSize(1)
        assertThat(resp.items[0].id).isEqualTo(7L)
        assertThat(resp.items[0].promptVersion).isEqualTo("5")
        assertThat(resp.items[0].schemaVersion).isEqualTo(4)
        assertThat(resp.nextCursor).isNotNull()
        assertThat(resp.nextCursor!!.id).isEqualTo(7L)
    }

    @Test
    fun `syncInsights 409 throws AiHttpException`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"detail":"ai_not_opted_in"}""",
            ),
        )
        try {
            client.syncInsights()
            error("expected throw")
        } catch (e: AiHttpException) {
            assertThat(e.code).isEqualTo(409)
        }
    }

    @Test
    fun `getConfig decodes prompt_version`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"configured":true,"sources_enabled":[],"daily_budget":0,"regen_daily_limit":0,"prompt_version":"5"}""",
            ),
        )
        val cfg = client.getConfig()
        assertThat(cfg.promptVersion).isEqualTo("5")
    }

    @Test
    fun `getConfig defaults prompt_version to legacy sentinel on omission`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"configured":true,"sources_enabled":[],"daily_budget":0,"regen_daily_limit":0}""",
            ),
        )
        val cfg = client.getConfig()
        // Default matches the server's Lock #19 sentinel; downstream cache
        // logic treats this as "use the in-code constant".
        assertThat(cfg.promptVersion).isEqualTo("1")
    }
}
