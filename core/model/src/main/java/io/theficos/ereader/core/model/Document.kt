package io.theficos.ereader.core.model

data class Document(
    val id: Long,
    val identity: DocumentIdentity,
    val title: String,
    val author: String?,
    val downloadUrl: String,
    val localPath: String,
    val coverPath: String?,
    val downloadedAt: Long,
    val seriesName: String? = null,
    val seriesIndex: Double? = null,
    /**
     * Schema version of the client-side hash function that produced
     * `identity.contentHash` for this document. See
     * [CURRENT_IDENTITY_HASH_VERSION]; today every Document is v1.
     */
    val identityHashVersion: Int = CURRENT_IDENTITY_HASH_VERSION,
)
