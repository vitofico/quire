package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
     */
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
    suspend fun markAbandoned(documentId: Long, now: Long)

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
