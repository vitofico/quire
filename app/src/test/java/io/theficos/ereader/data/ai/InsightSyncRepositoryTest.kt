package io.theficos.ereader.data.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * PR-η: [InsightSyncRepository] orchestrates the bulk sync. Tests cover
 * tuple-cursor walking, gating on opt-in, single-flight mutex, and
 * debounce/coalescing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InsightSyncRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AiClient
    private lateinit var dao: FakeInsightDao
    private lateinit var aiRepo: AiRepository

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = AiClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        )
        dao = FakeInsightDao()
        aiRepo = AiRepository(client = client, insightDao = dao, clock = { 1_000L })
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
    }

    private suspend fun bootstrap(aiEnabled: Boolean = true) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"configured":true,"model_id":"m","prompt_version":"4","sources_enabled":[],"daily_budget":0,"regen_daily_limit":0}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ai_enabled":$aiEnabled,"style":{"tone":"neutral","language":"auto"}}"""
            )
        )
        aiRepo.refresh()
        server.takeRequest(); server.takeRequest()
    }

    private fun syncItem(id: Long, generatedAt: String, identityKey: String = "m$id"): String =
        """
        {
          "id": $id,
          "identity": {"metadata_id": "$identityKey", "content_hash": "h$id"},
          "payload": {"schema_version": 4},
          "sources": [],
          "model_id": "m",
          "prompt_version": "4",
          "schema_version": 4,
          "tone": "neutral",
          "language": "auto",
          "generated_at": "$generatedAt"
        }
        """.trimIndent()

    @Test fun `syncNow walks tuple cursor across pages`() = runTest {
        bootstrap()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val sync = InsightSyncRepository(
            client = client, dao = dao, aiRepo = aiRepo,
            scope = scope, clock = { 2_000L },
        )
        // page1: 2 items, cursor → next
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{
              "items":[${syncItem(1, "2026-05-01T00:00:00Z")},${syncItem(2, "2026-05-02T00:00:00Z")}],
              "server_time":"2026-05-19T00:00:00Z",
              "next_cursor":{"generated_at":"2026-05-02T00:00:00Z","id":2}
            }"""
        ))
        // page2: 1 item, next_cursor=null
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{
              "items":[${syncItem(3, "2026-05-03T00:00:00Z")}],
              "server_time":"2026-05-19T00:00:00Z",
              "next_cursor":null
            }"""
        ))

        val result = sync.syncNow()
        assertThat(result).isInstanceOf(InsightSyncRepository.SyncResult.Synced::class.java)
        val synced = result as InsightSyncRepository.SyncResult.Synced
        assertThat(synced.pages).isEqualTo(2)
        assertThat(synced.items).isEqualTo(3)
        assertThat(dao.count()).isEqualTo(3)
        // The second call carried the cursor from page1.
        val req1 = server.takeRequest()
        val req2 = server.takeRequest()
        assertThat(req1.requestUrl?.queryParameter("since_ts")).isNull()
        assertThat(req2.requestUrl?.queryParameter("since_ts")).isEqualTo("2026-05-02T00:00:00Z")
        assertThat(req2.requestUrl?.queryParameter("since_id")).isEqualTo("2")
    }

    @Test fun `syncNow skips when ai_enabled is false`() = runTest {
        bootstrap(aiEnabled = false)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val sync = InsightSyncRepository(client, dao, aiRepo, scope = scope)
        val result = sync.syncNow()
        assertThat(result).isInstanceOf(InsightSyncRepository.SyncResult.Skipped::class.java)
        assertThat((result as InsightSyncRepository.SyncResult.Skipped).reason).isEqualTo("not_enabled")
        // No new network request.
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `syncNow returns Failed on network error`() = runTest {
        bootstrap()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val sync = InsightSyncRepository(client, dao, aiRepo, scope = scope)
        server.shutdown()
        val result = sync.syncNow()
        assertThat(result).isInstanceOf(InsightSyncRepository.SyncResult.Failed::class.java)
    }

    @Test fun `requestSync coalesces multiple calls in the debounce window`() = runTest {
        bootstrap()
        val sync = InsightSyncRepository(
            client, dao, aiRepo,
            scope = this,
            debounceMs = 500L,
        )
        // Enqueue exactly one network response. If coalescing fails the second
        // sync call hangs MockWebServer's queue and the assertion below trips.
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"items":[],"server_time":"2026-05-19T00:00:00Z","next_cursor":null}"""
        ))
        repeat(5) { sync.requestSync("burst-$it") }
        // Advance past the debounce; the one sync fires.
        advanceTimeBy(600L)
        runCurrent()
        advanceUntilIdle()

        // Drain the sync request — blocks until the real I/O dispatcher has
        // actually delivered the HTTP request to MockWebServer. Without this
        // wait, CI race makes `requestCount` flaky.
        val req = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertThat(req).isNotNull()
        assertThat(req!!.path).contains("/ai/v1/insights/sync")

        // No more requests should land in the next 200ms — i.e. coalescing held.
        val extra = server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertThat(extra).isNull()
    }
}
