package io.theficos.ereader.ui.scan

import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.data.library.AffinityResponse
import java.util.UUID

/**
 * The data a successful scan hands off to the result screen: the canonical
 * ISBN-13, the resolved metadata, and the affinity verdict (null + the
 * `affinityUnavailable` flag when the server has no affinity backend).
 */
data class ScanResultData(
    val isbn13: String,
    val bundle: MetadataBundle,
    val affinity: AffinityResponse?,
    val affinityUnavailable: Boolean,
)

/**
 * Transient in-memory map keyed by a short UUID, used to pass a
 * [ScanResultData] from `ScanScreen` to `ScanResultScreen` without encoding
 * the whole metadata bundle + affinity payload into a nav route argument.
 *
 * Mirrors `CatalogDetailRegistry`: lifetime is bound to the process; on
 * process death the registry resets and a restored result screen gets `null`
 * and shows a fallback. That's acceptable — a scan is a transient action the
 * user can simply repeat.
 */
class ScanResultRegistry {
    private val map = mutableMapOf<String, ScanResultData>()

    fun put(data: ScanResultData): String {
        val key = UUID.randomUUID().toString()
        synchronized(map) { map[key] = data }
        return key
    }

    fun get(key: String): ScanResultData? = synchronized(map) { map[key] }
}
