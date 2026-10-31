package io.theficos.ereader.ui.reader

import java.security.MessageDigest
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** Stable identity, independent of credential rotation. No raw account information is stored. */
fun memoryAccountKey(origin: String, subject: String): String = MessageDigest.getInstance("SHA-256")
    .digest((origin.trimEnd('/') + "\u0000" + subject).toByteArray())
    .joinToString("") { "%02x".format(it) }

enum class StopBoundary { SECTION, RESOURCE, END }
data class StoppingPoint(val locations: Int, val boundary: StopBoundary, val fractionOfBook: Double)

/** Distances use publication locations, never screen pages or elapsed wall-clock time. */
fun stoppingPoint(publication: Publication, current: Locator?, positions: List<Locator>?): StoppingPoint? =
    estimateStoppingPoint(publication.readingOrder.map { it.href.toString() },
        publication.tableOfContents, current, positions.orEmpty())

internal fun estimateStoppingPoint(
    readingOrder: List<String>, toc: List<Link>, current: Locator?, positions: List<Locator>,
): StoppingPoint? {
    current ?: return null
    if (positions.isEmpty() || readingOrder.isEmpty()) return null
    fun resource(href: String) = href.substringBefore('#')
    val currentResource = resource(current.href.toString())
    val spineIndex = readingOrder.indexOfFirst { resource(it) == currentResource }
    if (spineIndex < 0) return null
    val nextResource = readingOrder.getOrNull(spineIndex + 1)?.let(::resource)
    // A global position number is the only unambiguous index; don't guess from resource progress.
    val position = current.locations.position ?: return null
    val currentIndex = positions.indexOfFirst { it.locations.position == position }
    if (currentIndex < 0 || resource(positions[currentIndex].href.toString()) != currentResource) return null
    val numbered = positions.mapNotNull { it.locations.position }
    if (numbered.size != positions.size || numbered.first() < 1 ||
        numbered.zipWithNext().any { (a, b) -> b <= a }) return null
    val boundaries = mutableListOf<Pair<Int, StopBoundary>>()
    fun visit(links: List<Link>) {
        links.forEach { link ->
            val href = link.href.toString()
            val targetResource = resource(href)
            val fragment = href.substringAfter('#', "")
            val index = positions.indexOfFirst {
                resource(it.href.toString()) == resource(href) &&
                    (fragment.isEmpty() || it.href.toString().substringAfter('#', "") == fragment ||
                        fragment in it.locations.fragments)
            }
            // Fragment-bearing TOC entries only count when a position actually resolves them.
            if (index > currentIndex && (targetResource == currentResource ||
                    (targetResource == nextResource && fragment.isEmpty()))) {
                boundaries += index to if (fragment.isNotEmpty()) StopBoundary.SECTION else StopBoundary.RESOURCE
            }
            visit(link.children)
        }
    }
    visit(toc)
    readingOrder.getOrNull(spineIndex + 1)?.let { href ->
        val index = positions.indexOfFirst { resource(it.href.toString()) == resource(href) }
        if (index > currentIndex) boundaries += index to StopBoundary.RESOURCE
    }
    fun result(index: Int, boundary: StopBoundary): StoppingPoint {
        val distance = (index - currentIndex).coerceAtLeast(0)
        return StoppingPoint(distance, boundary, distance.toDouble() / positions.size)
    }
    val next = boundaries.minByOrNull { it.first }
    if (next != null) return result(next.first, next.second)
    // End is defensible only in the final resource with a final reading-order position.
    if (spineIndex != readingOrder.lastIndex || resource(positions.last().href.toString()) != currentResource) return null
    return result(if (current.locations.totalProgression == 1.0) currentIndex else positions.size, StopBoundary.END)
}
