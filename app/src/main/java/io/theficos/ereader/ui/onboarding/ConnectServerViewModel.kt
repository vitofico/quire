package io.theficos.ereader.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.data.opds.CLEARTEXT_BLOCKED_MESSAGE
import io.theficos.ereader.data.opds.ProbeError
import io.theficos.ereader.data.opds.ServerProbe
import io.theficos.ereader.data.opds.ServerProbeResult
import io.theficos.ereader.data.opds.cleartextBlockedMessage
import io.theficos.ereader.data.opds.isAcquisitionRel
import io.theficos.ereader.data.opds.isEpubMediaType
import io.theficos.ereader.data.opds.parseXmlOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.OffsetDateTime
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Drives the "Connect to my server" screen.
 *
 * Lifecycle of a flow:
 *   1. User types a URL → [onUrlChange].
 *   2. User taps Probe → [probe] → [UiState.Probing] → [UiState.Result].
 *   3. On `Calibre` or `NativeAuth` (or once they pick after `Both`), user
 *      fills credentials and submits → [verifyAndSaveBasic] /
 *      [verifyAndSaveBearer]. The verification call hits the plain
 *      onboarding HTTP client (no `AccountAuthInterceptor`), and we only
 *      persist to [CalibreCredentialStore] on success.
 *   4. On persist success, [UiState.Completed] is emitted and the screen
 *      navigates the caller to Library.
 *
 * Cancellation: probe and verify jobs are launched on `viewModelScope`, so
 * navigating away cancels them. We do NOT persist partial probe state — the
 * URL field is the only saveable, and that's held in Compose state.
 */
