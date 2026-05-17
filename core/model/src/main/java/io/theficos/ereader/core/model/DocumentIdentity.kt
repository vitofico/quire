package io.theficos.ereader.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors `DocumentIdentity` in `server/opds_sync/api/ai_schemas.py`.
 *
 * Canonical schemes (`metadataId`, `contentHash`) identify a downloaded EPUB
 * by stable byte-level or OPF-derived hashes. Alias fields (`opdsHref`,
 * `opdsDcId`, `calibreBookId`, `isbn`) are used pre-download by the
 * catalog-preview flow (PR7); the server resolves them to a canonical via
 * `insight_identity_aliases` (PR2).
 *
 * Invariant: at least one field must be non-null. The previous post-download
 * invariant (`contentHash` non-empty) is enforced by the call site
 * (`EpubIdentityExtractor`), not the type — pre-download paths legitimately
 * have no `contentHash`.
 */
@Serializable
data class DocumentIdentity(
    @SerialName("metadata_id") val metadataId: String? = null,
    @SerialName("content_hash") val contentHash: String? = null,
    @SerialName("opds_dc_id") val opdsDcId: String? = null,
    @SerialName("opds_href") val opdsHref: String? = null,
    @SerialName("calibre_book_id") val calibreBookId: String? = null,
    val isbn: String? = null,
) {
    init {
        require(
            metadataId != null ||
                contentHash != null ||
                opdsDcId != null ||
                opdsHref != null ||
                calibreBookId != null ||
                isbn != null,
        ) { "DocumentIdentity needs at least one canonical or alias hint" }
    }
}
