package io.theficos.ereader.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.data.opds.ProbeError
import io.theficos.ereader.data.opds.ServerProbe
import io.theficos.ereader.data.opds.ServerProbeResult
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
            val outcome = withContext(Dispatchers.IO) { probe.probe(rawUrl) }
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
            val result = withContext(Dispatchers.IO) {
                runBasicVerification(canonicalBaseUrl, username, password)
            }
            _state.value = when (result) {
                is VerificationOutcome.OkBasic -> {
                    credentialStore.saveBasicAccount(canonicalBaseUrl, username, password)
                    UiState.Completed
                }
                is VerificationOutcome.OkBearer -> error("unreachable in basic flow")
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
            val result = withContext(Dispatchers.IO) {
                runBearerVerification(canonicalBaseUrl, email, password)
            }
            _state.value = when (result) {
                is VerificationOutcome.OkBearer -> {
                    credentialStore.saveBearerAccount(canonicalBaseUrl, email, result.token)
                    UiState.Completed
                }
                is VerificationOutcome.OkBasic -> error("unreachable in bearer flow")
                is VerificationOutcome.Failure -> UiState.VerificationFailed(result.message)
            }
        }
    }

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
                    else -> VerificationOutcome.Failure(
                        "Unexpected response ${resp.code} from /opds.",
                    )
                }
            }
        } catch (e: IOException) {
            VerificationOutcome.Failure(e.localizedMessage ?: "Network error.")
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
                        val token = parseLoginToken(body)
                        if (token.isNullOrBlank()) {
                            VerificationOutcome.Failure(
                                "Server returned 200 but the response didn't include a token.",
                            )
                        } else {
                            VerificationOutcome.OkBearer(token)
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
            VerificationOutcome.Failure(e.localizedMessage ?: "Network error.")
        }
    }

    private fun parseLoginToken(body: String): String? = runCatching {
        val parsed = Json.parseToJsonElement(body) as? JsonObject ?: return null
        (parsed["token"] as? JsonPrimitive)?.content
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
        data class OkBearer(val token: String) : VerificationOutcome()
        data class Failure(val message: String) : VerificationOutcome()
    }

    companion object {
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
    ProbeError.Unexpected -> "Couldn't reach the server: $raw"
}
