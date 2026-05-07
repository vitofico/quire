package io.theficos.ereader.core.model

data class DocumentIdentity(
    val metadataId: String?,
    val contentHash: String,
) {
    init {
        require(contentHash.isNotEmpty()) { "contentHash must not be empty" }
    }
}
