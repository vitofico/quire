package io.theficos.ereader.data.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for `POST /library/v1/sync` — the bulk library-mirror push
 * introduced by Phase 0 task S-2 server-side and consumed by Phase 0 task
 * A-4 (Android library-mirror push WorkManager job).
 *
 * Identity-naming contract (Phase 0 / X-1): the JSON field for book identity
 * on this endpoint is `identity_hash`. The legacy server DB column is still
 * `content_hash`; the server bridges the rename at the ORM boundary. The
 * Pydantic model uses `extra="forbid"`, so any unknown wire field — most
 * importantly `content_hash` on this endpoint — fails the whole request
 * with a 422. The `LibrarySyncEntry` below uses `identity_hash` exclusively.
 *
 * Shape is FLAT — the server's `LibrarySyncEntry` carries metadata fields
 * at the top level rather than under a nested `metadata` object. We mirror
 * that here so kotlinx-serialization round-trips byte-identical to the
 * server's wire contract.
 */

@Serializable
enum class LibrarySyncStatus {
    @SerialName("present") PRESENT,
    @SerialName("deleted") DELETED,
}

/**
 * One book in a `POST /library/v1/sync` request body.
 *
 * Status semantics:
 * - `PRESENT` carries metadata and behaves like an upsert. `title` is
 *   required server-side for `present` entries.
 * - `DELETED` carries identity only and tombstones the row. Metadata
 *   fields on a `DELETED` entry are accepted by the server but ignored.
 *
 * `lastSeenAt` is a wire-only freshness signal: the server validates it
 * (tz-aware ISO-8601) but does NOT persist it in v1. The worker reuses a
 * single value across every entry in one run so logs and server-side
 * debugging stay coherent.
 *
 * `identityHashVersion` defaults to `1` for wire back-compat with older
 * servers; on F-2+ clients the field is propagated from
 * `DocumentEntity.identityHashVersion`.
 */
@Serializable
data class LibrarySyncEntry(
    @SerialName("identity_hash") val identityHash: String,
    @SerialName("identity_hash_version") val identityHashVersion: Int = 1,
    val status: LibrarySyncStatus = LibrarySyncStatus.PRESENT,
    // ISO-8601 with timezone (e.g. "2026-05-22T15:00:00Z"). Generated once
    // per worker run and reused across chunks.
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("metadata_id") val metadataId: String? = null,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    @SerialName("series_name") val seriesName: String? = null,
    @SerialName("series_index") val seriesIndex: Double? = null,
    val isbn: String? = null,
    val language: String? = null,
    val subjects: List<String> = emptyList(),
    @SerialName("opds_href") val opdsHref: String? = null,
)

/**
 * Body of `POST /library/v1/sync`. The server enforces a per-request cap
 * (`library_sync_max_items = 500`); callers chunk before hitting this wire.
 * An empty `items` list is a valid heartbeat ping.
 */
@Serializable
data class LibrarySyncRequest(val items: List<LibrarySyncEntry>)

/**
 * Response body of `POST /library/v1/sync` — aggregate counts only.
 *
 * The server intentionally does NOT echo per-entry results; clients that
 * want row-level state poll `GET /library/v1/items?since=`. `serverTime`
 * mirrors that endpoint's cursor shape so the client can chain delta-fetch
 * after a successful push.
 *
 * `ignoreUnknownKeys = true` on the [LibraryClient] Json instance means a
 * server that adds new counters (Phase 1+) won't break older clients.
 */
@Serializable
data class LibrarySyncSummary(
    val received: Int,
    val processed: Int,
    val created: Int,
    val updated: Int,
    val reactivated: Int,
    val deleted: Int,
    val skipped: Int,
    @SerialName("missing_deleted") val missingDeleted: Int,
    @SerialName("server_time") val serverTime: String,
)
