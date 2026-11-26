package io.theficos.ereader.sideload

import android.content.ContentResolver
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.theficos.ereader.EReaderApp
import io.theficos.ereader.ui.convert.PdfConvertPrompt
import io.theficos.ereader.ui.theme.QuireTheme
import io.theficos.quire.R

/**
 * Where a shared or opened PDF lands: a short dialog over the sending app, converting it on the
 * reader's Quire server or saying why that can't happen. Like [ImportActivity] it stays until the
 * PDF is copied, since the permission to read it ends with this activity.
 *
 * Only another app's content: never a `file:` path, nor one of Quire's own files.
 */
class ConvertPdfActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = extractImportUri(intent)?.takeIf {
            it.scheme == ContentResolver.SCHEME_CONTENT && it.authority?.startsWith(packageName) == false
        }
        if (uri == null) {
            Toast.makeText(this, getString(R.string.sideload_couldn_t_read_the_shared_file), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val container = (application as EReaderApp).container
        setContent {
            val theme by container.appThemePreferencesStore.flow.collectAsState()
            QuireTheme(theme = theme) {
                PdfConvertPrompt(uri, container.conversionRepository, onDismiss = ::finish)
            }
        }
    }
}
