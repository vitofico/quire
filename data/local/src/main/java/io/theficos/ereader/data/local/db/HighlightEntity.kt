package io.theficos.ereader.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** No documents FK: removing a download must not delete the reader's annotations. */
@Entity(
    tableName = "highlights",
    primaryKeys = ["ownerScope", "id"],
    indices = [
        Index(value = ["metadataId", "deletedAt"]),
        Index(value = ["contentHash", "deletedAt"]),
    ],
)
data class HighlightEntity(
    val id: String,
    val metadataId: String?,
    val contentHash: String,
    @ColumnInfo(defaultValue = "1") val identityHashVersion: Int = 1,
    val locator: String,
    val text: String,
    val colour: String,
    val bookTitle: String,
    val bookAuthor: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "''") val ownerScope: String = "",
    @ColumnInfo(defaultValue = "NULL") val syncedAt: Long? = null,
)
