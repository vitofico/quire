package io.theficos.ereader.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.sideload.EpubExport
import io.theficos.quire.R
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Save EPUB as…" for a library book: opens the system's create-document picker with a
 * suggested name, then copies the book's EPUB to the chosen place and reports the outcome
 * on [snackbarHostState]. Cancelling the picker does nothing. SAF needs no permissions.
 */
@Composable
fun rememberSaveEpubAs(snackbarHostState: SnackbarHostState): (Document) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saveable so the choice survives the activity being recreated behind the picker.
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip"),
    ) { uri ->
        val path = pendingPath
        pendingPath = null
        if (uri == null || path == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = EpubExport.copyTo(context.contentResolver, File(path), uri)
            snackbarHostState.showSnackbar(
                context.getString(if (saved) R.string.common_epub_saved else R.string.common_couldn_t_save_the_epub),
            )
        }
    }
    return { doc ->
        pendingPath = doc.localPath
        launcher.launch(EpubExport.suggestedFileName(doc.title, doc.author, File(doc.localPath).name))
    }
}
