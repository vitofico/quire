package io.theficos.ereader.data.ai

import io.theficos.ereader.data.local.db.InsightDao
import io.theficos.ereader.data.local.db.InsightEntity

/**
 * In-memory [InsightDao] for unit tests. Maps the same PK shape Room uses so
 * the local-first lookup logic exercises the production code path verbatim.
 */
class FakeInsightDao : InsightDao {
    private data class Key(
        val identityKey: String,
        val modelId: String,
        val promptVersion: String,
        val tone: String,
        val language: String,
    )

    private val rows = linkedMapOf<Key, InsightEntity>()

    fun seed(entity: InsightEntity) {
        rows[entity.key()] = entity
    }

    private fun InsightEntity.key() = Key(identityKey, modelId, promptVersion, tone, language)

    override suspend fun getByIdentity(
        identityKey: String,
        modelId: String,
        promptVersion: String,
        tone: String,
        language: String,
    ): InsightEntity? = rows[Key(identityKey, modelId, promptVersion, tone, language)]

    override suspend fun findAnyForIdentity(identityKey: String): InsightEntity? =
        rows.values.filter { it.identityKey == identityKey }
            .maxByOrNull { it.generatedAt }

    override suspend fun upsert(item: InsightEntity) {
        rows[item.key()] = item
    }

    override suspend fun upsertAll(items: List<InsightEntity>) {
        items.forEach { rows[it.key()] = it }
    }

    override suspend fun latestGeneratedAt(): Long? =
        rows.values.maxOfOrNull { it.generatedAt }

    override suspend fun latestServerId(): Long? {
        val tip = rows.values.maxWithOrNull(
            compareBy<InsightEntity>({ it.generatedAt }, { it.serverId })
        ) ?: return null
        return tip.serverId
    }

    override suspend fun latestSyncedAt(): Long? =
        rows.values.maxOfOrNull { it.syncedAt }

    override suspend fun count(): Int = rows.size

    fun all(): List<InsightEntity> = rows.values.toList()
}
