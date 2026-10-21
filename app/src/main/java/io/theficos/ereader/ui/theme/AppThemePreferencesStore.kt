package io.theficos.ereader.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppThemePreferencesStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("app_theme_prefs", Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(
        AppTheme.entries.firstOrNull { it.name == prefs.getString("theme", null) }
            ?: AppTheme.SYSTEM,
    )
    val flow: StateFlow<AppTheme> = _flow.asStateFlow()

    fun update(theme: AppTheme) {
        prefs.edit().putString("theme", theme.name).apply()
        _flow.value = theme
    }
}