class ConnectServerViewModel(
    private val credentialStore: CalibreCredentialStore,
    private val probe: ServerProbe = ServerProbe(),
    private val onboardingClient: OkHttpClient = defaultOnboardingClient(),
    /**
     * Where the blocking probe and verification calls run. Defaults to
     * [Dispatchers.IO] in production; tests hand in the same test dispatcher
     * that backs `Dispatchers.Main`, so a virtual-time advance drives a whole
     * verification to its terminal state instead of racing a real thread pool.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var probeJob: Job? = null
    private var verifyJob: Job? = null

    /** Reset any in-flight result, ready for a fresh URL. */
    fun resetToIdle() {
        probeJob?.cancel()
        verifyJob?.cancel()
        _state.value = UiState.Idle
    }

    /** Start probing [rawUrl]. */
    fun probe(rawUrl: String) {
        probeJob?.cancel()
        _state.value = UiState.Probing(rawUrl)
        probeJob = viewModelScope.launch {
            val outcome = withContext(ioDispatcher) { probe.probe(rawUrl) }
            _state.value = UiState.Result(outcome)
        }
    }

    /**
     * Verify Basic credentials by issuing a GET to `${base}/opds` with an
     * `Authorization: Basic ...` header. Persist iff the response is 200.
     */
    fun verifyAndSaveBasic(canonicalBaseUrl: String, username: String, password: String) {
        verifyJob?.cancel()
        _state.value = UiState.Verifying
        verifyJob = viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runBasicVerification(canonicalBaseUrl, username, password)
            }
            _state.value = when (result) {
                is VerificationOutcome.OkBasic -> {
                    credentialStore.saveBasicAccount(canonicalBaseUrl, username, password)
                    UiState.Completed
                }
                is VerificationOutcome.OkBearer -> error("unreachable in basic flow")
                is VerificationOutcome.OkOpds -> error("unreachable in basic flow")
                is VerificationOutcome.Failure -> UiState.VerificationFailed(result.message)
            }
        }
    }

    /**
     * Verify NativeAuth credentials by POSTing to `${base}/auth/v1/login`.
     * On 200 the response shape is `{"token": ..., "expires_at": ...}`
     * (per S-1: `server/quire_server/api/auth.py::LoginResponse`). We
     * persist the token via `saveBearerAccount`.
     */
    fun verifyAndSaveBearer(canonicalBaseUrl: String, email: String, password: String) {
        verifyJob?.cancel()
        _state.value = UiState.Verifying
        verifyJob = viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runBearerVerification(canonicalBaseUrl, email, password)
            }
            _state.value = when (result) {
                is VerificationOutcome.OkBearer -> {
                    credentialStore.saveBearerAccount(
                        canonicalBaseUrl,
                        email,
                        result.token,
                        result.expiresAtEpochMs,
                    )
                    UiState.Completed
                }
                is VerificationOutcome.OkBasic -> error("unreachable in bearer flow")
                is VerificationOutcome.OkOpds -> error("unreachable in bearer flow")
                is VerificationOutcome.Failure -> UiState.VerificationFailed(result.message)
            }
        }
    }

    /**
     * Verify a generic OPDS catalog (issue #101) and persist it on success.
     *
     * Accepts the feed when it parses as Atom AND carries at least one
     * navigation link or one EPUB acquisition link. The navigation half of that
     * test is load-bearing: Kavita's root feed is pure navigation, so requiring
     * a book at the root would reject the very server this was built for.
     *
     * The URL may embed an API key, so no failure message may quote it.
     */
    fun verifyAndSaveOpds(catalogUrl: String, username: String?, password: String?) {
        verifyJob?.cancel()
        _state.value = UiState.Verifying
        verifyJob = viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runOpdsVerification(catalogUrl, username, password)
            }
            _state.value = when (result) {
                is VerificationOutcome.OkOpds -> {
                    credentialStore.saveOpdsAccount(catalogUrl, username, password)
                    UiState.Completed
                }
                is VerificationOutcome.Failure -> UiState.VerificationFailed(result.message)
                else -> UiState.VerificationFailed("Unexpected verification result.")
            }
        }
    }

    private fun runOpdsVerification(
        catalogUrl: String,
        username: String?,
        password: String?,
    ): VerificationOutcome {
        val builder = Request.Builder()
            .url(catalogUrl)
            .header("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.8")
            .header("User-Agent", ServerProbe.USER_AGENT)
            .get()
        if (username != null && password != null) {
            val token = Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray())
            builder.header("Authorization", "Basic $token")
        }
        return try {
            onboardingClient.newCall(builder.build()).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        val body = resp.body?.string().orEmpty()
                        if (isUsableOpdsFeed(body)) {
                            VerificationOutcome.OkOpds
                        } else {
                            VerificationOutcome.Failure(
                                "That URL answered, but it isn't an OPDS catalog with " +
                                    "anything Quire can show.",
                            )
                        }
                    }
                    401 -> VerificationOutcome.Failure(
                        "The catalog rejected those credentials.",
                    )
                    in 500..599 -> VerificationOutcome.Failure(
                        "Server error ${resp.code}. Try again later.",
                    )
                    else -> VerificationOutcome.Failure(
                        "Unexpected response ${resp.code} from the catalog.",
                    )
                }
            }
        } catch (e: IOException) {
            VerificationOutcome.Failure(
                cleartextBlockedMessage(e) ?: e.localizedMessage ?: "Network error.",
            )
        }
    }

    /**
     * True when [body] is an Atom feed carrying at least one navigation link or
     * one EPUB acquisition link. Either alone is a usable catalog: a navigation
     * root leads to books, and an acquisition feed is books.
     */
    private fun isUsableOpdsFeed(body: String): Boolean = runCatching {
        // Shared hardened parser. An inline DocumentBuilderFactory with Apache
        // feature names throws on Android and would make every catalog look
        // invalid on device while the JVM tests stayed green (issue #101).
        val doc = parseXmlOrNull(body.toByteArray()) ?: return false
        val root = doc.documentElement ?: return false
        if (root.localName != "feed" || root.namespaceURI != ATOM_NS) return false

        val links = doc.getElementsByTagNameNS(ATOM_NS, "link")
        for (i in 0 until links.length) {
            val el = links.item(i) as org.w3c.dom.Element
            val rel = el.getAttribute("rel")
            val type = el.getAttribute("type")
            // Share the browser's own rules rather than restating them: a
            // stricter test here would reject at onboarding a feed the catalog
            // screen then renders fine. Flibusta emits bare `application/epub`
            // on a minority of entries (issue #101).
            val isAcquisition =
                isAcquisitionRel(rel) && isEpubMediaType(type)
            val isNavigation = rel == "subsection" ||
                type.startsWith("application/atom+xml;profile=opds-catalog")
            if (isAcquisition || isNavigation) return true
        }
        false
    }.getOrDefault(false)

    private fun runBasicVerification(
        canonicalBaseUrl: String,
        username: String,
        password: String,
    ): VerificationOutcome {
        val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        val req = Request.Builder()
            .url("$canonicalBaseUrl/opds")
            .header("Authorization", "Basic $token")
            .header("Accept", "application/atom+xml")
            .header("User-Agent", ServerProbe.USER_AGENT)
            .get()
            .build()
        return try {
            onboardingClient.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> VerificationOutcome.OkBasic
                    401 -> VerificationOutcome.Failure("Username or password rejected by the server.")
                    in 500..599 -> VerificationOutcome.Failure(
                        "Server error ${resp.code}. Try again later.",
                    )
                    // A server that demands Basic auth everywhere (Komga does)
                    // satisfies the calibre probe's 401-with-challenge test, then
                    // 404s here because its catalog lives elsewhere. Say so,
                    // rather than leaving the user at "unexpected response".
                    404 -> VerificationOutcome.Failure(
                        "This server asked for a username and password, but it has " +
                            "no catalog at /opds, so it probably isn't calibre-web. " +
                            "If it's Komga or another OPDS server, go back and enter " +
                            "its full catalog URL instead.",
                    )
                    else -> VerificationOutcome.Failure(
                        "Unexpected response ${resp.code} from /opds.",
                    )
                }
            }
        } catch (e: IOException) {
            VerificationOutcome.Failure(
                cleartextBlockedMessage(e) ?: e.localizedMessage ?: "Network error.",
            )
        }
    }

    private fun runBearerVerification(
        canonicalBaseUrl: String,
        email: String,
        password: String,
    ): VerificationOutcome {
        // Build the LoginRequest body via kotlinx.serialization's JSON
        // builder. We avoid `@Serializable` data classes here because the
        // `:app` module doesn't apply the kotlin-serialization compiler
        // plugin (only the runtime dependency); a `buildJsonObject` builder
        // does the same job with no codegen.
        val payload = buildJsonObject {
            put("email", JsonPrimitive(email))
            put("password", JsonPrimitive(password))
        }.toString()
        val req = Request.Builder()
            .url("$canonicalBaseUrl/auth/v1/login")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .headers(
                Headers.Builder()
                    .add("Accept", "application/json")
                    .add("User-Agent", ServerProbe.USER_AGENT)
                    .build()
            )
            .build()
        return try {
            onboardingClient.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        val body = resp.body?.string().orEmpty()
                        val parsed = parseLogin(body)
                        if (parsed == null || parsed.token.isBlank()) {
                            VerificationOutcome.Failure(
                                "Server returned 200 but the response didn't include a token.",
                            )
                        } else {
                            VerificationOutcome.OkBearer(parsed.token, parsed.expiresAtEpochMs)
                        }
                    }
                    401 -> VerificationOutcome.Failure("Email or password rejected by the server.")
                    in 500..599 -> VerificationOutcome.Failure(
                        "Server error ${resp.code}. Try again later.",
                    )
                    else -> VerificationOutcome.Failure(
                        "Unexpected response ${resp.code} from /auth/v1/login.",
                    )
                }
            }
        } catch (e: IOException) {
            VerificationOutcome.Failure(
                cleartextBlockedMessage(e) ?: e.localizedMessage ?: "Network error.",
            )
        }
    }

    /** Token plus optional absolute expiry, parsed from a login response. */
    private data class ParsedLogin(val token: String, val expiresAtEpochMs: Long?)

    /**
     * Parse `{"token": ..., "expires_at": ...}` from the login response.
     * `expires_at` is an ISO-8601 instant (e.g. `2026-06-28T12:00:00+00:00`
     * or `...Z`); an absent or unparseable value yields a null expiry, which
     * the credential store treats as "expiry unknown" rather than failing the
     * login. Returns null only when the body isn't a JSON object.
     */
    private fun parseLogin(body: String): ParsedLogin? = runCatching {
        val parsed = Json.parseToJsonElement(body) as? JsonObject ?: return null
        val token = (parsed["token"] as? JsonPrimitive)?.content ?: return null
        val expiresAt = (parsed["expires_at"] as? JsonPrimitive)?.content
            ?.let { raw -> runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull() }
        ParsedLogin(token, expiresAt)
    }.getOrNull()

    /** UI state. Sealed so the screen can `when`-exhaust render branches. */
    sealed class UiState {
        object Idle : UiState()
        data class Probing(val rawUrl: String) : UiState()
        data class Result(val probe: ServerProbeResult) : UiState()
        object Verifying : UiState()
        data class VerificationFailed(val message: String) : UiState()
        object Completed : UiState()
    }

    private sealed class VerificationOutcome {
        object OkBasic : VerificationOutcome()
        object OkOpds : VerificationOutcome()
        data class OkBearer(val token: String, val expiresAtEpochMs: Long?) : VerificationOutcome()
        data class Failure(val message: String) : VerificationOutcome()
    }

    companion object {
        /** Atom namespace, the only one an OPDS 1.x feed root may carry. */
        const val ATOM_NS = "http://www.w3.org/2005/Atom"

        /**
         * The onboarding HTTP client deliberately does NOT use
         * `OpdsHttpClient` — that one carries the account interceptor and
         * would leak any stale credentials into the verification request.
         *
         * Redirects are disabled outright. The verification step targets
         * the same URL the probe just confirmed; if that URL only answers
         * after a redirect, the probe will have already followed it and
         * the canonical base URL surfaced to us reflects the final
         * destination. A surprise 30x at verification time would more
         * likely indicate an attack (or a misconfigured reverse proxy)
         * than a benign hop — failing closed is safer.
         */
        fun defaultOnboardingClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/** Maps a [ProbeError] to a user-facing message. Kept out of the VM so the
 *  UI layer owns wording (translations live in resources, eventually). */
fun probeErrorMessage(reason: ProbeError, raw: String): String = when (reason) {
    ProbeError.MalformedUrl ->
        "That doesn't look like a valid URL. Start with http:// or https://."
    ProbeError.Tls ->
        "We couldn't verify the server's TLS certificate. " +
            "Check the URL or your network. ($raw)"
    ProbeError.Dns ->
        "We couldn't resolve that hostname. Check the URL and your connection."
    ProbeError.ConnectionRefused ->
        "The server refused the connection. Is it running and reachable?"
    ProbeError.Timeout ->
        "The server didn't respond in time. Check your network and try again."
    ProbeError.RedirectRejected ->
        "The server redirected us somewhere unexpected. " +
            "Try the redirect's final URL directly."
    ProbeError.Cleartext -> CLEARTEXT_BLOCKED_MESSAGE
    ProbeError.Unexpected -> "Couldn't reach the server: $raw"
}
