package io.theficos.ereader.core.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpfMetadataExtractorTest {

    private fun loadFixture(name: String): ByteArray =
        checkNotNull(this::class.java.classLoader!!.getResourceAsStream(name)) {
            "fixture not found: $name"
        }.use { it.readBytes() }

    @Test
    fun `extracts full metadata bundle from foundation opf`() {
        val bundle = OpfMetadataExtractor.extract(loadFixture("foundation.opf"), fallbackTitle = "fallback")
        assertThat(bundle.title).isEqualTo("Foundation")
        assertThat(bundle.author).isEqualTo("Isaac Asimov")
        assertThat(bundle.language).isEqualTo("en")
        assertThat(bundle.publisher).isEqualTo("Bantam Spectra")
        assertThat(bundle.publishDate).isEqualTo("1991-10-01")
        assertThat(bundle.isbn).isEqualTo("9780553293357")
        assertThat(bundle.subjects).containsExactly("Science Fiction", "Galactic empire")
        assertThat(bundle.description).contains("psychohistory")
        assertThat(bundle.seriesName).isEqualTo("Foundation")
        assertThat(bundle.seriesPosition).isEqualTo(1)
    }

    @Test
    fun `falls back to title when opf is malformed`() {
        val bundle = OpfMetadataExtractor.extract(byteArrayOf(0x00, 0x01), fallbackTitle = "Untitled")
        assertThat(bundle.title).isEqualTo("Untitled")
        assertThat(bundle.author).isNull()
    }

    @Test
    fun `parses epub3 belongs-to-collection`() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="x">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="x">x</dc:identifier>
                <dc:title>Foundation and Empire</dc:title>
                <meta property="belongs-to-collection" id="c01">Foundation</meta>
                <meta refines="#c01" property="group-position">2</meta>
              </metadata>
            </package>
        """.trimIndent().toByteArray()
        val bundle = OpfMetadataExtractor.extract(opf, fallbackTitle = "fb")
        assertThat(bundle.seriesName).isEqualTo("Foundation")
        assertThat(bundle.seriesPosition).isEqualTo(2)
    }

    @Test
    fun `extracts isbn from raw identifier`() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier>978-0-14-103614-4</dc:identifier>
                <dc:title>X</dc:title>
              </metadata>
            </package>
        """.trimIndent().toByteArray()
        val bundle = OpfMetadataExtractor.extract(opf, fallbackTitle = "fb")
        assertThat(bundle.isbn).isEqualTo("9780141036144")
    }
}
