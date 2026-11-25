package io.theficos.ereader.di

import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.ui.reader.memoryAccountKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Moves a signed-in Quire account to the address its server names as canonical.
 *
 * The server stays the same, only its address changes, so the session token keeps
 * working. Highlights, stopping-point notes and PDF conversions are keyed by address and follow.
 */
internal class ServerAddressFollower(
    private val store: CalibreCredentialStore,
    private val client: OkHttpClient,
    private val moveMemory: suspend (from: String, to: String) -> Unit,
    private val moveConversions: suspend (from: String, to: String) -> Unit = { _, _ -> },
) {
    private data class Identity(val accountId: String, val serverUrl: String?)

    suspend fun follow() {
        val account = store.getAccount() as? AccountCredentials.Bearer ?: return
        val here = withContext(Dispatchers.IO) { identity(account.baseUrl, account.token) } ?: return
        val current = account.baseUrl.toHttpUrlOrNull() ?: return
        val target = here.serverUrl?.toHttpUrlOrNull()
            ?.takeIf { it.query == null && it.fragment == null && it.username.isEmpty() }
            // Never downgrade an encrypted connection.
            ?.takeIf { it.isHttps || !current.isHttps }
            ?.toString()?.trimEnd('/') ?: return
        if (target == current.toString().trimEnd('/')) return
        if (account.accountId != null && account.accountId != here.accountId) return
        // The new address must be the same server: it knows this session as the same account.
        val there = withContext(Dispatchers.IO) { identity(target, account.token) }
        if (there?.accountId != here.accountId) return

        val from = memoryAccountKey(account.baseUrl, account.subject)
        val to = memoryAccountKey(target, account.subject)
        val fromOwner = accountOwner(account.baseUrl, account.subject)
        val toOwner = accountOwner(target, account.subject)
        moveMemory(from, to)
        moveConversions(fromOwner, toOwner)
        if (!store.moveQuireServer(account, target, account.highlightScope())) {
            moveMemory(to, from)
            moveConversions(toOwner, fromOwner)
        }
    }

    private fun identity(serverUrl: String, token: String): Identity? = runCatching {
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/auth/v1/me")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val payload = Json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
            val user = payload?.get("user") as? JsonObject ?: return null
            if ((user["backend"] as? JsonPrimitive)?.contentOrNull != "native") return null
            val id = (user["id"] as? JsonPrimitive)?.contentOrNull ?: return null
            Identity(id, (payload["server_url"] as? JsonPrimitive)?.contentOrNull)
        }
    }.getOrNull()
}
