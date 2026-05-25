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

    /**
     * Calibre-web style credentials (HTTP Basic).
     *
     * [quireServerUrl] optionally routes sync/library/AI calls to a different
     * host than the OPDS catalog at [baseUrl]. Null means "use [baseUrl] for
     * everything" (the common single-URL deployment). When set, both URLs
     * share the same Basic credential — the operator is responsible for
     * configuring quire-server's `QUIRE_SERVER_CWA_BASE_URL` to validate
     * incoming Basic headers against the same calibre-web instance.
     *
     * See `docs/superpowers/split-server-urls.md` for the tiered scope and
     * the deferred BEARER + separate-calibre-web case.
     */
    data class Basic(
        override val baseUrl: String,
        val username: String,
        val password: String,
        val quireServerUrl: String? = null,
    ) : AccountCredentials() {
        override val scheme: AuthScheme = AuthScheme.BASIC
        override val subject: String get() = username.lowercase()
        override fun toString(): String =
            "AccountCredentials.Basic(baseUrl=$baseUrl, username=$username, password=***, quireServerUrl=$quireServerUrl)"
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
