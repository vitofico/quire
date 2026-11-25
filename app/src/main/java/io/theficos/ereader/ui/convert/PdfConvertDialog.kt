package io.theficos.ereader.ui.convert

import android.net.ConnectivityManager
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.theficos.ereader.data.library.convert.ConversionRepository
import io.theficos.ereader.data.library.convert.ConversionStart
import io.theficos.quire.R

/**
 * Asks before a PDF goes to the reader's server and explains plainly when it can't. The file
 * name is the provider's, shown as plain text.
 */
@Composable
fun PdfConvertDialog(
    viewModel: PdfConvertViewModel,
    onDismiss: () -> Unit,
    onShowConversions: (() -> Unit)? = null,
    onOpenBook: ((Long) -> Unit)? = null,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val size = viewModel.sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
    val ok: @Composable () -> Unit = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } }
    val cancel: @Composable () -> Unit = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
    }
    var confirmButton: @Composable () -> Unit = ok
    var dismissButton: (@Composable () -> Unit)? = null
    val text: String = when (val s = state) {
        PdfConvertState.Checking -> {
            confirmButton = cancel
            stringResource(R.string.convert_checking)
        }
        PdfConvertState.Preparing -> {
            confirmButton = {}
            stringResource(R.string.convert_preparing)
        }
        is PdfConvertState.Confirm -> {
            confirmButton = {
                TextButton(onClick = viewModel::confirm) { Text(stringResource(R.string.convert_action)) }
            }
            dismissButton = cancel
            val main = if (size != null) {
                stringResource(R.string.convert_confirm, viewModel.fileName, size)
            } else {
                stringResource(R.string.convert_confirm_unknown_size, viewModel.fileName)
            }
            if (s.metered && size != null) main + "\n\n" + stringResource(R.string.convert_metered, size) else main
        }
        is PdfConvertState.TooLarge -> {
            val limit = Formatter.formatShortFileSize(context, s.maxBytes)
            if (size != null) stringResource(R.string.convert_too_large, size, limit)
            else stringResource(R.string.convert_too_large_unknown, limit)
        }
        is PdfConvertState.Blocked -> {
            if (s.reason == ConvertBlocked.UNREACHABLE) {
                confirmButton = { TextButton(onClick = viewModel::check) { Text(stringResource(R.string.common_retry)) } }
                dismissButton = cancel
            }
            stringResource(
                when (s.reason) {
                    ConvertBlocked.NO_SERVER -> R.string.convert_no_server
                    ConvertBlocked.NOT_OFFERED -> R.string.convert_not_offered
                    ConvertBlocked.UNAVAILABLE -> R.string.convert_unavailable
                    ConvertBlocked.SIGNED_OUT -> R.string.convert_signed_out
                    ConvertBlocked.UNREACHABLE -> R.string.convert_unreachable
                    ConvertBlocked.UNREADABLE -> R.string.convert_unreadable
                },
            )
        }
        is PdfConvertState.Done -> when (val outcome = s.outcome) {
            is ConversionStart.AlreadyInLibrary -> {
                if (onOpenBook != null) {
                    confirmButton = {
                        TextButton(onClick = { onOpenBook(outcome.documentId) }) { Text(stringResource(R.string.convert_open_book)) }
                    }
                    dismissButton = ok
                }
                stringResource(R.string.convert_already_in_library)
            }
            else -> {
                if (onShowConversions != null) {
                    confirmButton = { TextButton(onClick = onShowConversions) { Text(stringResource(R.string.convert_show)) } }
                    dismissButton = ok
                }
                if (outcome is ConversionStart.AlreadyConverting) {
                    stringResource(R.string.convert_already_converting)
                } else {
                    stringResource(R.string.convert_started, stringResource(R.string.common_more), stringResource(R.string.convert_title))
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (state != PdfConvertState.Preparing) onDismiss() },
        title = { Text(stringResource(R.string.convert_dialog_title)) },
        text = { Text(text) },
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    )
}

/** [PdfConvertDialog] for the PDF at [uri]; a new [key] asks afresh for the same file. */
@Composable
fun PdfConvertPrompt(
    uri: Uri,
    key: String,
    repository: ConversionRepository,
    onDismiss: () -> Unit,
    onShowConversions: (() -> Unit)? = null,
    onOpenBook: ((Long) -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val vm: PdfConvertViewModel = viewModel(
        key = "pdf-convert:$key",
        factory = viewModelFactory {
            initializer {
                val resolver = context.contentResolver
                val (name, size) = queryNameAndSize(resolver, uri)
                PdfConvertViewModel(
                    repository,
                    fileName = name ?: context.getString(R.string.convert_untitled_pdf),
                    sizeBytes = size,
                    open = { resolver.openInputStream(uri) },
                    metered = { context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered },
                )
            }
        },
    )
    PdfConvertDialog(vm, onDismiss, onShowConversions, onOpenBook)
}
