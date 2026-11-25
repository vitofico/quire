package io.theficos.ereader.data.opds

import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.SavedCatalog
import io.theficos.ereader.auth.validated
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the device's catalog list lives right now. */
enum class CatalogSyncStatus {
    /** No Quire login (none, or the legacy Calibre-Web sign-in): the list is this device's alone. */
    DEVICE_ONLY,

    /** A Quire login whose server does not sync catalogs. */
    NOT_SUPPORTED,

    /** A Quire login whose list has not been fetched yet, for example while offline. */
    CHECKING,

    /** The list follows the Quire account, except catalogs kept on this device only. */
    SYNCED,
}

sealed interface CatalogResult {
    data object Done : CatalogResult
    data class Failed(val reason: CatalogFailure) : CatalogResult
}

enum class CatalogFailure {
    /** The account already holds 20 catalogs. */
    LIMIT_REACHED,

    /** The account's copy can't be read: enter the address and login again. */
    NEEDS_LOGIN,
    INVALID_NAME,
    INVALID_URL,

    /** This server takes only https catalogs. */
    HTTPS_REQUIRED,

    /** The address carries a login; enter it as username and password instead. */
    CREDENTIALS_IN_URL,

    /** Only one of username and password, or a stored password can't follow the address to another server. */
    LOGIN_INCOMPLETE,

    /** Another device changed the list meanwhile; it has been fetched again. */
    ORDER_CHANGED,

    /** Another device removed this catalog from the account; it is gone from this device too. */
    REMOVED_ELSEWHERE,
    NOT_SUPPORTED,

    /** The Quire session ended. Nothing was changed; the list keeps working until the reader signs in. */
    SIGN_IN_REQUIRED,
    OFFLINE,
    REJECTED,
}

/**
 * Keeps the device's catalog list and the Quire account's in step. Every change to the list goes
 * through here: a catalog on the account changes on the server first, so an edit needs a connection,
 * while reading from the saved list works offline and with an expired session. Catalogs kept on this
 * device only, and every catalog without a Quire login, never leave the device.
 *
 * [onCatalogMerged] runs when a device catalog turns out to be one the account already has, so books
 * downloaded from it can follow the account's id.
 */
