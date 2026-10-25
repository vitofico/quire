package io.theficos.ereader.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.data.sync.SyncEnqueuer
import io.theficos.ereader.sideload.SideloadFailure
import io.theficos.ereader.sideload.SideloadImporter
import io.theficos.ereader.sideload.SideloadResult
import io.theficos.ereader.ui.components.CoverImage
import io.theficos.ereader.ui.components.MarbledInk
import io.theficos.ereader.ui.components.CoverInfoButton
import io.theficos.ereader.ui.components.SectionLabel
import io.theficos.quire.R
import kotlinx.coroutines.launch

private val sortLabels: List<Pair<LibrarySort, Int>> = listOf(
    LibrarySort.RECENTLY_READ to R.string.common_recently_read,
    LibrarySort.RECENTLY_ADDED to R.string.common_recently_added,
    LibrarySort.TITLE to R.string.common_title,
    LibrarySort.AUTHOR to R.string.common_author,
)

/**
 * MIME types the `+ Import` picker accepts. EPUB officially registers as
 * `application/epub+zip`; the fallback `application/zip` covers file pickers
 * (notably some stock Android Files apps) that fall back to a generic zip
 * MIME for `.epub`. The structural EPUB validation in `SideloadImporter`
 * filters out actual non-EPUB zips later — this filter is just to keep the
 * picker UX from listing every file on the device.
 */
