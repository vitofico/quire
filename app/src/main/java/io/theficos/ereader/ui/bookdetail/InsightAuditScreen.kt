package io.theficos.ereader.ui.bookdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.theficos.ereader.data.ai.AiStyle
import io.theficos.ereader.data.ai.BookInsightResponse
import io.theficos.ereader.data.ai.Citation
import io.theficos.ereader.ui.components.QuireCard
import java.time.Instant
import java.time.format.DateTimeParseException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightAuditScreen(
    viewModel: InsightAuditViewModel,
    onBack: () -> Unit,
    onInvalidated: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmOpen by rememberSaveable { mutableStateOf(false) }

    // Surface invalidate failures as snackbars; on success let the navigation
    // layer pop back via [onInvalidated].
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is InsightAuditViewModel.Event.InvalidateFailed ->
                    snackbarHostState.showSnackbar(event.message)
                InsightAuditViewModel.Event.Invalidated -> Unit
            }
        }
    }
    LaunchedEffect(state) {
        if (state is InsightAuditViewModel.State.Done) onInvalidated()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inspect insight") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                InsightAuditViewModel.State.Loading -> CenteredProgress()
                is InsightAuditViewModel.State.Error -> ErrorView(
                    message = s.message,
                    onRetry = { viewModel.retry() },
                )
                InsightAuditViewModel.State.NotCached -> NotCachedView()
                is InsightAuditViewModel.State.Loaded -> LoadedView(
                    response = s.response,
                    currentStyle = s.currentStyle,
                    invalidating = false,
                    onInvalidate = { confirmOpen = true },
                )
                InsightAuditViewModel.State.Invalidating -> {
                    // Render the last-known content area dimmed by overlaying
                    // a progress indicator; the underlying Loaded composition
                    // is gone, so show a centered spinner with helper text.
                    CenteredProgress(label = "Invalidating…")
                }
                InsightAuditViewModel.State.Done -> {
                    // Transient — onInvalidated() pops back. Render nothing.
                }
            }
        }
    }

    if (confirmOpen) {
        InvalidateConfirmDialog(
            onDismiss = { confirmOpen = false },
            onConfirm = {
                confirmOpen = false
                viewModel.invalidate()
            },
        )
    }
}

@Composable
private fun CenteredProgress(label: String? = null) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        if (label != null) {
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun NotCachedView() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No insight cached for this book yet. Open the book detail to generate one.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LoadedView(
    response: BookInsightResponse,
    currentStyle: AiStyle?,
    invalidating: Boolean,
    onInvalidate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CacheKeyCard(response)
        StyleCard(currentStyle)
        GeneratedCard(response.generatedAt)
        SourcesCard(response.sources)

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            enabled = !invalidating,
            onClick = onInvalidate,
        ) { Text("Invalidate cached insight") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CacheKeyCard(response: BookInsightResponse) {
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("Cache key (from server)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            LabeledRow("Model", response.modelId)
            LabeledRow("Prompt ver.", response.promptVersion)
            LabeledRow("Schema ver.", response.payload.schemaVersion.toString())
        }
    }
}

@Composable
private fun StyleCard(style: AiStyle?) {
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("Your current AI style", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            LabeledRow("Tone", style?.tone ?: "—")
            LabeledRow("Language", style?.language ?: "—")
        }
    }
}

@Composable
private fun GeneratedCard(generatedAt: String) {
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("Generated", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            val rel = formatRelative(generatedAt)
            if (rel != null) {
                Text(rel, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "($generatedAt)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Text(
                    generatedAt,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun SourcesCard(sources: List<Citation>) {
    val uriHandler = LocalUriHandler.current
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("Sources (${sources.size})", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (sources.isEmpty()) {
                Text(
                    "No sources recorded.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                sources.forEach { citation ->
                    Spacer(Modifier.height(4.dp))
                    SourceRow(citation, onOpen = { uriHandler.openUri(it) })
                }
            }
        }
    }
}

@Composable
private fun SourceRow(citation: Citation, onOpen: (String) -> Unit) {
    val label = citationLabel(citation)
    val url = citationUrl(citation)
    Column {
        Text("· $label", style = MaterialTheme.typography.bodyMedium)
        if (url != null) {
            val annotated = buildAnnotatedString {
                pushStringAnnotation("URL", url)
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                    append(url)
                }
                pop()
            }
            ClickableText(
                text = annotated,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                onClick = { offset ->
                    annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                        onOpen(it.item)
                    }
                },
            )
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun InvalidateConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invalidate insight?") },
        text = {
            Text(
                "Invalidating this insight removes the cached AI response. " +
                    "Returning to this book detail will generate a fresh insight, " +
                    "which may take a few seconds and uses one of your daily generations.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Invalidate") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Citation kind → human-readable label. Mirrors [InsightSection]'s
 * `SourcesFooter` mapping (keep the two in sync; the audit screen renders
 * sources as a list rather than inline text).
 */
internal fun citationLabel(c: Citation): String = when (c.kind) {
    "wikipedia" -> "Wikipedia"
    "openlibrary" -> "OpenLibrary"
    "model" -> "AI model: ${c.title}"
    "opf" -> "Book metadata"
    else -> c.title
}

/** URL exposed in the source row, or null if the citation kind has no URL. */
internal fun citationUrl(c: Citation): String? = when (c.kind) {
    "model", "opf" -> null
    else -> c.url
}

/**
 * Render an ISO-8601 timestamp as a human-friendly relative string ("3h
 * ago"). Returns null when the input cannot be parsed; callers fall back
 * to displaying the raw value.
 */
internal fun formatRelative(iso: String, nowMs: Long = System.currentTimeMillis()): String? {
    val epoch = parseIsoOrNull(iso) ?: return null
    val deltaSec = (nowMs - epoch) / 1000
    return when {
        deltaSec < 0 -> "just now"
        deltaSec < 60 -> "just now"
        deltaSec < 3600 -> "${deltaSec / 60}m ago"
        deltaSec < 86_400 -> "${deltaSec / 3600}h ago"
        else -> "${deltaSec / 86_400}d ago"
    }
}

private fun parseIsoOrNull(iso: String): Long? = try {
    Instant.parse(iso).toEpochMilli()
} catch (_: DateTimeParseException) {
    null
}
