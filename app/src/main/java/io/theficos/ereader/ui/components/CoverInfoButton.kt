package io.theficos.ereader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The (i) in a cover's top-left corner that opens the book's details; align it TopStart. The
 * circle behind it keeps it legible on any cover art, and the button keeps its full 48 dp touch
 * target, since a near miss lands on the cover underneath. It pulls itself toward the corner so
 * the circle sits 4 dp in and clears a badge in the opposite corner on a narrow tile.
 */
@Composable
fun CoverInfoButton(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.offset(x = (-6).dp, y = (-6).dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
