package io.theficos.ereader.auth

/**
 * Authentication scheme tag attached to a configured account.
 *
 * - [BASIC]: HTTP Basic against a calibre-web instance (the original Quire path).
 * - [BEARER]: bearer-token auth against a `quire_server` deployment running
 *   NativeAuth (the upcoming Quire Cloud / Native-auth self-host path).
 *
 * The set is intentionally closed. OAuth, magic links, refresh-token rotation
 * and MFA are *out of scope* for the on-device auth layer — those concerns
 * live in onboarding flows that ultimately deposit a long-lived bearer token
 * into the credential store. See `docs/sync-api.md` for the server-side
 * compatibility matrix.
 */
enum class AuthScheme {
    BASIC,
    BEARER,
}
