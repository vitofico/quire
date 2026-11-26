package io.theficos.ereader.reader

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.use

/**
 * Words in the publication's reading order. A document that cannot be read is left out, so a
 * damaged book still gets a count (a little low) instead of being read again on every open;
 * null only when no document can be read. Reads each document once: call it off the main
 * thread, once per book, and keep the answer.
 */
suspend fun Publication.countWords(): Long? {
    var total: Long? = null
    for (link in readingOrder) {
        currentCoroutineContext().ensureActive()
        val bytes = get(link)?.use { it.read().getOrNull() } ?: continue
        val html = link.mediaType?.matches(MediaType.HTML) == true
        total = (total ?: 0L) + countWords(utf8Document(bytes, html).toString(Charsets.UTF_8))
    }
    return total
}

private val IGNORED = Regex("(?is)<(head|script|style)\\b.*?</\\1\\s*>|<!--.*?-->")
private val TAG = Regex("(?s)<[^>]*>")
private val APOSTROPHE_ENTITY = Regex("(?i)&(apos|rsquo|#0*39|#x0*27|#0*8217|#x0*2019);")
private val ENTITY = Regex("&#?\\w+;")

/**
 * Counts the words of a document's body text. A word is a run of letters, digits and marks, with
 * apostrophes and hyphens inside it. Chinese and Japanese are written without spaces, so each of
 * their characters counts as one, the unit reading speed is usually measured in for them.
 */
internal fun countWords(markup: String): Long {
    val text = markup
        .replace(IGNORED, " ")
        .replace(TAG, " ")
        // An apostrophe stays inside its word ("don&rsquo;t"); other entities separate words.
        .replace(APOSTROPHE_ENTITY, "’")
        .replace(ENTITY, " ")
    var words = 0L
    var inWord = false
    var i = 0
    while (i < text.length) {
        val c = text.codePointAt(i)
        i += Character.charCount(c)
        when {
            // The long vowel mark lengthens the kana before it ("ラーメン"): not a word of its own.
            c == 0x30FC || c == 0xFF70 -> Unit
            c.isIdeographic() -> {
                words++
                inWord = false
            }
            Character.isLetterOrDigit(c) || Character.getType(c).isMark() -> {
                if (!inWord) words++
                inWord = true
            }
            inWord && (c == '\''.code || c == '’'.code || c == '-'.code) -> Unit
            else -> inWord = false
        }
    }
    return words
}

private fun Int.isIdeographic(): Boolean = when (Character.UnicodeScript.of(this)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
    else -> false
}

private fun Int.isMark(): Boolean =
    this == Character.NON_SPACING_MARK.toInt() || this == Character.COMBINING_SPACING_MARK.toInt() ||
        this == Character.ENCLOSING_MARK.toInt()
