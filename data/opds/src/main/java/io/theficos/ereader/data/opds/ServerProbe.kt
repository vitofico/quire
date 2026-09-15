package io.theficos.ereader.data.opds

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Detects what kind of Quire-compatible server lives behind a base URL.
 *
 * Phase 0 task A-2 — the first-launch onboarding flow probes the server the
 * user enters and routes them to the right credential-entry shape:
 * `quire_server` with NativeAuth needs email/password, calibre-web needs
 * HTTP Basic.
 *
 * The probe runs ALL THREE endpoint checks concurrently and waits for all to
 * complete before classifying — first-definitive-wins would misclassify a
 * reverse proxy or any deployment that fronts both shapes. Each individual
 * call has a tight per-request timeout; the overall budget is the slowest
 * of the three probes.
 *
 * Critically: this class does NOT carry credentials, does NOT see the
 * application's `AccountAuthInterceptor`, and does NOT share cookies with
 * the main app OkHttp client. The probe runs on a dedicated plain client so
 * we never accidentally leak stale credentials from a previous account into
 * a fresh URL the user is exploring.
 *
 * Spec: `docs/superpowers/specs/2026-05-22-quire-monetization-design.md`
 * — section "Architectural changes → First-launch flow (Android)".
 */
class ServerProbe(
    private val client: OkHttpClient = defaultClient(),
    private val perRequestTimeoutMs: Long = DEFAULT_PER_REQUEST_TIMEOUT_MS,
) {

    /**
     * Probe [rawUrl] and return a classification.
     *
     * The returned [ServerProbeResult] carries the *canonical* base URL —
     * trailing slash stripped, IDN punycode'd, port preserved. Downstream
     * code (LibraryClient, SyncClient, OpdsClient) builds full URLs by
     * string-appending path suffixes to the saved baseUrl, so a stable
     * canonical form is load-bearing.
     */
    suspend fun probe(rawUrl: String): ServerProbeResult {
        val canonical = normalizeBaseUrl(rawUrl)
            ?: return ServerProbeResult.Error(
                ProbeError.MalformedUrl,
                "Enter a full URL starting with http:// or https://",
            )

        // Same reject rules, so this cannot be null when `canonical` is not.
        val canonicalCatalog = normalizeCatalogUrl(rawUrl) ?: canonical

        return coroutineScope {
            val calibreDeferred = async { probeCalibre(canonical) }
            val nativeDeferred = async { probeNativeAuth(canonical) }
            val genericDeferred = async { probeGenericOpds(canonicalCatalog) }
            val outcomes =
                listOf(calibreDeferred, nativeDeferred, genericDeferred).awaitAll()
            classify(canonical, canonicalCatalog, outcomes[0], outcomes[1], outcomes[2])
        }
    }

    /**
     * GET `${canonical}/opds`.
     *
     * Positive if (a) 200 with `Content-Type: application/atom+xml`, or
     * (b) 401 with `WWW-Authenticate: Basic ...`. Calibre-web's OPDS
     * surface is the most stable signal; the 401-with-Basic branch covers
     * deployments that don't allow anonymous OPDS browsing.
     */
    private fun probeCalibre(canonicalBase: String): ProbeOutcome {
        val url = (canonicalBase + "/opds").toHttpUrlOrNull()
            ?: return ProbeOutcome.NegativeWith(ProbeError.MalformedUrl, "OPDS URL build failed")
        return runProbeRequest(canonicalBase, url, method = "GET") { resp ->
            when (resp.code) {
                200 -> {
                    val ct = resp.header("Content-Type")?.lowercase().orEmpty()
                    if (ct.startsWith("application/atom+xml")) {
                        ProbeOutcome.Positive
                    } else {
                        ProbeOutcome.NegativeWith(
                            ProbeError.Unexpected,
                            "/opds returned 200 with content-type '$ct'",
                        )
                    }
                }
                401 -> {
                    val wwwAuth = resp.header("WWW-Authenticate").orEmpty()
                    if (wwwAuth.trim().startsWith("Basic", ignoreCase = true)) {
                        ProbeOutcome.Positive
                    } else {
                        // A 401 without a Basic challenge could be a bearer-only
                        // surface dressed up as OPDS — refuse to classify as
                        // calibre-web on weak evidence.
                        ProbeOutcome.NegativeWith(
                            ProbeError.Unexpected,
                            "/opds returned 401 without Basic challenge",
                        )
                    }
                }
                else -> ProbeOutcome.NegativeWith(
                    ProbeError.Unexpected,
                    "/opds returned HTTP ${resp.code}",
                )
            }
        }
    }

    /**
     * GET `${canonical}/auth/v1/login`.
     *
     * Positive only on `405 Method Not Allowed` with an `Allow` header that
     * lists POST. The endpoint declares POST in `server/quire_server/api/
     * auth.py`, so FastAPI emits 405 with `Allow: POST` for any other
     * method. Generic 200/204 (e.g. a reverse-proxy fallback) is NOT a
     * positive signal. We deliberately do NOT issue OPTIONS — most ASGI
     * stacks answer it with a synthesised 200/204 even for absent routes.
     */
    private fun probeNativeAuth(canonicalBase: String): ProbeOutcome {
        val url = (canonicalBase + "/auth/v1/login").toHttpUrlOrNull()
            ?: return ProbeOutcome.NegativeWith(ProbeError.MalformedUrl, "login URL build failed")
        return runProbeRequest(canonicalBase, url, method = "GET") { resp ->
            when (resp.code) {
                405 -> {
                    val allow = resp.header("Allow").orEmpty().uppercase()
                    if (allow.contains("POST")) {
                        ProbeOutcome.Positive
                    } else {
                        ProbeOutcome.NegativeWith(
                            ProbeError.Unexpected,
                            "/auth/v1/login returned 405 without 'Allow: POST'",
                        )
                    }
                }
                else -> ProbeOutcome.NegativeWith(
                    ProbeError.Unexpected,
                    "/auth/v1/login returned HTTP ${resp.code}",
                )
            }
        }
    }

    /**
     * GET the user's URL exactly as typed, with no path synthesis.
     *
     * Positive on (a) 200 whose body's root element is an Atom `feed`, or
     * (b) 401 carrying a Basic challenge, which is positive-but-needs-auth.
     *
     * Content type is deliberately NOT part of the test: Kavita serves OPDS as
     * `application/xml`, which is precisely the case this exists for. The XML
     * root element is the real signal.
     *
     * A bare 401 (Kavita's answer to a wrong API key) stays negative, because
     * it cannot be told apart from "this is not an OPDS server".
     */
    private fun probeGenericOpds(canonicalCatalogUrl: String): ProbeOutcome {
        val url = canonicalCatalogUrl.toHttpUrlOrNull()
            ?: return ProbeOutcome.NegativeWith(ProbeError.MalformedUrl, "catalog URL build failed")
        return runProbeRequest(canonicalCatalogUrl, url, method = "GET") { resp ->
            when (resp.code) {
                200 -> {
                    // Cap the sniff so a huge or non-XML body can't be read
                    // into memory just to classify it.
                    val body = resp.peekBody(SNIFF_BYTES).string()
                    if (looksLikeAtomFeed(body)) {
                        ProbeOutcome.Positive
                    } else {
                        ProbeOutcome.NegativeWith(
                            ProbeError.Unexpected,
                            "URL returned 200 but the body is not an Atom feed",
                        )
                    }
                }
                401 -> {
                    val wwwAuth = resp.header("WWW-Authenticate").orEmpty()
                    if (wwwAuth.trim().startsWith("Basic", ignoreCase = true)) {
                        ProbeOutcome.PositiveNeedsAuth
                    } else {
                        ProbeOutcome.NegativeWith(
                            ProbeError.Unexpected,
                            "URL returned 401 without a Basic challenge",
                        )
                    }
                }
                else -> ProbeOutcome.NegativeWith(
                    ProbeError.Unexpected,
                    "URL returned HTTP ${resp.code}",
                )
            }
        }
    }

    /**
     * True when [body] is an Atom feed.
     *
     * Delegates to the shared hardened parser. Do NOT inline a
     * DocumentBuilderFactory here with Apache feature names: Android refuses
     * them and the parse silently yields false for every real feed, which is
     * exactly the device-only failure issue #101 hit.
     */
    private fun looksLikeAtomFeed(body: String): Boolean =
        looksLikeAtomFeed(body.toByteArray())

    private fun runProbeRequest(
        canonicalBase: String,
        url: HttpUrl,
        method: String,
        evaluate: (Response) -> ProbeOutcome,
    ): ProbeOutcome {
        val request = Request.Builder()
            .url(url)
            .method(method, null)
            .header("User-Agent", USER_AGENT)
            // Hint to intermediaries that this is a discovery request.
            .header("Accept", "*/*")
            .build()

        // Per-request timeout customisation. okhttp's per-call timeout
        // bounds the whole exchange including any (rejected) redirect, so
        // we can guarantee the probe call won't take longer than this.
        val callClient = client.newBuilder()
            .callTimeout(perRequestTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return try {
            callClient.newCall(request).execute().use(evaluate)
        } catch (e: SSLException) {
            ProbeOutcome.NegativeWith(ProbeError.Tls, e.localizedMessage ?: "TLS error")
        } catch (e: UnknownHostException) {
            ProbeOutcome.NegativeWith(ProbeError.Dns, e.localizedMessage ?: "DNS lookup failed")
        } catch (e: ConnectException) {
            ProbeOutcome.NegativeWith(
                ProbeError.ConnectionRefused,
                e.localizedMessage ?: "connection refused",
            )
        } catch (e: SocketTimeoutException) {
            ProbeOutcome.NegativeWith(ProbeError.Timeout, e.localizedMessage ?: "timed out")
        } catch (e: RedirectRejectedException) {
            ProbeOutcome.NegativeWith(ProbeError.RedirectRejected, e.localizedMessage ?: "redirect")
        } catch (e: IOException) {
            // Per-call timeout surfaces as InterruptedIOException → IOException.
            val msg = e.localizedMessage.orEmpty().lowercase()
            val reason = if ("timeout" in msg || "timed out" in msg) {
                ProbeError.Timeout
            } else {
                ProbeError.Unexpected
            }
            ProbeOutcome.NegativeWith(reason, e.localizedMessage ?: "I/O error")
        }
    }

    private fun classify(
        canonicalBase: String,
        canonicalCatalog: String,
        calibre: ProbeOutcome,
        native: ProbeOutcome,
        generic: ProbeOutcome,
    ): ServerProbeResult {
        val calibrePositive = calibre is ProbeOutcome.Positive
        val nativePositive = native is ProbeOutcome.Positive
        if (calibrePositive && nativePositive) {
            return ServerProbeResult.Both(canonicalBase)
        }
        if (calibrePositive) return ServerProbeResult.Calibre(canonicalBase)
        if (nativePositive) return ServerProbeResult.NativeAuth(canonicalBase)

        // Lowest precedence: calibre-web and quire-server both offer strictly
        // more than a reader-only catalog, so neither may be downgraded to one.
        val genericPositive = generic is ProbeOutcome.Positive ||
            generic is ProbeOutcome.PositiveNeedsAuth
        if (genericPositive) {
            return ServerProbeResult.GenericOpds(
                canonicalCatalogUrl = canonicalCatalog,
                requiresAuth = generic is ProbeOutcome.PositiveNeedsAuth,
            )
        }

        // Neither was definitive. If a call failed with a hard transport-level
        // error (TLS / DNS / refused / timeout / redirect rejected), surface
        // that: the user almost certainly has a typo or the wrong scheme.
        val errors = listOf(calibre, native, generic)
            .filterIsInstance<ProbeOutcome.NegativeWith>()
        val transportError = errors.firstOrNull { it.reason != ProbeError.Unexpected }
        if (transportError != null) {
            return ServerProbeResult.Error(transportError.reason, transportError.message)
        }
        val diagnostics = buildString {
            appendLine("Server didn't match any expected shape:")
            appendLine("  /opds: ${describeNegative(calibre)}")
            appendLine("  /auth/v1/login: ${describeNegative(native)}")
            append("  URL as entered: ${describeNegative(generic)}")
        }
        return ServerProbeResult.Unknown(canonicalBase, diagnostics)
    }

    private fun describeNegative(outcome: ProbeOutcome): String = when (outcome) {
        ProbeOutcome.Positive -> "ok"
        ProbeOutcome.PositiveNeedsAuth -> "ok (needs credentials)"
        is ProbeOutcome.NegativeWith -> "${outcome.reason} — ${outcome.message}"
    }

    private sealed class ProbeOutcome {
        object Positive : ProbeOutcome()

        /** Positive, but the server demanded Basic credentials first. */
        object PositiveNeedsAuth : ProbeOutcome()

        data class NegativeWith(val reason: ProbeError, val message: String) : ProbeOutcome()
    }

    companion object {
        const val DEFAULT_PER_REQUEST_TIMEOUT_MS = 5_000L
        const val USER_AGENT = "QuireAndroid/probe"
        const val SNIFF_BYTES = 256L * 1024
        const val ATOM_NS = "http://www.w3.org/2005/Atom"

        /**
         * Probe client: NO auth interceptor, NO cookies, redirects gated
         * by [SafeRedirectInterceptor]. Public so onboarding-time auth
         * verification (Basic GET /opds, Bearer login POST) can reuse the
         * same redirect policy without sharing the app's account interceptor.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(DEFAULT_PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DEFAULT_PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(DEFAULT_PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // Disable okhttp's default auto-follow so we can apply the
            // same-origin + http→https-only policy ourselves.
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(SafeRedirectInterceptor())
            .build()
    }
}

/**
 * Possible outcomes of a [ServerProbe.probe] call.
 *
 * `canonicalBaseUrl` on the positive variants is what callers should pass
 * to `CalibreCredentialStore.saveBasicAccount` / `saveBearerAccount` — it
 * is already trimmed, IDN-encoded, and has no trailing slash.
 */
sealed class ServerProbeResult {
    /** `/opds` answered 200 atom-xml OR 401 with Basic challenge. */
    data class Calibre(val canonicalBaseUrl: String) : ServerProbeResult()

    /** `/auth/v1/login` answered 405 with `Allow: POST`. */
    data class NativeAuth(val canonicalBaseUrl: String) : ServerProbeResult()

    /** Both endpoints answered positively. Caller asks the user to choose. */
    data class Both(val canonicalBaseUrl: String) : ServerProbeResult()

    /**
     * The URL as typed answered with an OPDS 1.x Atom feed, and neither the
     * calibre-web nor the quire-server shape matched (issue #101).
     *
     * [canonicalCatalogUrl] is the full catalog URL, query and trailing slash
     * intact, to be used verbatim. It may embed an API key: never log it
     * unredacted.
     *
     * [requiresAuth] is true when the probe saw `401` with a Basic challenge
     * rather than a feed, so the caller must collect a username and password
     * before it can confirm the catalog parses.
     */
    data class GenericOpds(
        val canonicalCatalogUrl: String,
        val requiresAuth: Boolean,
    ) : ServerProbeResult()

    /** Neither endpoint was definitive. Caller shows diagnostics + retry. */
    data class Unknown(val canonicalBaseUrl: String, val diagnostics: String) : ServerProbeResult()

    /** Transport-level failure (DNS, TLS, refused, timeout, redirect, etc.). */
    data class Error(val reason: ProbeError, val message: String) : ServerProbeResult()
}

/** Taxonomy of probe failures, mapped to user-facing copy in the UI layer. */
enum class ProbeError {
    MalformedUrl,
    Tls,
    Dns,
    ConnectionRefused,
    Timeout,
    RedirectRejected,
    Unexpected,
}

/**
 * Normalize a user-typed URL into the canonical form we persist in the
 * credential store.
 *
 * Rules:
 *   - Require an explicit `http://` or `https://` scheme. Bare hosts are
 *     rejected because users typing `mycalibre.example` could equally
 *     mean http or https, and choosing for them silently is the wrong
 *     default given the security gap between the two.
 *   - Reject embedded userinfo (`http://user:pass@host`). Credentials live
 *     in `CalibreCredentialStore`, not the URL.
 *   - Reject URL fragments.
 *   - Preserve any path prefix (so `http://host/calibre/` stays a
 *     `/calibre`-rooted deployment).
 *   - Trim a single trailing slash off non-root paths. Leave `/` (empty
 *     path) as the empty string so downstream string concatenation of
 *     endpoint suffixes still produces a valid URL.
 *   - Preserve explicit ports. IPv6 literals require brackets (delegated
 *     to OkHttp's HttpUrl parser).
 *   - IDN hostnames are punycoded by HttpUrl automatically.
 *
 * Returns null on any unrecoverable parse failure.
 */
internal fun normalizeBaseUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null

    val parsed = trimmed.toHttpUrlOrNull() ?: return null

    // Reject embedded credentials and fragments — both are foot-guns for
    // the credential store and the OPDS catalog respectively.
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    if (parsed.fragment != null) return null
    // Discarding query is intentional — base URLs don't have queries; if a
    // user paste-includes one we ignore it rather than guessing intent.
    val pathSegments = parsed.encodedPathSegments
    val rebuiltPath = buildString {
        for (segment in pathSegments) {
            if (segment.isEmpty()) continue
            append('/')
            append(segment)
        }
    }

    val portPart = if (
        (parsed.scheme == "http" && parsed.port == 80) ||
        (parsed.scheme == "https" && parsed.port == 443)
    ) {
        ""
    } else {
        ":${parsed.port}"
    }
    // `parsed.host` is the IDN-encoded (punycode) form. Brackets for IPv6
    // are reintroduced by HttpUrl when the host contains colons.
    val hostForUrl = if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host
    return buildString {
        append(parsed.scheme)
        append("://")
        append(hostForUrl)
        append(portPart)
        append(rebuiltPath)
    }
}

/**
 * Normalize a user-typed OPDS catalog URL (issue #101).
 *
 * Differs from [normalizeBaseUrl] in exactly two ways, both because the result
 * is used verbatim as a resource URL rather than as a prefix that endpoint
 * paths get appended to:
 *   - the **query is preserved** (some catalogs take their key as `?apiKey=...`);
 *   - a **trailing slash is preserved** (some servers distinguish the two).
 *
 * Everything else matches: an explicit scheme is required, userinfo and
 * fragments are rejected, IDN hosts are punycoded, and default ports are
 * dropped. Userinfo stays rejected on purpose, so `https://user:pass@host/feed`
 * is not a supported way to pass credentials; the Basic fields are.
 *
 * Returns null on any unrecoverable parse failure.
 */
fun normalizeCatalogUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null

    val parsed = trimmed.toHttpUrlOrNull() ?: return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    if (parsed.fragment != null) return null

    val portPart = if (
        (parsed.scheme == "http" && parsed.port == 80) ||
        (parsed.scheme == "https" && parsed.port == 443)
    ) {
        ""
    } else {
        ":${parsed.port}"
    }
    val hostForUrl = if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host
    val queryPart = parsed.encodedQuery?.let { "?$it" }.orEmpty()
    return buildString {
        append(parsed.scheme)
        append("://")
        append(hostForUrl)
        append(portPart)
        // encodedPath keeps the leading slash and any trailing one.
        append(parsed.encodedPath)
        append(queryPart)
    }
}

/**
 * Okhttp interceptor that enforces a strict same-origin redirect policy.
 *
 * Behaviour:
 *   - At most 3 hops to bound work.
 *   - Same host (case-insensitive) and same port required across hops.
 *   - Allow `http → https`; reject `https → http` (downgrade).
 *   - Reject cross-origin redirects outright. A calibre-web deployment that
 *     307's to its CDN is the kind of thing that would make the probe
 *     accidentally fetch a Cloudflare error page and call that "OPDS".
 *
 * Throws [RedirectRejectedException] on policy violation, which the
 * outer probe catches and maps to [ProbeError.RedirectRejected].
 */
internal class SafeRedirectInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        var hop = 0
        while (response.isRedirect && hop < MAX_REDIRECTS) {
            val location = response.header("Location") ?: return response
            val originalUrl = request.url
            val nextUrl = originalUrl.resolve(location)
                ?: throw RedirectRejectedException("malformed Location header: $location")
            if (!nextUrl.host.equals(originalUrl.host, ignoreCase = true)) {
                throw RedirectRejectedException(
                    "redirect to ${nextUrl.host} blocked (must stay on ${originalUrl.host})",
                )
            }
            if (nextUrl.port != originalUrl.port) {
                throw RedirectRejectedException(
                    "redirect changed port ${originalUrl.port} → ${nextUrl.port}",
                )
            }
            val schemeOk = nextUrl.scheme == originalUrl.scheme ||
                (originalUrl.scheme == "http" && nextUrl.scheme == "https")
            if (!schemeOk) {
                throw RedirectRejectedException(
                    "scheme downgrade ${originalUrl.scheme} → ${nextUrl.scheme} blocked",
                )
            }
            response.close()
            request = request.newBuilder().url(nextUrl).build()
            response = chain.proceed(request)
            hop++
        }
        return response
    }

    private companion object {
        const val MAX_REDIRECTS = 3
    }
}

internal class RedirectRejectedException(message: String) : IOException(message)
