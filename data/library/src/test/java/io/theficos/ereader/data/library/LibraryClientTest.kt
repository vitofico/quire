package io.theficos.ereader.data.library

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class LibraryClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LibraryClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = LibraryClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `getStats parses response and hits correct path`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"total_books":1,"finished_count":0,"in_progress_count":1,"top_authors":[{"name":"A","count":1}],"top_themes":[{"theme":"noir","count":1,"note":"v3+ insights only"}],"themes_caveat":"caveat"}"""
            )
        )
        val out = client.getStats()
        assertThat(out.totalBooks).isEqualTo(1)
        assertThat(out.topAuthors).hasSize(1)
        assertThat(out.themesCaveat).isEqualTo("caveat")
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("GET")
        assertThat(req.path).isEqualTo("/library/v1/stats")
    }

    @Test
    fun `401 raises LibraryHttpException with code 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        try {
            client.getStats()
            error("expected throw")
        } catch (e: LibraryHttpException) {
            assertThat(e.code).isEqualTo(401)
        }
    }

    @Test
    fun `404 raises LibraryHttpException with code 404`() = runTest {
        // Mode-gated server (PROGRESS_ENABLED=false) returns 404. Surface it
        // as a distinct error so the UI can render an actionable message.
        server.enqueue(MockResponse().setResponseCode(404).setBody("not_found"))
        try {
            client.getStats()
            error("expected throw")
        } catch (e: LibraryHttpException) {
            assertThat(e.code).isEqualTo(404)
        }
    }

    @Test
    fun `null baseUrl raises LibraryHttpException with code 0`() = runTest {
        val noBase = LibraryClient(
            baseUrlProvider = { null },
            http = OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build(),
        )
        try {
            noBase.getStats()
            error("expected throw")
        } catch (e: LibraryHttpException) {
            assertThat(e.code).isEqualTo(0)
            assertThat(e.body).contains("baseUrl not configured")
        }
    }
}
