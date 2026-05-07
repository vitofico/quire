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
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val syncEnqueuer: (Context) -> Unit = { SyncEnqueuer.enqueue(it, expedited = true, replaceExisting = true) },
) : ViewModel() {

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
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val items: StateFlow<List<LibraryRow>> = rows

    val continueReading: StateFlow<LibraryRow?> = rows
        .map { list ->
            list
                .filter { it.percent in 0.0001..0.9999 }
                .maxByOrNull { it.progressUpdatedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    fun delete(document: Document) {
        viewModelScope.launch { docs.delete(document) }
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
}

data class LibraryRow(
    val document: Document,
    val percent: Double,
    val progressUpdatedAt: Long,
)
