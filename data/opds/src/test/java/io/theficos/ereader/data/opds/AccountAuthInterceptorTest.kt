package io.theficos.ereader.data.opds

import io.theficos.ereader.testing.awaitRequest
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.CatalogSession
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
            .catalogAuth { session(provider()) }
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
            .quireAuth(provider) {}
            .build()
        client.newCall(Request.Builder().url(server.url("/sync/v1/progress")).build()).execute().close()

        val recorded = server.awaitRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer tok_abc123")
    }

    @Test fun `omits header when no account configured`() {
        server.enqueue(MockResponse().setBody("ok"))
        val client = OkHttpClient.Builder()
            .catalogAuth { session(null) }
            .build()
        client.newCall(Request.Builder().url(server.url("/feed")).build()).execute().close()

        val recorded = server.awaitRequest()
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test fun `blocks out-of-scope Quire request before sending private payload`() {
        val provider = {
            AccountCredentials.Bearer(server.url("/").toString(), "alice@example.com", "stored_tok")
        }
        val client = OkHttpClient.Builder().quireAuth(provider) {}.build()
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
                .catalogAuth { session(provider()) }
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
                .catalogAuth {
                    session(AccountCredentials.Basic(basicServer.url("/").toString(), "alice", "s3cret"))
                }
                .build()
            val bearerClient = OkHttpClient.Builder()
                .quireAuth({
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
                .quireAuth(provider) {}
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
                .catalogAuth { session(provider()) }
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
            .quireAuth(provider) { calls.incrementAndGet() }
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
            .quireAuth(provider) { calls.incrementAndGet() }
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
            .catalogAuth { session(provider()) }
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
                .catalogAuth { session(provider()) }
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
                .catalogAuth { session(provider()) }
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
            .catalogAuth { session(account) }
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
            .catalogAuth { session(account) }
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
            .catalogAuth { session(account) }
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
            .catalogAuth { session(account) }
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
        )
        val saved = AccountCredentials.OpdsOnly(server.url("/calibre/opds").toString(), "reader", "secret")
        val reauths = AtomicInteger()
        val catalog = OkHttpClient.Builder()
            .catalogAuth { session(account, saved) }
            .build()
        val quire = OkHttpClient.Builder()
            .quireAuth({ account }) { reauths.incrementAndGet() }
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
            .catalogAuth { session(account) }
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
            )
            val saved = AccountCredentials.OpdsOnly(server.url("/calibre/opds").toString(), "reader", "secret", calibre = true)
            val catalog = OkHttpClient.Builder().catalogAuth { session(account, saved) }.build()
            val quire = OkHttpClient.Builder().quireAuth({ account }) {}.build()

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
    @Test fun `Quire Web root and session routes never receive catalog credentials`() {
        val base = server.url("/").toString()
        val catalog = AccountCredentials.OpdsOnly(server.url("/opds").toString(), "reader", "secret")
        val sessions = listOf(
            session(AccountCredentials.Basic(base, "reader", "secret")),
            session(AccountCredentials.Bearer(base, "reader@example.com", "native-token"), catalog),
            session(null, catalog),
        )
        for (session in sessions) {
            val client = OkHttpClient.Builder().catalogAuth { session }.build()
            for (route in listOf("/quire", "/quire/v1/session", "/%71uire/v1/session", "/cloud/account")) {
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", route))
                server.enqueue(MockResponse().setResponseCode(401))
                client.newCall(Request.Builder().url(server.url("/download/1")).build()).execute().close()
                assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo("Basic cmVhZGVyOnNlY3JldA==")
                assertThat(server.awaitRequest().getHeader("Authorization")).isNull()

                server.enqueue(MockResponse().setResponseCode(401))
                client.newCall(Request.Builder().url(server.url(route)).build()).execute().close()
                assertThat(server.awaitRequest().getHeader("Authorization")).isNull()
            }
        }
    }

    // ---------- saved catalogs: each login only to its own catalog ----------

    private fun basic(user: String, pass: String) =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())

    private fun get(client: OkHttpClient, url: okhttp3.HttpUrl) =
        client.newCall(Request.Builder().url(url).build()).execute().close()

    @Test fun `each saved catalog gets its own login and only its own`() {
        MockWebServer().use { other ->
            other.start()
            val a = AccountCredentials.OpdsOnly(server.url("/opds").toString(), "alice", "a-pass")
            val b = AccountCredentials.OpdsOnly(other.url("/feed").toString(), "bob", "b-pass")
            val client = OkHttpClient.Builder().catalogAuth { CatalogSession(listOf(a, b), a, null) }.build()

            server.enqueue(MockResponse())
            other.enqueue(MockResponse())
            get(client, server.url("/opds/new"))
            get(client, other.url("/feed/books/1"))

            assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo(basic("alice", "a-pass"))
            // Not the catalog being browsed, but its own server still gets its own login.
            assertThat(other.awaitRequest().getHeader("Authorization")).isEqualTo(basic("bob", "b-pass"))
        }
    }

    @Test fun `two catalogs on one host keep their logins apart by path`() {
        val kavita = AccountCredentials.OpdsOnly(server.url("/kavita/api/opds/KEY").toString(), "kav", "k-pass")
        val calibre = AccountCredentials.OpdsOnly(server.url("/calibre/opds").toString(), "cal", "c-pass", calibre = true)
        val client = OkHttpClient.Builder()
            .catalogAuth { CatalogSession(listOf(kavita, calibre), kavita, null) }
            .build()

        repeat(3) { server.enqueue(MockResponse()) }
        get(client, server.url("/calibre/opds/download/1"))
        get(client, server.url("/kavita/api/opds/KEY/series/2"))
        get(client, server.url("/calibre/cover/1"))

        assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo(basic("cal", "c-pass"))
        assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo(basic("kav", "k-pass"))
        assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo(basic("cal", "c-pass"))
    }

    @Test fun `a path neither catalog owns goes to the one being browsed, never a guess`() {
        val first = AccountCredentials.OpdsOnly(server.url("/one/opds").toString(), "one", "1-pass")
        val second = AccountCredentials.OpdsOnly(server.url("/two/opds").toString(), "two", "2-pass")

        repeat(2) { server.enqueue(MockResponse()) }
        get(OkHttpClient.Builder().catalogAuth { CatalogSession(listOf(first, second), second, null) }.build(),
            server.url("/shared/cover/1"))
        get(OkHttpClient.Builder().catalogAuth { CatalogSession(listOf(first, second), null, null) }.build(),
            server.url("/shared/cover/1"))

        assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo(basic("two", "2-pass"))
        assertThat(server.awaitRequest().getHeader("Authorization")).isNull()
    }

    @Test fun `same host on another port or scheme is another catalog`() {
        MockWebServer().use { otherPort ->
            otherPort.start()
            val plain = AccountCredentials.OpdsOnly(server.url("/opds").toString(), "alice", "a-pass")
            val https = plain.copy(baseUrl = server.url("/opds").newBuilder().scheme("https").build().toString(),
                username = "sec", password = "s-pass")
            val defaultPort = plain.copy(baseUrl = "http://${server.hostName}/opds", username = "def", password = "d-pass")
            val client = OkHttpClient.Builder()
                .catalogAuth { CatalogSession(listOf(https, defaultPort, plain), https, null) }
                .build()

            server.enqueue(MockResponse())
            otherPort.enqueue(MockResponse())
            get(client, server.url("/opds"))
            get(client, otherPort.url("/opds"))

            assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo(basic("alice", "a-pass"))
            assertThat(otherPort.awaitRequest().getHeader("Authorization")).isNull()
        }
    }

    @Test fun `a redirect into another catalog drops the login`() {
        MockWebServer().use { other ->
            other.start()
            val a = AccountCredentials.OpdsOnly(server.url("/opds").toString(), "alice", "a-pass")
            val b = AccountCredentials.OpdsOnly(other.url("/feed").toString(), "bob", "b-pass")
            val client = OkHttpClient.Builder().catalogAuth { CatalogSession(listOf(a, b), a, null) }.build()

            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/feed/1")))
            other.enqueue(MockResponse())
            get(client, server.url("/opds/download/1"))

            assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo(basic("alice", "a-pass"))
            assertThat(other.awaitRequest().getHeader("Authorization")).isNull()
        }
    }

    @Test fun `a redirect between two catalogs on one host drops the login`() {
        val a = AccountCredentials.OpdsOnly(server.url("/a/opds").toString(), "alice", "a-pass")
        val b = AccountCredentials.OpdsOnly(server.url("/b/opds").toString(), "bob", "b-pass")
        val client = OkHttpClient.Builder().catalogAuth { CatalogSession(listOf(a, b), a, null) }.build()

        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/b/opds/1"))
        server.enqueue(MockResponse())
        get(client, server.url("/a/opds/1"))

        assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo(basic("alice", "a-pass"))
        assertThat(server.awaitRequest().getHeader("Authorization")).isNull()
    }

    @Test fun `a catalog on the Quire origin never receives the bearer or reaches Quire routes`() {
        val account = AccountCredentials.Bearer(server.url("/").toString(), "alice@example.com", "native-token")
        val catalog = AccountCredentials.OpdsOnly(server.url("/opds").toString(), "reader", "secret")
        val client = OkHttpClient.Builder().catalogAuth { session(account, catalog) }.build()

        for (route in listOf("/opds/1", "/catalogs/v1", "/sync/v1/progress", "/auth/v1/me")) {
            server.enqueue(MockResponse())
            get(client, server.url(route))
            val sent = server.awaitRequest().getHeader("Authorization")
            if (route == "/opds/1") assertThat(sent).isEqualTo(basic("reader", "secret")) else assertThat(sent).isNull()
        }
        // An empty segment the server may merge away still reaches a Quire route.
        for (path in listOf("//library/v1/items", "/opds//1")) {
            server.enqueue(MockResponse())
            get(client, server.url("/").newBuilder().encodedPath(path).build())
            assertThat(server.awaitRequest().getHeader("Authorization")).isNull()
        }
    }

    @Test fun `the Quire client never sends a catalog login and reaches the catalogs API`() {
        val account = AccountCredentials.Bearer(server.url("/").toString(), "alice@example.com", "native-token")
        val client = OkHttpClient.Builder().quireAuth({ account }) {}.build()

        server.enqueue(MockResponse())
        get(client, server.url("/catalogs/v1"))
        assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo("Bearer native-token")

        val blocked = runCatching { get(client, server.url("/opds/1")) }.exceptionOrNull()
        assertThat(blocked).isInstanceOf(IOException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `the Quire client signs book details and their covers`() {
        val account = AccountCredentials.Bearer(server.url("/").toString(), "alice@example.com", "native-token")
        val client = OkHttpClient.Builder().quireAuth({ account }) {}.build()

        for (path in listOf("/details/v1/lookup", "/details/v1/covers/abc?v=1")) {
            server.enqueue(MockResponse())
            get(client, server.url(path))
            assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo("Bearer native-token")
        }
    }

    @Test fun `the Quire client signs PDF conversion and the catalog client never does`() {
        val account = AccountCredentials.Bearer(server.url("/").toString(), "alice@example.com", "native-token")
        val client = OkHttpClient.Builder().quireAuth({ account }) {}.build()
        for (path in listOf("/convert/v1/capabilities", "/convert/v1/jobs/abc/source")) {
            server.enqueue(MockResponse())
            get(client, server.url(path))
            assertThat(server.awaitRequest().getHeader("Authorization")).isEqualTo("Bearer native-token")
        }

        val catalog = AccountCredentials.OpdsOnly(server.url("/").toString(), "reader", "secret")
        val catalogs = OkHttpClient.Builder().catalogAuth { session(account, catalog) }.build()
        server.enqueue(MockResponse())
        get(catalogs, server.url("/convert/v1/capabilities"))
        assertThat(server.awaitRequest().getHeader("Authorization")).isNull()
    }

    @Test fun `a caller's own Authorization header never survives the catalog client`() {
        val catalog = AccountCredentials.OpdsOnly(server.url("/opds").toString())
        val client = OkHttpClient.Builder().catalogAuth { CatalogSession(listOf(catalog), catalog, null) }.build()
        server.enqueue(MockResponse())
        client.newCall(
            Request.Builder().url(server.url("/opds")).header("Authorization", "Bearer leaked").build(),
        ).execute().close()
        assertThat(server.awaitRequest().getHeader("Authorization")).isNull()
    }
}

/**
 * The session the credential store builds: a legacy Calibre-Web sign-in's own catalog first,
 * then [catalogs], browsing the first.
 */
internal fun session(account: AccountCredentials?, vararg catalogs: AccountCredentials.OpdsOnly): CatalogSession {
    val pinned = (account as? AccountCredentials.Basic)?.let {
        AccountCredentials.OpdsOnly(it.baseUrl.trimEnd('/') + "/opds", it.username, it.password, calibre = true)
    }
    val all = listOfNotNull(pinned) + catalogs
    return CatalogSession(all, all.firstOrNull(), account)
}

/** Before saved catalogs a catalog-only device was signed in as its catalog. */
internal fun session(catalog: AccountCredentials.OpdsOnly): CatalogSession = CatalogSession(listOf(catalog), catalog, null)
