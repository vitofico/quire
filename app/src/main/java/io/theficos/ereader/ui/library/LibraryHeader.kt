package io.theficos.ereader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.theficos.ereader.ui.components.MarbledInk
import io.theficos.quire.R

@Composable
internal fun LibraryHeader(actions: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val titleProtection = (0.34f * LocalDensity.current.fontScale).coerceIn(0.34f, 0.72f)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(116.dp).clipToBounds()) {
            MarbledInk(
                modifier = Modifier.align(Alignment.CenterEnd).size(width = 336.dp, height = 116.dp),
                intensity = if (colors.surface.luminance() < 0.5f) 0.72f else 0.9f,
                contentScale = ContentScale.Crop,
                alignment = Alignment.BottomEnd,
            )
            // Preserve the title's paper background as its text scales up.
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(
                        0f to colors.surface,
                        titleProtection to colors.surface,
                        (titleProtection + 0.22f).coerceAtMost(0.98f) to colors.surface.copy(alpha = 0f),
                        1f to colors.surface.copy(alpha = 0f),
                    ),
                ),
            )
            Text(
                text = stringResource(R.string.brand_name),
                style = MaterialTheme.typography.displaySmall,
                color = colors.onSurface,
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 12.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}
