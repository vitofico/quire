package io.theficos.ereader.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DocumentIdDto(
    @SerialName("metadata_id") val metadataId: String? = null,
    @SerialName("content_hash") val contentHash: String,
)

@Serializable
data class ProgressItemDto(
    val document: DocumentIdDto,
    val locator: String,
    val percent: Double,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
    @SerialName("finished_at") val finishedAt: String? = null,
)

@Serializable
data class ProgressPushBody(val items: List<ProgressItemDto>)

@Serializable
data class ProgressPushResultDto(
    val document: DocumentIdDto,
    val status: String,
    @SerialName("server_client_updated_at") val serverClientUpdatedAt: String,
)

@Serializable
data class ProgressPushResponse(val results: List<ProgressPushResultDto>)

@Serializable
data class ProgressPullResponse(
    val items: List<ProgressItemDto>,
    @SerialName("server_time") val serverTime: String,
)
