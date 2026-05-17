package io.theficos.ereader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.theficos.ereader.di.AppContainer
import io.theficos.ereader.ui.bookdetail.BookDetailScreen
import io.theficos.ereader.ui.bookdetail.InsightAuditScreen
import io.theficos.ereader.ui.catalog.CatalogScreen
import io.theficos.ereader.ui.catalog.CatalogViewModel
import io.theficos.ereader.ui.catalogdetail.CatalogDetailScreen
import io.theficos.ereader.ui.library.LibraryScreen
import io.theficos.ereader.ui.library.LibraryViewModel
import io.theficos.ereader.ui.main.MainScaffold
import io.theficos.ereader.ui.main.Tab
import io.theficos.ereader.ui.reader.ReaderScreen
import io.theficos.ereader.ui.reader.ReaderViewModel
import io.theficos.ereader.ui.settings.LicensesScreen
import io.theficos.ereader.ui.settings.SettingsScreen
import io.theficos.ereader.ui.settings.SettingsViewModel

@Composable
fun AppNavGraph(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            val libVm = remember {
                LibraryViewModel(
                    docs = container.documentRepository,
                    progress = container.progressRepository,
                    syncOrchestrator = container.syncOrchestrator,
                    booksDir = container.booksDir,
                    libraryPreferencesStore = container.libraryPreferencesStore,
                )
            }
            val catVm = remember {
                CatalogViewModel(
                    client = container.opdsClient,
                    downloader = container.bookDownloader,
                    docs = container.documentRepository,
                    credentialStore = container.credentialStore,
                    syncStateDao = container.syncStateDao,
                    catalogPreferencesStore = container.catalogPreferencesStore,
                )
            }
            val setVm = remember {
                SettingsViewModel(
                    store = container.credentialStore,
                    readerStore = container.readerPreferencesStore,
                    syncStateDao = container.syncStateDao,
                    documentRepo = container.documentRepository,
                    booksDir = container.booksDir,
                    aiRepository = container.aiRepository,
                )
            }
            val aiConfig by container.aiRepository.config.collectAsState()
            MainScaffold { tab, padding ->
                when (tab) {
                    Tab.LIBRARY -> LibraryScreen(
                        viewModel = libVm,
                        onOpenBook = { id -> nav.navigate("reader/$id") },
                        onShowDetails = { id -> nav.navigate("book/$id") },
                        aiConfigured = aiConfig?.configured == true,
                        contentPadding = padding,
                    )
                    Tab.CATALOG -> CatalogScreen(
                        viewModel = catVm,
                        contentPadding = padding,
                        onShowDetails = { pub ->
                            val key = container.catalogDetailRegistry.put(pub)
                            nav.navigate("catalog-detail/$key")
                        },
                    )
                    Tab.SETTINGS -> SettingsScreen(
                        viewModel = setVm,
                        contentPadding = padding,
                        onNavigateToLicenses = { nav.navigate("licenses") },
                    )
                }
            }
        }
        composable(
            "reader/{docId}",
            arguments = listOf(navArgument("docId") { type = NavType.LongType }),
        ) { backStack ->
            val docId = backStack.arguments!!.getLong("docId")
            val vm = remember(docId) {
                ReaderViewModel(
                    documentId = docId,
                    docs = container.documentRepository,
                    progress = container.progressRepository,
                    readium = container.readiumFactory,
                    preferencesStore = container.readerPreferencesStore,
                )
            }
            ReaderScreen(viewModel = vm, onClose = { nav.popBackStack() })
        }
        composable(
            "book/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStack ->
            val id = backStack.arguments!!.getLong("id")
            val vm = remember(id) {
                container.bookDetailViewModelFactory.create(id)
            }
            // Re-run load() after the audit screen invalidates the cached row,
            // so the book detail naturally regenerates (or shows nothing) on
            // return.
            val savedStateHandle = backStack.savedStateHandle
            val invalidatedFlow = savedStateHandle
                .getStateFlow("insight_invalidated", false)
            val invalidated by invalidatedFlow.collectAsState()
            LaunchedEffect(invalidated) {
                if (invalidated) {
                    savedStateHandle["insight_invalidated"] = false
                    vm.retry()
                }
            }
            BookDetailScreen(
                viewModel = vm,
                onOpenReader = { docId -> nav.navigate("reader/$docId") },
                onInspectInsight = { docId -> nav.navigate("book/$docId/inspect-insight") },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "book/{id}/inspect-insight",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStack ->
            val id = backStack.arguments!!.getLong("id")
            val vm = remember(id) {
                container.insightAuditViewModelFactory.create(id)
            }
            InsightAuditScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onInvalidated = {
                    nav.previousBackStackEntry?.savedStateHandle
                        ?.set("insight_invalidated", true)
                    nav.popBackStack()
                },
            )
        }
        composable(
            "catalog-detail/{key}",
            arguments = listOf(navArgument("key") { type = NavType.StringType }),
        ) { backStack ->
            val key = backStack.arguments!!.getString("key")!!
            val vm = remember(key) { container.catalogDetailViewModelFactory.create(key) }
            if (vm == null) {
                CatalogDetailUnavailable(onBack = { nav.popBackStack() })
            } else {
                CatalogDetailScreen(viewModel = vm, onBack = { nav.popBackStack() })
            }
        }
        composable("licenses") {
            LicensesScreen(onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun CatalogDetailUnavailable(onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "This catalog entry is no longer available. Reload the catalog from the Catalog tab.",
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}
