package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

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

    @Test fun `each Chinese or Japanese character counts as one`() {
        assertThat(countWords("<p>我是猫。ひらがな ok</p>")).isEqualTo(8)
    }

    @Test fun `an empty or image-only document has no words`() {
        assertThat(countWords("<html><body><img src=\"a.png\" alt=\"x\"/></body></html>")).isEqualTo(0)
        assertThat(countWords("")).isEqualTo(0)
    }
}
