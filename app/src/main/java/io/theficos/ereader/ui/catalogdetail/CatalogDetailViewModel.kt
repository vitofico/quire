package io.theficos.ereader.ui.catalogdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.ai.AiConfig
import io.theficos.ereader.data.ai.AiHttpException
import io.theficos.ereader.data.ai.AiPreferences
import io.theficos.ereader.data.ai.AiQuotaException
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.ai.BookInsightResponse
import io.theficos.ereader.data.ai.CatalogInsightStash
import io.theficos.ereader.data.ai.CatalogInsightStashEntry
import io.theficos.ereader.data.opds.OpdsPublication
import io.theficos.ereader.ui.bookdetail.InsightUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Narrow adapter over [AiRepository] used by [CatalogDetailViewModel]. Lets
 * the viewmodel be tested without standing up the real repository (which
 * needs a configured `AiClient` and OkHttp). Production wiring in
 * `AppContainer` passes [AiRepositoryAdapter] which forwards to the real
 * [AiRepository].
 */
interface CatalogAiPort {
    val configFlow: StateFlow<AiConfig?>
    val prefsFlow: StateFlow<AiPreferences?>
    suspend fun getCachedInsight(identity: DocumentIdentity): BookInsightResponse?
    suspend fun lookupInsight(
        identity: DocumentIdentity,
        bundle: MetadataBundle,
    ): BookInsightResponse
}

class AiRepositoryAdapter(private val repo: AiRepository) : CatalogAiPort {
    override val configFlow get() = repo.config
    override val prefsFlow get() = repo.preferences
    override suspend fun getCachedInsight(identity: DocumentIdentity) =
        repo.getCachedInsight(identity)
    override suspend fun lookupInsight(
        identity: DocumentIdentity,
        bundle: MetadataBundle,
    ) = repo.lookupInsight(identity, bundle)
}

data class CatalogDetailState(
    val publication: OpdsPublication,
    val insight: InsightUiState = InsightUiState.Hidden,
)

class CatalogDetailViewModel(
    private val publication: OpdsPublication,
    private val ai: CatalogAiPort,
    private val insightStash: CatalogInsightStash? = null,
    private val subjectProvider: () -> String? = { null },
) : ViewModel() {

    private val _state = MutableStateFlow(CatalogDetailState(publication = publication))
    val state: StateFlow<CatalogDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val cfg = ai.configFlow.value
        val prefs = ai.prefsFlow.value
        if (cfg?.configured != true || prefs?.aiEnabled != true) {
            _state.value = _state.value.copy(insight = InsightUiState.Hidden)
            return
        }

        val identity = buildIdentity(publication)
        val bundle = MetadataBundle(title = publication.title, author = publication.author)

        val cached = runCatching { ai.getCachedInsight(identity) }.getOrNull()
        if (cached != null) {
            _state.value = _state.value.copy(
                insight = InsightUiState.Loaded(cached.payload, cached.sources),
            )
            recordStashIfPossible(identity, prefs)
            return
        }

        _state.value = _state.value.copy(insight = InsightUiState.Loading)
        runCatching { ai.lookupInsight(identity, bundle) }
            .onSuccess { resp ->
                _state.value = _state.value.copy(
                    insight = InsightUiState.Loaded(resp.payload, resp.sources),
                )
                recordStashIfPossible(identity, prefs)
            }
            .onFailure { e ->
                val msg = when {
                    e is AiQuotaException ->
                        "You've reached today's regeneration limit. Try again after ${e.info.resetsAt.take(10)}."
                    e is AiHttpException && e.code == 429 ->
                        "You've reached today's regeneration limit. Try again tomorrow."
                    e is AiHttpException -> "Couldn't generate insights (${e.code})."
                    else -> "Couldn't generate insights."
                }
                _state.value = _state.value.copy(insight = InsightUiState.Error(msg))
            }
    }

    /**
     * PR-ζ / Lock #16: record the catalog identity + (tone, language) in
     * the process-local stash so the post-download promote can find it.
     * No-ops when the stash or subject is missing — production wiring
     * always supplies both; some tests construct the VM without them.
     */
    private fun recordStashIfPossible(
        identity: DocumentIdentity,
        prefs: AiPreferences?,
    ) {
        val stash = insightStash ?: return
        val subject = subjectProvider() ?: return
        val style = prefs?.style
        stash.stash(
            subject = subject,
            href = publication.epubDownloadHref,
            entry = CatalogInsightStashEntry(
                catalogIdentity = identity,
                tone = style?.tone ?: "neutral",
                language = style?.language ?: "auto",
            ),
        )
    }

    companion object {
        /**
         * Build the AI identity payload for a pre-download publication.
         *
         * Strategy (spec §"Identity strategy", verified against
         * server/quire_server/core/ai/service.py::generate at line 200):
         *  - `metadata_id = "opds-href:" + sha256Hex(epubDownloadHref)` —
         *    a canonical so the server's `generate()` proceeds instead of
         *    raising `IdentityUnresolvable` (which it does for alias-only
         *    payloads). The `opds-href:` prefix keeps the value identifiable
         *    in `book_insights.metadata_id`.
         *  - `opds_href` mirrors `metadata_id` so the server's
         *    `reconcile_aliases` writes an alias row of scheme `opds_href`
         *    pointing at the canonical metadata_id (lets a future request
         *    sent under `opds_href` alone resolve via the alias table).
         *  - `opds_dc_id`, `calibre_book_id`, `isbn` — alias hints for the
         *    server to record when the OPDS entry exposes them. Calibre-web's
         *    stock template emits none of these.
         *  - `content_hash` stays null; the server synthesizes
         *    `synthetic:metadata_id:opds-href:<sha>` via
         *    `_synthetic_content_hash`.
         *
         * Note: this catalog row does NOT converge with the post-download
         * row (different identifier spaces — calibre-web's OPDS uuid vs the
         * EPUB OPF dc:identifier). Convergence requires a server-side
         * promote endpoint, tracked as a follow-up.
         */
        fun buildIdentity(publication: OpdsPublication): DocumentIdentity {
            val opdsHrefValue = "opds-href:" + sha256Hex(publication.epubDownloadHref)
            return DocumentIdentity(
                metadataId = opdsHrefValue,
                opdsHref = opdsHrefValue,
                opdsDcId = publication.opdsDcId,
                calibreBookId = publication.calibreBookId,
            )
        }

        private fun sha256Hex(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xff
                sb.append(HEX[v ushr 4])
                sb.append(HEX[v and 0x0f])
            }
            return sb.toString()
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
