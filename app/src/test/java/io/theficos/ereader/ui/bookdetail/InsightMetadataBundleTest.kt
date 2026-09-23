package io.theficos.ereader.ui.bookdetail

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.DocumentIdentity
import org.junit.Test

/** Issue #102: the insight lookup must not drop an author the library row knows. */
class InsightMetadataBundleTest {

    private fun doc(author: String?) = Document(
        id = 1L,
        identity = DocumentIdentity(metadataId = "m1", contentHash = "h1"),
        title = "Library Title",
        author = author,
        downloadUrl = "https://example.org/book.epub",
        localPath = "/books/book.epub",
        coverPath = null,
        downloadedAt = 0L,
    )

    private fun opf(creator: String?): ByteArray = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>OPF Title</dc:title>
            ${creator?.let { "<dc:creator>$it</dc:creator>" }.orEmpty()}
            <dc:language>en</dc:language>
          </metadata>
        </package>
    """.trimIndent().toByteArray()

    @Test
    fun `OPF without a creator takes the library row's author`() {
        val bundle = insightMetadataBundle(doc(author = "Feed Author"), opf(creator = null))

        assertThat(bundle.author).isEqualTo("Feed Author")
        assertThat(bundle.title).isEqualTo("OPF Title")
        assertThat(bundle.language).isEqualTo("en")
    }

    @Test
    fun `OPF creator wins over the library row's author`() {
        val bundle = insightMetadataBundle(doc(author = "Feed Author"), opf(creator = "OPF Author"))

        assertThat(bundle.author).isEqualTo("OPF Author")
    }

    @Test
    fun `missing OPF falls back to the library row`() {
        val bundle = insightMetadataBundle(doc(author = "Feed Author"), opfBytes = null)

        assertThat(bundle.title).isEqualTo("Library Title")
        assertThat(bundle.author).isEqualTo("Feed Author")
    }

    @Test
    fun `blank library author is never sent`() {
        assertThat(insightMetadataBundle(doc(author = "  "), opf(creator = null)).author).isNull()
        assertThat(insightMetadataBundle(doc(author = ""), opfBytes = null).author).isNull()
    }
}
