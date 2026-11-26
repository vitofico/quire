package io.theficos.ereader.data.ai

import io.theficos.ereader.core.model.DocumentIdentity
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DetailsLookupBody(
    val identity: DocumentIdentity,
    val book: DetailsBook,
    /** Where [book] came from: [ORIGIN_FILE] or [ORIGIN_CATALOG]. */
    val source: String,
    /** Where the cover the book already has came from, or null when it has none. */
    val cover: String?,
    /** [COVER_FIND], [COVER_DRAW] or [COVER_PAINT]; never sent when null, since a server without the field rejects it. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("cover_choice") val coverChoice: String? = null,
)

@Serializable
data class DetailsCover(
    val source: String? = null,
    /** Absolute for Open Library, a server path for a generated cover. */
    val url: String? = null,
    val generated: Boolean = false,
    /** Painted artwork for this generated cover is on its way: a later lookup answers with a new [url]. */
    val painting: Boolean = false,
    /** This generated cover is drawn on painted artwork. */
    val painted: Boolean = false,
)

@Serializable
data class DetailsResponse(
    val key: String,
    val book: DetailsBook,
    /** Where each field that has a value came from, by its name in [DetailsBook] and `cover`. */
    val origins: Map<String, String> = emptyMap(),
    val cover: DetailsCover = DetailsCover(),
    val tidy: String,
    /** Served from the shared store, at no cost, including the record the reader was last given. */
    val cached: Boolean = false,
    /** The matched Open Library work, for attribution. */
    @SerialName("openlibrary_url") val openLibraryUrl: String? = null,
    /** How often the app has asked again for artwork still being painted. The app's own count, kept with the answer. */
    @SerialName("painting_checks") val paintingChecks: Int = 0,
    // The reader's choices, kept by the app with the answer; the server never sends them.
    /** The field groups ([DETAILS_FIELDS]) the reader kept from this answer; null keeps them all. */
    val accepted: List<String>? = null,
    /** The cover the reader chose, [COVER_FIND], [COVER_DRAW] or [COVER_PAINT]; null keeps the book's own. */
    @SerialName("cover_choice") val coverChoice: String? = null,
    /** The key of a tidied record the reader rejected: served again, it shows none of its changes. */
    val declined: String? = null,
    /** The book's details as sent for this answer, so a field the catalog changed since shows the catalog's value. */
    val basis: DetailsBook? = null,
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

const val COVER_KEEP = "keep"
const val COVER_FIND = "find"
const val COVER_DRAW = "draw"
const val COVER_PAINT = "paint"

const val FIELD_TITLE = "title"
const val FIELD_AUTHORS = "authors"
const val FIELD_SERIES = "series"
const val FIELD_DESCRIPTION = "description"

/** The details a reader accepts or rejects one by one. */
val DETAILS_FIELDS = listOf(FIELD_TITLE, FIELD_AUTHORS, FIELD_SERIES, FIELD_DESCRIPTION)
