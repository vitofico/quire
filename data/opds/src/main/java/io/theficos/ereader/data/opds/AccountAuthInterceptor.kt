package io.theficos.ereader.data.opds

import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.catalogAccount
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.Base64

private fun AccountCredentials.authorization(): String? = when (this) {
    is AccountCredentials.Basic -> basicHeader(username, password)
    is AccountCredentials.Bearer -> "Bearer $token"
    is AccountCredentials.OpdsOnly -> {
        val user = username
        val pass = password
        if (user != null && pass != null) basicHeader(user, pass) else null
    }
}

private fun basicHeader(username: String, password: String): String {
    val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
    return "Basic $encoded"
}

private fun sameOrigin(base: String, request: HttpUrl): Boolean {
    val url = base.toHttpUrlOrNull() ?: ("https://$base").toHttpUrlOrNull() ?: return false
    return url.host == request.host && url.port == request.port && url.scheme == request.scheme
}

private const val HEADER_AUTHORIZATION = "Authorization"
private const val HTTP_UNAUTHORIZED = 401

internal enum class AccountAuthRoute { CATALOG, QUIRE }

private class AccountSnapshot(val account: AccountCredentials?)

internal fun OkHttpClient.Builder.accountAuth(
    route: AccountAuthRoute,
    accountProvider: () -> AccountCredentials?,
    onBearerUnauthorized: (AccountCredentials.Bearer) -> Unit,
): OkHttpClient.Builder = when (route) {
    AccountAuthRoute.QUIRE -> {
        // Quire POST bodies can contain private reading activity as well as authorization.
        followRedirects(false).addInterceptor { chain ->
            val request = chain.request()
            val account = accountProvider()
            val authorization = account?.authorization()
            val preset = request.header(HEADER_AUTHORIZATION)
            if (account == null || authorization == null || !account.allowsQuire(request.url) ||
                (preset != null && preset != authorization)
            ) {
                throw IOException("Quire account changed or the request is outside its configured API")
            }
            val response = chain.proceed(request.newBuilder().header(HEADER_AUTHORIZATION, authorization).build())
            if (account is AccountCredentials.Bearer && response.code == HTTP_UNAUTHORIZED) {
                onBearerUnauthorized(account)
            }
            response
        }
    }
    AccountAuthRoute.CATALOG -> addInterceptor { chain ->
        val request = chain.request().newBuilder()
            .tag(AccountSnapshot::class.java, AccountSnapshot(accountProvider()))
            .build()
        chain.proceed(request)
    }.addNetworkInterceptor { chain ->
        // OkHttp preserves Authorization across same-origin redirects. Select it per hop,
        // without allowing a catalog redirect into a separately authenticated Quire route.
        val request = chain.request()
        val original = chain.call().request()
        val account = request.tag(AccountSnapshot::class.java)?.account
        val authorization = account?.takeIf {
            it.allowsCatalog(original.url) && it.allowsCatalog(request.url)
        }?.catalogAccount()?.authorization()
        val actual = request.newBuilder().removeHeader(HEADER_AUTHORIZATION).apply {
            if (authorization != null) header(HEADER_AUTHORIZATION, authorization)
        }.build()
        chain.proceed(actual)
    }
}

private fun AccountCredentials.allowsQuire(url: HttpUrl): Boolean {
    val base = when (this) {
        is AccountCredentials.Basic -> quireServerUrl ?: baseUrl
        is AccountCredentials.Bearer -> baseUrl
        is AccountCredentials.OpdsOnly -> return false
    }
    return !url.hasAmbiguousPath() && url.pathUnder(base)?.isQuireApi() == true
}

private fun AccountCredentials.allowsCatalog(url: HttpUrl): Boolean {
    val catalogBase = catalogAccount()?.baseUrl ?: return false
    if (!sameOrigin(catalogBase, url)) return false
    val independentCatalog = (this as? AccountCredentials.Bearer)?.catalog ?: return true
    if (!sameOrigin(baseUrl, url)) return true
    if (url.hasAmbiguousPath()) return false
    val catalogUrl = catalogBase.toHttpUrlOrNull() ?: return false
    if (independentCatalog.calibre) {
        val calibreMount = catalogUrl.pathSegments.dropLastWhile { it.isEmpty() }.dropLast(1)
        if (url.pathSegments.take(calibreMount.size) != calibreMount) return false
    }
    // A separate URL prefix reserves the whole Quire mount. Catalog and service URLs
    // that share a mount still reserve Quire's ingress routes.
    val quirePath = url.pathUnder(baseUrl) ?: return true
    return catalogUrl.pathUnder(baseUrl) != null && quirePath.firstOrNull() !in quireEdgeRoutes
}

private fun HttpUrl.pathUnder(base: String): List<String>? {
    if (!sameOrigin(base, this)) return null
    val baseUrl = base.toHttpUrlOrNull() ?: return null
    val prefix = baseUrl.pathSegments.dropLastWhile { it.isEmpty() }
    if (pathSegments.take(prefix.size) != prefix) return null
    return pathSegments.drop(prefix.size)
}

private fun List<String>.isQuireApi(): Boolean =
    size >= 2 && first() in quireApiNamespaces && this[1] == "v1"

private fun HttpUrl.hasAmbiguousPath(): Boolean =
    pathSegments.any { '/' in it || '\\' in it || '%' in it || it == "." || it == ".." }

private val quireApiNamespaces = setOf("ai", "auth", "library", "sync")
private val quireEdgeRoutes = quireApiNamespaces + setOf(
    "agent", "mcp", "quire-admin", "health", "healthz", "readyz",
)
