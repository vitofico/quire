package io.theficos.ereader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.SavedCatalog
import io.theficos.ereader.data.opds.CatalogFailure
import io.theficos.ereader.data.opds.CatalogResult
import io.theficos.ereader.data.opds.CatalogSyncRepository
import io.theficos.ereader.data.opds.CatalogSyncStatus
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.catalog.OpenCatalog
import io.theficos.ereader.ui.onboarding.CatalogCheck
import io.theficos.ereader.ui.onboarding.catalogFailureMessage
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The catalog form in Settings, for a new catalog ([isNew]) or the saved one with [id]. The
 * address and login are only ever shown inside this form, never in the list.
 */
data class CatalogForm(
    val id: String,
    val isNew: Boolean,
    val address: String = "",
    val username: String = "",
    val password: String = "",
    val name: String = "",
    val busy: Boolean = false,
    val error: UiText? = null,
    /** The Quire account is full: the form offers keeping this catalog on this device only. */
    val offerDeviceOnly: Boolean = false,
) {
    override fun toString(): String =
        "CatalogForm(id=$id, isNew=$isNew, address=***, username=$username, password=***, name=$name, busy=$busy)"
}

/** An open catalog being added ([busy]) or that could not be, and why. */
data class OpenCatalogAdding(val id: String, val busy: Boolean, val error: UiText? = null)

/**
 * The catalog list in Settings and the sign-in question about uploading it. Every change goes
 * through [CatalogSyncRepository], which keeps the Quire account in step where there is one.
 */
