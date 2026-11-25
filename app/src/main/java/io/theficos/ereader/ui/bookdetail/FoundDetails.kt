package io.theficos.ereader.ui.bookdetail

import androidx.annotation.StringRes
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.data.ai.BookDetails
import io.theficos.ereader.data.ai.DetailsOrigin
import io.theficos.ereader.data.ai.detailsOrigin
import io.theficos.quire.R

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
internal fun headerNotes(document: Document, details: BookDetails): List<Int> {
    val origins = listOf("title", "authors", "series_name").mapNotNull { detailsOrigin(details.response.origins[it]) }
    val fields = when {
        DetailsOrigin.WRITTEN_BY_AI in origins -> R.string.details_written_by_ai
        DetailsOrigin.TIDIED_BY_AI in origins -> R.string.details_tidied_by_ai
        DetailsOrigin.OPEN_LIBRARY in origins -> R.string.details_from_open_library
        else -> null
    }
    val cover = when {
        document.coverPath != null || details.coverPath == null -> null
        details.response.cover.generated -> R.string.details_generated_cover
        else -> R.string.details_cover_from_open_library
    }
    return listOfNotNull(fields, cover)
}

/**
 * The book's metadata with the found details over it, for the description and the facts. A
 * description the server only passed back from the book stays the book's own, HTML and all.
 */
internal fun foundMetadata(metadata: MetadataBundle?, document: Document, details: BookDetails): MetadataBundle {
    val base = metadata ?: MetadataBundle(title = document.title, author = document.author)
    val book = details.response.book
    val ownDescription = detailsOrigin(details.response.origins["description"]) == DetailsOrigin.OWN
    return base.copy(
        publisher = book.publisher ?: base.publisher,
        publishDate = book.year?.toString() ?: base.publishDate,
        language = book.language ?: base.language,
        subjects = book.subjects.ifEmpty { base.subjects },
        isbn = book.isbn ?: base.isbn,
        description = book.description?.takeUnless { ownDescription } ?: base.description,
    )
}
