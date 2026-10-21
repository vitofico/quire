package io.theficos.ereader.ui.theme

import android.app.Activity
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AppSystemBarsTest {
    @Suppress("DEPRECATION")
    @Test fun `switching app palettes recolors both bars and updates icon contrast`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        // The final Sage selection also guards switching back from a dark palette.
        listOf(AppTheme.SAGE, AppTheme.MIDNIGHT, AppTheme.SAGE).forEach { theme ->
            val colors = theme.colorScheme(systemDark = false)
            applyAppSystemBars(window, colors, theme.isDark(systemDark = false))

            assertThat(window.statusBarColor).isEqualTo(colors.surface.toArgb())
            assertThat(window.navigationBarColor).isEqualTo(colors.surfaceVariant.toArgb())
            assertThat(controller.isAppearanceLightStatusBars).isEqualTo(theme == AppTheme.SAGE)
            assertThat(controller.isAppearanceLightNavigationBars).isEqualTo(theme == AppTheme.SAGE)
        }
        activity.finish()
    }
}
