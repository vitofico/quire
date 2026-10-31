package io.theficos.ereader.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

/**
 * Exact DOM ranges for pinned Readium 3.0; unsupported or stale ranges never fall back to text.
 * The JavaScript adapter consumes the toolkit's exported decoration-group shape and must be
 * regression-tested when upgrading Readium. It changes rendering, not stock locator navigation.
 */
internal class StructuralSelectionAnchors(context: Context) {
    private val script = context.assets.open("quire-structural-anchors.js").bufferedReader().use { it.readText() }

    private suspend fun evaluate(
        navigator: EpubNavigatorFragment,
        expression: String,
        href: String = navigator.currentLocator.value.href.toString(),
        install: Boolean = true,
    ): String? {
        val setup = if (install) script else ""
        val operation = "(function () { $setup\n return $expression; })();"
        return evaluateReaderJavascript(navigator, operation, href)?.value
    }

    suspend fun capture(navigator: EpubNavigatorFragment): Locator? {
        val page = navigator.currentLocator.value
        val raw = evaluate(navigator, "JSON.stringify(window.quireStructuralAnchors.capture(${JSONObject.quote(page.href.toString())}))", page.href.toString())
        val snapshot = parseObject(raw) ?: return null
        if (navigator.currentLocator.value.href != page.href) return null
        val text = snapshot.optJSONObject("text")?.let(Locator.Text::fromJSON) ?: return null
        if (text.highlight.isNullOrBlank()) return null
        val anchor = snapshot.optJSONObject("anchor") ?: return null
        return page.copy(
            text = text,
            locations = page.locations.copy(otherLocations = page.locations.otherLocations.filterKeys { it != VIEWPORT_POSITION_KEY } + (KEY to anchor.toString())),
        )
    }

    suspend fun viewport(navigator: EpubNavigatorFragment): Locator? {
        val page = navigator.currentLocator.value
        val raw = evaluate(navigator, "JSON.stringify(window.quireStructuralAnchors.viewport(${JSONObject.quote(page.href.toString())}))", page.href.toString())
        if (navigator.currentLocator.value.href != page.href) return null
        val snapshot = parseObject(raw)
        if (snapshot == null) {
            val position = parseObject(evaluate(navigator, "JSON.stringify(window.quireStructuralAnchors.position())", page.href.toString(), install = false)) ?: return null
            if (navigator.currentLocator.value.href != page.href) return null
            return page.copy(text = Locator.Text(), locations = page.locations.copy(
                otherLocations = mapOf(VIEWPORT_POSITION_KEY to position.toString()),
            ))
        }
        val anchor = snapshot.optJSONObject("anchor") ?: return null
        val text = snapshot.optJSONObject("text")?.let(Locator.Text::fromJSON) ?: return null
        return page.copy(text = text, locations = page.locations.copy(
            otherLocations = page.locations.otherLocations + (KEY to anchor.toString()),
        ))
    }

    suspend fun navigate(navigator: EpubNavigatorFragment, locator: Locator, apply: Boolean, restoringOrigin: Boolean = false): NavigationProbe? {
        if (!restoringOrigin && !isHighlightDestination(locator)) return null
        locator.locations.otherLocations[VIEWPORT_POSITION_KEY]?.takeIf { restoringOrigin }?.let { position ->
            val saved = JSONObject(position.toString())
            val raw = evaluate(navigator, "JSON.stringify(window.quireStructuralAnchors.restorePosition($saved, $apply))", locator.href.toString())
            val result = parseObject(raw) ?: return null
            return NavigationProbe(result.optBoolean("available"), result.optBoolean("visible"), result.optBoolean("moved"))
        }
        val method = if (apply) "jump" else "visible"
        val raw = evaluate(navigator, "JSON.stringify(window.quireStructuralAnchors.$method(${locator.toJSON()}))", locator.href.toString())
        val result = parseObject(raw) ?: return null
        return NavigationProbe(result.optBoolean("available"), result.optBoolean("visible"), result.optBoolean("moved"))
    }

    suspend fun prepare(navigator: EpubNavigatorFragment, group: String): Boolean {
        val result = evaluate(navigator, "JSON.stringify(window.quireStructuralAnchors.prepare(${JSONObject.quote(group)}))")
        return parseObject(result)?.optBoolean("ready") == true
    }

    /** Cheap page-turn probe; a replacement WebView must install its adapter before rendering. */
    suspend fun isPrepared(navigator: EpubNavigatorFragment, group: String): Boolean {
        val result = evaluate(navigator,
            "JSON.stringify((() => { const status = window.quireStructuralAnchors && " +
                "window.quireStructuralAnchors.diagnostics(${JSONObject.quote(group)});" +
                "return {ready:!!(status && status.ready && status.contentCurrent)}; })())", install = false
        )
        return parseObject(result)?.optBoolean("ready") == true
    }

    suspend fun rejectedIds(navigator: EpubNavigatorFragment, group: String, decorations: List<Decoration>): Set<String>? {
        val input = JSONArray().apply {
            decorations.forEach { put(JSONObject().put("id", it.id).put("locator", it.locator.toJSON())) }
        }
        val result = evaluate(navigator,
            "JSON.stringify(window.quireStructuralAnchors.validate(${JSONObject.quote(group)}, $input))", install = false
        )
        val ids = parseObject(result)?.optJSONArray("rejectedIds") ?: return null
        return (0 until ids.length()).map { ids.getString(it) }.toSet()
    }

    suspend fun clearRejections(navigator: EpubNavigatorFragment, group: String) {
        evaluate(navigator,
            "window.quireStructuralAnchors && window.quireStructuralAnchors.validate(${JSONObject.quote(group)}, [])", install = false
        )
    }

    companion object {
        const val KEY = "quireDomRange"

        /** Before our adapter is installed, stock Readium sees an unresolvable element locator. */
        fun decoration(id: String, locator: Locator, tint: Int): Decoration = Decoration(
            id = id,
            locator = locator.copy(
                text = Locator.Text(),
                locations = locator.locations.copy(
                    fragments = emptyList(),
                    otherLocations = mapOf("cssSelector" to ":not(*)") +
                        locator.locations.otherLocations.filterKeys { it == KEY },
                ),
            ),
            style = Decoration.Style.Highlight(tint),
        )

        internal fun parseObject(raw: String?): JSONObject? = runCatching {
            val value = raw?.trim()?.takeUnless { it == "null" || it.isBlank() } ?: return@runCatching null
            val decoded = if (value.startsWith('"')) org.json.JSONTokener(value).nextValue() as? String ?: return@runCatching null else value
            JSONObject(decoded)
        }.getOrNull()
    }
}

internal data class NavigationProbe(val available: Boolean, val visible: Boolean, val moved: Boolean)
