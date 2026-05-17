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
                    "/opds/both" -> MockResponse().setHeader("Content-Type", "application/atom+xml")
                        .setBody(resource("/opds/catalog-feed-thumbnail-and-image.xml"))
                    "/opds/thumb-only" -> MockResponse().setHeader("Content-Type", "application/atom+xml")
                        .setBody(resource("/opds/catalog-feed-thumbnail-only.xml"))
                    "/opds/calibre-style" -> MockResponse().setHeader("Content-Type", "application/atom+xml")
                        .setBody(resource("/opds/catalog-feed-calibre-no-dc.xml"))
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

    @Test fun `fetch acquisition feed extracts webUrl from rel=alternate text-html link`() = runTest {
        val feed = client.fetch(server.url("/opds/new").toString())
        val pub = feed.publications[0]
        assertThat(pub.webUrl).isNotNull()
        assertThat(pub.webUrl).endsWith("/book/42")
    }

    @Test fun `webUrl derives from calibre-web download href when no alternate link is present`() = runTest {
        val feed = client.fetch(server.url("/opds/thumb-only").toString())
        val pub = feed.publications.single()
        // No rel=alternate text/html in this fixture, but the acquisition href is
        // /opds/download/42/epub — fallback should produce <origin>/book/42.
        assertThat(pub.webUrl).isNotNull()
        assertThat(pub.webUrl).endsWith("/book/42")
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

    @Test fun `cover prefers thumbnail rel when both rels present`() = runTest {
        val feed = client.fetch(server.url("/opds/both").toString())
        val pub = feed.publications.single()
        assertThat(pub.coverUrl).isNotNull()
        assertThat(pub.coverUrl).endsWith("/opds/cover/42/thumb")
    }

    @Test fun `cover uses thumbnail rel when only thumbnail is present`() = runTest {
        val feed = client.fetch(server.url("/opds/thumb-only").toString())
        val pub = feed.publications.single()
        assertThat(pub.coverUrl).isNotNull()
        assertThat(pub.coverUrl).endsWith("/opds/cover/42/thumb")
    }

    @Test fun `cover falls back to full-size image rel when thumbnail is absent`() = runTest {
        val feed = client.fetch(server.url("/opds/new").toString())
        val pub = feed.publications.single()
        assertThat(pub.coverUrl).isNotNull()
        assertThat(pub.coverUrl).endsWith("/opds/cover/42")
    }

    @Test fun `extracts dc identifier when present on entry`() = runTest {
        val feed = client.fetch(server.url("/opds/new").toString())
        val pub = feed.publications.single()
        assertThat(pub.opdsDcId).isEqualTo("urn:uuid:550e8400-e29b-41d4-a716-446655440000")
    }

    @Test fun `extracts calibreBookId when href matches calibre-web pattern`() = runTest {
        val feed = client.fetch(server.url("/opds/new").toString())
        val pub = feed.publications.single()
        assertThat(pub.calibreBookId).isEqualTo("42")
    }

    @Test fun `opdsDcId is null when entry has no dc identifier (calibre-web stock template)`() = runTest {
        val feed = client.fetch(server.url("/opds/calibre-style").toString())
        val matched = feed.publications.first { it.title == "Pattern-matched book" }
        val unmatched = feed.publications.first { it.title == "Non-calibre href book" }
        assertThat(matched.opdsDcId).isNull()
        assertThat(unmatched.opdsDcId).isNull()
    }

    @Test fun `calibreBookId set only when acquisition href matches pattern`() = runTest {
        val feed = client.fetch(server.url("/opds/calibre-style").toString())
        val matched = feed.publications.first { it.title == "Pattern-matched book" }
        val unmatched = feed.publications.first { it.title == "Non-calibre href book" }
        assertThat(matched.calibreBookId).isEqualTo("77")
        assertThat(unmatched.calibreBookId).isNull()
    }
}
