package io.theficos.ereader.sideload

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.theficos.ereader.core.metadata.findCoverImageEntry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Stores an EPUB's own cover image the way the catalog path stores the cover it downloads:
 * a `<uuid>.cover` file of image bytes next to `<uuid>.epub` in the books folder, which Coil
 * decodes by content. The catalog path asks the server for its thumbnail link first, so a
 * cover that is big on disk or huge in pixels is shrunk to fit [MAX_EDGE_PX] rather than kept
 * at full resolution.
 */
internal object EpubCoverWriter {

    /**
     * Longest edge a stored cover keeps. The largest place a cover is drawn is a library tile,
     * a third of the screen wide: about 400 px on a phone and 500 px on a tablet.
     */
    const val MAX_EDGE_PX = 900

    /**
     * A cover at most this big on disk, and at most twice [MAX_EDGE_PX] on its longer side, is
     * stored byte for byte: re-encoding it would only lose quality, and can even grow the file
     * (Project Gutenberg's 800x1104 Alice cover comes out larger as a 652x900 JPEG).
     */
    private const val MAX_PASSTHROUGH_BYTES = 512L * 1024

    private const val JPEG_QUALITY = 85

    /**
     * Writes [epub]'s cover image to [dest] and returns it, or returns null when the EPUB
     * declares no cover image that decodes. Throws on IO failure; never leaves a partial file.
     */
    fun write(epub: File, dest: File): File? {
        ZipFile(epub).use { zip ->
            val entry = zip.findCoverImageEntry() ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val longEdge = max(bounds.outWidth, bounds.outHeight)

            val tmp = File(dest.parentFile, "${dest.name}.part")
            try {
                if (entry.size in 0..MAX_PASSTHROUGH_BYTES && longEdge <= 2 * MAX_EDGE_PX) {
                    zip.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                } else {
                    val bitmap = decodeScaled(zip, entry, longEdge) ?: return null
                    try {
                        // JPEG has no transparency; keep PNG for the rare cover that uses it.
                        val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                        tmp.outputStream().use { out ->
                            check(bitmap.compress(format, JPEG_QUALITY, out)) { "cover re-encode failed" }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                if (dest.exists()) dest.delete()
                check(tmp.renameTo(dest)) { "rename ${tmp.name} -> ${dest.name} failed" }
                return dest
            } finally {
                tmp.delete()
            }
        }
    }

    /**
     * Decodes at a power-of-two subsample first, so a huge image never lands in memory at full
     * size (at most about twice [MAX_EDGE_PX] per side), then scales to fit [MAX_EDGE_PX].
     */
    private fun decodeScaled(zip: ZipFile, entry: ZipEntry, longEdge: Int): Bitmap? {
        var sample = 1
        while (longEdge / (sample * 2) >= MAX_EDGE_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val scale = MAX_EDGE_PX.toFloat() / max(decoded.width, decoded.height)
        if (scale >= 1f) return decoded
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }
}