private val IMPORT_MIME_FILTER: Array<String> = arrayOf(
    "application/epub+zip",
    "application/zip",
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (documentId: Long) -> Unit,
    onShowDetails: (documentId: Long) -> Unit = {},
    onShowStats: () -> Unit = {},
    onShowInsights: () -> Unit = {},
    sideloadImporter: SideloadImporter? = null,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SyncEnqueuer.enqueue(context, expedited = true) }

    val loadedShelf by viewModel.shelf.collectAsState()
    val cont by viewModel.continueReading.collectAsState()
    val seriesCandidates by viewModel.seriesContinuationCandidates.collectAsState()
    val showAbandoned by viewModel.showAbandoned.collectAsState()
    val showFinished by viewModel.showFinished.collectAsState()
    var menuFor by remember { mutableStateOf<LibraryRow?>(null) }
    var pendingDelete by remember { mutableStateOf<Document?>(null) }
    var pendingRestart by remember { mutableStateOf<Document?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val query by viewModel.query.collectAsState()
    val canRestore by viewModel.canRestore.collectAsState()
    val restoreRunning by viewModel.restoreRunning.collectAsState()
    val restoreProgress by viewModel.restoreProgress.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val msg = when (event) {
                LibraryEvent.RestartFailed -> context.getString(R.string.library_couldn_t_sync_restart_will_retry)
                is LibraryEvent.RestoreFinished -> {
                    val s = event.summary
                    if (s.failed > 0) context.getString(R.string.restore_partial, s.downloaded, s.requested, s.failed)
                    else context.getString(R.string.restore_complete, s.downloaded, s.requested)
                }
                is LibraryEvent.RestoreFailed -> context.getString(R.string.common_restore_failed, event.message)
            }
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Phase-0 / A-3: `+ Import` button. Picker launches OpenDocument with an
    // EPUB MIME filter; result Uri is fed into the same SideloadImporter the
    // share-sheet ImportActivity uses. Importer may be null in previews/tests
    // — the button is suppressed in that case.
    val importLauncher = if (sideloadImporter != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val displayName = SideloadImporter.queryDisplayName(context.contentResolver, uri)
                    val result = sideloadImporter.import(uri, displayName)
                    val msg = when (result) {
                        is SideloadResult.Imported -> context.getString(R.string.common_imported, result.title)
                        is SideloadResult.AlreadyImported -> context.getString(R.string.sideload_already_in_your_library, result.title)
                        is SideloadResult.Failed -> when (result.reason) {
                            SideloadFailure.InvalidEpub -> context.getString(R.string.common_not_a_valid_epub)
                            SideloadFailure.UriUnreadable -> context.getString(R.string.common_couldn_t_read_the_file)
                            SideloadFailure.IoError -> context.getString(R.string.sideload_import_failed_try_again)
                        }
                    }
                    snackbarHostState.showSnackbar(msg)
                }
            }
        }
    } else null

    val launchPicker: (() -> Unit)? = importLauncher?.let { l ->
        { l.launch(IMPORT_MIME_FILTER) }
    }

    // Null until Room first answers. Drawing nothing for those few milliseconds
    // beats flashing stringResource(R.string.common_your_shelf_is_empty) over a library that has books.
    val shelf = loadedShelf ?: return
    val items = shelf.books

    // Only a truly empty library gets the empty state, which has no top bar:
    // when the filters hide every book, the filter menu must stay reachable.
    if (items.isEmpty() && shelf.hiddenCount == 0 && !searchActive && query.isBlank()) {
        Box(modifier = Modifier.fillMaxSize()) {
            EmptyState(
                modifier = Modifier.padding(contentPadding),
                onImport = launchPicker,
                canRestore = canRestore,
                restoreRunning = restoreRunning,
                restoreProgress = restoreProgress,
                onRestore = { viewModel.restoreInProgressBooks() },
            )
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
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
                LibraryHeader {
                    IconButton(onClick = { searchActive = true }) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.common_search))
                    }
                    launchPicker?.let { picker ->
                        IconButton(onClick = picker) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.common_import_epub))
                        }
                    }
                    var sortMenuOpen by rememberSaveable { mutableStateOf(false) }
                    val currentSort by viewModel.sort.collectAsState()
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = stringResource(R.string.common_sort))
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            sortLabels.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
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
                    // pr-δ: filter icon — abandoned books hidden by default. The
                    // OUTLINED + primary-tinted icon communicates "items hidden";
                    // the FILLED icon communicates "every book visible."
                    var filterMenuOpen by rememberSaveable { mutableStateOf(false) }
                    val anyHidden = !showAbandoned || !showFinished
                    Box {
                        IconButton(onClick = { filterMenuOpen = true }) {
                            Icon(
                                imageVector = if (anyHidden) {
                                    Icons.Outlined.FilterAlt
                                } else {
                                    Icons.Filled.FilterAlt
                                },
                                contentDescription = when {
                                    !showAbandoned && !showFinished -> stringResource(R.string.library_completed_abandoned_hidden)
                                    !showAbandoned -> stringResource(R.string.common_abandoned_books_hidden)
                                    !showFinished -> stringResource(R.string.library_finished_hidden)
                                    else -> stringResource(R.string.library_all_visible)
                                },
                                tint = if (anyHidden) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                },
                            )
                        }
                        DropdownMenu(
                            expanded = filterMenuOpen,
                            onDismissRequest = { filterMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_show_abandoned)) },
                                leadingIcon = {
                                    Checkbox(checked = showAbandoned, onCheckedChange = null)
                                },
                                onClick = {
                                    viewModel.toggleShowAbandoned()
                                    filterMenuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_show_finished)) },
                                leadingIcon = {
                                    Checkbox(checked = showFinished, onCheckedChange = null)
                                },
                                onClick = {
                                    viewModel.toggleShowFinished()
                                    filterMenuOpen = false
                                },
                            )
                        }
                    }
                    var moreMenuOpen by rememberSaveable { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { moreMenuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more))
                        }
                        DropdownMenu(
                            expanded = moreMenuOpen,
                            onDismissRequest = { moreMenuOpen = false },
                        ) {
                            // PR-γ: Insights ranks above Stats — Insights is the
                            // headline reader-profile surface; Stats is utility.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_insights)) },
                                onClick = {
                                    moreMenuOpen = false
                                    onShowInsights()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_stats)) },
                                onClick = {
                                    moreMenuOpen = false
                                    onShowStats()
                                },
                            )
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
                        placeholder = { Text(stringResource(R.string.common_search_library)) },
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
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close_search))
                            }
                        },
                    )
                } else {
                    SectionLabel(stringResource(R.string.common_library, items.size))
                }
            }
            itemsIndexed(items, key = { _, r -> r.document.id }) { _, row ->
                Column(
                    modifier = Modifier.combinedClickable(
                        onClick = { onOpenBook(row.document.id) },
                        onLongClick = { menuFor = row },
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
                        // Top-left, like the catalog's: the book's details, whether or not
                        // there is a server behind them to add AI insights.
                        CoverInfoButton(
                            onClick = { onShowDetails(row.document.id) },
                            contentDescription = stringResource(R.string.common_book_details),
                            modifier = Modifier.align(Alignment.TopStart),
                        )
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
                                        contentDescription = stringResource(R.string.common_finished_2),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        } else if (row.abandonedAt != null) {
                            // pr-δ: abandoned chip — top-right, mutually exclusive
                            // with the finished checkmark (server CHECK constraint
                            // guarantees the two terminal states never coexist).
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.common_abandoned),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
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
            if (items.isEmpty() && shelf.hiddenCount > 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = pluralStringResource(R.plurals.library_filter_hiding_books, shelf.hiddenCount, shelf.hiddenCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (searchActive && query.isNotBlank() && items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.library_no_matches_in_your_library),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    menuFor?.let { row ->
        val doc = row.document
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
                        onShowDetails(doc.id)
                        menuFor = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.common_book_details), modifier = Modifier.fillMaxWidth())
                }
                TextButton(
                    onClick = {
                        pendingRestart = doc
                        menuFor = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.common_restart_book), modifier = Modifier.fillMaxWidth())
                }
                // pr-δ: Mark / Unmark abandoned (Lock #18 — augment the existing
                // sheet, do not introduce a new menu). Snackbar with Undo on mark
                // so the disappear-on-default-hide feels safe.
                TextButton(
                    onClick = {
                        val wasAbandoned = row.abandonedAt != null
                        if (wasAbandoned) {
                            viewModel.unmarkAbandoned(doc)
                        } else {
                            viewModel.markAbandoned(doc)
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.common_marked_abandoned),
                                    actionLabel = context.getString(R.string.common_undo),
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.unmarkAbandoned(doc)
                                }
                            }
                        }
                        menuFor = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = if (row.abandonedAt == null) stringResource(R.string.common_mark_as_abandoned) else stringResource(R.string.common_unmark_abandoned),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(
                    onClick = {
                        pendingDelete = doc
                        menuFor = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(
                        stringResource(R.string.common_delete_from_library),
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
            title = { Text(stringResource(R.string.common_restart_book_2)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.library_will_be_marked_as_unread_and_synced, doc.title))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = alsoDelete, onCheckedChange = { alsoDelete = it })
                        Text(stringResource(R.string.library_also_delete_the_downloaded_copy), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restartFromUi(doc, alsoDelete, context)
                    pendingRestart = null
                }) { Text(stringResource(R.string.common_restart)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestart = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    pendingDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.common_delete_book)) },
            text = { Text(stringResource(R.string.library_will_be_removed_from_your_library_and, doc.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(doc)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    onImport: (() -> Unit)? = null,
    canRestore: Boolean = false,
    restoreRunning: Boolean = false,
    restoreProgress: io.theficos.ereader.domain.restore.RestoreProgress? = null,
    onRestore: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MarbledInk(Modifier.size(width = 256.dp, height = 144.dp).padding(bottom = 16.dp))
            Text(
                text = stringResource(R.string.common_your_shelf_is_empty),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.library_open_the_catalog_tab_to_find_books),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Phase-0 / A-3: the top-bar `+ Import` button is hidden when the
            // library is empty (top bar isn't rendered at all in that branch),
            // so surface the same action here for first-time-sideload users.
            onImport?.let { picker ->
                Button(
                    onClick = picker,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(text = stringResource(R.string.common_import_epub), modifier = Modifier.padding(start = 8.dp))
                }
            }
            // Task 6: empty-state restore prompt — only when an account is
            // connected and the library is empty (reconnected on a new device).
            if (canRestore) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.library_reconnected_on_a_new_device_bring_back),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val restoreLabel = when {
                    restoreRunning && restoreProgress != null ->
                        stringResource(R.string.common_restoring, restoreProgress.done, restoreProgress.total)
                    restoreRunning -> stringResource(R.string.common_restoring_2)
                    else -> stringResource(R.string.library_restore_in_progress_books)
                }
                Button(
                    onClick = onRestore,
                    enabled = !restoreRunning,
                ) { Text(restoreLabel) }
            }
        }
    }
}
