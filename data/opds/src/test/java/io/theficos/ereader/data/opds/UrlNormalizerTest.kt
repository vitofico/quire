package io.theficos.ereader.data.opds

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrlNormalizerTest {

    @Test fun `trailing slash is trimmed`() {
        assertThat(normalizeBaseUrl("http://example.com/")).isEqualTo("http://example.com")
    }

    @Test fun `no path is preserved as empty`() {
        assertThat(normalizeBaseUrl("http://example.com")).isEqualTo("http://example.com")
    }

    @Test fun `path prefix is preserved`() {
        assertThat(normalizeBaseUrl("http://host/calibre/"))
            .isEqualTo("http://host/calibre")
        assertThat(normalizeBaseUrl("http://host/calibre"))
            .isEqualTo("http://host/calibre")
    }

    @Test fun `explicit non-default port is preserved`() {
        assertThat(normalizeBaseUrl("http://host:8083"))
            .isEqualTo("http://host:8083")
        assertThat(normalizeBaseUrl("https://host:8443"))
            .isEqualTo("https://host:8443")
    }

    @Test fun `default ports are dropped`() {
        // 80 is the http default; should not appear in canonical form.
        assertThat(normalizeBaseUrl("http://example.com:80/"))
            .isEqualTo("http://example.com")
        assertThat(normalizeBaseUrl("https://example.com:443/"))
            .isEqualTo("https://example.com")
    }

    @Test fun `IPv6 literal is bracketed`() {
        assertThat(normalizeBaseUrl("http://[::1]:8083/"))
            .isEqualTo("http://[::1]:8083")
    }

    @Test fun `IDN hostname is punycoded`() {
        // "münchen.example" → "xn--mnchen-3ya.example" via HttpUrl/IDNA.
        val canonical = normalizeBaseUrl("http://münchen.example/")
        assertThat(canonical).isNotNull()
        assertThat(canonical!!).contains("xn--")
    }

    @Test fun `scheme is required`() {
        assertThat(normalizeBaseUrl("example.com")).isNull()
        assertThat(normalizeBaseUrl("//example.com")).isNull()
    }

    @Test fun `unsupported scheme is rejected`() {
        assertThat(normalizeBaseUrl("ftp://example.com")).isNull()
        assertThat(normalizeBaseUrl("file:///etc/passwd")).isNull()
    }

    @Test fun `userinfo is rejected`() {
        assertThat(normalizeBaseUrl("http://user@example.com")).isNull()
        assertThat(normalizeBaseUrl("http://u:p@example.com")).isNull()
    }

    @Test fun `fragment is rejected`() {
        assertThat(normalizeBaseUrl("http://example.com/#x")).isNull()
    }

    @Test fun `empty input is rejected`() {
        assertThat(normalizeBaseUrl("")).isNull()
        assertThat(normalizeBaseUrl("   ")).isNull()
    }

    @Test fun `leading and trailing whitespace is trimmed`() {
        assertThat(normalizeBaseUrl("  http://example.com  "))
            .isEqualTo("http://example.com")
    }
}
