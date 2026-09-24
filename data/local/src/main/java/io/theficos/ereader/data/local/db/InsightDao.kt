package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * PR-η: DAO for the local insight cache. See [InsightEntity] for the key
 * shape. The only eviction is [deleteForBook], run when the user invalidates
 * a book's insight; there is still no `deleteAll()`.
 */
@Dao
interface InsightDao {
    /** Exact-identity read; the local-first lookup path. */
    @Query(
        "SELECT * FROM book_insights " +
            "WHERE identityKey = :identityKey " +
            "AND modelId = :modelId " +
            "AND promptVersion = :promptVersion " +
            "AND tone = :tone " +
            "AND language = :language " +
            "LIMIT 1"
    )
    suspend fun getByIdentity(
        identityKey: String,
        modelId: String,
        promptVersion: String,
        tone: String,
        language: String,
    ): InsightEntity?

    /**
     * Narrow offline fallback: any cached row for the identity, regardless of
     * `(modelId, promptVersion, tone, language)`. Returned row is the most
     * recently generated so the user sees the freshest stale data.
     */
    @Query(
        "SELECT * FROM book_insights " +
            "WHERE identityKey = :identityKey " +
            "ORDER BY generatedAt DESC " +
            "LIMIT 1"
    )
    suspend fun findAnyForIdentity(identityKey: String): InsightEntity?

    /**
     * Issue #102: drop every cached row for one book, across all models,
     * prompt versions, tones and languages, so neither the exact read nor
     * the [findAnyForIdentity] fallback can bring an invalidated insight
     * back. A row may be filed under either identity, so both the key and
     * the stored ids are matched. A null argument matches nothing.
     */
    @Query(
        "DELETE FROM book_insights " +
            "WHERE identityKey = :metadataId OR metadataId = :metadataId " +
            "OR identityKey = :contentHash OR contentHash = :contentHash"
    )
    suspend fun deleteForBook(metadataId: String?, contentHash: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: InsightEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<InsightEntity>)

    /** Latest server-side `generated_at` we've cached locally; cursor coord 1. */
    @Query(
        "SELECT generatedAt FROM book_insights " +
            "ORDER BY generatedAt DESC, serverId DESC LIMIT 1"
    )
    suspend fun latestGeneratedAt(): Long?

    /** Server PK at the same `(generatedAt, serverId)` tip; cursor coord 2. */
    @Query(
        "SELECT serverId FROM book_insights " +
            "ORDER BY generatedAt DESC, serverId DESC LIMIT 1"
    )
    suspend fun latestServerId(): Long?

    /** Wall-clock millis of the last upsert; drives the Settings "last synced" status. */
    @Query("SELECT MAX(syncedAt) FROM book_insights")
    suspend fun latestSyncedAt(): Long?

    /** Test-only convenience. Production code uses `findAnyForIdentity`. */
    @Query("SELECT COUNT(*) FROM book_insights")
    suspend fun count(): Int
}
