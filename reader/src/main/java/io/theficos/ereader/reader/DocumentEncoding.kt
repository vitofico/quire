package io.theficos.ereader.reader

import java.nio.charset.Charset

/**
 * Returns [bytes], an HTML or XHTML document, in UTF-8, with every declaration of its encoding
 * rewritten to say so. A document already in UTF-8, or in an encoding Java does not know, comes
 * back unchanged: the same array, not a copy.
 *
 * Readium 3.0.0 decodes each document as UTF-8 when it injects its CSS and scripts, and serves the
 * result as UTF-8, whatever the document declares. So a UTF-16 chapter reaches the WebView as
 * noise it refuses to load (`net::ERR_FAILED`), the é of an ISO-8859-1 "café" becomes U+FFFD and
 * is then read back as Latin-1 ("cafï¿½"), and Shift_JIS text is lost outright. A document that is
 * already UTF-8 survives that round trip unchanged.
 *
 * The encoding is taken from where the WebView would take it: a byte-order mark, then the XML
 * declaration, then, for a document served as HTML ([html]), a `<meta>` charset. An XML parser
 * ignores `<meta>`, so for XHTML the default is UTF-8.
 */
internal fun utf8Document(bytes: ByteArray, html: Boolean): ByteArray {
    val source = sourceEncoding(bytes, html) ?: return bytes
    val text = String(bytes, source.bom, bytes.size - source.bom, source.charset)
    return declaringUtf8(text).toByteArray(Charsets.UTF_8)
}

private class SourceEncoding(val charset: Charset, val bom: Int = 0)

/** The encoding [bytes] must be decoded from, or null when they are to be served as they are. */
private fun sourceEncoding(bytes: ByteArray, html: Boolean): SourceEncoding? {
    when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> return null
        bytes.startsWith(0xFF, 0xFE) -> return SourceEncoding(Charsets.UTF_16LE, bom = 2)
        bytes.startsWith(0xFE, 0xFF) -> return SourceEncoding(Charsets.UTF_16BE, bom = 2)
        // No byte-order mark, but an XML declaration's "<?" spelled in UTF-16.
        bytes.startsWith(0x3C, 0x00, 0x3F, 0x00) -> return SourceEncoding(Charsets.UTF_16LE)
        bytes.startsWith(0x00, 0x3C, 0x00, 0x3F) -> return SourceEncoding(Charsets.UTF_16BE)
    }
    val prolog = String(bytes, 0, minOf(bytes.size, PRESCAN_BYTES), Charsets.ISO_8859_1)
    val label = XML_ENCODING.find(prolog)?.groupValues?.get(1)
        ?: META_CHARSET.takeIf { html }?.find(prolog)?.groupValues?.get(1)
        ?: return null
    val declared = runCatching { Charset.forName(label.trim()) }.getOrNull() ?: return null
    val charset = webCharset(declared)
    return if (declared == Charsets.UTF_8 && charset == Charsets.UTF_8) null else SourceEncoding(charset)
}

/**
 * The charset a browser decodes a document labelled [declared] with.
 *
 * The web's encoding standard reads several legacy labels as the superset documents so labelled
 * actually use, such as ISO-8859-1 as windows-1252, whose curly quotes sit in 0x80 to 0x9F. And
 * a label that is not ASCII-compatible (UTF-16, UTF-32) contradicts the ASCII bytes it was just
 * read from, so browsers take UTF-8 instead.
 */
private fun webCharset(declared: Charset): Charset {
    WEB_SUPERSETS[declared.name()]
        ?.let { runCatching { Charset.forName(it) }.getOrNull() }
        ?.let { return it }
    val asciiCompatible = runCatching {
        ASCII_PROBE.toByteArray(declared).contentEquals(ASCII_PROBE.toByteArray(Charsets.US_ASCII))
    }.getOrDefault(true)
    return if (asciiCompatible) declared else Charsets.UTF_8
}

/** [text] with the XML declaration and any `<meta>` charset in its head naming UTF-8. */
private fun declaringUtf8(text: String): String {
    val out = StringBuilder(text)
    fun MatchResult.nameUtf8() = groups[1]!!.range.let { out.replace(it.first, it.last + 1, "UTF-8") }
    XML_ENCODING.find(out)?.nameUtf8()
    val headEnd = out.indexOf("</head", ignoreCase = true).takeIf { it >= 0 } ?: out.length
    // Right to left, so each replacement leaves the ranges still to come where they were.
    META_CHARSET.findAll(out.substring(0, headEnd)).toList().asReversed().forEach { it.nameUtf8() }
    return out.toString()
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }

/** How far into a document browsers look for a `<meta>` charset before they parse it. */
private const val PRESCAN_BYTES = 1024

private val XML_ENCODING = Regex("""^\s*<\?xml\s[^>]*?\bencoding\s*=\s*["']([^"']*)["']""")

/** Both `<meta charset="x">` and `<meta http-equiv="Content-Type" content="text/html; charset=x">`. */
private val META_CHARSET = Regex("""<meta\b[^>]*?\bcharset\s*=\s*["']?\s*([^"'\s;/>]+)""", RegexOption.IGNORE_CASE)

private const val ASCII_PROBE = "<?xml encoding"

/** Java's canonical name for a legacy charset, and the superset browsers decode it as. */
private val WEB_SUPERSETS = mapOf(
    "US-ASCII" to "windows-1252",
    "ISO-8859-1" to "windows-1252",
    "ISO-8859-9" to "windows-1254",
    "TIS-620" to "x-windows-874",
    "Shift_JIS" to "windows-31j",
    "GB2312" to "GB18030",
    "GBK" to "GB18030",
    "EUC-KR" to "x-windows-949",
    "Big5" to "Big5-HKSCS",
)
