package io.theficos.ereader.ui.bookdetail

import androidx.annotation.StringRes
import io.theficos.quire.R
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.uiText
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.Progress
import java.math.BigDecimal
import java.util.Locale

internal fun seriesLine(name: String?, index: Double?): UiText? {
    val series = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val position = index?.takeIf { it.isFinite() }
        ?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }
    return if (position == null) UiText.Dynamic(series) else uiText(R.string.book_series_position, series, position)
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

internal fun readingStatus(progress: Progress?, formatDate: (Long) -> String): UiText {
    if (progress == null || !progress.isStarted()) return uiText(R.string.book_not_started)
    progress.finishedAt?.let { return uiText(R.string.book_finished_on, formatDate(it)) }
    progress.abandonedAt?.let { return uiText(R.string.book_abandoned_on, formatDate(it)) }
    return uiText(R.string.book_percent_read, (progress.percent * 100).toInt())
}

internal data class BookFact(@StringRes val label: Int, val value: String)

internal fun bookFacts(
    metadata: MetadataBundle?,
    document: Document,
    progress: Progress?,
    fileSizeBytes: Long?,
    formatDate: (Long) -> String,
    formatRelative: (Long) -> String,
    formatSize: (Long) -> String,
    displayLocale: Locale = Locale.getDefault(),
): List<BookFact> = buildList {
    fun addFact(@StringRes label: Int, value: String?) {
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { add(BookFact(label, it)) }
    }

    addFact(R.string.book_publisher, metadata?.publisher)
    addFact(R.string.book_published, publishedYear(metadata?.publishDate))
    addFact(R.string.common_language, languageName(metadata?.language, displayLocale))
    addFact(R.string.book_subjects, subjectsLine(metadata?.subjects.orEmpty()))
    addFact(R.string.book_isbn, metadata?.isbn)
    addFact(R.string.book_added, formatDate(document.downloadedAt))
    // Never alongside "Not started": a progress row can exist for a book still on its first page.
    addFact(
        R.string.book_last_read,
        progress?.takeIf { it.isStarted() }?.updatedAt?.takeIf { it > 0L }?.let(formatRelative),
    )
    addFact(R.string.book_file_size, fileSizeBytes?.takeIf { it > 0L }?.let(formatSize))
}
