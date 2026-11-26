package io.theficos.ereader

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.theficos.ereader.notify.OpenRequest
import io.theficos.ereader.notify.openRequest
import io.theficos.ereader.ui.AppNavGraph
import io.theficos.ereader.ui.theme.QuireTheme
import io.theficos.ereader.ui.theme.styleSystemBars
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    // Fires before super dispatches the config change to fragments, so the reader
    // can snapshot its locator before Readium's WebView re-paginates on rotation.
    var onBeforeReaderConfigChange: (() -> Unit)? = null

    // Notification taps, held until the navigation graph is there to act on them.
    private val openRequests = Channel<OpenRequest>(Channel.CONFLATED)
    private val openRequestFlow = openRequests.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(readerRecreationState(savedInstanceState))
        // The whole app is edge-to-edge: the single Activity window keeps a stable geometry,
        // so entering/leaving the immersive reader never resizes the shared window (which used
        // to show a visible jump on Back). Screens own their insets: Material3 Scaffold/TopAppBar/
        // NavigationBar handle it automatically; the few bare screens are wrapped in a safeDrawing
        // inset in AppNavGraph. Android 15 forces this anyway.
        styleSystemBars(dark = resources.configuration.uiModeIsNight())
        // A recreated activity already acted on the intent that started it.
        if (savedInstanceState == null) intent.openRequest(this)?.let(openRequests::trySend)
        setContent {
            val container = (application as EReaderApp).container
            val theme by container.appThemePreferencesStore.flow.collectAsState()
            val backgroundStyle by container.appThemePreferencesStore.backgroundStyle.collectAsState()
            val dark = theme.isDark(isSystemInDarkTheme())
            DisposableEffect(dark) {
                styleSystemBars(dark)
                onDispose { }
            }
            QuireTheme(theme = theme, backgroundStyle = backgroundStyle) {
                // Paints the app background behind the transparent system bars and between
                // navigation transitions, so the starting window never shows through.
                Surface(Modifier.fillMaxSize()) {
                    AppNavGraph(container = container, appTheme = theme, openRequests = openRequestFlow)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.openRequest(this)?.let(openRequests::trySend)
    }

    override fun onStart() {
        super.onStart()
        // A PDF conversion carries on: after process death, or one waiting for the app to be open.
        val conversions = (application as EReaderApp).container.conversionRepository
        lifecycleScope.launch {
            runCatching { conversions.resume() }
                .onFailure { android.util.Log.w("MainActivity", "Couldn't resume PDF conversions", it) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        onBeforeReaderConfigChange?.invoke()
        super.onConfigurationChanged(newConfig)
    }
}

// Not isNightModeActive: that member only exists from API 30 and would shadow an extension.
private fun Configuration.uiModeIsNight(): Boolean =
    (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
