package io.theficos.ereader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun EReaderTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) QuireDarkColors else QuireLightColors
    MaterialTheme(
        colorScheme = colors,
        typography = QuireTypography,
        shapes = QuireShapes,
        content = content,
    )
}
