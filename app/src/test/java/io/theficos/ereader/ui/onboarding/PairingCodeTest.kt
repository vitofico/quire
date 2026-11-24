package io.theficos.ereader.ui.onboarding

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class PairingCodeTest {
    private val ticket = "synthetic-ticket-1234567890"

    @Test fun `a pairing code survives the round trip through its QR text`() {
        val code = PairingCode("https://books.example/quire?x=1&y=2", ticket)
        assertThat(parsePairingCode(code.toUri())).isEqualTo(code)
    }

    @Test fun `anything that is not a pairing code is ignored`() {
        for (text in listOf(
            "9780141439518",
            "https://books.example/?ticket=$ticket",
            "quire://pair?server=javascript%3Aalert(1)&ticket=$ticket",
            "quire://pair?server=https%3A%2F%2Fbooks.example&ticket=short",
            "quire://pair?server=https%3A%2F%2Fbooks.example",
            "quire://other?server=https%3A%2F%2Fbooks.example&ticket=$ticket",
        )) {
            assertThat(parsePairingCode(text)).isNull()
        }
    }

    @Test fun `a signed-in device asks its own server for a ticket`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ticket":"$ticket","exchange_path":"/auth/v1/pairing/exchange"}"""))
            server.enqueue(MockResponse().setResponseCode(401))
            val base = server.url("/quire").toString()
            val account = AccountCredentials.Bearer(base, "alice@example.com", "device-token")

            assertThat(requestPairingCode(OkHttpClient(), account)).isEqualTo(PairingCode(base, ticket))
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/quire/auth/v1/pairing")
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer device-token")
            assertThat(requestPairingCode(OkHttpClient(), account)).isNull()
        }
    }
}
