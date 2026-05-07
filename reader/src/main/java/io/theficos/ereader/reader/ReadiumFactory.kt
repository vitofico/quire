package io.theficos.ereader.reader

import android.util.Log
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

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

    private companion object {
        const val TAG = "ReadiumFactory"
    }

    suspend fun open(asset: EpubAsset): Publication = withContext(Dispatchers.IO) {
        Log.i(TAG, "opening ${asset.file}")
        val retrieveResult = assetRetriever.retrieve(asset.file)
        val readiumAsset = retrieveResult.getOrNull()
            ?: error("AssetRetriever could not open ${asset.file}: ${retrieveResult.failureOrNull()}")
        // Pass the relaxer via open() rather than only via the constructor — the
        // per-call hook reliably runs in Readium 3.0.0 even if the constructor
        // default chain doesn't.
        val openResult = publicationOpener.open(
            asset = readiumAsset,
            allowUserInteraction = false,
            onCreatePublication = { relaxXhtml() },
        )
        val publication = openResult.getOrNull()
            ?: error("PublicationOpener could not open ${asset.file}: ${openResult.failureOrNull()}")
        Log.i(TAG, "opened ${asset.file}: ${publication.metadata.title}")
        publication
    }

    private fun Publication.Builder.relaxXhtml() {
        val xhtmlCount = (manifest.readingOrder + manifest.resources).count {
            it.mediaType.toString().contains("xhtml", ignoreCase = true)
        }
        Log.i(TAG, "relaxXhtml: rewriting $xhtmlCount XHTML links")
        manifest = manifest.copy(
            readingOrder = manifest.readingOrder.map { it.relaxedHtml() },
            resources = manifest.resources.map { it.relaxedHtml() },
        )
    }

    private fun Link.relaxedHtml(): Link {
        val mtString = mediaType.toString()
        return if (mtString.contains("xhtml", ignoreCase = true)) {
            Log.d(TAG, "relax: $href $mtString -> text/html")
            copy(mediaType = MediaType.HTML)
        } else this
    }
}
