package io.theficos.ereader.data.opds

import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.CatalogSession
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

/** Quire's own client: the account's login, and only to the account's Quire API. */
internal fun OkHttpClient.Builder.quireAuth(
    accountProvider: () -> AccountCredentials?,
    onBearerUnauthorized: (AccountCredentials.Bearer) -> Unit,
): OkHttpClient.Builder =
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

private class CatalogSnapshot(val session: CatalogSession?)

/**
 * The catalog client: a saved catalog's login, only to that catalog's server. Never the account's
 * login, never into the account's Quire API, and never one catalog's login to another catalog.
 */
internal fun OkHttpClient.Builder.catalogAuth(session: () -> CatalogSession?): OkHttpClient.Builder =
    addInterceptor { chain ->
        val request = chain.request().newBuilder()
            .tag(CatalogSnapshot::class.java, CatalogSnapshot(session()))
            .build()
        chain.proceed(request)
    }.addNetworkInterceptor { chain ->
        // OkHttp preserves Authorization across same-origin redirects. Select it per hop: a hop
        // gets a login only when it belongs to the same catalog as the request that started it.
        val request = chain.request()
        val original = chain.call().request()
        val snapshot = request.tag(CatalogSnapshot::class.java)?.session
        val catalog = snapshot?.catalogFor(request.url)?.takeIf { it == snapshot.catalogFor(original.url) }
        val authorization = catalog?.authorization()
        val actual = request.newBuilder().removeHeader(HEADER_AUTHORIZATION).apply {
            if (authorization != null) header(HEADER_AUTHORIZATION, authorization)
        }.build()
        chain.proceed(actual)
    }

/**
 * The catalog [url] belongs to, or null. Among the catalogs on the url's origin, the one whose
 * address shares the longest path with it wins, so two catalogs on one host each keep their own
 * login. A tie goes to the catalog being browsed; any other tie between different logins sends none.
 */
internal fun CatalogSession.catalogFor(url: HttpUrl): AccountCredentials.OpdsOnly? {
    val candidates = catalogs.filter { it.allows(url, account) }
    if (candidates.isEmpty()) return null
    val depth = candidates.associateWith { it.sharedPathDepth(url) }
    val longest = depth.values.max()
    val best = candidates.filter { depth[it] == longest }
    if (active in best) return active
    return best.first().takeIf { first -> best.all { it.authorization() == first.authorization() } }
}

private fun AccountCredentials.OpdsOnly.sharedPathDepth(url: HttpUrl): Int {
    val path = baseUrl.toHttpUrlOrNull()?.pathSegments?.filter { it.isNotEmpty() } ?: return 0
    return path.zip(url.pathSegments).takeWhile { (a, b) -> a == b }.size
}

private fun AccountCredentials.allowsQuire(url: HttpUrl): Boolean {
    val base = quireBase() ?: return false
    return !url.hasAmbiguousPath() && url.pathUnder(base)?.isQuireApi() == true
}

private fun AccountCredentials.quireBase(): String? = when (this) {
    is AccountCredentials.Basic -> quireServerUrl ?: baseUrl
    is AccountCredentials.Bearer -> baseUrl
    is AccountCredentials.OpdsOnly -> null
}

private fun AccountCredentials.OpdsOnly.allows(url: HttpUrl, account: AccountCredentials?): Boolean {
    if (!sameOrigin(baseUrl, url)) return false
    // These paths belong to Quire Web on a shared ingress, with or without a Quire login.
    if (url.pathSegments.firstOrNull() in quireWebRoutes) return false
    val quireBase = account?.quireBase() ?: return true
    if (!sameOrigin(quireBase, url)) return true
    if (url.hasAmbiguousPath()) return false
    val catalogUrl = baseUrl.toHttpUrlOrNull() ?: return false
    if (calibre) {
        val calibreMount = catalogUrl.pathSegments.dropLastWhile { it.isEmpty() }.dropLast(1)
        if (url.pathSegments.take(calibreMount.size) != calibreMount) return false
    }
    // A separate URL prefix reserves the whole Quire mount. Catalog and service URLs
    // that share a mount still reserve Quire's ingress routes.
    val quirePath = url.pathUnder(quireBase) ?: return true
    return catalogUrl.pathUnder(quireBase) != null && quirePath.firstOrNull() !in quireEdgeRoutes
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

private val quireApiNamespaces = setOf("ai", "auth", "catalogs", "library", "sync")
private val quireWebRoutes = setOf("quire", "cloud")
private val quireEdgeRoutes = quireApiNamespaces + quireWebRoutes + setOf(
    "agent", "mcp", "quire-admin", "health", "healthz", "readyz",
)
