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
            // NaN is the "unset / use book default" sentinel for the nullable knobs.
            .putFloat(KEY_PARAGRAPH_INDENT, next.paragraphIndent?.toFloat() ?: Float.NaN)
            .putFloat(KEY_PARAGRAPH_SPACING, next.paragraphSpacing?.toFloat() ?: Float.NaN)
            .putBoolean(KEY_USE_PUBLISHER_STYLES, next.usePublisherStyles)
            .putBoolean(KEY_IMMERSIVE_READING, next.immersiveReading)
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
        val paragraphIndent = prefs.getFloat(KEY_PARAGRAPH_INDENT, Float.NaN)
            .takeUnless { it.isNaN() }?.toDouble()?.coerceIn(0.0, 3.0)
        val paragraphSpacing = prefs.getFloat(KEY_PARAGRAPH_SPACING, Float.NaN)
            .takeUnless { it.isNaN() }?.toDouble()?.coerceIn(0.0, 2.0)
        val usePublisherStyles = prefs.getBoolean(KEY_USE_PUBLISHER_STYLES, false)
        val immersiveReading = prefs.getBoolean(KEY_IMMERSIVE_READING, true)
        return ReaderPreferences(
            fontScale = fontScale,
            theme = theme,
            fontFamily = family,
            lineSpacing = lineSpacing,
            tapNavigationEnabled = tap,
            pageMargins = pageMargins,
            paragraphIndent = paragraphIndent,
            paragraphSpacing = paragraphSpacing,
            usePublisherStyles = usePublisherStyles,
            immersiveReading = immersiveReading,
        )
    }

    private companion object {
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_THEME = "theme"
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_LINE_SPACING = "line_spacing"
        const val KEY_TAP_NAVIGATION = "tap_navigation_enabled"
        const val KEY_PAGE_MARGINS = "page_margins"
        const val KEY_PARAGRAPH_INDENT = "paragraph_indent"
        const val KEY_PARAGRAPH_SPACING = "paragraph_spacing"
        const val KEY_USE_PUBLISHER_STYLES = "use_publisher_styles"
        const val KEY_IMMERSIVE_READING = "immersive_reading"
    }
}
