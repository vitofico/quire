package io.theficos.ereader.data.opds

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrlRedactionTest {

    @Test fun `drops the path so an embedded key cannot leak`() {
        val redacted = redactUrl("https://kavita.example/api/opds/secret-key/series/70")
        assertThat(redacted).isEqualTo("https://kavita.example/…")
        assertThat(redacted).doesNotContain("secret-key")
    }

    @Test fun `drops the query so a key parameter cannot leak`() {
        val redacted = redactUrl("https://feed.example/opds?apiKey=secret-key")
        assertThat(redacted).doesNotContain("secret-key")
        assertThat(redacted).doesNotContain("apiKey")
    }

    @Test fun `keeps a non-default port so the message stays diagnosable`() {
        assertThat(redactUrl("http://192.168.1.10:8083/opds/new"))
            .isEqualTo("http://192.168.1.10:8083/…")
    }

    @Test fun `drops a default port`() {
        assertThat(redactUrl("https://calibre.example:443/opds"))
            .isEqualTo("https://calibre.example/…")
    }

    @Test fun `unparseable input redacts to a constant`() {
        assertThat(redactUrl("not a url at all")).isEqualTo("<redacted url>")
    }

    @Test fun `never echoes userinfo`() {
        assertThat(redactUrl("https://alice:hunter2@feed.example/opds"))
            .doesNotContain("hunter2")
    }
}
