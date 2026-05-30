package io.theficos.ereader.data.library

import io.theficos.ereader.core.metadata.MetadataBundle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AffinityIdentity(
    val isbn: String,
    @SerialName("metadata_id") val metadataId: String,
)

@Serializable
data class AffinityRequestBody(
    val identity: AffinityIdentity,
    val bundle: MetadataBundle,
)

@Serializable
data class AffinityOwned(
    @SerialName("in_library") val inLibrary: Boolean,
    @SerialName("reading_status") val readingStatus: String,
)

@Serializable
data class AffinityReasonDto(
    val kind: String,
    val polarity: String,
    val message: String,
)

@Serializable
data class AffinityResponse(
    @SerialName("affinity_version") val affinityVersion: Int,
    val owned: AffinityOwned? = null,
    val score: Int? = null,
    val band: String,
    val reasons: List<AffinityReasonDto> = emptyList(),
    @SerialName("generated_at") val generatedAt: String,
)
