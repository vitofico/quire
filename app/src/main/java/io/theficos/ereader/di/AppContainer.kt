package io.theficos.ereader.di

import android.content.Context
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.core.identity.extractIdentity
import io.theficos.ereader.core.metadata.readOpfBundle
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.ai.AiClient
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.ai.CatalogInsightStash
import io.theficos.ereader.data.ai.InsightSyncRepository
import io.theficos.ereader.data.library.LibraryClient
import io.theficos.ereader.data.library.LibraryUploader
import io.theficos.ereader.data.library.sync.CredentialsProvider
import io.theficos.ereader.data.library.sync.LibraryMirrorPushDependencies
import io.theficos.ereader.data.library.sync.LibraryMirrorPushScheduler
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.local.db.ProgressDao
import io.theficos.ereader.data.opds.BookDownloader
import io.theficos.ereader.data.opds.OpdsClient
import io.theficos.ereader.data.opds.OpdsHttpClient
import io.theficos.ereader.data.sync.SyncClient
import io.theficos.ereader.data.sync.SyncDependencies
import io.theficos.ereader.data.sync.SyncEnqueuer
import io.theficos.ereader.data.sync.SyncOrchestrator
import io.theficos.ereader.domain.restore.RestoreInProgressUseCase
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import io.theficos.ereader.sideload.SideloadImporter
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
import io.theficos.ereader.ui.onboarding.WelcomePreferencesStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * URL that sync/library/AI clients should target. For [AccountCredentials.Basic]
 * with a [AccountCredentials.Basic.quireServerUrl] override, return the
 * override; otherwise fall back to [AccountCredentials.baseUrl]. Bearer
 * accounts have no override in tier-1.
 */
private fun io.theficos.ereader.auth.AccountCredentials.quireServerOrPrimaryUrl(): String =
    (this as? io.theficos.ereader.auth.AccountCredentials.Basic)?.quireServerUrl ?: baseUrl

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val credentialStore: CalibreCredentialStore = CalibreCredentialStore(appContext)

    val opdsHttp = OpdsHttpClient(credentialStore)
    val opdsClient: OpdsClient = OpdsClient(opdsHttp.okHttp)
    val booksDir: File = File(appContext.filesDir, "books")
    val bookDownloader: BookDownloader = BookDownloader(
        okHttp = opdsHttp.okHttp,
        booksDir = booksDir,
    )

    private val db: EReaderDatabase = EReaderDatabase.build(appContext)
    val documentRepository = DocumentRepository(db.documentDao())
    val progressRepository = ProgressRepository(db.progressDao())
    val syncStateDao = db.syncStateDao()
    val readiumFactory = ReadiumFactory(appContext)
    val readerPreferencesStore = ReaderPreferencesStore(appContext)
    val libraryPreferencesStore = LibraryPreferencesStore(appContext)
    val catalogPreferencesStore = CatalogPreferencesStore(appContext)
    /**
     * A-2: tracks whether the user finished (or deliberately skipped) the
     * first-launch flow. Combined with [credentialStore] presence to pick
     * the navgraph's start destination.
     */
    val welcomePreferencesStore = WelcomePreferencesStore(appContext)

    val syncClient: SyncClient = SyncClient(
        baseUrlProvider = { credentialStore.getAccount()?.quireServerOrPrimaryUrl() },
        okHttp = opdsHttp.okHttp,
    )
    val syncOrchestrator: SyncOrchestrator = SyncOrchestrator(
        client = syncClient,
        progressRepo = progressRepository,
        progressDao = db.progressDao(),
        documentRepo = documentRepository,
        syncState = syncStateDao,
    )

    val aiClient: AiClient = AiClient(
        baseUrlProvider = { credentialStore.getAccount()?.quireServerOrPrimaryUrl() },
        http = opdsHttp.okHttp,
    )
    val insightDao = db.insightDao()

    /**
     * PR-ζ / Lock #16: process-local stash for the catalog → download
     * insight promote handoff. Cleared on AI opt-out toggle (PR-δ owns
     * that hook) and on base-URL change (we observe credentialStore.flow
     * below to invoke clearAll on any baseUrl transition).
     */
    val catalogInsightStash: CatalogInsightStash = CatalogInsightStash()

    /**
     * Subject identifier used to partition the [catalogInsightStash] and
     * the promote alias. Delegated to [AccountCredentials.subject] so the
     * scheme owns the canonical form: lowercased calibre-web username for
     * BASIC, lowercased email for BEARER. Matches the server's
     * `principal.subject` derivation in both `auth_mode=basic` and
     * `auth_mode=token`. See `docs/sync-api.md`.
     */
    private fun currentSubject(): String? =
        credentialStore.getAccount()?.subject

    val libraryClient: LibraryClient = LibraryClient(
        baseUrlProvider = { credentialStore.getAccount()?.quireServerOrPrimaryUrl() },
        http = opdsHttp.okHttp,
    )

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

    val aiRepository: AiRepository = AiRepository(
        client = aiClient,
        insightDao = insightDao,
        // pr-α (Bundle 3): wired so `markAbandoned`/`unmarkAbandoned`
        // can flip the Room row's `abandonedAt` without a separate DAO.
        progressDao = db.progressDao(),
        // PR-γ (Bundle 3): best-effort preflight collaborators for
        // refreshProfile(). Adapted to the decoupling interfaces so
        // `:data:ai` never has to depend on `:data:sync` / `:data:library`.
        syncRunner = {
            syncOrchestrator.runOnce() is io.theficos.ereader.data.sync.SyncResult.Success
        },
        libraryRunner = {
            val result = libraryUploader.runOnce()
            !result.abortedOnAuth
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

    val bookDetailViewModelFactory: BookDetailViewModelFactory = BookDetailViewModelFactory(
        documents = documentRepository,
        ai = aiRepository,
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
            subjectProvider = ::currentSubject,
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
        libraryUploaderScope.launch { sideloadImporter.sweepStaleParts() }
        // Phase 0 / A-4: wire the library-mirror push worker's DI before
        // any WorkManager run can fire. Same pattern as SyncDependencies
        // above. The `CredentialsProvider` indirection keeps the worker
        // testable without touching the Android KeyStore-backed store.
        LibraryMirrorPushDependencies.holder = LibraryMirrorPushDependencies.Holder(
            client = libraryClient,
            dao = db.documentDao(),
            credentials = CredentialsProvider { credentialStore.get() != null },
        )
        // PR-ζ: clear the catalog stash whenever the server base URL
        // changes (different deploy → entries are no longer relevant). The
        // AI opt-out toggle hook lives in PR-δ (Bundle 3); until then a
        // stale stash entry is harmless — its TTL expires within 30 min.
        libraryUploaderScope.launch {
            var seen: String? = credentialStore.getAccount()?.baseUrl
            // Observe the scheme-aware account flow so a BASIC → BEARER
            // re-onboarding (different baseUrl) also clears the stash.
            credentialStore.accountFlow.collect { account ->
                val next = account?.baseUrl
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
    private val ai: AiRepository,
    private val openOpfBytes: suspend (Document) -> ByteArray?,
) {
    fun create(documentId: Long) = BookDetailViewModel(
        documentId = documentId,
        documents = documents,
        ai = ai,
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
