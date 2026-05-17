package io.theficos.ereader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.data.library.LibraryHttpException
import io.theficos.ereader.data.library.LibraryStatsResponse
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
 * One-shot loader for the library stats endpoint. Re-runnable via [load] (the
 * Stats screen's retry button calls it).
 *
 * Injected `fetch` lambda keeps the ViewModel purely testable — production
 * binds it to `libraryClient::getStats`.
 */
class LibraryStatsViewModel(
    private val fetch: suspend () -> LibraryStatsResponse,
) : ViewModel() {

    private val _state = MutableStateFlow<LibraryStatsUiState>(LibraryStatsUiState.Loading)
    val state: StateFlow<LibraryStatsUiState> = _state.asStateFlow()

    fun load() {
        _state.value = LibraryStatsUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                LibraryStatsUiState.Ready(fetch())
            } catch (e: LibraryHttpException) {
                LibraryStatsUiState.Error(
                    when (e.code) {
                        401 -> "Sign in to your calibre-web instance to see stats."
                        404 -> "Library stats aren't available on this server."
                        else -> "Couldn't load stats (HTTP ${e.code})."
                    }
                )
            } catch (e: Throwable) {
                LibraryStatsUiState.Error(
                    "Couldn't reach the server: ${e.message ?: "unknown error"}."
                )
            }
        }
    }
}
