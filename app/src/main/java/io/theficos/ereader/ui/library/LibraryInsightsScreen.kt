package io.theficos.ereader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.ai.BookRecDto
import io.theficos.ereader.data.ai.ReaderProfileResponseDto
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryInsightsScreen(
    viewModel: LibraryInsightsViewModel,
    onBack: () -> Unit,
    onOpenBook: (DocumentIdentity) -> Unit,
    onOpenWeb: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.reload() }
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library insights") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            is LibraryInsightsUiState.Disabled ->
                DisabledScreen(padding, s, onOpenSettings = onOpenSettings)
            is LibraryInsightsUiState.Empty ->
                EmptyScreen(padding, s, onGenerate = { viewModel.refresh() })
            is LibraryInsightsUiState.Loading ->
                LoadingScreen(padding, s)
            is LibraryInsightsUiState.Loaded ->
                LoadedScreen(
                    padding = padding,
                    loaded = s,
                    onRefresh = { viewModel.refresh() },
                    onOpenBook = onOpenBook,
                    onOpenWeb = onOpenWeb,
                )
            is LibraryInsightsUiState.Error ->
                ErrorScreen(padding, s, onRetry = { viewModel.refresh() })
        }
    }
}

// --------------------------------------------------------------------------
// Disabled
// --------------------------------------------------------------------------

@Composable
private fun DisabledScreen(
    padding: PaddingValues,
    state: LibraryInsightsUiState.Disabled,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.message, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onOpenSettings) { Text("Open AI settings") }
            }
        }
    }
}

// --------------------------------------------------------------------------
// Empty
// --------------------------------------------------------------------------

@Composable
private fun EmptyScreen(
    padding: PaddingValues,
    state: LibraryInsightsUiState.Empty,
    onGenerate: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Generate your reader profile", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            val host = state.configHostLabel.ifBlank { "your AI host" }
            val model = state.modelId.ifBlank { "the configured model" }
            Text(
                "We'll send a summary of your library to $host using $model. " +
                    "Your reading data stays under your control.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            val preview = state.statsPreview
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("We'll consider:", style = MaterialTheme.typography.titleMedium)
                    DisclosureBullet("${preview?.totalBooks?.toString() ?: "—"} books in your library")
                    DisclosureBullet("${preview?.finished?.toString() ?: "—"} finished")
                    DisclosureBullet("${preview?.inProgress?.toString() ?: "—"} in progress")
                    val authors = preview?.topAuthors?.takeIf { it.isNotEmpty() }?.joinToString(" · ")
                    DisclosureBullet("Top authors: ${authors ?: "—"}")
                }
            }
        }
        item {
            OutlinedButton(onClick = onGenerate) { Text("Generate insights") }
        }
    }
}

