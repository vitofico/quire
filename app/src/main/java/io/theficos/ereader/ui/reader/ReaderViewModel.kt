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
import io.theficos.ereader.reader.ReadiumFactory
import io.theficos.ereader.reader.locatorAtPercent
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
    }

    private var pendingRotationAnchor: Locator? = null
    private var suppressLocatorPublishing: Boolean = false
    private var viewportSize: Pair<Int, Int>? = null

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

    // Called from MainActivity.onBeforeReaderConfigChange — runs BEFORE the
    // Activity dispatches the configuration change down to fragments. Snapshots
    // the current locator into pendingRotationAnchor and gates publishLocator so
    // Readium's post-resize drifted emissions cannot overwrite the anchor.
    fun beginViewportResize() {
        val anchor = _currentLocator.value ?: return
        pendingRotationAnchor = anchor
        suppressLocatorPublishing = true
    }

    // Called from the Readium PaginationListener when re-pagination completes
    // after a viewport resize. No-op if no resize is pending (so it's safe to
    // call on every onPageLoaded). Re-anchors via navigator.go(anchor, false),
    // re-seeds the current-locator flow, and re-enables publishing.
    fun completeViewportResize() {
        val anchor = pendingRotationAnchor ?: return
        pendingRotationAnchor = null
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
