package io.theficos.ereader.ui

import kotlinx.coroutines.flow.map
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.theficos.ereader.di.AppContainer
import io.theficos.ereader.ui.bookdetail.BookDetailScreen
import io.theficos.ereader.ui.bookdetail.BookDetailSheet
import io.theficos.ereader.ui.bookdetail.BookDetailViewModel
import io.theficos.ereader.ui.bookdetail.InsightAuditScreen
import io.theficos.ereader.ui.bookdetail.InsightAuditViewModel
import io.theficos.ereader.ui.catalog.CatalogScreen
import io.theficos.ereader.ui.catalog.CatalogViewModel
import io.theficos.ereader.ui.catalogdetail.CatalogDetailScreen
import io.theficos.ereader.ui.catalogdetail.CatalogDetailViewModel
import io.theficos.ereader.ui.library.LibraryInsightsScreen
import io.theficos.ereader.ui.library.LibraryInsightsViewModel
import io.theficos.ereader.ui.library.LibraryScreen
import io.theficos.ereader.ui.library.LibraryStatsScreen
import io.theficos.ereader.ui.library.LibraryStatsViewModel
import io.theficos.ereader.ui.library.LibraryViewModel
import io.theficos.ereader.ui.main.MainScaffold
import io.theficos.ereader.ui.main.Tab
import io.theficos.ereader.ui.onboarding.CloudComingSoonScreen
import io.theficos.ereader.ui.onboarding.ConnectServerScreen
import io.theficos.ereader.ui.onboarding.ConnectServerViewModel
import io.theficos.ereader.ui.onboarding.WelcomeScreen
import io.theficos.ereader.ui.onboarding.pickStartDestination
import io.theficos.ereader.ui.reader.ReaderScreen
import io.theficos.ereader.ui.reader.ReaderViewModel
import io.theficos.ereader.ui.scan.ScanResultScreen
import io.theficos.ereader.ui.scan.ScanResultViewModel
import io.theficos.ereader.ui.scan.ScanScreen
import io.theficos.ereader.ui.scan.ScanViewModel
import io.theficos.ereader.ui.settings.LicensesScreen
import io.theficos.ereader.ui.settings.SettingsScreen
import io.theficos.ereader.ui.settings.SettingsViewModel
import io.theficos.ereader.ui.theme.AppTheme
import io.theficos.quire.R
import kotlinx.coroutines.launch

