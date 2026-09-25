package io.theficos.ereader.reader

import android.util.Log
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

class ReadiumFactory(context: Context) {

    private val appContext = context.applicationContext
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(appContext.contentResolver, httpClient)
    private val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = appContext,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = null,
        ),
    )
    private val xhtmlVerdicts = XhtmlVerdicts(File(appContext.cacheDir, "xhtml-verdicts"))

    private companion object {
        const val TAG = "ReadiumFactory"
    }

    suspend fun open(asset: EpubAsset): Publication = withContext(Dispatchers.IO) {
        Log.i(TAG, "opening ${asset.file}")
        val retrieveResult = assetRetriever.retrieve(asset.file)
        val readiumAsset = retrieveResult.getOrNull()
            ?: error("AssetRetriever could not open ${asset.file}: ${retrieveResult.failureOrNull()}")
        // Readium 3.0.0 calls this per-call hook twice on the same builder (the parameter
        // shadows its constructor-level hook, which never runs), so only the first call reads
        // the book.
        var prepared = false
        val openResult = publicationOpener.open(
            asset = readiumAsset,
            allowUserInteraction = false,
            onCreatePublication = {
                if (!prepared) {
                    prepared = true
                    prepareDocuments(asset.file)
                }
            },
        )
        val publication = openResult.getOrNull()
            ?: error("PublicationOpener could not open ${asset.file}: ${openResult.failureOrNull()}")
        Log.i(TAG, "opened ${asset.file}: ${publication.metadata.title}")
        publication
    }

    /**
     * Serves every document in UTF-8, and hands the XHTML documents the WebView's XML parser
     * would reject to its HTML parser instead.
     */
    private fun Publication.Builder.prepareDocuments(book: File) {
        val started = System.nanoTime()
        val cached = xhtmlVerdicts[book]
        // The check reads each document as the WebView will get it, in UTF-8.
        val malformed = cached
            ?: runBlocking { manifest.malformedXhtml(container.servingDocuments(manifest)) }
                .also { xhtmlVerdicts[book] = it }
        val source = if (cached != null) "cached" else "checked in ${(System.nanoTime() - started) / 1_000_000} ms"
        Log.i(TAG, "relaxXhtml: ${malformed.size} XHTML documents need the HTML parser ($source)")
        manifest = manifest.relaxing(malformed)
        container = container.servingDocuments(manifest)
    }
}
