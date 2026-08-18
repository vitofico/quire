package io.theficos.ereader.reader

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * Finds the element that *begins* on the page currently on screen.
 *
 * Readium ships `readium.findFirstVisibleLocator()`, which returns the first element that is
 * visible — anything whose box overlaps the page at all, including a paragraph that started
 * two pages back and merely spills onto this one. That is the wrong end of the paragraph to
 * anchor to, and it is wrong in a way that compounds:
 *
 *   - going back to a locator scrolls to where its element *starts* (`scrollToRect` floors the
 *     element's offset to a page boundary), so restoring the first visible element lands on the
 *     page where that element started, one page or more before where the reader was;
 *   - the reader is then sitting on a page whose own first visible element is the one before
 *     that, so the next rotation anchors one element earlier again.
 *
 * Measured on the marker fixture, that walks backwards forever: anchor paragraph 11 landed on
 * 10, then 7, 6, 4, 2, and finally the chapter heading, one rotation at a time. Capturing and
 * restoring have to be inverses of each other, and they only are if the anchor is an element
 * that starts on this page: restoring puts its start at the page's leading edge, which is this
 * same page, and capturing there returns the same element. A fixed point.
 *
 * The script mirrors Readium's own walk — same `display: block` / `opacity: 0` skips, same
 * descent to the deepest match — and changes one thing: an element qualifies when its box
 * *begins* within the page (`left >= 0`) rather than merely reaching it (`right > 0`).
 * Containers that only overlap are still descended into, because the element that opens the
 * page is usually inside one.
 *
 * Returns `null`, and so leaves the caller on Readium's own answer, when nothing starts on this
 * page (one paragraph filling it end to end), or when the layout is one this reasoning does not
 * hold for: scrolled rather than paginated, or right-to-left, where the leading edge is the
 * other side.
 */
const val PAGE_START_ANCHOR_JS = """
(function () {
  try {
    if (typeof readium !== 'undefined' && readium.isFixedLayout) return null;
    var de = document.scrollingElement || document.documentElement;
    if (de.scrollHeight > de.clientHeight) return null;
    if (getComputedStyle(document.documentElement).direction === 'rtl') return null;
    var width = window.innerWidth;
    function skip(el) {
      var s = getComputedStyle(el);
      if (!s) return false;
      return s.getPropertyValue('display') !== 'block' || s.getPropertyValue('opacity') === '0';
    }
    function opens(r) { return r.left >= -1 && r.left < width; }
    function touches(r) { return r.right > 0 && r.left < width; }
    function deepest(el) {
      for (var i = 0; i < el.children.length; i++) {
        var c = el.children[i];
        if (!skip(c) && opens(c.getBoundingClientRect())) return deepest(c);
      }
      return el;
    }
    function search(el) {
      for (var i = 0; i < el.children.length; i++) {
        var c = el.children[i];
        if (skip(c)) continue;
        var r = c.getBoundingClientRect();
        if (opens(r)) return deepest(c);
        if (touches(r)) { var f = search(c); if (f) return f; }
      }
      return null;
    }
    var el = search(document.body);
    if (!el || !el.textContent) return null;
    var parts = [];
    for (var n = el; n && n.parentElement; n = n.parentElement) {
      var i = Array.prototype.indexOf.call(n.parentElement.children, n) + 1;
      parts.unshift(':nth-child(' + i + ')');
    }
    return { cssSelector: [':root'].concat(parts).join(' > '), text: el.textContent };
  } catch (e) {
    return null;
  }
})();
"""

/**
 * Turns what [PAGE_START_ANCHOR_JS] returned into the shape Readium restores from: a
 * `cssSelector` under `locations`, and the element's text as `text.highlight`.
 *
 * Both fields matter and they do different jobs. Readium resolves this pair by looking the
 * selector up to get a root and then matching the text inside it, so the selector is what makes
 * it exact and the text is what makes `R2EpubPageFragment.loadLocator` choose that path at all —
 * it only calls `scrollToLocator` when `text.highlight` is set.
 *
 * [href] and [mediaType] are the caller's, matching how Readium stamps its own
 * `firstVisibleElementLocator()`: the script has no idea which resource it is running in.
 * Returns `null` for the script's own `null`, for anything unparseable, and for a blank
 * selector or text, none of which Readium could resolve.
 */
fun parsePageStartAnchor(
    json: String?,
    href: Url,
    mediaType: MediaType,
): Locator? {
    val raw = json?.trim()?.takeUnless { it.isEmpty() || it == "null" } ?: return null
    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val selector = obj.optString("cssSelector").takeUnless { it.isBlank() } ?: return null
    val text = obj.optString("text").takeUnless { it.isBlank() } ?: return null
    return Locator(
        href = href,
        mediaType = mediaType,
        locations = Locator.Locations(otherLocations = mapOf("cssSelector" to selector)),
        text = Locator.Text(highlight = text),
    )
}
