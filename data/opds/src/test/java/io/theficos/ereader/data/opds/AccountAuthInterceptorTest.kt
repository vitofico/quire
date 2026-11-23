package io.theficos.ereader.data.opds

import io.theficos.ereader.testing.awaitRequest
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AccountAuthInterceptorTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `emits basic header for basic account`() {
        server.enqueue(MockResponse().setBody("ok"))
        val provider = {
            AccountCredentials.Basic(server.url("/").toString(), "alice", "s3cret")
        }
        val client = OkHttpClient.Builder()
            .accountAuth(AccountAuthRoute.CATALOG, provider) {}
            .build()
        client.newCall(Request.Builder().url(server.url("/feed")).build()).execute().close()

        val recorded = server.awaitRequest()
        val expected = "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".toByteArray())
        assertThat(recorded.getHeader("Authorization")).isEqualTo(expected)
    }

    @Test fun `emits bearer header for bearer account`() {
        server.enqueue(MockResponse().setBody("ok"))
        val provider = {
            AccountCredentials.Bearer(server.url("/").toString(), "alice@example.com", "tok_abc123")
        }
        val client = OkHttpClient.Builder()
            .accountAuth(AccountAuthRoute.QUIRE, provider) {}
            .build()
        client.newCall(Request.Builder().url(server.url("/sync/v1/progress")).build()).execute().close()

        val recorded = server.awaitRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer tok_abc123")
    }

    @Test fun `omits header when no account configured`() {
        server.enqueue(MockResponse().setBody("ok"))
        val client = OkHttpClient.Builder()
            .accountAuth(AccountAuthRoute.CATALOG, { null }) {}
            .build()
        client.newCall(Request.Builder().url(server.url("/feed")).build()).execute().close()

        val recorded = server.awaitRequest()
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test fun `blocks out-of-scope Quire request before sending private payload`() {
        val provider = {
            AccountCredentials.Bearer(server.url("/").toString(), "alice@example.com", "stored_tok")
        }
        val client = OkHttpClient.Builder().accountAuth(AccountAuthRoute.QUIRE, provider) {}.build()
        val failure = runCatching {
            client.newCall(Request.Builder().url(server.url("/outside")).build()).execute().close()
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `does not attach credentials to foreign host`() {
        // Two servers: account is for `account` host, request goes to `foreign` host.
        val foreign = MockWebServer().apply { start() }
        try {
            foreign.enqueue(MockResponse().setBody("ok"))
            val provider = {
                AccountCredentials.Bearer(server.url("/").toString(), "alice@example.com", "tok_xyz")
            }
            val client = OkHttpClient.Builder()
                .accountAuth(AccountAuthRoute.CATALOG, provider) {}
                .build()
            client.newCall(Request.Builder().url(foreign.url("/cover.jpg")).build()).execute().close()

            val recorded = foreign.awaitRequest()
            assertThat(recorded.getHeader("Authorization")).isNull()
        } finally {
            foreign.shutdown()
        }
    }

    @Test fun `concurrent requests across schemes each get correct header`() {
        // Two MockWebServers, two clients, two schemes. Fire many parallel
        // calls through each and assert every recorded request has the
        // header that matches its own client's configured scheme.
        val basicServer = MockWebServer().apply { start() }
        val bearerServer = MockWebServer().apply { start() }
        try {
            val n = 40
            repeat(n) {
                basicServer.enqueue(MockResponse().setBody("ok"))
                bearerServer.enqueue(MockResponse().setBody("ok"))
            }
            val basicClient = OkHttpClient.Builder()
                .accountAuth(AccountAuthRoute.CATALOG, {
                    AccountCredentials.Basic(basicServer.url("/").toString(), "alice", "s3cret")
                }) {}
                .build()
            val bearerClient = OkHttpClient.Builder()
                .accountAuth(AccountAuthRoute.QUIRE, {
                    AccountCredentials.Bearer(bearerServer.url("/").toString(), "bob@example.com", "tok_zzz")
                }) {}
                .build()

            val pool = Executors.newFixedThreadPool(8)
            val latch = CountDownLatch(n * 2)
            val failures = AtomicInteger(0)
            repeat(n) { i ->
                pool.submit {
                    runCatching {
                        val target = if (i % 2 == 0) basicClient to basicServer else bearerClient to bearerServer
                        val path = if (i % 2 == 0) "/r$i" else "/sync/v1/r$i"
                        target.first.newCall(Request.Builder().url(target.second.url(path)).build())
                            .execute().close()
                    }.onFailure { failures.incrementAndGet() }
                    latch.countDown()
                }
                pool.submit {
                    runCatching {
                        val target = if (i % 2 == 0) bearerClient to bearerServer else basicClient to basicServer
                        val path = if (i % 2 == 0) "/sync/v1/q$i" else "/q$i"
                        target.first.newCall(Request.Builder().url(target.second.url(path)).build())
                            .execute().close()
                    }.onFailure { failures.incrementAndGet() }
                    latch.countDown()
                }
            }
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue()
            assertThat(failures.get()).isEqualTo(0)
            pool.shutdownNow()

            val expectedBasic = "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".toByteArray())
            repeat(n) {
                val basicRec = basicServer.takeRequest(1, TimeUnit.SECONDS)
                    ?: error("missing basic request")
                assertThat(basicRec.getHeader("Authorization")).isEqualTo(expectedBasic)
                val bearerRec = bearerServer.takeRequest(1, TimeUnit.SECONDS)
                    ?: error("missing bearer request")
                assertThat(bearerRec.getHeader("Authorization")).isEqualTo("Bearer tok_zzz")
            }
        } finally {
            basicServer.shutdown()
            bearerServer.shutdown()
        }
    }

    @Test fun `attaches basic header to override quireServerUrl host`() {
        val quire = MockWebServer().apply { start() }
        try {
            quire.enqueue(MockResponse().setBody("ok"))
            val provider = {
                AccountCredentials.Basic(
                    baseUrl = server.url("/").toString(),
                    username = "alice",
                    password = "s3cret",
                    quireServerUrl = quire.url("/").toString(),
                )
            }
            val client = OkHttpClient.Builder()
                .accountAuth(AccountAuthRoute.QUIRE, provider) {}
                .build()
            client.newCall(Request.Builder().url(quire.url("/ai/v1/config")).build())
                .execute().close()

            val recorded = quire.awaitRequest()
            val expected = "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".toByteArray())
            assertThat(recorded.getHeader("Authorization")).isEqualTo(expected)
        } finally {
            quire.shutdown()
        }
    }

    @Test fun `still attaches header to primary baseUrl host when override set`() {
        val quire = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("ok"))
            val provider = {
                AccountCredentials.Basic(
                    baseUrl = server.url("/").toString(),
                    username = "alice",
                    password = "s3cret",
                    quireServerUrl = quire.url("/").toString(),
                )
            }
            val client = OkHttpClient.Builder()
                .accountAuth(AccountAuthRoute.CATALOG, provider) {}
                .build()
            client.newCall(Request.Builder().url(server.url("/opds")).build())
                .execute().close()

            val recorded = server.awaitRequest()
            val expected = "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".toByteArray())
            assertThat(recorded.getHeader("Authorization")).isEqualTo(expected)
        } finally {
            quire.shutdown()
        }
    }

    // ---------- B2: 401 re-auth signal for bearer sessions ----------

    @Test fun `401 on a same-origin bearer request fires the re-auth callback`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        val calls = AtomicInteger(0)
        val provider = {
            AccountCredentials.Bearer(server.url("/").toString(), "alice@example.com", "stale_tok")
        }
        val client = OkHttpClient.Builder()
            .accountAuth(AccountAuthRoute.QUIRE, provider) { calls.incrementAndGet() }
            .build()
        val resp = client.newCall(Request.Builder().url(server.url("/ai/v1/config")).build()).execute()
        // Response is returned unchanged (no retry, no mutation).
        assertThat(resp.code).isEqualTo(401)
        resp.close()
        assertThat(calls.get()).isEqualTo(1)
        // The interceptor still attached our bearer header to the (rejected) request.
        assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo("Bearer stale_tok")
    }

    @Test fun `non-401 bearer response does not fire the re-auth callback`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val calls = AtomicInteger(0)
        val provider = {
            AccountCredentials.Bearer(server.url("/").toString(), "alice@example.com", "tok")
        }
        val client = OkHttpClient.Builder()
            .accountAuth(AccountAuthRoute.QUIRE, provider) { calls.incrementAndGet() }
            .build()
        client.newCall(Request.Builder().url(server.url("/sync/v1/progress")).build()).execute().close()
        assertThat(calls.get()).isEqualTo(0)
    }

    @Test fun `401 on a basic account does not fire the bearer re-auth callback`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        val calls = AtomicInteger(0)
        val provider = {
            AccountCredentials.Basic(server.url("/").toString(), "alice", "wrong-pw")
        }
        val client = OkHttpClient.Builder()
            .accountAuth(AccountAuthRoute.CATALOG, provider) { calls.incrementAndGet() }
            .build()
        client.newCall(Request.Builder().url(server.url("/opds")).build()).execute().close()
        assertThat(calls.get()).isEqualTo(0)
    }

    @Test fun `401 from a foreign host does not fire the re-auth callback`() {
        // We never attach the bearer to a foreign host, so a 401 there is not
        // attributable to our session and must not trigger re-auth.
        val foreign = MockWebServer().apply { start() }
        try {
            foreign.enqueue(MockResponse().setResponseCode(401))
            val calls = AtomicInteger(0)
            val provider = {
                AccountCredentials.Bearer(server.url("/").toString(), "alice@example.com", "tok")
            }
            val client = OkHttpClient.Builder()
                .accountAuth(AccountAuthRoute.CATALOG, provider) { calls.incrementAndGet() }
                .build()
            client.newCall(Request.Builder().url(foreign.url("/cover.jpg")).build()).execute().close()
            assertThat(calls.get()).isEqualTo(0)
        } finally {
            foreign.shutdown()
        }
    }

    @Test fun `does not attach to third foreign host even when override set`() {
        val quire = MockWebServer().apply { start() }
        val foreign = MockWebServer().apply { start() }
        try {
            foreign.enqueue(MockResponse().setBody("ok"))
            val provider = {
                AccountCredentials.Basic(
                    baseUrl = server.url("/").toString(),
                    username = "alice",
                    password = "s3cret",
                    quireServerUrl = quire.url("/").toString(),
                )
            }
            val client = OkHttpClient.Builder()
                .accountAuth(AccountAuthRoute.CATALOG, provider) {}
                .build()
            client.newCall(Request.Builder().url(foreign.url("/cover.jpg")).build())
                .execute().close()

            val recorded = foreign.awaitRequest()
            assertThat(recorded.getHeader("Authorization")).isNull()
        } finally {
            quire.shutdown()
            foreign.shutdown()
        }
    }

    // ---------- issue #101: OPDS-only accounts ----------

    @Test fun `opds account with credentials sends basic auth`() {
        val account = AccountCredentials.OpdsOnly(
            baseUrl = server.url("/api/opds/secret-key").toString(),
            username = "alice",
            password = "hunter2",
        )
        server.enqueue(MockResponse().setResponseCode(200))
        val client = OkHttpClient.Builder()
            .accountAuth(AccountAuthRoute.CATALOG, { account }) {}
            .build()
        client.newCall(
            Request.Builder().url(server.url("/api/opds/secret-key")).build()
        ).execute().close()

        val sent = server.awaitRequest().getHeader("Authorization")
        val expected = "Basic " + Base64.getEncoder()
            .encodeToString("alice:hunter2".toByteArray())
        assertThat(sent).isEqualTo(expected)
    }

    @Test fun `opds account without credentials sends no authorization header`() {
        val account = AccountCredentials.OpdsOnly(
            baseUrl = server.url("/api/opds/secret-key").toString(),
        )
        server.enqueue(MockResponse().setResponseCode(200))
        val client = OkHttpClient.Builder()
            .accountAuth(AccountAuthRoute.CATALOG, { account }) {}
            .build()
        client.newCall(
            Request.Builder().url(server.url("/api/opds/secret-key")).build()
        ).execute().close()

        assertThat(server.awaitRequest().getHeader("Authorization")).isNull()
    }

    @Test fun `opds credentials are not sent to a third-party host`() {
        val account = AccountCredentials.OpdsOnly(
            baseUrl = "https://kavita.example/api/opds/secret-key",
            username = "alice",
            password = "hunter2",
        )
        server.enqueue(MockResponse().setResponseCode(200))
        val client = OkHttpClient.Builder()
            .accountAuth(AccountAuthRoute.CATALOG, { account }) {}
            .build()
        client.newCall(Request.Builder().url(server.url("/cover.jpg")).build())
            .execute().close()

        assertThat(server.awaitRequest().getHeader("Authorization")).isNull()
    }

    @Test fun `a 401 to an opds account never raises the bearer re-auth signal`() {
        val account = AccountCredentials.OpdsOnly(
            baseUrl = server.url("/api/opds/secret-key").toString(),
            username = "alice",
            password = "hunter2",
        )
        var signalled = false
        server.enqueue(MockResponse().setResponseCode(401))
        val client = OkHttpClient.Builder()
            .accountAuth(AccountAuthRoute.CATALOG, { account }) { signalled = true }
            .build()
        client.newCall(
            Request.Builder().url(server.url("/api/opds/secret-key")).build()
        ).execute().close()

        assertThat(signalled).isFalse()
    }
    @Test fun `shared origin keeps catalog and Quire credentials segregated per request and redirect`() {
        val account = AccountCredentials.Bearer(
            baseUrl = server.url("/").toString(),
            email = "alice@example.com",
            token = "native-token",
            catalog = AccountCredentials.OpdsOnly(server.url("/calibre/opds").toString(), "reader", "secret"),
        )
        val reauths = AtomicInteger()
        val catalog = OkHttpClient.Builder()
            .accountAuth(AccountAuthRoute.CATALOG, { account }) { reauths.incrementAndGet() }
            .build()
        val quire = OkHttpClient.Builder()
            .accountAuth(AccountAuthRoute.QUIRE, { account }) { reauths.incrementAndGet() }
            .build()

        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/quire-admin/api"))
        server.enqueue(MockResponse().setResponseCode(401))
        catalog.newCall(Request.Builder().url(server.url("/calibre/download/1")).build()).execute().close()
        assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo("Basic cmVhZGVyOnNlY3JldA==")
        assertThat(server.awaitRequest().getHeader("Authorization")).isNull()
        assertThat(reauths.get()).isEqualTo(0)

        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "/calibre/download/1"))
        quire.newCall(
            Request.Builder()
                .url(server.url("/sync/v1/highlights"))
                .header("Authorization", "Bearer native-token")
                .post("private highlight".toRequestBody())
                .build(),
        ).execute().use { assertThat(it.code).isEqualTo(307) }
        assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo("Bearer native-token")
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(reauths.get()).isEqualTo(0)

        server.enqueue(MockResponse().setResponseCode(401))
        quire.newCall(Request.Builder().url(server.url("/library/v1/items")).build()).execute().close()
        assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo("Bearer native-token")
        assertThat(reauths.get()).isEqualTo(1)
    }

    @Test fun `dedicated Quire login never sends bearer through the catalog client`() {
        val account = AccountCredentials.Bearer(
            baseUrl = server.url("/").toString(),
            email = "alice@example.com",
            token = "native-token",
        )
        val client = OkHttpClient.Builder()
            .accountAuth(AccountAuthRoute.CATALOG, { account }) { error("Unexpected reauthentication") }
            .build()
        server.enqueue(MockResponse().setResponseCode(401))
        client.newCall(Request.Builder().url(server.url("/opds")).build()).execute().close()
        assertThat(server.awaitRequest().getHeader("Authorization")).isNull()
    }
    @Test fun `split origins authenticate catalog acquisitions and Quire operations independently`() {
        MockWebServer().use { quireServer ->
            quireServer.start()
            val account = AccountCredentials.Bearer(
                baseUrl = quireServer.url("/quire").toString(),
                email = "alice@example.com",
                token = "native-token",
                catalog = AccountCredentials.OpdsOnly(server.url("/calibre/opds").toString(), "reader", "secret", calibre = true),
            )
            val catalog = OkHttpClient.Builder().accountAuth(AccountAuthRoute.CATALOG, { account }) {}.build()
            val quire = OkHttpClient.Builder().accountAuth(AccountAuthRoute.QUIRE, { account }) {}.build()

            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", quireServer.url("/quire/sync/v1/progress")))
            quireServer.enqueue(MockResponse().setResponseCode(200))
            catalog.newCall(Request.Builder().url(server.url("/calibre/download/1")).build()).execute().close()
            assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo("Basic cmVhZGVyOnNlY3JldA==")
            assertThat(quireServer.awaitRequest().getHeader("Authorization")).isNull()

            quireServer.enqueue(MockResponse().setResponseCode(200))
            quire.newCall(Request.Builder().url(quireServer.url("/quire/sync/v1/progress")).build()).execute().close()
            assertThat(quireServer.awaitRequest().getHeader("Authorization")).isEqualTo("Bearer native-token")
        }
    }
}
