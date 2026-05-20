package io.theficos.ereader.data.local

import io.theficos.ereader.core.model.Progress
import io.theficos.ereader.data.local.db.ProgressDao
import io.theficos.ereader.data.local.db.ProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProgressRepository(private val dao: ProgressDao) {
    suspend fun get(documentId: Long): Progress? =
        dao.findByDocument(documentId)?.toDomain()

    fun observe(documentId: Long): Flow<Progress?> =
        dao.observeByDocument(documentId).map { it?.toDomain() }

    suspend fun save(progress: Progress) {
        val now = System.currentTimeMillis()
        dao.upsert(ProgressEntity(
            documentId = progress.documentId,
            locator = progress.locator,
            percent = progress.percent,
            updatedAt = progress.updatedAt,
            localUpdatedAt = now,
            syncedAt = 0L,
            finishedAt = progress.finishedAt,
            abandonedAt = progress.abandonedAt,
        ))
    }

    suspend fun dirty(): List<Progress> = dao.dirty().map { it.toDomain() }

    suspend fun markSynced(documentId: Long, syncedAt: Long) =
        dao.markSynced(documentId, syncedAt)

    suspend fun resetForDocument(documentId: Long, now: Long) {
        dao.upsert(ProgressEntity(
            documentId = documentId,
            locator = "",
            percent = 0.0,
            updatedAt = now,
            localUpdatedAt = now,
            syncedAt = 0L,
            finishedAt = null,
        ))
    }

    private fun ProgressEntity.toDomain(): Progress =
        Progress(
            documentId = documentId,
            locator = locator,
            percent = percent,
            updatedAt = updatedAt,
            finishedAt = finishedAt,
            abandonedAt = abandonedAt,
        )
}
