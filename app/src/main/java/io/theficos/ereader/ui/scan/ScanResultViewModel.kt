package io.theficos.ereader.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.ai.AiHttpException
import io.theficos.ereader.data.ai.AiQuotaException
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.ui.bookdetail.InsightUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the scan-result screen.
 *
 * The metadata + affinity verdict come pre-resolved from the scan flow (held
 * in [data]); this VM owns only the on-demand "Get full insight" action. That
 * action calls [AiRepository.lookupInsight] with an `isbn:<isbn13>` identity —
 * the same identity the scan used for affinity, so a later library download of
 * the same book promotes onto the cached insight row.
 *
 * Insight is hidden until the user asks for it (cost gate) and when AI is not
 * configured / opted out, mirroring the book-detail screen's gating.
 */
class ScanResultViewModel(
    val data: ScanResultData,
    private val ai: AiRepository,
) : ViewModel() {

    private val _insight = MutableStateFlow<InsightUiState>(InsightUiState.Hidden)
    val insight: StateFlow<InsightUiState> = _insight.asStateFlow()

    /** True when AI is configured and the user hasn't opted out. */
    fun insightAvailable(): Boolean {
        val cfg = ai.config.value
        val pref = ai.preferences.value
        return cfg?.configured == true && pref?.aiEnabled == true
    }

    fun loadInsight() {
        if (!insightAvailable()) {
            _insight.value = InsightUiState.Hidden
            return
        }
        val isbn13 = data.isbn13.takeIf { it.isNotBlank() } ?: run {
            _insight.value = InsightUiState.Error("Couldn't generate insights.")
            return
        }
        val identity = DocumentIdentity(metadataId = "isbn:$isbn13", isbn = isbn13)
        _insight.value = InsightUiState.Loading
        viewModelScope.launch {
            runCatching { ai.lookupInsight(identity, data.bundle) }
                .onSuccess { resp ->
                    _insight.value = InsightUiState.Loaded(resp.payload, resp.sources)
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
                    _insight.value = InsightUiState.Error(msg)
                }
        }
    }

    fun retryInsight() = loadInsight()
}
