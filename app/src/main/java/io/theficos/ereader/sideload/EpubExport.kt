package io.theficos.ereader.sideload

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Save EPUB as…": hands a copy of a library book's EPUB to wherever the system file
 * picker points (local storage, Google Drive, Nextcloud…). Quire never sees the
 * destination's account; the picker's document provider does the writing.
 */
object EpubExport {
    private const val TAG = "EpubExport"

    /**
     * Upper bound on the name before ".epub", in UTF-8 bytes. Most filesystems cap a
     * name at 255 bytes; the slack leaves room for the " (1)" a provider adds on a clash.
     */
    internal const val MAX_STEM_BYTES = 200

    private val ILLEGAL = Regex("""[\\/:*?"<>|\p{Cntrl}]+""")
    private val SPACES = Regex("""\s+""")

    /**
     * The name the picker suggests: "<Title> - <Author>.epub", or "<Title>.epub" without
     * an author. Without a usable title it falls back to [fallbackFileName] (the book's
     * file on disk, or the original name of a converted file) with its extension swapped
     * for ".epub". Characters that some filesystems reject are replaced by spaces.
     */
    fun suggestedFileName(title: String?, author: String?, fallbackFileName: String): String {
        val cleanTitle = sanitize(title)
        val stem = when {
            cleanTitle.isEmpty() -> sanitize(stripExtension(fallbackFileName)).ifEmpty { "book" }
            else -> sanitize(author).let { if (it.isEmpty()) cleanTitle else "$cleanTitle - $it" }
        }
        return truncate(stem) + ".epub"
    }

    /**
     * Streams [source] into [target] on [Dispatchers.IO], leaving [source] untouched.
     * Returns false on any failure, after a best-effort delete of the half-written
     * [target] so a broken book is not left behind at the destination.
     */
    suspend fun copyTo(resolver: ContentResolver, source: File, target: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val copied = try {
                source.inputStream().use { input ->
                    resolver.openOutputStream(target)?.use { output ->
                        input.copyTo(output)
                        true
                    } ?: false
                }
            } catch (e: Exception) {
                Log.w(TAG, "saving ${source.name} failed", e)
                false
            }
            if (!copied) runCatching { DocumentsContract.deleteDocument(resolver, target) }
            copied
        }

    private fun sanitize(raw: String?): String =
        raw.orEmpty().replace(ILLEGAL, " ").replace(SPACES, " ").trim(' ', '.')

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** Cuts [stem] to [MAX_STEM_BYTES] on a code point boundary, never mid-character. */
    private fun truncate(stem: String): String {
        if (stem.toByteArray(Charsets.UTF_8).size <= MAX_STEM_BYTES) return stem
        var bytes = 0
        var end = 0
        while (end < stem.length) {
            val cp = stem.codePointAt(end)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > MAX_STEM_BYTES) break
            bytes += size
            end += Character.charCount(cp)
        }
        return stem.substring(0, end).trimEnd(' ', '.', '-')
    }
}
