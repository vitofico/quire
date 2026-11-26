package io.theficos.ereader.ui.bookdetail

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.data.ai.BookDetails
import io.theficos.ereader.data.ai.DetailsOrigin
import io.theficos.ereader.data.ai.FIELD_AUTHORS
import io.theficos.ereader.data.ai.FIELD_DESCRIPTION
import io.theficos.ereader.data.ai.FIELD_SERIES
import io.theficos.ereader.data.ai.FIELD_TITLE
import io.theficos.ereader.data.ai.detailsOrigin
import io.theficos.ereader.data.ai.shownWith
import io.theficos.ereader.data.ai.shows
import io.theficos.ereader.data.ai.showsCover
import io.theficos.quire.R
import java.net.URI

/** The label for a found field, or null for the book's own. */
@StringRes
internal fun originNote(origin: String?): Int? = when (detailsOrigin(origin)) {
    DetailsOrigin.OPEN_LIBRARY -> R.string.details_from_open_library
    DetailsOrigin.TIDIED_BY_AI -> R.string.details_tidied_by_ai
    DetailsOrigin.WRITTEN_BY_AI -> R.string.details_written_by_ai
    DetailsOrigin.OWN, null -> null
}

/**
 * The labels under the title: where the title, authors and series came from (AI over Open
 * Library, when both had a hand), and where the cover came from when the found one shows.
 */
internal fun headerNotes(document: Document, details: BookDetails): List<Int> =
    listOfNotNull(titleNote(document, details), shownCoverNote(document, details))

/** Where the found title, authors and series [document] shows came from, or null when they are the book's own. */
@StringRes
internal fun titleNote(document: Document, details: BookDetails): Int? {
    val origins = listOf("title" to FIELD_TITLE, "authors" to FIELD_AUTHORS, "series_name" to FIELD_SERIES, "series_index" to FIELD_SERIES)
        .filter { (_, group) -> details.shows(group, document) }
        .mapNotNull { (field, _) -> detailsOrigin(details.response.origins[field]) }
    return when {
        DetailsOrigin.WRITTEN_BY_AI in origins -> R.string.details_written_by_ai
        DetailsOrigin.TIDIED_BY_AI in origins -> R.string.details_tidied_by_ai
        DetailsOrigin.OPEN_LIBRARY in origins -> R.string.details_from_open_library
        else -> null
    }
}

/** Where a stored found cover came from, or null when there is none. */
@StringRes
internal fun coverNote(details: BookDetails): Int? = when {
    details.coverPath == null -> null
    details.response.cover.generated -> R.string.details_generated_cover
    else -> R.string.details_cover_from_open_library
}

/** [coverNote], when the found cover is the one [document] shows: over its own only when the reader chose it. */
@StringRes
internal fun shownCoverNote(document: Document, details: BookDetails): Int? =
    coverNote(details).takeIf { details.showsCover(document) }

/** [text] as a screen reader says it, with the [note] on where it came from. */
@Composable
internal fun withNote(text: String, @StringRes note: Int?): String =
    note?.let { "$text, ${stringResource(it)}" } ?: text

/** Whether the description shown is the found one rather than the book's own. */
internal fun usesFoundDescription(details: BookDetails): Boolean =
    details.response.book.description != null && details.response.accepted?.contains(FIELD_DESCRIPTION) != false &&
        detailsOrigin(details.response.origins["description"]) != DetailsOrigin.OWN

/**
 * The book's metadata with the found details over it, for the description and the facts. A
 * description the server only passed back from the book stays the book's own, HTML and all.
 */
internal fun foundMetadata(metadata: MetadataBundle?, document: Document, details: BookDetails): MetadataBundle {
    val base = metadata ?: MetadataBundle(title = document.title, author = document.author)
    val book = details.response.book
    return base.copy(
        publisher = book.publisher ?: base.publisher,
        publishDate = book.year?.toString() ?: base.publishDate,
        language = book.language ?: base.language,
        subjects = book.subjects.ifEmpty { base.subjects },
        isbn = book.isbn ?: base.isbn,
        description = if (usesFoundDescription(details)) book.description else base.description,
    )
}

/** Whether the found details change what [document] shows, so going back to the original would show something else. */
internal fun differsFromOriginal(document: Document, metadata: MetadataBundle?, details: BookDetails): Boolean {
    val improved = details.copy(useOriginal = false)
    val own = metadata ?: MetadataBundle(title = document.title, author = document.author)
    return document.shownWith(improved) != document || foundMetadata(metadata, document, improved) != own
}

/** The matched Open Library work, when it is a plain https openlibrary.org address. */
internal fun openLibraryLink(details: BookDetails): String? {
    val link = details.response.openLibraryUrl ?: return null
    val uri = runCatching { URI(link) }.getOrNull() ?: return null
    return link.takeIf { uri.scheme == "https" && uri.host == "openlibrary.org" && uri.userInfo == null }
}
