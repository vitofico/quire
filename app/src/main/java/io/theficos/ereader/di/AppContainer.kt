package io.theficos.ereader.di

import android.content.Context
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.opds.BookDownloader
import io.theficos.ereader.data.opds.OpdsClient
import io.theficos.ereader.data.opds.OpdsHttpClient
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import java.io.File

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val credentialStore: CalibreCredentialStore = CalibreCredentialStore(appContext)
    private val httpClient = OpdsHttpClient(credentialStore)
    val opdsClient: OpdsClient = OpdsClient(httpClient.okHttp)
    val bookDownloader: BookDownloader = BookDownloader(
        okHttp = httpClient.okHttp,
        booksDir = File(appContext.filesDir, "books"),
    )
    private val db: EReaderDatabase = EReaderDatabase.build(appContext)
    val documentRepository = DocumentRepository(db.documentDao())
    val progressRepository = ProgressRepository(db.progressDao())
    val readiumFactory = ReadiumFactory(appContext)
    val readerPreferencesStore = ReaderPreferencesStore(appContext)
}
