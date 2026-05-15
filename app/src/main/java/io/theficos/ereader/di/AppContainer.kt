package io.theficos.ereader.di

import android.content.Context
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.ai.AiClient
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.opds.BookDownloader
import io.theficos.ereader.data.opds.OpdsClient
import io.theficos.ereader.data.opds.OpdsHttpClient
import io.theficos.ereader.data.sync.SyncClient
import io.theficos.ereader.data.sync.SyncDependencies
import io.theficos.ereader.data.sync.SyncOrchestrator
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import java.io.File

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

    init {
        SyncDependencies.holder = SyncDependencies.Holder(syncOrchestrator)
    }
}
