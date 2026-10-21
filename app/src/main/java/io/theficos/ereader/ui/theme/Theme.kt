package io.theficos.ereader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun EReaderTheme(theme: AppTheme = AppTheme.SYSTEM, content: @Composable () -> Unit) {
    val colors = theme.colorScheme(isSystemInDarkTheme())
    // Lora loads from assets, which needs the context; build it once, not per recomposition.
    val assets = LocalContext.current.assets
    val typography = remember(assets) { quireTypography(loraFontFamily(assets)) }
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = QuireShapes,
        content = content,
    )
}
