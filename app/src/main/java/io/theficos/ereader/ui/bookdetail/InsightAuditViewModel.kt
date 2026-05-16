package io.theficos.ereader.ui.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.ai.AiHttpException
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.ai.AiStyle
import io.theficos.ereader.data.ai.BookInsightResponse
import io.theficos.ereader.data.local.DocumentRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the "Inspect insight" debug screen. Reads the currently cached
 * [BookInsightResponse] for a document and exposes a single invalidate
 * action.
 *
 * No regenerate path — PR11 drops that pattern entirely. Invalidate uses
 * the existing body-based `POST /ai/v1/insights/invalidate`; a 404 on
 * invalidate (the row was already evicted by another device between
 * screen open and the user's tap) is treated as success.
 *
 * Style snapshot: the server response does NOT carry the `tone` /
 * `language` that produced the row. The audit screen surfaces the user's
 * current [AiStyle] preference separately so the rendering does not
 * imply we know which style this row came from.
 */
class InsightAuditViewModel(
    private val documentId: Long,
    private val source: Source,
) : ViewModel() {

    /**
     * Narrow indirection over [AiRepository] + [DocumentRepository] used by
     * the VM. Lets unit tests inject canned responses without spinning up
     * the full stack. Production wiring is in [AppContainer] via
     * [InsightAuditViewModelFactory].
     */
    interface Source {
        suspend fun resolveIdentity(documentId: Long): DocumentIdentity?
        suspend fun getCachedInsight(id: DocumentIdentity): BookInsightResponse?
        suspend fun invalidate(id: DocumentIdentity)
        fun currentStyle(): AiStyle?
    }

    sealed interface State {
        data object Loading : State
        data class Loaded(
            val identity: DocumentIdentity,
            val response: BookInsightResponse,
            val currentStyle: AiStyle?,
        ) : State
        data object NotCached : State
        data class Error(val message: String) : State
        data object Invalidating : State
        data object Done : State
    }

    sealed interface Event {
        data object Invalidated : Event
        data class InvalidateFailed(val message: String) : Event
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    fun invalidate() {
        val loaded = _state.value as? State.Loaded ?: return
        viewModelScope.launch {
            _state.value = State.Invalidating
            runCatching { source.invalidate(loaded.identity) }
                .onSuccess {
                    _state.value = State.Done
                    _events.emit(Event.Invalidated)
                }
                .onFailure { e ->
                    // 404 means the row is already gone — that's exactly the
                    // state the user asked for, so treat it as success.
                    if (e is AiHttpException && e.code == 404) {
                        _state.value = State.Done
                        _events.emit(Event.Invalidated)
                    } else {
                        _state.value = loaded
                        _events.emit(Event.InvalidateFailed(invalidateErrorMessage(e)))
                    }
                }
        }
    }

    private suspend fun load() {
        _state.value = State.Loading
        val identity = runCatching { source.resolveIdentity(documentId) }
            .getOrElse {
                _state.value = State.Error(loadErrorMessage(it))
                return
            }
        if (identity == null) {
            _state.value = State.Error("Book not found.")
            return
        }
        runCatching { source.getCachedInsight(identity) }
            .onSuccess { cached ->
                _state.value = if (cached == null) {
                    State.NotCached
                } else {
                    State.Loaded(
                        identity = identity,
                        response = cached,
                        currentStyle = source.currentStyle(),
                    )
                }
            }
            .onFailure { e -> _state.value = State.Error(loadErrorMessage(e)) }
    }

    private fun loadErrorMessage(e: Throwable): String = when (e) {
        is AiHttpException -> "Couldn't read cached insight (${e.code})."
        else -> "Couldn't read cached insight."
    }

    private fun invalidateErrorMessage(e: Throwable): String = when (e) {
        is AiHttpException -> "Couldn't invalidate (${e.code})."
        else -> "Couldn't invalidate."
    }
}

/**
 * Production [InsightAuditViewModel.Source] that wires the VM to the real
 * [DocumentRepository] + [AiRepository] in [AppContainer].
 */
class AppInsightAuditSource(
    private val documents: DocumentRepository,
    private val ai: AiRepository,
) : InsightAuditViewModel.Source {
    override suspend fun resolveIdentity(documentId: Long): DocumentIdentity? =
        documents.findById(documentId)?.let { doc ->
            DocumentIdentity(
                metadataId = doc.identity.metadataId,
                contentHash = doc.identity.contentHash,
            )
        }

    override suspend fun getCachedInsight(id: DocumentIdentity): BookInsightResponse? =
        ai.getCachedInsight(id)

    override suspend fun invalidate(id: DocumentIdentity) {
        ai.invalidate(id)
    }

    override fun currentStyle(): AiStyle? = ai.preferences.value?.style
}
