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
}
