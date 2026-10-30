package io.theficos.ereader.reader

import android.content.Context
import io.theficos.ereader.core.model.Highlight
import io.theficos.ereader.core.model.HighlightColour
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

/** The same semantic palette is used by the picker and the page decoration. */
fun HighlightColour.highlightTint(): Int = when (this) {
    HighlightColour.YELLOW -> 0xFFFFEB3B.toInt()
    HighlightColour.GREEN -> 0xFFA5D6A7.toInt()
    HighlightColour.PINK -> 0xFFF48FB1.toInt()
}

/**
 * Binds persisted annotations to one navigator. Call [pageReady] from that navigator's
 * onPageChanged callback, [submit] for repository updates, and [close] before disposing it.
 * All entry points and callbacks are confined to the reader's main-thread scope.
 */
class ReaderHighlightSession(
    context: Context,
    private val navigator: EpubNavigatorFragment,
    scope: CoroutineScope,
    private val onActivated: (String) -> Boolean,
    private val onUnavailable: () -> Unit = {},
) {
    private val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val ready = ReadyDecorationSession(navigator, 0, sessionScope)
    private val anchors = StructuralSelectionAnchors(context)
    private var closed = false
    private var listenerAttached = false
    private var records: List<Highlight> = emptyList()
    private var parsed: ParsedHighlights = ParsedHighlights(emptyList(), emptySet())
    private var lastRender: RenderRequest? = null
    private var reportedFailures: Set<String> = emptySet()

    private val listener = object : DecorableNavigator.Listener {
        override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
            if (closed || records.none { it.id == event.decoration.id }) return false
            return onActivated(event.decoration.id)
        }
    }

    fun pageReady(locator: Locator) {
        if (closed || navigator.currentLocator.value.href != locator.href) return
        // The session is created before commitNow so early callbacks can refer to it.
        // Readium's listener methods access a fragment ViewModel and require attachment.
        if (!listenerAttached) {
            navigator.addDecorationListener(GROUP, listener)
            listenerAttached = true
        }
        ready.pageReady(locator.href.toString())
        render()
    }

    fun submit(highlights: List<Highlight>) {
        if (closed) return
        val active = highlights.filter { it.deletedAt == null }
        if (records == active) return
        records = active.toList()
        parsed = parseHighlights(records)
        if (ready.isReady) render()
    }

    /** The snapshot includes native quote text and exact endpoints from one WebView turn. */
    suspend fun captureSelection(): Locator? {
        if (closed) return null
        return try {
            anchors.capture(navigator).takeUnless { closed }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun render() {
        if (closed) return
        sessionScope.launch {
            var attemptHref: String? = null
            try {
                ready.submit({ !closed }) { nav, href ->
                    attemptHref = href
                    val requested = parsed.decorations.filter { it.locator.href.toString() == href }
                    val request = RenderRequest(href, requested, parsed.malformedIds)
                    if (lastRender == request &&
                        (requested.isEmpty() || anchors.isPrepared(nav, GROUP))) return@submit
                    if (!isCurrent(href)) return@submit

                    // Empty lists need no adapter, and must clear the previous resource's map.
                    if (requested.isEmpty()) {
                        anchors.clearRejections(nav, GROUP)
                        if (!isCurrent(href)) return@submit
                        nav.applyDecorations(emptyList(), GROUP)
                        if (!isCurrent(href)) return@submit
                        lastRender = request
                        report(parsed.malformedIds)
                        return@submit
                    }

                    val prepared = anchors.prepare(nav, GROUP)
                    if (!isCurrent(href)) return@submit
                    if (!prepared) {
                        nav.applyDecorations(emptyList(), GROUP)
                        lastRender = null
                        report(setOf(UNAVAILABLE_ADAPTER))
                        return@submit
                    }
                    val rejected = anchors.rejectedIds(nav, GROUP, requested)
                    if (!isCurrent(href)) return@submit
                    if (rejected == null) {
                        nav.applyDecorations(emptyList(), GROUP)
                        lastRender = null
                        report(setOf(UNAVAILABLE_ADAPTER))
                        return@submit
                    }
                    // Store only the prepared current chapter in Readium's native map.
                    // Preloaded chapters must not replay additions before their adapter exists.
                    nav.applyDecorations(requested.filterNot { it.id in rejected }, GROUP)
                    if (!isCurrent(href)) return@submit
                    lastRender = request
                    report(parsed.malformedIds + rejected)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (attemptHref?.let(::isCurrent) == true) report(setOf(UNAVAILABLE_ADAPTER))
            }
        }
    }

    private fun isCurrent(href: String): Boolean =
        !closed && ready.readyHref == href && navigator.currentLocator.value.href.toString() == href

    private fun report(ids: Set<String>) {
        if (closed || ids == reportedFailures) return
        reportedFailures = ids
        if (ids.isNotEmpty()) onUnavailable()
    }

    fun close() {
        if (closed) return
        closed = true
        ready.close()
        sessionScope.cancel()
        if (listenerAttached && navigator.isAdded) navigator.removeDecorationListener(listener)
    }

    private data class RenderRequest(
        val href: String,
        val decorations: List<Decoration>,
        val malformedIds: Set<String>,
    )

    internal data class ParsedHighlights(val decorations: List<Decoration>, val malformedIds: Set<String>)

    companion object {
        private const val GROUP = "quire-highlights"
        private const val UNAVAILABLE_ADAPTER = "adapter"

        internal fun parseHighlights(highlights: List<Highlight>): ParsedHighlights {
            val malformed = mutableSetOf<String>()
            val decorations = highlights.mapNotNull { highlight ->
                val locator = runCatching { Locator.fromJSON(JSONObject(highlight.locator)) }.getOrNull()
                if (locator == null) {
                    malformed += highlight.id
                    null
                } else StructuralSelectionAnchors.decoration(highlight.id, locator, highlight.colour.highlightTint())
            }
            return ParsedHighlights(decorations, malformed)
        }
    }
}
