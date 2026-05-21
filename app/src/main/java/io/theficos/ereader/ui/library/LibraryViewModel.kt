package io.theficos.ereader.ui.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.sync.SyncEnqueuer
import io.theficos.ereader.data.sync.SyncOrchestrator
import io.theficos.ereader.data.sync.SyncResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface LibraryEvent {
    data object RestartFailed : LibraryEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val docs: DocumentRepository,
    private val progress: ProgressRepository,
    private val syncOrchestrator: SyncOrchestrator,
    private val booksDir: File,
    private val libraryPreferencesStore: LibraryPreferencesStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val syncEnqueuer: (Context) -> Unit = { SyncEnqueuer.enqueue(it, expedited = true, replaceExisting = true) },
) : ViewModel() {

    val sort: StateFlow<LibrarySort> = libraryPreferencesStore.flow
        .map { it.sort }
        .stateIn(viewModelScope, SharingStarted.Eagerly, libraryPreferencesStore.flow.value.sort)

    val showAbandoned: StateFlow<Boolean> = libraryPreferencesStore.flow
        .map { it.showAbandoned }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            libraryPreferencesStore.flow.value.showAbandoned,
        )

    fun setSort(next: LibrarySort) = libraryPreferencesStore.updateSort(next)

    fun toggleShowAbandoned() =
        libraryPreferencesStore.updateShowAbandoned(!libraryPreferencesStore.flow.value.showAbandoned)

    private val _query = kotlinx.coroutines.flow.MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(next: String) { _query.value = next }

    private val rows: StateFlow<List<LibraryRow>> =
        docs.observeLibrary()
            .flatMapLatest { docList ->
                if (docList.isEmpty()) flowOf(emptyList())
                else combine(docList.map { d -> progress.observe(d.id).map { d to it } }) { it.toList() }
            }
            .map { pairs ->
                pairs.map { (d, p) ->
                    LibraryRow(
                        document = d,
                        percent = p?.percent ?: 0.0,
                        progressUpdatedAt = p?.updatedAt ?: 0L,
                        finishedAt = p?.finishedAt,
                        abandonedAt = p?.abandonedAt,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val items: StateFlow<List<LibraryRow>> =
        combine(rows, libraryPreferencesStore.flow, _query) { list, prefs, q ->
            val sorted = applySort(list, prefs.sort)
            if (q.isBlank()) {
                // pr-δ: hide abandoned by default; the filter toggle reveals them.
                if (prefs.showAbandoned) sorted else sorted.filter { it.abandonedAt == null }
            } else {
                // pr-δ: search bypasses the abandoned filter — a user explicitly
                // searching for a title should always be able to find it.
                val needle = q.trim().lowercase()
                sorted.filter { row ->
                    row.document.title.lowercase().contains(needle) ||
                        (row.document.author?.lowercase()?.contains(needle) == true)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val continueReading: StateFlow<LibraryRow?> = rows
        .map { list ->
            list
                // pr-δ: abandoned books never re-surface on Continue Reading.
                .filter { it.percent > 0.0001 && it.finishedAt == null && it.abandonedAt == null }
                .maxByOrNull { it.progressUpdatedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * "Continue your series" candidates for the library home shelf. Reactive —
     * re-emits when any `documents` or `progress` row changes.
     *
     * Pure local Room query, no AI, no server call. See PR8.
     */
    val seriesContinuationCandidates: StateFlow<List<Document>> =
        docs.observeSeriesContinuationCandidates()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    fun delete(document: Document) {
        viewModelScope.launch { docs.delete(document) }
    }

    fun markAbandoned(document: Document) {
        viewModelScope.launch { progress.markAbandoned(document.id, nowMillis()) }
    }

    fun unmarkAbandoned(document: Document) {
        viewModelScope.launch { progress.unmarkAbandoned(document.id, nowMillis()) }
    }

    /**
     * Returns true on success, false on failure (a [LibraryEvent.RestartFailed]
     * is also emitted). Suspends until the push completes; intended to be
     * launched from a coroutine.
     */
    suspend fun restart(document: Document, alsoDeleteFile: Boolean): Boolean {
        progress.resetForDocument(document.id, now = nowMillis())
        val pushed = syncOrchestrator.runOnce()
        return when (pushed) {
            is SyncResult.Success -> {
                if (alsoDeleteFile) docs.delete(document)
                true
            }
            else -> {
                _events.tryEmit(LibraryEvent.RestartFailed)
                false
            }
        }
    }

    /**
     * Fire-and-forget wrapper for the UI. Schedules a WorkManager retry on
     * failure so the dirty row eventually drains.
     */
    fun restartFromUi(document: Document, alsoDeleteFile: Boolean, context: Context) {
        viewModelScope.launch {
            if (!restart(document, alsoDeleteFile)) {
                syncEnqueuer(context)
            }
        }
    }

    private fun applySort(list: List<LibraryRow>, by: LibrarySort): List<LibraryRow> = when (by) {
        LibrarySort.RECENTLY_READ -> list.sortedWith(
            compareByDescending<LibraryRow> { it.progressUpdatedAt }
                .thenBy { it.document.title.lowercase() }
        )
        LibrarySort.RECENTLY_ADDED -> list.sortedByDescending { it.document.id }
        LibrarySort.TITLE -> list.sortedBy { it.document.title.lowercase() }
        LibrarySort.AUTHOR -> list.sortedWith(
            compareBy<LibraryRow> { it.document.author?.lowercase() ?: "￿" }
                .thenBy { it.document.title.lowercase() }
        )
    }
}

data class LibraryRow(
    val document: Document,
    val percent: Double,
    val progressUpdatedAt: Long,
    val finishedAt: Long? = null,
    val abandonedAt: Long? = null,
)
