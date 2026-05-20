package io.theficos.ereader.data.ai

import io.theficos.ereader.core.model.DocumentIdentity
import java.util.concurrent.ConcurrentHashMap

/**
 * One entry of [CatalogInsightStash]: the catalog-side identity used when
 * the user first viewed an OPDS publication's insight, plus the (tone,
 * language) tuple in effect at view time so the post-download promote
 * targets the same cache-key variant.
 */
data class CatalogInsightStashEntry(
    val catalogIdentity: DocumentIdentity,
    val tone: String,
    val language: String,
    val storedAt: Long = System.currentTimeMillis(),
)

/**
 * PR-ζ / Lock #16 — process-local stash holding the catalog-side identity
 * used when the user viewed an OPDS publication's insight pre-download. The
 * download-success path consults this stash to compute the `from` argument
 * for `aiRepository.promoteInsight()`.
 *
 * Lifecycle safeguards:
 *  - TTL (default 30 min): matches the typical catalog-view → download
 *    window; older entries are cold and the insight will be re-fetched
 *    anyway.
 *  - Partitioned by `(accountSubject, opdsHref)` to defend against
 *    warm-process cross-account bleed.
 *  - `peek` does NOT remove; `remove` only runs on terminal SUCCESS of
 *    the promote call so a failed promote retries on the next download
 *    attempt rather than silently losing the stash.
 *  - `clearAll()` hooks live in [AppContainer]: on AI opt-out toggle
 *    (owned by PR-δ Bundle 3) and on base-URL change.
 *
 * In-memory only — process restart drops the stash; cost is "same as
 * today" (book regenerates on first open).
 */
class CatalogInsightStash(
    private val ttlMillis: Long = 30L * 60L * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Key(val subject: String, val href: String)

    private val entries = ConcurrentHashMap<Key, CatalogInsightStashEntry>()

    /**
     * Stash (or overwrite) the catalog-side entry for [subject] + [href].
     * Overwrite is intentional: a user reopening the catalog page picks up
     * any updated tone/language preference and we want the most recent
     * variant to be promoted.
     */
    fun stash(subject: String, href: String, entry: CatalogInsightStashEntry) {
        entries[Key(subject, href)] = entry
    }

    /**
     * Read the stashed entry. Returns null when missing or expired (and
     * removes the expired row eagerly so subsequent calls don't re-check).
     * Does NOT remove on a fresh hit — that's the caller's job on success.
     */
    fun peek(subject: String, href: String): CatalogInsightStashEntry? {
        val k = Key(subject, href)
        val e = entries[k] ?: return null
        if (clock() - e.storedAt > ttlMillis) {
            entries.remove(k, e)
            return null
        }
        return e
    }

    /** Remove the entry for [subject] + [href]. No-op when absent. */
    fun remove(subject: String, href: String) {
        entries.remove(Key(subject, href))
    }

    /**
     * Drop everything. Wired to the AI opt-out toggle (PR-δ) and the
     * server base-URL change hook in [AppContainer].
     */
    fun clearAll() {
        entries.clear()
    }
}
