package io.theficos.ereader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.data.library.LibraryHttpException
import io.theficos.ereader.data.library.LibraryStatsResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LibraryStatsUiState {
    data object Loading : LibraryStatsUiState
    data class Ready(val stats: LibraryStatsResponse) : LibraryStatsUiState
    data class Error(val message: String) : LibraryStatsUiState
}

/**
 * One-shot loader for the library stats endpoint, with stale-while-revalidate
 * caching across ViewModel instances within the same process.
 *
 * Behaviour on each [load] call:
 *  1. If `lastReady != null`, emit `Ready(cached)` immediately — no spinner
 *     flash on screen re-entry.
 *  2. Otherwise emit `Loading`.
 *  3. Always kick off a background fetch. Stats are cheap; the correct
 *     default is "every screen open is a fresh fetch".
 *  4. On success: update the cache and emit `Ready(fresh)`.
 *  5. On failure WITH cached data: keep the cached `Ready` visible (silent
 *     refresh failure — the user keeps seeing their data). No new error UI
 *     in PR-9.
 *  6. On failure WITHOUT cached data: emit `Error` with code-specific copy
 *     (existing behaviour preserved).
 *
 * Cache scope is process / VM lifetime — cleared on process kill, which is
 * fine: stats are cheap to refetch on cold start. No Room, no DataStore, no
 * SavedStateHandle. The injected `fetch` lambda keeps the ViewModel purely
 * testable; production binds it to `libraryClient::getStats`.
 */
class LibraryStatsViewModel(
    private val fetch: suspend () -> LibraryStatsResponse,
) : ViewModel() {

    private val _state = MutableStateFlow<LibraryStatsUiState>(LibraryStatsUiState.Loading)
    val state: StateFlow<LibraryStatsUiState> = _state.asStateFlow()

    @Volatile private var lastReady: LibraryStatsResponse? = null

    fun load() {
        val cached = lastReady
        _state.value = if (cached != null) {
            LibraryStatsUiState.Ready(cached)
        } else {
            LibraryStatsUiState.Loading
        }

        // Always refetch — stats are cheap; SWR keeps the UI fresh.
        viewModelScope.launch {
            try {
                val fresh = fetch()
                lastReady = fresh
                _state.value = LibraryStatsUiState.Ready(fresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LibraryHttpException) {
                // Silent failure if we have cached data; otherwise surface
                // the existing code-specific copy.
                if (cached == null) {
                    _state.value = LibraryStatsUiState.Error(messageForHttp(e.code))
                }
            } catch (e: Throwable) {
                if (cached == null) {
                    _state.value = LibraryStatsUiState.Error(
                        "Couldn't reach the server: ${e.message ?: "unknown error"}."
                    )
                }
            }
        }
    }

    private fun messageForHttp(code: Int): String = when (code) {
        401 -> "Sign in to your calibre-web instance to see stats."
        404 -> "Library stats aren't available on this server."
        else -> "Couldn't load stats (HTTP $code)."
    }
}
