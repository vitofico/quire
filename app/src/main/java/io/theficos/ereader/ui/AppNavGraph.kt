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
import io.theficos.ereader.ui.reader.ReaderScreen
import io.theficos.ereader.ui.reader.ReaderViewModel
import io.theficos.ereader.ui.settings.SettingsScreen
import io.theficos.ereader.ui.settings.SettingsViewModel

@Composable
fun AppNavGraph(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "library") {
        composable("library") {
            val vm = remember { LibraryViewModel(container.documentRepository, container.progressRepository) }
            LibraryScreen(
                viewModel = vm,
                onOpenCatalog = { nav.navigate("catalog") },
                onOpenBook = { id -> nav.navigate("reader/$id") },
            )
        }
        composable("catalog") {
            val vm = remember {
                CatalogViewModel(container.opdsClient, container.bookDownloader, container.documentRepository, container.credentialStore)
            }
            CatalogScreen(
                viewModel = vm,
                onOpenLibrary = { nav.popBackStack("library", inclusive = false) },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            val vm = remember {
                SettingsViewModel(
                    store = container.credentialStore,
                    readerStore = container.readerPreferencesStore,
                )
            }
            SettingsScreen(viewModel = vm, onBack = { nav.popBackStack() })
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
            ReaderScreen(viewModel = vm)
        }
    }
}