class CatalogsViewModel(
    private val store: CalibreCredentialStore,
    private val repository: CatalogSyncRepository,
    private val check: CatalogCheck = CatalogCheck(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** The free catalogs offered for adding with one tap. */
    val openCatalogs: List<OpenCatalog> = emptyList(),
) : ViewModel() {
    val catalogs: StateFlow<List<SavedCatalog>> = store.catalogs

    private val _openAdding = MutableStateFlow<OpenCatalogAdding?>(null)
    val openAdding: StateFlow<OpenCatalogAdding?> = _openAdding.asStateFlow()

    val status: StateFlow<CatalogSyncStatus> =
        repository.status.stateIn(viewModelScope, SharingStarted.Eagerly, CatalogSyncStatus.DEVICE_ONLY)

    private val _form = MutableStateFlow<CatalogForm?>(null)
    val form: StateFlow<CatalogForm?> = _form.asStateFlow()

    private val _message = MutableStateFlow<UiText?>(null)

    /** Why the last reorder or upload did not go through; null once one does. */
    val message: StateFlow<UiText?> = _message.asStateFlow()

    private val answered = MutableStateFlow(false)

    /**
     * The catalogs the sign-in question asks about. Empty once the reader answered or put it off:
     * then it is asked again on the next launch, for whatever is still undecided.
     */
    val uploadQuestion: StateFlow<List<SavedCatalog>> =
        combine(repository.pendingUploads, answered) { pending, done -> if (done) emptyList() else pending }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var formJob: Job? = null

    fun add() {
        _form.value = CatalogForm(id = SavedCatalog.newId(), isNew = true)
    }

    /** Open the form for a saved catalog. The pinned sign-in row has its own form. */
    fun edit(id: String) {
        val entry = store.catalogs.value.find { it.id == id && !it.pinned } ?: return
        val catalog = entry.catalog
        _form.value = CatalogForm(
            id = id,
            isNew = false,
            address = catalog?.baseUrl.orEmpty(),
            username = catalog?.username.orEmpty(),
            password = catalog?.password.orEmpty(),
            name = entry.name,
            error = if (entry.needsLogin) uiText(R.string.catalogs_needs_login) else null,
        )
    }

    fun onFormChange(next: CatalogForm) {
        if (_form.value?.busy != false) return
        _form.value = next.copy(error = null, offerDeviceOnly = false)
    }

    fun dismissForm() {
        formJob?.cancel()
        _form.value = null
    }

    /**
     * Test the connection when the address or login changed, then save. [deviceOnly] null keeps the
     * catalog where it is (a new one goes to the Quire account when there is one, and one waiting
     * for the sign-in question keeps waiting); true keeps it on this device only; false is "Save to
     * account".
     */
    fun saveForm(deviceOnly: Boolean? = null) {
        val form = _form.value?.takeUnless { it.busy } ?: return
        val existing = store.catalogs.value.find { it.id == form.id }
        if (!form.isNew && existing == null) {
            // Removed meanwhile, for example from another device.
            _form.value = null
            return
        }
        val address = form.address.trim()
        if (address.isEmpty()) return
        val user = form.username.trim().takeIf { it.isNotEmpty() }
        val pass = form.password.takeIf { it.isNotEmpty() }
        if ((user == null) != (pass == null)) return fail(form, CatalogFailure.LOGIN_INCOMPLETE)
        val name = form.name.trim().ifEmpty { SavedCatalog.defaultName(address) }
        if (name.isEmpty() || name.length > SavedCatalog.MAX_NAME_LENGTH) return fail(form, CatalogFailure.INVALID_NAME)
        _form.value = form.copy(busy = true, error = null, offerDeviceOnly = false)
        formJob = viewModelScope.launch {
            val stored = existing?.catalog
            val tested = when {
                stored != null && stored.baseUrl == address && stored.username == user && stored.password == pass ->
                    CatalogCheck.Result.Found(stored)
                // The same address keeps its kind: a Calibre-Web feed address probes as plain OPDS.
                stored != null && stored.baseUrl == address ->
                    withContext(ioDispatcher) { check.feed(stored.copy(username = user, password = pass)) }
                else -> withContext(ioDispatcher) { check.check(address, user, pass) }
            }
            if (tested is CatalogCheck.Result.Failed) {
                _form.value = form.copy(error = tested.message)
                return@launch
            }
            val catalog = (tested as CatalogCheck.Result.Found).catalog
            val entry = (existing ?: SavedCatalog(form.id, name, null)).copy(
                name = name,
                catalog = catalog,
                deviceOnly = deviceOnly ?: (existing?.deviceOnly == true),
            )
            val toAccount = deviceOnly == false || (deviceOnly == null && existing == null)
            when (val saved = repository.save(entry, toAccount)) {
                CatalogResult.Done -> _form.value = null
                is CatalogResult.Failed -> _form.value = form.copy(
                    error = catalogFailureMessage(saved.reason),
                    offerDeviceOnly = saved.reason == CatalogFailure.LIMIT_REACHED && existing?.onAccount != true,
                )
            }
        }
    }

    /**
     * Add [open] as if its address had been typed into a new catalog form: tested first, then
     * saved to the Quire account when there is one.
     */
    fun addOpen(open: OpenCatalog) {
        if (_openAdding.value?.busy == true) return
        if (store.catalogs.value.any { it.catalog?.baseUrl == open.url }) return
        _openAdding.value = OpenCatalogAdding(open.id, busy = true)
        viewModelScope.launch {
            val tested = withContext(ioDispatcher) { check.feed(AccountCredentials.OpdsOnly(open.url)) }
            if (tested is CatalogCheck.Result.Failed) {
                _openAdding.value = OpenCatalogAdding(open.id, busy = false, error = tested.message)
                return@launch
            }
            val entry = SavedCatalog(SavedCatalog.newId(), open.name, (tested as CatalogCheck.Result.Found).catalog)
            _openAdding.value = when (val saved = repository.save(entry, toAccount = true)) {
                CatalogResult.Done -> null
                is CatalogResult.Failed -> OpenCatalogAdding(open.id, busy = false, error = catalogFailureMessage(saved.reason))
            }
        }
    }

    /** Remove the catalog the form is editing: from the Quire account too when it is there. */
    fun removeEditing() {
        val form = _form.value?.takeUnless { it.busy || it.isNew } ?: return
        _form.value = form.copy(busy = true, error = null)
        formJob = viewModelScope.launch {
            when (val removed = repository.remove(form.id)) {
                CatalogResult.Done -> _form.value = null
                is CatalogResult.Failed -> _form.value = form.copy(error = catalogFailureMessage(removed.reason))
            }
        }
    }

    /** Move a catalog [offset] places up (negative) or down. The pinned sign-in row stays first. */
    fun move(id: String, offset: Int) {
        val ids = store.catalogs.value.filterNot { it.pinned }.map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = from + offset
        if (from < 0 || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        viewModelScope.launch { _message.value = repository.reorder(ids).failureMessage() }
    }

    /** The answer to the sign-in question: upload [accepted], keep the rest on this device only. */
    fun answerUpload(accepted: Set<String>) {
        answered.value = true
        viewModelScope.launch { _message.value = repository.uploadPending(accepted).failureMessage() }
    }

    /** Put the question off until the next launch, deciding nothing. */
    fun postponeUpload() {
        answered.value = true
    }

    private fun fail(form: CatalogForm, reason: CatalogFailure) {
        _form.value = form.copy(error = catalogFailureMessage(reason))
    }

    private fun CatalogResult.failureMessage(): UiText? = (this as? CatalogResult.Failed)?.let { catalogFailureMessage(it.reason) }
}
