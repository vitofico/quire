package io.theficos.ereader.reader

import java.io.ByteArrayOutputStream

/**
 * Returns [bytes], a document in UTF-8, with each self-closing element that is not void written
 * as an explicit pair: `<a id="x"/>` becomes `<a id="x"></a>`. When there is none, returns the
 * same array.
 *
 * XML reads the slash as closing the element. HTML ignores it on anything but a void element
 * (`<br/>`, `<img/>`), so in a chapter the HTML parser reads (declared HTML, or see [relaxing]) Project
 * Gutenberg's `<a id="chap01"/>` stays open around every paragraph that follows, and the
 * stylesheet's `a:hover { color: red }` turns the chapter red on the first tap.
 *
 * Tags are read the way HTML's tokenizer reads them, so comments, CDATA sections, the contents of
 * `<script>` and `<style>`, and a `/>` inside an attribute value are left alone.
 */
internal fun closeEmptyElements(bytes: ByteArray): ByteArray {
    var out: ByteArrayOutputStream? = null
    var copied = 0
    var i = 0
    while (true) {
        val lt = bytes.indexOf('<', i)
        if (lt < 0) break
        i = when {
            bytes.startsWith("<!--", lt) -> bytes.after("-->", lt + 4)
            bytes.startsWith("<![CDATA[", lt) -> bytes.after("]]>", lt + 9)
            // Doctype, processing instruction, end tag.
            bytes.startsWith("<!", lt) || bytes.startsWith("<?", lt) || bytes.startsWith("</", lt) ->
                bytes.after(">", lt + 2)
            lt + 1 < bytes.size && bytes[lt + 1].isAsciiLetter() -> {
                val tag = bytes.startTag(lt) ?: break
                val name = String(bytes, lt + 1, tag.nameLength, Charsets.UTF_8).lowercase()
                when {
                    tag.slash >= 0 && name !in VOID_ELEMENTS -> {
                        val o = out ?: ByteArrayOutputStream(bytes.size + 256).also { out = it }
                        o.write(bytes, copied, tag.slash - copied)
                        o.write(GT.toInt())
                        o.write(END_TAG_OPEN)
                        o.write(bytes, lt + 1, tag.nameLength)
                        o.write(GT.toInt())
                        copied = tag.end
                        tag.end
                    }
                    tag.slash < 0 && name in RAW_TEXT_ELEMENTS ->
                        bytes.indexOf("</$name", tag.end, ignoreCase = true).let { if (it < 0) bytes.size else it }
                    else -> tag.end
                }
            }
            else -> lt + 1
        }
    }
    val o = out ?: return bytes
    o.write(bytes, copied, bytes.size - copied)
    return o.toByteArray()
}

/**
 * A start tag: [nameLength] bytes of name after the `<`, the index of its self-closing slash
 * ([slash], or -1), and the index just past its `>` ([end]).
 */
private class StartTag(val nameLength: Int, val slash: Int, val end: Int)

/** Reads the start tag at [lt], or returns null when the document ends inside it. */
private fun ByteArray.startTag(lt: Int): StartTag? {
    var j = lt + 1
    while (j < size && !this[j].isWhitespace() && this[j] != SLASH && this[j] != GT) j++
    val nameLength = j - lt - 1
    while (j < size) {
        when {
            this[j] == GT -> return StartTag(nameLength, slash = -1, end = j + 1)
            // A slash self-closes the tag only right before its '>'; anywhere else HTML skips it.
            this[j] == SLASH -> if (j + 1 < size && this[j + 1] == GT) return StartTag(nameLength, j, j + 2) else j++
            this[j].isWhitespace() -> j++
            else -> {
                // An attribute: its name, then maybe '=' and a value.
                while (j < size && !this[j].isWhitespace() && this[j] != SLASH && this[j] != GT && this[j] != EQ) j++
                var k = j
                while (k < size && this[k].isWhitespace()) k++
                if (k < size && this[k] == EQ) {
                    k++
                    while (k < size && this[k].isWhitespace()) k++
                    j = if (k < size && (this[k] == QUOTE || this[k] == APOSTROPHE)) {
                        indexOf(this[k].toInt().toChar(), k + 1).takeIf { it >= 0 }?.plus(1) ?: return null
                    } else {
                        // Unquoted: up to whitespace or '>', a slash included, as HTML reads it.
                        while (k < size && !this[k].isWhitespace() && this[k] != GT) k++
                        k
                    }
                }
            }
        }
    }
    return null
}

private fun ByteArray.indexOf(c: Char, from: Int): Int {
    for (j in from until size) if (this[j] == c.code.toByte()) return j
    return -1
}

private fun ByteArray.indexOf(s: String, from: Int, ignoreCase: Boolean = false): Int {
    for (j in from..size - s.length) if (startsWith(s, j, ignoreCase)) return j
    return -1
}

/** The index just past the next [s] at or after [from], or the end of the document. */
private fun ByteArray.after(s: String, from: Int): Int = indexOf(s, from).let { if (it < 0) size else it + s.length }

private fun ByteArray.startsWith(s: String, at: Int, ignoreCase: Boolean = false): Boolean =
    at + s.length <= size && s.indices.all { s[it].equals(this[at + it].toInt().toChar(), ignoreCase) }

private fun Byte.isAsciiLetter() = toInt().toChar().let { it in 'a'..'z' || it in 'A'..'Z' }

private fun Byte.isWhitespace() = this == ' '.code.toByte() || this == '\t'.code.toByte() ||
    this == '\n'.code.toByte() || this == '\r'.code.toByte() || this == 0x0C.toByte()

private const val SLASH = '/'.code.toByte()
private const val GT = '>'.code.toByte()
private const val EQ = '='.code.toByte()
private const val QUOTE = '"'.code.toByte()
private const val APOSTROPHE = '\''.code.toByte()
private val END_TAG_OPEN = "</".toByteArray()

/** The elements HTML never gives content, for which a self-closing slash is harmless. */
private val VOID_ELEMENTS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr",
)

/** The elements whose content HTML reads as raw text up to the closing tag. */
private val RAW_TEXT_ELEMENTS = setOf("script", "style")
