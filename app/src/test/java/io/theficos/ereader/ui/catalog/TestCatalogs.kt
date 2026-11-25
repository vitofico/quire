package io.theficos.ereader.ui.catalog

import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.SavedCatalog

/**
 * Point the device at [catalog]: the active catalog takes the new address or login, as an edit in
 * Settings would, or a first catalog is added.
 */
fun CalibreCredentialStore.useCatalog(catalog: AccountCredentials.OpdsOnly) {
    val active = activeCatalog.value?.takeUnless { it.pinned }
    val entry = active?.copy(catalog = catalog)
        ?: SavedCatalog(SavedCatalog.newId(), SavedCatalog.defaultName(catalog.baseUrl), catalog)
    saveCatalog(entry)
    setActiveCatalog(entry.id)
}
