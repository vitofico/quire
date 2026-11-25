package io.theficos.ereader.auth

import java.net.URI
import java.util.UUID

/**
 * One book catalog in the device's list: an address with an optional login, and a name for the
 * chip row. The list works the same with or without a Quire account; a Quire login on a server
 * with catalog sync also keeps it on the account.
 *
 * [id] is a UUID chosen on the device, so it is also the catalog's id on the Quire account.
 *
 * [catalog] is null only for a catalog the account lists but whose address this device has never
 * read, because the server answered `needs_login`. [host] then comes from the account's list.
 *
 * [deviceOnly] means the reader chose to keep this catalog off their Quire account.
 * [remoteUpdatedAt] is the account's `updated_at` for the copy held here, and null while the
 * catalog lives only on this device. [needsLogin] means the account's copy can't be read and the
 * reader has to enter the address and login again.
 *
 * [pinned] marks the legacy Calibre-Web sign-in's own catalog, listed first and removable only by
 * signing out. It is derived from the account and never stored.
 */
data class SavedCatalog(
    val id: String,
    val name: String,
    val catalog: AccountCredentials.OpdsOnly?,
    val deviceOnly: Boolean = false,
    val remoteUpdatedAt: String? = null,
    val needsLogin: Boolean = false,
    val listedHost: String? = null,
    val pinned: Boolean = false,
) {
    /** Scheme, host and a non-default port. Never the path, which may hold a key. Safe to show and log. */
    val host: String get() = catalog?.let { catalogHost(it.baseUrl) } ?: listedHost.orEmpty()

    /** True while the Quire account holds this catalog. */
    val onAccount: Boolean get() = remoteUpdatedAt != null

    /** On this device only because nobody has been asked yet: the sign-in question covers it. */
    val awaitingUpload: Boolean get() = !onAccount && !deviceOnly && !pinned

    override fun toString(): String =
        "SavedCatalog(id=$id, name=$name, host=$host, deviceOnly=$deviceOnly, " +
            "onAccount=$onAccount, needsLogin=$needsLogin, pinned=$pinned)"

    companion object {
        /** Longest name the Quire account accepts. */
        const val MAX_NAME_LENGTH = 60

        fun newId(): String = UUID.randomUUID().toString()

        /** The host, as a name for a catalog the reader has not named yet. */
        fun defaultName(url: String): String =
            (runCatching { URI(url.trim()).host }.getOrNull() ?: url.trim()).take(MAX_NAME_LENGTH)
    }
}

/**
 * What the catalog HTTP client needs to pick a login, read in one go when a request starts: every
 * catalog with an address, the one being browsed, and the account whose Quire server no catalog
 * login may reach.
 */
class CatalogSession(
    val catalogs: List<AccountCredentials.OpdsOnly>,
    val active: AccountCredentials.OpdsOnly?,
    val account: AccountCredentials?,
)

/**
 * This entry with its name and login trimmed. Throws [IllegalArgumentException] for an id that is
 * not a UUID, a name that is blank or over [SavedCatalog.MAX_NAME_LENGTH], a blank address, or only
 * one of username and password.
 */
fun SavedCatalog.validated(): SavedCatalog {
    require(runCatching { UUID.fromString(id) }.isSuccess) { "catalog id must be a UUID" }
    val trimmed = name.trim()
    require(trimmed.isNotEmpty() && trimmed.length <= SavedCatalog.MAX_NAME_LENGTH) {
        "catalog name must be 1 to ${SavedCatalog.MAX_NAME_LENGTH} characters"
    }
    return copy(name = trimmed, catalog = catalog?.validatedCatalog())
}

private fun AccountCredentials.OpdsOnly.validatedCatalog(): AccountCredentials.OpdsOnly {
    require(baseUrl.isNotBlank()) { "catalogUrl must not be blank" }
    val user = username?.takeIf { it.isNotBlank() }
    val pass = password?.takeIf { it.isNotBlank() }
    require((user == null) == (pass == null)) {
        "username and password must both be set or both be absent"
    }
    return copy(baseUrl = baseUrl.trim(), username = user, password = pass)
}

internal fun catalogHost(url: String): String {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return ""
    val scheme = uri.scheme?.lowercase() ?: return ""
    val host = uri.host ?: return ""
    val defaultPort = if (scheme == "https") 443 else 80
    return "$scheme://$host" + if (uri.port != -1 && uri.port != defaultPort) ":${uri.port}" else ""
}
