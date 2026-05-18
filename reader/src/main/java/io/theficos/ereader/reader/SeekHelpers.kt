package io.theficos.ereader.reader

import org.readium.r2.shared.publication.Locator
import kotlin.math.roundToInt

/**
 * Maps a 0..1 progress fraction to a [Locator] inside a sorted-by-progression
 * list of positions (typically obtained from `Publication.positions()`).
 *
 * Returns null when the list is empty. The percent is clamped to [0, 1].
 */
fun locatorAtPercent(positions: List<Locator>, percent: Double): Locator? {
    if (positions.isEmpty()) return null
    val clamped = percent.coerceIn(0.0, 1.0)
    val idx = (clamped * (positions.size - 1)).roundToInt()
    return positions[idx]
}
