package io.theficos.ereader.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.metadata.Isbn
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.data.library.AffinityIdentity
import io.theficos.ereader.data.library.AffinityRequestBody
import io.theficos.ereader.data.library.AffinityResponse
import io.theficos.ereader.data.library.LibraryHttpException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the "Scan a book" screen.
 *
 * Flow of a single scan (one [onIsbnSubmitted] call):
 *   1. Canonicalise the raw input via [Isbn.toIsbn13]; a non-ISBN yields
 *      [ScanUiState.InvalidIsbn] and stops.
 *   2. Emit [ScanUiState.Working] and launch on `viewModelScope`.
 *   3. [lookup] the ISBN-13 against an external metadata source (OpenLibrary).
 *      A miss yields [ScanUiState.NotFound].
 *   4. Ask the Quire server to score the book via [runAffinity]. Success →
 *      [ScanUiState.Result] with the affinity attached. A 404 (no affinity
 *      backend / book not scoreable) degrades to a Result with
 *      `affinityUnavailable = true` — the metadata is still useful. A 401
 *      signals stale credentials and triggers [onReauth]. Anything else →
 *      [ScanUiState.Failed].
 *
 * Collaborators are injected as suspend lambdas so the state machine is
 * unit-testable without real HTTP clients. Blocking IO is expected to be
 * dispatched by the supplied lambdas (e.g. the OpenLibrary client / library
 * client already hop to Dispatchers.IO internally).
 *
 * Cancellation: a fresh [onIsbnSubmitted] cancels any in-flight scan job, so
 * rapid re-scans never race a stale result onto the screen.
 */
class ScanViewModel(
    private val lookup: suspend (String) -> MetadataBundle?,
    private val runAffinity: suspend (AffinityRequestBody) -> AffinityResponse,
    private val onReauth: () -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    fun onIsbnSubmitted(raw: String) {
        val isbn13 = Isbn.toIsbn13(raw)
        if (isbn13 == null) {
            _state.value = ScanUiState.InvalidIsbn
            return
        }

        scanJob?.cancel()
        _state.value = ScanUiState.Working
        scanJob = viewModelScope.launch {
            val bundle = lookup(isbn13)
            if (bundle == null) {
                _state.value = ScanUiState.NotFound
                return@launch
            }

            val body = AffinityRequestBody(
                identity = AffinityIdentity(isbn = isbn13, metadataId = "isbn:$isbn13"),
                bundle = bundle,
            )
            _state.value = try {
                val resp = runAffinity(body)
                ScanUiState.Result(bundle = bundle, affinity = resp, affinityUnavailable = false)
            } catch (e: LibraryHttpException) {
                when (e.code) {
                    404 -> ScanUiState.Result(
                        bundle = bundle,
                        affinity = null,
                        affinityUnavailable = true,
                    )
                    401 -> {
                        onReauth()
                        // Leave the user on Working; the re-auth flow drives
                        // navigation and they can re-scan once re-authenticated.
                        ScanUiState.Working
                    }
                    else -> ScanUiState.Failed(e.localizedMessage ?: "Affinity request failed.")
                }
            } catch (e: Throwable) {
                ScanUiState.Failed(e.localizedMessage ?: "Something went wrong.")
            }
        }
    }
}

/** UI state for the scan screen. Sealed so the screen can `when`-exhaust. */
sealed interface ScanUiState {
    object Idle : ScanUiState
    object Working : ScanUiState
    object InvalidIsbn : ScanUiState
    object NotFound : ScanUiState
    data class Result(
        val bundle: MetadataBundle,
        val affinity: AffinityResponse?,
        val affinityUnavailable: Boolean,
    ) : ScanUiState
    data class Failed(val message: String) : ScanUiState
}
