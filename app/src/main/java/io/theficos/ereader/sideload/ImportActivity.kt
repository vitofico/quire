package io.theficos.ereader.sideload

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import io.theficos.ereader.EReaderApp
import io.theficos.ereader.ui.convert.PdfConvertPrompt
import io.theficos.ereader.ui.convert.isPdf
import io.theficos.ereader.ui.convert.queryNameAndSize
import io.theficos.ereader.ui.theme.QuireTheme
import io.theficos.quire.R
import kotlinx.coroutines.launch

/**
 * Transient sink activity for share-sheet (`ACTION_SEND`) and file-manager
 * (`ACTION_VIEW`) intents pointed at the app. Stays alive only as long as
 * needed to copy the content URI bytes into app-private storage — content
 * URI grants are tied to this activity's lifecycle, so we MUST NOT hand the
 * URI to a background WorkManager job and finish() early.
 *
 * Manifest config: `taskAffinity=""` + `excludeFromRecents="true"` keeps the
 * import flow out of the user's MainActivity task stack and out of recents,
 * so a user who shared a book from another app doesn't end up with Quire
 * sitting in their multitasker switcher.
 *
 * UI: an EPUB gets a toast on completion. A PDF gets a short dialog over the
 * sending app: converting it on the reader's Quire server, or why that can't
 * happen. The PDF is copied before the dialog closes, for the same reason.
 */
class ImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = extractImportUri(intent)
        if (uri == null) {
            Toast.makeText(this, getString(R.string.sideload_couldn_t_read_the_shared_file), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val container = (application as EReaderApp).container
        val (name, _) = queryNameAndSize(contentResolver, uri)
        if (isPdf(intent.type ?: contentResolver.getType(uri), name ?: uri.lastPathSegment)) {
            setContent {
                val theme by container.appThemePreferencesStore.flow.collectAsState()
                QuireTheme(theme = theme) {
                    PdfConvertPrompt(uri, key = uri.toString(), container.conversionRepository, onDismiss = ::finish)
                }
            }
            return
        }
        lifecycleScope.launch {
            val result = container.sideloadImporter.import(uri, name)
            val msg = when (result) {
                is SideloadResult.Imported -> getString(R.string.common_imported, result.title)
                is SideloadResult.AlreadyImported -> getString(R.string.sideload_already_in_your_library, result.title)
                is SideloadResult.Failed -> when (result.reason) {
                    SideloadFailure.InvalidEpub -> getString(R.string.common_not_a_valid_epub)
                    SideloadFailure.UriUnreadable -> getString(R.string.sideload_couldn_t_read_the_shared_file)
                    SideloadFailure.IoError -> getString(R.string.sideload_import_failed_try_again)
                }
            }
            Toast.makeText(this@ImportActivity, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
