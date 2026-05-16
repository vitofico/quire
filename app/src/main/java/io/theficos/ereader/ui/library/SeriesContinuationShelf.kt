package io.theficos.ereader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.ui.components.CoverImage
import io.theficos.ereader.ui.components.SectionLabel

/**
 * Horizontal "Continue your series" shelf shown above the main library grid
 * on the home screen.
 *
 * Renders nothing when [books] is empty — callers can include it unconditionally
 * and it will simply disappear from layout.
 */
@Composable
fun SeriesContinuationShelf(
    books: List<Document>,
    onBookClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (books.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = SHELF_CONTENT_DESCRIPTION },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel(SHELF_HEADER)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(items = books, key = { it.id }) { book ->
                SeriesShelfItem(
                    book = book,
                    onClick = { onBookClick(book.id) },
                )
            }
        }
    }
}

@Composable
private fun SeriesShelfItem(
    book: Document,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .clickable { onClick() },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CoverImage(
            source = book.coverPath,
            title = book.title,
            author = book.author,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        )
        Text(
            text = book.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        val seriesLabel = buildSeriesLabel(book.seriesName, book.seriesIndex)
        if (seriesLabel != null) {
            Text(
                text = seriesLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal const val SHELF_HEADER: String = "Continue your series"
internal const val SHELF_CONTENT_DESCRIPTION: String = "Continue your series shelf"

internal fun buildSeriesLabel(seriesName: String?, seriesIndex: Double?): String? {
    val name = seriesName?.takeIf { it.isNotBlank() } ?: return null
    val bookN = seriesIndex?.let { idx ->
        // Render whole numbers without ".0" (1.0 -> "1", 2.5 -> "2.5").
        if (idx == idx.toLong().toDouble()) " · Book ${idx.toLong()}"
        else " · Book $idx"
    } ?: ""
    return "$name$bookN"
}
