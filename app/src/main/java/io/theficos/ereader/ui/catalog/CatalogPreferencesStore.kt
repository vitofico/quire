package io.theficos.ereader.ui.catalog

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CatalogPreferencesStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("catalog_prefs", Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<CatalogSort> = _flow.asStateFlow()

    fun update(sort: CatalogSort) {
        prefs.edit().putString(KEY_SORT, sort.name).apply()
        _flow.value = sort
    }

    /**
     * Default [CatalogSort.AS_SHOWN]: leave a catalog page in the order the
     * server sent it. The old default sorted every page by author, which
     * scrambled feeds whose order is the point — a Kavita series page came out
     * with volume 19 first and volume 16 last (issue #105).
     */
    private fun load(): CatalogSort {
        val raw = prefs.getString(KEY_SORT, CatalogSort.AS_SHOWN.name)
            ?: CatalogSort.AS_SHOWN.name
        return runCatching { CatalogSort.valueOf(raw) }.getOrDefault(CatalogSort.AS_SHOWN)
    }

    private companion object {
        const val KEY_SORT = "catalog_sort"
    }
}
