package io.theficos.ereader.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.MaterialTheme
import io.theficos.ereader.ui.theme.BackgroundStyle
import io.theficos.ereader.ui.theme.LocalBackgroundStyle
import io.theficos.quire.R

/** One static edge crop, behind browsing chrome only; never used by the EPUB reader. */
@Composable
fun InkBackground(
    modifier: Modifier = Modifier,
    style: BackgroundStyle = LocalBackgroundStyle.current,
) {
    if (style != BackgroundStyle.ARTWORK) return
    val ink = MaterialTheme.colorScheme.primary
    Box(modifier.clipToBounds().graphicsLayer {
        // Apply the quiet background opacity after the pigment-to-alpha filter;
        // paint alpha before that filter would erase most of the ink detail.
        compositingStrategy = CompositingStrategy.Offscreen
        alpha = 0.11f
    }) {
        Image(
            painter = painterResource(R.drawable.background_ink),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            // Move the pigment detail into alpha before tinting: a simple tint flattens
            // the light veins into solid shapes and loses the approved ink texture.
            colorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                0f, 0f, 0f, 0f, ink.red * 255f,
                0f, 0f, 0f, 0f, ink.green * 255f,
                0f, 0f, 0f, 0f, ink.blue * 255f,
                -0.2126f, -0.7152f, -0.0722f, 1f, 0f,
            ))),
            contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd,
        )
    }
}
