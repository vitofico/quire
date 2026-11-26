package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.StringResource
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class PublicationWordsTest {
    @Test fun `counts body words, not markup, head, scripts or styles`() {
        val doc = """<?xml version="1.0"?><html><head><title>Ignored title</title><style>p { x: y }</style></head>
            <body><h1 class="c">Chapter One</h1><p>It was a <em>dark</em> night.</p>
            <script>var a = 1;</script><!-- a comment --></body></html>"""
        assertThat(countWords(doc)).isEqualTo(7)
    }

    @Test fun `apostrophes, hyphens and entities stay inside a word`() {
        assertThat(countWords("<p>Don’t over-think it, don&rsquo;t&nbsp;stop &mdash; well-known!</p>")).isEqualTo(6)
    }

    @Test fun `accented letters and combining marks are one word`() {
        assertThat(countWords("<p>café naïve café</p>")).isEqualTo(3)
    }

    @Test fun `entities other than an apostrophe separate words`() {
        assertThat(countWords("<p>war&mdash;peace and rock&#8217;n&apos;roll</p>")).isEqualTo(4)
    }

    @Test fun `the katakana long vowel mark is not a word of its own`() {
        assertThat(countWords("<p>ラーメン</p>")).isEqualTo(3)
    }

    @Test fun `an unreadable document is left out, and a book with none readable has no count`() = runTest {
        fun book(vararg readable: Boolean) = Publication(
            manifest = Manifest(
                metadata = Metadata(),
                readingOrder = readable.indices.map { Link(Url("$it.xhtml")!!, MediaType.XHTML) },
            ),
            container = object : Container<Resource> {
                override val entries = readable.indices.filter { readable[it] }.map { Url("$it.xhtml")!! }.toSet()
                override fun get(url: Url): Resource? =
                    if (url in entries) StringResource("<html><body><p>three small words</p></body></html>") else null
                override fun close() {}
            },
        )
        assertThat(book(true, false, true).countWords()).isEqualTo(6)
        assertThat(book(false, false).countWords()).isNull()
    }

    @Test fun `each Chinese or Japanese character counts as one`() {
        assertThat(countWords("<p>我是猫。ひらがな ok</p>")).isEqualTo(8)
    }

    @Test fun `an empty or image-only document has no words`() {
        assertThat(countWords("<html><body><img src=\"a.png\" alt=\"x\"/></body></html>")).isEqualTo(0)
        assertThat(countWords("")).isEqualTo(0)
    }
}
