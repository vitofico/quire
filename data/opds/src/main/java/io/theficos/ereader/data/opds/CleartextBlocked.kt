package io.theficos.ereader.data.opds

/**
 * Recognising Android's refusal to send a request in the clear.
 *
 * Since Android 9 the platform blocks plain-http traffic unless an app opts
 * back in, and Quire keeps that default (see `res/xml/network_security_config`).
 * OkHttp surfaces the refusal as `java.net.UnknownServiceException: CLEARTEXT
 * communication to <host> not permitted by network security policy`, which is
 * exactly the string issue #101's tester was shown after typing an `http://`
 * catalog URL — a platform policy, reported as if Quire had broken.
 */

/** User-facing copy for a blocked cleartext request. Quotes no URL: a catalog URL can carry an API key. */
const val CLEARTEXT_BLOCKED_MESSAGE: String =
    "Android blocks unencrypted http:// connections, so Quire can't reach this server. " +
        "Use its https:// address instead — a private certificate works as long as its CA is " +
        "installed on this device."

/**
 * [CLEARTEXT_BLOCKED_MESSAGE] when [t] (or anything it wraps) is that refusal,
 * null otherwise, so callers can keep their own fallback wording:
 * `cleartextBlockedMessage(e) ?: e.localizedMessage`.
 *
 * Matched on the message rather than on `UnknownServiceException` alone: OkHttp
 * re-wraps connection failures on retry, and that class also covers unrelated
 * errors.
 */
fun cleartextBlockedMessage(t: Throwable): String? {
    var current: Throwable? = t
    var hops = 0
    while (current != null && hops < MAX_CAUSE_HOPS) {
        if (current.message?.contains("CLEARTEXT", ignoreCase = true) == true) {
            return CLEARTEXT_BLOCKED_MESSAGE
        }
        current = current.cause
        hops++
    }
    return null
}

/** Cause chains are short in practice; the bound just rules out a cyclic one. */
private const val MAX_CAUSE_HOPS = 8
