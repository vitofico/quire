package io.theficos.ereader.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val downloadedUrls by viewModel.downloadedUrls.collectAsState()
    LaunchedEffect(Unit) { if (state == CatalogUiState.Idle) viewModel.loadRoot() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Catalog") },
            actions = {
                TextButton(onClick = onOpenLibrary) { Text("Library") }
                TextButton(onClick = onOpenSettings) { Text("Settings") }
            },
        )
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                CatalogUiState.Idle -> {}
                CatalogUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is CatalogUiState.Error -> Text(s.message, Modifier.align(Alignment.Center))
                is CatalogUiState.Loaded -> {
                    Column(Modifier.fillMaxSize()) {
                        if (s.error != null) {
                            Text(
                                "Download error: ${s.error}",
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                        items(s.feed.navigation) { nav ->
                            ListItem(
                                headlineContent = { Text(nav.title) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.load(nav.href) }
                                    .padding(horizontal = 8.dp),
                            )
                            HorizontalDivider()
                        }
                        items(s.feed.publications) { pub ->
                            val alreadyDownloaded = pub.epubDownloadHref in downloadedUrls
                            ListItem(
                                headlineContent = { Text(pub.title) },
                                supportingContent = pub.author?.let { author -> { Text(author) } },
                                trailingContent = {
                                    when {
                                        s.downloading == pub.epubDownloadHref ->
                                            CircularProgressIndicator(progress = { s.progress })
                                        alreadyDownloaded ->
                                            Text("Downloaded")
                                        else ->
                                            Button(onClick = { viewModel.download(pub) }) { Text("Download") }
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            HorizontalDivider()
                        }
                        }
                    }
                }
            }
        }
    }
}
