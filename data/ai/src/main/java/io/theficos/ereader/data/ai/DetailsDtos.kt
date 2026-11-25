package io.theficos.ereader.data.ai

import io.theficos.ereader.core.model.DocumentIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A book's details as `/details/v1` takes and returns them. Every text is plain text. */
@Serializable
data class DetailsBook(
    val title: String,
    val authors: List<String> = emptyList(),
    @SerialName("series_name") val seriesName: String? = null,
    @SerialName("series_index") val seriesIndex: Double? = null,
    val description: String? = null,
    val subjects: List<String> = emptyList(),
    val language: String? = null,
    val isbn: String? = null,
    val year: Int? = null,
    val publisher: String? = null,
)

/** Body of `POST /details/v1/lookup` and `POST /details/v1/tidy`. */
@Serializable
data class DetailsLookupBody(
    val identity: DocumentIdentity,
    val book: DetailsBook,
    /** Where [book] came from: [ORIGIN_FILE] or [ORIGIN_CATALOG]. */
    val source: String,
    /** Where the cover the book already has came from, or null when it has none. */
    val cover: String?,
)

@Serializable
data class DetailsCover(
    val source: String? = null,
    /** Absolute for Open Library, a server path for a generated cover. */
    val url: String? = null,
    val generated: Boolean = false,
)

@Serializable
data class DetailsResponse(
    val key: String,
    val book: DetailsBook,
    /** Where each field that has a value came from, by its name in [DetailsBook] and `cover`. */
    val origins: Map<String, String> = emptyMap(),
    val cover: DetailsCover = DetailsCover(),
    val tidy: String,
    /** The matched Open Library work, for attribution. */
    @SerialName("openlibrary_url") val openLibraryUrl: String? = null,
)

const val ORIGIN_FILE = "file"
const val ORIGIN_CATALOG = "catalog"
const val ORIGIN_OPEN_LIBRARY = "openlibrary"

/** How the app labels where a found field came from. */
enum class DetailsOrigin { OWN, OPEN_LIBRARY, TIDIED_BY_AI, WRITTEN_BY_AI }

/** [origin] as the server names it; any name the app does not know counts as made by AI, never as the book's own. */
fun detailsOrigin(origin: String?): DetailsOrigin? = when (origin) {
    null -> null
    ORIGIN_FILE, ORIGIN_CATALOG -> DetailsOrigin.OWN
    ORIGIN_OPEN_LIBRARY -> DetailsOrigin.OPEN_LIBRARY
    "ai_written" -> DetailsOrigin.WRITTEN_BY_AI
    else -> DetailsOrigin.TIDIED_BY_AI
}

const val TIDY_QUOTA_EXCEEDED = "quota_exceeded"
const val TIDY_FAILED = "failed"
