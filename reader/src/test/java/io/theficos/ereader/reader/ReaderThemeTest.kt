package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.preferences.Theme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric, despite there being nothing Android-shaped in these assertions: Readium's Theme
// initialises its colours via android.graphics.Color.parseColor, which the stub android.jar in a
// plain JVM test throws on ("not mocked").
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderThemeTest {

    @Test fun `each theme maps to its Readium appearance`() {
        assertThat(ReaderPreferences(theme = ReaderTheme.LIGHT).toEpubPreferences().theme)
            .isEqualTo(Theme.LIGHT)
        assertThat(ReaderPreferences(theme = ReaderTheme.DARK).toEpubPreferences().theme)
            .isEqualTo(Theme.DARK)
        assertThat(ReaderPreferences(theme = ReaderTheme.SEPIA).toEpubPreferences().theme)
            .isEqualTo(Theme.SEPIA)
        // Dark sepia is night mode plus custom colours — Readium's SEPIA appearance blends images
        // with mix-blend-mode:multiply, which assumes a light backdrop.
        assertThat(ReaderPreferences(theme = ReaderTheme.DARK_SEPIA).toEpubPreferences().theme)
            .isEqualTo(Theme.DARK)
    }

    @Test fun `the stock themes emit no colour override`() {
        // Regression guard: adding DARK_SEPIA must leave the three original themes rendering
        // exactly as before, which means Readium sees null for both colours.
        listOf(ReaderTheme.LIGHT, ReaderTheme.DARK, ReaderTheme.SEPIA).forEach { theme ->
            val prefs = ReaderPreferences(theme = theme).toEpubPreferences()
            assertThat(prefs.textColor).isNull()
            assertThat(prefs.backgroundColor).isNull()
        }
    }

    @Test fun `dark sepia emits Calibre's sepia-dark colours`() {
        val prefs = ReaderPreferences(theme = ReaderTheme.DARK_SEPIA).toEpubPreferences()
        assertThat(prefs.textColor?.int).isEqualTo(0xFFF6F3E9.toInt())
        assertThat(prefs.backgroundColor?.int).isEqualTo(0xFF39322B.toInt())
    }

    @Test fun `dark sepia clears WCAG AA for body text`() {
        val ratio = contrastRatio(
            requireNotNull(ReaderTheme.DARK_SEPIA.textColor),
            requireNotNull(ReaderTheme.DARK_SEPIA.backgroundColor),
        )
        // 4.5:1 is the AA floor for body text. The chosen pair is ~11.4:1; this asserts the floor
        // so a future colour tweak can't quietly make the theme unreadable.
        assertThat(ratio).isGreaterThan(4.5)
    }

    @Test fun `isDark is set for every theme with a dark background`() {
        // Drives the immersive system bars. Getting it wrong paints dark icons on a dark
        // background, which reads as "the buttons vanished".
        assertThat(ReaderTheme.LIGHT.isDark).isFalse()
        assertThat(ReaderTheme.SEPIA.isDark).isFalse()
        assertThat(ReaderTheme.DARK.isDark).isTrue()
        assertThat(ReaderTheme.DARK_SEPIA.isDark).isTrue()
    }

    @Test fun `every theme declaring colours also declares both of them`() {
        // A background without a matching text colour (or vice versa) inherits the other from the
        // base appearance and can land on unreadable pairings.
        ReaderTheme.values().forEach { theme ->
            assertThat(theme.textColor == null).isEqualTo(theme.backgroundColor == null)
        }
    }

    /** WCAG 2.1 contrast ratio between two opaque ARGB colours. */
    private fun contrastRatio(argbA: Int, argbB: Int): Double {
        val a = relativeLuminance(argbA)
        val b = relativeLuminance(argbB)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun relativeLuminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val raw = (argb shr shift and 0xFF) / 255.0
            return if (raw <= 0.03928) raw / 12.92 else ((raw + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
