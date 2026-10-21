package io.theficos.ereader.ui.theme

import android.graphics.Color
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * The only code that styles the system bars: transparent, so each screen's own background shows
 * through, with icons that suit [dark]. The reader changes only the icons, the bars' visibility
 * and the cutout mode while a book is open, and puts them back when it closes.
 */
internal fun ComponentActivity.styleSystemBars(dark: Boolean) {
    val style = if (dark) {
        SystemBarStyle.dark(Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    }
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    // enableEdgeToEdge also opens the window into the display cutout. The app's screens aren't
    // laid out around a camera hole, so keep the default; the reader opts in while full-screen.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
    }
}
