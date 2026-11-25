package io.theficos.ereader.ui.convert

import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.data.library.convert.Conversion
import io.theficos.ereader.data.library.convert.ConversionRepository
import io.theficos.ereader.data.library.convert.ConversionStatus
import io.theficos.ereader.ui.components.QuireLoading
import io.theficos.quire.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversionsScreen(viewModel: ConversionsViewModel, onBack: () -> Unit, onOpenBook: (Long) -> Unit) {
    val conversions by viewModel.conversions.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.convert_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        val rows = conversions
        when {
            rows == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { QuireLoading() }
            rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.convert_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rows, key = { it.id }) { row ->
                    ConversionCard(
                        row,
                        onCancel = { viewModel.cancel(row.id) },
                        onRetry = { viewModel.retry(row.id) },
                        onRemove = { viewModel.remove(row.id) },
                        onOpenBook = onOpenBook,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversionCard(
    row: Conversion,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpenBook: (Long) -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    row.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                Text(
                    Formatter.formatShortFileSize(context, row.bytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(statusText(row), style = MaterialTheme.typography.bodyMedium)
            if (row.status in IN_FLIGHT) LinearProgressIndicator(Modifier.fillMaxWidth())
            waitingText(row)?.let { Secondary(it) }
            if (row.status == ConversionStatus.FAILED) {
                Secondary(stringResource(problemText(row.problem)))
                // The converter's own words, for codes where they say which stage or kind of PDF.
                row.detail?.takeIf { it.isNotBlank() }?.let { Secondary(it) }
            }
            reviewPages(row)?.let { Secondary(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (row.canCancel) TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                if (row.canRetry) TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                if (row.canRemove) TextButton(onClick = onRemove) { Text(stringResource(R.string.convert_remove)) }
                val book = row.documentId
                if (book != null && row.status in IN_LIBRARY) {
                    TextButton(onClick = { onOpenBook(book) }) { Text(stringResource(R.string.convert_open_book)) }
                }
            }
        }
    }
}

@Composable
private fun Secondary(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun statusText(row: Conversion): String = stringResource(
    when (row.status) {
        ConversionStatus.UPLOADING -> R.string.convert_status_uploading
        ConversionStatus.QUEUED -> R.string.convert_status_queued
        ConversionStatus.CONVERTING -> R.string.convert_status_converting
        ConversionStatus.IMPORTING -> R.string.convert_status_importing
        ConversionStatus.READY -> R.string.convert_status_ready
        ConversionStatus.REVIEW -> R.string.convert_status_review
        ConversionStatus.FAILED -> R.string.convert_status_failed
        ConversionStatus.CANCELLED -> R.string.convert_status_cancelled
    },
)

@Composable
private fun waitingText(row: Conversion): String? {
    // Waits for the reader, with no time set.
    if (row.problem == ConversionRepository.NEEDS_APP && row.status == ConversionStatus.UPLOADING) {
        return stringResource(R.string.convert_wait_open_app)
    }
    val until = row.waitingUntil ?: return null
    return when (row.problem) {
        ConversionRepository.TOO_MANY_ACTIVE_JOBS -> stringResource(R.string.convert_wait_busy)
        ConversionRepository.DAILY_LIMIT_REACHED -> {
            val context = LocalContext.current
            if (DateUtils.isToday(until)) {
                stringResource(R.string.convert_wait_daily_limit, DateFormat.getTimeFormat(context).format(until))
            } else {
                val at = DateUtils.formatDateTime(context, until, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
                stringResource(R.string.convert_wait_daily_limit_date, at)
            }
        }
        ConversionRepository.SIGNED_OUT -> stringResource(R.string.convert_wait_signed_out)
        ConversionRepository.UPLOAD_IN_PROGRESS -> stringResource(R.string.convert_wait_upload_in_progress)
        ConversionRepository.WAITING_UNMETERED -> stringResource(R.string.convert_wait_unmetered)
        ConversionRepository.CONVERTER_UNAVAILABLE -> stringResource(R.string.convert_wait_converter)
        else -> stringResource(R.string.convert_wait_network)
    }
}

@Composable
private fun reviewPages(row: Conversion): String? {
    val pages = row.review?.affectedPages?.takeIf { it.isNotEmpty() } ?: return null
    // Printed labels can differ from the page count (a preface in roman numerals, say).
    val list = pages.joinToString(", ") { p ->
        val label = p.label?.trim()?.takeIf { it.isNotEmpty() && it != p.page.toString() }
        if (label != null) "${p.page} ($label)" else p.page.toString()
    }
    return pluralStringResource(R.plurals.convert_review_pages, pages.size, list)
}

@StringRes
internal fun problemText(problem: String?): Int = when (problem) {
    "encrypted" -> R.string.convert_failed_encrypted
    "too_many_pages" -> R.string.convert_failed_too_many_pages
    "unreadable_pdf" -> R.string.convert_failed_unreadable_pdf
    "unsupported" -> R.string.convert_failed_unsupported
    "timed_out" -> R.string.convert_failed_timed_out
    "worker_error", "worker_lost", "invalid_result" -> R.string.convert_failed_server
    ConversionRepository.HOSTING_SUBSCRIPTION_REQUIRED -> R.string.convert_failed_plan
    ConversionRepository.SOURCE_TOO_LARGE -> R.string.convert_failed_too_large
    ConversionRepository.NOT_A_PDF -> R.string.convert_failed_not_a_pdf
    ConversionRepository.CONVERTER_UNAVAILABLE -> R.string.convert_failed_unavailable
    ConversionRepository.SOURCE_MISSING -> R.string.convert_failed_source_missing
    ConversionRepository.UPLOAD_FAILED -> R.string.convert_failed_upload
    ConversionRepository.DOWNLOAD_FAILED -> R.string.convert_failed_download
    ConversionRepository.IMPORT_FAILED -> R.string.convert_failed_import
    ConversionRepository.INVALID_EPUB -> R.string.convert_failed_invalid_epub
    ConversionRepository.RESULT_GONE, ConversionRepository.JOB_GONE,
    ConversionRepository.IDEMPOTENCY_CONFLICT -> R.string.convert_failed_gone
    ConversionRepository.CONVERSION_OFF -> R.string.convert_failed_off
    "conversion_failed", ConversionRepository.FAILED -> R.string.convert_failed_conversion
    else -> R.string.convert_failed_refused
}

/** The library's line about conversions under way or needing the reader, or nothing. */
@Composable
fun ConversionsBanner(conversions: List<Conversion>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (!conversions.showsBanner) return
    val active = conversions.count { it.status in IN_FLIGHT }
    Card(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                if (active > 0) pluralStringResource(R.plurals.convert_banner_active, active, active)
                else stringResource(R.string.convert_banner_attention),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** True while a conversion is under way or one failed and waits for the reader. */
val List<Conversion>.showsBanner: Boolean
    get() = any { it.status in IN_FLIGHT || it.status == ConversionStatus.FAILED }

private val IN_FLIGHT = setOf(
    ConversionStatus.UPLOADING, ConversionStatus.QUEUED, ConversionStatus.CONVERTING, ConversionStatus.IMPORTING,
)
private val IN_LIBRARY = setOf(ConversionStatus.READY, ConversionStatus.REVIEW)
