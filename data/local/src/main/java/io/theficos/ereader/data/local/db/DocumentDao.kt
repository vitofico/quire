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

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
