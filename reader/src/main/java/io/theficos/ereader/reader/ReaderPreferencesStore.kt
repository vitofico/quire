package io.theficos.ereader.reader

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ReaderPreferencesStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<ReaderPreferences> = _flow.asStateFlow()

    fun update(transform: (ReaderPreferences) -> ReaderPreferences) {
        val next = transform(_flow.value)
        prefs.edit()
            .putFloat(KEY_FONT_SCALE, next.fontScale.toFloat())
            .putString(KEY_THEME, next.theme.name)
            .apply()
        _flow.value = next
    }

    private fun load(): ReaderPreferences {
        val fontScale = prefs.getFloat(KEY_FONT_SCALE, DEFAULT_FONT_SCALE).toDouble()
            .coerceIn(0.5, 2.0)
        val themeName = prefs.getString(KEY_THEME, ReaderTheme.LIGHT.name) ?: ReaderTheme.LIGHT.name
        val theme = runCatching { ReaderTheme.valueOf(themeName) }.getOrDefault(ReaderTheme.LIGHT)
        return ReaderPreferences(fontScale = fontScale, theme = theme)
    }

    private companion object {
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_THEME = "theme"
        const val DEFAULT_FONT_SCALE = 1.0f
    }
}
