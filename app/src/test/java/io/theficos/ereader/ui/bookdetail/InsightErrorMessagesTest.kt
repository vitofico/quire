package io.theficos.ereader.ui.bookdetail

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.ai.AiHttpException
import io.theficos.ereader.data.ai.AiProviderException
import io.theficos.ereader.data.ai.AiQuotaException
import io.theficos.ereader.data.ai.QuotaInfo
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Issue #102: every row of the spec's mapping table, pinned verbatim. */
class InsightErrorMessagesTest {

    private fun provider(hint: String?) = AiProviderException(
        code = 504,
        body = "{}",
        errorCode = "provider_timeout",
        serverMessage = "The AI provider did not answer within 120 seconds.",
        hint = hint,
        providerStatus = null,
    )

    @Test
    fun `quota exception names the reset date`() {
        val e = AiQuotaException(QuotaInfo(used = 3, limit = 3, resetsAt = "2026-09-17T00:00:00+00:00"))
        assertThat(insightErrorMessage(e))
            .isEqualTo("You've reached today's regeneration limit. Try again after 2026-09-17.")
    }

    @Test
    fun `plain 429 falls back to tomorrow`() {
        assertThat(insightErrorMessage(AiHttpException(429, "")))
            .isEqualTo("You've reached today's regeneration limit. Try again tomorrow.")
    }

    @Test
    fun `provider error shows the server's sentence and the hint`() {
        assertThat(insightErrorMessage(provider("Raise QUIRE_SERVER_AI_TIMEOUT_S for slow local models, or pick a faster model.")))
            .isEqualTo(
                "The AI provider did not answer within 120 seconds. " +
                    "Raise QUIRE_SERVER_AI_TIMEOUT_S for slow local models, or pick a faster model."
            )
    }

    @Test
    fun `provider error without a hint shows only the sentence`() {
        assertThat(insightErrorMessage(provider(null)))
            .isEqualTo("The AI provider did not answer within 120 seconds.")
    }

    @Test
    fun `other http errors keep the status code`() {
        assertThat(insightErrorMessage(AiHttpException(502, "bad gateway")))
            .isEqualTo("Couldn't generate insights (502).")
    }

    @Test
    fun `read and call timeouts say the server may still be generating`() {
        val expected = "The server took too long to answer. It may still be generating; try again in a minute."
        assertThat(insightErrorMessage(SocketTimeoutException("timeout"))).isEqualTo(expected)
        assertThat(insightErrorMessage(InterruptedIOException("timeout"))).isEqualTo(expected)
    }

    @Test
    fun `other io errors point at the connection`() {
        val expected = "Couldn't reach the server. Check the sync URL and your connection."
        assertThat(insightErrorMessage(UnknownHostException("quire.lan"))).isEqualTo(expected)
        assertThat(insightErrorMessage(IOException("reset"))).isEqualTo(expected)
    }

    @Test
    fun `anything else is the generic sentence`() {
        assertThat(insightErrorMessage(IllegalStateException("x"))).isEqualTo("Couldn't generate insights.")
    }

    @Test
    fun `provider error with a blank message falls back to the status code`() {
        val e = AiProviderException(
            code = 502,
            body = "",
            errorCode = "provider_error",
            serverMessage = "",
            hint = " ",
            providerStatus = null,
        )
        assertThat(insightErrorMessage(e)).isEqualTo("Couldn't generate insights (502).")
    }

    @Test
    fun `provider error with a blank hint shows only the sentence`() {
        assertThat(insightErrorMessage(provider(""))).isEqualTo("The AI provider did not answer within 120 seconds.")
    }
}
