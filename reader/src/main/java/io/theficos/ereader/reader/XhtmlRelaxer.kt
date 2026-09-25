package io.theficos.ereader.reader

import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.use
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

private const val TAG = "XhtmlRelaxer"

/**
 * Returns the hrefs of the XHTML documents the WebView's strict XML parser would reject, the
 * only ones [relaxing] hands to its forgiving HTML parser.
 *
 * Readium serves each document with the media type in the manifest. As `application/xhtml+xml`
 * the WebView parses it as XML, where a single error (an unclosed `<br>`, a bare `&`, an
 * `&nbsp;` no DTD defines) replaces the chapter with a parser error. As `text/html` it parses
 * anything, but it ignores the self-closing slash on elements that are not void: Project
 * Gutenberg opens each chapter with `<a id="chap01"/>`, which HTML keeps open around every
 * paragraph that follows, so the stylesheet's `a:hover { color: red }` turns the whole chapter
 * red on the first tap. Relaxing every document traded the first failure for the second.
 *
 * This reads every XHTML document in the book, so callers keep the answer (see [XhtmlVerdicts]).
 */
internal suspend fun Manifest.malformedXhtml(container: Container<Resource>): Set<String> {
    val hrefs = (readingOrder + resources)
        .filter { it.mediaType?.matches(MediaType.XHTML) == true }
        .map { it.url() }
        .distinct()
    if (hrefs.isEmpty()) return emptySet()
    // One chunk per core, each on an IO thread: Readium's reads switch to Dispatchers.IO
    // themselves, and doing so from an IO thread costs no thread hop per document.
    val workers = Runtime.getRuntime().availableProcessors()
    return coroutineScope {
        hrefs.chunked((hrefs.size + workers - 1) / workers)
            .map { chunk -> async(Dispatchers.IO) { chunk.filter { container.isMalformed(it) } } }
            .awaitAll()
            .flatten()
            .mapTo(mutableSetOf()) { it.toString() }
    }
}

/** Switches the documents at [malformed] (from [malformedXhtml]) to the HTML parser. */
internal fun Manifest.relaxing(malformed: Set<String>): Manifest {
    if (malformed.isEmpty()) return this
    fun Link.relaxed() = if (url().toString() in malformed) copy(mediaType = MediaType.HTML) else this
    return copy(
        readingOrder = readingOrder.map { it.relaxed() },
        resources = resources.map { it.relaxed() },
    )
}

private suspend fun Container<Resource>.isMalformed(url: Url): Boolean {
    // An unreadable document stays as it is: the navigator shows its error page either way.
    val bytes = get(url)?.use { it.read().getOrNull() } ?: return false
    val error = xmlParseError(bytes) ?: return false
    Log.d(TAG, "relaxing $url: $error")
    return true
}

/**
 * Returns why the WebView's XML parser would reject [bytes], or null when it would accept them.
 *
 * Android's pull parser does most of the work. It lets through a few things Chromium treats as
 * fatal, so those are checked here: a second root element, a document that ends inside an
 * element, duplicate attributes, bytes the document's encoding cannot decode, characters XML
 * forbids, and named entities Chromium cannot resolve (the parser quietly drops any entity once
 * a DTD is referenced).
 */
internal fun xmlParseError(bytes: ByteArray): String? {
    // Readium's HTML injector trims each document before serving it, so whitespace ahead of the
    // XML declaration (an error in XML) never reaches the WebView.
    val start = bytes.indexOfFirst { it.toInt().toChar() !in " \t\r\n" }.coerceAtLeast(0)
    val parser = Xml.newPullParser()
    var xhtmlDtd = false
    val declaredEntities = mutableSetOf<String>()
    var roots = 0
    try {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(bytes, start, bytes.size - start), null)
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.DOCDECL -> {
                    val doctype = parser.text
                    xhtmlDtd = PUBLIC_ID.find(doctype)?.groupValues?.get(1) in XHTML_DTDS
                    ENTITY_DECLARATION.findAll(doctype).mapTo(declaredEntities) { it.groupValues[1] }
                }
                XmlPullParser.START_TAG -> {
                    if (parser.depth == 1 && ++roots > 1) return "more than one root element"
                    parser.duplicateAttribute()?.let { return "duplicate attribute $it on <${parser.name}>" }
                }
                XmlPullParser.END_DOCUMENT -> {
                    if (roots == 0) return "no root element"
                    if (parser.depth > 0) return "document ends before its root element closes"
                    break
                }
            }
        }
    } catch (e: Exception) {
        return e.message ?: e.javaClass.simpleName
    }
    return encodingError(bytes, start) ?: characterError(bytes, start) { name ->
        name in declaredEntities || (xhtmlDtd && name in XHTML_ENTITIES)
    }
}

