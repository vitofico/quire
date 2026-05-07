package io.theficos.ereader.core.model

data class Document(
    val id: Long,
    val identity: DocumentIdentity,
    val title: String,
    val author: String?,
    val downloadUrl: String,
    val localPath: String,
    val downloadedAt: Long,
)
