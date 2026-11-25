package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * One PDF the reader asked the Quire server to convert, kept so the conversion picks up again
 * after the app is closed or killed. Saved before anything is sent: the idempotency key lets the
 * server answer with the same job however often the app asks.
 */
@Entity(tableName = "conversion_jobs", indices = [Index("owner")])
data class ConversionJobEntity(
    /** Stays the same across a retry, which starts a new server job with a new key. */
    @PrimaryKey val id: String,
    /** The account on the server this conversion belongs to; another account never drives it. */
    val owner: String,
    val idempotencyKey: String,
    /** The PDF's name, shown in the app only and never logged or sent. */
    val fileName: String,
    val sha256: String,
    val bytes: Long,
    /** The app's own copy of the PDF, until nothing can need it again. */
    val sourcePath: String?,
    val jobId: String?,
    /** Where the conversion is on this device; see ConversionRepository. */
    val stage: String,
    /** Why it stopped, or what it waits for: a server code or one of the app's own. */
    val problem: String?,
    /** The server's last answer about the job, as received. */
    val jobJson: String?,
    /** Not before this time (epoch ms) does the app ask the server again. */
    val retryAt: Long?,
    /** Transfers that broke in a row; a few are retried, more need the reader. */
    val failures: Int,
    /** The library book the converted EPUB became. */
    val documentId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
abstract class ConversionJobDao {
    @Query("SELECT * FROM conversion_jobs WHERE owner = :owner ORDER BY createdAt DESC")
    abstract fun observe(owner: String): Flow<List<ConversionJobEntity>>

    @Query("SELECT * FROM conversion_jobs WHERE owner = :owner AND stage IN (:stages) ORDER BY createdAt")
    abstract suspend fun inStages(owner: String, stages: List<String>): List<ConversionJobEntity>

    @Query("SELECT * FROM conversion_jobs WHERE id = :id")
    abstract suspend fun find(id: String): ConversionJobEntity?

    @Query("SELECT * FROM conversion_jobs WHERE owner = :owner AND sha256 = :sha256 ORDER BY createdAt DESC LIMIT 1")
    abstract suspend fun latestFor(owner: String, sha256: String): ConversionJobEntity?

    @Query("SELECT * FROM conversion_jobs WHERE stage IN (:stages) AND updatedAt < :before")
    abstract suspend fun settledBefore(stages: List<String>, before: Long): List<ConversionJobEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(row: ConversionJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun replace(row: ConversionJobEntity)

    @Query("DELETE FROM conversion_jobs WHERE id = :id")
    abstract suspend fun delete(id: String)

    /**
     * Saves [row] only while the stored row is still at [expectedStage], so a cancel or retry
     * the reader made meanwhile is never overwritten by work that started before it.
     */
    @Transaction
    open suspend fun replaceIf(row: ConversionJobEntity, expectedStage: String): Boolean {
        val current = find(row.id) ?: return false
        if (current.stage != expectedStage) return false
        replace(row)
        return true
    }
}
