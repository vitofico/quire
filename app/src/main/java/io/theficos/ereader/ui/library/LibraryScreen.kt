package io.theficos.ereader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.theficos.ereader.core.model.Document

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenCatalog: () -> Unit,
    onOpenBook: (documentId: Long) -> Unit,
) {
    val items by viewModel.items.collectAsState()
    var pendingDelete by remember { mutableStateOf<Document?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Library") }, actions = {
            TextButton(onClick = onOpenCatalog) { Text("Catalog") }
        })
    }) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No books yet. Download from the Catalog.")
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(items) { row ->
                    ListItem(
                        headlineContent = { Text(row.document.title) },
                        supportingContent = { Text("${(row.percent * 100).toInt()}%") },
                        trailingContent = {
                            IconButton(onClick = { pendingDelete = row.document }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenBook(row.document.id) }
                            .padding(horizontal = 8.dp),
                    )
                    HorizontalDivider()
                }
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
