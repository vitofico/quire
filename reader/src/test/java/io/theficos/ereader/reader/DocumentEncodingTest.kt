package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.charset.Charset

class DocumentEncodingTest {

    private fun xhtml(encoding: String?, text: String, head: String = "") =
        (if (encoding == null) "" else """<?xml version="1.0" encoding="$encoding"?>""") +
            """<html xmlns="http://www.w3.org/1999/xhtml"><head>$head<title>t</title></head>""" +
            """<body><p>$text</p></body></html>"""

    private fun html(meta: String, text: String) = "<html><head>$meta<title>t</title></head><body><p>$text</p></body></html>"

    private fun ByteArray.text() = toString(Charsets.UTF_8)

    private val windows1252 = Charset.forName("windows-1252")

    @Test fun `UTF-16 with a byte-order mark is served as UTF-8`() {
        val littleEndian = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + xhtml("UTF-16", "café 日本").toByteArray(Charsets.UTF_16LE)
        val bigEndian = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + xhtml("UTF-16", "café 日本").toByteArray(Charsets.UTF_16BE)

        assertThat(utf8Document(littleEndian, html = false).text()).isEqualTo(xhtml("UTF-8", "café 日本"))
        assertThat(utf8Document(bigEndian, html = false).text()).isEqualTo(xhtml("UTF-8", "café 日本"))
    }

    @Test fun `UTF-16 without a byte-order mark is recognised by its XML declaration`() {
        val source = xhtml("UTF-16", "café").toByteArray(Charsets.UTF_16LE)

        assertThat(utf8Document(source, html = false).text()).isEqualTo(xhtml("UTF-8", "café"))
    }

    @Test fun `ISO-8859-1 is served as UTF-8, its curly quotes read the way browsers read them`() {
        // Browsers decode ISO-8859-1 as windows-1252, and books so labelled rely on its quotes.
        val source = xhtml("ISO-8859-1", "café “quoted”").toByteArray(windows1252)

        assertThat(utf8Document(source, html = false).text()).isEqualTo(xhtml("UTF-8", "café “quoted”"))
    }

    @Test fun `windows-1252 is served as UTF-8`() {
        val source = xhtml("windows-1252", "€5 – café").toByteArray(windows1252)

        assertThat(utf8Document(source, html = false).text()).isEqualTo(xhtml("UTF-8", "€5 – café"))
    }

    @Test fun `Shift_JIS is served as UTF-8, Microsoft's extensions included`() {
        // ① is one of the characters Japanese books labelled Shift_JIS take from windows-31j.
        val source = xhtml("Shift_JIS", "日本語のテキスト①").toByteArray(Charset.forName("windows-31j"))

        assertThat(utf8Document(source, html = false).text()).isEqualTo(xhtml("UTF-8", "日本語のテキスト①"))
    }

    @Test fun `a document already in UTF-8 is served byte for byte`() {
        val declared = xhtml("utf-8", "café").toByteArray()
        val undeclared = xhtml(null, "café").toByteArray()
        // The byte-order mark outranks the declaration.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + xhtml("ISO-8859-1", "café").toByteArray()
        val plainHtml = html(meta = "", text = "café").toByteArray()

        assertThat(utf8Document(declared, html = false)).isSameInstanceAs(declared)
        assertThat(utf8Document(undeclared, html = false)).isSameInstanceAs(undeclared)
        assertThat(utf8Document(bom, html = false)).isSameInstanceAs(bom)
        assertThat(utf8Document(plainHtml, html = true)).isSameInstanceAs(plainHtml)
    }

    @Test fun `an encoding Java does not know is left as it is`() {
        val source = xhtml("x-klingon", "qapla'").toByteArray()

        assertThat(utf8Document(source, html = false)).isSameInstanceAs(source)
    }

    @Test fun `a UTF-16 label on bytes that are not UTF-16 is read as UTF-8`() {
        val source = xhtml("UTF-16", "café").toByteArray()

        assertThat(utf8Document(source, html = false).text()).isEqualTo(xhtml("UTF-8", "café"))
    }

    @Test fun `a meta charset counts only for a document served as HTML`() {
        val charset = html(meta = """<meta charset="windows-1252">""", text = "café").toByteArray(windows1252)
        val httpEquiv = html(
            meta = """<meta http-equiv="Content-Type" content="text/html; charset=iso-8859-1" />""",
            text = "café",
        ).toByteArray(windows1252)

        assertThat(utf8Document(charset, html = true).text())
            .isEqualTo(html(meta = """<meta charset="UTF-8">""", text = "café"))
        assertThat(utf8Document(httpEquiv, html = true).text()).isEqualTo(
            html(meta = """<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />""", text = "café"),
        )
        // The XML parser ignores <meta>.
        assertThat(utf8Document(charset, html = false)).isSameInstanceAs(charset)
    }

    @Test fun `the XML declaration outranks a meta charset, and both are rewritten`() {
        val source = xhtml("Shift_JIS", "日本", head = """<meta charset="EUC-JP"/>""")
            .toByteArray(Charset.forName("Shift_JIS"))

        assertThat(utf8Document(source, html = true).text())
            .isEqualTo(xhtml("UTF-8", "日本", head = """<meta charset="UTF-8"/>"""))
    }
}
