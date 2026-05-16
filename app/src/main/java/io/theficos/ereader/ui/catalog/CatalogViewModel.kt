package io.theficos.ereader.ui.catalog

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.core.identity.extractIdentity
import io.theficos.ereader.core.metadata.readOpfBundle
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.db.SyncStateDao
import io.theficos.ereader.data.opds.BookDownloader
import io.theficos.ereader.data.opds.OpdsClient
import io.theficos.ereader.data.opds.OpdsFeed
import io.theficos.ereader.data.opds.OpdsPublication
import io.theficos.ereader.data.sync.SyncEnqueuer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.UUID

class CatalogViewModel(
    private val client: OpdsClient,
    private val downloader: BookDownloader,
    private val docs: DocumentRepository,
    private val credentialStore: CalibreCredentialStore,
    private val syncStateDao: SyncStateDao,
    private val catalogPreferencesStore: CatalogPreferencesStore,
    private val syncEnqueuer: (Context) -> Unit =
        { ctx -> SyncEnqueuer.enqueue(ctx, expedited = true, replaceExisting = true) },
) : ViewModel() {

    private val _state = MutableStateFlow<CatalogUiState>(CatalogUiState.Idle)
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val sort: StateFlow<CatalogSort> = catalogPreferencesStore.flow

    fun setSort(next: CatalogSort) = catalogPreferencesStore.update(next)

    private val backStack = ArrayDeque<Pair<String, OpdsFeed>>()

    val downloadedUrls: StateFlow<Set<String>> = docs.observeLibrary()
        .map { list -> list.map { it.downloadUrl }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    init {
        viewModelScope.launch {
            credentialStore.flow
                .map { it?.baseUrl }
                .distinctUntilChanged()
                .collect { baseUrl ->
                    if (!baseUrl.isNullOrBlank()) loadRoot()
                }
        }
    }

    fun loadRoot() {
        val baseUrl = credentialStore.get()?.baseUrl
        if (baseUrl.isNullOrBlank()) {
            _state.value = CatalogUiState.Error("Configure calibre-web in Settings first.")
            return
        }
        backStack.clear()
        load("${baseUrl.trimEnd('/')}/opds")
    }

    fun refresh() {
        val current = _state.value as? CatalogUiState.Loaded ?: return loadRoot()
        _isRefreshing.value = true
        viewModelScope.launch {
            runCatching { client.fetch(current.url) }
                .onSuccess { feed ->
                    _state.value = CatalogUiState.Loaded(current.url, feed, canGoBack = backStack.isNotEmpty())
                }
                .onFailure { _state.value = CatalogUiState.Error(it.message ?: "Fetch failed") }
            _isRefreshing.value = false
        }
    }

    fun load(url: String) {
        val current = _state.value as? CatalogUiState.Loaded
        _state.value = CatalogUiState.Loading
        viewModelScope.launch {
            runCatching { client.fetch(url) }
                .onSuccess { feed ->
                    if (current != null) backStack.push(current.url to current.feed)
                    _state.value = CatalogUiState.Loaded(url, feed, canGoBack = backStack.isNotEmpty())
                }
                .onFailure { _state.value = CatalogUiState.Error(it.message ?: "Fetch failed") }
        }
    }

    fun back(): Boolean {
        val (url, feed) = backStack.pollFirst() ?: return false
        _state.value = CatalogUiState.Loaded(url, feed, canGoBack = backStack.isNotEmpty())
        return true
    }

    fun search(query: String) {
        val current = _state.value as? CatalogUiState.Loaded ?: return
        val link = current.feed.searchLink ?: return
        if (query.isBlank()) return
        _state.value = CatalogUiState.Loading
        viewModelScope.launch {
            runCatching {
                val resolved = client.resolveSearchUrl(link, query.trim())
                client.fetch(resolved) to resolved
            }.onSuccess { (feed, resolved) ->
                backStack.push(current.url to current.feed)
                _state.value = CatalogUiState.Loaded(resolved, feed, canGoBack = true)
            }.onFailure {
                _state.value = CatalogUiState.Error(it.message ?: "Search failed")
            }
        }
    }

    fun download(pub: OpdsPublication, context: Context) {
        val current = _state.value as? CatalogUiState.Loaded ?: return
        viewModelScope.launch {
            _state.value = current.copy(downloading = pub.epubDownloadHref, progress = 0f)
            runCatching {
                val fileName = "${UUID.randomUUID()}.epub"
                val file = downloader.download(pub.epubDownloadHref, fileName) { sent, total ->
                    val frac = if (total > 0) sent.toFloat() / total else 0f
                    _state.value = (_state.value as? CatalogUiState.Loaded)?.copy(progress = frac) ?: return@download
                }
                val coverFile = pub.coverUrl?.let { url ->
                    val coverName = fileName.removeSuffix(".epub") + ".cover"
                    downloader.downloadCover(url, coverName)
                }
                val identity = extractIdentity(file)
                val existing = docs.findByIdentity(identity)
                if (existing == null) {
                    // Best-effort: read the OPF for series metadata so PR8's
                    // continuity shelf can include this book the moment it lands.
                    // Failures fall back to a title-only bundle (no series).
                    val opf = readOpfBundle(file, fallbackTitle = pub.title)
                    docs.insert(
                        identity = identity,
                        title = pub.title,
                        author = pub.author,
                        downloadUrl = pub.epubDownloadHref,
                        localPath = file.absolutePath,
                        coverPath = coverFile?.absolutePath,
                        downloadedAt = System.currentTimeMillis(),
                        seriesName = opf.seriesName,
                        seriesIndex = opf.seriesPosition?.toDouble(),
                    )
                } else {
                    file.delete()
                    coverFile?.delete()
                }
            }.onSuccess {
                // The book that just landed may have server-side progress that an
                // earlier pull silently dropped (no local doc to attach to). Reset the
                // progress sync cursor so the next pull re-fetches every row from
                // epoch 0; the now-present local doc lets it attach. Best-effort —
                // a failure here doesn't roll back the already-successful download,
                // but is logged so the silent re-attach gap is visible in logcat.
                runCatching { syncStateDao.clearAll() }
                    .onFailure { Log.w("CatalogViewModel", "clearAll failed; progress re-attach deferred", it) }
                runCatching { syncEnqueuer(context) }
                    .onFailure { Log.w("CatalogViewModel", "syncEnqueuer failed; will retry on next manual sync", it) }
                _state.value = current.copy(downloading = null, progress = 0f, lastDownloaded = pub.title)
            }.onFailure {
                Log.e("CatalogViewModel", "download failed for ${pub.epubDownloadHref}", it)
                _state.value = current.copy(
                    downloading = null,
                    progress = 0f,
                    error = "${it.javaClass.simpleName}: ${it.message ?: "(no message)"}",
                )
            }
        }
    }
}

sealed interface CatalogUiState {
    data object Idle : CatalogUiState
    data object Loading : CatalogUiState
    data class Error(val message: String) : CatalogUiState
    data class Loaded(
        val url: String,
        val feed: OpdsFeed,
        val canGoBack: Boolean = false,
        val downloading: String? = null,
        val progress: Float = 0f,
        val lastDownloaded: String? = null,
        val error: String? = null,
    ) : CatalogUiState
}
