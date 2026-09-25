package io.theficos.ereader.sideload

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** A cover to embed: stored at `OEBPS/images/cover.<ext>` and declared as EPUB 3's cover-image. */
internal class TestCover(val mediaType: String, val bytes: ByteArray)

/** Writes a minimal but structurally valid EPUB to [file], with [cover] when given. */
internal fun writeTestEpub(file: File, id: String, title: String, cover: TestCover? = null): File {
    val coverHref = cover?.let { "images/cover." + it.mediaType.substringAfter('/') }
    val coverItem = coverHref?.let {
        """<item id="cover" href="$it" media-type="${cover.mediaType}" properties="cover-image"/>"""
    }.orEmpty()
    ZipOutputStream(file.outputStream()).use { zip ->
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
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
        zip.write(
            """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bid">$id</dc:identifier>
                <dc:title>$title</dc:title>
                <dc:creator>Author</dc:creator>
              </metadata>
              <manifest>$coverItem</manifest>
            </package>
            """.trimIndent().toByteArray(),
        )
        zip.closeEntry()
        if (cover != null && coverHref != null) {
            zip.putNextEntry(ZipEntry("OEBPS/$coverHref"))
            zip.write(cover.bytes)
            zip.closeEntry()
        }
    }
    return file
}

/**
 * An image of the given size, encoded as JPEG or PNG. Solid colour compresses to almost nothing;
 * [noisy] random pixels make a file that is big on disk. Needs native graphics.
 */
internal fun testImage(
    width: Int,
    height: Int,
    format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
    noisy: Boolean = false,
): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        if (noisy) {
            val random = Random(42)
            setPixels(IntArray(width * height) { random.nextInt() or 0xFF000000.toInt() }, 0, width, 0, 0, width, height)
        } else {
            eraseColor(Color.rgb(120, 40, 30))
        }
    }
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(format, 100, out)
        bitmap.recycle()
        out.toByteArray()
    }
}
