package io.theficos.ereader.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID

/**
 * Encrypted local storage for the Quire login and the device's list of saved catalogs.
 * A legacy record without a scheme is interpreted as a shared Basic account.
 * Writes are serialized with observable state updates; a change to the account and the
 * catalog list it implies is one transaction.
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
    private val _catalogs = MutableStateFlow<List<SavedCatalog>>(emptyList())
    private val _activeCatalog = MutableStateFlow<SavedCatalog?>(null)
    private val _catalogRevision = MutableStateFlow<String?>(null)

    /** What is stored: the list without the legacy sign-in's pinned row, the active id, the list revision. */
    private data class CatalogState(val saved: List<SavedCatalog>, val activeId: String?, val revision: String?)
    private var catalogState = CatalogState(emptyList(), null, null)

    init {
        migrateLegacyCatalog()
        val initial = loadAccount()
        _accountFlow = MutableStateFlow(initial)
        _flow = MutableStateFlow(initial.asLegacyBasicOrNull())
        publishCatalogs(initial, loadCatalogState())
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
    ) = synchronized(mutationLock) {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(username.isNotBlank()) { "username must not be blank" }
        require(password.isNotBlank()) { "password must not be blank" }
        val account = AccountCredentials.Basic(
            baseUrl = baseUrl.trim().trimEnd('/'),
            username = username,
            password = password,
            quireServerUrl = quireServerUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() },
        )
        persistAccount(account, catalogs = catalogsFollowing(account))
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
            accountId = accountId?.takeIf { it.isNotBlank() },
        )
        val previous = getAccount() as? AccountCredentials.Bearer
        val pending = if (previous != null && previous.baseUrl == account.baseUrl &&
            (previous.subject == account.subject || previousHighlightScope != null)
        ) previous.previousHighlightScopes else emptyList()
        val next = account.copy(previousHighlightScopes = (pending + listOfNotNull(previousHighlightScope)).distinct())
        persistAccount(next, catalogs = catalogsFollowing(next))
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
     * Every catalog on this device, in chip order. A legacy Calibre-Web sign-in's own catalog
     * comes first, marked [SavedCatalog.pinned].
     */
    val catalogs: StateFlow<List<SavedCatalog>> = _catalogs.asStateFlow()

    /** The catalog the catalog view browses: the last one chosen, else the first. Null when there is none. */
    val activeCatalog: StateFlow<SavedCatalog?> = _activeCatalog.asStateFlow()

    /**
     * Revision of the Quire account's catalog list this device last applied. Null while the list
     * is not synced: no Quire login, a server without catalog sync, or not fetched yet.
     */
    val catalogRevision: StateFlow<String?> = _catalogRevision.asStateFlow()

    /** Address and login of the [activeCatalog], for browsing and downloading. */
    fun catalogAccount(): AccountCredentials.OpdsOnly? = _activeCatalog.value?.catalog

    /** The catalogs and the account, read together. Null when no catalog has an address. */
    fun catalogSession(): CatalogSession? = synchronized(mutationLock) {
        val catalogs = _catalogs.value.mapNotNull { it.catalog }
        if (catalogs.isEmpty()) null else CatalogSession(catalogs, catalogAccount(), getAccount())
    }

    /** Remember [id] as the catalog to browse on this device. Unknown ids are ignored. */
    fun setActiveCatalog(id: String) {
        synchronized(mutationLock) {
            if (_catalogs.value.none { it.id == id }) return
            persistCatalogs(catalogState.copy(activeId = id))
        }
    }

    /**
     * Add [entry] at the end, or replace the catalog with its id in place. Local only: changes that
     * belong on the Quire account go through the catalog sync repository, which calls this once the
     * server agreed. The pinned sign-in row changes only with the sign-in itself.
     */
    fun saveCatalog(entry: SavedCatalog) {
        synchronized(mutationLock) {
            require(!entry.pinned && _catalogs.value.none { it.id == entry.id && it.pinned }) {
                "the sign-in's own catalog changes only with the sign-in"
            }
            val validated = entry.validated()
            val saved = catalogState.saved
            val next = if (saved.any { it.id == entry.id }) {
                saved.map { if (it.id == entry.id) validated else it }
            } else {
                saved + validated
            }
            persistCatalogs(catalogState.copy(saved = next))
        }
    }

    /** Forget a catalog on this device. The pinned sign-in row is not removable. */
    fun removeCatalog(id: String) {
        synchronized(mutationLock) {
            if (catalogState.saved.none { it.id == id }) return
            persistCatalogs(catalogState.copy(saved = catalogState.saved.filter { it.id != id }))
        }
    }

    /** Order the catalogs as [ids]. Catalogs not named keep their order, after the named ones. */
    fun reorderCatalogs(ids: List<String>) {
        synchronized(mutationLock) {
            val rank = ids.withIndex().associate { (index, id) -> id to index }
            persistCatalogs(catalogState.copy(saved = catalogState.saved.sortedBy { rank[it.id] ?: Int.MAX_VALUE }))
        }
    }

    /**
     * Apply [change], computed from the Quire account's list, and record that list's [revision].
     * Returns false and writes nothing when [owner]'s session is no longer the stored one: the reader
     * signed out or switched account while the list was in flight.
     */
    fun applyAccountCatalogs(
        owner: AccountCredentials.Bearer,
        revision: String?,
        change: (List<SavedCatalog>) -> List<SavedCatalog>,
    ): Boolean = synchronized(mutationLock) {
        val current = getAccount() as? AccountCredentials.Bearer
        if (current?.baseUrl != owner.baseUrl || current.token != owner.token) return false
        persistCatalogs(catalogState.copy(saved = change(catalogState.saved), revision = revision))
        true
    }

    /**
     * Sign out of the Quire account or the legacy sign-in. With [keepCatalogs] every catalog stays
     * on this device and keeps working for reading, now held by the device alone; without it the
     * device forgets them all. Catalogs on the Quire account stay there either way. A session that
     * merely expired is not a sign-out and never comes through here.
     */
    fun signOut(keepCatalogs: Boolean) {
        synchronized(mutationLock) {
            val state = if (keepCatalogs) {
                CatalogState(
                    saved = listOfNotNull(getAccount().pinnedCatalog()?.copy(pinned = false)) +
                        catalogState.saved.mapNotNull { it.detached() },
                    activeId = catalogState.activeId,
                    revision = null,
                )
            } else {
                CatalogState(emptyList(), null, null)
            }
            val editor = prefs.edit()
            ACCOUNT_KEYS.forEach { editor.remove(it) }
            writeCatalogState(editor, state)
            if (!editor.commit()) {
                Log.w(TAG, "EncryptedSharedPreferences sign-out commit returned false; flow not advanced")
                return
            }
            _accountFlow.value = null
            _flow.value = null
            _needsReauth.value = false
            publishCatalogs(null, state)
        }
    }

    /**
     * The catalog this device was signed in as before saved catalogs, kept until its highlights and
     * notes have moved to the device. Only the address and username: enough to find what was keyed
     * by it. Null once [forgetLegacyCatalogOwner] has run, and on every device that never had one.
     */
    fun legacyCatalogOwner(): AccountCredentials.OpdsOnly? =
        prefs.getString(KEY_LEGACY_OWNER_URL, null)?.let {
            AccountCredentials.OpdsOnly(it, username = prefs.getString(KEY_LEGACY_OWNER_USER, null))
        }

    fun forgetLegacyCatalogOwner() {
        synchronized(mutationLock) {
            prefs.edit().remove(KEY_LEGACY_OWNER_URL).remove(KEY_LEGACY_OWNER_USER).commit()
        }
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

    /** Clear all stored credentials and catalogs. */
    fun clear() {
        synchronized(mutationLock) {
            val committed = prefs.edit().clear().commit()
            if (!committed) {
                Log.w(TAG, "EncryptedSharedPreferences.clear() commit returned false")
            }
            _accountFlow.value = null
            _flow.value = null
            _needsReauth.value = false
            publishCatalogs(null, CatalogState(emptyList(), null, null))
        }
    }

    private fun persistAccount(
        account: AccountCredentials,
        preserveReauth: Boolean = false,
        catalogs: CatalogState = catalogState,
    ) {
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
                is AccountCredentials.OpdsOnly ->
                    throw IllegalArgumentException("a catalog is saved with saveCatalog, not as the account")
            }
            val bearer = account as? AccountCredentials.Bearer
            val accountId = bearer?.accountId
            val highlightScopes = bearer?.previousHighlightScopes.orEmpty()
            editor.apply {
                if (highlightScopes.isEmpty()) remove(KEY_PREVIOUS_HIGHLIGHT_SCOPES)
                else putString(KEY_PREVIOUS_HIGHLIGHT_SCOPES, highlightScopes.joinToString(":"))
                if (accountId == null) remove(KEY_ACCOUNT_ID) else putString(KEY_ACCOUNT_ID, accountId)
            }
            writeCatalogState(editor, catalogs)
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
            publishCatalogs(account, catalogs)
        }
    }

    /**
     * The catalog list once [next] replaces the current account. The old legacy sign-in's own
     * catalog stays as an ordinary catalog with the same id, so books keep their catalog. Catalogs
     * that belonged to another Quire account stay on the device but no longer count as synced.
     */
    private fun catalogsFollowing(next: AccountCredentials): CatalogState {
        val previous = getAccount()
        val nextPinned = next.pinnedCatalog()?.id
        val oldPinned = previous.pinnedCatalog()?.takeIf { it.id != nextPinned }?.copy(pinned = false)
        var saved = (listOfNotNull(oldPinned) + catalogState.saved).filter { it.id != nextPinned }
        var revision = catalogState.revision
        if (previous is AccountCredentials.Bearer &&
            !(next is AccountCredentials.Bearer && sameOwner(previous, next))
        ) {
            saved = saved.mapNotNull { it.detached() }
            revision = null
        }
        return catalogState.copy(saved = saved, revision = revision)
    }

    private fun sameOwner(a: AccountCredentials.Bearer, b: AccountCredentials.Bearer): Boolean =
        a.baseUrl == b.baseUrl && (a.subject == b.subject || a.email.equals(b.email, ignoreCase = true))

    private fun persistCatalogs(state: CatalogState) {
        val editor = prefs.edit()
        writeCatalogState(editor, state)
        if (!editor.commit()) {
            Log.w(TAG, "EncryptedSharedPreferences catalog commit returned false; flow not advanced")
            return
        }
        publishCatalogs(getAccount(), state)
    }

    private fun writeCatalogState(editor: SharedPreferences.Editor, state: CatalogState) {
        editor.putString(KEY_CATALOG_LIST, catalogsToJson(state.saved))
        if (state.activeId == null) editor.remove(KEY_ACTIVE_CATALOG) else editor.putString(KEY_ACTIVE_CATALOG, state.activeId)
        if (state.revision == null) editor.remove(KEY_CATALOG_REVISION) else editor.putString(KEY_CATALOG_REVISION, state.revision)
    }

    private fun publishCatalogs(account: AccountCredentials?, state: CatalogState) {
        catalogState = state
        val all = listOfNotNull(account.pinnedCatalog()) + state.saved
        _catalogs.value = all
        _activeCatalog.value = all.find { it.id == state.activeId } ?: all.firstOrNull()
        _catalogRevision.value = state.revision
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
                val accountId = prefs.getString(KEY_ACCOUNT_ID, null)
                val highlightScopes = prefs.getString(KEY_PREVIOUS_HIGHLIGHT_SCOPES, null)?.split(':').orEmpty()
                AccountCredentials.Bearer(baseUrl, email, token, expiresAt, accountId, highlightScopes)
            }
            // A catalog-only device has no account since saved catalogs: its catalog moved into the list.
            AuthScheme.OPDS -> null
        }
    }

    /**
     * Move the single catalog of an older version into the list, in one commit: the catalog of a
     * Quire login (`catalog_*` keys) or a catalog-only device (an OPDS "account"). It becomes the
     * active entry with a fresh id; a list entry with the same address takes the newer login instead,
     * so running this again (or after a downgrade wrote the old keys back) duplicates nothing.
     * Nothing is uploaded here: the sign-in question offers it to the Quire account.
     */
    private fun migrateLegacyCatalog() {
        val catalogOnly = prefs.getString(KEY_SCHEME, null) == AuthScheme.OPDS.name
        val legacy = if (catalogOnly) {
            prefs.getString(KEY_BASE_URL, null)?.let { readLegacyCatalog(it, KEY_USER, KEY_PASS) }
        } else {
            prefs.getString(KEY_CATALOG_URL, null)?.let { readLegacyCatalog(it, KEY_CATALOG_USER, KEY_CATALOG_PASS) }
        }
        if (legacy == null && !catalogOnly && LEGACY_CATALOG_KEYS.none(prefs::contains)) return
        val editor = prefs.edit()
        LEGACY_CATALOG_KEYS.forEach { editor.remove(it) }
        if (catalogOnly) ACCOUNT_KEYS.forEach { editor.remove(it) }
        if (legacy != null && legacy.baseUrl.isNotBlank()) {
            val saved = readCatalogList()
            val existing = saved.find { it.catalog?.baseUrl == legacy.baseUrl }
            val entry = existing?.copy(catalog = legacy)
                ?: SavedCatalog(SavedCatalog.newId(), defaultName(legacy.baseUrl), legacy)
            val next = if (existing != null) saved.map { if (it.id == entry.id) entry else it } else saved + entry
            editor.putString(KEY_CATALOG_LIST, catalogsToJson(next)).putString(KEY_ACTIVE_CATALOG, entry.id)
            if (catalogOnly) {
                // Its highlights and notes were keyed by the catalog; they move once the app has started.
                editor.putString(KEY_LEGACY_OWNER_URL, legacy.baseUrl)
                if (legacy.username == null) editor.remove(KEY_LEGACY_OWNER_USER)
                else editor.putString(KEY_LEGACY_OWNER_USER, legacy.username)
            }
        }
        // A failed commit still leaves the change in memory for this run, and the old keys on disk
        // let the next start do it again.
        if (!editor.commit()) Log.w(TAG, "Catalog list migration commit returned false; retried on next start")
    }

    private fun readLegacyCatalog(baseUrl: String, userKey: String, passKey: String): AccountCredentials.OpdsOnly {
        val user = prefs.getString(userKey, null)
        val pass = prefs.getString(passKey, null)
        val calibre = prefs.getBoolean(KEY_CATALOG_CALIBRE, false)
        return if (user != null && pass != null) {
            AccountCredentials.OpdsOnly(baseUrl, user, pass, calibre)
        } else {
            AccountCredentials.OpdsOnly(baseUrl, calibre = calibre)
        }
    }

    private fun loadCatalogState() = CatalogState(
        saved = readCatalogList(),
        activeId = prefs.getString(KEY_ACTIVE_CATALOG, null),
        revision = prefs.getString(KEY_CATALOG_REVISION, null),
    )

    private fun readCatalogList(): List<SavedCatalog> {
        val raw = prefs.getString(KEY_CATALOG_LIST, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { catalogFromJson(array.getJSONObject(it)) }
        } catch (e: JSONException) {
            // Never log the value: it holds catalog addresses and passwords.
            Log.w(TAG, "Stored catalog list can't be read; starting with an empty list")
            emptyList()
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
        const val KEY_CATALOG_LIST = "catalog_list"
        const val KEY_ACTIVE_CATALOG = "catalog_active"
        const val KEY_CATALOG_REVISION = "catalog_revision"
        const val KEY_LEGACY_OWNER_URL = "legacy_catalog_owner_url"
        const val KEY_LEGACY_OWNER_USER = "legacy_catalog_owner_username"

        /** The single catalog of versions before saved catalogs. Only migration reads them. */
        val LEGACY_CATALOG_KEYS = listOf(KEY_CATALOG_URL, KEY_CATALOG_USER, KEY_CATALOG_PASS, KEY_CATALOG_CALIBRE)

        val ACCOUNT_KEYS = listOf(
            KEY_BASE_URL, KEY_SCHEME, KEY_USER, KEY_PASS, KEY_EMAIL, KEY_TOKEN, KEY_EXPIRES_AT,
            KEY_QUIRE_SERVER_URL, KEY_PREVIOUS_HIGHLIGHT_SCOPES, KEY_ACCOUNT_ID,
        )

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

private fun defaultName(url: String): String = SavedCatalog.defaultName(url).ifBlank { "Catalog" }

/** The legacy Calibre-Web sign-in's own catalog, with an id stable for its server. */
private fun AccountCredentials?.pinnedCatalog(): SavedCatalog? {
    val basic = this as? AccountCredentials.Basic ?: return null
    return SavedCatalog(
        id = UUID.nameUUIDFromBytes("calibre-web:${basic.baseUrl}".toByteArray()).toString(),
        name = defaultName(basic.baseUrl),
        catalog = AccountCredentials.OpdsOnly("${basic.baseUrl}/opds", basic.username, basic.password, calibre = true),
        pinned = true,
    )
}

/** The device's own copy once the catalog no longer belongs to a Quire account; null when it has no address. */
private fun SavedCatalog.detached(): SavedCatalog? =
    catalog?.let { copy(deviceOnly = false, remoteUpdatedAt = null, needsLogin = false, listedHost = null) }

private fun catalogsToJson(catalogs: List<SavedCatalog>): String = JSONArray().apply {
    catalogs.forEach { entry ->
        put(JSONObject().apply {
            put("id", entry.id)
            put("name", entry.name)
            entry.catalog?.let {
                put("url", it.baseUrl)
                it.username?.let { user -> put("username", user) }
                it.password?.let { pass -> put("password", pass) }
                put("calibre", it.calibre)
            }
            put("device_only", entry.deviceOnly)
            entry.remoteUpdatedAt?.let { put("remote_updated_at", it) }
            put("needs_login", entry.needsLogin)
            entry.listedHost?.let { put("listed_host", it) }
        })
    }
}.toString()

private fun catalogFromJson(json: JSONObject): SavedCatalog {
    fun string(key: String): String? = if (json.has(key) && !json.isNull(key)) json.getString(key) else null
    val url = string("url")
    val user = string("username")
    val pass = string("password")
    return SavedCatalog(
        id = json.getString("id"),
        name = json.getString("name"),
        catalog = url?.let {
            if (user != null && pass != null) AccountCredentials.OpdsOnly(it, user, pass, json.optBoolean("calibre"))
            else AccountCredentials.OpdsOnly(it, calibre = json.optBoolean("calibre"))
        },
        deviceOnly = json.optBoolean("device_only"),
        remoteUpdatedAt = string("remote_updated_at"),
        needsLogin = json.optBoolean("needs_login"),
        listedHost = string("listed_host"),
    )
}