@Composable
fun AppNavGraph(
    container: AppContainer,
    appTheme: AppTheme,
    nav: NavHostController = rememberNavController(),
) {
    // First-launch gating (A-2). Computed once per composition — the
    // navgraph rebuilds when the configured account or welcome marker
    // changes (via Compose recomposition driven by AppContainer state), and
    // anyway we only need to pick a start destination on cold launch.
    val startDestination = pickStartDestination(
        hasAccount = container.credentialStore.getAccount() != null,
        welcomeCompleted = container.welcomePreferencesStore.isCompleted(),
    )
    // Re-auth gating for NativeAuth (Bearer) sessions. The store raises this
    // when the session token expires or a request comes back 401 — there's no
    // refresh token, so the only recovery is signing in again. We probe expiry
    // once on entry, then route to the connect screen whenever the signal
    // flips true mid-session. A successful re-login clears the signal (the
    // store resets it on save) and the connect screen navigates onward.
    LaunchedEffect(Unit) { container.credentialStore.checkSessionExpiry() }
    val needsReauth by container.credentialStore.needsReauth.collectAsState()
    LaunchedEffect(needsReauth) {
        if (needsReauth) {
            nav.navigate("connect-server") { launchSingleTop = true }
        }
    }
    NavHost(navController = nav, startDestination = startDestination) {
        composable("welcome") {
            InsetSurface {
                WelcomeScreen(
                    onConnectServer = { nav.navigate("connect-server") },
                    onCloudSignIn = { nav.navigate("cloud-coming-soon") },
                    onSkipOffline = {
                        container.welcomePreferencesStore.markCompleted()
                        nav.navigate("home") {
                            popUpTo("welcome") { inclusive = true }
                        }
                    },
                )
            }
        }
        composable("connect-server") {
            val vm: ConnectServerViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { ConnectServerViewModel(credentialStore = container.credentialStore) }
                },
            )
            InsetSurface {
                ConnectServerScreen(
                    viewModel = vm,
                    onBack = { nav.popBackStack() },
                    onCompleted = {
                        container.welcomePreferencesStore.markCompleted()
                        nav.navigate("home") {
                            // A returning session starts at home, so welcome may not exist.
                            // Replace the old stack in either case, including Completed login.
                            popUpTo(nav.graph.startDestinationId) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
        composable("cloud-coming-soon") {
            InsetSurface {
                CloudComingSoonScreen(onBack = { nav.popBackStack() })
            }
        }
        composable("home") {
            val libVm: LibraryViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        LibraryViewModel(
                            docs = container.documentRepository,
                            progress = container.progressRepository,
                            syncOrchestrator = container.syncOrchestrator,
                            booksDir = container.booksDir,
                            libraryPreferencesStore = container.libraryPreferencesStore,
                            credentialStore = container.credentialStore,
                            restoreInProgress = { onProgress -> container.runRestoreInProgress(onProgress) },
                        )
                    }
                },
            )
            // Destination models survive a detail push and are cleared with the back-stack
            // entry, so their work is retained on return and cancelled when the entry leaves.
            val catVm: CatalogViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        CatalogViewModel(
                            client = container.opdsClient,
                            downloader = container.bookDownloader,
                            docs = container.documentRepository,
                            credentialStore = container.credentialStore,
                            syncStateDao = container.syncStateDao,
                            catalogPreferencesStore = container.catalogPreferencesStore,
                            libraryUploader = container.libraryUploader,
                            aiRepository = container.aiRepository,
                            catalogInsightStash = container.catalogInsightStash,
                            insightSyncRepository = container.insightSyncRepository,
                            subjectProvider = {
                                container.credentialStore.get()?.username?.lowercase()
                            },
                        )
                    }
                },
            )
            val setVm: SettingsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SettingsViewModel(
                            store = container.credentialStore,
                            readerStore = container.readerPreferencesStore,
                            appThemeStore = container.appThemePreferencesStore,
                            syncStateDao = container.syncStateDao,
                            documentRepo = container.documentRepository,
                            booksDir = container.booksDir,
                            aiRepository = container.aiRepository,
                            insightSyncRepository = container.insightSyncRepository,
                            insightDao = container.insightDao,
                            restoreInProgress = { onProgress -> container.runRestoreInProgress(onProgress) },
                        )
                    }
                },
            )
            MainScaffold { tab, padding ->
                when (tab) {
                    Tab.LIBRARY -> LibraryScreen(
                        viewModel = libVm,
                        onOpenBook = { id -> nav.navigate("reader/$id") },
                        onShowDetails = { id -> nav.navigate("book/$id") },
                        onShowStats = { nav.navigate("library/stats") },
                        onShowInsights = { nav.navigate("library/insights") },
                        sideloadImporter = container.sideloadImporter,
                        contentPadding = padding,
                    )
                    Tab.CATALOG -> CatalogScreen(
                        viewModel = catVm,
                        contentPadding = padding,
                        onShowDetails = { pub ->
                            val key = container.catalogDetailRegistry.put(pub)
                            nav.navigate("catalog-detail/$key")
                        },
                        onScan = { nav.navigate("scan") },
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
            val vm: ReaderViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ReaderViewModel(
                            documentId = docId,
                            docs = container.documentRepository,
                            progress = container.progressRepository,
                            readium = container.readiumFactory,
                            preferencesStore = container.readerPreferencesStore,
                            bookmarks = container.memoryBookmarkDao,
                            accountChanges = container.credentialStore.accountFlow.map { account ->
                                account?.let { io.theficos.ereader.ui.reader.memoryAccountKey(it.baseUrl, it.subject) }
                                    ?: "device-local"
                            },
                            accountKey = {
                                container.credentialStore.getAccount()?.let {
                                    io.theficos.ereader.ui.reader.memoryAccountKey(it.baseUrl, it.subject)
                                } ?: "device-local"
                            },
                            highlightRepository = container.highlightRepository,
                        )
                    }
                },
            )
            ReaderScreen(
                viewModel = vm,
                appTheme = appTheme,
                onClose = { nav.popBackStack() },
                detailsSheet = { onDismiss ->
                    // Scoped to the reader's back-stack entry: built the first time the sheet
                    // opens (so no insight lookup runs for a book nobody asked about), kept
                    // while the book is open, and cleared with it.
                    BookDetailSheet(viewModel = bookDetailViewModel(container, docId), onDismiss = onDismiss)
                },
            )
        }
        composable(
            "book/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStack ->
            val id = backStack.arguments!!.getLong("id")
            val vm = bookDetailViewModel(container, id)
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
                // Back from the book returns to wherever the details were opened from.
                onOpenReader = { docId ->
                    nav.navigate("reader/$docId") { popUpTo("book/{id}") { inclusive = true } }
                },
                onInspectInsight = { docId -> nav.navigate("book/$docId/inspect-insight") },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "book/{id}/inspect-insight",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStack ->
            val id = backStack.arguments!!.getLong("id")
            val vm: InsightAuditViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { container.insightAuditViewModelFactory.create(id) }
                },
            )
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
            val publication = remember(key) { container.catalogDetailRegistry.get(key) }
            InsetSurface {
                if (publication == null) {
                    CatalogDetailUnavailable(onBack = { nav.popBackStack() })
                } else {
                    val vm: CatalogDetailViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { checkNotNull(container.catalogDetailViewModelFactory.create(key)) }
                        },
                    )
                    CatalogDetailScreen(viewModel = vm, onBack = { nav.popBackStack() })
                }
            }
        }
        composable("scan") {
            val vm: ScanViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ScanViewModel(
                            lookup = { isbn -> container.openLibraryClient.lookupByIsbn(isbn) },
                            runAffinity = { body -> container.libraryClient.affinity(body) },
                            onReauth = { container.credentialStore.notifyUnauthorized() },
                        )
                    }
                },
            )
            ScanScreen(
                viewModel = vm,
                onResult = { data ->
                    val key = container.scanResultRegistry.put(data)
                    nav.navigate("scan-result/$key") { launchSingleTop = true }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "scan-result/{key}",
            arguments = listOf(navArgument("key") { type = NavType.StringType }),
        ) { backStack ->
            val key = backStack.arguments!!.getString("key")!!
            val data = remember(key) { container.scanResultRegistry.get(key) }
            if (data == null) {
                InsetSurface { CatalogDetailUnavailable(onBack = { nav.popBackStack() }) }
            } else {
                val vm: ScanResultViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ScanResultViewModel(data = data, ai = container.aiRepository)
                        }
                    },
                )
                ScanResultScreen(viewModel = vm, onBack = { nav.popBackStack() })
            }
        }
        composable("licenses") {
            LicensesScreen(onBack = { nav.popBackStack() })
        }
        composable("library/stats") {
            val vm: LibraryStatsViewModel = viewModel(
                factory = viewModelFactory { initializer { container.libraryStatsViewModelFactory.create() } },
            )
            LibraryStatsScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
            )
        }
        composable("library/insights") {
            val vm: LibraryInsightsViewModel = viewModel(
                factory = viewModelFactory { initializer { container.libraryInsightsViewModelFactory.create() } },
            )
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            LibraryInsightsScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onOpenBook = { identity ->
                    scope.launch {
                        val doc = container.documentRepository.findByIdentity(identity)
                        if (doc != null) nav.navigate("reader/${doc.id}")
                    }
                },
                onOpenWeb = { url ->
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                // PR-δ wires this to nav.navigate("settings"); for now a no-op
                // (the Library is the only entry point and the user can reach
                // Settings from the bottom-nav).
                onOpenSettings = { nav.popBackStack() },
            )
        }
    }
}

/**
 * Full-bleed surface that insets its content by [WindowInsets.safeDrawing] (system bars +
 * display cutout + IME). The app is edge-to-edge (see MainActivity), so screens that don't
 * already manage their own insets via a Material3 Scaffold/TopAppBar are wrapped in this to
 * keep content clear of the system bars and the keyboard. The outer Surface paints behind
 * the (opaque) bars so there's no window-background seam.
 */
@Composable
private fun InsetSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            content()
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
                text = stringResource(R.string.ui_this_catalog_entry_is_no_longer_available),
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
        }
    }
}

/** A book-details view model owned by the current back-stack entry, so it is cleared with it. */
@Composable
private fun bookDetailViewModel(container: AppContainer, documentId: Long): BookDetailViewModel =
    viewModel(
        factory = viewModelFactory {
            initializer { container.bookDetailViewModelFactory.create(documentId) }
        },
    )
