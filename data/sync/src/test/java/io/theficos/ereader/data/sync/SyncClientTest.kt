package io.theficos.ereader.data.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class SyncClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: SyncClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = SyncClient(
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            okHttp = OkHttpClient(),
        )
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `push returns success on 200`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"results":[{"document":{"metadata_id":"m","content_hash":"h"},"status":"accepted","server_client_updated_at":"2026-05-05T12:00:00+00:00"}]}"""
        ))
        val r = client.pushProgress(ProgressPushBody(listOf(
            ProgressItemDto(DocumentIdDto("m", "h"), "loc", 0.1, "2026-05-05T12:00:00+00:00")
        )))
        check(r is SyncResult.Success)
        assertThat(r.value.results).hasSize(1)
    }

    @Test fun `pull returns Unauthorized on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val r = client.pullProgress("2026-01-01T00:00:00Z")
        assertThat(r).isInstanceOf(SyncResult.Unauthorized::class.java)
    }

    @Test fun `null base url short-circuits to Unauthorized`() = runTest {
        val nullClient = SyncClient(baseUrlProvider = { null }, okHttp = OkHttpClient())
        val r = nullClient.pullProgress("2026-01-01T00:00:00Z")
        assertThat(r).isInstanceOf(SyncResult.Unauthorized::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `malformed successful responses return a bounded failure for push and pull`() = runTest {
        for (body in listOf("{", "<html><body>private proxy login page</body></html>", "{}")) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(body))
            val push = client.pushProgress(ProgressPushBody(emptyList()))
            assertThat(push).isEqualTo(SyncResult.HttpFailure(200, "Invalid sync response"))

            server.enqueue(MockResponse().setResponseCode(200).setBody(body))
            val pull = client.pullProgress("2026-01-01T00:00:00Z")
            assertThat(pull).isEqualTo(SyncResult.HttpFailure(200, "Invalid sync response"))
        }
    }

    @Test fun `push and pull perform blocking HTTP away from the caller thread`() = runTest {
        val caller = Thread.currentThread()
        var requests = 0
        val mainSafeClient = SyncClient(
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            okHttp = OkHttpClient.Builder().addInterceptor { chain ->
                assertThat(Thread.currentThread()).isNotSameInstanceAs(caller)
                requests++
                chain.proceed(chain.request())
            }.build(),
        )
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        server.enqueue(MockResponse().setBody(
            """{"items":[],"server_time":"2026-01-01T00:00:00Z"}"""
        ))

        assertThat(mainSafeClient.pushProgress(ProgressPushBody(emptyList())))
            .isInstanceOf(SyncResult.Success::class.java)
        assertThat(mainSafeClient.pullProgress("2026-01-01T00:00:00Z"))
            .isInstanceOf(SyncResult.Success::class.java)
        assertThat(requests).isEqualTo(2)
    }
}
