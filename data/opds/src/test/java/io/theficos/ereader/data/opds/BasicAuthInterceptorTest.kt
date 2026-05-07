package io.theficos.ereader.data.opds

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.CalibreCredentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class BasicAuthInterceptorTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `adds basic auth header`() {
        server.enqueue(MockResponse().setBody("ok"))
        val provider = { CalibreCredentials(server.url("/").toString(), "alice", "s3cret") }
        val client = OkHttpClient.Builder().addInterceptor(BasicAuthInterceptor(provider)).build()
        client.newCall(Request.Builder().url(server.url("/feed")).build()).execute().close()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization"))
            .isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString("alice:s3cret".toByteArray()))
    }

    @Test fun `omits header when no credentials`() {
        server.enqueue(MockResponse().setBody("ok"))
        val client = OkHttpClient.Builder().addInterceptor(BasicAuthInterceptor { null }).build()
        client.newCall(Request.Builder().url(server.url("/feed")).build()).execute().close()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isNull()
    }
}
