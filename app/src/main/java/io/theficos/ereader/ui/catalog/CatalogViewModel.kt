package io.theficos.ereader.ui.catalog

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.SavedCatalog
import io.theficos.ereader.core.identity.extractIdentity
import io.theficos.ereader.core.metadata.readOpfBundle
import io.theficos.ereader.core.metadata.validateEpubContainer
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.ai.BookDetails
import io.theficos.ereader.data.ai.CatalogInsightStash
import io.theficos.ereader.data.ai.InsightSyncRepository
import io.theficos.ereader.data.library.LibraryUploader
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.db.SyncStateDao
import io.theficos.ereader.data.opds.BookDownloader
import io.theficos.ereader.data.opds.OpdsClient
import io.theficos.ereader.data.opds.OpdsFeed
import io.theficos.ereader.data.opds.OpdsPublication
import io.theficos.ereader.data.opds.cleartextBlockedMessage
import io.theficos.ereader.data.opds.redactUrl
import io.theficos.ereader.data.sync.SyncEnqueuer
import kotlinx.coroutines.Dispatchers
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CatalogViewModel(
    private val client: OpdsClient,
    private val downloader: BookDownloader,
    private val docs: DocumentRepository,
    private val credentialStore: CalibreCredentialStore,
    private val syncStateDao: SyncStateDao,
    private val catalogPreferencesStore: CatalogPreferencesStore,
    private val libraryUploader: LibraryUploader? = null,
    private val syncEnqueuer: (Context) -> Unit =
        { ctx -> SyncEnqueuer.enqueue(ctx, expedited = true, replaceExisting = true) },
    private val aiRepository: AiRepository? = null,
    private val catalogInsightStash: CatalogInsightStash? = null,
    private val insightSyncRepository: InsightSyncRepository? = null,
    private val subjectProvider: () -> String? = { null },
    /** Fetch the Quire account's catalog list, so a catalog added on another device shows up here. */
    private val pullCatalogs: suspend () -> Unit = {},
    /** Found and tidied details by library book id; only their covers show here. */
    private val shownDetails: Flow<Map<Long, BookDetails>> = flowOf(emptyMap()),
) : ViewModel() {

    /** Every saved catalog, for the switcher. */
    val catalogs: StateFlow<List<SavedCatalog>> = credentialStore.catalogs

    /** The catalog being browsed. */
    val activeCatalog: StateFlow<SavedCatalog?> = credentialStore.activeCatalog

    fun selectCatalog(id: String) = credentialStore.setActiveCatalog(id)

    /** The catalog view came on screen. */
    fun onOpened() {
        viewModelScope.launch { pullCatalogs() }
    }

    private val _state = MutableStateFlow<CatalogUiState>(CatalogUiState.Idle)
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val sort: StateFlow<CatalogSort> = catalogPreferencesStore.flow

    fun setSort(next: CatalogSort) = catalogPreferencesStore.update(next)

    private val backStack = ArrayDeque<Pair<String, OpdsFeed>>()
    private var failedSearch: Pair<CatalogUiState.Loaded, String>? = null

    val downloadedUrls: StateFlow<Set<String>> = docs.observeLibrary()
        .map { list -> list.map { it.downloadUrl }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** The found cover of each downloaded book that has none of its own, by download address. */
    val storedCovers: StateFlow<Map<String, String>> = docs.observeLibrary()
        .combine(shownDetails) { list, details ->
            list.mapNotNull { doc ->
                details[doc.id]?.coverPath?.takeIf { doc.coverPath == null }?.let { doc.downloadUrl to it }
            }.toMap()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        viewModelScope.launch {
            // Reload when the catalog or its authentication changes, including
            // a password correction at the same URL.
            credentialStore.activeCatalog
                .map { it?.id to it?.catalog }
                .distinctUntilChanged()
                .collect { loadRoot() }
        }
    }

    fun loadRoot() {
        val active = credentialStore.activeCatalog.value
        val account = active?.catalog
        backStack.clear()
        failedSearch = null
        _isRefreshing.value = false
        if (account == null || account.baseUrl.isBlank()) {
            // A catalog the account lists but whose login this device can't read yet.
            _state.value = CatalogUiState.Error(
                if (active != null) uiText(R.string.catalog_needs_login, active.name)
                else uiText(R.string.quire_account_catalog_missing),
            )
            return
        }
        _state.value = CatalogUiState.Loading
        // A saved catalog's address is the complete feed URL (Kavita's is `/api/opds/{api-key}`),
        // so it is used exactly as entered. Appending to it is what issue #101 was filed about.
        load(account.baseUrl)
    }

    fun refresh() {
        failedSearch = null
        val current = _state.value as? CatalogUiState.Loaded ?: return loadRoot()
        _isRefreshing.value = true
        val catalog = credentialStore.catalogAccount()
        viewModelScope.launch {
            val result = runCatching { client.fetch(current.url) }
            if (catalog != credentialStore.catalogAccount()) return@launch
            result.onSuccess { feed ->
                    _state.value = CatalogUiState.Loaded(current.url, feed, canGoBack = backStack.isNotEmpty())
                }
                .onFailure { fail(it, current.url, current, uiText(R.string.common_fetch_failed)) }
            _isRefreshing.value = false
        }
    }

    fun load(url: String) {
        failedSearch = null
        val current = _state.value as? CatalogUiState.Loaded
        _state.value = CatalogUiState.Loading
        val catalog = credentialStore.catalogAccount()
        viewModelScope.launch {
            val result = runCatching { client.fetch(url) }
            if (catalog != credentialStore.catalogAccount()) return@launch
            result.onSuccess { feed ->
                    if (current != null) backStack.push(current.url to current.feed)
                    _state.value = CatalogUiState.Loaded(url, feed, canGoBack = backStack.isNotEmpty())
                }
                .onFailure { fail(it, url, current, uiText(R.string.common_fetch_failed)) }
        }
    }

    fun back(): Boolean {
        val (url, feed) = backStack.pollFirst() ?: return false
        failedSearch = null
        _state.value = CatalogUiState.Loaded(url, feed, canGoBack = backStack.isNotEmpty())
        return true
    }

    fun retry() {
        val failed = _state.value as? CatalogUiState.Error ?: return
        failedSearch?.let { (parent, query) ->
            search(parent, query, retainParent = false)
            return
        }
        failed.url?.let(::load) ?: loadRoot()
    }

    private fun fail(t: Throwable, url: String, previous: CatalogUiState.Loaded?, fallback: UiText) {
        if (previous != null) backStack.push(previous.url to previous.feed)
        _state.value = CatalogUiState.Error(describeFailure(t, fallback), url, backStack.isNotEmpty())
    }

    fun search(query: String) {
        val current = _state.value as? CatalogUiState.Loaded ?: return
        search(current, query, retainParent = true)
    }

    private fun search(current: CatalogUiState.Loaded, query: String, retainParent: Boolean) {
        val link = current.feed.searchLink ?: return
        if (query.isBlank()) return
        _state.value = CatalogUiState.Loading
        val catalog = credentialStore.catalogAccount()
        viewModelScope.launch {
            var retryUrl = current.url
            val result = runCatching {
                val resolved = client.resolveSearchUrl(link, query.trim())
                retryUrl = resolved
                client.fetch(resolved) to resolved
            }
            if (catalog != credentialStore.catalogAccount()) return@launch
            result.onSuccess { (feed, resolved) ->
                failedSearch = null
                if (retainParent) backStack.push(current.url to current.feed)
                _state.value = CatalogUiState.Loaded(resolved, feed, canGoBack = backStack.isNotEmpty())
            }.onFailure {
                failedSearch = current to query
                fail(it, retryUrl, current.takeIf { retainParent }, uiText(R.string.common_search_failed))
            }
        }
    }

    /**
     * PR-ζ: best-effort insight promote.
     *
     * Consults [CatalogInsightStash] for the catalog-side identity the user
     * saw pre-download. On a hit, calls [AiRepository.promoteInsight] and
     * removes the stash entry only on a confirmed success. On any failure
     * the stash entry is kept so a retry on the next download attempt has
     * a shot at promoting.
     *
     * Returns silently when any of the stash / repo / subject are null —
     * production wiring supplies all three; unit tests that don't care
     * leave them as null.
     */
    private suspend fun maybePromoteInsight(
        pub: OpdsPublication,
        downloadedIdentity: DocumentIdentity,
        subject: String?,
    ) {
        val stash = catalogInsightStash ?: return
        val ai = aiRepository ?: return
        if (subject == null || subject != subjectProvider()) return
        val entry = stash.peek(subject, pub.epubDownloadHref) ?: return
        val ok = ai.promoteInsight(
            from = entry.catalogIdentity,
            to = downloadedIdentity,
            tone = entry.tone,
            language = entry.language,
        )
        if (ok) {
            stash.remove(subject, pub.epubDownloadHref)
            // PR-η / coordinator §3.17: a successful promote means the server
            // wrote a new BookInsight row at `downloadedIdentity` with
            // `generated_at = NOW()`. Trigger a sync so the Android cache picks
            // it up — coalescing collapses bursts when the user downloads
            // several books in quick succession.
            if (subject == subjectProvider()) insightSyncRepository?.requestSync("after_promote")
        } else {
            Log.d(
                "CatalogViewModel",
                // The acquisition href can itself be a credential: a Kavita catalog
                // embeds a full-account API key in the download path (issue #101).
                "promoteInsight returned false for href=${redactUrl(pub.epubDownloadHref)}; stash kept for retry",
            )
        }
    }

    /**
     * Failure copy for the catalog screen.
     *
     * Exception text is the fallback, not the answer: the one failure a user can
     * act on themselves is the platform refusing an `http://` request, and its
     * raw form ("CLEARTEXT communication to … not permitted by network security
     * policy") reads like a crash report (issue #101).
     */
    private fun describeFailure(t: Throwable, fallback: UiText): UiText =
        if (t is InvalidDownloadedEpub) uiText(R.string.common_not_a_valid_epub)
        else if (cleartextBlockedMessage(t) != null) uiText(R.string.error_cleartext_blocked)
        else t.message?.let(UiText::Dynamic) ?: fallback

    fun download(pub: OpdsPublication, context: Context) {
        val current = _state.value as? CatalogUiState.Loaded ?: return
        // Without a Quire login (issue #101) there is no quire-server: enqueueing a
        // sync or a mirror push would just log a failure per book. The clients
        // would refuse anyway; this keeps the work from being scheduled at all.
        val account = credentialStore.getAccount()
        val catalog = credentialStore.catalogAccount()
        val catalogId = credentialStore.activeCatalog.value?.id
        val subject = subjectProvider()
        val hasQuireServer = account != null
        fun sameCatalog() = catalog == credentialStore.catalogAccount()
        fun sameServiceScope() = hasQuireServer && sameCatalog() && subject == subjectProvider()
        viewModelScope.launch {
            _state.value = current.copy(downloading = pub.epubDownloadHref, progress = 0f)
            runCatching {
                val fileName = "${UUID.randomUUID()}.epub"
                val file = downloader.download(pub.epubDownloadHref, fileName) { sent, total ->
                    if (!sameCatalog()) return@download
                    val frac = if (total > 0) sent.toFloat() / total else 0f
                    _state.value = (_state.value as? CatalogUiState.Loaded)?.copy(progress = frac) ?: return@download
                }
                val identity = withContext(Dispatchers.IO) {
                    if (!validateEpubContainer(file)) {
                        file.delete()
                        throw InvalidDownloadedEpub()
                    }
                    extractIdentity(file)
                }
                val coverFile = pub.coverUrl?.takeIf { sameCatalog() }?.let { url ->
                    val coverName = fileName.removeSuffix(".epub") + ".cover"
                    downloader.downloadCover(url, coverName)
                }
                val existing = docs.findByIdentity(identity)
                if (existing == null) {
                    // Best-effort: read the OPF for series metadata so PR8's
                    // continuity shelf can include this book the moment it lands.
                    // Failures fall back to a title-only bundle (no series).
                    val opf = withContext(Dispatchers.IO) { readOpfBundle(file, fallbackTitle = pub.title) }
                    val insertedId = docs.insert(
                        identity = identity,
                        title = pub.title,
                        author = pub.author,
                        downloadUrl = pub.epubDownloadHref,
                        localPath = file.absolutePath,
                        coverPath = coverFile?.absolutePath,
                        downloadedAt = System.currentTimeMillis(),
                        seriesName = opf.seriesName,
                        seriesIndex = opf.seriesPosition?.toDouble(),
                        catalogId = catalogId,
                    )
                    // Upload to /library/v1/items so the server can include
                    // this book in stats / aggregates. Lock #13: we capture
                    // the Job and join it before the promote call fires, so
                    // the server has the library_items row before the
                    // promote's ownership gate (`_assert_owns`) runs.
                    // Failures are logged inside the uploader; the row stays
                    // unsynced and the next app-start pass retries.
                    val uploadJob =
                        if (sameServiceScope()) libraryUploader?.enqueueOne(insertedId) else null
                    uploadJob?.join()
                    maybePromoteInsight(pub, identity, subject)
                } else {
                    withContext(Dispatchers.IO) {
                        file.delete()
                        coverFile?.delete()
                    }
                }
            }.onSuccess {
                // The book that just landed may have server-side progress that an
                // earlier pull silently dropped (no local doc to attach to). Reset the
                // progress sync cursor so the next pull re-fetches every row from
                // epoch 0; the now-present local doc lets it attach. Best-effort —
                // a failure here doesn't roll back the already-successful download,
                // but is logged so the silent re-attach gap is visible in logcat.
                if (sameServiceScope()) {
                    runCatching { syncStateDao.clearAll() }
                        .onFailure { Log.w("CatalogViewModel", "clearAll failed; progress re-attach deferred", it) }
                    runCatching { syncEnqueuer(context) }
                        .onFailure { Log.w("CatalogViewModel", "syncEnqueuer failed; will retry on next manual sync", it) }
                }
                if (sameCatalog()) {
                    _state.value = current.copy(downloading = null, progress = 0f, lastDownloaded = pub.title)
                }
            }.onFailure {
                // Redacted for the same reason as the promote log above: on a
                // generic OPDS account this href carries the user's API key.
                Log.e("CatalogViewModel", "download failed for ${redactUrl(pub.epubDownloadHref)}", it)
                if (sameCatalog()) {
                    _state.value = current.copy(
                        downloading = null,
                        progress = 0f,
                        error = describeFailure(it, uiText(R.string.common_fetch_failed)),
                    )
                }
            }
        }
    }
}

private class InvalidDownloadedEpub : Exception()

sealed interface CatalogUiState {
    data object Idle : CatalogUiState
    data object Loading : CatalogUiState
    data class Error(val message: UiText, val url: String? = null, val canGoBack: Boolean = false) : CatalogUiState
    data class Loaded(
        val url: String,
        val feed: OpdsFeed,
        val canGoBack: Boolean = false,
        val downloading: String? = null,
        val progress: Float = 0f,
        val lastDownloaded: String? = null,
        val error: UiText? = null,
    ) : CatalogUiState
}
