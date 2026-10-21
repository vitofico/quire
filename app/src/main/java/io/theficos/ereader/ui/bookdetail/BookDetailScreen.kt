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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.ui.components.BookDescription
import io.theficos.ereader.ui.components.BookHeader
import io.theficos.ereader.ui.components.QuireCard
import io.theficos.ereader.ui.components.SectionLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.insight !is InsightUiState.Hidden) {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Inspect insight") },
                                enabled = doc != null,
                                onClick = {
                                    menuExpanded = false
                                    doc?.let { onInspectInsight(it.id) }
                                },
                            )
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
    val document = state.document ?: return
    val metadata = state.metadata
    val progress = state.progress
    val context = LocalContext.current
    val dateFormatter = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
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
    )

    Column(modifier = modifier) {
        BookHeader(
            coverSource = document.coverPath,
            title = document.title,
            author = document.author?.takeIf { it.isNotBlank() } ?: metadata?.author,
            series = seriesLine(
                document.seriesName?.takeIf { it.isNotBlank() } ?: metadata?.seriesName,
                document.seriesIndex ?: metadata?.seriesPosition?.toDouble(),
            ),
        )

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
            text = readingStatus(progress, formatDate),
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
                    progress?.finishedAt != null -> "Open book"
                    progress?.abandonedAt != null || (progress?.percent ?: 0.0) > 0 -> "Continue reading"
                    else -> "Start reading"
                }
                Text(label)
            }
        }

        if (!metadata?.description.isNullOrBlank()) {
            Spacer(Modifier.height(24.dp))
            BookDescription(metadata?.description)
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("Details")
        Spacer(Modifier.height(8.dp))
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                facts.forEach { fact ->
                    Row(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        Text(
                            text = fact.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(96.dp),
                        )
                        Text(
                            text = fact.value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = if (fact.label == "Subjects") 3 else Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (state.insight !is InsightUiState.Hidden) {
            Spacer(Modifier.height(24.dp))
            SectionLabel("AI insights")
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
