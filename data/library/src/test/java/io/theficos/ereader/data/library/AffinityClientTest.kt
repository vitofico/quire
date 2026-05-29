package io.theficos.ereader.data.library

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.metadata.MetadataBundle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AffinityClientTest {
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

    private fun sampleBody() = AffinityRequestBody(
        identity = AffinityIdentity(isbn = "9780441013593", metadataId = "m1"),
        bundle = MetadataBundle(title = "Dune", author = "Frank Herbert", isbn = "9780441013593"),
    )

    @Test
    fun `affinity parses full response and hits correct path`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "affinity_version": 3,
                  "owned": {"in_library": true, "reading_status": "finished"},
                  "score": 82,
                  "band": "high",
                  "reasons": [
                    {"kind": "author", "polarity": "positive", "message": "You like Frank Herbert"},
                    {"kind": "theme", "polarity": "negative", "message": "Not your usual genre"}
                  ],
                  "generated_at": "2026-05-29T00:00:00+00:00"
                }
                """.trimIndent()
            )
        )

        val out = client.affinity(sampleBody())

        assertThat(out.affinityVersion).isEqualTo(3)
        assertThat(out.score).isEqualTo(82)
        assertThat(out.band).isEqualTo("high")
        assertThat(out.owned).isNotNull()
        assertThat(out.owned!!.inLibrary).isTrue()
        assertThat(out.owned!!.readingStatus).isEqualTo("finished")
        assertThat(out.reasons).hasSize(2)
        assertThat(out.reasons[0].kind).isEqualTo("author")
        assertThat(out.reasons[0].polarity).isEqualTo("positive")
        assertThat(out.generatedAt).isEqualTo("2026-05-29T00:00:00+00:00")

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/library/v1/affinity")
        val body = req.body.readUtf8()
        assertThat(body).contains("\"metadata_id\":\"m1\"")
        assertThat(body).contains("\"title\":\"Dune\"")
    }

    @Test
    fun `affinity 404 raises LibraryHttpException with code 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not_found"))
        try {
            client.affinity(sampleBody())
            error("expected throw")
        } catch (e: LibraryHttpException) {
            assertThat(e.code).isEqualTo(404)
        }
    }

    @Test
    fun `affinity 401 raises LibraryHttpException with code 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        try {
            client.affinity(sampleBody())
            error("expected throw")
        } catch (e: LibraryHttpException) {
            assertThat(e.code).isEqualTo(401)
        }
    }
}
