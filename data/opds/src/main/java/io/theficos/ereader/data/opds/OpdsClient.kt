package io.theficos.ereader.data.opds

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.readium.r2.opds.OPDS1Parser
import org.readium.r2.shared.util.AbsoluteUrl

class OpdsClient(
    private val okHttp: OkHttpClient,
) {
    suspend fun fetch(absoluteUrl: String): OpdsFeed = withContext(Dispatchers.IO) {
        val response = okHttp.newCall(Request.Builder().url(absoluteUrl).get().build()).execute()
        response.use {
            require(it.isSuccessful) { "OPDS fetch ${it.code} for $absoluteUrl" }
            val bytes = it.body!!.bytes()
            val baseUrl = AbsoluteUrl(absoluteUrl)
                ?: error("Not an absolute URL: $absoluteUrl")
            val parsed = OPDS1Parser.parse(bytes, baseUrl)
            val feed = parsed.feed ?: error("Parsed OPDS payload had no feed")
            OpdsFeed(
                title = feed.metadata.title,
                navigation = feed.navigation.map { link ->
                    OpdsNavigationLink(
                        title = link.title.orEmpty(),
                        href = absolutize(absoluteUrl, link.href.toString()),
                    )
                },
                publications = feed.publications.mapNotNull { pub ->
                    val epubLink = pub.links.firstOrNull { link ->
                        link.rels.contains("http://opds-spec.org/acquisition") &&
                            link.mediaType.toString() == "application/epub+zip"
                    } ?: return@mapNotNull null
                    OpdsPublication(
                        title = pub.metadata.title.orEmpty(),
                        author = pub.metadata.authors.firstOrNull()?.name,
                        epubDownloadHref = absolutize(absoluteUrl, epubLink.href.toString()),
                        coverHref = null, // covers deferred — Readium 3.0.0's cover API exposes Bitmap, not href
                    )
                },
            )
        }
    }

    private fun absolutize(base: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val baseUrl = base.toHttpUrl()
        val resolved = baseUrl.resolve(href) ?: return href
        return resolved.toString()
    }
}
