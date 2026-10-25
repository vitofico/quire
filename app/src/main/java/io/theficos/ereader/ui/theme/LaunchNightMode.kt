package io.theficos.ereader.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.util.Log

/** Persist brightness for Android's starting window, which precedes Compose and its palettes. */
internal fun Context.syncLaunchNightMode(mode: ThemeMode) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode.launchNightMode())
        } catch (failure: RuntimeException) {
            // A vendor service failure must not prevent the app or its saved Compose theme opening.
            Log.w("LaunchNightMode", "Unable to update the starting window brightness", failure)
        }
    }
}

internal fun ThemeMode.launchNightMode(): Int = when (this) {
    ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
    ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
    // Android maps AUTO to an undefined per-app override, restoring the system configuration.
    ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
}
