package io.theficos.ereader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.data.sync.SyncEnqueuer
import io.theficos.ereader.ui.components.CoverImage
import io.theficos.ereader.ui.components.SectionLabel
import io.theficos.ereader.ui.theme.Lora

private val sortLabels: List<Pair<LibrarySort, String>> = listOf(
    LibrarySort.RECENTLY_READ to "Recently read",
    LibrarySort.RECENTLY_ADDED to "Recently added",
    LibrarySort.TITLE to "Title",
    LibrarySort.AUTHOR to "Author",
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (documentId: Long) -> Unit,
    onShowDetails: (documentId: Long) -> Unit = {},
    aiConfigured: Boolean = false,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SyncEnqueuer.enqueue(context, expedited = true) }

    val items by viewModel.items.collectAsState()
    val cont by viewModel.continueReading.collectAsState()
    val seriesCandidates by viewModel.seriesContinuationCandidates.collectAsState()
    var menuFor by remember { mutableStateOf<Document?>(null) }
    var pendingDelete by remember { mutableStateOf<Document?>(null) }
    var pendingRestart by remember { mutableStateOf<Document?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val query by viewModel.query.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                LibraryEvent.RestartFailed ->
                    snackbarHostState.showSnackbar("Couldn't sync restart — will retry.")
            }
        }
    }

    if (items.isEmpty() && !searchActive && query.isBlank()) {
        EmptyState(modifier = Modifier.padding(contentPadding))
        return
    }

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Quire",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { searchActive = true }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    var sortMenuOpen by rememberSaveable { mutableStateOf(false) }
                    val currentSort by viewModel.sort.collectAsState()
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            sortLabels.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = if (currentSort == key) {
                                        { Icon(Icons.Filled.Check, contentDescription = null) }
                                    } else null,
                                    onClick = {
                                        viewModel.setSort(key)
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            cont?.let { row ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ContinueReadingCard(row = row, onClick = { onOpenBook(row.document.id) })
                }
            }
            if (seriesCandidates.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SeriesContinuationShelf(
                        books = seriesCandidates,
                        onBookClick = { onOpenBook(it) },
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (searchActive) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { viewModel.setQuery(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search library") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            imeAction = ImeAction.Search,
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                viewModel.setQuery("")
                                searchActive = false
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close search")
                            }
                        },
                    )
                } else {
                    SectionLabel("Library · ${items.size}")
                }
            }
            itemsIndexed(items, key = { _, r -> r.document.id }) { _, row ->
                Column(
                    modifier = Modifier.combinedClickable(
                        onClick = { onOpenBook(row.document.id) },
                        onLongClick = { menuFor = row.document },
                    ),
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        CoverImage(
                            source = row.document.coverPath,
                            title = row.document.title,
                            author = row.document.author,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f),
                        )
                        // Info icon: top-left, tap target for AI insights. Only when
                        // AI is configured server-side; suppressed otherwise to keep
                        // tiles clean.
                        if (aiConfigured) {
                            IconButton(
                                onClick = { onShowDetails(row.document.id) },
                                modifier = Modifier.align(Alignment.TopStart),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = "Book details and AI insights",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        // Finished badge: top-right, passive marker.
                        if (row.finishedAt != null) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(24.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "Finished",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = row.document.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (searchActive && query.isNotBlank() && items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "No matches in your library",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    menuFor?.let { doc ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { menuFor = null },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    text = doc.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                TextButton(
                    onClick = {
                        pendingRestart = doc
                        menuFor = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text("Restart book", modifier = Modifier.fillMaxWidth())
                }
                TextButton(
                    onClick = {
                        pendingDelete = doc
                        menuFor = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(
                        "Delete from library",
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    pendingRestart?.let { doc ->
        var alsoDelete by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingRestart = null },
            title = { Text("Restart book?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("\"${doc.title}\" will be marked as unread and synced to your other devices.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = alsoDelete, onCheckedChange = { alsoDelete = it })
                        Text("Also delete the downloaded copy", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restartFromUi(doc, alsoDelete, context)
                    pendingRestart = null
                }) { Text("Restart") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestart = null }) { Text("Cancel") }
            },
        )
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
