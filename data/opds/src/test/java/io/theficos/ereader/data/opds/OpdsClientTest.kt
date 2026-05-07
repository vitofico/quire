package io.theficos.ereader.data.opds

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.CalibreCredentials
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OpdsClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpdsClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val creds = CalibreCredentials(server.url("/").toString().trimEnd('/'), "u", "p")
        val okHttp = OkHttpClient.Builder()
            .addInterceptor(BasicAuthInterceptor { creds })
            .build()
        client = OpdsClient(okHttp)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                val path = req.path?.substringBefore('?')
                return when (path) {
                    "/opds" -> MockResponse().setHeader("Content-Type", "application/atom+xml")
                        .setBody(resource("/opds/catalog-root.xml"))
                    "/opds/new" -> MockResponse().setHeader("Content-Type", "application/atom+xml")
                        .setBody(resource("/opds/catalog-feed.xml"))
                    "/opds/with-search" -> MockResponse().setHeader("Content-Type", "application/atom+xml")
                        .setBody(resource("/opds/catalog-with-search.xml"))
                    "/opds/with-direct-search" -> MockResponse().setHeader("Content-Type", "application/atom+xml")
                        .setBody(resource("/opds/catalog-with-direct-search.xml"))
                    "/opds/osd" -> MockResponse().setHeader("Content-Type", "application/opensearchdescription+xml")
                        .setBody(resource("/opds/opensearch.xml"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun resource(p: String) = javaClass.getResource(p)!!.readText()

    @Test fun `fetch root catalog returns navigation entries`() = runTest {
        val feed = client.fetch(server.url("/opds").toString())
        assertThat(feed.title).isEqualTo("calibre-web")
        assertThat(feed.navigation).hasSize(1)
        assertThat(feed.navigation[0].title).isEqualTo("All Books")
        assertThat(feed.navigation[0].href).endsWith("/opds/new")
    }

    @Test fun `fetch acquisition feed returns publications with epub links`() = runTest {
        val feed = client.fetch(server.url("/opds/new").toString())
        assertThat(feed.publications).hasSize(1)
        val pub = feed.publications[0]
        assertThat(pub.title).isEqualTo("The Sample Book")
        assertThat(pub.author).isEqualTo("Jane Doe")
        assertThat(pub.epubDownloadHref).endsWith("/opds/download/42/epub")
    }

    @Test fun `fetch acquisition feed extracts cover URL`() = runTest {
        val feed = client.fetch(server.url("/opds/new").toString())
        val pub = feed.publications[0]
        assertThat(pub.coverUrl).isNotNull()
        assertThat(pub.coverUrl).endsWith("/opds/cover/42")
    }

    @Test fun `feed without a search link exposes none`() = runTest {
        val feed = client.fetch(server.url("/opds").toString())
        assertThat(feed.searchLink).isNull()
    }

    @Test fun `feed with OpenSearch description link is parsed as description`() = runTest {
        val feed = client.fetch(server.url("/opds/with-search").toString())
        val link = feed.searchLink
        assertThat(link).isNotNull()
        assertThat(link!!.isDescription).isTrue()
        assertThat(link.href).endsWith("/opds/osd")
    }

    @Test fun `feed with templated atom search link is parsed as direct`() = runTest {
        val feed = client.fetch(server.url("/opds/with-direct-search").toString())
        val link = feed.searchLink
        assertThat(link).isNotNull()
        assertThat(link!!.isDescription).isFalse()
        assertThat(link.href).contains("{searchTerms}")
    }

    @Test fun `resolveSearchUrl substitutes searchTerms in direct template`() = runTest {
        val feed = client.fetch(server.url("/opds/with-direct-search").toString())
        val resolved = client.resolveSearchUrl(feed.searchLink!!, "moby dick")
        assertThat(resolved).endsWith("/opds/search/moby+dick")
    }

    @Test fun `resolveSearchUrl fetches description and substitutes template`() = runTest {
        val feed = client.fetch(server.url("/opds/with-search").toString())
        val resolved = client.resolveSearchUrl(feed.searchLink!!, "tolkien")
        assertThat(resolved).contains("/opds/search/tolkien")
        assertThat(resolved).doesNotContain("{")
        assertThat(resolved).doesNotContain("startIndex={")
    }

}
