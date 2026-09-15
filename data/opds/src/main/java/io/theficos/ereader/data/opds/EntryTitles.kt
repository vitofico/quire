package io.theficos.ereader.data.opds

/**
 * Cleanup for the `<title>` of an OPDS acquisition entry.
 *
 * Kavita prefixes every book entry's title with a reading-progress glyph and a
 * space — `⭘ Series - Volume 1` for unread, `⬤ …` for finished, and three
 * quarter-circles in between. It is a server-side display convention (the
 * `EmbedProgressIndicator` user preference, verified against Kavita's
 * `OpdsService.cs` and against live feeds from its public demo server), not
 * part of the book's name, and Quire carries the title straight into the
 * library row at download time. Issue #101's tester saw books land as
 * "⭘ EXP Is Golden - EXP Is Golden: Volume 3".
 *
 * Only a glyph that stands alone at the very start is removed, so a title that
 * genuinely begins with one of these characters keeps it unless it is followed
 * by whitespace.
 */
private val READING_PROGRESS_GLYPHS = setOf(
    '⭘', // ⭘ unread
    '◔', // ◔ a quarter read
    '◑', // ◑ half read
    '◕', // ◕ more than half read
    '⬤', // ⬤ finished
)

/** [raw] with a leading reading-progress glyph removed, and trimmed. */
fun cleanEntryTitle(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.length < 2) return trimmed
    if (trimmed[0] !in READING_PROGRESS_GLYPHS) return trimmed
    if (!trimmed[1].isWhitespace()) return trimmed
    return trimmed.drop(1).trimStart()
}
