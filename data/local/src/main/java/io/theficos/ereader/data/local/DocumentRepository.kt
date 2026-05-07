package io.theficos.ereader.data.local

import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.local.db.DocumentDao
import io.theficos.ereader.data.local.db.DocumentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

class DocumentRepository(private val dao: DocumentDao) {

    fun observeLibrary(): Flow<List<Document>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun findByIdentity(identity: DocumentIdentity): Document? {
        identity.metadataId?.let { dao.findByMetadataId(it)?.let { return it.toDomain() } }
        return dao.findByContentHash(identity.contentHash)?.toDomain()
    }

    suspend fun findById(id: Long): Document? = dao.findById(id)?.toDomain()

    /**
     * Removes the row (cascade-deletes any [progress] row via FK), then best-effort
     * deletes the local EPUB file. The DB delete is the source of truth — if the
     * file unlink fails (e.g. already missing), the document is still gone from
     * the library.
     */
    suspend fun delete(document: Document) {
        dao.deleteById(document.id)
        runCatching { File(document.localPath).delete() }
    }

    suspend fun insert(
        identity: DocumentIdentity,
        title: String,
        author: String?,
        downloadUrl: String,
        localPath: String,
        coverPath: String?,
        downloadedAt: Long,
    ): Long = dao.insert(DocumentEntity(
        metadataId = identity.metadataId,
        contentHash = identity.contentHash,
        title = title,
        author = author,
        downloadUrl = downloadUrl,
        localPath = localPath,
        coverPath = coverPath,
        downloadedAt = downloadedAt,
    ))

    private fun DocumentEntity.toDomain(): Document = Document(
        id = id,
        identity = DocumentIdentity(metadataId = metadataId, contentHash = contentHash),
        title = title,
        author = author,
        downloadUrl = downloadUrl,
        localPath = localPath,
        coverPath = coverPath,
        downloadedAt = downloadedAt,
    )
}
