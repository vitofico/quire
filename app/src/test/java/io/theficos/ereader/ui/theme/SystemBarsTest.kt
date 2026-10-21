package io.theficos.ereader.ui.theme

import android.graphics.Color
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SystemBarsTest {
    @Suppress("DEPRECATION")
    @Test fun `bars stay transparent and their icons follow the appearance`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        // Ending on light also guards switching back from dark.
        listOf(false, true, false).forEach { dark ->
            activity.styleSystemBars(dark)

            assertThat(window.statusBarColor).isEqualTo(Color.TRANSPARENT)
            assertThat(window.navigationBarColor).isEqualTo(Color.TRANSPARENT)
            assertThat(controller.isAppearanceLightStatusBars).isEqualTo(!dark)
            assertThat(controller.isAppearanceLightNavigationBars).isEqualTo(!dark)
            // App screens aren't laid out around a camera hole; only the reader opts in.
            assertThat(window.attributes.layoutInDisplayCutoutMode)
                .isEqualTo(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT)
        }
        activity.finish()
    }
}
