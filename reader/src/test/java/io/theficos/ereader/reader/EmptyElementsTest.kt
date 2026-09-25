package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmptyElementsTest {

    private fun closed(html: String) = closeEmptyElements(html.toByteArray()).toString(Charsets.UTF_8)

    @Test fun `a self-closing element that is not void becomes an explicit pair`() {
        assertThat(closed("""<a id="chap01"/><p>text</p>""")).isEqualTo("""<a id="chap01"></a><p>text</p>""")
        assertThat(closed("""<div class="x" /><span/><p/>""")).isEqualTo("""<div class="x" ></div><span></span><p></p>""")
        assertThat(closed("""<A ID='x'/><svg:rect width="1"/>""")).isEqualTo("""<A ID='x'></A><svg:rect width="1"></svg:rect>""")
    }

    @Test fun `a self-closing script or style is closed too, so HTML does not swallow what follows`() {
        assertThat(closed("""<script src="a.js"/><style/><a id="x"/>"""))
            .isEqualTo("""<script src="a.js"></script><style></style><a id="x"></a>""")
    }

    @Test fun `void elements are left alone`() {
        val html = """<br/><hr /><img src="a.png"/><meta charset="UTF-8"/><link rel="stylesheet" href="s.css"/>""" +
            """<input/><col/><area/><base/><embed/><source/><track/><wbr/><BR/>"""
        val bytes = html.toByteArray()

        assertThat(closeEmptyElements(bytes)).isSameInstanceAs(bytes)
    }

    @Test fun `comments, CDATA, script and style contents are left alone`() {
        val html = """<!-- <a id="x"/> --><![CDATA[ <b/> ]]>""" +
            """<script>var s = "<b/>"; if (a</b) {}</script><style>/* <i/> */</style><p>text</p>"""
        val bytes = html.toByteArray()

        assertThat(closeEmptyElements(bytes)).isSameInstanceAs(bytes)
    }

    @Test fun `a slash-greater-than inside an attribute value is not a self-closing tag`() {
        val html = """<p title="a/>b">x</p><p title='c/>d'>y</p><a href=foo/>z</a>"""
        val bytes = html.toByteArray()

        assertThat(closeEmptyElements(bytes)).isSameInstanceAs(bytes)
        assertThat(closed("""<a title="a/>b"/>after""")).isEqualTo("""<a title="a/>b"></a>after""")
    }

    @Test fun `the rest of the document is copied unchanged`() {
        val html = """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE html><html><body>""" +
            """<p>café 日本</p><a id="x"/><p>“quoted”</p></body></html>"""

        assertThat(closed(html)).isEqualTo(html.replace("""<a id="x"/>""", """<a id="x"></a>"""))
    }

    @Test fun `a tag the document ends inside is copied as it is`() {
        val bytes = """<p>ok</p><a id="x"/><p title="unterminated""".toByteArray()

        assertThat(closed(bytes.toString(Charsets.UTF_8))).isEqualTo("""<p>ok</p><a id="x"></a><p title="unterminated""")
    }
}
