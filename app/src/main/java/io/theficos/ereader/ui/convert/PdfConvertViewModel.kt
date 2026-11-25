package io.theficos.ereader.ui.convert

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.data.library.convert.ConversionOffer
import io.theficos.ereader.data.library.convert.ConversionRepository
import io.theficos.ereader.data.library.convert.ConversionStart
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Why a PDF cannot be converted from here; each has its own plain explanation. */
enum class ConvertBlocked { NO_SERVER, NOT_OFFERED, UNAVAILABLE, SIGNED_OUT, UNREACHABLE, UNREADABLE, NO_SPACE }

sealed interface PdfConvertState {
    data object Checking : PdfConvertState
    data class Confirm(val maxBytes: Long, val metered: Boolean) : PdfConvertState
    data class TooLarge(val maxBytes: Long) : PdfConvertState
    data class Blocked(val reason: ConvertBlocked) : PdfConvertState
    data object Preparing : PdfConvertState
    data class Done(val outcome: ConversionStart) : PdfConvertState
}

/**
 * The short confirmation before a PDF goes to the reader's server: is conversion on offer, does
 * the file fit, and, once confirmed, the app's own copy of it. [open] reads the PDF; it must be
 * called while whatever granted access to it is still around.
 */
class PdfConvertViewModel(
    private val repository: ConversionRepository,
    val fileName: String,
    /** The size the provider reported, when it did. */
    val sizeBytes: Long?,
    private val open: () -> InputStream?,
    private val metered: () -> Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow<PdfConvertState>(PdfConvertState.Checking)
    val state: StateFlow<PdfConvertState> = _state.asStateFlow()

    init {
        check()
    }

    fun check() {
        _state.value = PdfConvertState.Checking
        viewModelScope.launch {
            _state.value = when (val offer = repository.offer()) {
                is ConversionOffer.Available -> when {
                    sizeBytes != null && sizeBytes > offer.maxBytes -> PdfConvertState.TooLarge(offer.maxBytes)
                    else -> PdfConvertState.Confirm(offer.maxBytes, metered())
                }
                ConversionOffer.NoServer -> PdfConvertState.Blocked(ConvertBlocked.NO_SERVER)
                ConversionOffer.NotOffered -> PdfConvertState.Blocked(ConvertBlocked.NOT_OFFERED)
                ConversionOffer.Unavailable -> PdfConvertState.Blocked(ConvertBlocked.UNAVAILABLE)
                ConversionOffer.SignedOut -> PdfConvertState.Blocked(ConvertBlocked.SIGNED_OUT)
                ConversionOffer.Unreachable -> PdfConvertState.Blocked(ConvertBlocked.UNREACHABLE)
            }
        }
    }

    fun confirm() {
        val confirm = _state.value as? PdfConvertState.Confirm ?: return
        _state.value = PdfConvertState.Preparing
        viewModelScope.launch {
            val started = repository.start(fileName, sizeBytes, confirm.maxBytes, allowMetered = confirm.metered, open)
            _state.value = when (started) {
                is ConversionStart.TooLarge -> PdfConvertState.TooLarge(started.maxBytes)
                ConversionStart.Unreadable -> PdfConvertState.Blocked(ConvertBlocked.UNREADABLE)
                ConversionStart.NoSpace -> PdfConvertState.Blocked(ConvertBlocked.NO_SPACE)
                ConversionStart.NoServer -> PdfConvertState.Blocked(ConvertBlocked.NO_SERVER)
                else -> PdfConvertState.Done(started)
            }
        }
    }
}

/** A shared or picked file's name and size, as its provider reports them. */
internal fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> = try {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null to null
        val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
        val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
        name?.takeIf { it.isNotBlank() } to size?.takeIf { it > 0 }
    } ?: (null to null)
} catch (e: RuntimeException) {
    null to null
}

/** True for a PDF, judged by the type the sender or provider gives and, failing that, the name. */
internal fun isPdf(type: String?, name: String?): Boolean =
    type == PDF_TYPE || (type.isNullOrBlank() || type == "application/octet-stream") && name?.endsWith(".pdf", ignoreCase = true) == true

internal const val PDF_TYPE = "application/pdf"
