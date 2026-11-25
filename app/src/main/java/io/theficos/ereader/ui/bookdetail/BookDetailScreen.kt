package io.theficos.ereader.ui.bookdetail

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.data.ai.shownWith
import io.theficos.ereader.ui.components.BookDescription
import io.theficos.ereader.ui.components.BookHeader
import io.theficos.ereader.ui.components.QuireCard
import io.theficos.ereader.ui.components.SectionLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.ui.res.stringResource
import io.theficos.quire.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    viewModel: BookDetailViewModel,
    onOpenReader: (documentId: Long) -> Unit,
    onInspectInsight: (documentId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val doc = state.document
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    val details = state.details
                    val hasInsight = state.insight !is InsightUiState.Hidden
                    val stored = details.stored
                    if (hasInsight || details.available || stored != null) {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.common_more))
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            if (hasInsight) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.common_inspect_insight)) },
                                    enabled = doc != null,
                                    onClick = {
                                        menuExpanded = false
                                        doc?.let { onInspectInsight(it.id) }
                                    },
                                )
                            }
                            if (details.available) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.details_tidy)) },
                                    enabled = doc != null && !details.tidying,
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.tidyDetails()
                                    },
                                )
                            }
                            // Offered even with AI off, so found details can always be put away.
                            if (stored != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (stored.useOriginal) R.string.details_use_found else R.string.details_use_original,
                                            ),
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.useOriginalDetails(!stored.useOriginal)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        BookDetailContent(
            state = state,
            onOpenReader = onOpenReader,
            onRetryInsight = viewModel::retry,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailContent(
    state: BookDetailState,
    onOpenReader: ((Long) -> Unit)?,
    onRetryInsight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val own = state.document ?: return
    val found = state.details.shown
    // The found details over the book's own, when the reader kept them.
    val document = own.shownWith(found)
    val metadata = found?.let { foundMetadata(state.metadata, own, it) } ?: state.metadata
    val progress = state.progress
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault())
    }
    val formatDate: (Long) -> String = { dateFormatter.format(Instant.ofEpochMilli(it)) }
    val now = System.currentTimeMillis()
    val facts = bookFacts(
        metadata = metadata,
        document = document,
        progress = progress,
        fileSizeBytes = state.fileSizeBytes,
        formatDate = formatDate,
        formatRelative = {
            DateUtils.getRelativeTimeSpanString(it, now, DateUtils.DAY_IN_MILLIS).toString()
        },
        formatSize = { Formatter.formatShortFileSize(context, it) },
        displayLocale = locale,
        origins = found?.response?.origins.orEmpty(),
    )

    Column(modifier = modifier) {
        BookHeader(
            coverSource = document.coverPath,
            title = document.title,
            author = document.author?.takeIf { it.isNotBlank() } ?: metadata?.author,
            series = seriesLine(
                document.seriesName?.takeIf { it.isNotBlank() } ?: metadata?.seriesName,
                document.seriesIndex ?: metadata?.seriesPosition?.toDouble(),
            )?.asString(),
            notes = found?.let { headerNotes(own, it) }.orEmpty().map { stringResource(it) },
        )

        if (state.details.tidying) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.details_tidying), style = MaterialTheme.typography.bodySmall)
            }
        }
        state.details.outcome?.let { outcome ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = outcome.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        if (progress != null && progress.percent > 0 &&
            progress.finishedAt == null && progress.abandonedAt == null
        ) {
            LinearProgressIndicator(
                progress = { progress.percent.toFloat() },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = readingStatus(progress, formatDate).asString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (onOpenReader != null) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onOpenReader(document.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                val label = when {
                    progress?.finishedAt != null -> stringResource(R.string.book_open)
                    progress?.abandonedAt != null || (progress?.percent ?: 0.0) > 0 -> stringResource(R.string.common_continue_reading)
                    else -> stringResource(R.string.book_start)
                }
                Text(label)
            }
        }

        if (!metadata?.description.isNullOrBlank()) {
            Spacer(Modifier.height(24.dp))
            if (metadata?.description != state.metadata?.description) {
                found?.response?.origins?.get("description")?.let(::originNote)?.let { note ->
                    Text(
                        text = stringResource(note),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            BookDescription(metadata?.description)
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.common_details))
        Spacer(Modifier.height(8.dp))
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                facts.forEach { fact ->
                    Row(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        Text(
                            text = stringResource(fact.label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(96.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = fact.value,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = if (fact.label == R.string.book_subjects) 3 else Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
                            )
                            fact.note?.let { note ->
                                Text(
                                    text = stringResource(note),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.insight !is InsightUiState.Hidden) {
            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.common_ai_insights))
            Spacer(Modifier.height(8.dp))
            InsightSection(state.insight, onRetry = onRetryInsight)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailSheet(viewModel: BookDetailViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            BookDetailContent(state, onOpenReader = null, onRetryInsight = viewModel::retry)
        }
    }
}
