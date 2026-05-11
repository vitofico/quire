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

    private fun load(): CatalogSort {
        val raw = prefs.getString(KEY_SORT, CatalogSort.AUTHOR.name)
            ?: CatalogSort.AUTHOR.name
        return runCatching { CatalogSort.valueOf(raw) }.getOrDefault(CatalogSort.AUTHOR)
    }

    private companion object {
        const val KEY_SORT = "catalog_sort"
    }
}
