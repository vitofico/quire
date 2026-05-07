package io.theficos.ereader.data.opds

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.readium.r2.opds.OPDS1Parser
import org.readium.r2.shared.util.AbsoluteUrl
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

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
            // Readium silently drops links whose href contains template chars like `{searchTerms}`,
            // so we always look for rel="search" ourselves from the raw XML.
            val searchLink = parseSearchLink(bytes, absoluteUrl)
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
                    val imageLinks = pub.subcollections["images"].orEmpty().flatMap { it.links }
                    val coverLink = imageLinks.firstOrNull { link ->
                        link.rels.contains("http://opds-spec.org/image")
                    } ?: imageLinks.firstOrNull { link ->
                        link.rels.contains("http://opds-spec.org/image/thumbnail")
                    } ?: imageLinks.firstOrNull()
                    OpdsPublication(
                        title = pub.metadata.title.orEmpty(),
                        author = pub.metadata.authors.firstOrNull()?.name,
                        epubDownloadHref = absolutize(absoluteUrl, epubLink.href.toString()),
                        coverUrl = coverLink?.href?.toString()?.let { absolutize(absoluteUrl, it) },
                    )
                },
                searchLink = searchLink,
            )
        }
    }

    suspend fun resolveSearchUrl(link: OpdsSearchLink, query: String): String {
        // Substitute {searchTerms} into the raw template *before* absolutising,
        // otherwise URL resolution percent-encodes the braces and the substitution misses.
        val rawTemplate = if (link.isDescription) fetchSearchTemplate(link.href) else link.href
        return absolutize(link.baseUrl, applyTemplate(rawTemplate, query))
    }

    private fun parseSearchLink(bytes: ByteArray, feedUrl: String): OpdsSearchLink? {
        val doc = runCatching {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(bytes))
        }.getOrNull() ?: return null
        val links = doc.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "link")
        for (i in 0 until links.length) {
            val el = links.item(i) as org.w3c.dom.Element
            // Only top-level feed links, not entry links.
            if (el.parentNode?.localName != "feed") continue
            if (el.getAttribute("rel") != "search") continue
            val href = el.getAttribute("href").takeIf { it.isNotBlank() } ?: continue
            val type = el.getAttribute("type")
            val isDescription = type.startsWith("application/opensearchdescription+xml")
            return if (isDescription) {
                val absolute = absolutize(feedUrl, href)
                OpdsSearchLink(href = absolute, baseUrl = absolute, isDescription = true)
            } else {
                OpdsSearchLink(href = href, baseUrl = feedUrl, isDescription = false)
            }
        }
        return null
    }

    private suspend fun fetchSearchTemplate(descriptionUrl: String): String = withContext(Dispatchers.IO) {
        val response = okHttp.newCall(Request.Builder().url(descriptionUrl).get().build()).execute()
        response.use {
            require(it.isSuccessful) { "OpenSearch description fetch ${it.code} for $descriptionUrl" }
            val bytes = it.body!!.bytes()
            val doc = DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(bytes))
            val urls = doc.getElementsByTagNameNS("http://a9.com/-/spec/opensearch/1.1/", "Url")
            var atomTemplate: String? = null
            var fallbackTemplate: String? = null
            for (i in 0 until urls.length) {
                val el = urls.item(i) as org.w3c.dom.Element
                val template = el.getAttribute("template").takeIf { t -> t.isNotBlank() } ?: continue
                if (!template.contains("{searchTerms}")) continue
                val type = el.getAttribute("type")
                if (type.startsWith("application/atom+xml")) {
                    atomTemplate = template; break
                }
                if (fallbackTemplate == null) fallbackTemplate = template
            }
            atomTemplate ?: fallbackTemplate
                ?: error("OpenSearch description has no Url template with {searchTerms}")
        }
    }

    private fun applyTemplate(template: String, query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        // OpenSearch optional parameters use a trailing "?" — clear them; substitute searchTerms.
        return OPTIONAL_PARAM.replace(template) { "" }
            .replace("{searchTerms}", encoded)
    }

    private fun absolutize(base: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val baseUrl = base.toHttpUrl()
        val resolved = baseUrl.resolve(href) ?: return href
        return resolved.toString()
    }

    private companion object {
        private val OPTIONAL_PARAM = Regex("""\{[^{}]+\?\}""")
    }
}
