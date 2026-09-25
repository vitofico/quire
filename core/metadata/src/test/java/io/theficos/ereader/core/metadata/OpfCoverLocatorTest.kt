package io.theficos.ereader.core.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class OpfCoverLocatorTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun opf(version: String, metadata: String = "", manifest: String, guide: String = ""): ByteArray = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="$version" unique-identifier="id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
            <dc:identifier id="id">urn:uuid:1</dc:identifier>
            <dc:title>T</dc:title>
            $metadata
          </metadata>
          <manifest>
            <item id="text" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
            $manifest
          </manifest>
          <spine><itemref idref="text"/></spine>
          $guide
        </package>
    """.trimIndent().toByteArray()

    @Test fun `epub 3 cover-image manifest item`() {
        val bytes = opf(
            version = "3.0",
            manifest = """<item id="c" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>""",
        )
        assertThat(OpfCoverLocator.coverImagePath(bytes, "OEBPS/content.opf")).isEqualTo("OEBPS/images/cover.jpg")
    }

    @Test fun `epub 3 cover-image among several properties`() {
        val bytes = opf(
            version = "3.0",
            manifest = """<item id="c" href="cover.webp" media-type="image/webp" properties="svg  cover-image"/>""",
        )
        assertThat(OpfCoverLocator.coverImagePath(bytes, "OEBPS/content.opf")).isEqualTo("OEBPS/cover.webp")
    }

    @Test fun `epub 2 meta name cover points at a manifest id`() {
        val bytes = opf(
            version = "2.0",
            metadata = """<meta name="cover" content="cover-img"/>""",
            manifest = """<item id="cover-img" href="cover.png" media-type="image/png"/>""",
        )
        assertThat(OpfCoverLocator.coverImagePath(bytes, "OEBPS/content.opf")).isEqualTo("OEBPS/cover.png")
    }

    @Test fun `epub 3 property wins over the epub 2 meta`() {
        val bytes = opf(
            version = "3.0",
            metadata = """<meta name="cover" content="old"/>""",
            manifest = """
                <item id="old" href="old.jpg" media-type="image/jpeg"/>
                <item id="new" href="new.jpg" media-type="image/jpeg" properties="cover-image"/>
            """,
        )
        assertThat(OpfCoverLocator.coverImagePath(bytes, "OEBPS/content.opf")).isEqualTo("OEBPS/new.jpg")
    }

    @Test fun `relative href resolves against the opf folder in a subfolder`() {
        val bytes = opf(
            version = "3.0",
            manifest = """<item id="c" href="../images/./cover.jpeg" media-type="image/jpeg" properties="cover-image"/>""",
        )
        assertThat(OpfCoverLocator.coverImagePath(bytes, "OPS/package/content.opf"))
            .isEqualTo("OPS/images/cover.jpeg")
    }

    @Test fun `opf at the zip root resolves to a root path`() {
        val bytes = opf(
            version = "2.0",
            metadata = """<meta name="cover" content="c"/>""",
            manifest = """<item id="c" href="cover.gif" media-type="image/gif"/>""",
        )
        assertThat(OpfCoverLocator.coverImagePath(bytes, "content.opf")).isEqualTo("cover.gif")
    }

    @Test fun `percent-encoded href is decoded as utf-8 and a plus sign is kept`() {
        val bytes = opf(
            version = "3.0",
            manifest = """<item id="c" href="images/My%20Cover%20%C3%A9t%C3%A9+1.jpg" media-type="image/jpeg" properties="cover-image"/>""",
        )
        assertThat(OpfCoverLocator.coverImagePath(bytes, "OEBPS/content.opf"))
            .isEqualTo("OEBPS/images/My Cover été+1.jpg")
    }

    @Test fun `non-image cover items are rejected`() {
        val epub3 = opf(
            version = "3.0",
            manifest = """<item id="c" href="cover.xhtml" media-type="application/xhtml+xml" properties="cover-image"/>""",
        )
        val epub2 = opf(
            version = "2.0",
            metadata = """<meta name="cover" content="text"/>""",
            manifest = "",
        )
        assertThat(OpfCoverLocator.coverImagePath(epub3, "OEBPS/content.opf")).isNull()
        assertThat(OpfCoverLocator.coverImagePath(epub2, "OEBPS/content.opf")).isNull()
    }

    @Test fun `meta cover naming a missing manifest item finds nothing`() {
        val bytes = opf(
            version = "2.0",
            metadata = """<meta name="cover" content="nope"/>""",
            manifest = """<item id="img" href="cover.jpg" media-type="image/jpeg"/>""",
        )
        assertThat(OpfCoverLocator.coverImagePath(bytes, "OEBPS/content.opf")).isNull()
    }

    @Test fun `a guide cover page alone is not a cover image`() {
        val bytes = opf(
            version = "2.0",
            manifest = """<item id="cp" href="cover.xhtml" media-type="application/xhtml+xml"/>""",
            guide = """<guide><reference type="cover" title="Cover" href="cover.xhtml"/></guide>""",
        )
        assertThat(OpfCoverLocator.coverImagePath(bytes, "OEBPS/content.opf")).isNull()
    }

    @Test fun `remote hrefs, paths escaping the container, and malformed xml find nothing`() {
        val remote = opf(
            version = "3.0",
            manifest = """<item id="c" href="https://example.com/c.jpg" media-type="image/jpeg" properties="cover-image"/>""",
        )
        val escaping = opf(
            version = "3.0",
            manifest = """<item id="c" href="../../c.jpg" media-type="image/jpeg" properties="cover-image"/>""",
        )
        assertThat(OpfCoverLocator.coverImagePath(remote, "OEBPS/content.opf")).isNull()
        assertThat(OpfCoverLocator.coverImagePath(escaping, "OEBPS/content.opf")).isNull()
        assertThat(OpfCoverLocator.coverImagePath(byteArrayOf(0x00, 0x01), "OEBPS/content.opf")).isNull()
    }

    @Test fun `findCoverImageEntry returns the zip entry, or null when the file is missing`() {
        val opfBytes = opf(
            version = "3.0",
            manifest = """<item id="c" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>""",
        )
        val withImage = zip("with", opfBytes, mapOf("OEBPS/images/cover.jpg" to byteArrayOf(1, 2, 3)))
        val withoutImage = zip("without", opfBytes, emptyMap())

        ZipFile(withImage).use { assertThat(it.findCoverImageEntry()?.name).isEqualTo("OEBPS/images/cover.jpg") }
        ZipFile(withoutImage).use { assertThat(it.findCoverImageEntry()).isNull() }
    }

    private fun zip(name: String, opfBytes: ByteArray, extra: Map<String, ByteArray>) =
        tmp.newFile("$name.epub").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/container.xml"))
                zip.write(
                    """
                    <?xml version="1.0"?>
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """.trimIndent().toByteArray(),
                )
                zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
                zip.write(opfBytes)
                extra.forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                }
            }
        }
}
