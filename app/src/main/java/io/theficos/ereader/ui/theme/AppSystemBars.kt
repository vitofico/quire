package io.theficos.ereader.ui.theme

import android.view.Window
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat

/** Override the XML launch theme's bar colours when an app palette is selected. */
@Suppress("DEPRECATION")
internal fun applyAppSystemBars(window: Window, colors: ColorScheme, dark: Boolean) {
    window.statusBarColor = colors.surface.toArgb()
    window.navigationBarColor = colors.surfaceVariant.toArgb()
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = !dark
    controller.isAppearanceLightNavigationBars = !dark
}
