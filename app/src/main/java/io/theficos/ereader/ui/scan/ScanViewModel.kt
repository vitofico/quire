package io.theficos.ereader.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.metadata.Isbn
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.data.library.AffinityIdentity
import io.theficos.ereader.data.library.AffinityRequestBody
import io.theficos.ereader.data.library.AffinityResponse
import io.theficos.ereader.data.library.LibraryHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *      backend / book not scoreable) or a code-0 unreachable/unconfigured
 *      server (split / metadata-only deployment) degrades to a Result with
 *      `affinityUnavailable = true` — the metadata is still useful. A 401
 *      signals stale credentials: [onReauth] is fired and the screen returns
 *      to [ScanUiState.ReauthRequired] so the user has a recoverable, non-
 *      spinning state to act on. Anything else → [ScanUiState.Failed].
 *
 * Collaborators are injected as suspend lambdas so the state machine is
 * unit-testable without real HTTP clients. [lookup] is invoked on
 * [Dispatchers.IO] by this VM because the production OpenLibrary client does a
 * blocking OkHttp call without hopping dispatchers itself; [runAffinity] (the
 * library client) already hops to Dispatchers.IO internally, so the extra hop
 * is harmless for it.
 *
 * Cancellation / staleness: each scan is tagged with a monotonic [generation].
 * A fresh [onIsbnSubmitted] cancels any in-flight job and bumps the generation;
 * every state write after a suspension point is guarded by a generation check,
 * and [CancellationException] is rethrown rather than swallowed. A slow, stale
 * scan therefore can never clobber a newer scan's result — even if its network
 * resolves after the newer one's.
 */
class ScanViewModel(
    private val lookup: suspend (String) -> MetadataBundle?,
    private val runAffinity: suspend (AffinityRequestBody) -> AffinityResponse,
    private val onReauth: () -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    @Volatile private var generation: Long = 0L

    fun onIsbnSubmitted(raw: String) {
        val isbn13 = Isbn.toIsbn13(raw)
        if (isbn13 == null) {
            _state.value = ScanUiState.InvalidIsbn
            return
        }

        scanJob?.cancel()
        _state.value = ScanUiState.Working
        val mine = ++generation
        scanJob = viewModelScope.launch {
            try {
                val bundle = withContext(Dispatchers.IO) { lookup(isbn13) }
                if (mine != generation) return@launch
                if (bundle == null) {
                    _state.value = ScanUiState.NotFound
                    return@launch
                }

                val body = AffinityRequestBody(
                    identity = AffinityIdentity(isbn = isbn13, metadataId = "isbn:$isbn13"),
                    bundle = bundle,
                )
                val next = try {
                    val resp = runAffinity(body)
                    ScanUiState.Result(bundle = bundle, affinity = resp, affinityUnavailable = false)
                } catch (e: LibraryHttpException) {
                    when (e.code) {
                        // 404: no affinity backend / book not scoreable.
                        // 0: the affinity client never reached a server
                        //    (e.g. baseUrl not configured in a metadata-only /
                        //    split deployment). In both cases the OpenLibrary
                        //    metadata we already resolved is still useful, so
                        //    degrade to a Result with affinity unavailable
                        //    rather than hiding it behind a red Failed error.
                        0, 404 -> ScanUiState.Result(
                            bundle = bundle,
                            affinity = null,
                            affinityUnavailable = true,
                        )
                        401 -> {
                            onReauth()
                            // Recoverable, non-spinning state: the re-auth flow
                            // can drive navigation, and if the user stays here
                            // they have an actionable screen to re-scan from.
                            ScanUiState.ReauthRequired
                        }
                        else -> ScanUiState.Failed(e.localizedMessage ?: "Affinity request failed.")
                    }
                }
                if (mine != generation) return@launch
                _state.value = next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (mine != generation) return@launch
                _state.value = ScanUiState.Failed(e.localizedMessage ?: "Something went wrong.")
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

    /**
     * Affinity scoring returned 401 (stale credentials). [onReauth] has been
     * fired; the screen should prompt the user to re-authenticate and offer a
     * re-scan rather than spin indefinitely.
     */
    object ReauthRequired : ScanUiState
    data class Result(
        val bundle: MetadataBundle,
        val affinity: AffinityResponse?,
        val affinityUnavailable: Boolean,
    ) : ScanUiState
    data class Failed(val message: String) : ScanUiState
}
