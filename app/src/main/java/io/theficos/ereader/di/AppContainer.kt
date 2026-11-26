package io.theficos.ereader.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.core.identity.extractIdentity
import io.theficos.ereader.core.metadata.readOpfBundle
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.ai.AiClient
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.ai.BookDetailsRepository
import io.theficos.ereader.data.ai.CatalogInsightStash
import io.theficos.ereader.data.ai.InsightSyncRepository
import io.theficos.ereader.data.library.LibraryClient
import io.theficos.ereader.data.library.LibraryUploader
import io.theficos.ereader.data.library.convert.ConversionDependencies
import io.theficos.ereader.data.library.convert.ConversionRepository
import io.theficos.ereader.data.library.convert.ConversionScheduler
import io.theficos.ereader.data.library.convert.ConvertClient
import io.theficos.ereader.data.library.convert.ImportOutcome
import io.theficos.ereader.data.library.sync.CredentialsProvider
import io.theficos.ereader.data.library.sync.LibraryMirrorPushDependencies
import io.theficos.ereader.data.library.sync.LibraryMirrorPushScheduler
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.HighlightRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.local.db.ProgressDao
import io.theficos.ereader.data.opds.BookDownloader
import io.theficos.ereader.data.opds.CatalogSyncClient
import io.theficos.ereader.data.opds.CatalogSyncRepository
import io.theficos.ereader.data.opds.OpdsClient
import io.theficos.ereader.data.opds.OpdsHttpClient
import io.theficos.ereader.data.opds.OpenLibraryClient
import io.theficos.ereader.data.sync.HighlightSyncer
import io.theficos.ereader.data.sync.SyncClient
import io.theficos.ereader.data.sync.SyncDependencies
import io.theficos.ereader.data.sync.SyncEnqueuer
import io.theficos.ereader.data.sync.SyncOrchestrator
import io.theficos.ereader.domain.restore.RestoreInProgressUseCase
import io.theficos.ereader.domain.restore.RestoreSummary
import io.theficos.ereader.notify.AndroidDoNotDisturb
import io.theficos.ereader.notify.NotificationFeatures
import io.theficos.ereader.notify.NotificationPreferencesStore
import io.theficos.ereader.notify.QuietWhileReading
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import io.theficos.ereader.sideload.SideloadFailure
import io.theficos.ereader.sideload.SideloadImporter
import io.theficos.ereader.sideload.SideloadResult
import io.theficos.ereader.ui.bookdetail.AppInsightAuditSource
import io.theficos.ereader.ui.bookdetail.BookDetailViewModel
import io.theficos.ereader.ui.bookdetail.InsightAuditViewModel
import io.theficos.ereader.ui.catalog.CatalogPreferencesStore
import io.theficos.ereader.ui.catalogdetail.AiRepositoryAdapter
import io.theficos.ereader.ui.catalogdetail.CatalogAiPort
import io.theficos.ereader.ui.catalogdetail.CatalogDetailRegistry
import io.theficos.ereader.ui.catalogdetail.CatalogDetailViewModel
import io.theficos.ereader.ui.library.LibraryInsightsViewModel
import io.theficos.ereader.ui.library.LibraryPreferencesStore
import io.theficos.ereader.ui.library.LibraryStatsCache
import io.theficos.ereader.ui.library.LibraryStatsViewModel
import io.theficos.ereader.ui.onboarding.ConnectServerViewModel
import io.theficos.ereader.ui.onboarding.WelcomePreferencesStore
import io.theficos.ereader.ui.reader.memoryAccountKey
import io.theficos.ereader.ui.settings.WhatsNewPreferencesStore
import io.theficos.ereader.ui.scan.ScanResultRegistry
import io.theficos.ereader.ui.theme.AppThemePreferencesStore
import io.theficos.quire.R
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * URL that the sync, library and AI clients should target, or null when this
 * account has no quire-server behind it at all.
 *
 * A null here is the single hard gate that makes an OPDS-only account
 * reader-only (issue #101). Every client already treats null as "not
 * configured": [SyncClient] returns `Unauthorized`, [AiClient] and
 * [LibraryClient] throw an exception their callers already catch. Expressing
 * the gate as an exhaustive `when` rather than a feature flag means a future
 * account variant cannot silently inherit server access: this stops compiling
 * until someone decides what it should do.
 */
internal fun AccountCredentials.quireServerUrlOrNull(): String? = when (this) {
    is AccountCredentials.Basic -> quireServerUrl ?: baseUrl
    is AccountCredentials.Bearer -> baseUrl
    is AccountCredentials.OpdsOnly -> null
}

/** Who stored book details belong to: this account on this server, or null with no Quire server. */
private fun AccountCredentials.detailsOwner(): String? = quireServerUrlOrNull()?.let { accountOwner(it, subject) }

/** An account on a Quire server, as book details and PDF conversions record their owner. */
internal fun accountOwner(serverUrl: String, subject: String): String = "$serverUrl $subject"

/** True while the device's default network is not metered, such as Wi-Fi. */
private fun unmeteredNetwork(context: Context): Flow<Boolean> = callbackFlow {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    fun offer(capabilities: NetworkCapabilities?) {
        trySend(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true)
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = offer(capabilities)
        override fun onLost(network: Network) = offer(null)
    }
    offer(connectivity.getNetworkCapabilities(connectivity.activeNetwork))
    connectivity.registerDefaultNetworkCallback(callback)
    awaitClose { connectivity.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()

/** Key of the reading-pause notes kept on a device with no account. */
internal const val DEVICE_MEMORY_KEY = "device-local"

internal fun AccountCredentials.catalogInsightScope(catalog: AccountCredentials.OpdsOnly?): String? {
    val service = quireServerUrlOrNull() ?: return null
    if (catalog == null) return null
    val input = listOf(service, subject, catalog.baseUrl, catalog.username.orEmpty())
        .joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

class AppContainer internal constructor(context: Context, private val db: EReaderDatabase) {
    constructor(context: Context) : this(context, EReaderDatabase.build(context.applicationContext))

    private val appContext = context.applicationContext

    val credentialStore: CalibreCredentialStore = CalibreCredentialStore(appContext)

    val opdsHttp = OpdsHttpClient(credentialStore)
    val opdsClient: OpdsClient = OpdsClient(opdsHttp.okHttp)
    val booksDir: File = File(appContext.filesDir, "books")
    val bookDownloader: BookDownloader = BookDownloader(
        okHttp = opdsHttp.okHttp,
        booksDir = booksDir,
    )

    val memoryBookmarkDao = db.memoryBookmarkDao()
    val documentRepository = DocumentRepository(db.documentDao())
    val progressRepository = ProgressRepository(db.progressDao())
    val highlightRepository = HighlightRepository(
        db.highlightDao(),
        scopeProvider = { credentialStore.getAccount().highlightScope() },
        scopeFlow = credentialStore.accountFlow.map { it.highlightScope() },
        onChanged = { SyncEnqueuer.enqueue(appContext) },
    )
    val syncStateDao = db.syncStateDao()
    val readiumFactory = ReadiumFactory(appContext)
    val readerPreferencesStore = ReaderPreferencesStore(appContext)
    val appThemePreferencesStore = AppThemePreferencesStore(appContext)
    val libraryPreferencesStore = LibraryPreferencesStore(appContext)
    val catalogPreferencesStore = CatalogPreferencesStore(appContext)
    /**
     * A-2: tracks whether the user finished (or deliberately skipped) the
     * first-launch flow. Combined with [credentialStore] presence to pick
     * the navgraph's start destination.
     */
    val welcomePreferencesStore = WelcomePreferencesStore(appContext)
    internal val whatsNewPreferencesStore = WhatsNewPreferencesStore(appContext)
    val notificationPreferencesStore = NotificationPreferencesStore(appContext)
    val notificationFeatures = NotificationFeatures(
        appContext,
        notificationPreferencesStore,
        QuietWhileReading(AndroidDoNotDisturb(appContext), notificationPreferencesStore),
    )

    val syncClient: SyncClient = SyncClient(
        baseUrlProvider = { credentialStore.getAccount()?.quireServerUrlOrNull() },
        okHttp = opdsHttp.quireHttp,
    )
    private val highlightSyncer = HighlightSyncer(
        syncClient, db.highlightSyncDao(), syncStateDao,
        sessionProvider = { credentialStore.getAccount().highlightSession() },
    )
    val syncOrchestrator: SyncOrchestrator = SyncOrchestrator(
        client = syncClient,
        progressDao = db.progressDao(),
        documentRepo = documentRepository,
        syncState = syncStateDao,
        highlights = highlightSyncer,
    )

    val aiClient: AiClient = AiClient(
        baseUrlProvider = { credentialStore.getAccount()?.quireServerUrlOrNull() },
        http = opdsHttp.quireHttp,
    )
    val insightDao = db.insightDao()

    /**
     * PR-ζ / Lock #16: process-local stash for the catalog → download
     * insight promote handoff. Cleared on AI opt-out toggle (PR-δ owns
     * that hook) and on base-URL change (we observe credentialStore.flow
     * below to invoke clearAll on any baseUrl transition).
     */
    val catalogInsightStash: CatalogInsightStash = CatalogInsightStash()

    internal fun catalogInsightScope(): String? =
        credentialStore.getAccount()?.catalogInsightScope(credentialStore.catalogAccount())

    val catalogSyncRepository: CatalogSyncRepository = CatalogSyncRepository(
        store = credentialStore,
        client = CatalogSyncClient(
            baseUrlProvider = { (credentialStore.getAccount() as? AccountCredentials.Bearer)?.baseUrl },
            http = opdsHttp.quireHttp,
        ),
        onCatalogMerged = { from, into -> db.documentDao().replaceCatalogId(from, into) },
    )

    val libraryClient: LibraryClient = LibraryClient(
        baseUrlProvider = { credentialStore.getAccount()?.quireServerUrlOrNull() },
        http = opdsHttp.quireHttp,
    )

    /**
     * Book-scan: on-device ISBN -> metadata lookup against OpenLibrary. Plain
     * OkHttp, NO account auth — it talks to a third-party host, so it must not
     * reuse the account-authed [opdsHttp] client. Uses the client's own default
     * timeouts.
     */
    val openLibraryClient: OpenLibraryClient = OpenLibraryClient()

    /**
     * Book-scan: process-local handoff of a scanned book's metadata + affinity
     * verdict from the scan screen to the result screen. Mirrors
     * [catalogDetailRegistry]; resets on process death.
     */
    val scanResultRegistry: ScanResultRegistry = ScanResultRegistry()

    /**
     * Process-lifetime scope for fire-and-forget library upload work. A
     * SupervisorJob means a single PUT failure won't cancel sibling
     * launches; cancellation propagates only if the process itself goes
     * away, which is the correct lifetime for this kind of background sync.
     */
    val libraryUploaderScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val libraryUploader: LibraryUploader = LibraryUploader(
        client = libraryClient,
        dao = db.documentDao(),
        scope = libraryUploaderScope,
    )

    /**
     * Phase-0 / A-3: ingest pipeline for share-sheet + `+ Import` button.
     * Constructed after [documentRepository], [libraryUploader], and
     * [booksDir] so it can reuse the same physical book directory and
     * upload pump the catalog download path uses — sideloaded rows behave
     * identically to OPDS-downloaded rows post-insert.
     */
    val sideloadImporter: SideloadImporter = SideloadImporter(
        documentRepository = documentRepository,
        libraryUploader = libraryUploader,
        booksDir = booksDir,
        contentResolver = appContext.contentResolver,
        uploaderScope = libraryUploaderScope,
        onSuccessfulImport = {
            // Catalog-download parity: a returning user may have
            // server-side progress for this identity that an earlier pull
            // dropped (no local doc to attach to). Resetting the cursor +
            // expedited enqueue lets the next pull re-attach from epoch 0.
            runCatching { syncStateDao.clearAll() }
            runCatching {
                SyncEnqueuer.enqueue(appContext, expedited = true, replaceExisting = true)
            }
            // Wire the freshly-imported book into the library-mirror push
            // path right away instead of waiting for the next periodic
            // run. The scheduler coalesces concurrent enqueues, so this
            // is safe to call alongside the periodic worker.
            runCatching { LibraryMirrorPushScheduler.enqueueAfterLibraryChange(appContext) }
        },
    )

    /**
     * PDF to EPUB on the account's Quire server. The converted EPUB goes through [sideloadImporter]
     * like any shared file, which also spots a book already in the library.
     */
    val conversionRepository: ConversionRepository = ConversionRepository(
        client = ConvertClient(
            baseUrlProvider = { credentialStore.getAccount()?.quireServerUrlOrNull() },
            http = opdsHttp.quireHttp,
        ),
        dao = db.conversionJobDao(),
        documents = db.documentDao(),
        owner = { credentialStore.getAccount()?.detailsOwner() },
        ownerFlow = credentialStore.accountFlow.map { it?.detailsOwner() },
        // Out of backups: a copy of a PDF only matters to the conversion on this device.
        sourceDir = File(appContext.noBackupFilesDir, "conversions"),
        downloadDir = File(appContext.cacheDir, "conversions"),
        importEpub = { epub, title ->
            when (val result = sideloadImporter.import(Uri.fromFile(epub), title)) {
                is SideloadResult.Imported -> ImportOutcome.Saved(result.documentId)
                is SideloadResult.AlreadyImported -> ImportOutcome.Saved(result.documentId)
                is SideloadResult.Failed ->
                    if (result.reason == SideloadFailure.InvalidEpub) ImportOutcome.Invalid else ImportOutcome.Failed
            }
        },
        wake = { ConversionScheduler.now(appContext) },
        metered = { appContext.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered },
    )

    val aiRepository: AiRepository = AiRepository(
        client = aiClient,
        insightDao = insightDao,
        // PR-γ (Bundle 3): best-effort preflight collaborators for
        // refreshProfile(). Adapted to the decoupling interfaces so
        // `:data:ai` never has to depend on `:data:sync` / `:data:library`.
        syncRunner = {
            syncOrchestrator.runOnce() is io.theficos.ereader.data.sync.SyncResult.Success
        },
        libraryRunner = {
            val result = libraryUploader.runOnce()
            !result.abortedOnAuth && result.succeeded == result.attempted
        },
    )

    /**
     * PR-η: orchestrates `/ai/v1/insights/sync` against the local cache.
     * Fired on app start (post-upload), after every promote success, after
     * /library/v1/items uploads, and on the Settings "Refresh insights"
     * button. Uses the same long-lived scope as the library uploader so
     * fire-and-forget triggers survive Activity tear-down.
     */
    val insightSyncRepository: InsightSyncRepository = InsightSyncRepository(
        client = aiClient,
        dao = insightDao,
        aiRepo = aiRepository,
        scope = libraryUploaderScope,
    )

    val libraryStatsViewModelFactory: LibraryStatsViewModelFactory =
        LibraryStatsViewModelFactory(client = libraryClient)

    /**
     * PR-γ: factory for the Library Insights screen ViewModel. Hosts a
     * `StateFlow<Boolean>` adapter over `aiRepository.preferences.aiEnabled`
     * so the VM's gating-flow contract stays a plain `StateFlow<Boolean>`
     * (rather than re-deriving inside the VM).
     */
    val aiEnabledFlow: StateFlow<Boolean> = aiRepository.preferences
        .map { it?.aiEnabled == true }
        .stateIn(
            scope = libraryUploaderScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    val libraryInsightsViewModelFactory: LibraryInsightsViewModelFactory =
        LibraryInsightsViewModelFactory(
            ai = aiRepository,
            libraryClient = libraryClient,
            progressDao = db.progressDao(),
            aiConfigFlow = aiRepository.config,
            aiEnabledFlow = aiEnabledFlow,
        )

    /** Found and tidied book details and covers, kept beside each library book. */
    val bookDetailsRepository: BookDetailsRepository = BookDetailsRepository(
        client = aiClient,
        ai = aiRepository,
        documents = documentRepository,
        dao = db.bookDetailsDao(),
        coversDir = booksDir,
        // Open Library is a third party: no account login on this client.
        openLibraryHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build(),
        showTidied = libraryPreferencesStore.flow
            .map { it.tidyDetails }
            .stateIn(libraryUploaderScope, SharingStarted.Eagerly, libraryPreferencesStore.flow.value.tidyDetails),
        owner = credentialStore.accountFlow.map { it?.detailsOwner() },
        unmetered = unmeteredNetwork(appContext).stateIn(libraryUploaderScope, SharingStarted.Eagerly, false),
        prefs = appContext.getSharedPreferences("book_details", Context.MODE_PRIVATE),
        scope = libraryUploaderScope,
    )

    val bookDetailViewModelFactory: BookDetailViewModelFactory = BookDetailViewModelFactory(
        documents = documentRepository,
        progress = progressRepository,
        ai = aiRepository,
        details = bookDetailsRepository,
        openOpfBytes = ::readOpfBytes,
    )

    val insightAuditViewModelFactory: InsightAuditViewModelFactory =
        InsightAuditViewModelFactory(
            documents = documentRepository,
            ai = aiRepository,
        )

    val catalogDetailRegistry: CatalogDetailRegistry = CatalogDetailRegistry()

    val catalogDetailViewModelFactory: CatalogDetailViewModelFactory =
        CatalogDetailViewModelFactory(
            ai = AiRepositoryAdapter(aiRepository),
            registry = catalogDetailRegistry,
            insightStash = catalogInsightStash,
            subjectProvider = ::catalogInsightScope,
            // When the user opens catalog detail for a book they already own,
            // resolve to the library-side identity so the insight cache hits
            // the same `book_insights` row the reader/library detail uses
            // (no PR-ζ promote needed — promote only fires on fresh download).
            localIdentityResolver = { href ->
                documentRepository.findByDownloadUrl(href)?.identity?.takeIf { id ->
                    id.metadataId != null || id.contentHash != null
                }
            },
        )

    /**
     * Restore-after-reinstall use case (Tasks 1–4). Assembles
     * [RestoreInProgressUseCase] with the real OPDS/sync/library/Room
     * collaborators. Unlike the catalog download path this deliberately
     * does NOT re-upload to /library/v1/items: the server mirror already
     * holds the row we joined on, so a re-upload would be redundant.
     *
     * Best-effort throughout: a missing mirror, an offline/401 progress
     * pull, or a single failed download must not abort the whole run.
     * Callers (Tasks 5/6) own when this fires.
     */
    fun restoreInProgressUseCase(): RestoreInProgressUseCase = RestoreInProgressUseCase(
        fetchLibraryItems = {
            libraryClient.listAllItems(
                onTruncated = { n -> android.util.Log.w("Restore", "library mirror truncated at $n items") },
            )
        },
        fetchInProgress = {
            when (val res = syncClient.pullProgress(java.time.Instant.EPOCH.toString())) {
                is io.theficos.ereader.data.sync.SyncResult.Success -> res.value.items
                else -> emptyList() // best-effort: offline / 401 -> nothing to restore
            }
        },
        isPresent = { identity -> documentRepository.findByIdentity(identity) != null },
        downloadAndInsert = { c ->
            // Identity hashing (whole-EPUB hash) and OPF zip parsing are plain
            // CPU/IO with no internal dispatcher; the use case runs on Main
            // (viewModelScope). Move all per-book work off the main thread to
            // avoid an ANR while restoring.
            withContext(Dispatchers.IO) {
                val fileName = "${java.util.UUID.randomUUID()}.epub"
                val file = bookDownloader.download(c.opdsHref, fileName) { _, _ -> }
                val coverFile: java.io.File? = null
                // If identity extraction / OPF read / insert throws after the bytes
                // landed, delete the temp file before rethrowing so a re-run (this
                // feature is explicitly re-runnable) doesn't accumulate orphans.
                try {
                    val identity = extractIdentity(file)
                    if (documentRepository.findByIdentity(identity) != null) {
                        file.delete()
                        coverFile?.delete()
                    } else {
                        val opf = readOpfBundle(file, fallbackTitle = c.title)
                        documentRepository.insert(
                            identity = identity,
                            title = c.title,
                            author = c.authors.firstOrNull(),
                            downloadUrl = c.opdsHref,
                            localPath = file.absolutePath,
                            catalogId = c.catalogId,
                            coverPath = coverFile?.absolutePath,
                            downloadedAt = System.currentTimeMillis(),
                            seriesName = opf.seriesName,
                            seriesIndex = opf.seriesPosition?.toDouble(),
                        )
                    }
                } catch (t: Throwable) {
                    file.delete()
                    coverFile?.delete()
                    throw t
                }
            }
        },
        applyPositions = { items -> syncOrchestrator.applyProgressItems(items) },
    )

    /**
     * Entry point for the "Restore in-progress books" action. Runs the whole use case
     * off the main thread: its network pulls (library mirror + progress) and Room reads
     * otherwise execute on the caller's viewModelScope (Main) and throw
     * NetworkOnMainThreadException. downloadAndInsert already has its own IO context; the
     * nested withContext is harmless.
     */
    suspend fun runRestoreInProgress(onProgress: (Int, Int) -> Unit): RestoreSummary =
        withContext(Dispatchers.IO) { restoreInProgressUseCase().run(onProgress) }

    fun legacyHighlightScopeFor(
        priorAccount: AccountCredentials?,
        native: AccountCredentials.Bearer,
        legacyCalibreUserId: String?,
    ): String? = priorAccount.previousHighlightScope(native, legacyCalibreUserId)

    private suspend fun migrateHighlights(account: AccountCredentials.Bearer) {
        val previous = account.previousHighlightScopes
        if (previous.isEmpty()) return
        highlightSyncer.moveOwnership(previous, account.highlightScope())
        credentialStore.completeHighlightMigration(account, previous)
        SyncEnqueuer.enqueue(appContext)
    }

    /**
     * Before saved catalogs a catalog-only device was signed in as its catalog, and its highlights
     * and reading-pause notes were kept under that sign-in. They belong to the device now.
     */
    private suspend fun releaseLegacyCatalogOwner() {
        val legacy = credentialStore.legacyCatalogOwner() ?: return
        highlightSyncer.releaseOwnership(legacy.highlightScope())
        memoryBookmarkDao.moveAccount(memoryAccountKey(legacy.baseUrl, legacy.subject), DEVICE_MEMORY_KEY)
        credentialStore.forgetLegacyCatalogOwner()
    }

    private suspend fun readOpfBytes(doc: Document): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            java.util.zip.ZipFile(doc.localPath).use { zip ->
                val container = zip.getEntry("META-INF/container.xml") ?: return@use null
                val containerXml = zip.getInputStream(container).readBytes().decodeToString()
                val opfPath = Regex("""full-path="([^"]+)"""")
                    .find(containerXml)?.groupValues?.get(1)
                    ?: return@use null
                val opfEntry = zip.getEntry(opfPath) ?: return@use null
                zip.getInputStream(opfEntry).readBytes()
            }
        }.getOrNull()
    }

    init {
        SyncDependencies.holder = SyncDependencies.Holder(syncOrchestrator)
        // Phase-0 / A-3: clean up any `*.epub.part` files leaked by an
        // import that was killed mid-copy (process death between
        // openInputStream and renameTo). Cheap, best-effort, silent.
        // Then, once per install, give books imported before covers were
        // extracted their cover from the EPUB already on disk (no network).
        libraryUploaderScope.launch {
            sideloadImporter.sweepStaleParts()
            runCatching {
                sideloadImporter.backfillCoversOnce(
                    appContext.getSharedPreferences("sideload_prefs", Context.MODE_PRIVATE),
                )
            }.onFailure { android.util.Log.w("AppContainer", "sideload cover backfill failed", it) }
        }
        // Phase 0 / A-4: wire the library-mirror push worker's DI before
        // any WorkManager run can fire. Same pattern as SyncDependencies
        // above. The `CredentialsProvider` indirection keeps the worker
        // testable without touching the Android KeyStore-backed store.
        LibraryMirrorPushDependencies.holder = LibraryMirrorPushDependencies.Holder(
            client = libraryClient,
            dao = db.documentDao(),
            credentials = CredentialsProvider { credentialStore.getAccount()?.quireServerUrlOrNull() != null },
            // Only a server that syncs catalogs is new enough to take `catalog_id` in a batch.
            sendCatalogIds = { credentialStore.catalogRevision.value != null },
        )
        ConversionDependencies.holder = ConversionDependencies.Holder(conversionRepository) {
            appContext.getString(R.string.convert_notification)
        }
        // Signing out forgets the account's PDF conversions; another account's wait for it a few days.
        libraryUploaderScope.launch {
            var signedIn: String? = null
            credentialStore.accountFlow.map { it?.detailsOwner() }.distinctUntilChanged().collect { owner ->
                val left = signedIn
                signedIn = owner
                if (owner == null && left != null) {
                    runCatching { conversionRepository.forgetOwner(left) }
                        .onFailure { android.util.Log.w("AppContainer", "Couldn't forget PDF conversions", it) }
                }
            }
        }
        // Once per launch, follow the server if it has moved to another address.
        libraryUploaderScope.launch {
            try {
                ServerAddressFollower(
                    credentialStore,
                    ConnectServerViewModel.defaultOnboardingClient(),
                    memoryBookmarkDao::moveAccount,
                    conversionRepository::moveOwner,
                ).follow()
            } catch (e: android.database.SQLException) {
                android.util.Log.w("AppContainer", "Couldn't follow the server address", e)
            }
        }
        libraryUploaderScope.launch {
            // Any failure, not only the database's: the catalog sync below must still start. The
            // owner stays recorded, so the next app start tries again.
            runCatching { releaseLegacyCatalogOwner() }
                .onFailure { android.util.Log.w("AppContainer", "Couldn't move the old catalog's highlights to the device", it) }
            credentialStore.accountFlow.collect { account ->
                if (account is AccountCredentials.Bearer) {
                    try {
                        migrateHighlights(account)
                    } catch (e: android.database.SQLException) {
                        // Keep the pending migration persisted for retry on the next app start.
                        android.util.Log.w("AppContainer", "Couldn't transfer highlight ownership", e)
                    }
                    // On app start and on every sign-in; usually an empty 304.
                    catalogSyncRepository.pull()
                }
            }
        }
        // Quietly fill in missing book details and covers, once per book, while AI is on and on Wi-Fi.
        libraryUploaderScope.launch { bookDetailsRepository.followLibrary() }
        // Discard queued promotions when either connection changes ownership.
        libraryUploaderScope.launch {
            var seen = catalogInsightScope()
            combine(credentialStore.accountFlow, credentialStore.activeCatalog) { account, active ->
                account?.catalogInsightScope(active?.catalog)
            }.collect { next ->
                if (next != seen) {
                    seen = next
                    catalogInsightStash.clearAll()
                }
            }
        }
    }
}

class BookDetailViewModelFactory(
    private val documents: DocumentRepository,
    private val progress: ProgressRepository,
    private val ai: AiRepository,
    private val details: BookDetailsRepository,
    private val openOpfBytes: suspend (Document) -> ByteArray?,
) {
    fun create(documentId: Long) = BookDetailViewModel(
        documentId = documentId,
        documents = documents,
        progress = progress,
        ai = ai,
        details = details,
        openOpfBytes = openOpfBytes,
    )
}

class InsightAuditViewModelFactory(
    private val documents: DocumentRepository,
    private val ai: AiRepository,
) {
    fun create(documentId: Long) = InsightAuditViewModel(
        documentId = documentId,
        source = AppInsightAuditSource(documents = documents, ai = ai),
    )
}

class CatalogDetailViewModelFactory(
    private val ai: CatalogAiPort,
    private val registry: CatalogDetailRegistry,
    private val insightStash: CatalogInsightStash? = null,
    private val subjectProvider: () -> String? = { null },
    /** Maps an OPDS `epubDownloadHref` to the LIBRARY identity when the
     *  user already owns the book, else returns null. */
    private val localIdentityResolver: suspend (String) -> DocumentIdentity? = { null },
) {
    /**
     * Look up the [OpdsPublication] by nav key and build the viewmodel.
     * Returns null if the key is unknown (typically because the process
     * died and the in-memory registry was reset). Callers render a
     * graceful fallback in that case.
     */
    fun create(key: String): CatalogDetailViewModel? {
        val pub = registry.get(key) ?: return null
        return CatalogDetailViewModel(
            publication = pub,
            ai = ai,
            insightStash = insightStash,
            subjectProvider = subjectProvider,
            localIdentityResolver = localIdentityResolver,
        )
    }
}

class LibraryStatsViewModelFactory(
    private val client: LibraryClient,
) {
    // Process-lifetime cache. Held here (not on the ViewModel) so that
    // navigating away from the Stats screen and back gives the new VM
    // instance an immediate `Ready(cached)` — i.e. real SWR across VMs.
    private val cache = LibraryStatsCache()

    fun create() = LibraryStatsViewModel(
        fetch = { client.getStats() },
        cache = cache,
    )
}

class LibraryInsightsViewModelFactory(
    private val ai: AiRepository,
    private val libraryClient: LibraryClient,
    private val progressDao: ProgressDao,
    private val aiConfigFlow: StateFlow<io.theficos.ereader.data.ai.AiConfig?>,
    private val aiEnabledFlow: StateFlow<Boolean>,
) {
    fun create() = LibraryInsightsViewModel(
        ai = ai,
        libraryClient = libraryClient,
        progressDao = progressDao,
        aiConfigFlow = aiConfigFlow,
        aiEnabledFlow = aiEnabledFlow,
    )
}
