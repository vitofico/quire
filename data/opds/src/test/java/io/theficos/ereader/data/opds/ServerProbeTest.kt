package io.theficos.ereader.data.opds

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServerProbeTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() { server.shutdown() }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun probe(timeoutMs: Long = 5_000L): ServerProbe = ServerProbe(
        client = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(SafeRedirectInterceptor())
            .build(),
        perRequestTimeoutMs = timeoutMs,
    )

    @Test fun `calibre-web detected via 200 atom-xml`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when (req.path) {
                "/opds" -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/atom+xml;profile=opds-catalog")
                    .setBody("<feed xmlns=\"http://www.w3.org/2005/Atom\"></feed>")
                "/auth/v1/login" -> MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val result = probe().probe(baseUrl())
        assertThat(result).isInstanceOf(ServerProbeResult.Calibre::class.java)
        assertThat((result as ServerProbeResult.Calibre).canonicalBaseUrl).isEqualTo(baseUrl())
    }

    @Test fun `calibre-web detected via 401 with Basic challenge`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when (req.path) {
                "/opds" -> MockResponse()
                    .setResponseCode(401)
                    .setHeader("WWW-Authenticate", "Basic realm=\"calibre\"")
                "/auth/v1/login" -> MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val result = probe().probe(baseUrl())
        assertThat(result).isInstanceOf(ServerProbeResult.Calibre::class.java)
    }

    @Test fun `quire_server detected via 405 with Allow POST`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when (req.path) {
                "/opds" -> MockResponse().setResponseCode(404)
                "/auth/v1/login" -> MockResponse()
                    .setResponseCode(405)
                    .setHeader("Allow", "POST")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val result = probe().probe(baseUrl())
        assertThat(result).isInstanceOf(ServerProbeResult.NativeAuth::class.java)
    }

    @Test fun `405 without Allow POST does not count as NativeAuth`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when (req.path) {
                "/opds" -> MockResponse().setResponseCode(404)
                "/auth/v1/login" -> MockResponse()
                    .setResponseCode(405)
                    .setHeader("Allow", "GET")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val result = probe().probe(baseUrl())
        assertThat(result).isInstanceOf(ServerProbeResult.Unknown::class.java)
    }

    @Test fun `both endpoints positive returns Both`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when (req.path) {
                "/opds" -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/atom+xml")
                    .setBody("<feed/>")
                "/auth/v1/login" -> MockResponse()
                    .setResponseCode(405)
                    .setHeader("Allow", "POST")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val result = probe().probe(baseUrl())
        assertThat(result).isInstanceOf(ServerProbeResult.Both::class.java)
    }

    @Test fun `neither endpoint definitive returns Unknown with diagnostics`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(404)
        }
        val result = probe().probe(baseUrl())
        assertThat(result).isInstanceOf(ServerProbeResult.Unknown::class.java)
        val diagnostics = (result as ServerProbeResult.Unknown).diagnostics
        assertThat(diagnostics).contains("/opds")
        assertThat(diagnostics).contains("/auth/v1/login")
    }

    @Test fun `malformed URL returns Error with MalformedUrl`() = runTest {
        val result = probe().probe("not a url")
        val err = result as ServerProbeResult.Error
        assertThat(err.reason).isEqualTo(ProbeError.MalformedUrl)
    }

    @Test fun `bare host with no scheme is rejected`() = runTest {
        val result = probe().probe("example.com")
        val err = result as ServerProbeResult.Error
        assertThat(err.reason).isEqualTo(ProbeError.MalformedUrl)
    }

    @Test fun `URL with embedded credentials is rejected`() = runTest {
        val result = probe().probe("http://user:pass@example.com")
        val err = result as ServerProbeResult.Error
        assertThat(err.reason).isEqualTo(ProbeError.MalformedUrl)
    }

    @Test fun `URL with fragment is rejected`() = runTest {
        val result = probe().probe("http://example.com/#frag")
        val err = result as ServerProbeResult.Error
        assertThat(err.reason).isEqualTo(ProbeError.MalformedUrl)
    }

    @Test fun `cross-origin redirect is rejected`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when (req.path) {
                "/opds" -> MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://evil.example/opds")
                "/auth/v1/login" -> MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val result = probe().probe(baseUrl())
        // Both probes negative; one with RedirectRejected — surface that.
        val err = result as ServerProbeResult.Error
        assertThat(err.reason).isEqualTo(ProbeError.RedirectRejected)
    }

    @Test fun `same-host redirect is followed`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when (req.path) {
                "/opds" -> MockResponse()
                    .setResponseCode(301)
                    .setHeader("Location", "${baseUrl()}/opds/index")
                "/opds/index" -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/atom+xml")
                    .setBody("<feed/>")
                "/auth/v1/login" -> MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val result = probe().probe(baseUrl())
        assertThat(result).isInstanceOf(ServerProbeResult.Calibre::class.java)
    }

    @Test fun `slow server never produces a positive classification`() = runTest {
        // NO_RESPONSE keeps the socket open without writing anything, so the
        // probe's per-call timeout must fire. Critical contract: a timeout
        // must NEVER imply a scheme. The exact mapping (Error vs Unknown)
        // depends on whether SocketTimeoutException or a generic
        // InterruptedIOException is thrown, but in either case the result
        // cannot be Calibre/NativeAuth/Both.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse =
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }
        val result = probe(timeoutMs = 750L).probe(baseUrl())
        assertThat(result).isNotInstanceOf(ServerProbeResult.Calibre::class.java)
        assertThat(result).isNotInstanceOf(ServerProbeResult.NativeAuth::class.java)
        assertThat(result).isNotInstanceOf(ServerProbeResult.Both::class.java)
    }

    @Test fun `unsupported scheme (ftp) returns MalformedUrl`() = runTest {
        val result = probe().probe("ftp://example.com")
        val err = result as ServerProbeResult.Error
        assertThat(err.reason).isEqualTo(ProbeError.MalformedUrl)
    }

    @Test fun `base path is preserved across probe URL construction`() = runTest {
        // Simulate a calibre-web deployment behind /calibre/. MockWebServer
        // exposes a root, so we test that probe(serverUrl + "/calibre/")
        // queries /calibre/opds (and that the request URL is the
        // canonical, trimmed form).
        val seenPaths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                req.path?.let { seenPaths.add(it) }
                return when (req.path) {
                    "/calibre/opds" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/atom+xml")
                        .setBody("<feed/>")
                    "/calibre/auth/v1/login" -> MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val result = probe().probe(baseUrl() + "/calibre/")
        assertThat(result).isInstanceOf(ServerProbeResult.Calibre::class.java)
        val canonical = (result as ServerProbeResult.Calibre).canonicalBaseUrl
        // Trailing slash trimmed, /calibre/ prefix preserved.
        assertThat(canonical).endsWith("/calibre")
        assertThat(canonical).doesNotContain("/calibre/")
        assertThat(seenPaths).contains("/calibre/opds")
    }
}
