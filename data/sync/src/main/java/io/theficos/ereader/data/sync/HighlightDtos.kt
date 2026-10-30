package io.theficos.ereader.data.sync

import io.theficos.ereader.core.model.HighlightColour
import io.theficos.ereader.data.local.db.HighlightEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class HighlightItemDto(
    val id: String,
    val document: DocumentIdDto,
    val locator: String,
    val text: String,
    val colour: String,
    @SerialName("book_title") val bookTitle: String,
    @SerialName("book_author") val bookAuthor: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
) {
    fun toEntity(scope: String): HighlightEntity {
        require(UUID.fromString(id).toString() == id) { "Invalid highlight ID" }
        require(document.contentHash.isNotBlank() && locator.isNotBlank() && text.isNotBlank())
        require(document.identityHashVersion > 0)
        HighlightColour.valueOf(colour)
        require(createdAt >= 0 && updatedAt >= createdAt && (deletedAt == null || deletedAt in 0..updatedAt))
        return HighlightEntity(
            id, document.metadataId, document.contentHash, document.identityHashVersion,
            locator, text, colour, bookTitle, bookAuthor, createdAt, updatedAt, deletedAt,
            ownerScope = scope, syncedAt = updatedAt,
        )
    }
}

fun HighlightEntity.toSyncDto() = HighlightItemDto(
    id, DocumentIdDto(metadataId, contentHash, identityHashVersion), locator, text, colour,
    bookTitle, bookAuthor, createdAt, updatedAt, deletedAt,
)

@Serializable data class HighlightPushBody(val items: List<HighlightItemDto>)
@Serializable data class HighlightPushResult(val status: String, val item: HighlightItemDto)
@Serializable data class HighlightPushResponse(val results: List<HighlightPushResult>)
@Serializable data class HighlightPullResponse(
    val items: List<HighlightItemDto>,
    @SerialName("next_cursor") val nextCursor: String,
    @SerialName("has_more") val hasMore: Boolean,
)

/** A run freezes both credentials and origin; it must never consult a live auth interceptor. */
class HighlightSyncSession(val scope: String, val baseUrl: String, val authorization: String) {
    override fun toString() = "HighlightSyncSession(scope=$scope)"
}
