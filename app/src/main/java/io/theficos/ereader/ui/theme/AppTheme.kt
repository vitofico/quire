package io.theficos.ereader.ui.theme

/** App chrome only; EPUB colours are controlled by the reader's separate preferences. */
enum class AppTheme(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark"),
    SAGE("Sage"),
    MIDNIGHT("Midnight"),
    ;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT, SAGE -> false
        DARK, MIDNIGHT -> true
    }
}

internal fun AppTheme.colorScheme(systemDark: Boolean) = when (this) {
    AppTheme.SYSTEM -> if (systemDark) QuireDarkColors else QuireLightColors
    AppTheme.LIGHT -> QuireLightColors
    AppTheme.DARK -> QuireDarkColors
    AppTheme.SAGE -> QuireSageColors
    AppTheme.MIDNIGHT -> QuireMidnightColors
}
