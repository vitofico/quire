package io.theficos.ereader.data.sync

import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.ProgressDao
import io.theficos.ereader.data.local.db.ProgressEntity
import io.theficos.ereader.data.local.db.SyncStateDao
import io.theficos.ereader.data.local.db.SyncStateEntity
import java.time.Instant

class SyncOrchestrator(
    private val client: SyncClient,
    private val progressRepo: ProgressRepository,
    private val progressDao: ProgressDao,
    private val documentRepo: DocumentRepository,
    private val syncState: SyncStateDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    suspend fun runOnce(): SyncResult<Unit> {
        // 1) PUSH dirty rows
        val dirty = progressRepo.dirty()
        if (dirty.isNotEmpty()) {
            val items = dirty.map { progress ->
                val doc = documentRepo.findById(progress.documentId)
                    ?: return SyncResult.HttpFailure(0, "missing document for documentId=${progress.documentId}")
                ProgressItemDto(
                    document = DocumentIdDto(
                        metadataId = doc.identity.metadataId,
                        contentHash = requireNotNull(doc.identity.contentHash) {
                            "downloaded document must have a contentHash"
                        },
                    ),
                    locator = progress.locator,
                    percent = progress.percent,
                    clientUpdatedAt = Instant.ofEpochMilli(progress.updatedAt).toString(),
                    finishedAt = progress.finishedAt?.let { Instant.ofEpochMilli(it).toString() },
                )
            }
            when (val res = client.pushProgress(ProgressPushBody(items))) {
                is SyncResult.Success -> {
                    res.value.results.zip(dirty).forEach { (_, p) ->
                        progressRepo.markSynced(p.documentId, syncedAt = nowMillis())
                    }
                }
                else -> return res.asUnit()
            }
        }

        // 2) PULL deltas
        val sinceMs = syncState.lastPulled(SYNC_TABLE) ?: 0L
        val sinceIso = Instant.ofEpochMilli(sinceMs).toString()
        val pulled = client.pullProgress(sinceIso)
        return when (pulled) {
            is SyncResult.Success -> {
                pulled.value.items.forEach { applyPulled(it) }
                val serverEpoch = Instant.parse(pulled.value.serverTime).toEpochMilli()
                syncState.set(SyncStateEntity(SYNC_TABLE, serverEpoch))
                SyncResult.Success(Unit)
            }
            else -> pulled.asUnit()
        }
    }

    private suspend fun applyPulled(item: ProgressItemDto) {
        val identity = DocumentIdentity(metadataId = item.document.metadataId, contentHash = item.document.contentHash)
        val doc = documentRepo.findByIdentity(identity) ?: return
        val incomingUpdatedAt = Instant.parse(item.clientUpdatedAt).toEpochMilli()
        val incomingFinishedAt = item.finishedAt?.let { Instant.parse(it).toEpochMilli() }
        val existing = progressDao.findByDocument(doc.id)
        if (existing != null && existing.localUpdatedAt >= incomingUpdatedAt) {
            return
        }
        progressDao.upsert(
            ProgressEntity(
                id = existing?.id ?: 0L,
                documentId = doc.id,
                locator = item.locator,
                percent = item.percent,
                updatedAt = incomingUpdatedAt,
                localUpdatedAt = incomingUpdatedAt,
                syncedAt = incomingUpdatedAt,
                finishedAt = incomingFinishedAt,
            )
        )
    }

    private fun <T> SyncResult<T>.asUnit(): SyncResult<Unit> = when (this) {
        is SyncResult.Success -> SyncResult.Success(Unit)
        is SyncResult.Unauthorized -> this
        is SyncResult.HttpFailure -> this
        is SyncResult.NetworkFailure -> this
    }

    private companion object { const val SYNC_TABLE = "progress" }
}
