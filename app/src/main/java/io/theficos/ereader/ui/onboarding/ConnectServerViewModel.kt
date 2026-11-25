package io.theficos.ereader.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.SavedCatalog
import io.theficos.ereader.data.opds.CatalogResult
import io.theficos.ereader.data.opds.CatalogSyncRepository
import io.theficos.ereader.data.opds.ProbeError
import io.theficos.ereader.data.opds.ServerProbe
import io.theficos.ereader.data.opds.ServerProbeResult
import io.theficos.ereader.data.opds.cleartextBlockedMessage
import io.theficos.ereader.data.opds.normalizeCatalogUrl
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import java.io.IOException
import java.time.OffsetDateTime
import java.util.Base64
import java.util.concurrent.TimeUnit
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
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Drives the "Connect to my server" screen.
 *
 * Lifecycle of a flow:
 *   1. User types a URL → [onUrlChange].
 *   2. User taps Probe → [probe] → [UiState.Probing] → [UiState.Result].
 *   3. A catalog (Calibre-Web or any OPDS feed) is tested and saved to the
 *      catalog list through [saveCatalog]; a Quire server signs in through
 *      [verifyAndSaveBearer]. Verification uses the plain onboarding HTTP
 *      client (no `AccountAuthInterceptor`), and nothing is persisted unless
 *      it succeeds. Calibre-Web is no longer offered as a sign-in here.
 *   4. On persist success, [UiState.Completed] is emitted and the screen
 *      navigates the caller to Library.
 *
 * Cancellation: probe and verify jobs are launched on `viewModelScope`, so
 * navigating away cancels them. We do NOT persist partial probe state — the
 * URL field is the only saveable, and that's held in Compose state.
 */
