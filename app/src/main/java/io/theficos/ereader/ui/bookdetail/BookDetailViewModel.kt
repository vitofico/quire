package io.theficos.ereader.ui.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.metadata.OpfMetadataExtractor
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.Progress
import io.theficos.ereader.data.ai.AiHttpException
import io.theficos.ereader.data.ai.AiQuotaException
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.ai.BookDetails
import io.theficos.ereader.data.ai.BookDetailsRepository
import io.theficos.ereader.data.ai.BookInsightPayload
import io.theficos.ereader.data.ai.Citation
import io.theficos.ereader.data.ai.DetailsResponse
import io.theficos.ereader.data.ai.TIDY_FAILED
import io.theficos.ereader.data.ai.TIDY_QUOTA_EXCEEDED
import io.theficos.ereader.data.ai.aiIdentity
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.stats.ReadingStatsRepository
import io.theficos.ereader.ui.stats.statsKey
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

sealed interface InsightUiState {
    data object Hidden : InsightUiState
    data object Loading : InsightUiState
    data class Loaded(val payload: BookInsightPayload, val sources: List<Citation>) : InsightUiState
    data class Error(val message: UiText) : InsightUiState
}

/**
 * Found and tidied details. [available]: the reader may ask for a tidy now. [stored]: the
 * answer kept for this book, shown unless the reader chose the original. [outcome]: how the
 * last "Tidy details" went.
 */
data class DetailsUiState(
    val available: Boolean = false,
    val stored: BookDetails? = null,
    val tidying: Boolean = false,
    val outcome: UiText? = null,
) {
    /** The found details in use, or null when the book shows its own. */
    val shown: BookDetails? get() = stored?.takeUnless { it.useOriginal }
}

data class BookDetailState(
    val document: Document? = null,
    val metadata: MetadataBundle? = null,
    val progress: Progress? = null,
    val fileSizeBytes: Long? = null,
    val insight: InsightUiState = InsightUiState.Hidden,
    val details: DetailsUiState = DetailsUiState(),
    /** About how long the rest takes at the reader's pace, from the reading statistics. */
    val timeLeftSeconds: Long? = null,
)

class BookDetailViewModel(
    private val documentId: Long,
    private val documents: DocumentRepository,
    private val progress: ProgressRepository,
    private val ai: AiRepository,
    private val details: BookDetailsRepository,
    private val openOpfBytes: suspend (Document) -> ByteArray?,
    private val readingStats: ReadingStatsRepository? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(BookDetailState())
    val state: StateFlow<BookDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            progress.observe(documentId).collect { progress ->
                _state.update { it.copy(progress = progress) }
            }
        }
        viewModelScope.launch {
            combine(details.available, details.observe(documentId), ::Pair).collect { (available, stored) ->
                _state.update { it.copy(details = it.details.copy(available = available, stored = stored)) }
            }
        }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val doc = documents.findById(documentId) ?: run {
            _state.update { it.copy(document = null, insight = InsightUiState.Hidden) }
            return
        }
        _state.update { it.copy(document = doc) }
        readingStats?.let { stats ->
            viewModelScope.launch {
                stats.secondsLeft(doc.statsKey, progress.observe(documentId))
                    .flowOn(Dispatchers.Default)
                    .collect { left -> _state.update { it.copy(timeLeftSeconds = left) } }
            }
        }
        val opfBytes = openOpfBytes(doc)
        val (bundle, fileSizeBytes) = withContext(Dispatchers.IO) {
            insightMetadataBundle(doc, opfBytes) to File(doc.localPath).length().takeIf { it > 0L }
        }
        _state.update { it.copy(metadata = bundle, fileSizeBytes = fileSizeBytes) }

        val cfg = ai.config.value
        val pref = ai.preferences.value
        if (cfg?.configured != true || pref?.aiEnabled != true) {
            _state.update { it.copy(insight = InsightUiState.Hidden) }
            return
        }

        val ident = doc.aiIdentity()
        val cached = runCatching { ai.getCachedInsight(ident) }.getOrNull()
        if (cached != null) {
            _state.update {
                it.copy(insight = InsightUiState.Loaded(cached.payload, cached.sources))
            }
            return
        }

        _state.update { it.copy(insight = InsightUiState.Loading) }
        runCatching { ai.lookupInsight(ident, bundle) }
            .onSuccess { resp ->
                _state.update {
                    it.copy(insight = InsightUiState.Loaded(resp.payload, resp.sources))
                }
            }
            .onFailure { e ->
                val msg = insightErrorMessage(e)
                _state.update { it.copy(insight = InsightUiState.Error(msg)) }
            }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    /** "Tidy details": asks the server now, even for a book the automatic pass left alone. */
    fun tidyDetails() {
        if (_state.value.details.tidying) return
        _state.update { it.copy(details = it.details.copy(tidying = true, outcome = null)) }
        viewModelScope.launch {
            val outcome = try {
                tidyOutcome(details.tidy(documentId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tidyErrorMessage(e)
            }
            _state.update { it.copy(details = it.details.copy(tidying = false, outcome = outcome)) }
        }
    }

    /** "Use original" and back; remembered for the book. */
    fun useOriginalDetails(useOriginal: Boolean) {
        viewModelScope.launch { details.setUseOriginal(documentId, useOriginal) }
    }
}

internal fun tidyOutcome(response: DetailsResponse): UiText = when (response.tidy) {
    TIDY_QUOTA_EXCEEDED -> uiText(R.string.details_allowance_used)
    TIDY_FAILED -> uiText(R.string.details_couldn_t_tidy)
    "not_needed" -> uiText(R.string.details_nothing_to_tidy)
    else -> uiText(R.string.details_tidied)
}

/** Why "Tidy details" did not work, in the reader's words. */
internal fun tidyErrorMessage(e: Throwable): UiText = when {
    e is AiQuotaException -> uiText(R.string.details_allowance_used_until, e.info.resetsAt.take(10))
    e is AiHttpException && e.code == 429 && e.isRateLimited() -> uiText(R.string.details_busy)
    e is AiHttpException && e.code == 429 -> uiText(R.string.details_allowance_used)
    e is AiHttpException && e.code == 409 -> uiText(R.string.details_ai_not_available)
    e is AiHttpException && (e.code == 404 || e.code == 503) -> uiText(R.string.details_not_offered)
    e is SocketTimeoutException || e is InterruptedIOException ->
        uiText(R.string.bookdetail_the_server_took_too_long_to_answer)
    e is IOException -> uiText(R.string.bookdetail_couldn_t_reach_the_server_check_the)
    else -> uiText(R.string.details_couldn_t_tidy)
}

/** The server's "too many requests this minute", as opposed to a spent allowance. */
private fun AiHttpException.isRateLimited(): Boolean =
    runCatching { Json.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.content }.getOrNull() == "details_rate_limited"

/**
 * Metadata sent with an insight lookup. The OPF wins where it has a value,
 * but when it names no author (no `dc:creator`) the library row's author is
 * used: that one can come from the OPDS feed or a library restore, so it may
 * know what the EPUB does not, and the server's retrieval searches (Open
 * Library among them) query by title and author. A blank library author
 * counts as none, so the request never carries an empty one.
 */
internal fun insightMetadataBundle(doc: Document, opfBytes: ByteArray?): MetadataBundle {
    val libraryAuthor = doc.author?.takeIf { it.isNotBlank() }
    if (opfBytes == null) return MetadataBundle(title = doc.title, author = libraryAuthor)
    val opf = OpfMetadataExtractor.extract(opfBytes, fallbackTitle = doc.title)
    return if (opf.author == null) opf.copy(author = libraryAuthor) else opf
}
