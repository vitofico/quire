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
