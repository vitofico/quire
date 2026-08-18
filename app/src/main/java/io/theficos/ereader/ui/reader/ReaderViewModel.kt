package io.theficos.ereader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.Progress
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.reader.EpubAsset
import io.theficos.ereader.reader.ProgressTracker
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.PAGE_START_ANCHOR_JS
import io.theficos.ereader.reader.ReadiumFactory
import io.theficos.ereader.reader.locatorAtPercent
import io.theficos.ereader.reader.parsePageStartAnchor
import io.theficos.ereader.reader.resizeAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import java.io.File

class ReaderViewModel(
    private val documentId: Long,
    private val docs: DocumentRepository,
    private val progress: ProgressRepository,
    private val readium: ReadiumFactory,
    private val preferencesStore: ReaderPreferencesStore,
    // How the reader asks Readium whereabouts in the DOM the current page starts. A seam so
    // the re-anchor can be tested without a live WebView; see ReaderViewportResizeTest.
    private val readDomAnchor: suspend (EpubNavigatorFragment?) -> Locator? =
        ::readPageStartAnchor,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _locatorUpdates = MutableSharedFlow<Locator>(extraBufferCapacity = 64)
    val locatorUpdates: SharedFlow<Locator> = _locatorUpdates.asSharedFlow()

    private val _currentLocator = MutableStateFlow<Locator?>(null)
    val currentLocator: StateFlow<Locator?> = _currentLocator.asStateFlow()

    private val _positions = MutableStateFlow<List<Locator>?>(null)
    val positions: StateFlow<List<Locator>?> = _positions.asStateFlow()

    private var positionsJob: Job? = null

    val preferences: StateFlow<ReaderPreferences> = preferencesStore.flow

    private val _chromeVisible = MutableStateFlow(true)
    val chromeVisible: StateFlow<Boolean> = _chromeVisible.asStateFlow()

    fun setChromeVisible(visible: Boolean) {
        _chromeVisible.value = visible
    }

    fun toggleChrome() {
        _chromeVisible.value = !_chromeVisible.value
    }

    fun updatePreferences(next: ReaderPreferences) {
        preferencesStore.update { next }
    }

    private val tracker = ProgressTracker(
        save = { progress.save(it) },
        scope = viewModelScope,
    )

    private var navigator: EpubNavigatorFragment? = null

    fun bindNavigator(nav: EpubNavigatorFragment?) {
        navigator = nav
    }

    fun pageForward() {
        viewModelScope.launch { navigator?.goForward() }
    }

    fun pageBackward() {
        viewModelScope.launch { navigator?.goBackward() }
    }

    fun goTo(locator: Locator) {
        clearPendingResize()
        invalidateDomAnchor()
        viewModelScope.launch { navigator?.go(locator, false) }
    }

    fun load() {
        viewModelScope.launch {
            val doc = docs.findById(documentId) ?: run {
                _state.value = ReaderUiState.Error("Document not found")
                return@launch
            }
            val publication = runCatching {
                readium.open(EpubAsset(doc.id, File(doc.localPath), doc.title))
            }.getOrElse {
                _state.value = ReaderUiState.Error(it.message ?: "Failed to open book")
                return@launch
            }
            val savedProgress = progress.get(doc.id)
            val initialLocator = savedProgress?.locator?.let { ProgressTracker.parseOrNull(it) }
            val lastSpineHref = publication.readingOrder.lastOrNull()?.url()
            _currentLocator.value = initialLocator
            _state.value = ReaderUiState.Open(doc, publication, initialLocator, savedProgress)
            tracker.attach(
                documentId = doc.id,
                locatorUpdates = locatorUpdates,
                lastSpineHref = lastSpineHref,
                initialFinishedAt = savedProgress?.finishedAt,
            )
            positionsJob?.cancel()
            _positions.value = null
            positionsJob = viewModelScope.launch {
                val computed = runCatching {
                    withContext(Dispatchers.Default) { publication.positions() }
                }.getOrDefault(emptyList<Locator>())
                _positions.value = computed
            }
        }
    }

    fun publishLocator(locator: Locator) {
        if (suppressLocatorPublishing) return
        _currentLocator.value = locator
        _locatorUpdates.tryEmit(locator)
        refreshDomAnchor()
    }

    /**
     * Keeps [domAnchor] tracking the page on screen.
     *
     * Asking Readium costs a round trip into the WebView's JavaScript, which is far too slow to
     * do at the moment a rotation starts — the answer would come back describing the page after
     * re-pagination, which is the one thing it must not be. So it is kept warm instead, refreshed
     * whenever Readium reports a settled position, and read synchronously when the resize arms.
     *
     * Both guards here exist to keep a rotation from rewriting the anchor, which is the failure
     * that made rotation drift compound. Which element anchors a page depends on the page's
     * shape: a tall portrait column starts several paragraphs where a short landscape one starts
     * a single paragraph, so reading the anchor in the orientation the reader is only passing
     * through replaces it with an earlier one, and the return leg dutifully honours that. Turning
     * a page moves the reader; rotating the device does not.
     */
    private fun refreshDomAnchor() {
        if (nowMs() < anchorPinnedUntil) return
        viewModelScope.launch {
            val nav = navigator
            val dom = runCatching { readDomAnchor(nav) }.getOrNull()
            // A resize may have armed while that round trip was in flight, in which case the
            // answer describes the re-paginated page. Drop it and keep the pre-resize anchor.
            if (!suppressLocatorPublishing && nowMs() >= anchorPinnedUntil) domAnchor = dom
        }
    }

    private var pendingRotationAnchor: Locator? = null
    private var suppressLocatorPublishing: Boolean = false
    private var viewportSize: Pair<Int, Int>? = null
    private var domAnchor: Locator? = null
    private var anchorPinnedUntil: Long = 0L

    /**
     * Reports the reader viewport's measured size, on every layout pass.
     *
     * Any change in that size re-paginates Readium's WebView, after which Readium reports
     * whatever text happened to land on screen. Nothing marks that locator as junk, so left
     * alone it is published to the HUD and written over the reader's saved position — the
     * reader comes back to the wrong page and stays there.
     *
     * Everything else here arms the re-anchor by naming a cause: [beginViewportResize] is
     * called from `MainActivity.onConfigurationChanged` for rotation, and from the
     * full-screen-reading toggle. Issue #95 was a resize nobody had named, so this arms on
     * the effect instead — the measured size — and covers causes we haven't thought of.
     * (It only sees resizes of the Compose node; one that happens further down, inside
     * Readium's own view tree, is invisible here. See `shouldApplyInsetsPadding` in
     * ReaderScreen for the one that bit us.)
     *
     * The first report just establishes the baseline; the WebView is created at that size, so
     * there is nothing to re-anchor. Zero sizes (pre-layout, or a detached view) are ignored
     * so they never read as a resize in either direction.
     */
    fun onViewportChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val previous = viewportSize
        viewportSize = width to height
        if (previous == null || previous == viewportSize) return
        beginViewportResize()
    }

    /**
     * Forgets where the page on screen begins, because the reader is being sent elsewhere.
     *
     * [domAnchor] describes the page being left. Until Readium reports the one being arrived at,
     * a resize landing in between would otherwise fold that stale element into the new locator
     * and send the reader straight back to the page they just jumped away from.
     */
    private fun invalidateDomAnchor() {
        domAnchor = null
    }

    // Called from MainActivity.onBeforeReaderConfigChange — runs BEFORE the Activity dispatches
    // the configuration change down to fragments. Snapshots where the reader is into
    // pendingRotationAnchor and gates publishLocator so Readium's post-resize drifted emissions
    // cannot overwrite it.
    fun beginViewportResize() {
        // A single rotation arms this several times over — MainActivity as the configuration
        // change is dispatched, then onViewportChanged as the measured size follows. Only the
        // first of those still sees the page the reader is leaving, so it wins.
        if (pendingRotationAnchor != null) return
        val live = _currentLocator.value ?: return
        pendingRotationAnchor = resizeAnchor(live, domAnchor)
        suppressLocatorPublishing = true
    }

    /**
     * Puts the reader back on the anchored page, and stays armed. Called from the Readium
     * pagination listener on every re-pagination while a resize is in flight; a no-op when no
     * resize is pending, so it is safe on every page turn.
     *
     * Re-pagination arrives in steps, and anchoring on the first one is not enough: wired that
     * way, and with everything else here unchanged, rotation still landed on the wrong page and
     * did so inconsistently, the same start position coming back two different ways. Readium
     * scrolls to an anchor by snapping the element's offset to a page boundary, using a page
     * width it caches in JavaScript and only recomputes when it is told the viewport moved, so
     * an early re-anchor is measuring against a grid that is still the old one. Re-anchoring on
     * every step and once more when the size stops changing ([completeViewportResize]) gives the
     * settled layout the last word.
     *
     * Repeating this is only safe because the anchor is a DOM element: it lands in the same place
     * however many times it is used. The progression fraction this used to carry was consumed a
     * little by every application, which is why it could only ever be applied once.
     */
    fun reanchorViewport() {
        val anchor = pendingRotationAnchor ?: return
        viewModelScope.launch { navigator?.go(anchor, false) }
    }

    // Called when the viewport has stopped changing (and from the immersive transition's own
    // settle timer). No-op if no resize is pending. Re-anchors one last time, now that the
    // layout is final, re-seeds the current-locator flow and re-enables publishing.
    fun completeViewportResize() {
        val anchor = pendingRotationAnchor ?: return
        pendingRotationAnchor = null
        // Readium reports the restored position of its own accord a moment after the jump —
        // measured at around half a second. That report is the resize finishing, not the reader
        // moving, so it must not be allowed to re-read the anchor from the page it just landed
        // on. Hold the anchor over that window; the next page the reader actually turns to
        // refreshes it normally.
        anchorPinnedUntil = nowMs() + ANCHOR_PIN_MS
        viewModelScope.launch {
            navigator?.go(anchor, false)
            _currentLocator.value = anchor
            _locatorUpdates.tryEmit(anchor)
            suppressLocatorPublishing = false
        }
    }

    // Safety valve for the immersive decor-fits transitions: if a resize was armed
    // (beginViewportResize) but no re-pagination completed it — e.g. the reader is torn
    // down mid-transition — clear the anchor so publishing is never left suppressed.
    fun clearPendingResize() {
        pendingRotationAnchor = null
        suppressLocatorPublishing = false
    }

    fun previewLocator(percent: Double): Locator? {
        val list = _positions.value ?: return null
        return locatorAtPercent(list, percent)
    }

    fun seek(percent: Double) {
        val target = previewLocator(percent) ?: return
        val nav = navigator ?: return
        // An explicit jump supersedes any armed re-anchor: the anchor predates the seek, so
        // honouring it afterwards would yank the reader back out of the page they just chose.
        clearPendingResize()
        invalidateDomAnchor()
        // Surface the target on the HUD synchronously, before the suspending nav.go()
        // call dispatches. This avoids a one-frame window where the slider thumb
        // would snap back to the pre-seek liveLocator after the UI clears its drag
        // preview but before Readium emits the post-seek locator. Routing through
        // _locatorUpdates here also persists the jump via ProgressTracker even if
        // Readium's own emission is delayed. The later emission re-flushes the same
        // row — idempotent. On the rare failure of go(), Readium's next emission
        // corrects any drift.
        _currentLocator.value = target
        _locatorUpdates.tryEmit(target)
        viewModelScope.launch {
            nav.go(target, animated = false)
        }
    }

    override fun onCleared() {
        positionsJob?.cancel()
        tracker.detach()
        super.onCleared()
    }
}

