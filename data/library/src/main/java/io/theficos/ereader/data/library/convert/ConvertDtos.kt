package io.theficos.ereader.data.library.convert

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /convert/v1/capabilities`. */
@Serializable
data class ConvertCapabilities(
    val available: Boolean,
    @SerialName("max_bytes") val maxBytes: Long,
    @SerialName("max_pages") val maxPages: Int? = null,
)

@Serializable
internal data class CreateJobBody(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val sha256: String,
    val bytes: Long,
    /** Converted on this server, nothing sent elsewhere; the only mode there is. */
    val mode: String,
)

/**
 * A job as every `/convert/v1/jobs` route answers. Worker-written text ([ConvertFailure.detail],
 * [ConvertReview.reason], issue messages, page labels) comes from an untrusted PDF: plain text only.
 */
@Serializable
data class ConvertJob(
    val id: String,
    val state: String,
    val result: ConvertResult? = null,
    val review: ConvertReview? = null,
    val failure: ConvertFailure? = null,
)

@Serializable
data class ConvertResult(
    val sha256: String,
    val bytes: Long,
)

@Serializable
data class ConvertReview(
    val reason: String? = null,
    @SerialName("affected_pages") val affectedPages: List<ConvertPage> = emptyList(),
)

@Serializable
data class ConvertPage(
    val page: Int,
    val label: String? = null,
)

@Serializable
data class ConvertFailure(
    val code: String,
    val retryable: Boolean = false,
    val detail: String? = null,
)
