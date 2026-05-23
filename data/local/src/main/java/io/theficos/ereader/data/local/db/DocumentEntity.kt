package io.theficos.ereader.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["metadataId"], unique = true),
        Index(value = ["contentHash"], unique = true),
        Index(
            value = ["seriesName", "seriesIndex"],
            name = "index_documents_seriesName_seriesIndex",
        ),
    ],
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val metadataId: String?,
    val contentHash: String,
    val title: String,
    val author: String?,
    val downloadUrl: String,
    val localPath: String,
    val coverPath: String?,
    val downloadedAt: Long,
    val seriesName: String? = null,
    val seriesIndex: Double? = null,
    /**
     * When this row was last successfully PUT to `/library/v1/items`.
     * `null` means "not yet synced" — the upload pass picks these up.
     * Existing rows backfill via the 5→6 migration as null, so the first
     * post-update run uploads everything.
     *
     * Wall-clock millis (System.currentTimeMillis) — used only as a
     * presence marker; we never compare it against server timestamps.
     */
    val librarySyncedAt: Long? = null,
    /**
     * Phase-0 / F-2: schema version of the client-side hash function that
     * produced [contentHash]. `INTEGER NOT NULL DEFAULT 1` on disk so the
     * 8→9 migration can backfill pre-existing rows in a single ALTER TABLE.
     * Today every row is v1; the field exists so a future hash-function
     * change can be detected via
     * `io.theficos.ereader.core.model.isStaleHashVersion`.
     */
    @ColumnInfo(defaultValue = "1")
    val identityHashVersion: Int = 1,
)
