package io.theficos.ereader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.Progress
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.reader.EpubAsset
import io.theficos.ereader.reader.PAGE_START_ANCHOR_JS
import io.theficos.ereader.reader.ProgressTracker
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import io.theficos.ereader.reader.locatorAtPercent
import io.theficos.ereader.reader.parsePageStartAnchor
import io.theficos.ereader.reader.resizeAnchor
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
    private val bookmarks: io.theficos.ereader.data.local.db.MemoryBookmarkDao? = null,
    private val accountKey: () -> String = { "device-local" },
    private val accountChanges: kotlinx.coroutines.flow.Flow<String>? = null,
    private val restoreNavigator: suspend (EpubNavigatorFragment?, Locator) -> Boolean = { nav, anchor ->
        nav?.go(anchor, false) ?: false
    },
    private val verifyAnchor: suspend (EpubNavigatorFragment?, Locator, ReaderPreferences) -> Boolean = ::verifyReaderAnchor,
    private val openPublication: suspend (Document) -> Publication = { doc ->
        readium.open(EpubAsset(doc.id, File(doc.localPath), doc.title))
    },
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

    /**
     * True until the reader first shows or hides the controls. Only the reveal on opening a book
     * hides itself on a timer: controls the reader asked for stay until their next tap, so a slow
     * reach for the slider cannot land on the page after the bars have gone and turn it instead.
     */
    private val _chromeAutoHides = MutableStateFlow(true)
    val chromeAutoHides: StateFlow<Boolean> = _chromeAutoHides.asStateFlow()

    fun toggleChrome() {
        _chromeAutoHides.value = false
        _chromeVisible.value = !_chromeVisible.value
    }

    fun updatePreferences(next: ReaderPreferences) {
        val previous = preferences.value
        val layoutChanged = previous.copy(theme = next.theme, tapNavigationEnabled = next.tapNavigationEnabled) != next
        if (!layoutChanged) {
            preferencesStore.update { next }
            return
        }
        pendingPreferenceTarget = next
        if (preferenceCaptureJob?.isActive == true) return
        if (pendingRotationAnchor != null) {
            preferenceReflowPending = true
            pendingPreferenceTarget = null
            preferencesStore.update { next }
            return
        }
        val live = _currentLocator.value
        beginViewportResize()
        preferenceReflowPending = pendingRotationAnchor != null
        if (live == null || pendingRotationAnchor == null) {
            pendingPreferenceTarget = null
            preferencesStore.update { next }
            return
        }
        val generation = anchorGeneration
        // A deliberate preference change can await the current DOM before changing it. Keep
        // the original point across a slider gesture, coalescing events while JS is in flight.
        preferenceCaptureJob = viewModelScope.launch {
            val fresh = try {
                withTimeoutOrNull(ANCHOR_OBSERVATION_TIMEOUT_MS) { readDomAnchor(navigator) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) { null }
            if (generation != anchorGeneration || pendingRotationAnchor == null) return@launch
            if (fresh == null) {
                pendingPreferenceTarget = null
                _anchorRecovery.value = true
                return@launch
            }
            pendingRotationAnchor = resizeAnchor(live, fresh.takeIf { !it.text.highlight.isNullOrBlank() } ?: domAnchor)
            if (fresh?.href == live.href && !fresh.text.highlight.isNullOrBlank()) domAnchor = fresh
            val target = pendingPreferenceTarget ?: next
            pendingPreferenceTarget = null
            preferencesStore.update { target }
        }
    }

    /** Preference re-pagination changes the page grid without changing the Compose viewport. */
    fun onPreferencesApplied() {
        if (!preferenceReflowPending) return
        schedulePreferenceReanchor()
    }

    private fun schedulePreferenceReanchor() {
        preferenceReflowJob?.cancel()
        preferenceReflowJob = viewModelScope.launch {
            delay(600)
            completeViewportResize()
        }
    }

    fun recreationLocator(): Locator? = _currentLocator.value?.let { resizeAnchor(it, domAnchor) }


    private val _memoryScope = MutableStateFlow(accountKey())
    val memoryScope = _memoryScope.asStateFlow()
    private val _memory = MutableStateFlow<io.theficos.ereader.data.local.db.MemoryBookmark?>(null)
    val memory = _memory.asStateFlow()
    private val _memoryReady = MutableStateFlow(false)
    val memoryReady = _memoryReady.asStateFlow()
    private val _memoryBusy = MutableStateFlow(false)
    val memoryBusy = _memoryBusy.asStateFlow()
    private val _memoryError = MutableStateFlow(false)
    val memoryError = _memoryError.asStateFlow()
    private var memoryJob: Job? = null
    private var loadedAccount: String? = null

    init {
        viewModelScope.launch {
            accountChanges?.collect { owner ->
                _memoryScope.value = owner
                if (loadedAccount != null && loadedAccount != owner) {
                    loadedAccount = null
                    _memoryReady.value = false
                    memoryJob?.cancel()
                    _memory.value = null
                    clearPendingResize()
                    invalidateDomAnchor()
                }
            }
        }
    }

    /** Existing notes keep their original anchor; replacement at the live point is deliberate. */
    fun saveMemory(note: String, atCurrentPoint: Boolean = false) {
        val dao = bookmarks ?: return
        if (!_memoryReady.value || _memoryBusy.value) return
        val owner = loadedAccount ?: return
        if (owner != accountKey()) return
        _memoryBusy.value = true
        viewModelScope.launch {
            _memoryError.value = false
            runCatching {
                check(docs.findById(documentId) != null)
                val existing = _memory.value
                val saved = existing?.locator?.let(ProgressTracker::parseOrNull)
                // Corrupt notes remain readable/removable. Never silently replace their anchor.
                check(existing == null || saved != null || atCurrentPoint)
                check(!suppressLocatorPublishing)
                val live = _currentLocator.value
                val generation = anchorGeneration
                val locator = if (existing != null && !atCurrentPoint) saved else {
                    val dom = if (!suppressLocatorPublishing) runCatching { readDomAnchor(navigator) }.getOrNull() else null
                    check(!suppressLocatorPublishing && live == _currentLocator.value && generation == anchorGeneration)
                    live?.let { resizeAnchor(it, dom) }
                }
                check(locator != null)
                val open = _state.value as? ReaderUiState.Open
                check(open?.publication?.readingOrder?.any {
                    it.href.toString().substringBefore('#') == locator.href.toString().substringBefore('#')
                } == true)
                val passage = if (existing != null && !atCurrentPoint) existing.passage else
                    listOfNotNull(locator.text.before, locator.text.highlight, locator.text.after)
                        .joinToString(" ").trim().take(1500)
                check(owner == accountKey())
                dao.save(io.theficos.ereader.data.local.db.MemoryBookmark(documentId, owner,
                    ProgressTracker.serialize(locator), note.trim().take(2000), passage, nowMs()))
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                _memoryError.value = true
            }
            _memoryBusy.value = false
        }
    }

    fun removeMemory() {
        if (_memoryBusy.value) return
        val owner = loadedAccount ?: return
        if (owner != accountKey()) return
        viewModelScope.launch {
            _memoryError.value = false
            runCatching { bookmarks?.remove(documentId, owner) }
                .onFailure { _memoryError.value = true }
        }
    }

    fun memoryLocator(): Locator? {
        if (loadedAccount != accountKey()) return null
        val locator = _memory.value?.locator?.let(ProgressTracker::parseOrNull) ?: return null
        val publication = (_state.value as? ReaderUiState.Open)?.publication ?: return null
        return locator.takeIf { publication.readingOrder.any { link ->
            link.href.toString().substringBefore('#') == locator.href.toString().substringBefore('#')
        } }
    }

    fun returnToMemory() { memoryLocator()?.let(::goTo) }

    private val tracker = ProgressTracker(
        save = { progress.save(it) },
        scope = viewModelScope,
    )

    private var navigator: EpubNavigatorFragment? = null

    fun bindNavigator(nav: EpubNavigatorFragment?) {
        navigator = nav
        if (nav == null) {
            anchorRestoreJob?.cancel()
            paginationRestoreJob?.cancel()
        }
        else if (finalRestoreRequested && !_anchorRecovery.value) completeViewportResize()
        else if (preferenceReflowPending) schedulePreferenceReanchor()
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

    private var loadJob: Job? = null

    fun load() {
        if (_state.value is ReaderUiState.Open || loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val doc = docs.findById(documentId) ?: run {
                _state.value = ReaderUiState.Error(uiText(R.string.common_document_not_found))
                return@launch
            }
            val publication = runCatching {
                openPublication(doc)
            }.getOrElse {
                _state.value = ReaderUiState.Error(it.message?.let(UiText::Dynamic) ?: uiText(R.string.common_failed_to_open_book))
                return@launch
            }
            val owner = accountKey()
            loadedAccount = owner
            _memoryScope.value = owner
            memoryJob?.cancel()
            _memory.value = null
            _memoryReady.value = false
            memoryJob = viewModelScope.launch {
                bookmarks?.observe(documentId, owner)?.collect {
                    if (loadedAccount == owner && accountKey() == owner) {
                        _memory.value = it
                        _memoryReady.value = true
                    }
                }
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

    private val _anchorRecovery = MutableStateFlow(false)
    val anchorRecovery = _anchorRecovery.asStateFlow()
    private var finalRestoreRequested = false
    private var anchorRestoreJob: Job? = null
    private var paginationRestoreJob: Job? = null
    private var preferenceCaptureJob: Job? = null
    private var pendingPreferenceTarget: ReaderPreferences? = null
    private var preferenceReflowPending = false
    private var preferenceReflowJob: Job? = null
    private var anchorGeneration: Long = 0
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
        anchorGeneration++
        _anchorRecovery.value = false
        finalRestoreRequested = false
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
        if (_anchorRecovery.value) return
        if (finalRestoreRequested) {
            completeViewportResize()
            return
        }
        paginationRestoreJob?.cancel()
        paginationRestoreJob = viewModelScope.launch { tryRestore(anchor) }
        if (preferenceReflowPending) schedulePreferenceReanchor()
    }

    private suspend fun tryRestore(anchor: Locator): Boolean = try {
        restoreNavigator(navigator, anchor)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    // Both viewport and preference reflows share bounded final restoration. Keeping the anchor
    // after a failed go is safe only if readiness callbacks/retries can eventually release it.
    fun completeViewportResize() {
        val anchor = pendingRotationAnchor ?: return
        if (_anchorRecovery.value || anchorRestoreJob?.isActive == true) return
        val generation = anchorGeneration
        finalRestoreRequested = true
        paginationRestoreJob?.cancel()
        anchorRestoreJob = viewModelScope.launch {
            repeat(3) { attempt ->
                val requestedPreferences = preferences.value
                val accepted = tryRestore(anchor)
                val restored = accepted && try {
                    withTimeoutOrNull(ANCHOR_OBSERVATION_TIMEOUT_MS) {
                        verifyAnchor(navigator, anchor, requestedPreferences)
                    } == true
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) { false }
                if (generation != anchorGeneration || pendingRotationAnchor != anchor) return@launch
                if (restored && preferences.value == requestedPreferences) {
                    pendingRotationAnchor = null
                    finalRestoreRequested = false
                    preferenceReflowPending = false
                    preferenceReflowJob?.cancel()
                    _anchorRecovery.value = false
                    anchorPinnedUntil = nowMs() + ANCHOR_PIN_MS
                    _currentLocator.value = anchor
                    _locatorUpdates.tryEmit(anchor)
                    suppressLocatorPublishing = false
                    return@launch
                }
                if (attempt < 2) delay(600)
            }
            // Never silently persist the drift. Offer deliberate retry/close recovery instead
            // of leaving a permanent, invisible progress lockout or retrying without a bound.
            if (generation == anchorGeneration && pendingRotationAnchor == anchor) {
                _anchorRecovery.value = true
            }
        }
    }

    fun retryViewportRestore() {
        anchorRestoreJob?.cancel()
        anchorRestoreJob = null
        _anchorRecovery.value = false
        completeViewportResize()
    }

    // Safety valve for the immersive decor-fits transitions: if a resize was armed
    // (beginViewportResize) but no re-pagination completed it — e.g. the reader is torn
    // down mid-transition — clear the anchor so publishing is never left suppressed.
    fun clearPendingResize() {
        preferenceCaptureJob?.cancel()
        pendingPreferenceTarget = null
        paginationRestoreJob?.cancel()
        anchorRestoreJob?.cancel()
        anchorRestoreJob = null
        finalRestoreRequested = false
        _anchorRecovery.value = false
        preferenceReflowPending = false
        preferenceReflowJob?.cancel()
        anchorGeneration++
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
        memoryJob?.cancel()
        preferenceReflowJob?.cancel()
        anchorRestoreJob?.cancel()
        paginationRestoreJob?.cancel()
        preferenceCaptureJob?.cancel()
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
private const val ANCHOR_OBSERVATION_TIMEOUT_MS = 1_500L

sealed interface ReaderUiState {
    data object Loading : ReaderUiState
    data class Error(val message: UiText) : ReaderUiState
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
    val expectedHref = current.href.toString()
    val primary = evaluateReaderJavascript(nav, PAGE_START_ANCHOR_JS, expectedHref) ?: return null
    if (nav.currentLocator.value.href.toString().substringBefore('#') != expectedHref.substringBefore('#')) return null
    val fromScript = parsePageStartAnchor(primary.value, current.href, current.mediaType)
    if (fromScript != null) return fromScript
    val fallback = evaluateReaderJavascript(nav, "readium.findFirstVisibleLocator();", expectedHref) ?: return null
    if (nav.currentLocator.value.href.toString().substringBefore('#') != expectedHref.substringBefore('#')) return null
    // The DOM URL envelope has already proved which publication resource supplied this value.
    // Readium's JS locator omits the publication href, so bind it only after that proof.
    return runCatching {
        Locator.fromJSON(org.json.JSONObject(fallback.value))?.copy(href = current.href, mediaType = current.mediaType)
    }.getOrNull() ?: current.takeIf { it.text.highlight.isNullOrBlank() }
}
