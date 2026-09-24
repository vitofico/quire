package io.theficos.ereader.ui.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.metadata.OpfMetadataExtractor
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.ai.BookInsightPayload
import io.theficos.ereader.data.ai.Citation
import io.theficos.ereader.data.local.DocumentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface InsightUiState {
    data object Hidden : InsightUiState
    data object Loading : InsightUiState
    data class Loaded(val payload: BookInsightPayload, val sources: List<Citation>) : InsightUiState
    data class Error(val message: String) : InsightUiState
}

data class BookDetailState(
    val document: Document? = null,
    val insight: InsightUiState = InsightUiState.Hidden,
)

class BookDetailViewModel(
    private val documentId: Long,
    private val documents: DocumentRepository,
    private val ai: AiRepository,
    private val openOpfBytes: suspend (Document) -> ByteArray?,
) : ViewModel() {

    private val _state = MutableStateFlow(BookDetailState())
    val state: StateFlow<BookDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val doc = documents.findById(documentId) ?: run {
            _state.value = BookDetailState(insight = InsightUiState.Hidden)
            return
        }
        _state.value = BookDetailState(document = doc)

        val cfg = ai.config.value
        val pref = ai.preferences.value
        if (cfg?.configured != true || pref?.aiEnabled != true) {
            _state.value = _state.value.copy(insight = InsightUiState.Hidden)
            return
        }

        val ident = DocumentIdentity(
            metadataId = doc.identity.metadataId,
            contentHash = doc.identity.contentHash,
        )
        val cached = runCatching { ai.getCachedInsight(ident) }.getOrNull()
        if (cached != null) {
            _state.value = _state.value.copy(
                insight = InsightUiState.Loaded(cached.payload, cached.sources),
            )
            return
        }

        _state.value = _state.value.copy(insight = InsightUiState.Loading)
        val bundle = insightMetadataBundle(doc, openOpfBytes(doc))
        runCatching { ai.lookupInsight(ident, bundle) }
            .onSuccess { resp ->
                _state.value = _state.value.copy(
                    insight = InsightUiState.Loaded(resp.payload, resp.sources),
                )
            }
            .onFailure { e ->
                val msg = insightErrorMessage(e)
                _state.value = _state.value.copy(insight = InsightUiState.Error(msg))
            }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }
}

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
