package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One continuous stretch of reading a book in the foreground. Device-local: never synced.
 *
 * Keyed by the book's content hash, with its title and author copied in, and no foreign key, so
 * a removed book still counts in the totals. Times are UTC epoch milliseconds; [utcOffsetSeconds]
 * is the device's offset from UTC when the stretch began, so its hour of day stays where it was
 * read after travel or a daylight saving change.
 *
 * [progressionRead] is the share of the whole book (0..1) gained by ordinary page turns and
 * scrolling; jumps do not count. [wordsRead] is that share of the book's word count, or null
 * when the count was not known.
 */
@Entity(
    tableName = "reading_sessions",
    indices = [Index("bookKey"), Index("startedAt")],
)
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookKey: String,
    val title: String,
    val author: String?,
    val startedAt: Long,
    val endedAt: Long,
    val utcOffsetSeconds: Int,
    val activeSeconds: Long,
    val startProgression: Double,
    val endProgression: Double,
    val progressionRead: Double,
    val wordsRead: Long?,
)

/** Words in a book's reading order, counted once in the background and kept by content hash. */
@Entity(tableName = "book_word_counts")
data class BookWordCountEntity(
    @PrimaryKey val bookKey: String,
    val words: Long,
)

/** Where a library book stands, by content hash, for the time-left estimates. */
data class BookProgressRow(
    val bookKey: String,
    val percent: Double,
    val finishedAt: Long?,
    val abandonedAt: Long?,
)

@Dao
interface ReadingSessionDao {
    @Insert
    suspend fun insert(session: ReadingSessionEntity)

    @Query("SELECT * FROM reading_sessions ORDER BY startedAt")
    fun observeAll(): Flow<List<ReadingSessionEntity>>

    @Query("DELETE FROM reading_sessions")
    suspend fun clear()

    @Query("SELECT words FROM book_word_counts WHERE bookKey = :bookKey")
    suspend fun wordCount(bookKey: String): Long?

    @Query("SELECT * FROM book_word_counts")
    fun observeWordCounts(): Flow<List<BookWordCountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWordCount(count: BookWordCountEntity)

    @Query(
        "SELECT d.contentHash AS bookKey, p.percent AS percent, p.finishedAt AS finishedAt, " +
            "p.abandonedAt AS abandonedAt FROM documents d JOIN progress p ON p.documentId = d.id",
    )
    fun observeBookProgress(): Flow<List<BookProgressRow>>
}
