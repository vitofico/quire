package io.theficos.ereader.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DocumentIdentity(
    @SerialName("metadata_id") val metadataId: String?,
    @SerialName("content_hash") val contentHash: String,
) {
    init {
        require(contentHash.isNotEmpty()) { "contentHash must not be empty" }
    }
}
