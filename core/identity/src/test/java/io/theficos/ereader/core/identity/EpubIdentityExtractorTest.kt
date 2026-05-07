package io.theficos.ereader.core.identity

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubIdentityExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `picks first non-empty dc identifier and normalizes`() {
        val opf = javaClass.getResource("/identity/sample.opf")!!.readBytes()
        val epub = makeEpubWith(opf)

        val id = extractMetadataId(epub)
        assertThat(id).isEqualTo("42")
    }

    @Test fun `returns null when no identifier present`() {
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>x</dc:title></metadata></package>""".toByteArray()
        val epub = makeEpubWith(opf)
        assertThat(extractMetadataId(epub)).isNull()
    }

    @Test fun `picks second identifier when first is whitespace`() {
        val opf = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
  <dc:identifier>   </dc:identifier>
  <dc:identifier>calibre:42</dc:identifier>
</metadata></package>""".toByteArray()
        val epub = makeEpubWith(opf)
        assertThat(extractMetadataId(epub)).isEqualTo("42")
    }

    @Test fun `does not fall through to second identifier when first is non-empty but normalizes to empty`() {
        // Per spec §5.3 step 5: if the first non-empty identifier normalizes to empty, treat as missing.
        // Subsequent identifiers should NOT be consulted.
        val opf = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
  <dc:identifier>urn:isbn:</dc:identifier>
  <dc:identifier>calibre:42</dc:identifier>
</metadata></package>""".toByteArray()
        val epub = makeEpubWith(opf)
        assertThat(extractMetadataId(epub)).isNull()
    }

    @Test fun `corrupt epub returns null without throwing`() {
        val notAZip = tmp.newFile("not-a-zip.epub").apply { writeText("plain text not a zip file") }
        assertThat(extractMetadataId(notAZip)).isNull()
    }

    private fun makeEpubWith(opfBytes: ByteArray): File {
        val f = tmp.newFile("book.epub")
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""
                <?xml version="1.0"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(opfBytes)
            zip.closeEntry()
        }
        return f
    }
}
