package io.theficos.ereader.ui.catalog

import io.theficos.ereader.data.opds.OpdsPublication

enum class CatalogSort { AUTHOR, TITLE, AS_SHOWN }

/**
 * Reorder a catalog page for display.
 *
 * [CatalogSort.AS_SHOWN] is the default, and it is the only one that is always
 * right. An OPDS feed is an ordered document: the server chose the order and it
 * usually carries meaning a client cannot reconstruct. A series page lists its
 * volumes in reading order, "recently added" is newest first, "on deck" is the
 * order you should read next. Sorting those alphabetically throws that away, and
 * it does so in ways that look like corruption rather than like a preference,
 * because entry metadata is only as tidy as whoever typed it (issue #105).
 *
 * The alphabetical options stay available for the flat, unordered "all books"
 * listings where they genuinely help.
 */
internal fun applyCatalogSort(
    list: List<OpdsPublication>,
    by: CatalogSort,
): List<OpdsPublication> = when (by) {
    CatalogSort.AUTHOR -> list.sortedWith(
        compareBy<OpdsPublication> { it.author?.lowercase() ?: "￿" }
            .thenBy { it.title.lowercase() }
    )
    CatalogSort.TITLE -> list.sortedBy { it.title.lowercase() }
    CatalogSort.AS_SHOWN -> list
}
