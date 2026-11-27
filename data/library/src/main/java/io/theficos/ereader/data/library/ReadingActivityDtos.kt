package io.theficos.ereader.data.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReadingActivity(
    val id: String,
    @SerialName("book_key") val bookKey: String,
    val title: String,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int,
    @SerialName("active_seconds") val activeSeconds: Long,
    val words: Long? = null,
)

@Serializable
data class ReadingActivityPage(
    @SerialName("cleared_before") val clearedBefore: Long,
    val sessions: List<ReadingActivity>,
    val next: String?,
)

@Serializable
data class ReadingActivityReset(@SerialName("cleared_before") val clearedBefore: Long)

@Serializable
data class ReadingActivityUpload(
    @SerialName("cleared_before") val clearedBefore: Long,
    val sessions: List<ReadingActivity>,
)
