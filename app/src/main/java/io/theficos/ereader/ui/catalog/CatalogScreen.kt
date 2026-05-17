package io.theficos.ereader.ui.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.data.opds.OpdsPublication
import io.theficos.ereader.ui.components.CoverImage
import io.theficos.ereader.ui.components.SectionLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    contentPadding: PaddingValues,
    onShowDetails: (OpdsPublication) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val downloadedUrls by viewModel.downloadedUrls.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val context = LocalContext.current

    val canGoBack = (state as? CatalogUiState.Loaded)?.canGoBack == true
    BackHandler(enabled = canGoBack) { viewModel.back() }

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        when (val s = state) {
            CatalogUiState.Idle -> {}
            CatalogUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is CatalogUiState.Error -> Text(
                s.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            is CatalogUiState.Loaded -> {
                val sort by viewModel.sort.collectAsState()
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Loaded(
                        state = s,
                        downloadedUrls = downloadedUrls,
                        sort = sort,
                        onSort = viewModel::setSort,
                        onNavigate = viewModel::load,
                        onBack = viewModel::back,
                        onSearch = viewModel::search,
                        onDownload = { pub -> viewModel.download(pub, context) },
                        onShowDetails = onShowDetails,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Loaded(
    state: CatalogUiState.Loaded,
    downloadedUrls: Set<String>,
    sort: CatalogSort,
    onSort: (CatalogSort) -> Unit,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onDownload: (OpdsPublication) -> Unit,
    onShowDetails: (OpdsPublication) -> Unit,
) {
    val publications = remember(state.feed.publications, sort) {
        applyCatalogSort(state.feed.publications, sort)
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Header(
                title = state.feed.title.takeUnless { it.isBlank() } ?: "Catalog",
                canGoBack = state.canGoBack,
                onBack = onBack,
            )
        }
        if (state.feed.searchLink != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchField(onSearch = onSearch)
            }
        }
        if (state.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Download error: ${state.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (state.feed.navigation.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Sections") }
            navigationItems(state, onNavigate)
        }
        if (publications.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                CatalogBooksHeader(
                    count = publications.size,
                    sort = sort,
                    onSort = onSort,
                )
            }
            itemsIndexed(publications, key = { _, p -> p.epubDownloadHref }) { _, pub ->
                val downloaded = pub.epubDownloadHref in downloadedUrls
                val downloading = state.downloading == pub.epubDownloadHref
                Column(
                    modifier = Modifier.combinedClickable(
                        enabled = !downloading,
                        onClick = { if (!downloaded) onDownload(pub) },
                        onLongClick = { onShowDetails(pub) },
                    ),
                ) {
                    Box {
                        CoverImage(
                            source = pub.coverUrl,
                            title = pub.title,
                            author = pub.author,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f),
                        )
                        when {
                            downloading -> CircularProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(36.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            downloaded -> Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Downloaded",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.align(Alignment.Center).size(14.dp),
                                )
                            }
                            else -> Icon(
                                Icons.Default.FileDownload,
                                contentDescription = "Download",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(20.dp),
                            )
                        }
                        // Visible info affordance — opens the catalog detail screen
                        // without competing with the cover-tap download target
                        // (positioned at TopStart while the download badge sits
                        // at TopEnd).
                        IconButton(
                            onClick = { onShowDetails(pub) },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(2.dp)
                                .size(32.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                            ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = "Details",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.Center).size(18.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = pub.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    pub.author?.let { author ->
                        Text(
                            text = author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

}

private val catalogSortLabels: List<Pair<CatalogSort, String>> = listOf(
    CatalogSort.AUTHOR to "Author",
    CatalogSort.TITLE to "Title",
    CatalogSort.AS_SHOWN to "As shown",
)

private fun applyCatalogSort(list: List<OpdsPublication>, by: CatalogSort): List<OpdsPublication> = when (by) {
    CatalogSort.AUTHOR -> list.sortedWith(
        compareBy<OpdsPublication> { it.author?.lowercase() ?: "￿" }
            .thenBy { it.title.lowercase() }
    )
    CatalogSort.TITLE -> list.sortedBy { it.title.lowercase() }
    CatalogSort.AS_SHOWN -> list
}

@Composable
private fun CatalogBooksHeader(
    count: Int,
    sort: CatalogSort,
    onSort: (CatalogSort) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel("Books · $count", modifier = Modifier.weight(1f))
        var sortMenuOpen by rememberSaveable { mutableStateOf(false) }
        Box {
            IconButton(onClick = { sortMenuOpen = true }) {
                Icon(Icons.Filled.Sort, contentDescription = "Sort")
            }
            DropdownMenu(
                expanded = sortMenuOpen,
                onDismissRequest = { sortMenuOpen = false },
            ) {
                catalogSortLabels.forEach { (key, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = if (sort == key) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            onSort(key)
                            sortMenuOpen = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    title: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        if (canGoBack) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = if (canGoBack) 48.dp else 0.dp),
        )
    }
}

@Composable
private fun SearchField(onSearch: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Search this catalog") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            if (query.isNotBlank()) onSearch(query)
        }),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun LazyGridScope.navigationItems(
    state: CatalogUiState.Loaded,
    onNavigate: (String) -> Unit,
) {
    items(state.feed.navigation.size, span = { GridItemSpan(maxLineSpan) }) { idx ->
        val nav = state.feed.navigation[idx]
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigate(nav.href) }
                    .padding(vertical = 14.dp, horizontal = 4.dp),
            ) {
                Text(
                    text = nav.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}
