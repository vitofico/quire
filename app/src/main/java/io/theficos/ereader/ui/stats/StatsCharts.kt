package io.theficos.ereader.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bars for [values], with a label under each bar whose label is not blank. A screen reader
 * hears [description] instead of the drawing, so it should say what the chart shows.
 * Labels follow the font scale: the chart grows to fit them.
 */
@Composable
internal fun BarChart(
    values: List<Long>,
    labels: List<String>,
    description: String,
    modifier: Modifier = Modifier,
    barHeight: Dp = 120.dp,
) {
    val bar = MaterialTheme.colorScheme.primary
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelHeight = remember(style, density) {
        with(density) { measurer.measure("0", style).size.height.toDp() }
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(barHeight + labelHeight + 4.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val count = values.size
        if (count == 0) return@Canvas
        val chartHeight = barHeight.toPx()
        val slot = size.width / count
        val width = slot * 0.7f
        val max = values.max().coerceAtLeast(1)
        val minimum = 2.dp.toPx()
        values.forEachIndexed { i, value ->
            if (value <= 0) return@forEachIndexed
            val height = (chartHeight * value / max).coerceAtLeast(minimum)
            drawRoundRect(
                color = bar,
                topLeft = Offset(i * slot + (slot - width) / 2, chartHeight - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(minOf(width / 2, 3.dp.toPx())),
            )
        }
        drawLine(baseline, Offset(0f, chartHeight), Offset(size.width, chartHeight), strokeWidth = 1.dp.toPx())
        labels.forEachIndexed { i, label ->
            if (label.isBlank()) return@forEachIndexed
            val text = measurer.measure(label, style)
            val x = (i * slot + slot / 2 - text.size.width / 2f).coerceIn(0f, (size.width - text.size.width).coerceAtLeast(0f))
            drawText(text, topLeft = Offset(x, chartHeight + 4.dp.toPx()))
        }
    }
}
