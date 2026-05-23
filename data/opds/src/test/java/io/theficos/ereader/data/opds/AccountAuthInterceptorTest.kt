package io.theficos.ereader.data.opds

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
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
            .addInterceptor(AccountAuthInterceptor(provider))
            .build()
        client.newCall(Request.Builder().url(server.url("/feed")).build()).execute().close()

        val recorded = server.takeRequest()
        val expected = "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".toByteArray())
        assertThat(recorded.getHeader("Authorization")).isEqualTo(expected)
    }

    @Test fun `emits bearer header for bearer account`() {
        server.enqueue(MockResponse().setBody("ok"))
        val provider = {
            AccountCredentials.Bearer(server.url("/").toString(), "alice@example.com", "tok_abc123")
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AccountAuthInterceptor(provider))
            .build()
        client.newCall(Request.Builder().url(server.url("/sync/v1/progress")).build()).execute().close()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer tok_abc123")
    }

    @Test fun `omits header when no account configured`() {
        server.enqueue(MockResponse().setBody("ok"))
        val client = OkHttpClient.Builder()
            .addInterceptor(AccountAuthInterceptor { null })
            .build()
        client.newCall(Request.Builder().url(server.url("/feed")).build()).execute().close()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test fun `preserves preset authorization header`() {
        server.enqueue(MockResponse().setBody("ok"))
        val provider = {
            AccountCredentials.Bearer(server.url("/").toString(), "alice@example.com", "stored_tok")
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AccountAuthInterceptor(provider))
            .build()
        client.newCall(
            Request.Builder()
                .url(server.url("/login"))
                .header("Authorization", "Bearer one-shot-handshake-token")
                .build()
        ).execute().close()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer one-shot-handshake-token")
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
                .addInterceptor(AccountAuthInterceptor(provider))
                .build()
            client.newCall(Request.Builder().url(foreign.url("/cover.jpg")).build()).execute().close()

            val recorded = foreign.takeRequest()
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
                .addInterceptor(AccountAuthInterceptor {
                    AccountCredentials.Basic(basicServer.url("/").toString(), "alice", "s3cret")
                })
                .build()
            val bearerClient = OkHttpClient.Builder()
                .addInterceptor(AccountAuthInterceptor {
                    AccountCredentials.Bearer(bearerServer.url("/").toString(), "bob@example.com", "tok_zzz")
                })
                .build()

            val pool = Executors.newFixedThreadPool(8)
            val latch = CountDownLatch(n * 2)
            val failures = AtomicInteger(0)
            repeat(n) { i ->
                pool.submit {
                    runCatching {
                        val target = if (i % 2 == 0) basicClient to basicServer else bearerClient to bearerServer
                        target.first.newCall(Request.Builder().url(target.second.url("/r$i")).build())
                            .execute().close()
                    }.onFailure { failures.incrementAndGet() }
                    latch.countDown()
                }
                pool.submit {
                    runCatching {
                        val target = if (i % 2 == 0) bearerClient to bearerServer else basicClient to basicServer
                        target.first.newCall(Request.Builder().url(target.second.url("/q$i")).build())
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
}
