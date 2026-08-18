package io.theficos.ereader.reader

import org.readium.r2.shared.publication.Locator

/**
 * Builds the locator the reader goes back to after its viewport changes.
 *
 * Readium restores a locator in `R2EpubPageFragment.loadLocator()`, and what it does there
 * depends entirely on what the locator carries:
 *
 *  1. `text.highlight != null` — `scrollToLocator()`: look the element up in the DOM by its
 *     CSS selector and scroll its box into view. Independent of how the text is paginated.
 *  2. else `locations.htmlId` — `scrollToId()`: the same idea, by element id.
 *  3. else — `item = round(progression * numPages)`, then `setCurrentItem(item)`.
 *
 * What Readium publishes on `currentLocator` never has the first two. It builds that locator
 * from the EPUB positions service, which emits `Locations(progression, position)` and nothing
 * else, so re-anchoring on it always took the third path: a fraction mapped onto a page grid.
 * Rotation rebuilds that grid (in the fixture book, 12 portrait pages against 23 landscape
 * ones) and the arithmetic runs while it is still being rebuilt, so the page it lands on is
 * only loosely related to the page it left. Worse, Readium then publishes wherever it landed,
 * that becomes the anchor for the next rotation, and the error walks: a real trace went
 * paragraph 18 to 31 to 38 over three rotate-and-return trips.
 *
 * [dom] is where the page begins in the document, from [PAGE_START_ANCHOR_JS]: a `cssSelector`
 * and the element's text, and no progression whatsoever. Handing that to Readium on its own
 * would trade one bug for a worse one, because if the selector ever failed to resolve Readium
 * would fall back to `progression ?: 0.0` and jump to the top of the chapter, and because the
 * progress row and the HUD percentage both read their numbers off this same locator.
 *
 * So keep both. The DOM fields decide where to land; [live] keeps the fallback honest and the
 * percentages unchanged. Falls back to [live] untouched whenever [dom] cannot be trusted:
 * absent, textless (nothing for `scrollToLocator` to match), or pointing at another resource,
 * which would be the dangerous case — selectors here are positional (`:nth-child(19)`), so one
 * from a different chapter resolves happily against the wrong paragraph.
 */
fun resizeAnchor(live: Locator, dom: Locator?): Locator {
    if (dom == null || dom.text.highlight.isNullOrBlank()) return live
    if (dom.href != live.href) return live
    return live.copy(
        locations = live.locations.copy(
            otherLocations = live.locations.otherLocations + dom.locations.otherLocations,
        ),
        text = dom.text,
    )
}
