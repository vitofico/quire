package io.theficos.ereader.auth

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * On-device credential store.
 *
 * Historically this was strictly a calibre-web (HTTP Basic) credential cache;
 * the class name is retained so existing callers don't churn. As of A-1 it
 * supports two account schemes — BASIC (calibre-web) and BEARER (Native-auth
 * `quire_server` / Quire Cloud) — selected at save time by the caller via
 * [saveBasicAccount] / [saveBearerAccount]. Onboarding UI (task A-2) is the
 * intended caller; this layer carries no probing or scheme detection logic.
 *
 * Storage backing is [EncryptedSharedPreferences]. Each save writes
 * scheme-specific keys and removes inverse-scheme keys in one editor
 * transaction; mutations are serialized under an intrinsic lock so the
 * stored record and the observable flows can't diverge.
 *
 * Backwards compatibility: pre-A-1 records (no `scheme` key) are treated as
 * BASIC if `base_url` + `username` + `password` are present. The scheme key
 * is best-effort backfilled on first load so subsequent reads don't keep
 * inferring.
 */
class CalibreCredentialStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "calibre_creds",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * Single lock guarding every prefs mutation. Without this, two concurrent
     * `saveBearerAccount` + `clear` callers can interleave the editor
     * transaction with the flow update and leave the store inconsistent.
     */
    private val mutationLock = Any()

    private val _accountFlow: MutableStateFlow<AccountCredentials?>
    private val _flow: MutableStateFlow<CalibreCredentials?>

    init {
        val initial = loadAccount()
        _accountFlow = MutableStateFlow(initial)
        _flow = MutableStateFlow(initial.asLegacyBasicOrNull())
    }

    /**
     * Scheme-aware account observable. New callers (sync, library, AI,
     * catalog) should prefer this over [flow] so BEARER accounts surface a
     * baseUrl. Emits null when no account is configured.
     */
    val accountFlow: StateFlow<AccountCredentials?> = _accountFlow.asStateFlow()

    /**
     * Legacy basic-credentials observable. Emits the BASIC record when one
     * is configured; emits null otherwise (including when a BEARER account
     * is configured). Retained so the existing Settings screen and any
     * unconverted callers compile without churn — new code should use
     * [accountFlow] instead.
     */
    val flow: StateFlow<CalibreCredentials?> = _flow.asStateFlow()

    /** Returns the configured account, regardless of scheme. */
    fun getAccount(): AccountCredentials? = _accountFlow.value

    /**
     * Returns the configured account as the legacy basic shape, or null when
     * either nothing is configured or the configured account is BEARER.
     */
    fun get(): CalibreCredentials? = _flow.value

    /**
     * Save a calibre-web style account. Convenience wrapper preserved for
     * existing callers; equivalent to [saveBasicAccount].
     */
    fun put(creds: CalibreCredentials) {
        saveBasicAccount(creds.baseUrl, creds.username, creds.password)
    }

    /**
     * Save a calibre-web style account (HTTP Basic). Replaces any
     * previously-stored account regardless of scheme. Blank values are
     * rejected at the API boundary.
     *
     * [quireServerUrl] is an optional override that routes sync/library/AI
     * calls to a different host than the OPDS catalog at [baseUrl]. Pass
     * null (or blank) to use [baseUrl] for everything. Tier-1 scope; see
     * `docs/superpowers/split-server-urls.md`.
     */
    fun saveBasicAccount(
        baseUrl: String,
        username: String,
        password: String,
        quireServerUrl: String? = null,
    ) {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(username.isNotBlank()) { "username must not be blank" }
        require(password.isNotBlank()) { "password must not be blank" }
        val account = AccountCredentials.Basic(
            baseUrl = baseUrl.trim().trimEnd('/'),
            username = username,
            password = password,
            quireServerUrl = quireServerUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() },
        )
        persistAccount(account)
    }

    /**
     * Save a `quire_server` / Quire Cloud bearer-token account. The token is
     * stored as-is; callers must pass the raw token (no leading "Bearer ").
     * Blank values and tokens containing whitespace are rejected.
     */
    fun saveBearerAccount(baseUrl: String, email: String, token: String) {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(email.isNotBlank()) { "email must not be blank" }
        require(token.isNotBlank()) { "token must not be blank" }
        require(token.none { it.isWhitespace() }) {
            "token must not contain whitespace (pass the raw token, not \"Bearer <token>\")"
        }
        val account = AccountCredentials.Bearer(
            baseUrl = baseUrl.trim().trimEnd('/'),
            email = email,
            token = token,
        )
        persistAccount(account)
    }

    /** Clear all stored credentials. */
    fun clear() {
        synchronized(mutationLock) {
            val committed = prefs.edit().clear().commit()
            if (!committed) {
                Log.w(TAG, "EncryptedSharedPreferences.clear() commit returned false")
            }
            _accountFlow.value = null
            _flow.value = null
        }
    }

    private fun persistAccount(account: AccountCredentials) {
        synchronized(mutationLock) {
            // One editor transaction writes the new shape and removes any
            // keys from the inverse scheme so we never end up with mixed
            // records (e.g. a stale password key alongside a Bearer token).
            val editor = prefs.edit()
                .putString(KEY_BASE_URL, account.baseUrl)
                .putString(KEY_SCHEME, account.scheme.name)
            when (account) {
                is AccountCredentials.Basic -> {
                    editor
                        .putString(KEY_USER, account.username)
                        .putString(KEY_PASS, account.password)
                        .remove(KEY_EMAIL)
                        .remove(KEY_TOKEN)
                    if (account.quireServerUrl != null) {
                        editor.putString(KEY_QUIRE_SERVER_URL, account.quireServerUrl)
                    } else {
                        editor.remove(KEY_QUIRE_SERVER_URL)
                    }
                }
                is AccountCredentials.Bearer -> {
                    editor
                        .putString(KEY_EMAIL, account.email)
                        .putString(KEY_TOKEN, account.token)
                        .remove(KEY_USER)
                        .remove(KEY_PASS)
                        .remove(KEY_QUIRE_SERVER_URL)
                }
            }
            val committed = editor.commit()
            if (!committed) {
                // Don't update the in-memory flows when persistence failed —
                // a subsequent read after process restart would otherwise
                // diverge from what callers observed in-flow.
                Log.w(TAG, "EncryptedSharedPreferences.put() commit returned false; flow not advanced")
                return@synchronized
            }
            _accountFlow.value = account
            _flow.value = account.asLegacyBasicOrNull()
        }
    }

    /**
     * Read the persisted account. Handles the back-compat case where a
     * pre-A-1 record has no `scheme` key but does carry a username/password.
     * Best-effort backfills the scheme key in that case so the next read
     * doesn't repeat the inference.
     */
    private fun loadAccount(): AccountCredentials? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val schemeRaw = prefs.getString(KEY_SCHEME, null)
        val scheme = when (schemeRaw) {
            null -> {
                // Pre-A-1 record: BASIC iff username+password are present.
                val user = prefs.getString(KEY_USER, null)
                val pass = prefs.getString(KEY_PASS, null)
                if (user == null || pass == null) return null
                runCatching {
                    prefs.edit().putString(KEY_SCHEME, AuthScheme.BASIC.name).commit()
                }
                AuthScheme.BASIC
            }
            else -> runCatching { AuthScheme.valueOf(schemeRaw) }.getOrElse {
                // Unknown scheme value (forward-compat from a future build):
                // fail closed by reporting "no account" rather than guessing.
                Log.w(TAG, "Unknown stored auth scheme '$schemeRaw'; treating as no account")
                return null
            }
        }
        return when (scheme) {
            AuthScheme.BASIC -> {
                val user = prefs.getString(KEY_USER, null) ?: return null
                val pass = prefs.getString(KEY_PASS, null) ?: return null
                val quireServerUrl = prefs.getString(KEY_QUIRE_SERVER_URL, null)
                AccountCredentials.Basic(baseUrl, user, pass, quireServerUrl)
            }
            AuthScheme.BEARER -> {
                val email = prefs.getString(KEY_EMAIL, null) ?: return null
                val token = prefs.getString(KEY_TOKEN, null) ?: return null
                AccountCredentials.Bearer(baseUrl, email, token)
            }
        }
    }

    private fun AccountCredentials?.asLegacyBasicOrNull(): CalibreCredentials? =
        (this as? AccountCredentials.Basic)?.let {
            CalibreCredentials(it.baseUrl, it.username, it.password)
        }

    private companion object {
        const val TAG = "CalibreCredentialStore"
        const val KEY_BASE_URL = "base_url"
        const val KEY_SCHEME = "scheme"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
        const val KEY_EMAIL = "email"
        const val KEY_TOKEN = "token"
        const val KEY_QUIRE_SERVER_URL = "quire_server_url"
    }
}
