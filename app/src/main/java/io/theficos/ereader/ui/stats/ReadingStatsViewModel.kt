package io.theficos.ereader.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.data.library.LibraryHttpException
import io.theficos.ereader.data.library.LibraryStatsResponse
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReadingStatsUiState(val stats: ReadingStats, val recording: Boolean)

/** The library figures Quire's server counts: every book in the library, on every device. */
sealed interface LibraryStatsUiState {
    data object Loading : LibraryStatsUiState
    data class Ready(val stats: LibraryStatsResponse) : LibraryStatsUiState
    data class Error(val message: UiText) : LibraryStatsUiState

    /** No Quire server is set up, so there are no library figures to show. */
    data object NoServer : LibraryStatsUiState
}

/**
 * The last library figures the server sent, and the account they belong to, for the life
 * of the process. It lives on `AppContainer`, not on the view model, so reopening the
 * screen shows them at once while a fresh copy loads.
 */
class LibraryStatsCache {
    @Volatile var lastReady: Pair<String, LibraryStatsResponse>? = null

    /** The figures cached for [owner], never another account's. */
    fun forOwner(owner: String): LibraryStatsResponse? = lastReady?.takeIf { it.first == owner }?.second
}

/**
 * The Statistics screen: reading sessions recorded on this device ([state]) and the
 * server's library figures ([library]).
 *
 * The library figures are fetched on every [loadLibrary] for the signed-in account
 * ([owner], null without a Quire server), showing that account's cached ones meanwhile.
 * A failed fetch keeps cached figures on screen and shows an error only when there are
 * none. Only the latest fetch may change [library], so an earlier one that ends late
 * cannot overwrite it.
 */
class ReadingStatsViewModel(
    private val repository: ReadingStatsRepository,
    private val owner: () -> String?,
    private val fetchLibrary: suspend () -> LibraryStatsResponse,
    private val libraryCache: LibraryStatsCache = LibraryStatsCache(),
    nowMs: () -> Long = System::currentTimeMillis,
    zone: () -> ZoneId = ZoneId::systemDefault,
    firstDayOfWeek: () -> DayOfWeek = { WeekFields.of(Locale.getDefault()).firstDayOfWeek },
    private val activitySync: ReadingActivitySync? = null,
) : ViewModel() {
    val activity = activitySync?.state ?: MutableStateFlow(ReadingActivityState()).asStateFlow()
    private val sessions = activitySync?.let { sync ->
        combine(repository.sessions, activity, sync::merged)
    } ?: repository.sessions
    /** Ticks now and just after each midnight, so a screen left open moves on to the new day. */
    private val days = flow {
        while (true) {
            emit(Unit)
            val now = Instant.ofEpochMilli(nowMs()).atZone(zone())
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            delay(Duration.between(now, midnight).toMillis() + 1_000)
        }
    }

    /**
     * Null until the first computation. Only the computation runs off the main thread: the
     * state itself is published on the main thread. A `flowOn` here would move `stateIn`'s
     * own coroutine to that dispatcher too, so the screen's state would change from a
     * background thread while the screen was composing.
     */
    val state: StateFlow<ReadingStatsUiState?> = combine(
        sessions, repository.bookProgress, repository.wordCounts, repository.recording, days,
    ) { sessions, progress, counts, recording, _ ->
        val stats = withContext(Dispatchers.Default) {
            readingStats(
                sessions = sessions,
                progress = progress,
                wordCounts = counts.associate { it.bookKey to it.words },
                nowMs = nowMs(),
                zone = zone(),
                firstDayOfWeek = firstDayOfWeek(),
            )
        }
        ReadingStatsUiState(stats = stats, recording = recording)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _library = MutableStateFlow(cachedOrLoading(owner()))
    val library: StateFlow<LibraryStatsUiState> = _library.asStateFlow()

    @Volatile private var libraryRequest = 0L

    fun loadLibrary() {
        val account = owner()
        val mine = ++libraryRequest
        _library.value = cachedOrLoading(account)
        if (account == null) {
            libraryCache.lastReady = null
            return
        }
        viewModelScope.launch {
            val result = try {
                LibraryStatsUiState.Ready(fetchLibrary())
            } catch (e: CancellationException) {
                throw e
            } catch (e: LibraryHttpException) {
                LibraryStatsUiState.Error(messageForHttp(e.code))
            } catch (e: Throwable) {
                LibraryStatsUiState.Error(uiText(R.string.library_couldn_t_reach_the_server_2, e.message ?: ""))
            }
            if (mine != libraryRequest) return@launch
            if (result is LibraryStatsUiState.Ready) libraryCache.lastReady = account to result.stats
            if (result is LibraryStatsUiState.Ready || libraryCache.forOwner(account) == null) _library.value = result
        }
    }

    fun refreshActivity() { activitySync?.refresh() }

    fun syncActivity(enabled: Boolean) {
        viewModelScope.launch { activitySync?.setEnabled(enabled) }
    }

    fun clear() {
        viewModelScope.launch {
            if (activitySync != null && activity.value.enabled && activity.value.owner == owner()) activitySync.clear()
            else repository.clear()
        }
    }

    private fun cachedOrLoading(account: String?): LibraryStatsUiState = when (account) {
        null -> LibraryStatsUiState.NoServer
        else -> libraryCache.forOwner(account)?.let { LibraryStatsUiState.Ready(it) } ?: LibraryStatsUiState.Loading
    }

    private fun messageForHttp(code: Int): UiText = when (code) {
        401 -> uiText(R.string.library_sign_in_to_your_calibre_web_instance)
        404 -> uiText(R.string.library_library_stats_aren_t_available_on_this)
        else -> uiText(R.string.library_couldn_t_load_stats_http, code)
    }
}
