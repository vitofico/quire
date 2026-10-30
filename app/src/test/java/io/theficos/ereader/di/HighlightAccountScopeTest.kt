package io.theficos.ereader.di

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import org.junit.Test

class HighlightAccountScopeTest {
    private val basic = AccountCredentials.Basic("https://catalog.example", "Reader", "secret", "https://sync.example")

    @Test fun `scope separates subjects servers catalogs and schemes while preserving reauthentication`() {
        val original = basic.highlightScope()
        assertThat(original).hasLength(64)
        assertThat(basic.copy(password = "new password").highlightScope()).isEqualTo(original) // pragma: allowlist secret - dummy test credential
        assertThat(basic.copy(username = "reader").highlightScope()).isEqualTo(original)
        assertThat(basic.copy(baseUrl = "https://catalog.example/").highlightScope()).isEqualTo(original)
        assertThat(basic.copy(username = "other").highlightScope()).isNotEqualTo(original)
        assertThat(basic.copy(quireServerUrl = "https://other.example").highlightScope()).isNotEqualTo(original)
        assertThat(basic.copy(baseUrl = "https://other-catalog.example").highlightScope()).isNotEqualTo(original)
        val bearer = AccountCredentials.Bearer("https://sync.example", "reader", "token")
        assertThat(bearer.highlightScope()).isNotEqualTo(original)
        assertThat(bearer.copy(token = "new token").highlightScope()).isEqualTo(bearer.highlightScope())
    }

    @Test fun `generic OPDS gets a local account scope and logged out quotes remain unowned`() {
        val opds = AccountCredentials.OpdsOnly("https://catalog.example/opds?api_key=secret")
        assertThat(opds.highlightScope()).hasLength(64)
        assertThat(opds.highlightScope()).isNotEqualTo(basic.highlightScope())
        assertThat(opds.highlightScope()).isNotEqualTo(opds.copy(baseUrl = "https://other.example/opds").highlightScope())
        assertThat(opds.highlightSession()).isNull()
        val authenticated = opds.copy(username = "reader", password = "secret")
        assertThat(authenticated.highlightScope()).isNotEqualTo(opds.highlightScope())
        assertThat(authenticated.copy(username = "other").highlightScope()).isNotEqualTo(authenticated.highlightScope())
        assertThat(authenticated.copy(password = "new secret").highlightScope()).isEqualTo(authenticated.highlightScope()) // pragma: allowlist secret - dummy test credential
        assertThat((null as AccountCredentials?).highlightScope()).isEmpty()
        assertThat((null as AccountCredentials?).highlightSession()).isNull()
    }

    @Test fun `session freezes split server destination and authorization without logging credentials`() {
        val session = basic.highlightSession()!!
        assertThat(session.baseUrl).isEqualTo("https://sync.example")
        assertThat(session.authorization).isEqualTo("Basic UmVhZGVyOnNlY3JldA==")
        assertThat(session.toString()).doesNotContain("secret")
        assertThat(session.toString()).doesNotContain(session.authorization)
    }
}
