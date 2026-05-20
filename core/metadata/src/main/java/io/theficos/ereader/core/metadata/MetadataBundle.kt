package io.theficos.ereader.core.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The bundle of EPUB metadata sent to the server when requesting AI insights.
 * Mirrors `MetadataBundle` in `server/quire_server/api/ai_schemas.py`.
 */
@Serializable
data class MetadataBundle(
    val title: String,
    val author: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val publisher: String? = null,
    @SerialName("publish_date") val publishDate: String? = null,
    val subjects: List<String> = emptyList(),
    val description: String? = null,
    @SerialName("series_name") val seriesName: String? = null,
    @SerialName("series_position") val seriesPosition: Int? = null,
)
