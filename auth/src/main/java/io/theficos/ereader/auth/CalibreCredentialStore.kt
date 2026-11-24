package io.theficos.ereader.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Encrypted local storage for the Quire login and the catalog connection.
 * A legacy record without a scheme is interpreted as a shared Basic account.
 * Writes are serialized with observable state updates and persist both connections together.
 */
class CalibreCredentialStore(context: Context) {

    private val prefs = openPrefs(context)

    /**
     * Single lock guarding every prefs mutation. Without this, two concurrent
     * `saveBearerAccount` + `clear` callers can interleave the editor
     * transaction with the flow update and leave the store inconsistent.
     */
    private val mutationLock = Any()

    private val _accountFlow: MutableStateFlow<AccountCredentials?>
    private val _flow: MutableStateFlow<CalibreCredentials?>
    private val _needsReauth: MutableStateFlow<Boolean>

    init {
        val initial = loadAccount()
        _accountFlow = MutableStateFlow(initial)
        _flow = MutableStateFlow(initial.asLegacyBasicOrNull())
        // A stored Bearer session whose expiry has already passed on cold
        // launch starts in the re-auth state, so the UI can route the user
        // straight to sign-in instead of letting the first request 401.
        _needsReauth = MutableStateFlow(
            (initial as? AccountCredentials.Bearer)?.isExpiredAt(System.currentTimeMillis()) == true
        )
    }

    /**
     * Re-auth signal for the configured Bearer (NativeAuth) account.
     *
     * Emits `true` when the current session can no longer authenticate and
     * the user must sign in again — either because its `expires_at` has
     * passed ([checkSessionExpiry]) or because a request came back `401`
     * ([notifyUnauthorized]). NativeAuth has no refresh endpoint, so there is
     * nothing to silently retry; the only recovery is a fresh interactive
     * login, which the onboarding flow drives.
     *
     * This is a read-only *signal*: it never mutates or clears the stored
     * credential. Saving a new account (a successful re-login) or clearing
     * the store resets it to `false`. Basic accounts never set it — a 401
     * there is a wrong-password condition handled by the onboarding verifier.
     */
    val needsReauth: StateFlow<Boolean> = _needsReauth.asStateFlow()

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
     *
     * [expiresAtEpochMs] is the session expiry from the login response's
     * `expires_at`, in Unix epoch milliseconds; pass null when unknown (the
     * session is then treated as non-expiring locally — see
     * [AccountCredentials.Bearer.expiresAtEpochMs]). A successful save clears
     * any pending [needsReauth] signal, since persisting a token is exactly
     * what a re-login does.
     */
    fun saveBearerAccount(
        baseUrl: String,
        email: String,
        token: String,
        expiresAtEpochMs: Long? = null,
        accountId: String? = null,
        previousHighlightScope: String? = null,
    ) = synchronized(mutationLock) {
        saveBearerAccount(
            baseUrl, email, token, expiresAtEpochMs, getAccount().independentCatalog(), accountId, previousHighlightScope,
        )
    }

