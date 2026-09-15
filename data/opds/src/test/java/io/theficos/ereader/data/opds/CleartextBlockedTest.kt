package io.theficos.ereader.data.opds

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
import java.net.UnknownServiceException

class CleartextBlockedTest {

    private val refusal = UnknownServiceException(
        "CLEARTEXT communication to books.example.com not permitted by network security policy",
    )

    @Test fun `recognises the platform refusal`() {
        assertThat(cleartextBlockedMessage(refusal)).isEqualTo(CLEARTEXT_BLOCKED_MESSAGE)
    }

    @Test fun `recognises it when wrapped`() {
        assertThat(cleartextBlockedMessage(IOException("fetch failed", refusal)))
            .isEqualTo(CLEARTEXT_BLOCKED_MESSAGE)
    }

    @Test fun `returns null for an unrelated failure`() {
        assertThat(cleartextBlockedMessage(IOException("connection reset"))).isNull()
    }

    @Test fun `returns null for a failure with no message`() {
        assertThat(cleartextBlockedMessage(IOException())).isNull()
    }

    @Test fun `survives a cyclic cause chain`() {
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        assertThat(cleartextBlockedMessage(b)).isNull()
    }

    @Test fun `the message names no host or URL`() {
        // A generic OPDS catalog URL can itself be an API key (issue #101).
        assertThat(CLEARTEXT_BLOCKED_MESSAGE).doesNotContain("books.example.com")
    }
}
