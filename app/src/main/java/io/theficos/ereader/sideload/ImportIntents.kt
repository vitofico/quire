package io.theficos.ereader.sideload

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Extracts the EPUB content URI from an intent that targets [ImportActivity].
 *
 * Two shapes are accepted:
 *   - `ACTION_VIEW`: the URI lives on `intent.data`. Used by file managers
 *     opening an `.epub` file.
 *   - `ACTION_SEND`: the URI lives in `EXTRA_STREAM`. Used by share-sheet.
 *
 * Returns `null` if the intent doesn't carry a usable URI. Lives in its own
 * file so the activity's job is reduced to thin glue and the parsing logic
 * stays unit-testable without needing to launch an Activity.
 */
fun extractImportUri(intent: Intent?): Uri? {
    if (intent == null) return null
    return when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> {
            // getParcelableExtra(String) is deprecated on API 33+ in favor of
            // the type-safe overload. Both branches handle the same payload —
            // SDK_INT gates the API surface, not the semantics.
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
        }
        else -> null
    }
}
