package io.theficos.ereader.data.local.db

import androidx.room.Entity
import androidx.room.Index

/**
 * PR-η: local cache row for one BookInsight, keyed by the canonical
 * identity tuple `(identityKey, modelId, promptVersion, tone, language)`.
 *
 * `identityKey` is `metadataId ?: contentHash` — server-side `LibraryItem`
 * always has a non-null `content_hash`, so the fallback is total.
 *
 * `serverId` is the source row's `BookInsight.id` on the server, exposed by
 * `/ai/v1/insights/sync` so the Android cursor can be reconstructed from
 * local state without an extra round-trip.
 */
@Entity(
    tableName = "book_insights",
    primaryKeys = ["identityKey", "modelId", "promptVersion", "tone", "language"],
    indices = [
        Index("syncedAt"),
        Index("metadataId"),
        Index("contentHash"),
        Index(value = ["generatedAt", "serverId"], name = "index_book_insights_cursor"),
    ],
)
data class InsightEntity(
    val identityKey: String,
    val metadataId: String?,
    val contentHash: String?,
    val modelId: String,
    val promptVersion: String,
    val tone: String,
    val language: String,
    val payloadJson: String,
    val sourcesJson: String,
    val schemaVersion: Int,
    /** Server's BookInsight.id; cursor coordinate. */
    val serverId: Long,
    /** Server-side `generated_at` as epoch millis. */
    val generatedAt: Long,
    /** When this row was upserted locally; wall clock millis. */
    val syncedAt: Long,
)
