package io.theficos.ereader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.data.ai.AiConfig
import io.theficos.ereader.data.ai.AiHttpException
import io.theficos.ereader.data.ai.AiQuotaException
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.ai.PreflightOutcome
import io.theficos.ereader.data.ai.ReaderProfileResponseDto
import io.theficos.ereader.data.library.LibraryClient
import io.theficos.ereader.data.library.LibraryStatsResponse
import io.theficos.ereader.data.local.db.ProgressDao
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant

/**
 * PR-γ — Reader Profile / Library Insights screen.
 *
 * Sealed-state shape per coordinator §3.5 and corrections.md PR-γ Required #5.
 * Disabled is a sealed family (locked copy on each variant); Loading has
 * exactly two substates; Error has distinct kinds; 409 ai_not_opted_in maps to
 * Disabled.OptedOut (NOT Error) and 503 progress-unsupported maps to
 * Disabled.ProgressUnsupported.
 */
sealed interface LibraryInsightsUiState {

    /** Disabled state family — three distinct reasons with locked copy. */
    sealed interface Disabled : LibraryInsightsUiState {
        val message: String

        data object AiOff : Disabled {
            override val message: String =
                "AI insights are turned off. Enable in Settings → AI to view or generate your reader profile."
        }

        data object OptedOut : Disabled {
            override val message: String =
                "Reader profile is off. Opt in from Settings → AI to generate your profile."
        }

        data object ProgressUnsupported : Disabled {
            override val message: String =
                "Library mirror isn't enabled on this server. Connect your library first."
        }
    }

    /** First-load state. `statsPreview == null` while /library/v1/stats is in flight. */
    data class Empty(
        val configHostLabel: String,
        val modelId: String,
        val statsPreview: ReaderStatsPreview?,
    ) : LibraryInsightsUiState

    /** Exactly two substates per corrections.md PR-γ Required #5. */
    sealed interface Loading : LibraryInsightsUiState {
        data object PreflightSyncing : Loading
        data object Generating : Loading
    }

    /** Loaded — main render path. */
    data class Loaded(
        val profile: ReaderProfileResponseDto,
        val stats: LibraryStatsResponse?,
        val stale: Boolean,
        val refreshedAt: Instant?,
        val preflightFailed: Boolean,
        val configHostLabel: String,
        val refreshing: Boolean = false,
    ) : LibraryInsightsUiState

    /** Distinct error kinds (architect Finding #5). */
    sealed interface Error : LibraryInsightsUiState {
        val retryable: Boolean

        data class Network(val detail: String) : Error {
            override val retryable: Boolean = true
        }

        data class RateLimit(val resetHint: String?) : Error {
            override val retryable: Boolean = false
        }

        data object ModelFailure : Error {
            override val retryable: Boolean = true
        }

        data object Unknown : Error {
            override val retryable: Boolean = true
        }
    }
}

/**
 * Tiny preview struct so the Empty screen can render live counts while the
 * profile is still un-generated. Lifted from /library/v1/stats; never persisted.
 */
data class ReaderStatsPreview(
    val finished: Int,
    val inProgress: Int,
    val totalBooks: Int,
    val topAuthors: List<String>,
)

private fun LibraryStatsResponse.toPreview(): ReaderStatsPreview = ReaderStatsPreview(
    finished = finishedCount,
    inProgress = inProgressCount,
    totalBooks = totalBooks,
    topAuthors = topAuthors.take(3).map { it.name },
)

/**
 * Drives the Library Insights screen. Pulls /ai/v1/profile + /library/v1/stats
 * concurrently on [reload]; calls /ai/v1/profile/refresh through
 * [AiRepository.refreshProfile] on [refresh] (best-effort preflight per PR-γ
 * Required #6).
 *
 * The injected flows (`aiConfigFlow` / `aiEnabledFlow`) are point-in-time
 * sampled — the screen does NOT live-observe them. A toggle in Settings → AI
 * takes effect on the next [reload]. This matches the existing Library Stats
 * pattern (one-shot loader).
 */
