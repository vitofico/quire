package io.theficos.ereader.ui.components

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import io.theficos.quire.R

@Composable
fun MarbledInk(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
) {
    Image(
        painter = painterResource(R.drawable.marbled_ink),
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary.copy(alpha = intensity)),
        contentScale = contentScale,
        alignment = alignment,
    )
}
