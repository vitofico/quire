package io.theficos.ereader.core.metadata

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream

/**
 * Finds the cover image an OPF declares, as a path inside the EPUB zip.
 *
 * Kept apart from [MetadataBundle] on purpose: that class is the wire shape the
 * server's AI endpoint reads, and a zip path is only meaningful on this device.
 */
object OpfCoverLocator {

    private val IMAGE_MEDIA_TYPES = setOf("image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp")
    private val URI_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Zip entry path of the cover image, or null when the OPF declares none.
     *
     * Looks first for EPUB 3's manifest item whose `properties` include `cover-image`, then
     * for EPUB 2's `<meta name="cover" content="<manifest id>"/>`. Only image media types count:
     * a `<guide>` cover reference points at an XHTML page, not a picture, so it is ignored.
     * The href is percent-decoded and resolved against the folder holding the OPF
     * ([opfPath] is the OPF's own zip path, e.g. `OEBPS/content.opf`).
     */
    fun coverImagePath(opfBytes: ByteArray, opfPath: String): String? {
        val doc = parseOpfDocument(opfBytes) ?: return null
        val items = doc.getElementsByTagNameNS("*", "item").let { nodes ->
            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
                .filter { (it.parentNode as? Element)?.localName == "manifest" }
        }

        val cover = items.firstOrNull { item ->
            "cover-image" in item.getAttribute("properties").split(WHITESPACE) && item.isImage()
        } ?: epub2CoverId(doc)?.let { id ->
            items.firstOrNull { it.getAttribute("id") == id && it.isImage() }
        }
        val href = cover?.getAttribute("href")?.takeIf { it.isNotBlank() } ?: return null
        return resolve(opfPath, href)
    }

    private fun epub2CoverId(doc: Document): String? {
        val metas = doc.getElementsByTagNameNS("*", "meta")
        for (i in 0 until metas.length) {
            val meta = metas.item(i) as? Element ?: continue
            if (meta.getAttribute("name") == "cover") {
                return meta.getAttribute("content").trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun Element.isImage(): Boolean =
        getAttribute("media-type").trim().lowercase() in IMAGE_MEDIA_TYPES

    /**
     * Resolves [href] against the OPF's folder into a normalised zip path. Returns null for
     * remote resources and for paths that climb out of the container.
     */
    private fun resolve(opfPath: String, href: String): String? {
        val raw = href.substringBefore('#').substringBefore('?').trim()
        if (raw.isEmpty() || URI_SCHEME.containsMatchIn(raw)) return null
        val decoded = percentDecode(raw)
        val base = if (decoded.startsWith("/")) "" else opfPath.substringBeforeLast('/', "")
        val segments = ArrayDeque<String>()
        for (segment in "$base/$decoded".split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull() ?: return null
                else -> segments.addLast(segment)
            }
        }
        return segments.joinToString("/").takeIf { it.isNotEmpty() }
    }

    /** RFC 3986 percent-decoding as UTF-8. Unlike URLDecoder, a `+` stays a `+`. */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder(s.length)
        val bytes = ByteArrayOutputStream()
        fun flushBytes() {
            if (bytes.size() > 0) out.append(String(bytes.toByteArray(), Charsets.UTF_8))
            bytes.reset()
        }
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length && s[i + 1].isHex() && s[i + 2].isHex()) {
                bytes.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                flushBytes()
                out.append(s[i])
                i++
            }
        }
        flushBytes()
        return out.toString()
    }

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
