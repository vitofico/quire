package io.theficos.ereader.ui.reader

import io.theficos.ereader.reader.evaluateReaderJavascript
import io.theficos.ereader.reader.ReaderPreferences
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.Length
import org.readium.r2.navigator.epub.css.UserProperties
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Either

/** Readium go() acknowledges a queued request; it does not await the actual DOM scroll. */
internal suspend fun verifyReaderAnchor(
    navigator: EpubNavigatorFragment?, anchor: Locator, preferences: ReaderPreferences,
): Boolean {
    val nav = navigator ?: return false
    if (nav.currentLocator.value.href.toString().substringBefore('#') != anchor.href.toString().substringBefore('#')) return false
    return strictJavascriptTrue(evaluateReaderJavascript(nav, anchorVerificationScript(anchor, preferences), anchor.href.toString())?.value)
}

internal fun strictJavascriptTrue(value: String?): Boolean = value?.trim() == "true"

/** Use Readium's own CSS serializer instead of guessing units, quoting or boolean flags. */
@OptIn(ExperimentalReadiumApi::class)
internal fun anchorCssProperties(preferences: ReaderPreferences): Map<String, String?> = UserProperties(
    fontSize = Length.Percent(preferences.fontScale),
    fontFamily = preferences.fontFamily.readium?.let { listOf(it.name) },
    fontOverride = preferences.fontFamily.readium != null,
    pageMargins = preferences.pageMargins,
    lineHeight = Either(preferences.lineSpacing),
    advancedSettings = !preferences.usePublisherStyles,
    paraIndent = preferences.paragraphIndent?.let { Length.Rem(it) },
    paraSpacing = preferences.paragraphSpacing?.let { Length.Rem(it) },
).toCssProperties().filterKeys { it in setOf(
    "--USER__fontSize", "--USER__fontFamily", "--USER__fontOverride", "--USER__pageMargins",
    "--USER__lineHeight", "--USER__advancedSettings", "--USER__paraIndent", "--USER__paraSpacing",
) }

internal fun anchorVerificationScript(anchor: Locator, preferences: ReaderPreferences): String {
    val selector = anchor.locations.otherLocations["cssSelector"] as? String
    val id = anchor.locations.fragments.firstOrNull()?.removePrefix("#")
    // Only location data and requested styles enter the bridge, never a reader's note.
    val input = JSONObject().apply {
        put("selector", selector ?: JSONObject.NULL)
        put("id", id ?: JSONObject.NULL)
        put("resource", anchor.href.toString().substringBefore('#').substringBefore('?').trimStart('/'))
        put("progression", anchor.locations.progression ?: 0.0)
        put("allowProgression", selector == null && id == null && anchor.text.highlight.isNullOrBlank())
        put("styles", JSONObject(anchorCssProperties(preferences).mapValues { it.value.orEmpty() }))
    }
    return """
        (function () {
          try {
            var input = $input;
            var path = decodeURI(window.location.pathname);
            if (!path.endsWith('/' + decodeURI(input.resource))) return false;
            var root = document.documentElement;
            for (var name in input.styles) {
              if (root.style.getPropertyValue(name).trim() !== input.styles[name]) return false;
            }
            if (document.fonts && document.fonts.status !== 'loaded') return false;
            var element = input.selector ? document.querySelector(input.selector) :
                (input.id ? document.getElementById(input.id) : null);
            if (!element) {
              // A plain/textless locator has no precise DOM assertion to preserve. Retain
              // Readium's coarse page/progression contract, but observe the actual DOM scroll.
              // A missing or invalid real text selector never receives this fallback.
              if (!input.allowProgression) return false;
              var scroller = document.scrollingElement || root;
              var vertical = scroller.scrollHeight > window.innerHeight + 1;
              if (vertical) {
                var targetY = Math.min(input.progression * scroller.scrollHeight,
                    Math.max(0, scroller.scrollHeight - window.innerHeight));
                return Math.abs(scroller.scrollTop - targetY) <= 1;
              }
              var width = window.innerWidth;
              if (width <= 0) return false;
              var pages = Math.max(1, Math.ceil(scroller.scrollWidth / width));
              var rtl = getComputedStyle(root).direction === 'rtl';
              var page = Math.round((rtl ? 1 - input.progression : input.progression) * pages);
              if (rtl && page > 0) page--;
              page = Math.max(0, Math.min(pages - 1, page));
              return Math.abs(Math.abs(scroller.scrollLeft) - page * width) <= 1;
            }
            // Reading a rect forces layout with the applied CSS. Check individual fragments,
            // rather than their union, so offscreen columns cannot falsely span the viewport.
            var rects = element.getClientRects();
            for (var i = 0; i < rects.length; i++) {
              var r = rects[i];
              if (r.width > 0 && r.height > 0 && r.right > 0 && r.left < window.innerWidth &&
                  r.bottom > 0 && r.top < window.innerHeight) return true;
            }
            return false;
          } catch (error) { return false; }
        })();
    """.trimIndent()
}
