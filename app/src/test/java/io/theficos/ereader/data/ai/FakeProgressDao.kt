package io.theficos.ereader.data.ai

import io.theficos.ereader.data.local.db.ProgressDao
import io.theficos.ereader.data.local.db.ProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [ProgressDao] for unit tests that need to assert pr-α's
 * `markAbandoned` / `unmarkAbandoned` behavior without spinning up a Room
 * database.
 */
class FakeProgressDao : ProgressDao {
    private val rows = linkedMapOf<Long, ProgressEntity>()

    fun seed(entity: ProgressEntity) {
        rows[entity.documentId] = entity
    }

    fun byDocument(documentId: Long): ProgressEntity? = rows[documentId]

    override suspend fun upsert(progress: ProgressEntity) {
        rows[progress.documentId] = progress
    }

    override suspend fun findByDocument(docId: Long): ProgressEntity? = rows[docId]

    override fun observeByDocument(docId: Long): Flow<ProgressEntity?> = flowOf(rows[docId])

    override suspend fun dirty(): List<ProgressEntity> =
        rows.values.filter { it.localUpdatedAt > it.syncedAt }

    override suspend fun markSynced(documentId: Long, syncedAt: Long) {
        rows[documentId]?.let { rows[documentId] = it.copy(syncedAt = syncedAt) }
    }

    override suspend fun markAbandoned(documentId: Long, now: Long) {
        rows[documentId]?.let {
            rows[documentId] = it.copy(
                abandonedAt = now,
                finishedAt = null,
                updatedAt = now,
                localUpdatedAt = now,
            )
        }
    }

    override suspend fun unmarkAbandoned(documentId: Long, now: Long) {
        rows[documentId]?.let {
            rows[documentId] = it.copy(
                abandonedAt = null,
                updatedAt = now,
                localUpdatedAt = now,
            )
        }
    }
}
