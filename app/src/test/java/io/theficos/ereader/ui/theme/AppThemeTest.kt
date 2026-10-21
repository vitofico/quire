package io.theficos.ereader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import kotlin.math.max
import kotlin.math.min
import org.junit.Test

class AppThemeTest {
    @Test fun `system appearance retains the original palettes`() {
        assertThat(AppTheme.SYSTEM.colorScheme(systemDark = false)).isSameInstanceAs(QuireLightColors)
        assertThat(AppTheme.SYSTEM.colorScheme(systemDark = true)).isSameInstanceAs(QuireDarkColors)
        assertThat(AppTheme.SYSTEM.isDark(systemDark = false)).isFalse()
        assertThat(AppTheme.SYSTEM.isDark(systemDark = true)).isTrue()
    }

    @Test fun `explicit app themes ignore system appearance`() {
        listOf(false, true).forEach { systemDark ->
            assertThat(AppTheme.LIGHT.colorScheme(systemDark)).isSameInstanceAs(QuireLightColors)
            assertThat(AppTheme.DARK.colorScheme(systemDark)).isSameInstanceAs(QuireDarkColors)
            assertThat(AppTheme.SAGE.colorScheme(systemDark)).isSameInstanceAs(QuireSageColors)
            assertThat(AppTheme.MIDNIGHT.colorScheme(systemDark)).isSameInstanceAs(QuireMidnightColors)
            assertThat(AppTheme.LIGHT.isDark(systemDark)).isFalse()
            assertThat(AppTheme.SAGE.isDark(systemDark)).isFalse()
            assertThat(AppTheme.DARK.isDark(systemDark)).isTrue()
            assertThat(AppTheme.MIDNIGHT.isDark(systemDark)).isTrue()
        }
    }

    @Test fun `new palettes keep text readable on app surfaces and controls`() {
        listOf(QuireSageColors, QuireMidnightColors).forEach { colors ->
            val pairs = listOf(
                colors.onBackground to colors.background,
                colors.onSurface to colors.surface,
                colors.onSurfaceVariant to colors.surfaceVariant,
                colors.onPrimary to colors.primary,
                colors.onPrimaryContainer to colors.primaryContainer,
                colors.onSecondary to colors.secondary,
                colors.onSecondaryContainer to colors.secondaryContainer,
                colors.onTertiary to colors.tertiary,
                colors.onTertiaryContainer to colors.tertiaryContainer,
                colors.inverseOnSurface to colors.inverseSurface,
                colors.primary to colors.surface,
            ) + listOf(
                colors.surfaceDim,
                colors.surfaceBright,
                colors.surfaceContainerLowest,
                colors.surfaceContainerLow,
                colors.surfaceContainer,
                colors.surfaceContainerHigh,
                colors.surfaceContainerHighest,
            ).map { colors.onSurface to it }

            pairs.forEach { (text, background) ->
                assertThat(contrastRatio(text, background)).isAtLeast(4.5)
            }
        }
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }
}
