package io.theficos.ereader.auth

import java.security.MessageDigest

/**
 * Scheme-tagged credentials for a single configured Quire account.
 *
 * Three concrete shapes:
 * - [Basic]: `username` + `password` (calibre-web style). Emits
 *   `Authorization: Basic <base64(user:pass)>`.
 * - [Bearer]: `email` (subject hint) + `token` (long-lived bearer).
 *   Emits `Authorization: Bearer <token>`.
 * - [OpdsOnly]: a complete OPDS catalog URL with optional Basic credentials.
 *   Reader-only, with no quire-server behind it. See issue #101.
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

    /**
     * NativeAuth (`quire_server` / Quire Cloud) bearer-token credentials.
     *
     * [expiresAtEpochMs] is the absolute expiry of the session token, in
     * Unix epoch milliseconds, as reported by the server's
     * `POST /auth/v1/login` response (`expires_at`). It is **nullable** to
     * cover two cases: legacy records persisted before expiry tracking
     * existed, and login responses whose `expires_at` we could not parse.
     * `null` means "expiry unknown" — callers must treat that as a
     * still-usable token (fail-open) rather than as already-expired, since
     * NativeAuth has no refresh endpoint and a spurious logout would be worse
     * than letting the next request surface a real 401.
     *
     * NativeAuth issues opaque, non-refreshable session tokens, so when this
     * instant passes the only recovery is a fresh interactive login. See
     * [isExpiredAt].
     */
    data class Bearer(
        override val baseUrl: String,
        val email: String,
        val token: String,
        val expiresAtEpochMs: Long? = null,
    ) : AccountCredentials() {
        override val scheme: AuthScheme = AuthScheme.BEARER
        override val subject: String get() = email.lowercase()

        /**
         * True when this session's [expiresAtEpochMs] is known and is at or
         * before [nowEpochMs]. Returns false when expiry is unknown
         * (fail-open — see the [expiresAtEpochMs] doc).
         */
        fun isExpiredAt(nowEpochMs: Long): Boolean {
            val exp = expiresAtEpochMs ?: return false
            return nowEpochMs >= exp
        }

        override fun toString(): String =
            "AccountCredentials.Bearer(baseUrl=$baseUrl, email=$email, token=***, " +
                "expiresAtEpochMs=$expiresAtEpochMs)"
    }

    /**
     * A plain OPDS 1.x catalog at an arbitrary URL (Kavita, Komga, a hand-rolled
     * feed, or calibre-web reached by its feed URL rather than its root).
     *
     * [baseUrl] is a COMPLETE catalog URL, used verbatim. Unlike the other two
     * variants it is not a server root and callers must never append a path to
     * it: the whole point of this scheme is that the catalog lives at a layout
     * we cannot predict. `ServerProbe` and `CatalogViewModel` are the only two
     * callers that ever appended, and both special-case this variant.
     *
     * [baseUrl] may itself be a credential. Kavita's catalog URL embeds a
     * full-account API key as a path segment, which is why [toString] redacts
     * the URL outright rather than just the password. Never interpolate it into
     * a log line or a user-facing message: use `redactUrl` from the opds module.
     *
     * [username] and [password] are both null for an anonymous or URL-authed
     * catalog, and both non-null for one behind HTTP Basic. One-of-two is not a
     * valid state and the credential store rejects it.
     *
     * Reader-only by construction: `quireServerUrlOrNull()` returns null for
     * this variant, which switches off sync, the library mirror and AI.
     */
    data class OpdsOnly(
        override val baseUrl: String,
        val username: String? = null,
        val password: String? = null,
    ) : AccountCredentials() {
        override val scheme: AuthScheme = AuthScheme.OPDS

        /**
         * Local-only principal, used to partition the catalog insight stash.
         * There is no server behind this account, so there is no server-issued
         * subject to mirror. A truncated hash of the catalog URL is stable
         * across restarts, distinct per catalog, and exposes neither the host
         * nor any embedded key.
         */
        override val subject: String
            get() = "opds:" + sha256Hex(baseUrl).take(16)

        override fun toString(): String =
            "AccountCredentials.OpdsOnly(baseUrl=***, username=$username, password=***)"
    }
}

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
