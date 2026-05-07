package io.theficos.ereader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.theficos.ereader.di.AppContainer
import io.theficos.ereader.ui.catalog.CatalogScreen
import io.theficos.ereader.ui.catalog.CatalogViewModel
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
            val libVm = remember { LibraryViewModel(container.documentRepository, container.progressRepository) }
            val catVm = remember {
                CatalogViewModel(container.opdsClient, container.bookDownloader, container.documentRepository, container.credentialStore)
            }
            val setVm = remember {
                SettingsViewModel(
                    store = container.credentialStore,
                    readerStore = container.readerPreferencesStore,
                    syncStateDao = container.syncStateDao,
                    documentRepo = container.documentRepository,
                    booksDir = container.booksDir,
                )
            }
            MainScaffold { tab, padding ->
                when (tab) {
                    Tab.LIBRARY -> LibraryScreen(
                        viewModel = libVm,
                        onOpenBook = { id -> nav.navigate("reader/$id") },
                        contentPadding = padding,
                    )
                    Tab.CATALOG -> CatalogScreen(
                        viewModel = catVm,
                        contentPadding = padding,
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
        composable("licenses") {
            LicensesScreen(onBack = { nav.popBackStack() })
        }
    }
}
