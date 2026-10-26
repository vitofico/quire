package io.theficos.ereader.ui.theme

import androidx.annotation.StringRes
import io.theficos.quire.R
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.theficos.ereader.reader.ReaderTheme

/** Whether the app follows the system's light/dark setting or holds one of its own. */
enum class ThemeMode(@StringRes val label: Int) {
    SYSTEM(R.string.font_system),
    LIGHT(R.string.common_light),
    DARK(R.string.common_dark),
}

/** The app's colour palettes. Each is either light or dark, never both. */
enum class Palette(@StringRes val label: Int, val isDark: Boolean) {
    PAPER(R.string.palette_paper, isDark = false),
    SAGE(R.string.common_sage, isDark = false),
    INK(R.string.palette_ink, isDark = true),
    MIDNIGHT(R.string.common_midnight, isDark = true),
    ;

    val colors: ColorScheme
        get() = when (this) {
            PAPER -> PaperColors
            SAGE -> SageColors
            INK -> InkColors
            MIDNIGHT -> MidnightColors
        }
}

/**
 * The app's appearance: a [mode], and the palette to use when it resolves to light and when it
 * resolves to dark, so someone following the system can have Sage by day and Midnight by night.
 * App chrome only; the page colours of a book are the reader's own preference.
 */
data class AppTheme(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val light: Palette = Palette.PAPER,
    val dark: Palette = Palette.INK,
) {
    init {
        require(!light.isDark) { "light palette is dark: $light" }
        require(dark.isDark) { "dark palette is light: $dark" }
    }

    fun isDark(systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    fun palette(systemDark: Boolean): Palette = if (isDark(systemDark)) dark else light

    /**
     * Colours for the reader's chrome over a [page]: the palette of the page's brightness, with
     * the page colour as its surface, so the bars and sheets read as part of the page instead of
     * an app-coloured band over it.
     */
    fun readerColors(page: ReaderTheme): ColorScheme {
        val paper = Color(page.pageBackground)
        return (if (page.isDark) dark else light).colors.copy(surface = paper, background = paper)
    }
}

internal val QuireShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),  // covers
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),     // buttons
    large = RoundedCornerShape(12.dp),      // cards
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun QuireTheme(
    theme: AppTheme = AppTheme(),
    backgroundStyle: BackgroundStyle = LocalBackgroundStyle.current,
    content: @Composable () -> Unit,
) {
    val colors = theme.palette(isSystemInDarkTheme()).colors
    // Lora loads from assets, which needs the context; build it once, not per recomposition.
    val assets = LocalContext.current.assets
    val typography = remember(assets) { quireTypography(loraFontFamily(assets)) }
    CompositionLocalProvider(LocalBackgroundStyle provides backgroundStyle) {
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = QuireShapes,
        content = content,
    )
    }
}
