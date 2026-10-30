package io.theficos.ereader.data.local

import io.theficos.ereader.core.identity.normalizeMetadataId
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.core.model.Highlight
import io.theficos.ereader.core.model.HighlightColour
import io.theficos.ereader.data.local.db.HighlightDao
import io.theficos.ereader.data.local.db.HighlightEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

class HighlightRepository(
    private val dao: HighlightDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val scopeProvider: () -> String = { "" },
    private val scopeFlow: Flow<String> = flowOf(""),
    private val onChanged: () -> Unit = {},
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(identity: DocumentIdentity): Flow<List<Highlight>> {
        val metadataId = normalizeMetadataId(identity.metadataId)
        val contentHash = identity.contentHash?.trim()?.takeIf { it.isNotEmpty() }
        if (metadataId == null && contentHash == null) return flowOf(emptyList())
        return scopeFlow.flatMapLatest { scope ->
            dao.observeActive(metadataId, contentHash, scope).map { rows -> rows.map { it.toDomain() } }
        }
    }

    suspend fun create(
        document: Document,
        locator: String,
        text: String,
        colour: HighlightColour,
    ): Highlight {
        val scope = scopeProvider()
        val contentHash = requireNotNull(document.identity.contentHash?.trim()?.takeIf { it.isNotEmpty() }) {
            "A highlight requires the downloaded EPUB's content hash"
        }
        require(locator.isNotBlank()) { "A highlight requires a locator" }
        require(text.isNotBlank()) { "A highlight requires selected text" }
        val now = nowMs()
        val row = HighlightEntity(
            id = newId(),
            metadataId = normalizeMetadataId(document.identity.metadataId),
            contentHash = contentHash,
            identityHashVersion = document.identity.identityHashVersion,
            locator = locator,
            text = text,
            colour = colour.name,
            bookTitle = document.title,
            bookAuthor = document.author,
            createdAt = now,
            updatedAt = now,
            ownerScope = scope,
        )
        dao.insert(row)
        onChanged()
        return row.toDomain()
    }

    suspend fun setColour(id: String, colour: HighlightColour) {
        dao.setColour(id, colour.name, nowMs(), scopeProvider())
        onChanged()
    }

    /** Keep the tombstone so a later sync can propagate the removal. */
    suspend fun delete(id: String) {
        dao.softDelete(id, nowMs(), scopeProvider())
        onChanged()
    }

    private fun HighlightEntity.toDomain() = Highlight(
        id = id,
        identity = DocumentIdentity(metadataId = metadataId, contentHash = contentHash, identityHashVersion = identityHashVersion),
        locator = locator,
        text = text,
        colour = HighlightColour.valueOf(colour),
        bookTitle = bookTitle,
        bookAuthor = bookAuthor,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )
}
