package io.theficos.ereader.di

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import org.junit.Test

class QuireServerUrlTest {

    @Test fun `basic account without an override uses its own base url`() {
        val account = AccountCredentials.Basic("https://calibre.example", "u", "p")
        assertThat(account.quireServerUrlOrNull()).isEqualTo("https://calibre.example")
    }

    @Test fun `basic account with an override uses the override`() {
        val account = AccountCredentials.Basic(
            baseUrl = "https://calibre.example",
            username = "u",
            password = "p",
            quireServerUrl = "https://quire.example",
        )
        assertThat(account.quireServerUrlOrNull()).isEqualTo("https://quire.example")
    }

    @Test fun `bearer account uses its base url`() {
        val account = AccountCredentials.Bearer("https://quire.example", "a@b.c", "tok")
        assertThat(account.quireServerUrlOrNull()).isEqualTo("https://quire.example")
    }

    @Test fun `opds account has no quire server`() {
        val account = AccountCredentials.OpdsOnly("https://kavita.example/api/opds/KEY")
        assertThat(account.quireServerUrlOrNull()).isNull()
    }
}
