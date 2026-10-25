package io.theficos.ereader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import io.theficos.ereader.ui.components.MarbledInk

/** A cropped edge of the ink sheet, fading to plain paper before shelf prose. */
@Composable
internal fun LibraryBackdrop(modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.fillMaxWidth().height(128.dp).clipToBounds()) {
        MarbledInk(
            modifier = Modifier.align(Alignment.TopEnd)
                .offset(x = 32.dp, y = (-48).dp)
                .size(width = 360.dp, height = 240.dp),
            intensity = 0.48f,
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to surface,
                    0.26f to surface,
                    0.72f to surface.copy(alpha = 0f),
                    1f to surface.copy(alpha = 0f),
                ),
            ),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to surface.copy(alpha = 0.08f),
                    0.35f to surface.copy(alpha = 0.16f),
                    1f to surface,
                ),
            ),
        )
    }
}
