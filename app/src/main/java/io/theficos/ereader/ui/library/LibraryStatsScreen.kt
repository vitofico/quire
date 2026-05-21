package io.theficos.ereader.ui.library

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.theficos.ereader.data.library.LibraryStatsResponse
import io.theficos.ereader.data.library.TopAuthor
import io.theficos.ereader.data.library.TopTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryStatsScreen(
    viewModel: LibraryStatsViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library stats") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            is LibraryStatsUiState.Loading -> LoadingState(padding)
            is LibraryStatsUiState.Error -> ErrorState(padding, s.message, onRetry = { viewModel.load() })
            is LibraryStatsUiState.Ready -> ReadyState(padding, s.stats)
        }
    }
}

@Composable
private fun LoadingState(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) { Text("Loading…") }
}

@Composable
private fun ErrorState(padding: PaddingValues, message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun ReadyState(padding: PaddingValues, stats: LibraryStatsResponse) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            // 2×2 grid — Books / Finished / Reading / Abandoned. PR-9
            // added Abandoned; 4 cards in one row are too narrow on phones,
            // 2×2 scales down cleanly and leaves room for future tiles.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CountCard(label = "Books", value = stats.totalBooks, modifier = Modifier.weight(1f))
                    CountCard(label = "Finished", value = stats.finishedCount, modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CountCard(label = "Reading", value = stats.inProgressCount, modifier = Modifier.weight(1f))
                    CountCard(label = "Abandoned", value = stats.abandonedCount, modifier = Modifier.weight(1f))
                }
            }
        }
        item { SectionHeading("Top authors") }
        if (stats.topAuthors.isEmpty()) {
            item { PlaceholderText("No authors yet — add books to your library.") }
        } else {
            items(stats.topAuthors) { TopAuthorRow(it) }
        }
        item { SectionHeading("Top themes") }
        item {
            Text(
                stats.themesCaveat,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (stats.topThemes.isEmpty()) {
            item { PlaceholderText("No themes yet — open a book to generate insights, or regenerate older ones.") }
        } else {
            items(stats.topThemes) { TopThemeRow(it) }
        }
    }
}

@Composable
private fun CountCard(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = value.toString(), style = MaterialTheme.typography.displaySmall)
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun PlaceholderText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TopAuthorRow(a: TopAuthor) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(a.name, style = MaterialTheme.typography.bodyLarge)
        Text(a.count.toString(), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TopThemeRow(t: TopTheme) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(t.theme.replace('_', ' '), style = MaterialTheme.typography.bodyLarge)
        Text(t.count.toString(), style = MaterialTheme.typography.bodyLarge)
    }
}
