package io.theficos.ereader.ui.bookdetail

import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.Progress
import java.math.BigDecimal
import java.util.Locale

internal fun seriesLine(name: String?, index: Double?): String? {
    val series = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val position = index?.takeIf { it.isFinite() }
        ?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }
    return if (position == null) series else "$series, book $position"
}

internal fun publishedYear(date: String?): String? =
    date?.let { Regex("^[0-9]{4}").find(it)?.value }?.takeIf { it.toInt() >= 1000 }

private val iso3Languages by lazy {
    Locale.getISOLanguages().mapNotNull { code ->
        runCatching { Locale.forLanguageTag(code).getISO3Language() }.getOrNull()
            ?.let { it to code }
    }.toMap()
}

internal fun languageName(code: String?, locale: Locale = Locale.getDefault()): String? {
    val tag = code?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
        ?: return null
    val language = Locale.forLanguageTag(tag).let {
        iso3Languages[it.language]?.let(Locale::forLanguageTag) ?: it
    }
    val display = language.getDisplayLanguage(locale)
    if (display.isBlank() || display.equals(language.language, ignoreCase = true)) return tag
    return display.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

internal fun subjectsLine(subjects: List<String>): String? = subjects
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinctBy { it.lowercase(Locale.ROOT) }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(", ")

/** Whether the reader has got anywhere with the book: past its start, or done with it either way. */
internal fun Progress.isStarted(): Boolean =
    percent >= 0.000001 || finishedAt != null || abandonedAt != null

internal fun readingStatus(progress: Progress?, formatDate: (Long) -> String): String {
    if (progress == null || !progress.isStarted()) return "Not started"
    progress.finishedAt?.let { return "Finished on ${formatDate(it)}" }
    progress.abandonedAt?.let { return "Abandoned on ${formatDate(it)}" }
    return "${(progress.percent * 100).toInt()}% read"
}

internal data class BookFact(val label: String, val value: String)

internal fun bookFacts(
    metadata: MetadataBundle?,
    document: Document,
    progress: Progress?,
    fileSizeBytes: Long?,
    formatDate: (Long) -> String,
    formatRelative: (Long) -> String,
    formatSize: (Long) -> String,
): List<BookFact> = buildList {
    fun addFact(label: String, value: String?) {
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { add(BookFact(label, it)) }
    }

    addFact("Publisher", metadata?.publisher)
    addFact("Published", publishedYear(metadata?.publishDate))
    addFact("Language", languageName(metadata?.language))
    addFact("Subjects", subjectsLine(metadata?.subjects.orEmpty()))
    addFact("ISBN", metadata?.isbn)
    addFact("Added", formatDate(document.downloadedAt))
    // Never alongside "Not started": a progress row can exist for a book still on its first page.
    addFact(
        "Last read",
        progress?.takeIf { it.isStarted() }?.updatedAt?.takeIf { it > 0L }?.let(formatRelative),
    )
    addFact("File size", fileSizeBytes?.takeIf { it > 0L }?.let(formatSize))
}
