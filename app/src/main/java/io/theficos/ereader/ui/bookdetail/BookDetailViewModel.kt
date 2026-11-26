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
import io.theficos.ereader.data.ai.COVER_DRAW
import io.theficos.ereader.data.ai.COVER_FIND
import io.theficos.ereader.data.ai.COVER_KEEP
import io.theficos.ereader.data.ai.COVER_PAINT
import io.theficos.ereader.data.ai.Citation
import io.theficos.ereader.data.ai.CoverPreview
import io.theficos.ereader.data.ai.DETAILS_FIELDS
import io.theficos.ereader.data.ai.DetailsBook
import io.theficos.ereader.data.ai.DetailsCover
import io.theficos.ereader.data.ai.DetailsProposal
import io.theficos.ereader.data.ai.FIELD_AUTHORS
import io.theficos.ereader.data.ai.FIELD_DESCRIPTION
import io.theficos.ereader.data.ai.FIELD_SERIES
import io.theficos.ereader.data.ai.FIELD_TITLE
import io.theficos.ereader.data.ai.ORIGIN_CATALOG
import io.theficos.ereader.data.ai.ORIGIN_FILE
import io.theficos.ereader.data.ai.aiIdentity
import io.theficos.ereader.data.ai.shownWith
import io.theficos.ereader.data.ai.showsCover
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
import java.math.BigDecimal
import androidx.core.text.HtmlCompat
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
 * answer kept for this book, shown unless the reader chose the original. [preview]: a tidy
 * waiting for the reader to accept or reject it. [outcome]: how the last "Improve details"
 * went, sentence by sentence.
 */
data class DetailsUiState(
    val available: Boolean = false,
    val stored: BookDetails? = null,
    val improving: Boolean = false,
    val preview: DetailsPreview? = null,
    val outcome: List<UiText> = emptyList(),
) {
    /** The found details in use, or null when the book shows its own. */
    val shown: BookDetails? get() = stored?.takeUnless { it.useOriginal }
}

/** One field a tidy would change, as the book shows it now and as proposed. */
data class DetailsChange(
    /** One of [DETAILS_FIELDS]. */
    val field: String,
    val current: String,
    val proposed: String,
    /** [current] is the book's own description, which may be HTML. */
    val currentIsHtml: Boolean = false,
)

/** A cover choice's preview: loading until it has a [preview] or [failed]. */
data class CoverLoad(val preview: CoverPreview? = null, val failed: UiText? = null)

/**
 * A tidy's proposal: the [changes] with those [ticked] to keep, and the cover. With
 * [coverOptions] the reader picks one ([choice]); without them (a server that takes no cover
 * choice) a [newCover] the tidy brings is kept while [keepCover].
 */
