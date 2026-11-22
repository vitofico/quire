package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Account partitioning is part of the primary key, including on downloaded-file-free rows. */
@Dao
abstract class HighlightSyncDao {
    @Query("SELECT * FROM highlights WHERE ownerScope = :scope AND (syncedAt IS NULL OR syncedAt != updatedAt) AND (:supportsComments OR (comment IS NULL AND NOT commentCleared)) ORDER BY id LIMIT :limit")
    abstract suspend fun dirty(scope: String, supportsComments: Boolean = true, limit: Int = 500): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE ownerScope = ''")
    abstract suspend fun unowned(): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE ownerScope = :scope AND id = :id")
    abstract suspend fun find(scope: String, id: String): HighlightEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(row: HighlightEntity)

    @Query("DELETE FROM highlights WHERE ownerScope = '' AND id = :id")
    abstract suspend fun deleteUnowned(id: String)

    @Query("INSERT OR REPLACE INTO sync_state (tableName, lastPulledAt) VALUES (:key, :cursor)")
    abstract suspend fun setCursor(key: String, cursor: Long)

    /** Called only after authenticated endpoint success. Never reassign an already owned row. */
    @Transaction
    open suspend fun claimUnowned(scope: String) {
        require(scope.isNotEmpty())
        for (row in unowned()) {
            val existing = find(scope, row.id)
            val version = maxOf(row.identityHashVersion, existing?.identityHashVersion ?: 1)
            val candidate = row.copy(ownerScope = scope, identityHashVersion = version, syncedAt = null)
            val normalizedExisting = existing?.copy(identityHashVersion = version)
            if (normalizedExisting == null || compareHighlights(candidate, normalizedExisting) > 0) {
                put(candidate)
            } else if (existing.identityHashVersion != version) {
                put(normalizedExisting.copy(syncedAt = null))
            }
            deleteUnowned(row.id)
        }
    }

    /**
     * Full authoritative rows acknowledge only their exact payload. A concurrent local edit
     * with a greater version stays dirty; a deterministic server winner replaces it atomically.
     */
    @Transaction
    open suspend fun merge(rows: List<HighlightEntity>, supportsComments: Boolean = true) {
        for (incoming in rows) {
            val current = find(incoming.ownerScope, incoming.id)
            val version = maxOf(incoming.identityHashVersion, current?.identityHashVersion ?: 1)
            // Older servers cannot represent a local comment or acknowledge a pending clear.
            val pendingComment = current?.takeIf { !supportsComments && (it.comment != null || it.commentCleared) }
            val normalizedIncoming = incoming.copy(
                identityHashVersion = version,
                comment = pendingComment?.comment ?: incoming.comment,
                commentCleared = pendingComment?.commentCleared ?: false,
            )
            val normalizedCurrent = current?.copy(identityHashVersion = version)
            if (normalizedCurrent == null || compareHighlights(normalizedIncoming, normalizedCurrent) >= 0) {
                put(normalizedIncoming.copy(syncedAt = if (pendingComment == null && version == incoming.identityHashVersion) incoming.updatedAt else null))
            } else if (current.identityHashVersion != version) {
                // The payload and version can come from different edits. Upload that composite winner.
                put(normalizedCurrent.copy(syncedAt = null))
            }
        }
    }

    @Transaction
    open suspend fun applyPage(rows: List<HighlightEntity>, key: String, cursor: Long, supportsComments: Boolean = true) {
        merge(rows, supportsComments)
        setCursor(key, cursor)
    }
}

/** Exact counterpart of the server's normalized wire comparator, with UTF-8 string ordering. */
fun compareHighlights(a: HighlightEntity, b: HighlightEntity): Int {
    fun text(left: String?, right: String?): Int {
        val x = left.orEmpty().toByteArray(Charsets.UTF_8)
        val y = right.orEmpty().toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val order = (x[i].toInt() and 255).compareTo(y[i].toInt() and 255)
            if (order != 0) return order
        }
        return x.size.compareTo(y.size)
    }
    val values = listOf(
        a.updatedAt.compareTo(b.updatedAt),
        (a.deletedAt != null).compareTo(b.deletedAt != null),
        (a.deletedAt ?: 0).compareTo(b.deletedAt ?: 0),
        text(a.metadataId, b.metadataId), text(a.contentHash, b.contentHash),
        a.identityHashVersion.compareTo(b.identityHashVersion),
        text(a.locator, b.locator), text(a.text, b.text), text(a.colour, b.colour),
        text(a.bookTitle, b.bookTitle), (a.bookAuthor != null).compareTo(b.bookAuthor != null),
        text(a.bookAuthor, b.bookAuthor), a.createdAt.compareTo(b.createdAt),
        (a.comment != null).compareTo(b.comment != null), text(a.comment, b.comment),
    )
    return values.firstOrNull { it != 0 } ?: 0
}
