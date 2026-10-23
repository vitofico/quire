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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.ai.BookRecDto
import io.theficos.ereader.data.ai.ReaderProfileResponseDto
import io.theficos.quire.R
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
    LibraryInsightsScreenContent(
        state = state,
        onBack = onBack,
        onOpenBook = onOpenBook,
        onOpenWeb = onOpenWeb,
        onOpenSettings = onOpenSettings,
        onRefresh = { viewModel.refresh() },
    )
}

/**
 * Stateless overload — drives the screen straight off a [LibraryInsightsUiState]
 * value so unit/UI tests can render any state without constructing a real
 * [LibraryInsightsViewModel] (whose deps include AiRepository, LibraryClient,
 * ProgressDao, …). Production wiring goes through the VM-flavored
 * `LibraryInsightsScreen` above.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryInsightsScreenContent(
    state: LibraryInsightsUiState,
    onBack: () -> Unit,
    onOpenBook: (DocumentIdentity) -> Unit,
    onOpenWeb: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_library_insights)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            is LibraryInsightsUiState.Disabled ->
                DisabledScreen(padding, s, onOpenSettings = onOpenSettings)
            is LibraryInsightsUiState.Empty ->
                EmptyScreen(padding, s, onGenerate = onRefresh)
            is LibraryInsightsUiState.Loading ->
                LoadingScreen(padding, s)
            is LibraryInsightsUiState.Loaded ->
                LoadedScreen(
                    padding = padding,
                    loaded = s,
                    onRefresh = onRefresh,
                    onOpenBook = onOpenBook,
                    onOpenWeb = onOpenWeb,
                )
            is LibraryInsightsUiState.Error ->
                ErrorScreen(padding, s, onRetry = onRefresh)
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.message.asString(), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.common_open_ai_settings)) }
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
            Text(stringResource(R.string.library_generate_your_reader_profile), style = MaterialTheme.typography.headlineSmall)
        }
        item {
            val host = state.configHostLabel.ifBlank { stringResource(R.string.common_your_ai_host) }
            val model = state.modelId.ifBlank { stringResource(R.string.common_the_configured_model) }
            Text(
                stringResource(R.string.library_we_ll_send_a_summary_of_your, host, model),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            val preview = state.statsPreview
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.common_we_ll_consider), style = MaterialTheme.typography.titleMedium)
                    DisclosureBullet(pluralStringResource(R.plurals.library_books_in_your_library, preview?.totalBooks ?: 0, preview?.totalBooks?.toString() ?: "—"))
                    DisclosureBullet(pluralStringResource(R.plurals.common_finished, preview?.finished ?: 0, preview?.finished?.toString() ?: "—"))
                    DisclosureBullet(stringResource(R.string.common_in_progress, preview?.inProgress?.toString() ?: "—"))
                    val authors = preview?.topAuthors?.takeIf { it.isNotEmpty() }?.joinToString(" · ")
                    DisclosureBullet(stringResource(R.string.common_top_authors, authors ?: "—"))
                }
            }
        }
        item {
            OutlinedButton(onClick = onGenerate) { Text(stringResource(R.string.common_generate_insights)) }
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
        LibraryInsightsUiState.Loading.PreflightSyncing -> stringResource(R.string.common_syncing_your_library)
        LibraryInsightsUiState.Loading.Generating -> stringResource(R.string.library_generating_your_profile_30s)
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
                    stringResource(R.string.library_profile_may_be_out_of_date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (loaded.preflightFailed) {
            item {
                Text(
                    stringResource(R.string.library_latest_changes_could_not_sync_recommendations_may),
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
            item { Text(stringResource(R.string.common_from_your_library), style = MaterialTheme.typography.titleMedium) }
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
            item { Text(stringResource(R.string.common_discovery), style = MaterialTheme.typography.titleMedium) }
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
        val aiSuggested = loaded.profile.payload.aiSuggestedRecommendations
        if (aiSuggested.isNotEmpty()) {
            item { Text(stringResource(R.string.common_suggested_by_ai), style = MaterialTheme.typography.titleMedium) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(aiSuggested) { rec ->
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
                stringResource(R.string.library_last_generated, relativeTime(loaded.profile.generatedAt), loaded.profile.modelId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = !loaded.refreshing) { Text(stringResource(R.string.common_refresh)) }
        }
        loaded.refreshedAt?.let {
            Text(
                stringResource(R.string.common_profile_refreshed, relativeFromNow(it)),
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.common_your_reading), style = MaterialTheme.typography.titleMedium)
            StatRow(stringResource(R.string.common_books_2), stats.totalBooks.toString())
            StatRow(stringResource(R.string.common_finished_2), stats.finishedCount.toString())
            StatRow(stringResource(R.string.common_in_progress_2), stats.inProgressCount.toString())
            StatRow(stringResource(R.string.common_abandoned), stats.abandonedCount.toString())
            val authors = stats.mostReadAuthors.take(5).joinToString(" · ") { it.name }
            if (authors.isNotBlank()) {
                Text(
                    stringResource(R.string.common_top_authors, authors),
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
                    stringResource(R.string.common_top_themes, themes),
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
            pluralStringResource(R.plurals.library_themes_available_for_of_books, profile.payload.stats.totalBooks, with, profile.payload.stats.totalBooks),
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.common_about_your_reading), style = MaterialTheme.typography.titleMedium)
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
                        ) { Text(stringResource(R.string.common_open_in_library)) }
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
                            Text(stringResource(R.string.common_open_in_browser))
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
        stringResource(R.string.common_generated_by, profile.generatedAt, profile.modelId),
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
            stringResource(R.string.library_couldn_t_reach_the_server) to error.detail
        LibraryInsightsUiState.Error.Timeout ->
            stringResource(R.string.library_the_server_took_too_long_to_answer) to stringResource(R.string.library_it_may_still_be_generating_try_again)
        is LibraryInsightsUiState.Error.RateLimit ->
            stringResource(R.string.library_daily_refresh_limit_reached) to stringResource(R.string.common_try_again_tomorrow)
        LibraryInsightsUiState.Error.ModelFailure ->
            stringResource(R.string.library_the_ai_model_returned_an_error) to stringResource(R.string.library_try_refresh_in_a_few_minutes)
        LibraryInsightsUiState.Error.Unknown ->
            stringResource(R.string.common_something_went_wrong) to stringResource(R.string.common_try_again)
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
                TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
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

private fun relativeFromNow(at: Instant, now: Instant = Instant.now()): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(
        at.toEpochMilli(), now.toEpochMilli(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