data class DetailsPreview(
    val proposal: DetailsProposal,
    val changes: List<DetailsChange>,
    val ticked: Set<String>,
    val coverOptions: List<String>,
    val choice: String,
    val newCover: Boolean,
    val keepCover: Boolean = true,
    val covers: Map<String, CoverLoad> = emptyMap(),
    val applying: Boolean = false,
    val failed: UiText? = null,
) {
    /** The preview of the cover that Apply would keep. */
    val cover: CoverLoad? get() = covers[if (coverOptions.isEmpty()) COVER_KEEP else choice]

    val canApply: Boolean get() {
        val loading = cover.let { it == null || (it.preview == null && it.failed == null) }
        return !applying && !(loading && (coverOptions.isNotEmpty() || (newCover && keepCover)))
    }
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

    /**
     * "Improve details": tidies now, even for a book the automatic pass left alone, and opens
     * a preview of what would change. When nothing would and the server offers no cover
     * choice, says so instead.
     */
    fun improveDetails() {
        val current = _state.value
        if (current.details.improving || current.details.preview != null) return
        _state.update { it.copy(details = it.details.copy(improving = true, outcome = emptyList())) }
        viewModelScope.launch {
            val proposal = try {
                details.propose(documentId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(details = it.details.copy(improving = false, outcome = listOf(tidyErrorMessage(e)))) }
                return@launch
            }
            val state = _state.value
            val doc = proposal.document
            val stored = state.details.stored
            val config = ai.config.value
            val choices = config?.coverChoiceSupported == true
            val changes = detailsChanges(doc, state.metadata, stored, proposal.answer.book)
            val newCover = proposedCover(doc, proposal.answer.cover) != shownCover(doc, stored)
            if (!choices && changes.isEmpty() && !newCover) {
                // Nothing to decide, so nothing is declined: the book keeps all it showed.
                details.dismiss(proposal)
                _state.update { it.copy(details = it.details.copy(improving = false, outcome = listOf(uiText(R.string.details_nothing_to_tidy)))) }
                return@launch
            }
            val own = doc.coverPath != null
            val options = if (!choices) {
                emptyList()
            } else {
                listOfNotNull(COVER_KEEP, COVER_FIND.takeIf { own }, COVER_DRAW, COVER_PAINT.takeIf { config?.coverPaintingSupported == true })
            }
            // The reader's last choice, while this server still offers it; without a cover of its own, "keep" already finds one.
            val last = stored?.response?.coverChoice?.takeIf { choices } ?: COVER_KEEP
            val choice = when {
                last == COVER_PAINT && COVER_PAINT !in options -> COVER_DRAW
                last == COVER_FIND && !own -> COVER_KEEP
                else -> last
            }
            val preview = DetailsPreview(proposal, changes, changes.mapTo(HashSet()) { it.field }, options, choice, newCover)
            _state.update { it.copy(details = it.details.copy(improving = false, preview = preview)) }
            loadCover(if (options.isEmpty()) COVER_KEEP else choice)
        }
    }

    fun toggleChange(field: String) = updatePreview { p ->
        p.copy(ticked = if (field in p.ticked) p.ticked - field else p.ticked + field)
    }

    fun keepNewCover(keep: Boolean) = updatePreview { it.copy(keepCover = keep) }

    fun chooseCover(choice: String) {
        updatePreview { it.copy(choice = choice) }
        loadCover(choice)
    }

    /** Ends the preview, rejecting the tidy: a later lookup served the same record brings none of its changes. */
    fun cancelPreview() {
        val preview = _state.value.details.preview ?: return
        if (preview.applying) return
        details.close(preview.proposal)
        _state.update { it.copy(details = it.details.copy(preview = null)) }
    }

    /** Keeps the ticked changes and the chosen cover, then says what changed. */
    fun applyPreview() {
        val preview = _state.value.details.preview ?: return
        if (!preview.canApply) return
        updatePreview { it.copy(applying = true, failed = null) }
        viewModelScope.launch {
            val before = _state.value.details.stored
            val choices = preview.coverOptions.isNotEmpty()
            val accepted = DETAILS_FIELDS.filter { field -> preview.changes.none { it.field == field } || field in preview.ticked }
            // A cover that failed to download leaves the cover as it is; the text changes still apply.
            val chosen = if (choices || preview.keepCover) preview.cover?.preview else null
            val choice = when {
                chosen == null -> before?.response?.coverChoice?.takeIf { choices } ?: COVER_KEEP
                choices -> preview.choice
                else -> COVER_KEEP
            }
            val applied = try {
                details.apply(preview.proposal, accepted, choice, chosen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updatePreview { it.copy(applying = false, failed = tidyErrorMessage(e)) }
                return@launch
            }
            val doc = preview.proposal.document
            val outcome = buildList {
                val kept = preview.changes.map { it.field }.filter { it in preview.ticked }
                if (kept.isNotEmpty()) add(uiText(R.string.details_updated, fieldList(kept)))
                if (applied != null && coverNow(doc, applied) != coverNow(doc, before)) add(uiText(R.string.details_new_cover))
                if (applied != null && choice == COVER_PAINT && chosen != null) {
                    val cover = applied.response.cover
                    add(
                        uiText(
                            when {
                                cover.painting -> R.string.details_painting_on_its_way
                                cover.painted -> R.string.details_new_cover
                                else -> R.string.details_no_painting_today
                            },
                        ),
                    )
                }
            }.distinct().ifEmpty { listOf(uiText(R.string.details_nothing_changed)) }
            _state.update {
                it.copy(details = it.details.copy(preview = null, outcome = if (applied == null) emptyList() else outcome))
            }
        }
    }

    /** "Use original" and back; remembered for the book. */
    fun useOriginalDetails(useOriginal: Boolean) {
        viewModelScope.launch { details.setUseOriginal(documentId, useOriginal) }
    }

    override fun onCleared() {
        // Leaving the book ends the preview, so the automatic pass goes on; an Apply under way finishes on its own.
        _state.value.details.preview?.takeUnless { it.applying }?.let { details.close(it.proposal) }
    }

    private fun updatePreview(change: (DetailsPreview) -> DetailsPreview) = _state.update { state ->
        state.details.preview?.let { state.copy(details = state.details.copy(preview = change(it))) } ?: state
    }

    /** Fetches [choice]'s cover once, and again after a failure when the reader picks it again. */
    private fun loadCover(choice: String) {
        val preview = _state.value.details.preview ?: return
        val loaded = preview.covers[choice]
        if (loaded != null && loaded.failed == null) return
        val proposal = preview.proposal
        fun store(load: CoverLoad) = updatePreview { if (it.proposal === proposal) it.copy(covers = it.covers + (choice to load)) else it }
        store(CoverLoad())
        viewModelScope.launch {
            store(
                try {
                    CoverLoad(preview = details.coverPreview(proposal, choice))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CoverLoad(failed = tidyErrorMessage(e))
                },
            )
        }
    }
}

/** What the book shows for each field group now, as [DETAILS_FIELDS] names them. */
private fun shownFields(own: Document, metadata: MetadataBundle?, stored: BookDetails?): Map<String, String> {
    val found = stored?.takeUnless { it.useOriginal }
    val shown = own.shownWith(found)
    return mapOf(
        FIELD_TITLE to shown.title,
        FIELD_AUTHORS to (shown.author?.takeIf { it.isNotBlank() } ?: metadata?.author).orEmpty(),
        FIELD_SERIES to seriesText(shown.seriesName ?: metadata?.seriesName, shown.seriesIndex ?: metadata?.seriesPosition?.toDouble()),
        FIELD_DESCRIPTION to (found?.let { foundMetadata(metadata, own, it).description } ?: metadata?.description).orEmpty(),
    )
}

/**
 * The fields whose [proposed] value differs from what the book shows now. A field proposed
 * empty is left out: a found answer never empties a field, the book's own value shows instead.
 */
internal fun detailsChanges(own: Document, metadata: MetadataBundle?, stored: BookDetails?, proposed: DetailsBook): List<DetailsChange> {
    val now = shownFields(own, metadata, stored)
    val found = stored?.takeUnless { it.useOriginal }
    val ownDescription = found == null || !usesFoundDescription(found)
    val next = mapOf(
        FIELD_TITLE to proposed.title,
        FIELD_AUTHORS to proposed.authors.joinToString(", "),
        FIELD_SERIES to seriesText(proposed.seriesName, proposed.seriesIndex),
        FIELD_DESCRIPTION to proposed.description.orEmpty(),
    )
    return DETAILS_FIELDS.mapNotNull { field ->
        val html = field == FIELD_DESCRIPTION && ownDescription
        val current = now.getValue(field)
        val shownText = if (html) plainText(current) else current
        DetailsChange(field, current, next.getValue(field), currentIsHtml = html)
            .takeIf { it.proposed.isNotBlank() && shownText.collapse() != it.proposed.collapse() }
    }
}

private fun seriesText(name: String?, index: Double?): String {
    val series = name?.takeIf { it.isNotBlank() } ?: return ""
    val position = index?.takeIf { it.isFinite() }?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }
    return listOfNotNull(series, position).joinToString(" · ")
}