/**
 * Returns why the WebView could not decode the document, or null. It decodes with the charset
 * the byte-order mark or else the XML declaration names (UTF-8 when neither does), and under XML
 * a byte sequence that charset cannot decode is fatal ("Encoding error"), where the pull parser
 * quietly substitutes U+FFFD.
 *
 * Readium 3.0.0 decodes every document as UTF-8 and re-encodes it before serving it, whatever it
 * declares, so a chapter that declares Shift_JIS reaches the WebView as UTF-8 bytes labelled
 * Shift_JIS, which fails. The book's own bytes are checked as well, which keeps the answer right
 * if a later Readium serves them unchanged.
 */
private fun encodingError(bytes: ByteArray, start: Int): String? {
    val charset = if (bytes.hasUtf8Bom(start)) Charsets.UTF_8 else declaredCharset(bytes, start) ?: Charsets.UTF_8
    if (!charset.decodes(bytes, start)) return "bytes that are not valid ${charset.name()}"
    if (charset == Charsets.UTF_8) return null
    val served = String(bytes, start, bytes.size - start, Charsets.UTF_8).toByteArray(Charsets.UTF_8)
    return if (charset.decodes(served, 0)) null else "Readium serves it as UTF-8, which ${charset.name()} cannot decode"
}

private fun ByteArray.hasUtf8Bom(start: Int) = size - start >= 3 &&
    this[start] == 0xEF.toByte() && this[start + 1] == 0xBB.toByte() && this[start + 2] == 0xBF.toByte()

private fun declaredCharset(bytes: ByteArray, start: Int): Charset? {
    val prolog = String(bytes, start, minOf(bytes.size - start, 256), Charsets.ISO_8859_1)
    val name = DECLARED_ENCODING.find(prolog)?.groupValues?.get(1) ?: return null
    // The pull parser has already rejected an encoding Java does not know.
    return runCatching { Charset.forName(name) }.getOrNull()
}

private fun Charset.decodes(bytes: ByteArray, start: Int): Boolean = try {
    newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, start, bytes.size - start))
    true
} catch (e: CharacterCodingException) {
    false
}

private fun XmlPullParser.duplicateAttribute(): String? {
    for (i in 1 until attributeCount) {
        for (j in 0 until i) {
            if (getAttributeName(i) == getAttributeName(j) &&
                getAttributeNamespace(i) == getAttributeNamespace(j)
            ) {
                return getAttributeName(i)
            }
        }
    }
    return null
}

/**
 * Scans the raw bytes for a character XML forbids, as UTF-8 or as a `&#...;` reference, and for
 * a named entity reference that is neither built into XML nor [defined]. Anything of the kind
 * inside a comment or CDATA section is flagged too; the cost of that is one document on the
 * HTML parser.
 */
private fun characterError(bytes: ByteArray, start: Int, defined: (String) -> Boolean): String? {
    var i = start
    while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) {
            return "control character 0x%02x".format(b)
        }
        // U+FFFE and U+FFFF are valid UTF-8 (EF BF BE, EF BF BF) but not XML characters.
        if (b == 0xEF && i + 2 < bytes.size && bytes[i + 1] == 0xBF.toByte() &&
            (bytes[i + 2] == 0xBE.toByte() || bytes[i + 2] == 0xBF.toByte())
        ) {
            return if (bytes[i + 2] == 0xBE.toByte()) "noncharacter U+FFFE" else "noncharacter U+FFFF"
        }
        if (b != '&'.code) {
            i++
            continue
        }
        var end = i + 1
        while (end < bytes.size && bytes[end].isReferenceChar()) end++
        if (end < bytes.size && bytes[end] == ';'.code.toByte() && end > i + 1) {
            val name = String(bytes, i + 1, end - i - 1, Charsets.UTF_8)
            if (name.startsWith("#")) {
                val code = if (name.startsWith("#x")) name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull()
                if (code != null && !isXmlChar(code)) {
                    return "reference to a character XML forbids, &$name;"
                }
            } else if (name !in XML_ENTITIES && !defined(name)) {
                return "undefined entity &$name;"
            }
        }
        i = end
    }
    return null
}

