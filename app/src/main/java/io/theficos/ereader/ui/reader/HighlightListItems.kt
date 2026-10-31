package io.theficos.ereader.ui.reader

import io.theficos.ereader.core.model.Highlight
import io.theficos.ereader.reader.HighlightLocation
import io.theficos.ereader.reader.compareHighlightLocations
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import org.readium.r2.shared.publication.Link

internal fun highlightListItems(highlights: List<Highlight>, readingOrder: List<Link>): List<HighlightListItem> {
    data class Entry(val highlight: Highlight, val location: HighlightLocation?, val chapter: Int)
    val entries = highlights.filter { it.deletedAt == null }.map { highlight ->
        val location = HighlightLocation.parse(highlight.locator)
        val chapter = location?.let { saved -> readingOrder.indexOfFirst {
            it.href.toString().substringBefore('#') == saved.locator.href.toString().substringBefore('#')
        } } ?: -1
        Entry(highlight, location, chapter)
    }.sortedWith { a, b ->
        val chapterOrder = (if (a.chapter < 0) Int.MAX_VALUE else a.chapter)
            .compareTo(if (b.chapter < 0) Int.MAX_VALUE else b.chapter)
        val structuralOrder = if (chapterOrder == 0 && a.location != null && b.location != null)
            compareHighlightLocations(a.location, b.location) else 0
        chapterOrder.takeIf { it != 0 } ?: structuralOrder.takeIf { it != 0 }
            ?: a.highlight.createdAt.compareTo(b.highlight.createdAt).takeIf { it != 0 }
            ?: a.highlight.id.compareTo(b.highlight.id)
    }
    return entries.map { entry ->
        val canJump = entry.location != null && entry.chapter >= 0
        val label = if (!canJump) null else readingOrder[entry.chapter].title?.takeIf { it.isNotBlank() }
            ?.let { UiText.Dynamic(it) } ?: uiText(R.string.reader_highlight_chapter, entry.chapter + 1)
        HighlightListItem(entry.highlight, label, canJump)
    }
}
