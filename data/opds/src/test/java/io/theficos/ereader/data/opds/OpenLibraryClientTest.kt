package io.theficos.ereader.data.opds

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class OpenLibraryClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client() = OpenLibraryClient(
        http = OkHttpClient.Builder().build(),
        baseUrl = server.url("/").toString().trimEnd('/'),
    )

    @Test fun `parses title author and subjects for a found isbn`() {
        server.enqueue(
            MockResponse().setBody(
                """{"ISBN:9780261103573":{"title":"The Hobbit",""" +
                    """"authors":[{"name":"J.R.R. Tolkien"}],""" +
                    """"subjects":[{"name":"Fantasy"},{"name":"Fiction"}]}}""",
            ),
        )

        val result = client().lookupByIsbn("9780261103573")

        assertThat(result).isNotNull()
        assertThat(result!!.title).isEqualTo("The Hobbit")
        assertThat(result.author).isEqualTo("J.R.R. Tolkien")
        assertThat(result.isbn).isEqualTo("9780261103573")
        assertThat(result.subjects).contains("Fantasy")
    }

    @Test fun `returns null when record is absent`() {
        server.enqueue(MockResponse().setBody("{}"))

        assertThat(client().lookupByIsbn("9780261103573")).isNull()
    }

    @Test fun `returns null when record present but has no title`() {
        server.enqueue(MockResponse().setBody("""{"ISBN:9780261103573":{}}"""))

        assertThat(client().lookupByIsbn("9780261103573")).isNull()
    }
}
