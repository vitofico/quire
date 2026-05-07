package io.theficos.ereader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import io.theficos.ereader.ui.theme.Lora

private val FallbackPalettes = listOf(
    Color(0xFF7A2E2A) to Color(0xFF4A1A18),  // oxblood
    Color(0xFF3B5A4A) to Color(0xFF1F3B2C),  // forest
    Color(0xFF1F2B4A) to Color(0xFF0F1530),  // ink
    Color(0xFF6A4A2A) to Color(0xFF3F2A18),  // tobacco
)

@Composable
fun CoverImage(
    source: Any?,
    title: String,
    author: String?,
    modifier: Modifier = Modifier,
) {
    val initials = remember(title, author) { computeInitials(title, author) }
    val palette = remember(title) {
        FallbackPalettes[(title.hashCode() and 0x7FFFFFFF) % FallbackPalettes.size]
    }
    val shape = RoundedCornerShape(4.dp)

    Box(
        modifier = modifier.clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (source != null) {
            SubcomposeAsyncImage(
                model = source,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { Fallback(initials, palette) },
                error = { Fallback(initials, palette) },
            )
        } else {
            Fallback(initials, palette)
        }
    }
}

@Composable
private fun Fallback(initials: String, palette: Pair<Color, Color>) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(palette.first, palette.second),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.displaySmall,
            fontFamily = Lora,
            fontWeight = FontWeight.SemiBold,
            fontSize = 36.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp),
        )
    }
}

private fun computeInitials(title: String, author: String?): String {
    val source = author?.takeIf { it.isNotBlank() } ?: title
    val parts = source.trim().split(Regex("\\s+"))
    return when {
        parts.isEmpty() -> "·"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts.first().firstOrNull()?.toString().orEmpty() +
                 parts.last().firstOrNull()?.toString().orEmpty()).uppercase()
    }
}
