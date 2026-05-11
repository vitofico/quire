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
            .putString(KEY_FONT_FAMILY, next.fontFamily.name)
            .putFloat(KEY_LINE_SPACING, next.lineSpacing.toFloat())
            .putBoolean(KEY_TAP_NAVIGATION, next.tapNavigationEnabled)
            .putFloat(KEY_PAGE_MARGINS, next.pageMargins.toFloat())
            .apply()
        _flow.value = next
    }

    private fun load(): ReaderPreferences {
        val fontScale = prefs.getFloat(KEY_FONT_SCALE, 1.0f).toDouble().coerceIn(0.5, 2.0)
        val themeName = prefs.getString(KEY_THEME, ReaderTheme.LIGHT.name) ?: ReaderTheme.LIGHT.name
        val theme = runCatching { ReaderTheme.valueOf(themeName) }.getOrDefault(ReaderTheme.LIGHT)
        val familyName = prefs.getString(KEY_FONT_FAMILY, ReaderFontFamily.SYSTEM.name)
            ?: ReaderFontFamily.SYSTEM.name
        val family = runCatching { ReaderFontFamily.valueOf(familyName) }
            .getOrDefault(ReaderFontFamily.SYSTEM)
        val lineSpacing = prefs.getFloat(KEY_LINE_SPACING, 1.4f).toDouble().coerceIn(1.0, 1.8)
        val tap = prefs.getBoolean(KEY_TAP_NAVIGATION, true)
        val pageMargins = prefs.getFloat(KEY_PAGE_MARGINS, 1.4f).toDouble().coerceIn(0.5, 2.0)
        return ReaderPreferences(
            fontScale = fontScale,
            theme = theme,
            fontFamily = family,
            lineSpacing = lineSpacing,
            tapNavigationEnabled = tap,
            pageMargins = pageMargins,
        )
    }

    private companion object {
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_THEME = "theme"
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_LINE_SPACING = "line_spacing"
        const val KEY_TAP_NAVIGATION = "tap_navigation_enabled"
        const val KEY_PAGE_MARGINS = "page_margins"
    }
}
