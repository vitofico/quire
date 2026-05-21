package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(doc: DocumentEntity): Long

    @Update
    suspend fun update(doc: DocumentEntity)

    @Query("SELECT * FROM documents WHERE metadataId = :id LIMIT 1")
    suspend fun findByMetadataId(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE contentHash = :hash LIMIT 1")
    suspend fun findByContentHash(hash: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE downloadUrl = :url LIMIT 1")
    suspend fun findByDownloadUrl(url: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    /**
     * "Continue your series" candidates for PR8's library-home shelf.
     *
     * A candidate is any book where:
     *   - the book itself has a non-empty `seriesName`,
     *   - the user has neither finished nor meaningfully started it
     *     (no progress row, or `percent < startedThreshold` and `finishedAt IS NULL`),
     *   - at least one other book in the same `seriesName` (case-insensitive)
     *     has a `progress.finishedAt` set.
     *
     * Order: series whose latest finish is most recent first, then ascending
     * `seriesIndex` within a series, with NULL indices pushed to the end and a
     * stable title/id tiebreaker.
     */
    @Query(
        """
        WITH finished_series AS (
            SELECT sibling.seriesName       AS seriesName,
                   MAX(sp.finishedAt)       AS lastFinishedAt
            FROM documents AS sibling
            JOIN progress  AS sp ON sp.documentId = sibling.id
            WHERE sibling.seriesName IS NOT NULL
              AND sibling.seriesName != ''
              AND sp.finishedAt IS NOT NULL
            GROUP BY sibling.seriesName COLLATE NOCASE
        )
        SELECT d.*
        FROM documents AS d
        JOIN finished_series fs
          ON fs.seriesName = d.seriesName COLLATE NOCASE
        WHERE d.seriesName IS NOT NULL
          AND d.seriesName != ''
          AND NOT EXISTS (
              SELECT 1 FROM progress AS p
              WHERE p.documentId = d.id
                AND (p.finishedAt IS NOT NULL OR p.percent >= :startedThreshold)
          )
        ORDER BY
          fs.lastFinishedAt DESC,
          (d.seriesIndex IS NULL) ASC,
          d.seriesIndex ASC,
          d.title COLLATE NOCASE ASC,
          d.id ASC
        LIMIT :maxItems
        """
    )
    fun observeSeriesContinuationCandidates(
        startedThreshold: Double,
        maxItems: Int,
    ): Flow<List<DocumentEntity>>

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM documents")
    suspend fun deleteAll()

    /**
     * Rows that haven't been uploaded to `/library/v1/items` yet. The uploader
     * walks these on each app start and after every new download. Returns
     * newest-first so the user-facing book they just downloaded reaches the
     * server before the long tail of pre-existing backlog.
     */
    @Query("SELECT * FROM documents WHERE librarySyncedAt IS NULL ORDER BY downloadedAt DESC, id DESC")
    suspend fun findUnsyncedToLibrary(): List<DocumentEntity>

    @Query("UPDATE documents SET librarySyncedAt = :at WHERE id = :id")
    suspend fun markLibrarySynced(id: Long, at: Long)
}
