package io.theficos.ereader.sideload

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.theficos.ereader.EReaderApp
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
 * UI: deliberately minimal — a toast on completion. Spec forbids any
 * curation / metadata-confirm UI in v1.
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
        val importer = (application as EReaderApp).container.sideloadImporter
        val displayName = SideloadImporter.queryDisplayName(contentResolver, uri)
        lifecycleScope.launch {
            val result = importer.import(uri, displayName)
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
