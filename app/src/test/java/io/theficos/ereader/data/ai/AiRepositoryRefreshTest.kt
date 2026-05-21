package io.theficos.ereader.data.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * PR-γ — verifies `AiRepository.refreshProfile()` runs the preflight
 * collaborators BEFORE calling the network, and that preflight failures are
 * swallowed (best-effort per corrections.md PR-γ Required #6).
 */
class AiRepositoryRefreshTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AiClient
    private lateinit var insightDao: FakeInsightDao

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = AiClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        )
        insightDao = FakeInsightDao()
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test fun `refreshProfile invokes preflight then network in order`() = runBlocking {
        val order = mutableListOf<String>()
        val repo = AiRepository(
            client = client,
            insightDao = insightDao,
            syncRunner = { order.add("sync"); true },
            libraryRunner = { order.add("library"); true },
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(PROFILE_BODY))
        repo.refreshProfile()
        assertThat(order).containsExactly("sync", "library").inOrder()
    }

    @Test fun `refreshProfile fires network even when preflight throws`() = runBlocking {
        val repo = AiRepository(
            client = client,
            insightDao = insightDao,
            syncRunner = { throw RuntimeException("transient-sync") },
            libraryRunner = { throw RuntimeException("transient-library") },
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(PROFILE_BODY))
        var outcome: PreflightOutcome? = null
        val resp = repo.refreshProfile(onPreflightDone = { outcome = it })
        assertThat(resp.modelId).isEqualTo("test-model")
        assertThat(outcome).isNotNull()
        assertThat(outcome!!.progressSyncOk).isFalse()
        assertThat(outcome!!.libraryUploadOk).isFalse()
        assertThat(outcome!!.anyFailed).isTrue()
    }

    @Test fun `refreshProfile fires network when preflight runners are null`() = runBlocking {
        val repo = AiRepository(
            client = client,
            insightDao = insightDao,
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(PROFILE_BODY))
        val resp = repo.refreshProfile()
        assertThat(resp.modelId).isEqualTo("test-model")
    }

    @Test fun `deleteProfile sends DELETE returns Unit on 204`() = runBlocking {
        val repo = AiRepository(client = client, insightDao = insightDao)
        server.enqueue(MockResponse().setResponseCode(204))
        repo.deleteProfile()
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("DELETE")
        assertThat(req.path).isEqualTo("/ai/v1/profile")
    }

    private companion object {
        const val PROFILE_BODY = """
            {
                "payload": {
                    "schema_version": 1,
                    "stats": {
                        "total_books": 1,
                        "finished_count": 0,
                        "in_progress_count": 0,
                        "abandoned_count": 0
                    },
                    "in_library_recommendations": [],
                    "discovery_recommendations": []
                },
                "schema_version": 1,
                "model_id": "test-model",
                "prompt_version": "1",
                "generated_at": "2026-05-20T10:00:00Z"
            }
        """
    }
}