class LibraryInsightsViewModel(
    private val ai: AiRepository,
    private val libraryClient: LibraryClient,
    private val progressDao: ProgressDao,
    private val aiConfigFlow: StateFlow<AiConfig?>,
    private val aiEnabledFlow: StateFlow<Boolean>,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {

    private val _state =
        MutableStateFlow<LibraryInsightsUiState>(LibraryInsightsUiState.Loading.Generating)
    val state: StateFlow<LibraryInsightsUiState> = _state.asStateFlow()

    /** Pull config + profile + stats. Decides Disabled vs Empty vs Loaded. */
    fun reload() {
        viewModelScope.launch { reloadInternal() }
    }

    /** Triggered by Empty.Generate or Loaded.Refresh button. */
    fun refresh() {
        viewModelScope.launch { refreshInternal() }
    }

    private suspend fun reloadInternal() {
        if (!aiEnabledFlow.value) {
            _state.value = LibraryInsightsUiState.Disabled.AiOff
            return
        }
        val config = aiConfigFlow.value
        if (config == null || !config.configured) {
            _state.value = LibraryInsightsUiState.Disabled.AiOff
            return
        }
        if (!config.progressSupported) {
            _state.value = LibraryInsightsUiState.Disabled.ProgressUnsupported
            return
        }

        // Fetch profile + stats concurrently.
        val (profileResult, statsResult) = coroutineScope {
            val p = async { runCatching { ai.fetchProfile() } }
            val s = async { runCatching { libraryClient.getStats() } }
            p.await() to s.await()
        }
        val stats = statsResult.getOrNull()

        profileResult.exceptionOrNull()?.let {
            mapErrorToState(it)
            return
        }
        val profile = profileResult.getOrNull()

        if (profile == null) {
            _state.value = LibraryInsightsUiState.Empty(
                configHostLabel = config.baseUrlHost.orEmpty(),
                modelId = config.modelId.orEmpty(),
                statsPreview = stats?.toPreview(),
            )
        } else {
            _state.value = LibraryInsightsUiState.Loaded(
                profile = profile,
                stats = stats,
                stale = computeStaleness(profile, stats),
                refreshedAt = null,
                preflightFailed = false,
                configHostLabel = config.baseUrlHost.orEmpty(),
            )
        }
    }

    private suspend fun refreshInternal() {
        val before = (_state.value as? LibraryInsightsUiState.Loaded)?.profile
        val config = aiConfigFlow.value
        if (config == null || !config.configured) {
            _state.value = LibraryInsightsUiState.Disabled.AiOff
            return
        }

        // Architect Finding #1: skeleton ONLY on initial load. During refresh,
        // keep current Loaded visible but disable Refresh (refreshing=true).
        if (before == null) {
            _state.value = LibraryInsightsUiState.Loading.PreflightSyncing
        } else {
            _state.value = (_state.value as LibraryInsightsUiState.Loaded)
                .copy(refreshing = true)
        }

        var preflightFailed = false
        try {
            val fresh = ai.refreshProfile(
                onPreflightStart = {
                    if (before == null) {
                        _state.value = LibraryInsightsUiState.Loading.PreflightSyncing
                    }
                },
                onPreflightDone = { outcome: PreflightOutcome ->
                    preflightFailed = outcome.anyFailed
                    if (before == null) {
                        _state.value = LibraryInsightsUiState.Loading.Generating
                    }
                },
            )
            val stats = runCatching { libraryClient.getStats() }.getOrNull()
            _state.value = LibraryInsightsUiState.Loaded(
                profile = fresh,
                stats = stats,
                stale = computeStaleness(fresh, stats),
                refreshedAt = clock(),
                preflightFailed = preflightFailed,
                configHostLabel = config.baseUrlHost.orEmpty(),
            )
        } catch (t: Throwable) {
            mapErrorToState(t)
        }
    }

    internal fun mapErrorToState(t: Throwable) {
        _state.value = when {
            t is AiHttpException && t.code == 409 ->
                LibraryInsightsUiState.Disabled.OptedOut
            t is AiHttpException && t.code == 503 ->
                LibraryInsightsUiState.Disabled.ProgressUnsupported
            t is AiQuotaException ->
                LibraryInsightsUiState.Error.RateLimit(resetHint = t.info.resetsAt)
            t is AiHttpException && t.code in 500..599 ->
                LibraryInsightsUiState.Error.ModelFailure
            t is IOException ->
                LibraryInsightsUiState.Error.Network(t.message ?: "Couldn't reach the server")
            else ->
                LibraryInsightsUiState.Error.Unknown
        }
    }

    /**
     * Lock #12 (corrections.md PR-γ Required #1): compute a local approximation
     * of the server's input_fingerprint. Mismatch → soft "Profile may be out
     * of date" hint (architect Finding #2). Returns false when we can't
     * compute (no stats or server emitted no fingerprint) so the banner stays
     * hidden by default.
     *
     * Server recipe (mirrored from PR-β's _compute_input_fingerprint):
     *   sha256("{finished}|{in_progress}|{abandoned}|{latest_progress_iso}|
     *           {library_items_count}|{books_with_themes_count}").take(16)
     */
    private suspend fun computeStaleness(
        profile: ReaderProfileResponseDto,
        stats: LibraryStatsResponse?,
    ): Boolean {
        val serverFp = profile.inputFingerprint ?: return false
        val localFp = computeLocalFingerprint(profile, stats) ?: return false
        return serverFp != localFp
    }

    internal suspend fun computeLocalFingerprint(
        profile: ReaderProfileResponseDto,
        stats: LibraryStatsResponse?,
    ): String? {
        if (stats == null) return null
        val latestProgressMs = progressDao.maxUpdatedAt()
        val latestProgressIso = latestProgressMs?.let { Instant.ofEpochMilli(it).toString() }.orEmpty()
        val seed = buildString {
            append(profile.payload.stats.finishedCount); append('|')
            append(profile.payload.stats.inProgressCount); append('|')
            append(profile.payload.stats.abandonedCount); append('|')
            append(latestProgressIso); append('|')
            append(stats.totalBooks); append('|')
            // Lock #15 / CC-8: NOT `topThemes.size`.
            append(profile.payload.stats.booksWithThemesCount)
        }
        return sha256Hex(seed).take(16)
    }
}

internal fun sha256Hex(input: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4]); sb.append(HEX[v and 0x0F])
    }
    return sb.toString()
}

private val HEX = "0123456789abcdef".toCharArray()
