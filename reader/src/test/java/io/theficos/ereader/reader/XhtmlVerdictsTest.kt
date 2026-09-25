package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XhtmlVerdictsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `remembers a book's verdict, including an empty one`() {
        val verdicts = XhtmlVerdicts(tmp.newFolder("verdicts"))
        val broken = tmp.newFile("broken.epub").apply { writeText("v1") }
        val clean = tmp.newFile("clean.epub").apply { writeText("v1") }

        assertThat(verdicts[broken]).isNull()
        verdicts[broken] = setOf("OEBPS/ch1.xhtml", "OEBPS/ch%202.xhtml")
        verdicts[clean] = emptySet()

        assertThat(verdicts[broken]).containsExactly("OEBPS/ch1.xhtml", "OEBPS/ch%202.xhtml")
        assertThat(verdicts[clean]).isEmpty()
    }

    @Test fun `forgets the verdict once the book file changes`() {
        val verdicts = XhtmlVerdicts(tmp.newFolder("verdicts"))
        val book = tmp.newFile("book.epub").apply { writeText("v1") }
        verdicts[book] = setOf("OEBPS/ch1.xhtml")

        book.writeText("a longer second version")

        assertThat(verdicts[book]).isNull()
    }
}
