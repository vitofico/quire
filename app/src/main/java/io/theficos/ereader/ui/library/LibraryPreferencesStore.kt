package io.theficos.ereader.ui.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted library home preferences.
 *
 * pr-δ: extended from a bare `LibrarySort` to a struct carrying the
 * `showAbandoned` toggle so the library home can hide abandoned books by
 * default. The underlying store is still `SharedPreferences` per Lock #17.
 * `showFinished` (issue #141) does the same for finished books, but defaults
 * to showing them.
 */
data class LibraryPreferences(
    val sort: LibrarySort,
    val showAbandoned: Boolean,
    val showFinished: Boolean,
)

class LibraryPreferencesStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("library_prefs", Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<LibraryPreferences> = _flow.asStateFlow()

    fun updateSort(sort: LibrarySort) {
        prefs.edit().putString(KEY_SORT, sort.name).apply()
        _flow.value = _flow.value.copy(sort = sort)
    }

    fun updateShowAbandoned(showAbandoned: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ABANDONED, showAbandoned).apply()
        _flow.value = _flow.value.copy(showAbandoned = showAbandoned)
    }

    fun updateShowFinished(showFinished: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_FINISHED, showFinished).apply()
        _flow.value = _flow.value.copy(showFinished = showFinished)
    }

    private fun load(): LibraryPreferences {
        val raw = prefs.getString(KEY_SORT, LibrarySort.RECENTLY_READ.name)
            ?: LibrarySort.RECENTLY_READ.name
        val sort = runCatching { LibrarySort.valueOf(raw) }.getOrDefault(LibrarySort.RECENTLY_READ)
        val showAbandoned = prefs.getBoolean(KEY_SHOW_ABANDONED, false)
        val showFinished = prefs.getBoolean(KEY_SHOW_FINISHED, true)
        return LibraryPreferences(sort = sort, showAbandoned = showAbandoned, showFinished = showFinished)
    }

    private companion object {
        const val KEY_SORT = "library_sort"
        // Lock #17: exact key — dot separator, not underscore.
        const val KEY_SHOW_ABANDONED = "library.show_abandoned"
        const val KEY_SHOW_FINISHED = "library.show_finished"
    }
}
