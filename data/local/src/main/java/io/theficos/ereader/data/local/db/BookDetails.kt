package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * What the server found and tidied for one library book, kept so the book shows it offline.
 * The book's own fields in `documents` are never changed: this row sits beside them.
 */
@Entity(
    tableName = "book_details",
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"],
        childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
)
data class BookDetailsEntity(
    @PrimaryKey val documentId: Long,
    /**
     * The server's answer as received, with its key, field origins and cover, and the reader's
     * choices kept with it; null when none was asked for.
     */
    val responseJson: String?,
    /** Local copy of the answer's cover, for a book with no cover of its own or whose reader chose another. */
    val coverPath: String?,
    /** The reader chose the book's own details over the found ones. */
    val useOriginal: Boolean,
    /** When the automatic pass may ask again, after a spent allowance or a failure; null when it never needs to. */
    val retryAt: Long?,
)

@Dao
interface BookDetailsDao {
    @Query("SELECT * FROM book_details WHERE documentId = :documentId")
    fun observe(documentId: Long): Flow<BookDetailsEntity?>

    @Query("SELECT * FROM book_details")
    fun observeAll(): Flow<List<BookDetailsEntity>>

    @Query("SELECT * FROM book_details WHERE documentId = :documentId")
    suspend fun find(documentId: Long): BookDetailsEntity?

    @Query("SELECT * FROM book_details")
    suspend fun all(): List<BookDetailsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: BookDetailsEntity)

    @Query("DELETE FROM book_details")
    suspend fun clear()

    @Query("UPDATE book_details SET useOriginal = :useOriginal WHERE documentId = :documentId")
    suspend fun setUseOriginal(documentId: Long, useOriginal: Boolean)
}
