package io.theficos.ereader.data.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Verifies that [AiRepository.setStyleTone] and [AiRepository.setStyleLanguage]
 * preserve their sibling style fields. Regression test for PR4: PR #9's
 * setStyleTone overwrote the entire style block, clobbering any future knob
 * (including the language we just added).
 */
class AiRepositoryStyleTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AiClient
    private lateinit var repo: AiRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AiClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = OkHttpClient.Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
        repo = AiRepository(client)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `setStyleTone preserves language from current prefs`() = runTest {
        // Refresh: server returns prefs with language=it.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":true,"sources_enabled":[],"daily_budget":0,"regen_daily_limit":0}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ai_enabled":true,"style":{"tone":"neutral","language":"it"}}"""))
        repo.refresh()

        // PUT response (we don't care about its content; we care about the request body).
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ai_enabled":true,"style":{"tone":"scholarly","language":"it"}}"""))
        repo.setStyleTone("scholarly")

        // Drain config + prefs GETs.
        server.takeRequest()
        server.takeRequest()
        // The PUT body must include the preserved language.
        val put = server.takeRequest()
        assertThat(put.method).isEqualTo("PUT")
        val body = put.body.readUtf8()
        assertThat(body).contains("\"tone\":\"scholarly\"")
        assertThat(body).contains("\"language\":\"it\"")
    }

    @Test
    fun `setStyleLanguage preserves tone from current prefs`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"configured":true,"sources_enabled":[],"daily_budget":0,"regen_daily_limit":0}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ai_enabled":true,"style":{"tone":"scholarly","language":"auto"}}"""))
        repo.refresh()

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ai_enabled":true,"style":{"tone":"scholarly","language":"es"}}"""))
        repo.setStyleLanguage("es")

        server.takeRequest()
        server.takeRequest()
        val put = server.takeRequest()
        val body = put.body.readUtf8()
        assertThat(body).contains("\"tone\":\"scholarly\"")
        assertThat(body).contains("\"language\":\"es\"")
    }

    @Test
    fun `setStyleLanguage falls back to defaults when no prefs are loaded`() = runTest {
        // No refresh; _prefs is null. The repo should fall back to AiStyle().copy(...).
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ai_enabled":true,"style":{"tone":"neutral","language":"fr"}}"""))
        repo.setStyleLanguage("fr")

        val put = server.takeRequest()
        val body = put.body.readUtf8()
        assertThat(body).contains("\"tone\":\"neutral\"")
        assertThat(body).contains("\"language\":\"fr\"")
    }
}
