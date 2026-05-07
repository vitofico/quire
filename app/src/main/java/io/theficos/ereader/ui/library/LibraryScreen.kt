package io.theficos.ereader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import io.theficos.ereader.data.sync.SyncEnqueuer
import androidx.compose.ui.unit.dp
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.ui.components.CoverImage
import io.theficos.ereader.ui.components.SectionLabel
import io.theficos.ereader.ui.theme.Lora

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (documentId: Long) -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SyncEnqueuer.enqueue(context, expedited = true) }

    val items by viewModel.items.collectAsState()
    val cont by viewModel.continueReading.collectAsState()
    var pendingDelete by remember { mutableStateOf<Document?>(null) }

    if (items.isEmpty()) {
        EmptyState(modifier = Modifier.padding(contentPadding))
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "Quire",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        cont?.let { row ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                ContinueReadingCard(row = row, onClick = { onOpenBook(row.document.id) })
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionLabel("Library · ${items.size}")
        }
        itemsIndexed(items, key = { _, r -> r.document.id }) { _, row ->
            Column(
                modifier = Modifier.combinedClickable(
                    onClick = { onOpenBook(row.document.id) },
                    onLongClick = { pendingDelete = row.document },
                ),
            ) {
                CoverImage(
                    source = row.document.coverPath,
                    title = row.document.title,
                    author = row.document.author,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f),
                )
                Text(
                    text = row.document.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    pendingDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete book?") },
            text = { Text("\"${doc.title}\" will be removed from your library and the downloaded file deleted. Reading progress will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(doc)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "q",
                fontFamily = Lora,
                style = MaterialTheme.typography.displaySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Text(
                text = "Your shelf is empty.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Open the Catalog tab to find books.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
