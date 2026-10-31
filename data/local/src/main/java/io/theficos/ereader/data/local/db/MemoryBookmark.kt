package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One deliberate pause per book/account. Device-local: never enters the sync outbox. */
@Entity(
    tableName = "memory_bookmarks",
    primaryKeys = ["documentId", "accountKey"],
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"],
        childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("documentId")],
)
data class MemoryBookmark(
    val documentId: Long,
    val accountKey: String,
    val locator: String,
    val note: String,
    val passage: String,
    val updatedAt: Long,
)

@Dao
interface MemoryBookmarkDao {
    @Query("SELECT * FROM memory_bookmarks WHERE documentId = :documentId AND accountKey = :accountKey")
    fun observe(documentId: Long, accountKey: String): Flow<MemoryBookmark?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(bookmark: MemoryBookmark)

    @Query("DELETE FROM memory_bookmarks WHERE documentId = :documentId AND accountKey = :accountKey")
    suspend fun remove(documentId: Long, accountKey: String)
}
