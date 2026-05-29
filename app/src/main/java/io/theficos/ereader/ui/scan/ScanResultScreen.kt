package io.theficos.ereader.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.theficos.ereader.data.library.AffinityResponse
import io.theficos.ereader.ui.bookdetail.InsightSection
import io.theficos.ereader.ui.bookdetail.InsightUiState
import io.theficos.ereader.ui.components.CoverImage
import io.theficos.ereader.ui.components.QuireCard

/**
 * Scan-result screen.
 *
 * Layout, top to bottom:
 *  - Header: cover (OpenLibrary cover-by-ISBN), title, author.
 *  - Owned banner (leads when the book is already in the user's library).
 *  - Affinity card: score + band + reason bullets, OR "not enough history yet"
 *    for the `unknown` band, OR an "affinity unavailable on this server" note
 *    when the affinity backend is mode-gated off.
 *  - "Get full insight" → triggers [ScanResultViewModel.loadInsight] and
 *    renders the shared [InsightSection] below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultScreen(
    viewModel: ScanResultViewModel,
    onBack: () -> Unit,
) {
    val data = viewModel.data
    val bundle = data.bundle
    val insight by viewModel.insight.collectAsState()
    val coverUrl = data.isbn13.takeIf { it.isNotBlank() }
        ?.let { "https://covers.openlibrary.org/b/isbn/$it-L.jpg" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan result") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                CoverImage(
                    source = coverUrl,
                    title = bundle.title,
                    author = bundle.author,
                    modifier = Modifier
                        .width(96.dp)
                        .height(144.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        bundle.title,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    bundle.author?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val owned = data.affinity?.owned
            if (owned != null) {
                OwnedBanner(readingStatus = owned.readingStatus)
            }

            when {
                data.affinityUnavailable -> AffinityUnavailableCard()
                data.affinity != null -> AffinityCard(data.affinity)
            }

            Button(
                onClick = { viewModel.loadInsight() },
                enabled = viewModel.insightAvailable() && insight !is InsightUiState.Loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Get full insight")
            }

            InsightSection(insight, onRetry = { viewModel.retryInsight() })

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OwnedBanner(readingStatus: String) {
    QuireCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column {
            Text("Already in your library", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Status: ${readingStatus.replace('_', ' ')}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AffinityCard(affinity: AffinityResponse) {
    QuireCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column {
            if (affinity.band == "unknown") {
                Text("Affinity", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Not enough history yet to score this for you.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            val header = buildString {
                append(affinity.band.replaceFirstChar { it.uppercase() })
                affinity.score?.let { append(" · $it") }
            }
            Text("Affinity", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(header, style = MaterialTheme.typography.titleMedium)
            if (affinity.reasons.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                affinity.reasons.forEach { reason ->
                    val marker = when (reason.polarity) {
                        "positive" -> "+"
                        "negative" -> "−"
                        else -> "•"
                    }
                    Text(
                        "$marker ${reason.message}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun AffinityUnavailableCard() {
    QuireCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column {
            Text("Affinity", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Affinity scoring isn't available on this server.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
