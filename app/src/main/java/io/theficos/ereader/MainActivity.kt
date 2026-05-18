package io.theficos.ereader

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import io.theficos.ereader.ui.AppNavGraph
import io.theficos.ereader.ui.theme.EReaderTheme

class MainActivity : FragmentActivity() {
    // Fires before super dispatches the config change to fragments, so the reader
    // can snapshot its locator before Readium's WebView re-paginates on rotation.
    var onBeforeReaderConfigChange: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
