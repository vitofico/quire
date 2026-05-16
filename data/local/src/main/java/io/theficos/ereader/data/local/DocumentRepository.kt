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

    /**
     * Books eligible for the library home "Continue your series" shelf: any book
     * the user has not finished and barely (or not at all) started, where some
     * other book in the same `seriesName` (case-insensitive) has a finish.
     *
     * Capped at [SERIES_CONTINUATION_MAX_ITEMS]; "barely started" is governed
     * by [SERIES_CONTINUATION_STARTED_THRESHOLD] on `progress.percent`.
     */
    fun observeSeriesContinuationCandidates(): Flow<List<Document>> =
        dao.observeSeriesContinuationCandidates(
            startedThreshold = SERIES_CONTINUATION_STARTED_THRESHOLD,
            maxItems = SERIES_CONTINUATION_MAX_ITEMS,
        ).map { rows -> rows.map { it.toDomain() } }

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

    /**
     * Deletes every document row (cascade-deletes all progress) and best-effort
     * removes everything inside [booksDir]. The directory itself is preserved so
     * future downloads have a destination.
     */
    suspend fun deleteAll(booksDir: File) {
        dao.deleteAll()
        runCatching { booksDir.listFiles()?.forEach { it.deleteRecursively() } }
    }

    suspend fun insert(
        identity: DocumentIdentity,
        title: String,
        author: String?,
        downloadUrl: String,
        localPath: String,
        coverPath: String?,
        downloadedAt: Long,
        seriesName: String? = null,
        seriesIndex: Double? = null,
    ): Long = dao.insert(DocumentEntity(
        metadataId = identity.metadataId,
        contentHash = identity.contentHash,
        title = title,
        author = author,
        downloadUrl = downloadUrl,
        localPath = localPath,
        coverPath = coverPath,
        downloadedAt = downloadedAt,
        seriesName = seriesName?.takeIf { it.isNotBlank() },
        seriesIndex = seriesIndex,
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
        seriesName = seriesName,
        seriesIndex = seriesIndex,
    )

    companion object {
        /** Per-tile cap on the "Continue your series" shelf — keeps the LazyRow bounded. */
        const val SERIES_CONTINUATION_MAX_ITEMS: Int = 12

        /**
         * Books with `progress.percent` below this threshold are treated as
         * "not really started" and remain eligible for the continuation shelf.
         * Tolerates an accidental open without burying the prompt.
         */
        const val SERIES_CONTINUATION_STARTED_THRESHOLD: Double = 0.05
    }
}
