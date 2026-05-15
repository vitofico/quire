package io.theficos.ereader.core.metadata

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Extracts a [MetadataBundle] from an OPF document's bytes.
 *
 * Tolerant: missing OPF, malformed XML, or missing `<metadata>` produces a
 * minimal bundle (title only, possibly empty). The caller decides what to do
 * with that.
 */
object OpfMetadataExtractor {

    fun extract(opfBytes: ByteArray, fallbackTitle: String): MetadataBundle {
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
                isXIncludeAware = false
                isExpandEntityReferences = false
                // Hardening: block DOCTYPE entirely, then defence-in-depth for parsers that ignore the above.
                safeSetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                safeSetFeature("http://xml.org/sax/features/external-general-entities", false)
                safeSetFeature("http://xml.org/sax/features/external-parameter-entities", false)
                safeSetFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            factory.newDocumentBuilder().parse(opfBytes.inputStream())
        } catch (_: Exception) {
            return MetadataBundle(title = fallbackTitle)
        }
        val metadataElems = doc.getElementsByTagNameNS("*", "metadata")
        if (metadataElems.length == 0) {
            return MetadataBundle(title = fallbackTitle)
        }
        val metadata = metadataElems.item(0) as Element

        val title = textOf(metadata, "title") ?: fallbackTitle
        val author = textOf(metadata, "creator")
        val language = textOf(metadata, "language")
        val publisher = textOf(metadata, "publisher")
        val publishDate = textOf(metadata, "date")
        val description = textOf(metadata, "description")
        val isbn = identifiersOf(metadata)
            .firstOrNull { it.startsWith("urn:isbn:") || isPlausibleIsbn(it) }
            ?.removePrefix("urn:isbn:")
            ?.removePrefix("isbn:")
            ?.replace("-", "")
            ?.replace(" ", "")
        val subjects = collectText(metadata, "subject")

        // EPUB 3 series via belongs-to-collection (Calibre-style)
        val (seriesName, seriesPosition) = parseSeries(metadata)

        return MetadataBundle(
            title = title.trim(),
            author = author?.trim(),
            language = language?.trim()?.lowercase(),
            isbn = isbn,
            publisher = publisher?.trim(),
            publishDate = publishDate?.trim(),
            subjects = subjects,
            description = description?.trim(),
            seriesName = seriesName?.trim(),
            seriesPosition = seriesPosition,
        )
    }

    private fun textOf(parent: Element, localName: String): String? {
        val nodes = parent.getElementsByTagNameNS("*", localName)
        if (nodes.length == 0) return null
        return nodes.item(0).textContent?.takeIf { it.isNotBlank() }
    }

    private fun collectText(parent: Element, localName: String): List<String> {
        val nodes = parent.getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).mapNotNull { i ->
            nodes.item(i).textContent?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun identifiersOf(parent: Element): List<String> {
        val nodes = parent.getElementsByTagNameNS("*", "identifier")
        return (0 until nodes.length).mapNotNull { i ->
            nodes.item(i).textContent?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun isPlausibleIsbn(s: String): Boolean {
        val cleaned = s.replace("-", "").replace(" ", "")
        return cleaned.length in setOf(10, 13) && cleaned.all { it.isDigit() || it == 'X' }
    }

    /**
     * EPUB 3 collection metadata. Looks for:
     *   <meta property="belongs-to-collection" id="c01">Foundation</meta>
     *   <meta refines="#c01" property="group-position">1</meta>
     * Falls back to Calibre's older format:
     *   <meta name="calibre:series" content="Foundation"/>
     *   <meta name="calibre:series_index" content="1"/>
     */
    private fun parseSeries(metadata: Element): Pair<String?, Int?> {
        val metas = metadata.getElementsByTagNameNS("*", "meta")
        var calibreName: String? = null
        var calibreIndex: Int? = null
        for (i in 0 until metas.length) {
            val m = metas.item(i) as? Element ?: continue
            when (m.getAttribute("name")) {
                "calibre:series" -> calibreName = m.getAttribute("content").takeIf { it.isNotBlank() }
                "calibre:series_index" ->
                    calibreIndex = m.getAttribute("content").toFloatOrNull()?.toInt()
            }
        }
        if (calibreName != null) return calibreName to calibreIndex

        var name: String? = null
        var position: Int? = null
        var collectionId: String? = null
        for (i in 0 until metas.length) {
            val m = metas.item(i) as? Element ?: continue
            if (m.getAttribute("property") == "belongs-to-collection") {
                name = m.textContent?.trim().takeIf { !it.isNullOrEmpty() }
                collectionId = m.getAttribute("id").takeIf { it.isNotBlank() }
                break
            }
        }
        if (collectionId != null) {
            for (i in 0 until metas.length) {
                val m = metas.item(i) as? Element ?: continue
                if (m.getAttribute("refines") == "#$collectionId" &&
                    m.getAttribute("property") == "group-position"
                ) {
                    position = m.textContent?.trim()?.toFloatOrNull()?.toInt()
                }
            }
        }
        return name to position
    }

    private fun DocumentBuilderFactory.safeSetFeature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // Provider doesn't recognise this feature; the other hardening features will catch it.
        }
    }
}
