package io.theficos.ereader.ui.bookdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    viewModel: BookDetailViewModel,
    onOpenReader: (documentId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val doc = state.document

    Scaffold(
        topBar = { TopAppBar(title = { Text(doc?.title ?: "Book") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (doc != null) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(doc.title, style = MaterialTheme.typography.headlineSmall)
                    doc.author?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { onOpenReader(doc.id) }) { Text("Open in reader") }
                }
            }
            InsightSection(state.insight, onRetry = { viewModel.retry() })
            Spacer(Modifier.height(24.dp))
        }
    }
}