private fun plainText(raw: String): String =
    if (Regex("<[a-zA-Z/!]").containsMatchIn(raw)) HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).toString() else raw

private fun String.collapse() = trim().replace(Regex("\\s+"), " ")

/** The cover [doc] shows now with [stored]: the found one's address, "own" or "none". */
private fun shownCover(doc: Document, stored: BookDetails?): String =
    stored?.takeUnless { it.useOriginal }?.takeIf { it.showsCover(doc) }?.response?.cover?.url
        ?: if (doc.coverPath != null) "own" else "none"

/** The cover a tidy's answer would show, said as [shownCover] says it. */
private fun proposedCover(doc: Document, cover: DetailsCover): String =
    cover.url ?: if (doc.coverPath != null && (cover.source == ORIGIN_FILE || cover.source == ORIGIN_CATALOG)) "own" else "none"

/** The cover [doc] shows with [details]: the found one's local copy, else the book's own, else none. */
private fun coverNow(doc: Document, details: BookDetails?): String? =
    details?.takeUnless { it.useOriginal }?.takeIf { it.showsCover(doc) }?.coverPath ?: doc.coverPath

/** The changed fields, as a sentence lists them: "title and series". */
private fun fieldList(fields: List<String>): UiText {
    val names = fields.map {
        uiText(
            when (it) {
                FIELD_TITLE -> R.string.details_changed_title
                FIELD_AUTHORS -> R.string.details_changed_authors
                FIELD_SERIES -> R.string.details_changed_series
                else -> R.string.details_changed_description
            },
        )
    }
    return when (names.size) {
        1 -> names[0]
        2 -> uiText(R.string.details_list_two, names[0], names[1])
        3 -> uiText(R.string.details_list_three, names[0], names[1], names[2])
        else -> uiText(R.string.details_list_four, names[0], names[1], names[2], names[3])
    }
}

/** Why "Improve details" did not work, in the reader's words. */
internal fun tidyErrorMessage(e: Throwable): UiText = when {
    e is AiQuotaException -> uiText(R.string.details_allowance_used_until, e.info.resetsAt.take(10))
    e is AiHttpException && e.code == 429 && e.detail == "details_rate_limited" -> uiText(R.string.details_busy)
    e is AiHttpException && e.code == 429 -> uiText(R.string.details_allowance_used)
    e is AiHttpException && e.code == 409 -> uiText(R.string.details_ai_not_available)
    e is AiHttpException && (e.code == 404 || e.code == 503) -> uiText(R.string.details_not_offered)
    e is SocketTimeoutException || e is InterruptedIOException ->
        uiText(R.string.bookdetail_the_server_took_too_long_to_answer)
    e is IOException -> uiText(R.string.bookdetail_couldn_t_reach_the_server_check_the)
    else -> uiText(R.string.details_couldn_t_tidy)
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
