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

    suspend fun capture(navigator: EpubNavigatorFragment): Locator? {
        val page = navigator.currentLocator.value
        val raw = navigator.evaluateJavascript("$script\nJSON.stringify(window.quireStructuralAnchors.capture(${JSONObject.quote(page.href.toString())}));")
        val snapshot = parseObject(raw) ?: return null
        if (navigator.currentLocator.value.href != page.href) return null
        val text = snapshot.optJSONObject("text")?.let(Locator.Text::fromJSON) ?: return null
        if (text.highlight.isNullOrBlank()) return null
        val anchor = snapshot.optJSONObject("anchor") ?: return null
        return page.copy(
            text = text,
            locations = page.locations.copy(otherLocations = page.locations.otherLocations + (KEY to anchor.toString())),
        )
    }

    suspend fun prepare(navigator: EpubNavigatorFragment, group: String): Boolean {
        val result = navigator.evaluateJavascript("$script\nJSON.stringify(window.quireStructuralAnchors.prepare(${JSONObject.quote(group)}));")
        return parseObject(result)?.optBoolean("ready") == true
    }

    /** Cheap page-turn probe; a replacement WebView must install its adapter before rendering. */
    suspend fun isPrepared(navigator: EpubNavigatorFragment, group: String): Boolean {
        val result = navigator.evaluateJavascript(
            "JSON.stringify((() => { const status = window.quireStructuralAnchors && " +
                "window.quireStructuralAnchors.diagnostics(${JSONObject.quote(group)});" +
                "return {ready:!!(status && status.ready && status.contentCurrent)}; })());"
        )
        return parseObject(result)?.optBoolean("ready") == true
    }

    suspend fun rejectedIds(navigator: EpubNavigatorFragment, group: String, decorations: List<Decoration>): Set<String>? {
        val input = JSONArray().apply {
            decorations.forEach { put(JSONObject().put("id", it.id).put("locator", it.locator.toJSON())) }
        }
        val result = navigator.evaluateJavascript(
            "JSON.stringify(window.quireStructuralAnchors.validate(${JSONObject.quote(group)}, $input));"
        )
        val ids = parseObject(result)?.optJSONArray("rejectedIds") ?: return null
        return (0 until ids.length()).map { ids.getString(it) }.toSet()
    }

    suspend fun clearRejections(navigator: EpubNavigatorFragment, group: String) {
        navigator.evaluateJavascript(
            "window.quireStructuralAnchors && window.quireStructuralAnchors.validate(${JSONObject.quote(group)}, []);"
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
