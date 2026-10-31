package io.theficos.ereader.reader

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/** Structural reading order is stable across fonts; a saved percentage is not a row's order. */
data class HighlightLocation(val locator: Locator, val path: List<Int>, val offset: Int) {
    companion object {
        fun parse(json: String): HighlightLocation? = runCatching {
            val locator = Locator.fromJSON(JSONObject(json)) ?: return@runCatching null
            if (!isHighlightDestination(locator)) return@runCatching null
            val raw = locator.locations.otherLocations[StructuralSelectionAnchors.KEY] ?: return@runCatching null
            val anchor = if (raw is String) JSONObject(raw) else JSONObject(raw.toString())
            if (anchor.optInt("version") != 3 || anchor.optString("href") != locator.href.toString() ||
                !anchor.optString("documentHash").matches(Regex("[a-f0-9]{64}")) ||
                anchor.optString("quote").isBlank() || !anchor.has("rangeText")) return@runCatching null
            fun point(name: String): Pair<List<Int>, Int> {
                val point = anchor.getJSONObject(name)
                require(point.getInt("nodeType") in listOf(1, 3, 4))
                val path = point.getJSONArray("path")
                val indexes = (0 until path.length()).map { i ->
                    val number = path.get(i) as? Number ?: error("invalid path")
                    number.toInt().also { require(it >= 0 && it.toDouble() == number.toDouble()) }
                }
                val offset = point.get("offset") as? Number ?: error("invalid offset")
                return indexes to offset.toInt().also { require(it >= 0 && it.toDouble() == offset.toDouble()) }
            }
            val (path, offset) = point("start")
            point("end")
            HighlightLocation(locator, path, offset)
        }.getOrNull()
    }
}

internal const val VIEWPORT_POSITION_KEY = "quireViewportPosition"
internal fun isHighlightDestination(locator: Locator): Boolean = !locator.locations.otherLocations.containsKey(VIEWPORT_POSITION_KEY)

/** Compare child indexes numerically, including ancestor-prefix boundaries. */
fun compareHighlightLocations(first: HighlightLocation, second: HighlightLocation): Int {
    val limit = minOf(first.path.size, second.path.size)
    for (i in 0 until limit) {
        val compared = first.path[i].compareTo(second.path[i])
        if (compared != 0) return compared
    }
    // An element endpoint offset identifies a child boundary, not the element's beginning.
    if (first.path.size < second.path.size) return if (first.offset <= second.path[limit]) -1 else 1
    if (first.path.size > second.path.size) return if (second.offset <= first.path[limit]) 1 else -1
    return first.offset.compareTo(second.offset)
}
