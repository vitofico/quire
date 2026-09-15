package io.theficos.ereader.data.opds

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Reduce a URL to `scheme://host[:port]/…` for safe display and logging.
 *
 * An OPDS catalog URL can itself be a credential: Kavita embeds a full-account
 * API key as a path segment, and other servers take one as a query parameter.
 * Quire interpolates fetch URLs into failure messages that reach the catalog
 * screen and logcat, so any such message must go through here first.
 *
 * The host and port survive because a reachability error the user cannot
 * attribute to a server is not worth showing. Everything after the authority is
 * dropped, userinfo included. Input that does not parse redacts wholesale
 * rather than falling back to echoing it.
 *
 * See issue #101.
 */
fun redactUrl(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return "<redacted url>"
    val portPart = if (
        (parsed.scheme == "http" && parsed.port == 80) ||
        (parsed.scheme == "https" && parsed.port == 443)
    ) {
        ""
    } else {
        ":${parsed.port}"
    }
    val host = if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host
    return "${parsed.scheme}://$host$portPart/…"
}
