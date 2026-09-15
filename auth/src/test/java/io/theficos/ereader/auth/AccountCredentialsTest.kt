package io.theficos.ereader.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccountCredentialsTest {

    @Test fun `opds account reports the OPDS scheme`() {
        val account = AccountCredentials.OpdsOnly("https://kavita.example/api/opds/secret-key")
        assertThat(account.scheme).isEqualTo(AuthScheme.OPDS)
    }

    @Test fun `opds account keeps the catalog url verbatim`() {
        val url = "https://kavita.example/api/opds/secret-key"
        assertThat(AccountCredentials.OpdsOnly(url).baseUrl).isEqualTo(url)
    }

    @Test fun `opds subject is stable and derived from the url`() {
        val a = AccountCredentials.OpdsOnly("https://kavita.example/api/opds/secret-key")
        val b = AccountCredentials.OpdsOnly("https://kavita.example/api/opds/secret-key")
        assertThat(a.subject).isEqualTo(b.subject)
        assertThat(a.subject).startsWith("opds:")
    }

    @Test fun `opds subject differs between catalogs`() {
        val a = AccountCredentials.OpdsOnly("https://kavita.example/api/opds/key-one")
        val b = AccountCredentials.OpdsOnly("https://kavita.example/api/opds/key-two")
        assertThat(a.subject).isNotEqualTo(b.subject)
    }

    @Test fun `opds subject does not leak the url`() {
        val account = AccountCredentials.OpdsOnly("https://kavita.example/api/opds/secret-key")
        assertThat(account.subject).doesNotContain("secret-key")
        assertThat(account.subject).doesNotContain("kavita.example")
    }

    @Test fun `opds toString redacts the whole url and the password`() {
        val account = AccountCredentials.OpdsOnly(
            baseUrl = "https://kavita.example/api/opds/secret-key",
            username = "alice",
            password = "hunter2",
        )
        val rendered = account.toString()
        assertThat(rendered).doesNotContain("secret-key")
        assertThat(rendered).doesNotContain("kavita.example")
        assertThat(rendered).doesNotContain("hunter2")
        assertThat(rendered).contains("alice")
    }

    @Test fun `opds account allows absent credentials`() {
        val account = AccountCredentials.OpdsOnly("https://kavita.example/api/opds/secret-key")
        assertThat(account.username).isNull()
        assertThat(account.password).isNull()
    }
}
