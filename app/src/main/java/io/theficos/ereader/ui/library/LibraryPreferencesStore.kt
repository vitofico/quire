package io.theficos.ereader.ui.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LibraryPreferencesStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("library_prefs", Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<LibrarySort> = _flow.asStateFlow()

    fun update(sort: LibrarySort) {
        prefs.edit().putString(KEY_SORT, sort.name).apply()
        _flow.value = sort
    }

    private fun load(): LibrarySort {
        val raw = prefs.getString(KEY_SORT, LibrarySort.RECENTLY_READ.name)
            ?: LibrarySort.RECENTLY_READ.name
        return runCatching { LibrarySort.valueOf(raw) }.getOrDefault(LibrarySort.RECENTLY_READ)
    }

    private companion object {
        const val KEY_SORT = "library_sort"
    }
}