/**
 * How long the reading anchor is held after a viewport resize completes, covering Readium's own
 * delayed report of where it landed. Long enough for that report (about half a second in
 * practice), short enough that a page the reader turns to just after a rotation still registers.
 */
private const val ANCHOR_PIN_MS = 1_500L

sealed interface ReaderUiState {
    data object Loading : ReaderUiState
    data class Error(val message: String) : ReaderUiState
    data class Open(
        val document: Document,
        val publication: Publication,
        val initialLocator: Locator?,
        val savedProgress: Progress?,
    ) : ReaderUiState
}

/**
 * Asks the navigator where the page on screen begins in the DOM.
 *
 * Runs [PAGE_START_ANCHOR_JS] in the current resource's web view and stamps the answer with that
 * resource's href, so a stale anchor can be rejected later if the reader has moved on to another
 * chapter. Falls back to Readium's own `firstVisibleElementLocator()` whenever the script
 * declines to answer: a page with nothing starting on it, or a layout the script bows out of.
 * That fallback anchors slightly earlier than the reader actually is — the very thing the script
 * exists to improve on — but it is still an exact DOM anchor, so it costs at most a one-off
 * shift rather than the compounding walk the progression fraction caused.
 */
private suspend fun readPageStartAnchor(navigator: EpubNavigatorFragment?): Locator? {
    val nav = navigator ?: return null
    val current = nav.currentLocator.value
    val fromScript = runCatching {
        parsePageStartAnchor(
            json = nav.evaluateJavascript(PAGE_START_ANCHOR_JS),
            href = current.href,
            mediaType = current.mediaType,
        )
    }.getOrNull()
    return fromScript ?: runCatching { nav.firstVisibleElementLocator() }.getOrNull()
}
