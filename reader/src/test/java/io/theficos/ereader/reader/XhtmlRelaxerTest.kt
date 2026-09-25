package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.InMemoryResource
import org.readium.r2.shared.util.resource.Resource
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class XhtmlRelaxerTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("xhtml/$name")!!.use { it.readBytes() }

    private fun doc(body: String, doctype: String = "", prolog: String = """<?xml version="1.0" encoding="utf-8"?>""") =
        """$prolog$doctype<html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>$body</body></html>"""
            .toByteArray()

    private val xhtml11 =
        """<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">"""

    @Test fun `Gutenberg chapter with a self-closing anchor stays on the XML parser`() {
        assertThat(xmlParseError(fixture("gutenberg-chapter.xhtml"))).isNull()
    }

    @Test fun `malformed chapter needs the HTML parser`() {
        assertThat(xmlParseError(fixture("malformed-chapter.xhtml"))).isNotNull()
    }

    @Test fun `unclosed br, bare ampersand and undeclared prefix are each rejected`() {
        assertThat(xmlParseError(doc("<p>a<br>b</p>"))).isNotNull()
        assertThat(xmlParseError(doc("<p>salt & pepper</p>"))).isNotNull()
        assertThat(xmlParseError(doc("""<p epub:type="note">x</p>"""))).isNotNull()
    }

    @Test fun `HTML entities are undefined without an XHTML DTD`() {
        assertThat(xmlParseError(doc("<p>a&nbsp;b</p>"))).contains("&nbsp;")
        assertThat(xmlParseError(doc("<p>a&nbsp;b</p>", doctype = "<!DOCTYPE html>"))).contains("&nbsp;")
        assertThat(xmlParseError(doc("""<p title="a&nbsp;b">x</p>"""))).contains("&nbsp;")
        assertThat(
            xmlParseError(doc("<p>a&nbsp;b</p>", doctype = """<!DOCTYPE html SYSTEM "about:legacy-compat">""")),
        ).contains("&nbsp;")
    }

    @Test fun `the XHTML DTD defines the HTML entities but not made-up ones`() {
        assertThat(xmlParseError(doc("<p>caf&eacute; &mdash; a&nbsp;b</p>", doctype = xhtml11))).isNull()
        assertThat(xmlParseError(doc("<p>a&bogus;b</p>", doctype = xhtml11))).contains("&bogus;")
    }

    @Test fun `entities declared in the internal subset and XML's own are defined`() {
        val doctype = """<!DOCTYPE html [<!ENTITY nbsp "&#160;">]>"""
        assertThat(xmlParseError(doc("<p>a&nbsp;b</p>", doctype = doctype))).isNull()
        assertThat(xmlParseError(doc("""<p title="&quot;&apos;">&lt;&amp;&gt;&#160;&#xA0;</p>"""))).isNull()
    }

    @Test fun `errors the pull parser lets through are still caught`() {
        assertThat(xmlParseError(doc("""<p id="a" id="b">x</p>"""))).contains("duplicate attribute")
        assertThat(xmlParseError(doc("<p>x</p>") + "<p>y</p>".toByteArray())).contains("root")
        assertThat(xmlParseError(doc("<p>form\u000Cfeed</p>"))).contains("control character")
        assertThat(xmlParseError(doc("<p>tab&#x9;ok, bell&#7;not</p>"))).contains("&#7;")
        val truncated = doc("<p>cut short").let { it.copyOf(it.size - "</body></html>".length) }
        assertThat(xmlParseError(truncated)).contains("document ends")
    }

    @Test fun `a byte-order mark or leading blank line before the declaration is fine`() {
        assertThat(xmlParseError(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + doc("<p>x</p>"))).isNull()
        assertThat(xmlParseError("\n  ".toByteArray() + doc("<p>x</p>"))).isNull()
    }

    @Test fun `only the malformed document is switched to text-html`() = runTest {
        val good = Url("OEBPS/ch1.xhtml")!!
        val bad = Url("OEBPS/ch2.xhtml")!!
        val nav = Url("OEBPS/nav.xhtml")!!
        val css = Url("OEBPS/style.css")!!
        val container = MapContainer(
            mapOf(
                good to fixture("gutenberg-chapter.xhtml"),
                bad to fixture("malformed-chapter.xhtml"),
                nav to doc("<nav><ol><li><a href=\"ch1.xhtml\">One</a></li></ol></nav>"),
                css to "a:hover { color: red }".toByteArray(),
            ),
        )
        val manifest = Manifest(
            metadata = Metadata(),
            readingOrder = listOf(Link(good, MediaType.XHTML), Link(bad, MediaType.XHTML)),
            resources = listOf(Link(nav, MediaType.XHTML), Link(css, MediaType.CSS)),
        )

        val malformed = manifest.malformedXhtml(container)
        val relaxed = manifest.relaxing(malformed)

        assertThat(malformed).containsExactly("OEBPS/ch2.xhtml")
        assertThat(relaxed.readingOrder.map { it.mediaType }).containsExactly(MediaType.XHTML, MediaType.HTML).inOrder()
        assertThat(relaxed.resources.map { it.mediaType }).containsExactly(MediaType.XHTML, MediaType.CSS).inOrder()
    }

    private class MapContainer(private val files: Map<Url, ByteArray>) : Container<Resource> {
        override val entries: Set<Url> = files.keys
        override fun get(url: Url): Resource? = files[url]?.let { InMemoryResource(it) }
        override fun close() {}
    }
}