    fun saveBearerAccount(
        baseUrl: String,
        email: String,
        token: String,
        expiresAtEpochMs: Long? = null,
        catalog: AccountCredentials.OpdsOnly?,
        accountId: String? = null,
        previousHighlightScope: String? = null,
    ) = synchronized(mutationLock) {
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
            expiresAtEpochMs = expiresAtEpochMs,
            catalog = catalog?.validatedCatalog(),
            accountId = accountId?.takeIf { it.isNotBlank() },
        )
        val previous = getAccount() as? AccountCredentials.Bearer
        val pending = if (previous != null && previous.baseUrl == account.baseUrl &&
            (previous.subject == account.subject || previousHighlightScope != null)
        ) previous.previousHighlightScopes else emptyList()
        persistAccount(account.copy(previousHighlightScopes = (pending + listOfNotNull(previousHighlightScope)).distinct()))
    }

    /**
     * The same server under another address: keep the session and queue the highlights
     * owned under the old address for transfer. False when the account changed meanwhile.
     */
    fun moveQuireServer(account: AccountCredentials.Bearer, baseUrl: String, highlightScope: String): Boolean =
        synchronized(mutationLock) {
            if (getAccount() != account) return false
            persistAccount(
                account.copy(
                    baseUrl = baseUrl.trim().trimEnd('/'),
                    previousHighlightScopes = (account.previousHighlightScopes + highlightScope).distinct(),
                ),
                preserveReauth = true,
            )
            true
        }

    /** Mark already transferred scopes so a restarted app only retries pending work. */
    fun completeHighlightMigration(account: AccountCredentials.Bearer, scopes: List<String>) {
        synchronized(mutationLock) {
            val current = getAccount() as? AccountCredentials.Bearer ?: return
            if (current.baseUrl != account.baseUrl || current.subject != account.subject) return
            val remaining = current.previousHighlightScopes - scopes.toSet()
            if (remaining != current.previousHighlightScopes) {
                persistAccount(current.copy(previousHighlightScopes = remaining), preserveReauth = true)
            }
        }
    }

    /**
     * Save a plain OPDS catalog account (issue #101).
     *
     * [catalogUrl] is the COMPLETE catalog URL and is stored verbatim: no
     * trailing-slash trimming and no query stripping, because unlike a server
     * root every character of it is load-bearing. Callers should pass the
     * already-normalized form from `normalizeCatalogUrl`.
     *
     * [username] and [password] are both null for an anonymous or URL-authed
     * catalog, and both non-null for one behind HTTP Basic. Supplying exactly
     * one is rejected: it would silently produce an account that sends no
     * `Authorization` header and then fails with an unexplained 401.
     */
    fun saveOpdsAccount(
        catalogUrl: String,
        username: String? = null,
        password: String? = null,
        calibre: Boolean = false,
    ) {
        persistAccount(AccountCredentials.OpdsOnly(catalogUrl, username, password, calibre).validatedCatalog())
    }

    /** Replace the independent catalog while keeping the current Quire session. */
    fun setCatalog(catalog: AccountCredentials.OpdsOnly?) {
        synchronized(mutationLock) {
            val validated = catalog?.validatedCatalog()
            val account = getAccount()
            when {
                account is AccountCredentials.Bearer ->
                    persistAccount(account.copy(catalog = validated), preserveReauth = true)
                validated != null -> persistAccount(validated)
                else -> clear()
            }
        }
    }

    /** Disconnect Quire services while retaining the independent catalog. */
    fun disconnectQuire() {
        synchronized(mutationLock) {
            val catalog = getAccount().independentCatalog()
            if (catalog == null) clear() else persistAccount(catalog)
        }
    }

    private fun AccountCredentials.OpdsOnly.validatedCatalog(): AccountCredentials.OpdsOnly {
        require(baseUrl.isNotBlank()) { "catalogUrl must not be blank" }
        val user = username?.takeIf { it.isNotBlank() }
        val pass = password?.takeIf { it.isNotBlank() }
        require((user == null) == (pass == null)) {
            "username and password must both be set or both be absent"
        }
        return copy(baseUrl = baseUrl.trim(), username = user, password = pass)
    }

    /**
     * Record that an authenticated request to the configured account's origin
     * came back `401`. Flips [needsReauth] to `true` **only** when the current
     * account is Bearer (NativeAuth) — there is no token to refresh, so the
     * user must sign in again. Never mutates or clears the stored credential
     * (the network layer must not silently swap or drop credentials); it only
     * raises the signal the UI observes. No-op for Basic accounts and when no
     * account is configured.
     */
    fun notifyUnauthorized(rejectedSession: AccountCredentials.Bearer? = null) {
        synchronized(mutationLock) {
            val current = getAccount() as? AccountCredentials.Bearer ?: return
            if (rejectedSession == null || current.baseUrl == rejectedSession.baseUrl && current.token == rejectedSession.token) {
                _needsReauth.value = true
            }
        }
    }

    /**
     * Proactively evaluate the configured Bearer session's expiry against
     * [nowEpochMs] and raise [needsReauth] if it has lapsed. Callers (e.g. the
     * UI on resume / cold launch) invoke this so an already-expired session
     * routes to sign-in without first firing a doomed network request.
     * Returns the resulting [needsReauth] value. No-op effect for non-Bearer
     * accounts or sessions with unknown expiry.
     */
    fun checkSessionExpiry(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(mutationLock) {
            val account = _accountFlow.value
            if (account is AccountCredentials.Bearer && account.isExpiredAt(nowEpochMs)) {
                _needsReauth.value = true
            }
            return _needsReauth.value
        }
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
            _needsReauth.value = false
        }
    }

    private fun persistAccount(account: AccountCredentials, preserveReauth: Boolean = false) {
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
                        .remove(KEY_EXPIRES_AT)
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
                    if (account.expiresAtEpochMs != null) {
                        editor.putLong(KEY_EXPIRES_AT, account.expiresAtEpochMs)
                    } else {
                        editor.remove(KEY_EXPIRES_AT)
                    }
                }
                is AccountCredentials.OpdsOnly -> {
                    editor
                        .remove(KEY_EMAIL)
                        .remove(KEY_TOKEN)
                        .remove(KEY_EXPIRES_AT)
                        .remove(KEY_QUIRE_SERVER_URL)
                    if (account.username != null && account.password != null) {
                        editor
                            .putString(KEY_USER, account.username)
                            .putString(KEY_PASS, account.password)
                    } else {
                        editor.remove(KEY_USER).remove(KEY_PASS)
                    }
                }
            }
            val catalog = (account as? AccountCredentials.Bearer)?.catalog
            val bearer = account as? AccountCredentials.Bearer
            val accountId = bearer?.accountId
            val highlightScopes = bearer?.previousHighlightScopes.orEmpty()
            editor.apply {
                if (highlightScopes.isEmpty()) remove(KEY_PREVIOUS_HIGHLIGHT_SCOPES)
                else putString(KEY_PREVIOUS_HIGHLIGHT_SCOPES, highlightScopes.joinToString(":"))
                if (accountId == null) remove(KEY_ACCOUNT_ID) else putString(KEY_ACCOUNT_ID, accountId)
                if (catalog == null) remove(KEY_CATALOG_URL) else putString(KEY_CATALOG_URL, catalog.baseUrl)
                if (catalog?.username == null) remove(KEY_CATALOG_USER) else putString(KEY_CATALOG_USER, catalog.username)
                if (catalog?.password == null) remove(KEY_CATALOG_PASS) else putString(KEY_CATALOG_PASS, catalog.password)
                putBoolean(KEY_CATALOG_CALIBRE, (catalog ?: account as? AccountCredentials.OpdsOnly)?.calibre == true)
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
            if (!preserveReauth) _needsReauth.value = false
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
                // Absent key → unknown expiry (legacy record or unparseable
                // login response). Stored as a plain Long when known.
                val expiresAt = if (prefs.contains(KEY_EXPIRES_AT)) {
                    prefs.getLong(KEY_EXPIRES_AT, 0L)
                } else {
                    null
                }
                val catalog = prefs.getString(KEY_CATALOG_URL, null)?.let { catalogUrl ->
                    readCatalog(catalogUrl, KEY_CATALOG_USER, KEY_CATALOG_PASS)
                }
                val accountId = prefs.getString(KEY_ACCOUNT_ID, null)
                val highlightScopes = prefs.getString(KEY_PREVIOUS_HIGHLIGHT_SCOPES, null)?.split(':').orEmpty()
                AccountCredentials.Bearer(baseUrl, email, token, expiresAt, catalog, accountId, highlightScopes)
            }
            AuthScheme.OPDS -> readCatalog(baseUrl, KEY_USER, KEY_PASS)
        }
    }

    private fun readCatalog(baseUrl: String, userKey: String, passKey: String): AccountCredentials.OpdsOnly {
        val user = prefs.getString(userKey, null)
        val pass = prefs.getString(passKey, null)
        val calibre = prefs.getBoolean(KEY_CATALOG_CALIBRE, false)
        return if (user != null && pass != null) {
            AccountCredentials.OpdsOnly(baseUrl, user, pass, calibre)
        } else {
            AccountCredentials.OpdsOnly(baseUrl, calibre = calibre)
        }
    }

    private fun AccountCredentials?.asLegacyBasicOrNull(): CalibreCredentials? =
        (this as? AccountCredentials.Basic)?.let {
            CalibreCredentials(it.baseUrl, it.username, it.password)
        }

    private companion object {
        const val TAG = "CalibreCredentialStore"
        const val PREFS_FILE = "calibre_creds"
        const val KEY_BASE_URL = "base_url"
        const val KEY_SCHEME = "scheme"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
        const val KEY_EMAIL = "email"
        const val KEY_TOKEN = "token"
        const val KEY_EXPIRES_AT = "expires_at_epoch_ms"
        const val KEY_QUIRE_SERVER_URL = "quire_server_url"
        const val KEY_PREVIOUS_HIGHLIGHT_SCOPES = "previous_highlight_scopes"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_CATALOG_URL = "catalog_url"
        const val KEY_CATALOG_USER = "catalog_username"
        const val KEY_CATALOG_PASS = "catalog_password"
        const val KEY_CATALOG_CALIBRE = "catalog_calibre"

        /**
         * Opens the encrypted prefs, starting them over empty when they can't be decrypted. That
         * happens when a backup restore or a device transfer brings the file back without the
         * Keystore key that encrypted it, since Keystore keys never leave the device. The user is
         * then signed out and connects again, instead of the app crashing on every launch.
         */
        fun openPrefs(context: Context): SharedPreferences {
            fun open() = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return try {
                open()
            } catch (e: Exception) {
                if (e !is GeneralSecurityException && e !is IOException) throw e
                Log.w(TAG, "Stored credentials can't be decrypted; starting over signed out", e)
                context.deleteSharedPreferences(PREFS_FILE)
                open()
            }
        }
    }
}
