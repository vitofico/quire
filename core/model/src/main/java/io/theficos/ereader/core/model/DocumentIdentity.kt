package io.theficos.ereader.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Schema version of the client-side identity-hash function. Stamped on every
 * record that carries an identity hash (Room rows + outbound DTOs) so a future
 * change to the hash function (edition merging, normalization fixes, etc.)
 * doesn't break sync, caches, recs, export, or deletion across persisted data.
 *
 * Phase-0 task F-2 introduces this constant at value `1` — the current MD5-
 * sampled hash in `core/identity/ContentHash.kt`. Bumping this constant later
 * requires:
 *   1. Updating the hash function.
 *   2. A migration that re-computes hashes for local rows and re-uploads them.
 *   3. Server-side acceptance of the new version (sibling task F-1 introduces
 *      the wire field; further bumps are coordinated with the server team).
 *
 * The companion [isStaleHashVersion] predicate is the explicit "needs rehash"
 * check; today it is a no-op (no rows exist at version < 1) but the mechanism
 * is wired so a future version bump only needs to flip the constant.
 */
const val CURRENT_IDENTITY_HASH_VERSION: Int = 1

/**
 * True iff a record stamped with [rowVersion] was produced by an older client
 * hash function and should be re-hashed before its next push to the server.
 *
 * Today this is `rowVersion < 1`, which is always false — bumping
 * [CURRENT_IDENTITY_HASH_VERSION] activates the predicate for legacy rows.
 */
fun isStaleHashVersion(rowVersion: Int): Boolean = rowVersion < CURRENT_IDENTITY_HASH_VERSION

/**
 * Mirrors `DocumentIdentity` in `server/quire_server/api/ai_schemas.py`.
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
 *
 * `identityHashVersion` (Phase-0 / F-2) stamps which version of the
 * client-side hash function produced [contentHash]. Defaults to
 * [CURRENT_IDENTITY_HASH_VERSION] so callers building an identity from a
 * freshly-computed hash get the right value automatically; defaults to `1`
 * on the wire so an older server that doesn't emit the field decodes
 * correctly (every existing hash on the network is v1).
 */
@Serializable
data class DocumentIdentity(
    @SerialName("metadata_id") val metadataId: String? = null,
    @SerialName("content_hash") val contentHash: String? = null,
    @SerialName("opds_dc_id") val opdsDcId: String? = null,
    @SerialName("opds_href") val opdsHref: String? = null,
    @SerialName("calibre_book_id") val calibreBookId: String? = null,
    val isbn: String? = null,
    @SerialName("identity_hash_version") val identityHashVersion: Int = 1,
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