class ConnectServerViewModel(
    private val credentialStore: CalibreCredentialStore,
    private val catalogs: CatalogSyncRepository,
    private val probe: ServerProbe = ServerProbe(),
    private val onboardingClient: OkHttpClient = defaultOnboardingClient(),
    private val catalogCheck: CatalogCheck = CatalogCheck(probe, onboardingClient),
    /**
     * Where the blocking probe and verification calls run. Defaults to
     * [Dispatchers.IO] in production; tests hand in the same test dispatcher
     * that backs `Dispatchers.Main`, so a virtual-time advance drives a whole
     * verification to its terminal state instead of racing a real thread pool.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val legacyHighlightScopeFor: (AccountCredentials?, AccountCredentials.Bearer, String?) -> String? = { _, _, _ -> null },
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var probeJob: Job? = null
    private var verifyJob: Job? = null

    private val account = credentialStore.getAccount()
    private val nativeAccount = account as? AccountCredentials.Bearer
    val initialQuireUrl = when (account) {
        is AccountCredentials.Bearer -> account.baseUrl
        is AccountCredentials.Basic -> account.quireServerUrl ?: account.baseUrl
        else -> ""
    }
    val initialQuireEmail = nativeAccount?.email?.takeIf { '@' in it }.orEmpty()
    private val initialCanonicalQuireUrl = canonicalQuireUrl(initialQuireUrl)
    val isReauthentication = credentialStore.needsReauth.value && nativeAccount != null
    private val legacyAccount = account as? AccountCredentials.Basic

    private val _legacyMigration = MutableStateFlow(LegacyMigration.UNKNOWN)

    /** Whether this legacy reader's server lets them create their own Quire account, once [claimLegacyAccount] knows. */
    val legacyMigration: StateFlow<LegacyMigration> = _legacyMigration.asStateFlow()

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
     * Test the catalog a probe found with the optional login, then add it to the catalog list.
     * A Calibre-Web address becomes a catalog like any other: signing in with it is being retired.
     */
    fun saveCatalog(found: ServerProbeResult, username: String?, password: String?) {
        verifyJob?.cancel()
        _state.value = UiState.Verifying
        verifyJob = viewModelScope.launch {
            val result = withContext(ioDispatcher) { catalogCheck.verify(found, username, password) }
            _state.value = when (result) {
                is CatalogCheck.Result.Failed -> UiState.VerificationFailed(result.message)
                is CatalogCheck.Result.Found -> {
                    val entry = SavedCatalog(SavedCatalog.newId(), SavedCatalog.defaultName(result.catalog.baseUrl), result.catalog)
                    when (val saved = catalogs.save(entry)) {
                        CatalogResult.Done -> UiState.Completed
                        is CatalogResult.Failed -> UiState.VerificationFailed(catalogFailureMessage(saved.reason))
                    }
                }
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
        verifyAndSaveBearerRequest(canonicalBaseUrl, email, password, null)
    }

    fun verifyAndSavePairing(canonicalBaseUrl: String, ticket: String) {
        val trimmed = ticket.trim()
        if (!trimmed.matches(Regex("[A-Za-z0-9_-]{20,128}"))) {
            _state.value = UiState.VerificationFailed(uiText(R.string.onboarding_pairing_ticket_invalid))
            return
        }
        verifyAndSaveBearerRequest(canonicalBaseUrl, "", "", trimmed)
    }

    /**
     * Quietly exchange the stored Calibre login for a Quire session. A reader who
     * already migrated on another device is signed in without typing anything; one
     * who has not is offered [migrateLegacyAccount]. Every other answer, including
     * a server without self-service migration, leaves the legacy account untouched.
     */
    fun claimLegacyAccount() {
        val legacy = legacyAccount ?: return
        val serverUrl = initialCanonicalQuireUrl ?: return
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { runBearerVerification(serverUrl, "", "", legacy = legacy) }
            when (result) {
                is VerificationOutcome.OkBearer ->
                    if (saveNativeAccount(serverUrl, "", result) == UiState.Completed) _state.value = UiState.Completed
                is VerificationOutcome.Failure -> result.migration?.let { _legacyMigration.value = it }
            }
        }
    }

    /** Create the reader's Quire account, authorised by the Calibre login this device already holds. */
    fun migrateLegacyAccount(email: String, password: String) {
        val legacy = legacyAccount
        val serverUrl = initialCanonicalQuireUrl
        if (legacy == null || serverUrl == null) {
            _state.value = UiState.VerificationFailed(uiText(R.string.onboarding_quire_connection_changed))
            return
        }
        verifyJob?.cancel()
        _state.value = UiState.Verifying
        verifyJob = viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runBearerVerification(serverUrl, email.trim(), password, legacy = legacy)
            }
            _state.value = when (result) {
                is VerificationOutcome.OkBearer -> saveNativeAccount(serverUrl, email, result)
                is VerificationOutcome.Failure -> UiState.VerificationFailed(result.message)
            }
        }
    }

    private fun verifyAndSaveBearerRequest(
        canonicalBaseUrl: String,
        email: String,
        password: String,
        pairingTicket: String?,
    ) {
        val serverUrl = canonicalQuireUrl(canonicalBaseUrl)
        if (serverUrl == null || (isReauthentication && serverUrl != initialCanonicalQuireUrl)) {
            _state.value = UiState.VerificationFailed(uiText(R.string.onboarding_invalid_quire_url))
            return
        }
        verifyJob?.cancel()
        _state.value = UiState.Verifying
        verifyJob = viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runBearerVerification(serverUrl, email.trim(), password, pairingTicket)
            }
            _state.value = when (result) {
                is VerificationOutcome.OkBearer -> {
                    saveNativeAccount(serverUrl, email, result)
                }
                is VerificationOutcome.Failure -> UiState.VerificationFailed(result.message)
            }
        }
    }

    private suspend fun saveNativeAccount(
        serverUrl: String,
        inputEmail: String,
        result: VerificationOutcome.OkBearer,
    ): UiState {
        val previous = credentialStore.getAccount()
        val sameServer = serverUrl == initialCanonicalQuireUrl
        val legacy = previous as? AccountCredentials.Basic
        val legacyServer = legacy?.let { canonicalQuireUrl(it.quireServerUrl ?: it.baseUrl) }
        val identity = if (legacy != null && serverUrl == legacyServer) {
            val verified = withContext(ioDispatcher) { fetchNativeIdentity(serverUrl, result.token) }
                ?: return UiState.VerificationFailed(uiText(R.string.onboarding_legacy_check_failed))
            if (verified.legacyCalibreUserId != legacy.subject) {
                return UiState.VerificationFailed(uiText(R.string.onboarding_legacy_binding_required))
            }
            verified
        } else {
            null
        }
        if (identity != null && result.accountId != null && identity.accountId != result.accountId) {
            return UiState.VerificationFailed(uiText(R.string.onboarding_legacy_check_failed))
        }

        val email = result.accountSubject ?: inputEmail.trim().ifEmpty { result.accountId.orEmpty() }
        val existingId = nativeAccount?.accountId ?: nativeAccount?.email?.takeIf(::isNativeAccountId)
        val verifiedId = identity?.accountId ?: result.accountId
        val sameNativeAccount = if (existingId != null && verifiedId != null) {
            existingId == verifiedId
        } else {
            email.equals(nativeAccount?.email, ignoreCase = true)
        }
        if (isReauthentication && !sameNativeAccount) {
            return UiState.VerificationFailed(uiText(R.string.onboarding_quire_reauth_same_account))
        }
        if (credentialStore.getAccount() != previous) {
            return UiState.VerificationFailed(uiText(R.string.onboarding_quire_connection_changed))
        }

        val candidate = AccountCredentials.Bearer(
            baseUrl = serverUrl,
            email = email,
            token = result.token,
            expiresAtEpochMs = result.expiresAtEpochMs,
            accountId = verifiedId ?: existingId?.takeIf { sameServer && sameNativeAccount },
        )
        credentialStore.saveBearerAccount(
            baseUrl = candidate.baseUrl,
            email = candidate.email,
            token = candidate.token,
            expiresAtEpochMs = candidate.expiresAtEpochMs,
            accountId = candidate.accountId,
            previousHighlightScope = legacyHighlightScopeFor(previous, candidate, identity?.legacyCalibreUserId),
        )
        return UiState.Completed
    }

    private fun runBearerVerification(
        canonicalBaseUrl: String,
        email: String,
        password: String,
        pairingTicket: String? = null,
        legacy: AccountCredentials.Basic? = null,
    ): VerificationOutcome {
        // Build the LoginRequest body via kotlinx.serialization's JSON
        // builder. We avoid `@Serializable` data classes here because the
        // `:app` module doesn't apply the kotlin-serialization compiler
        // plugin (only the runtime dependency); a `buildJsonObject` builder
        // does the same job with no codegen.
        val payload = buildJsonObject {
            if (pairingTicket != null) {
                put("ticket", JsonPrimitive(pairingTicket))
                put("device_name", JsonPrimitive("Android phone"))
            } else if (legacy != null && email.isEmpty()) {
                put("device_name", JsonPrimitive("Android phone"))
            } else {
                if (legacy != null) put("device_name", JsonPrimitive("Android phone"))
                put("email", JsonPrimitive(email))
                put("password", JsonPrimitive(password))
            }
        }.toString()
        val path = when {
            pairingTicket != null -> "pairing/exchange"
            legacy == null -> "login"
            email.isEmpty() -> "migrate/claim"
            else -> "migrate"
        }
        val req = Request.Builder()
            .url("$canonicalBaseUrl/auth/v1/$path")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .headers(
                Headers.Builder()
                    .add("Accept", "application/json")
                    .add("User-Agent", ServerProbe.USER_AGENT)
                    .apply {
                        if (legacy != null) {
                            val basic = Base64.getEncoder()
                                .encodeToString("${legacy.username}:${legacy.password}".toByteArray())
                            add("Authorization", "Basic $basic")
                        }
                    }
                    .build()
            )
            .build()
        return try {
            onboardingClient.newCall(req).execute().use { resp ->
                if (legacy != null && resp.code != 200) {
                    return legacyMigrationFailure(resp.code, resp.body?.string().orEmpty())
                }
                when (resp.code) {
                    200 -> {
                        val body = resp.body?.string().orEmpty()
                        val parsed = parseLogin(body)
                        if (parsed == null || parsed.token.isBlank() || parsed.token.any(Char::isWhitespace)) {
                            VerificationOutcome.Failure(
                                uiText(R.string.onboarding_server_returned_200_but_the_response_didn),
                            )
                        } else if (pairingTicket != null && parsed.accountId == null) {
                            VerificationOutcome.Failure(uiText(R.string.onboarding_pairing_identity_missing))
                        } else {
                            VerificationOutcome.OkBearer(parsed.token, parsed.expiresAtEpochMs, parsed.accountId, parsed.accountSubject)
                        }
                    }
                    401 -> VerificationOutcome.Failure(
                        if (pairingTicket != null) uiText(R.string.onboarding_pairing_ticket_expired)
                        else uiText(R.string.onboarding_email_or_password_rejected_by_the_server),
                    )
                    in 500..599 -> VerificationOutcome.Failure(
                        uiText(R.string.onboarding_server_error_try_again_later, resp.code),
                    )
                    else -> VerificationOutcome.Failure(
                        if (pairingTicket != null) uiText(R.string.onboarding_unexpected_pairing_response, resp.code)
                        else uiText(R.string.onboarding_unexpected_response_from_auth_v1_login, resp.code),
                    )
                }
            }
        } catch (e: IOException) {
            VerificationOutcome.Failure(
                if (cleartextBlockedMessage(e) != null) uiText(R.string.error_cleartext_blocked)
                else e.localizedMessage?.let(UiText::Dynamic) ?: uiText(R.string.common_network_error),
            )
        }
    }

    private fun legacyMigrationFailure(code: Int, body: String): VerificationOutcome.Failure {
        val detail = runCatching {
            ((Json.parseToJsonElement(body) as? JsonObject)?.get("detail") as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        return when {
            code == 409 && detail == "not_migrated" ->
                VerificationOutcome.Failure(uiText(R.string.quire_migration_unavailable), LegacyMigration.OFFERED)
            code == 409 && detail == "email_unavailable" ->
                VerificationOutcome.Failure(uiText(R.string.quire_migration_email_unavailable))
            code == 409 && detail == "invalid_email" ->
                VerificationOutcome.Failure(uiText(R.string.quire_migration_invalid_email))
            code == 409 && detail == "invalid_password" ->
                VerificationOutcome.Failure(uiText(R.string.quire_migration_password_too_short))
            code == 409 && detail == "already_migrated" || code == 403 ->
                VerificationOutcome.Failure(uiText(R.string.quire_migration_sign_in_instead))
            code == 401 -> VerificationOutcome.Failure(uiText(R.string.quire_migration_calibre_rejected))
            code in 500..599 -> VerificationOutcome.Failure(uiText(R.string.onboarding_server_error_try_again_later, code))
            // No migration route at all: the server has self-service migration turned off.
            code == 404 || code == 405 ->
                VerificationOutcome.Failure(uiText(R.string.quire_migration_unavailable), LegacyMigration.UNAVAILABLE)
            else -> VerificationOutcome.Failure(uiText(R.string.quire_migration_unavailable))
        }
    }

    private fun canonicalQuireUrl(value: String): String? =
        normalizeCatalogUrl(value)?.takeUnless { '?' in it }?.trimEnd('/')

    private data class NativeIdentity(val accountId: String, val legacyCalibreUserId: String?)

    private fun fetchNativeIdentity(serverUrl: String, token: String): NativeIdentity? {
        val request = Request.Builder()
            .url("$serverUrl/auth/v1/me")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .build()
        return runCatching {
            onboardingClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val payload = Json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                val user = payload?.get("user") as? JsonObject ?: return null
                if ((user["backend"] as? JsonPrimitive)?.contentOrNull != "native") return null
                val accountId = (user["id"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf(::isNativeAccountId) ?: return null
                NativeIdentity(accountId, (user["legacy_calibre_user_id"] as? JsonPrimitive)?.contentOrNull)
            }
        }.getOrNull()
    }

    private fun isNativeAccountId(value: String): Boolean = value.matches(Regex("native:[1-9][0-9]{0,17}"))

    /** Token plus optional absolute expiry, parsed from a login response. */
    private data class ParsedLogin(val token: String, val expiresAtEpochMs: Long?, val accountId: String?, val accountSubject: String?)

    /**
     * Parse `{"token": ..., "expires_at": ...}` from the login response.
     * `expires_at` is an ISO-8601 instant (e.g. `2026-06-28T12:00:00+00:00`
     * or `...Z`); an absent or unparseable value yields a null expiry, which
     * the credential store treats as "expiry unknown" rather than failing the
     * login. Returns null only when the body isn't a JSON object.
     */
    private fun parseLogin(body: String): ParsedLogin? = runCatching {
        val parsed = Json.parseToJsonElement(body) as? JsonObject ?: return null
        val token = (parsed["token"] as? JsonPrimitive)?.contentOrNull ?: return null
        val expiresAt = (parsed["expires_at"] as? JsonPrimitive)?.contentOrNull
            ?.let { raw -> runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull() }
        val accountId = (parsed["account_id"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(::isNativeAccountId)
        val accountSubject = (parsed["account_subject"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= 320 }
        ParsedLogin(token, expiresAt, accountId, accountSubject)
    }.getOrNull()

    /** UI state. Sealed so the screen can `when`-exhaust render branches. */
    sealed class UiState {
        object Idle : UiState()
        data class Probing(val rawUrl: String) : UiState()
        data class Result(val probe: ServerProbeResult) : UiState()
        object Verifying : UiState()
        data class VerificationFailed(val message: UiText) : UiState()
        object Completed : UiState()
    }

    private sealed class VerificationOutcome {
        data class OkBearer(val token: String, val expiresAtEpochMs: Long?, val accountId: String? = null, val accountSubject: String? = null) : VerificationOutcome()

        /** [migration] is what a legacy claim learned about self-service migration, when it learned anything. */
        data class Failure(val message: UiText, val migration: LegacyMigration? = null) : VerificationOutcome()
    }

    /** Whether a legacy Calibre-Web reader can create their own Quire account from the app. */
    enum class LegacyMigration { UNKNOWN, OFFERED, UNAVAILABLE }

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
fun probeErrorMessage(reason: ProbeError, raw: String): UiText = when (reason) {
    ProbeError.MalformedUrl ->
        uiText(R.string.onboarding_that_doesn_t_look_like_a_valid)
    ProbeError.Tls ->
        uiText(R.string.onboarding_we_couldn_t_verify_the_server_s, raw)
    ProbeError.Dns ->
        uiText(R.string.onboarding_we_couldn_t_resolve_that_hostname_check)
    ProbeError.ConnectionRefused ->
        uiText(R.string.onboarding_the_server_refused_the_connection_is_it)
    ProbeError.Timeout ->
        uiText(R.string.onboarding_the_server_didn_t_respond_in_time)
    ProbeError.RedirectRejected ->
        uiText(R.string.onboarding_the_server_redirected_us_somewhere_unexpected_try)
    ProbeError.Cleartext -> uiText(R.string.error_cleartext_blocked)
    ProbeError.Unexpected -> uiText(R.string.onboarding_couldn_t_reach_the_server, raw)
}