@Composable
private fun DisclosureBullet(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("• ", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// --------------------------------------------------------------------------
// Loading
// --------------------------------------------------------------------------

@Composable
private fun LoadingScreen(padding: PaddingValues, state: LibraryInsightsUiState.Loading) {
    val label = when (state) {
        LibraryInsightsUiState.Loading.PreflightSyncing -> "Syncing your library…"
        LibraryInsightsUiState.Loading.Generating -> "Generating your profile… (~30s)"
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// --------------------------------------------------------------------------
// Loaded
// --------------------------------------------------------------------------

@Composable
private fun LoadedScreen(
    padding: PaddingValues,
    loaded: LibraryInsightsUiState.Loaded,
    onRefresh: () -> Unit,
    onOpenBook: (DocumentIdentity) -> Unit,
    onOpenWeb: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (loaded.refreshing) {
            item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        item { HeaderRow(loaded = loaded, onRefresh = onRefresh) }
        if (loaded.stale) {
            item {
                Text(
                    "Profile may be out of date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (loaded.preflightFailed) {
            item {
                Text(
                    "Latest changes could not sync — recommendations may not " +
                        "reflect your most recent reading.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { ReadingStatsCard(loaded.profile) }
        item { CoverageMeter(loaded.profile) }
        loaded.profile.payload.narrative?.takeIf { it.isNotBlank() }?.let { narrative ->
            item { NarrativeCard(narrative) }
        }
        val inLibrary = loaded.profile.payload.inLibraryRecommendations
        if (inLibrary.isNotEmpty()) {
            item { Text("From your library", style = MaterialTheme.typography.titleMedium) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(inLibrary) { rec ->
                        BookRecCard(
                            rec = rec,
                            onOpenBook = onOpenBook,
                            onOpenWeb = onOpenWeb,
                        )
                    }
                }
            }
        }
        val discovery = loaded.profile.payload.discoveryRecommendations
        if (discovery.isNotEmpty()) {
            item { Text("Discovery", style = MaterialTheme.typography.titleMedium) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(discovery) { rec ->
                        BookRecCard(
                            rec = rec,
                            onOpenBook = onOpenBook,
                            onOpenWeb = onOpenWeb,
                        )
                    }
                }
            }
        }
        item { FooterAttribution(loaded.profile) }
    }
}

@Composable
private fun HeaderRow(
    loaded: LibraryInsightsUiState.Loaded,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Last generated ${relativeTime(loaded.profile.generatedAt)} • ${loaded.profile.modelId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = !loaded.refreshing) { Text("Refresh") }
        }
        loaded.refreshedAt?.let {
            Text(
                "Profile refreshed ${relativeFromNow(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReadingStatsCard(profile: ReaderProfileResponseDto) {
    val stats = profile.payload.stats
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Your reading", style = MaterialTheme.typography.titleMedium)
            StatRow("Books", stats.totalBooks.toString())
            StatRow("Finished", stats.finishedCount.toString())
            StatRow("In progress", stats.inProgressCount.toString())
            StatRow("Abandoned", stats.abandonedCount.toString())
            val authors = stats.mostReadAuthors.take(5).joinToString(" · ") { it.name }
            if (authors.isNotBlank()) {
                Text(
                    "Top authors: $authors",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val themes = stats.finishRateByTheme.entries
                .sortedByDescending { it.value }
                .take(5)
                .joinToString(" · ") { it.key.replace('_', ' ') }
            if (themes.isNotBlank()) {
                Text(
                    "Top themes: $themes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CoverageMeter(profile: ReaderProfileResponseDto) {
    val with = profile.payload.stats.booksWithThemesCount
    val total = profile.payload.stats.totalBooks.coerceAtLeast(1)
    val fraction = (with.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Themes available for $with of ${profile.payload.stats.totalBooks} books",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NarrativeCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("About your reading", style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BookRecCard(
    rec: BookRecDto,
    onOpenBook: (DocumentIdentity) -> Unit,
    onOpenWeb: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.width(220.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                rec.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                rec.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                rec.rationale,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                when {
                    rec.sourceType == "in_library" && rec.identity != null -> {
                        OutlinedButton(
                            onClick = { onOpenBook(rec.identity!!) },
                        ) { Text("Open in library") }
                    }
                    rec.sourceType == "discovery_openlibrary" &&
                        rec.sourceUrl?.startsWith("https://") == true -> {
                        OutlinedButton(onClick = { onOpenWeb(rec.sourceUrl!!) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.width(16.dp).height(16.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open in browser")
                        }
                        Text(
                            extractHost(rec.sourceUrl!!),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // ai_suggested or fallback: rationale only.
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun FooterAttribution(profile: ReaderProfileResponseDto) {
    Text(
        "Generated ${profile.generatedAt} by ${profile.modelId}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// --------------------------------------------------------------------------
// Error
// --------------------------------------------------------------------------

@Composable
private fun ErrorScreen(
    padding: PaddingValues,
    error: LibraryInsightsUiState.Error,
    onRetry: () -> Unit,
) {
    val (title, body) = when (error) {
        is LibraryInsightsUiState.Error.Network ->
            "Couldn't reach the server" to error.detail
        is LibraryInsightsUiState.Error.RateLimit ->
            "Daily refresh limit reached" to "Try again tomorrow."
        LibraryInsightsUiState.Error.ModelFailure ->
            "The AI model returned an error" to "Try refresh in a few minutes."
        LibraryInsightsUiState.Error.Unknown ->
            "Something went wrong" to "Try again."
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error.retryable) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

// --------------------------------------------------------------------------
// Helpers
// --------------------------------------------------------------------------

internal fun extractHost(url: String): String {
    val afterScheme = url.substringAfter("://", missingDelimiterValue = url)
    return afterScheme.substringBefore('/')
}

private fun relativeTime(iso: String): String =
    try {
        relativeFromNow(Instant.parse(iso))
    } catch (_: DateTimeParseException) {
        iso
    }

private fun relativeFromNow(at: Instant, now: Instant = Instant.now()): String {
    val d = Duration.between(at, now)
    val abs = d.abs()
    val mins = abs.toMinutes()
    val hours = abs.toHours()
    val days = abs.toDays()
    val tense = if (d.isNegative) "in" else "ago"
    val amount = when {
        days >= 1 -> "${days}d"
        hours >= 1 -> "${hours}h"
        mins >= 1 -> "${mins}m"
        else -> "moments"
    }
    return if (d.isNegative) "$tense $amount" else "$amount $tense"
}
