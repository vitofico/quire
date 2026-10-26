package io.theficos.ereader.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppThemePreferencesStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.applicationContext
        .getSharedPreferences("app_theme_prefs", Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(prefs.readTheme())
    val flow: StateFlow<AppTheme> = _flow.asStateFlow()

    private val _backgroundStyle = MutableStateFlow(BackgroundStyle.fromStored(prefs.getString("background-style", null)))
    val backgroundStyle: StateFlow<BackgroundStyle> = _backgroundStyle.asStateFlow()

    fun updateBackgroundStyle(style: BackgroundStyle) {
        prefs.edit().putString("background-style", style.name).commit()
        _backgroundStyle.value = style
    }

    init {
        appContext.syncLaunchNightMode(_flow.value.mode)
    }

    fun update(theme: AppTheme) {
        // The platform may recreate the Activity when brightness changes; save before notifying it.
        prefs.edit().putTheme(theme).commit()
        _flow.value = theme
        appContext.syncLaunchNightMode(theme.mode)
    }
}

private const val KEY_MODE = "mode"
private const val KEY_LIGHT = "light"
private const val KEY_DARK = "dark"

/** The single choice saved before mode and palettes were separate. */
private const val KEY_LEGACY = "theme"

private fun SharedPreferences.readTheme(): AppTheme {
    val mode = getString(KEY_MODE, null)
    if (mode == null) {
        val migrated = legacyTheme(getString(KEY_LEGACY, null))
        edit().putTheme(migrated).remove(KEY_LEGACY).apply()
        return migrated
    }
    val defaults = AppTheme()
    return AppTheme(
        mode = ThemeMode.entries.firstOrNull { it.name == mode } ?: defaults.mode,
        light = palette(getString(KEY_LIGHT, null), dark = false) ?: defaults.light,
        dark = palette(getString(KEY_DARK, null), dark = true) ?: defaults.dark,
    )
}

/** A saved palette of the right brightness, or null for anything else. */
private fun palette(name: String?, dark: Boolean): Palette? =
    Palette.entries.firstOrNull { it.name == name && it.isDark == dark }

/** The appearance each old single choice gave, so an upgrade changes nothing on screen. */
private fun legacyTheme(name: String?): AppTheme = when (name) {
    "LIGHT" -> AppTheme(mode = ThemeMode.LIGHT)
    "DARK" -> AppTheme(mode = ThemeMode.DARK)
    "SAGE" -> AppTheme(mode = ThemeMode.LIGHT, light = Palette.SAGE)
    "MIDNIGHT" -> AppTheme(mode = ThemeMode.DARK, dark = Palette.MIDNIGHT)
    else -> AppTheme()
}

private fun SharedPreferences.Editor.putTheme(theme: AppTheme) = this
    .putString(KEY_MODE, theme.mode.name)
    .putString(KEY_LIGHT, theme.light.name)
    .putString(KEY_DARK, theme.dark.name)
