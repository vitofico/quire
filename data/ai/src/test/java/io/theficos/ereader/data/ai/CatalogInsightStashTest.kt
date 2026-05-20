package io.theficos.ereader.data.ai

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.DocumentIdentity
import org.junit.Test

/**
 * PR-ζ / Lock #16: process-local stash for catalog → download promote.
 *
 * Covers:
 *  - TTL expiry purges entries on peek.
 *  - Partition by (subject, href): same href under different subjects is
 *    isolated.
 *  - `peek` does NOT remove on a hit (caller controls remove timing).
 *  - `remove` is a no-op on absent keys.
 *  - `clearAll` drops everything.
 */
class CatalogInsightStashTest {

    private val ident = DocumentIdentity(metadataId = "opds-href:abc")
    private val entry = CatalogInsightStashEntry(
        catalogIdentity = ident,
        tone = "neutral",
        language = "auto",
    )

    @Test
    fun peek_returns_stored_entry_without_removing_it() {
        val stash = CatalogInsightStash()
        stash.stash("alice", "href-1", entry)
        assertThat(stash.peek("alice", "href-1")).isEqualTo(entry)
        // Second peek still returns the entry (not consumed).
        assertThat(stash.peek("alice", "href-1")).isEqualTo(entry)
    }

    @Test
    fun peek_returns_null_when_missing() {
        val stash = CatalogInsightStash()
        assertThat(stash.peek("alice", "href-x")).isNull()
    }

    @Test
    fun peek_returns_null_after_ttl_expiry() {
        var now = 1_000L
        val stash = CatalogInsightStash(ttlMillis = 100L, clock = { now })
        stash.stash("alice", "href-1", entry.copy(storedAt = now))
        // Advance time past the TTL.
        now += 200L
        assertThat(stash.peek("alice", "href-1")).isNull()
        // And the expired entry is gone from the map.
        now += 1L
        assertThat(stash.peek("alice", "href-1")).isNull()
    }

    @Test
    fun partition_by_subject_and_href() {
        val stash = CatalogInsightStash()
        stash.stash("alice", "href-1", entry)
        // bob has not stashed anything under href-1.
        assertThat(stash.peek("bob", "href-1")).isNull()
        // Same subject different href: also null.
        assertThat(stash.peek("alice", "href-2")).isNull()
    }

    @Test
    fun remove_drops_entry_and_is_idempotent_on_missing() {
        val stash = CatalogInsightStash()
        stash.stash("alice", "href-1", entry)
        stash.remove("alice", "href-1")
        assertThat(stash.peek("alice", "href-1")).isNull()
        // Removing again does not throw.
        stash.remove("alice", "href-1")
    }

    @Test
    fun clearAll_drops_every_entry() {
        val stash = CatalogInsightStash()
        stash.stash("alice", "href-1", entry)
        stash.stash("bob", "href-2", entry)
        stash.clearAll()
        assertThat(stash.peek("alice", "href-1")).isNull()
        assertThat(stash.peek("bob", "href-2")).isNull()
    }

    @Test
    fun stash_overwrites_existing_entry_for_same_key() {
        val stash = CatalogInsightStash()
        val older = entry.copy(tone = "neutral")
        val newer = entry.copy(tone = "scholarly")
        stash.stash("alice", "href-1", older)
        stash.stash("alice", "href-1", newer)
        assertThat(stash.peek("alice", "href-1")?.tone).isEqualTo("scholarly")
    }
}
