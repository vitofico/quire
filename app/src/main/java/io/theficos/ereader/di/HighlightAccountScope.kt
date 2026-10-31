package io.theficos.ereader.di

import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.data.sync.HighlightSyncSession
import java.security.MessageDigest
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Password/token refresh preserves ownership; server, catalog, scheme or subject changes do not. */
internal fun AccountCredentials?.highlightScope(): String {
    val account = this ?: return ""
    val server = account.quireServerUrlOrNull()
    val parts = listOf(
        account.scheme.name,
        account.baseUrl.toHttpUrl().toString().trimEnd('/'),
        server?.toHttpUrl()?.toString()?.trimEnd('/').orEmpty(),
        account.subject,
    ) + if (account is AccountCredentials.OpdsOnly) {
        listOf((account.username != null).toString(), account.username.orEmpty())
    } else emptyList()
    val input = parts.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun AccountCredentials?.highlightSession(): HighlightSyncSession? {
    val account = this ?: return null
    val base = account.quireServerUrlOrNull() ?: return null
    val authorization = when (account) {
        is AccountCredentials.Basic -> "Basic " + Base64.getEncoder()
            .encodeToString("${account.username}:${account.password}".toByteArray(Charsets.UTF_8))
        is AccountCredentials.Bearer -> "Bearer ${account.token}"
        is AccountCredentials.OpdsOnly -> return null
    }
    return HighlightSyncSession(account.highlightScope(), base, authorization)
}
