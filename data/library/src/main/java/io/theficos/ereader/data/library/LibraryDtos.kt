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
    // PR-9 (Bundle 4): additive. The `= 0` default tolerates a server that
    // hasn't yet deployed PR-9 (back-compat). Combined with
    // `LibraryClient`'s `ignoreUnknownKeys = true`, the response is
    // forward-compat too: a newer server adding more fields won't break
    // older clients.
    @SerialName("abandoned_count") val abandonedCount: Int = 0,
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
    // Phase-0 / F-2: stamps which client hash-function version produced
    // `contentHash`. Defaults to `1` for wire back-compat with an older
    // server that doesn't yet emit the field (F-1 lands it server-side).
    @SerialName("identity_hash_version") val identityHashVersion: Int = 1,
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
    // Phase-0 / F-2: defaults to `1` so an older server (pre-F-1) decodes
    // safely — every hash on the wire today is v1.
    @SerialName("identity_hash_version") val identityHashVersion: Int = 1,
)

/**
 * Response body of `GET /library/v1/items` (paginated). `server_time` is the
 * snapshot instant the server bounded the page to; the restore path ignores it
 * (best-effort snapshot semantics) but it's decoded for completeness.
 */
@Serializable
data class LibraryItemListResponse(
    val items: List<LibraryItemResponse>,
    @SerialName("server_time") val serverTime: String,
)
