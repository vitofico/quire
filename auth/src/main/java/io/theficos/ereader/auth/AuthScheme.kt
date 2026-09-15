package io.theficos.ereader.auth

/**
 * Authentication scheme tag attached to a configured account.
 *
 * - [BASIC]: HTTP Basic against a calibre-web instance (the original Quire path).
 * - [BEARER]: bearer-token auth against a `quire_server` deployment running
 *   NativeAuth (the upcoming Quire Cloud / Native-auth self-host path).
 * - [OPDS]: a plain OPDS 1.x catalog at an arbitrary URL, with optional HTTP
 *   Basic credentials. Reader-only: there is no quire-server behind it, so
 *   sync, the library mirror and AI are unavailable. See issue #101.
 *
 * OAuth, magic links, refresh-token rotation and MFA remain *out of scope* for
 * the on-device auth layer. See `docs/sync-api.md` for the server-side
 * compatibility matrix.
 */
enum class AuthScheme {
    BASIC,
    BEARER,
    OPDS,
}