class CatalogSyncRepository(
    private val store: CalibreCredentialStore,
    private val client: CatalogSyncClient,
    private val onCatalogMerged: suspend (from: String, into: String) -> Unit,
) {
    private val mutex = Mutex()

    /** The Quire server address that answered that it has no catalog sync. */
    private val unsupportedAt = MutableStateFlow<String?>(null)

    val status: Flow<CatalogSyncStatus> =
        combine(store.accountFlow, store.catalogRevision, unsupportedAt) { account, revision, unsupported ->
            when {
                account !is AccountCredentials.Bearer -> CatalogSyncStatus.DEVICE_ONLY
                unsupported == account.baseUrl -> CatalogSyncStatus.NOT_SUPPORTED
                revision != null -> CatalogSyncStatus.SYNCED
                else -> CatalogSyncStatus.CHECKING
            }
        }

    /**
     * Device catalogs nobody has decided about yet, once the account syncs catalogs: what the
     * sign-in question asks about. Answer it with [uploadPending].
     */
    val pendingUploads: Flow<List<SavedCatalog>> =
        combine(store.catalogs, status) { catalogs, status ->
            if (status != CatalogSyncStatus.SYNCED) emptyList()
            else catalogs.filter { it.awaitingUpload && it.catalog != null }
        }

    /**
     * Fetch the account's list and apply it: on sign-in, on app start and when the catalog view
     * opens. Usually a 304 with no body. Catalogs only this device has stay, after the account's.
     */
    suspend fun pull(): CatalogResult = mutex.withLock { pullLocked() }

    /**
     * Add [candidate], or replace the catalog with its id. A new catalog whose address the list
     * already has updates that one instead, which keeps its place on the account or the device.
     *
     * Only a catalog already on the account, or one the reader chose to put there ([toAccount]),
     * reaches the server, and then nothing changes locally when the server says no. Any other edit
     * stays on this device: a catalog waiting for the sign-in question keeps waiting, so a login
     * nobody agreed to upload never leaves the device. An account catalog that another device
     * removed meanwhile is refused and dropped here too, instead of being saved back.
     */
    suspend fun save(candidate: SavedCatalog, toAccount: Boolean = false): CatalogResult = mutex.withLock {
        val valid = runCatching { candidate.validated() }.getOrNull() ?: return@withLock failed(CatalogFailure.REJECTED)
        val catalog = valid.catalog
        if (valid.pinned || catalog == null) return@withLock failed(CatalogFailure.REJECTED)
        val current = store.catalogs.value
        val twin = if (current.any { it.id == valid.id }) null
        else current.find { !it.pinned && it.catalog?.baseUrl == catalog.baseUrl }
        val entry = if (twin == null) valid else valid.copy(id = twin.id, deviceOnly = twin.deviceOnly)
        val existing = current.find { it.id == entry.id }
        if (existing?.pinned == true) return@withLock failed(CatalogFailure.REJECTED)
        // Moving a catalog off the account is not something the reader can ask for.
        if (existing?.onAccount == true && entry.deviceOnly) return@withLock failed(CatalogFailure.REJECTED)
        val onAccount = existing?.onAccount == true
        if (!onAccount && (!toAccount || twin != null || entry.deviceOnly)) {
            store.saveCatalog(entry.copy(remoteUpdatedAt = null, needsLogin = false, listedHost = null))
            return@withLock CatalogResult.Done
        }
        val owner = syncedOwner()
        if (owner == null) {
            if (existing != null) return@withLock failed(unsynced())
            // Not synced right now: a new catalog waits for the sign-in question.
            store.saveCatalog(entry.copy(remoteUpdatedAt = null, needsLogin = false, listedHost = null))
            return@withLock CatalogResult.Done
        }
        if (onAccount) {
            // Usually a 304. Saving a catalog another device removed would put it back.
            (pullLocked() as? CatalogResult.Failed)?.let { return@withLock it }
            if (store.catalogs.value.none { it.id == entry.id }) return@withLock failed(CatalogFailure.REMOVED_ELSEWHERE)
        }
        remote {
            val saved = client.put(entry.id, catalog.toPut(entry.name))
            store.applyAccountCatalogs(owner, store.catalogRevision.value) { current ->
                val next = entry.copy(name = saved.name, remoteUpdatedAt = saved.updatedAt, needsLogin = false, listedHost = null)
                if (current.any { it.id == entry.id }) current.map { if (it.id == entry.id) next else it } else current + next
            }
        }
    }

    /** Forget a catalog. One on the account is removed from the account, and so from every device. */
    suspend fun remove(id: String): CatalogResult = mutex.withLock {
        val existing = store.catalogs.value.find { it.id == id } ?: return@withLock CatalogResult.Done
        if (existing.pinned) return@withLock failed(CatalogFailure.REJECTED)
        if (!existing.onAccount) {
            store.removeCatalog(id)
            return@withLock CatalogResult.Done
        }
        val owner = syncedOwner() ?: return@withLock failed(unsynced())
        remote {
            client.delete(id)
            store.applyAccountCatalogs(owner, store.catalogRevision.value) { current -> current.filter { it.id != id } }
        }
    }

    /**
     * Put the catalogs in the order of [ids]. The account's catalogs keep that order on every
     * device; when another device changed the list meanwhile it is fetched again and nothing moves.
     */
    suspend fun reorder(ids: List<String>): CatalogResult = mutex.withLock {
        val wereOnAccount = store.catalogs.value.filter { it.onAccount }.map { it.id }.toSet()
        val owner = syncedOwner()
        if (wereOnAccount.isEmpty() || owner == null) {
            if (wereOnAccount.isNotEmpty()) return@withLock failed(unsynced())
            store.reorderCatalogs(ids)
            return@withLock CatalogResult.Done
        }
        (pullLocked() as? CatalogResult.Failed)?.let { return@withLock it }
        val onAccount = store.catalogs.value.filter { it.onAccount }.map { it.id }.toSet()
        if (ids.any { it in wereOnAccount && it !in onAccount }) return@withLock failed(CatalogFailure.REMOVED_ELSEWHERE)
        val result = remote {
            // The answer is the whole list, which may carry another device's edits too.
            apply(owner, client.order(ids.filter { it in onAccount }))
            store.reorderCatalogs(ids)
        }
        if (result == CatalogResult.Failed(CatalogFailure.ORDER_CHANGED)) pullLocked()
        result
    }

    /**
     * The reader's answer to the sign-in question: upload the catalogs in [accepted] and keep the
     * others on this device only. A catalog whose address the account already has is merged into
     * the account's copy, unless its own login differs: then it stays on this device only, so that
     * login is not thrown away. One the account can't take (the limit, or an address the server refuses)
     * stays on this device only. Offline, the declined ones are still marked and the accepted ones
     * wait for the next try.
     */
    suspend fun uploadPending(accepted: Set<String>): CatalogResult = mutex.withLock {
        store.catalogs.value.filter { it.awaitingUpload && it.id !in accepted }
            .forEach { store.saveCatalog(it.copy(deviceOnly = true)) }
        (pullLocked() as? CatalogResult.Failed)?.let { return@withLock it }
        val owner = syncedOwner() ?: return@withLock failed(CatalogFailure.NOT_SUPPORTED)
        var outcome: CatalogResult = CatalogResult.Done
        for (pending in store.catalogs.value.filter { it.awaitingUpload && it.id in accepted }) {
            val catalog = pending.catalog ?: continue
            val twin = store.catalogs.value.find { it.onAccount && it.catalog?.baseUrl == catalog.baseUrl }
            if (twin != null) {
                val accountCopy = twin.catalog
                if (catalog.username != null &&
                    (catalog.username != accountCopy?.username || catalog.password != accountCopy?.password)
                ) {
                    store.saveCatalog(pending.copy(deviceOnly = true))
                    continue
                }
                onCatalogMerged(pending.id, twin.id)
                val wasActive = store.activeCatalog.value?.id == pending.id
                store.removeCatalog(pending.id)
                if (wasActive) store.setActiveCatalog(twin.id)
                continue
            }
            when (val result = remote {
                val saved = client.put(pending.id, catalog.toPut(pending.name))
                store.applyAccountCatalogs(owner, store.catalogRevision.value) { current ->
                    current.map { if (it.id == pending.id) it.copy(name = saved.name, remoteUpdatedAt = saved.updatedAt) else it }
                }
            }) {
                CatalogResult.Done -> Unit
                is CatalogResult.Failed -> when (result.reason) {
                    CatalogFailure.OFFLINE, CatalogFailure.SIGN_IN_REQUIRED, CatalogFailure.NOT_SUPPORTED ->
                        return@withLock result
                    else -> {
                        store.saveCatalog(pending.copy(deviceOnly = true))
                        if (outcome == CatalogResult.Done || result.reason == CatalogFailure.LIMIT_REACHED) outcome = result
                    }
                }
            }
        }
        outcome
    }

    private suspend fun pullLocked(): CatalogResult {
        val owner = store.getAccount() as? AccountCredentials.Bearer ?: return CatalogResult.Done
        val result = remote { client.list(store.catalogRevision.value)?.let { apply(owner, it) } }
        if (result == CatalogResult.Done && unsupportedAt.value == owner.baseUrl) unsupportedAt.value = null
        return result
    }

    /** Make the device's list follow the account's [list]. Catalogs only this device has stay, after the account's. */
    private suspend fun apply(owner: AccountCredentials.Bearer, list: RemoteCatalogList) {
        val known = store.catalogs.value.associateBy { it.id }
        // Fetch every secret before applying anything, so a dropped connection changes nothing.
        val remote = list.catalogs.sortedBy { it.position }.mapNotNull { listed -> resolve(listed, known[listed.id]) }
        val remoteIds = list.catalogs.map { it.id }.toSet()
        store.applyAccountCatalogs(owner, list.revision) { current ->
            remote + current.filter { it.id !in remoteIds && !it.onAccount }
        }
    }

    /** The device's copy of one listed catalog; null when it was deleted while the list was read. */
    private suspend fun resolve(listed: RemoteCatalog, local: SavedCatalog?): SavedCatalog? {
        val base = (local ?: SavedCatalog(listed.id, listed.name, null)).copy(
            name = listed.name,
            deviceOnly = false,
            remoteUpdatedAt = listed.updatedAt,
            listedHost = listed.host,
        )
        if (listed.needsLogin) return base.copy(needsLogin = true)
        if (local?.remoteUpdatedAt == listed.updatedAt && local.catalog != null && !local.needsLogin) {
            return base.copy(needsLogin = false)
        }
        val secret = try {
            client.secret(listed.id)
        } catch (e: CatalogHttpException) {
            when {
                e.code == HTTP_NOT_FOUND && e.detail == "not_found" -> return null
                e.code == HTTP_CONFLICT && e.detail == "needs_login" -> return base.copy(needsLogin = true)
                else -> throw e
            }
        }
        val user = secret.username?.takeIf { it.isNotBlank() }
        val pass = secret.password?.takeIf { it.isNotBlank() }
        val catalog = AccountCredentials.OpdsOnly(
            baseUrl = secret.url,
            username = user?.takeIf { pass != null },
            password = pass?.takeIf { user != null },
            calibre = listed.kind == KIND_CALIBRE,
        )
        return base.copy(catalog = catalog, needsLogin = false)
    }

    /** The Quire login whose list this device holds, or null when the list is not synced right now. */
    private fun syncedOwner(): AccountCredentials.Bearer? {
        val owner = store.getAccount() as? AccountCredentials.Bearer ?: return null
        if (unsupportedAt.value == owner.baseUrl || store.catalogRevision.value == null) return null
        return owner
    }

    /** Why a catalog on the account can't be changed right now. */
    private fun unsynced(): CatalogFailure {
        val account = store.getAccount() as? AccountCredentials.Bearer
        return if (account != null && unsupportedAt.value == account.baseUrl) CatalogFailure.NOT_SUPPORTED
        else CatalogFailure.SIGN_IN_REQUIRED
    }

    /** Run one exchange with the server, mapping its failures. A change of account meanwhile is [CatalogFailure.SIGN_IN_REQUIRED]. */
    private suspend fun remote(block: suspend () -> Unit): CatalogResult {
        val owner = store.getAccount() as? AccountCredentials.Bearer
        return try {
            block()
            val now = store.getAccount() as? AccountCredentials.Bearer
            if (now?.baseUrl != owner?.baseUrl || now?.token != owner?.token) failed(CatalogFailure.SIGN_IN_REQUIRED)
            else CatalogResult.Done
        } catch (e: CatalogHttpException) {
            if (e.code == HTTP_NOT_FOUND && owner != null && e.detail != "not_found") unsupportedAt.value = owner.baseUrl
            failed(e.failure())
        } catch (e: IOException) {
            failed(CatalogFailure.OFFLINE)
        }
    }

    private fun CatalogHttpException.failure(): CatalogFailure = when {
        code == HTTP_UNAUTHORIZED -> CatalogFailure.SIGN_IN_REQUIRED
        code == HTTP_NOT_FOUND -> CatalogFailure.NOT_SUPPORTED
        else -> when (detail) {
            "catalog_limit_reached" -> CatalogFailure.LIMIT_REACHED
            "needs_login" -> CatalogFailure.NEEDS_LOGIN
            "invalid_name" -> CatalogFailure.INVALID_NAME
            "invalid_url" -> CatalogFailure.INVALID_URL
            "https_required" -> CatalogFailure.HTTPS_REQUIRED
            "credentials_in_url" -> CatalogFailure.CREDENTIALS_IN_URL
            "login_incomplete" -> CatalogFailure.LOGIN_INCOMPLETE
            "order_mismatch" -> CatalogFailure.ORDER_CHANGED
            else -> CatalogFailure.REJECTED
        }
    }

    private fun failed(reason: CatalogFailure) = CatalogResult.Failed(reason)

    private fun AccountCredentials.OpdsOnly.toPut(name: String) = CatalogPut(
        name = name,
        kind = if (calibre) KIND_CALIBRE else KIND_OPDS,
        url = baseUrl,
        username = username,
        password = password,
    )

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val KIND_CALIBRE = "calibre"
        const val KIND_OPDS = "opds"
    }
}
