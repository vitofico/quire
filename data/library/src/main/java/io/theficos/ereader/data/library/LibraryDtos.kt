package io.theficos.ereader.data.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TopAuthor(val name: String, val count: Int)

@Serializable
data class TopTheme(val theme: String, val count: Int, val note: String)

/**
 * Response body of `GET /library/v1/stats` (PR9).
 *
 * `themesCaveat` is a constant copy emitted by the server (sourcing it
 * server-side means the wording can change without an app release). The
 * client renders it verbatim under the top-themes section.
 */
@Serializable
data class LibraryStatsResponse(
    @SerialName("total_books") val totalBooks: Int,
    @SerialName("finished_count") val finishedCount: Int,
    @SerialName("in_progress_count") val inProgressCount: Int,
    @SerialName("top_authors") val topAuthors: List<TopAuthor>,
    @SerialName("top_themes") val topThemes: List<TopTheme>,
    @SerialName("themes_caveat") val themesCaveat: String,
)

/**
 * Body of `PUT /library/v1/items` — the identity (`content_hash`) travels in
 * the JSON body, not the path. Mirrors
 * `server/quire_server/api/library_schemas.py:LibraryItemRequest`.
 *
 * `series_index` is a wire-side double — the server stores it as Postgres
 * `Numeric` for exactness, but serializes as a JSON number (float-64 is
 * plenty for the rare 1.5-style novella positions).
 *
 * Optional list fields default to empty rather than null because the server
 * tolerates either, and emitting `[]` keeps the payload self-describing.
 */
@Serializable
data class LibraryItemRequest(
    @SerialName("content_hash") val contentHash: String,
    val title: String,
    val authors: List<String> = emptyList(),
    @SerialName("metadata_id") val metadataId: String? = null,
    @SerialName("series_name") val seriesName: String? = null,
    @SerialName("series_index") val seriesIndex: Double? = null,
    val isbn: String? = null,
    val language: String? = null,
    val subjects: List<String> = emptyList(),
    @SerialName("opds_href") val opdsHref: String? = null,
)

/**
 * Single-item wrapper required by the server. The shape keeps the door open
 * for a future bulk endpoint shaped `{"items": [...]}` without breaking
 * clients.
 */
@Serializable
data class LibraryItemPutBody(val item: LibraryItemRequest)

/**
 * Response body of `PUT /library/v1/items`. Server-owned timestamps are
 * always present; `deleted_at` is non-null for tombstones (only returned via
 * `GET ?since=`, never by PUT).
 *
 * Datetimes arrive as ISO-8601 with explicit `+00:00`. The current uploader
 * doesn't parse them — they're kept as strings so the parse cost is paid
 * only by callers that actually need them.
 */
@Serializable
data class LibraryItemResponse(
    @SerialName("content_hash") val contentHash: String,
    val title: String,
    val authors: List<String> = emptyList(),
    @SerialName("metadata_id") val metadataId: String? = null,
    @SerialName("series_name") val seriesName: String? = null,
    @SerialName("series_index") val seriesIndex: Double? = null,
    val isbn: String? = null,
    val language: String? = null,
    val subjects: List<String> = emptyList(),
    @SerialName("opds_href") val opdsHref: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)
