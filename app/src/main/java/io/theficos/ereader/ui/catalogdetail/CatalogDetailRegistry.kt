package io.theficos.ereader.ui.catalogdetail

import io.theficos.ereader.data.opds.OpdsPublication
import java.util.UUID

/**
 * Transient in-memory map keyed by a short UUID, used to pass an
 * [OpdsPublication] from `CatalogScreen` to `CatalogDetailScreen` without
 * encoding the entire publication into a nav route argument.
 *
 * Lifetime: bound to the process. On process death the registry resets;
 * a navigated detail screen restored from the back stack will get `null`
 * and show a fallback message. That matches the AndroidViewModel back-stack
 * behavior on process death — the catalog screen also re-fetches on resume.
 *
 * Memory cost: O(catalog tiles tapped in this session). Not GC'd until the
 * process exits, but the catalog scope is bounded by the server's feed size
 * so this is acceptable.
 */
class CatalogDetailRegistry {
    private val map = mutableMapOf<String, OpdsPublication>()

    fun put(publication: OpdsPublication): String {
        val key = UUID.randomUUID().toString()
        synchronized(map) { map[key] = publication }
        return key
    }

    fun get(key: String): OpdsPublication? = synchronized(map) { map[key] }
}
