package io.theficos.ereader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.model.Highlight
import io.theficos.ereader.data.ai.AiHttpException
import io.theficos.ereader.data.ai.AiProviderException
import io.theficos.ereader.data.ai.AiQuotaException
import io.theficos.ereader.data.ai.DiscussAnswer
import io.theficos.ereader.data.ai.DiscussExchange
import io.theficos.ereader.data.ai.DiscussLimits
import io.theficos.ereader.data.ai.DiscussPassage
import io.theficos.ereader.data.ai.DiscussRequest
import io.theficos.ereader.data.ai.discussDraft
import io.theficos.ereader.data.ai.discussPassages
import io.theficos.ereader.data.ai.discussShareNote
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A conversation about a few passages. Nothing of it is saved: [close] discards it. */
data class DiscussionState(
    /** Empty while no discussion is open. */
    val passages: List<DiscussPassage> = emptyList(),
    val exchanges: List<DiscussExchange> = emptyList(),
    val includeNotes: Boolean = true,
    val draft: String = "",
    /** The question waiting for its answer. */
    val asking: String? = null,
    val clipped: Boolean = false,
    val error: UiText? = null,
) {
    val open: Boolean get() = passages.isNotEmpty()
    val hasNotes: Boolean get() = passages.any { it.note != null }

    /** Notes are chosen before the first question and stay as they were until Start again. */
    val notesLocked: Boolean get() = exchanges.isNotEmpty() || asking != null

    fun questionsLeft(limits: DiscussLimits): Int = (limits.maxQuestions - exchanges.size).coerceAtLeast(0)
}

/**
 * Holds the conversation of the reader's "Think it through" screen. It lives with the book and
 * keeps nothing once the screen closes; only [limits] decide whether a question may be asked.
 */
class HighlightsDiscussionViewModel(
    /** The server's limits while the feature is offered, null while it is not. */
    val limits: StateFlow<DiscussLimits?>,
    private val discuss: suspend (DiscussRequest) -> DiscussAnswer,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscussionState())
    val state: StateFlow<DiscussionState> = _state.asStateFlow()

    private var job: Job? = null

    /** Bumped whenever a waiting answer stops being wanted, so a late one is dropped. */
    private var generation = 0

    fun open(highlights: List<Highlight>) {
        // The selection already stops at the limit; this holds if a caller ever passes more.
        val passages = discussPassages(highlights.take(limits.value?.maxHighlights ?: highlights.size))
        if (passages.isEmpty()) return
        drop()
        _state.value = DiscussionState(passages = passages)
    }

    fun close() {
        drop()
        _state.value = DiscussionState()
    }

    fun setDraft(text: String) = _state.update { it.copy(draft = text) }

    fun setIncludeNotes(include: Boolean) = _state.update { if (it.notesLocked) it else it.copy(includeNotes = include) }

    fun ask(question: String) {
        val current = _state.value
        val limits = limits.value
        if (!current.open || question.isBlank() || current.asking != null) return
        if (limits == null) {
            _state.update { it.copy(error = uiText(R.string.discuss_not_offered)) }
            return
        }
        if (current.questionsLeft(limits) == 0) return
        // The server can lower its limit while the screen is open; never send more than it takes.
        if (current.passages.size > limits.maxHighlights) {
            _state.update { it.copy(error = uiText(R.string.discuss_too_large)) }
            return
        }
        val draft = discussDraft(current.passages, current.exchanges, question, current.includeNotes, limits)
        if (draft.tooLarge) {
            _state.update { it.copy(clipped = draft.clipped, error = uiText(R.string.discuss_too_large)) }
            return
        }
        val asked = question.trim()
        val mine = ++generation
        // A starter goes into the box too, so Cancel leaves it there to send again, as it does a typed one.
        _state.update { it.copy(asking = asked, draft = question, clipped = draft.clipped, error = null) }
        job = viewModelScope.launch {
            val outcome = try {
                Result.success(discuss(draft.request))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (mine != generation) return@launch
            _state.update { state ->
                outcome.fold(
                    onSuccess = { answer ->
                        state.copy(
                            exchanges = state.exchanges + DiscussExchange(asked, answer.answer, answer.cited),
                            asking = null,
                            draft = "",
                        )
                    },
                    onFailure = { state.copy(asking = null, error = discussErrorMessage(it)) },
                )
            }
        }
    }

    /** Stops waiting; the question stays in the box to send again. */
    fun cancel() {
        if (_state.value.asking == null) return
        drop()
        _state.update { it.copy(asking = null) }
    }

    /** A new conversation about the same passages, with the notes choice open again. */
    fun startAgain() {
        drop()
        _state.update { DiscussionState(passages = it.passages, includeNotes = it.includeNotes) }
    }

    /** The latest answer as a note to send, or null before the first answer. */
    fun shareNote(heading: String): String? {
        val current = _state.value
        val latest = current.exchanges.lastOrNull() ?: return null
        return discussShareNote(latest, current.passages, heading)
    }

    private fun drop() {
        generation++
        job?.cancel()
        job = null
    }
}

/** Why a question about highlights got no answer, in the reader's words. */
internal fun discussErrorMessage(e: Throwable): UiText = when {
    e is AiQuotaException -> uiText(R.string.details_allowance_used_until, e.info.resetsAt.take(10))
    e is AiProviderException && e.serverMessage.isNotBlank() -> UiText.Dynamic(e.serverMessage)
    e is AiHttpException -> when {
        e.code == 401 -> uiText(R.string.quire_account_expired)
        e.code == 413 -> uiText(R.string.discuss_too_large)
        e.code == 429 && e.detail == "discuss_rate_limited" -> uiText(R.string.details_busy)
        e.code == 429 -> uiText(R.string.details_allowance_used)
        e.detail == "ai_provider_unavailable" || e.detail == "ai_provider_unknown" -> uiText(R.string.discuss_provider_gone)
        e.code == 409 -> uiText(R.string.details_ai_not_available)
        e.code == 404 || e.detail == "ai_disabled" -> uiText(R.string.discuss_not_offered)
        e.code == 503 -> uiText(R.string.details_busy)
        e.code == 422 && e.detail == "too_large" -> uiText(R.string.discuss_too_large)
        e.code == 422 -> uiText(R.string.discuss_refused)
        else -> uiText(R.string.discuss_failed)
    }
    // SocketTimeoutException (read timeout) extends InterruptedIOException (OkHttp call timeout).
    e is SocketTimeoutException || e is InterruptedIOException -> uiText(R.string.bookdetail_the_server_took_too_long_to_answer)
    e is IOException -> uiText(R.string.bookdetail_couldn_t_reach_the_server_check_the)
    else -> uiText(R.string.discuss_failed)
}
