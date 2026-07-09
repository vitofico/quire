package io.theficos.ereader

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import io.theficos.ereader.ui.AppNavGraph
import io.theficos.ereader.ui.theme.EReaderTheme

class MainActivity : FragmentActivity() {
    // Fires before super dispatches the config change to fragments, so the reader
    // can snapshot its locator before Readium's WebView re-paginates on rotation.
    var onBeforeReaderConfigChange: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Go edge-to-edge for the whole app: the single Activity window keeps a stable
        // geometry, so entering/leaving the immersive reader never flips decorFits and
        // never resizes the shared window (which used to show a visible jump on Back).
        // Screens own their insets: Material3 Scaffold/TopAppBar/NavigationBar handle it
        // automatically; the few bare screens are wrapped in a safeDrawing inset in
        // AppNavGraph. Android 15 forces this anyway.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            EReaderTheme {
                AppNavGraph(container = (application as EReaderApp).container)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        onBeforeReaderConfigChange?.invoke()
        super.onConfigurationChanged(newConfig)
    }
}
