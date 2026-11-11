package io.theficos.ereader.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class WhatsNewPreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("whats_new_prefs", Context.MODE_PRIVATE)
    private val _lastSeenEntryId = MutableStateFlow(prefs.getString("last_seen_entry_id", null))
    private val _highlightsOpened = MutableStateFlow(prefs.getBoolean("highlights_opened", false))

    val lastSeenEntryId = _lastSeenEntryId.asStateFlow()
    val highlightsOpened = _highlightsOpened.asStateFlow()

    fun markSeen(entryId: String) {
        if (_lastSeenEntryId.value == entryId) return
        prefs.edit().putString("last_seen_entry_id", entryId).apply()
        _lastSeenEntryId.value = entryId
    }

    fun markHighlightsOpened() {
        if (_highlightsOpened.value) return
        prefs.edit().putBoolean("highlights_opened", true).apply()
        _highlightsOpened.value = true
    }
}
