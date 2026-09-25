package io.theficos.ereader.reader

import java.io.File

/**
 * Remembers, per book file, which XHTML documents need the HTML parser, so the check in
 * [malformedXhtml], which reads every document, runs on a book's first open and not on every one.
 *
 * Each book gets one small text file under [dir]: a key line, then one href per line. The key
 * changes when the book file is replaced or when the rules in [xmlParseError] change (bump
 * [RULES]), and a stale or missing entry just means checking the book again.
 */
internal class XhtmlVerdicts(private val dir: File) {

    operator fun get(book: File): Set<String>? {
        val lines = runCatching { entry(book).readLines() }.getOrNull() ?: return null
        if (lines.firstOrNull() != key(book)) return null
        return lines.drop(1).toSet()
    }

    operator fun set(book: File, malformed: Set<String>) {
        // A failed write only costs the next open another check.
        runCatching {
            dir.mkdirs()
            entry(book).writeText((listOf(key(book)) + malformed).joinToString("\n"))
        }
    }

    private fun entry(book: File) = File(dir, "${book.name}.txt")

    private fun key(book: File) = "$RULES\t${book.absolutePath}\t${book.length()}\t${book.lastModified()}"

    private companion object {
        const val RULES = "xhtml-rules-1"
    }
}
