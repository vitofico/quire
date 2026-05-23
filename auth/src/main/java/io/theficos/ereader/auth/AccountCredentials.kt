package io.theficos.ereader.auth

/**
 * Scheme-tagged credentials for a single configured Quire account.
 *
 * Two concrete shapes:
 * - [Basic]: `username` + `password` (calibre-web style). Emits
 *   `Authorization: Basic <base64(user:pass)>`.
 * - [Bearer]: `email` (subject hint) + `token` (long-lived bearer).
 *   Emits `Authorization: Bearer <token>`.
 *
 * Subclasses **deliberately override `toString()`** to redact secrets — these
 * objects must never leak into logs in their raw form. Tests that need to
 * compare them must compare individual fields rather than relying on the
 * string form.
 */
sealed class AccountCredentials {
    abstract val scheme: AuthScheme

    /** Canonicalized server base URL (no trailing slash). */
    abstract val baseUrl: String

    /**
     * Identifier used by the server to bind rows to a principal. For BASIC,
     * this is the lowercased calibre-web username; for BEARER, this is the
     * lowercased email associated with the issued token. See
     * `docs/sync-api.md` for the server contract.
     */
    abstract val subject: String

    data class Basic(
        override val baseUrl: String,
        val username: String,
        val password: String,
    ) : AccountCredentials() {
        override val scheme: AuthScheme = AuthScheme.BASIC
        override val subject: String get() = username.lowercase()
        override fun toString(): String =
            "AccountCredentials.Basic(baseUrl=$baseUrl, username=$username, password=***)"
    }

    data class Bearer(
        override val baseUrl: String,
        val email: String,
        val token: String,
    ) : AccountCredentials() {
        override val scheme: AuthScheme = AuthScheme.BEARER
        override val subject: String get() = email.lowercase()
        override fun toString(): String =
            "AccountCredentials.Bearer(baseUrl=$baseUrl, email=$email, token=***)"
    }
}
