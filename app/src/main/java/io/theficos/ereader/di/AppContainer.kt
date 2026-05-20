package io.theficos.ereader.di

import android.content.Context
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.data.ai.AiClient
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.ai.CatalogInsightStash
import io.theficos.ereader.data.ai.InsightSyncRepository
import io.theficos.ereader.data.library.LibraryClient
import io.theficos.ereader.data.library.LibraryUploader
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.opds.BookDownloader
import io.theficos.ereader.data.opds.OpdsClient
import io.theficos.ereader.data.opds.OpdsHttpClient
import io.theficos.ereader.data.sync.SyncClient
import io.theficos.ereader.data.sync.SyncDependencies
import io.theficos.ereader.data.sync.SyncOrchestrator
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import io.theficos.ereader.ui.bookdetail.AppInsightAuditSource
import io.theficos.ereader.ui.bookdetail.BookDetailViewModel
import io.theficos.ereader.ui.bookdetail.InsightAuditViewModel
import io.theficos.ereader.ui.catalog.CatalogPreferencesStore
import io.theficos.ereader.ui.catalogdetail.AiRepositoryAdapter
import io.theficos.ereader.ui.catalogdetail.CatalogAiPort
import io.theficos.ereader.ui.catalogdetail.CatalogDetailRegistry
import io.theficos.ereader.ui.catalogdetail.CatalogDetailViewModel
import io.theficos.ereader.ui.library.LibraryPreferencesStore
import io.theficos.ereader.ui.library.LibraryStatsViewModel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val syncClient: SyncClient = SyncClient(
        baseUrlProvider = { credentialStore.get()?.baseUrl },
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
        baseUrlProvider = { credentialStore.get()?.baseUrl },
        http = opdsHttp.okHttp,
    )
    val insightDao = db.insightDao()
    val aiRepository: AiRepository = AiRepository(
        client = aiClient,
        insightDao = insightDao,
        // pr-α (Bundle 3): wired so `markAbandoned`/`unmarkAbandoned`
        // can flip the Room row's `abandonedAt` without a separate DAO.
        progressDao = db.progressDao(),
    )

    /**
     * PR-ζ / Lock #16: process-local stash for the catalog → download
     * insight promote handoff. Cleared on AI opt-out toggle (PR-δ owns
     * that hook) and on base-URL change (we observe credentialStore.flow
     * below to invoke clearAll on any baseUrl transition).
     */
    val catalogInsightStash: CatalogInsightStash = CatalogInsightStash()

    /**
     * Subject identifier used to partition the [catalogInsightStash] and
     * the promote alias. We use the calibre-web username (case-normalized
     * to match the server's basic-auth principal) which mirrors the value
     * the server uses for `principal.subject` under default basic auth.
     */
    private fun currentSubject(): String? =
        credentialStore.get()?.username?.lowercase()

    val libraryClient: LibraryClient = LibraryClient(
        baseUrlProvider = { credentialStore.get()?.baseUrl },
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
        // PR-ζ: clear the catalog stash whenever the server base URL
        // changes (different deploy → entries are no longer relevant). The
        // AI opt-out toggle hook lives in PR-δ (Bundle 3); until then a
        // stale stash entry is harmless — its TTL expires within 30 min.
        libraryUploaderScope.launch {
            var seen: String? = credentialStore.get()?.baseUrl
            credentialStore.flow.collect { creds ->
                val next = creds?.baseUrl
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
        )
    }
}

class LibraryStatsViewModelFactory(
    private val client: LibraryClient,
) {
    fun create() = LibraryStatsViewModel(fetch = { client.getStats() })
}
