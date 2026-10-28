package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE documentId = :docId LIMIT 1")
    suspend fun findByDocument(docId: Long): ProgressEntity?

    @Query("SELECT * FROM progress WHERE documentId = :docId LIMIT 1")
    fun observeByDocument(docId: Long): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE localUpdatedAt > syncedAt")
    suspend fun dirty(): List<ProgressEntity>

    @Query("UPDATE progress SET syncedAt = :syncedAt WHERE documentId = :documentId")
    suspend fun markSynced(documentId: Long, syncedAt: Long)

    /** A response acknowledges only the snapshot sent, never a save made during the request. */
    @Transaction
    suspend fun markSyncedIfUnchanged(snapshot: ProgressEntity) {
        if (findByDocument(snapshot.documentId) == snapshot) {
            markSynced(snapshot.documentId, snapshot.localUpdatedAt)
        }
    }

    /** Keep the conflict check and write atomic with respect to reader saves. */
    @Transaction
    suspend fun applyPulledIfNewer(incoming: ProgressEntity) {
        val existing = findByDocument(incoming.documentId)
        if (existing != null && existing.localUpdatedAt >= incoming.updatedAt) return
        upsert(incoming.copy(id = existing?.id ?: 0L))
    }

    /**
     * PR-γ: max `updatedAt` across all progress rows. Used to seed the local
     * input-fingerprint approximation (Lock #12, coordinator §3.6). Returns
     * null when no rows exist.
     */
    @Query("SELECT MAX(updatedAt) FROM progress")
    suspend fun maxUpdatedAt(): Long?

    /**
     * Mark a row abandoned. Sets `abandonedAt`, clears `finishedAt` (terminal
     * state invariant — coordinator §3.10), and leaves `percent` untouched
     * so abandoning at 60% remembers 60%.
     *
     * Both `updatedAt` AND `localUpdatedAt` are bumped to `now`. This is
     * load-bearing: the SyncOrchestrator pushes `progress.updatedAt` as
     * `client_updated_at`, and the server's `push_progress` LWW guard only
     * accepts the row when `client_updated_at` is strictly newer than the
     * stored value. Updating only `localUpdatedAt` would make the row
     * "dirty" locally but the server would silently reject the abandon.
     *
     * A book that was never opened has no row for the update to touch, so one
     * is created first at 0% with an empty locator, the shape a restart
     * writes; the reader opens an empty locator at the start of the book.
     */
    @Transaction
    suspend fun markAbandoned(documentId: Long, now: Long) {
        insertIfAbsent(ProgressEntity(
            documentId = documentId, locator = "", percent = 0.0,
            updatedAt = now, localUpdatedAt = now, syncedAt = 0L,
        ))
        setAbandoned(documentId, now)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(progress: ProgressEntity)

    @Query(
        """
        UPDATE progress
        SET abandonedAt    = :now,
            finishedAt     = NULL,
            updatedAt      = :now,
            localUpdatedAt = :now
        WHERE documentId = :documentId
        """
    )
    suspend fun setAbandoned(documentId: Long, now: Long)

    /**
     * Inverse of [markAbandoned]: clears `abandonedAt`. `percent` and
     * `finishedAt` are left untouched. As above, bumps `updatedAt` AND
     * `localUpdatedAt` to `now` so the LWW push gets through.
     */
    @Query(
        """
        UPDATE progress
        SET abandonedAt    = NULL,
            updatedAt      = :now,
            localUpdatedAt = :now
        WHERE documentId = :documentId
        """
    )
    suspend fun unmarkAbandoned(documentId: Long, now: Long)
}
