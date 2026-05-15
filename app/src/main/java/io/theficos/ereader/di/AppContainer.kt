package io.theficos.ereader.di

import android.content.Context
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.data.ai.AiClient
import io.theficos.ereader.data.ai.AiRepository
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
import io.theficos.ereader.ui.bookdetail.BookDetailViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
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
    val aiRepository: AiRepository = AiRepository(aiClient)

    val bookDetailViewModelFactory: BookDetailViewModelFactory = BookDetailViewModelFactory(
        documents = documentRepository,
        ai = aiRepository,
        openOpfBytes = ::readOpfBytes,
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
