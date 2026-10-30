package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightDao {
    // A UUID collision must never replace an existing annotation or revive its tombstone.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(highlight: HighlightEntity)

    @Query("SELECT * FROM highlights WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): HighlightEntity?

    @Query(
        """
        SELECT * FROM highlights
        WHERE deletedAt IS NULL
          AND ((:metadataId IS NOT NULL AND metadataId = :metadataId)
            OR (:contentHash IS NOT NULL AND contentHash = :contentHash))
        ORDER BY createdAt, id
        """
    )
    fun observeActive(metadataId: String?, contentHash: String?): Flow<List<HighlightEntity>>

    @Query(
        """
        UPDATE highlights
        SET colour = :colour, updatedAt = MAX(:now, updatedAt + 1)
        WHERE id = :id AND deletedAt IS NULL AND colour != :colour
        """
    )
    suspend fun setColour(id: String, colour: String, now: Long)

    @Query(
        """
        UPDATE highlights
        SET deletedAt = MAX(:now, updatedAt + 1), updatedAt = MAX(:now, updatedAt + 1)
        WHERE id = :id AND deletedAt IS NULL
        """
    )
    suspend fun softDelete(id: String, now: Long)
}
