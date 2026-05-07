package io.theficos.ereader.reader

import io.theficos.ereader.core.model.Progress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

class ProgressTracker(
    private val save: suspend (Progress) -> Unit,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = 1_000L,
) {
    private val pending = MutableStateFlow<Pending?>(null)
    private var collectJob: Job? = null
    private var debounceJob: Job? = null
    private var documentId: Long = -1L

    fun attach(documentId: Long, locatorUpdates: Flow<Locator>) {
        this.documentId = documentId
        collectJob = scope.launch {
            locatorUpdates.collect { locator ->
                pending.value = Pending(locator, nowMs())
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(debounceMs)
                    flushOnce()
                }
            }
        }
    }

    fun detach() {
        debounceJob?.cancel()
        runBlocking { flushOnce() }
        collectJob?.cancel()
    }

    private suspend fun flushOnce() {
        val p = pending.value ?: return
        pending.value = null
        save(Progress(
            documentId = documentId,
            locator = serialize(p.locator),
            percent = (p.locator.locations.totalProgression
                ?: p.locator.locations.progression
                ?: 0.0).coerceIn(0.0, 1.0),
            updatedAt = p.timestampMs,
        ))
    }

    private data class Pending(val locator: Locator, val timestampMs: Long)

    companion object {
        /** Encodes a Readium [Locator] as a JSON string for persistence and (Phase 2) sync. */
        fun serialize(locator: Locator): String =
            locator.toJSON().toString()

        /**
         * Returns a [Locator] reconstituted from a previously-[serialize]d string, or `null` if
         * the input is the Phase 1 legacy format, malformed JSON, or otherwise un-parseable.
         */
        fun parseOrNull(raw: String): Locator? = try {
            val json = JSONObject(raw)
            // Legacy Phase 1 stub wrote {"href":..., "percent":...} — no "locations" object.
            if (json.has("percent") && !json.has("locations")) null
            else Locator.fromJSON(json)
        } catch (_: Throwable) {
            null
        }
    }
}