private fun isXmlChar(c: Int) = c == 0x09 || c == 0x0A || c == 0x0D ||
    c in 0x20..0xD7FF || c in 0xE000..0xFFFD || c in 0x10000..0x10FFFF

private fun Byte.isReferenceChar(): Boolean {
    val c = toInt()
    return c < 0 || c == '#'.code || c == '_'.code || c == '-'.code || c == '.'.code || c == ':'.code ||
        c in '0'.code..'9'.code || c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code
}

private val DECLARED_ENCODING = Regex("""^<\?xml\s[^>]*?\bencoding\s*=\s*["']([^"']+)["']""")

private val PUBLIC_ID = Regex("""PUBLIC\s+["']([^"']*)["']""")

private val ENTITY_DECLARATION = Regex("""<!ENTITY\s+([^\s%]+)""")

private val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")

/**
 * Public identifiers of the DTDs whose named entities (`&nbsp;`, `&mdash;`, ...) Chromium
 * resolves without loading the DTD. Under any other DTD, or none (EPUB 3's `<!DOCTYPE html>`),
 * they stay undefined, and an undefined entity is an XML error.
 */
private val XHTML_DTDS = setOf(
    "-//W3C//DTD XHTML 1.0 Transitional//EN",
    "-//W3C//DTD XHTML 1.1//EN",
    "-//W3C//DTD XHTML 1.0 Strict//EN",
    "-//W3C//DTD XHTML 1.0 Frameset//EN",
    "-//W3C//DTD XHTML Basic 1.0//EN",
    "-//W3C//DTD XHTML 1.1 plus MathML 2.0//EN",
    "-//W3C//DTD XHTML 1.1 plus MathML 2.0 plus SVG 1.1//EN",
    "-//W3C//DTD MathML 2.0//EN",
    "-//WAPFORUM//DTD XHTML Mobile 1.0//EN",
    "-//WAPFORUM//DTD XHTML Mobile 1.1//EN",
    "-//WAPFORUM//DTD XHTML Mobile 1.2//EN",
)

/**
 * The entities the XHTML 1.x DTDs declare beyond XML's own five (the HTML 4 set). Chromium
 * resolves the larger HTML5 set; a name outside this list counts as undefined, which only costs
 * that document the HTML parser.
 */
private val XHTML_ENTITIES = (
    "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg " +
        "plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 " +
        "iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml " +
        "Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash " +
        "Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde auml aring " +
        "aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve " +
        "oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml " +
        "fnof Alpha Beta Gamma Delta Epsilon Zeta Eta Theta Iota Kappa Lambda Mu Nu Xi " +
        "Omicron Pi Rho Sigma Tau Upsilon Phi Chi Psi Omega alpha beta gamma delta epsilon " +
        "zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigmaf sigma tau upsilon " +
        "phi chi psi omega thetasym upsih piv bull hellip prime Prime oline frasl weierp " +
        "image real trade alefsym larr uarr rarr darr harr crarr lArr uArr rArr dArr hArr " +
        "forall part exist empty nabla isin notin ni prod sum minus lowast radic prop infin " +
        "ang and or cap cup int there4 sim cong asymp ne equiv le ge sub sup nsub sube supe " +
        "oplus otimes perp sdot lceil rceil lfloor rfloor lang rang loz spades clubs hearts " +
        "diams OElig oelig Scaron scaron Yuml circ tilde ensp emsp thinsp zwnj zwj lrm rlm " +
        "ndash mdash lsquo rsquo sbquo ldquo rdquo bdquo dagger Dagger permil lsaquo rsaquo euro"
    ).split(' ').toSet()
