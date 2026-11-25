package io.theficos.ereader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.ui.bookdetail.shownCoverNote
import io.theficos.ereader.ui.bookdetail.titleNote
import io.theficos.ereader.ui.bookdetail.withNote
import io.theficos.ereader.ui.components.CoverImage
import io.theficos.ereader.ui.components.QuireCard
import io.theficos.ereader.ui.components.SectionLabel
import io.theficos.quire.R

@Composable
fun ContinueReadingCard(row: LibraryRow, onClick: () -> Unit) {
    val percentInt = (row.percent * 100).toInt().coerceIn(0, 100)
    QuireCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Column {
            SectionLabel(stringResource(R.string.common_continue_reading))
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                CoverImage(
                    source = row.shown.coverPath,
                    title = row.shown.title,
                    author = row.shown.author,
                    modifier = Modifier.size(width = 64.dp, height = 96.dp),
                    contentDescription = withNote(row.shown.title, row.found?.let { shownCoverNote(row.document, it) }),
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    val spokenTitle = withNote(row.shown.title, row.found?.let(::titleNote))
                    Text(
                        text = row.shown.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { contentDescription = spokenTitle },
                    )
                    row.shown.author?.let { author ->
                        Text(
                            text = author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .height(3.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(row.percent.toFloat())
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Text(
                        text = stringResource(R.string.common_reading_percent, percentInt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
