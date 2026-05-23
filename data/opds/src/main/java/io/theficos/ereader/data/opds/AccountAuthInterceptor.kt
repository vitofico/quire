package io.theficos.ereader.data.opds

import io.theficos.ereader.auth.AccountCredentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Base64

/**
 * Attaches the right `Authorization` header for the configured Quire account.
 *
 * Dispatches per [AccountCredentials.scheme]:
 * - [AccountCredentials.Basic]: `Authorization: Basic <base64(user:pass)>`.
 * - [AccountCredentials.Bearer]: `Authorization: Bearer <token>`.
 *
 * Safety rails:
 * - **Origin guard**: the header is attached only when the outgoing request
 *   targets the same host (and, where parsable, the same scheme + port) as
 *   the configured account's `baseUrl`. This prevents an OPDS publication
 *   that points to a third-party cover/download host from receiving the
 *   user's bearer token or basic credentials.
 * - **Pre-set header passthrough**: if the caller already attached an
 *   `Authorization` header to the request, it is preserved verbatim (the
 *   bearer-token onboarding handshake builds explicit auth requests; we
 *   must not stomp them).
 *
 * Notes for future work:
 * - This is an [Interceptor], not an [okhttp3.Authenticator]. We choose
 *   proactive header injection because (a) refresh-token rotation is
 *   deliberately deferred and (b) using an Authenticator for a static
 *   bearer would just retry the same bad token in a loop on 401.
 * - On a 401 from a bearer endpoint, callers should surface the failure to
 *   the user (re-auth) rather than mutate the store from inside an
 *   interceptor — silent token swaps from the network layer have caused
 *   "wrong credentials sent to wrong host" incidents in the past.
 */
class AccountAuthInterceptor(
    private val accountProvider: () -> AccountCredentials?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER_AUTHORIZATION) != null) {
            return chain.proceed(request)
        }
        val account = accountProvider() ?: return chain.proceed(request)
        if (!sameOrigin(account.baseUrl, request.url)) {
            return chain.proceed(request)
        }
        val headerValue = when (account) {
            is AccountCredentials.Basic -> basicHeader(account)
            is AccountCredentials.Bearer -> "Bearer ${account.token}"
        }
        return chain.proceed(
            request.newBuilder().header(HEADER_AUTHORIZATION, headerValue).build()
        )
    }

    private fun basicHeader(account: AccountCredentials.Basic): String {
        val raw = "${account.username}:${account.password}"
        val encoded = Base64.getEncoder().encodeToString(raw.toByteArray())
        return "Basic $encoded"
    }

    /**
     * Returns true when the request URL targets the same origin as the
     * configured account base URL. A parse failure on the account base URL
     * is treated permissively (we attach the header) to preserve the
     * existing calibre-web behavior where bare hosts with no scheme were
     * historically accepted; in that defensive branch we still match on
     * host + (best-effort) port.
     */
    private fun sameOrigin(accountBaseUrl: String, requestUrl: HttpUrl): Boolean {
        val parsed = accountBaseUrl.toHttpUrlOrNull()
            ?: ("https://$accountBaseUrl").toHttpUrlOrNull()
            ?: return requestUrl.host.equals(stripScheme(accountBaseUrl), ignoreCase = true)
        return requestUrl.host.equals(parsed.host, ignoreCase = true) &&
            requestUrl.port == parsed.port &&
            requestUrl.scheme.equals(parsed.scheme, ignoreCase = true)
    }

    private fun stripScheme(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore(':')

    private companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
    }
}
