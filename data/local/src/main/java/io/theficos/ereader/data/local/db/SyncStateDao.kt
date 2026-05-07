package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncStateDao {
    @Query("SELECT lastPulledAt FROM sync_state WHERE tableName = :tableName")
    suspend fun lastPulled(tableName: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(state: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun clearAll()
}
