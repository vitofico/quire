package io.theficos.ereader.core.model

/** Stable semantic colours; reader themes choose the actual drawing tint. */
enum class HighlightColour { YELLOW, GREEN, PINK }

/** A local annotation belongs to a library identity, independently of a downloaded file. */
data class Highlight(
    val id: String,
    val identity: DocumentIdentity,
    val locator: String,
    val text: String,
    val colour: HighlightColour,
    val bookTitle: String,
    val bookAuthor: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)
