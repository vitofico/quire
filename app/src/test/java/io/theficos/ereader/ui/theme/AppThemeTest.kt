package io.theficos.ereader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.theficos.ereader.reader.ReaderTheme
import kotlin.math.max
import kotlin.math.min
import org.junit.Test

class AppThemeTest {
    @Test fun `system mode follows the system between the light and the dark palette`() {
        val theme = AppTheme(light = Palette.SAGE, dark = Palette.MIDNIGHT)
        assertThat(theme.palette(systemDark = false)).isEqualTo(Palette.SAGE)
        assertThat(theme.palette(systemDark = true)).isEqualTo(Palette.MIDNIGHT)
        assertThat(theme.isDark(systemDark = false)).isFalse()
        assertThat(theme.isDark(systemDark = true)).isTrue()
    }

    @Test fun `light and dark modes ignore the system`() {
        listOf(false, true).forEach { systemDark ->
            val light = AppTheme(mode = ThemeMode.LIGHT, light = Palette.SAGE)
            val dark = AppTheme(mode = ThemeMode.DARK, dark = Palette.MIDNIGHT)
            assertThat(light.palette(systemDark)).isEqualTo(Palette.SAGE)
            assertThat(light.isDark(systemDark)).isFalse()
            assertThat(dark.palette(systemDark)).isEqualTo(Palette.MIDNIGHT)
            assertThat(dark.isDark(systemDark)).isTrue()
        }
    }

    @Test fun `the defaults are Quire's own palettes, following the system`() {
        assertThat(AppTheme()).isEqualTo(AppTheme(ThemeMode.SYSTEM, Palette.PAPER, Palette.INK))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a dark palette can't be the light one`() {
        AppTheme(light = Palette.MIDNIGHT)
    }

    @Test fun `the reader's chrome takes the page colour and the palette of its brightness`() {
        val theme = AppTheme(light = Palette.SAGE, dark = Palette.MIDNIGHT)
        ReaderTheme.entries.forEach { page ->
            val colors = theme.readerColors(page)
            val palette = if (page.isDark) Palette.MIDNIGHT else Palette.SAGE
            assertWithMessage("$page surface").that(colors.surface).isEqualTo(Color(page.pageBackground))
            assertWithMessage("$page background").that(colors.background).isEqualTo(Color(page.pageBackground))
            assertWithMessage("$page primary").that(colors.primary).isEqualTo(palette.colors.primary)
        }
    }

    @Test fun `every palette declares its brightness truthfully`() {
        Palette.entries.forEach { palette ->
            assertWithMessage("$palette").that(palette.colors.surface.luminance() < 0.5f).isEqualTo(palette.isDark)
        }
    }

    @Test fun `every palette keeps text readable on its surfaces and controls`() {
        Palette.entries.forEach { palette ->
            val colors = palette.colors
            val surfaces = listOf(
                colors.surface,
                colors.surfaceDim,
                colors.surfaceBright,
                colors.surfaceContainerLowest,
                colors.surfaceContainerLow,
                colors.surfaceContainer,
                colors.surfaceContainerHigh,
            )
            val pairs = listOf(
                "onBackground" to (colors.onBackground to colors.background),
                "onPrimary" to (colors.onPrimary to colors.primary),
                "onPrimaryContainer" to (colors.onPrimaryContainer to colors.primaryContainer),
                "onSecondary" to (colors.onSecondary to colors.secondary),
                "onSecondaryContainer" to (colors.onSecondaryContainer to colors.secondaryContainer),
                "onTertiary" to (colors.onTertiary to colors.tertiary),
                "onTertiaryContainer" to (colors.onTertiaryContainer to colors.tertiaryContainer),
                "inverseOnSurface" to (colors.inverseOnSurface to colors.inverseSurface),
            ) + (surfaces + colors.surfaceContainerHighest).flatMap { surface ->
                listOf(
                    "onSurface" to (colors.onSurface to surface),
                    "onSurfaceVariant" to (colors.onSurfaceVariant to surface),
                )
            } + surfaces.map { "primary as text" to (colors.primary to it) }

            pairs.forEach { (name, pair) ->
                assertWithMessage("$palette $name").that(contrastRatio(pair.first, pair.second)).isAtLeast(4.5)
            }
            // A text field's border has to be seen to be found.
            assertWithMessage("$palette outline").that(contrastRatio(colors.outline, colors.surface)).isAtLeast(3.0)
        }
    }

    @Test fun `ambient ink preserves text contrast in every palette`() {
        Palette.entries.forEach { palette ->
            val colors = palette.colors
            val strongestInk = colors.primary.copy(alpha = 0.11f).compositeOver(colors.background)
            listOf(colors.onBackground, colors.onSurfaceVariant, colors.primary).forEach { text ->
                assertWithMessage("$palette text over artwork").that(contrastRatio(text, strongestInk)).isAtLeast(4.5)
            }
        }
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }
}
