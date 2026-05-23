"""Pydantic schemas for `/library/v1/items` and `/library/v1/sync`.

Identity travels in the JSON body, never the path (URL-encoded sha256s are a
footgun). Wrapping each request under `{"item": {...}}` keeps the door open
for a future bulk endpoint shaped `{"items": [...]}` without breaking clients.

Request and response shapes are intentionally distinct so they can evolve
independently:

- `LibraryItemRequest` is what the client sends to PUT. Server timestamps
  are forbidden here.
- `LibraryItemResponse` is what the server returns. Always includes
  `created_at`, `updated_at`, and (possibly null) `deleted_at`.
- `LibrarySyncRequest`/`LibrarySyncEntry` model the bulk push surface
  introduced by Phase 0 task S-2. Each entry is one of two shapes
  discriminated by `status`: a `present` entry carries metadata, a
  `deleted` entry carries identity only.
"""

from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal
from enum import StrEnum

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    field_serializer,
    field_validator,
    model_validator,
)


class LibraryItemRequest(BaseModel):
    """Body of `PUT /library/v1/items` (inside the `item` wrapper)."""

    metadata_id: str | None = None
    content_hash: str
    # Phase 0, task F-1: identity-hash algorithm version. Default `1`
    # accepts pre-versioning clients; the server's PUT path applies
    # `max(existing, incoming)` so an old client cannot downgrade a row
    # written by a newer client.
    identity_hash_version: int = Field(default=1, ge=1)
    title: str
    authors: list[str] = Field(default_factory=list)
    series_name: str | None = None
    # Wire-side `Decimal | float | None`: pydantic coerces JSON numbers to
    # `Decimal` if the column is `Numeric`, which preserves exactness for the
    # rare fractional series positions (`1.5`).
    series_index: Decimal | None = None
    isbn: str | None = None
    language: str | None = None
    subjects: list[str] = Field(default_factory=list)
    opds_href: str | None = None


class LibraryItemPutBody(BaseModel):
    item: LibraryItemRequest


class LibraryItemIdentity(BaseModel):
    """The identity sub-object inside a DELETE body."""

    content_hash: str
    # Phase 0, task F-1: optional on DELETE. The current matcher keys
    # exclusively on `content_hash` so the field is accepted but not used
    # for row selection — clients can already locate a row by hash alone.
    # Once a real hash-version migration happens this field becomes the
    # tiebreaker for delete semantics.
    identity_hash_version: int = Field(default=1, ge=1)


class LibraryItemDeleteBody(BaseModel):
    item: LibraryItemIdentity


class LibraryItemResponse(BaseModel):
    """Server-persisted row, returned by PUT/DELETE and listed by GET."""

    metadata_id: str | None
    content_hash: str
    # Phase 0, task F-1: always emitted from server, so clients can detect
    # the row's persisted hash-algorithm version and trigger recompute on
    # next sync when their computed-version > N.
    identity_hash_version: int
    title: str
    authors: list[str]
    series_name: str | None
    series_index: Decimal | None
    isbn: str | None
    language: str | None
    subjects: list[str]
    opds_href: str | None
    created_at: datetime
    updated_at: datetime
    deleted_at: datetime | None

    # All datetimes serialize as ISO-8601 with explicit `+00:00`. The `progress`
    # router uses the same trick; clients parse with `Instant.parse(...)`.
    @field_serializer("created_at")
    def _serialize_created_at(self, v: datetime) -> str:
        return _iso(v)

    @field_serializer("updated_at")
    def _serialize_updated_at(self, v: datetime) -> str:
        return _iso(v)

    @field_serializer("deleted_at")
    def _serialize_deleted_at(self, v: datetime | None) -> str | None:
        return None if v is None else _iso(v)

    @field_serializer("series_index")
    def _serialize_series_index(self, v: Decimal | None) -> float | None:
        # JSON doesn't have Decimal; emit as a number. EPUB series positions
        # are at worst a few decimal places (1.5 for novellas), so float-64 is
        # fine on the wire.
        return None if v is None else float(v)


class LibraryItemListResponse(BaseModel):
    items: list[LibraryItemResponse]
    server_time: datetime

    @field_serializer("server_time")
    def _serialize_server_time(self, v: datetime) -> str:
        return _iso(v)


def _iso(v: datetime) -> str:
    if v.tzinfo is None:
        v = v.replace(tzinfo=UTC)
    return v.isoformat()


# ---------------------------------------------------------------------------
# Library stats v0 (PR9)
# ---------------------------------------------------------------------------
# Pure aggregation response. `themes_caveat` is a constant server-emitted
# copy: the client renders it verbatim. Sourcing it server-side means the
# wording can change without an app release.


class TopAuthor(BaseModel):
    name: str
    count: int


class TopTheme(BaseModel):
    theme: str
    count: int
    note: str  # always "v3+ insights only" in v0


# Public copy lives here so both the endpoint and (eventually) the docs page
# can reference the same string. Honest wording (architect review,
# 2026-05-17): the query gates on presence of `book_themes` rows, not on a
# specific `prompt_version` threshold — `QUIRE_SERVER_AI_PROMPT_VERSION` is
# operator-configured and may not be pinned to "4" in every deployment.
LIBRARY_STATS_THEMES_CAVEAT: str = (
    "Theme stats include books with AI theme data; older cached insights "
    "may be missing until regenerated."
)


class LibraryStatsResponse(BaseModel):
    total_books: int
    finished_count: int
    in_progress_count: int
    # PR-9 (Bundle 4): purely additive. The three count buckets
    # (`finished_count`, `in_progress_count`, `abandoned_count`) are
    # mutually disjoint — a book counted under `abandoned_count` is NOT
    # also counted under `in_progress_count` even if its percent > 0.
    # See `library.py::get_stats` for the SQL gates that enforce this.
    abandoned_count: int
    top_authors: list[TopAuthor]
    top_themes: list[TopTheme]
    themes_caveat: str


# ---------------------------------------------------------------------------
# Phase 0, task S-2 — `POST /library/v1/sync` bulk push.
# ---------------------------------------------------------------------------
# The push-model API per the monetization design spec
# (`docs/superpowers/specs/2026-05-22-quire-monetization-design.md`,
# "Architectural changes → Push-model API"). The client is authoritative
# about its library and pushes a bag of {present, deleted} entries here;
# the server upserts and never reaches outward.
#
# Two intentional asymmetries vs `/library/v1/items` (PUT):
#   1. NO `item` wrapper — bulk responses lead with `items:` already, and a
#      `{"items": [...]}` body is idiomatic. This is the same shape the
#      original PUT module-docstring anticipated.
#   2. Two-shape discriminated entry: `status="present"` carries metadata
#      and behaves like the existing PUT upsert; `status="deleted"`
#      carries identity only and tombstones an existing row (idempotent).
#
# Absent entries are NEVER auto-deleted in v1 — the spec's "Entries not
# present in this push are NOT auto-deleted" rule. Clients send an explicit
# tombstone command to delete.


class LibrarySyncStatus(StrEnum):
    """Lifecycle status carried on each sync entry.

    Deliberately narrow — `present` and `deleted` are the only states this
    table durably represents. Reading lifecycle (in-progress, finished,
    abandoned) lives in the `progress` table and reaches the server via
    `/sync/v1/progress`, not here.
    """

    PRESENT = "present"
    DELETED = "deleted"


class LibrarySyncEntry(BaseModel):
    """One book in a `POST /library/v1/sync` request.

    Validation is status-aware (see `_check_present_requires_title`). For
    `status=deleted`, only `identity_hash` (plus the optional
    `identity_hash_version` / `last_seen_at`) is meaningful; metadata fields
    are accepted but ignored. For `status=present`, `title` is required
    (matches the `LibraryItem.title` NOT-NULL column).

    Wire naming (Phase 0, task X-1): this endpoint canonicalizes the
    book-identity field as `identity_hash` per the monetization spec
    ("Architectural changes → Push-model API"). The legacy server DB
    column is still named `content_hash`; the handler bridges the rename
    at the ORM boundary. Unknown fields are rejected (`extra='forbid'`)
    so a legacy client sending `content_hash` fails loudly with 422
    rather than silently dropping its identity payload.
    """

    model_config = ConfigDict(extra="forbid")

    # Identity (the only required field across both statuses).
    identity_hash: str = Field(min_length=1)

    # Phase 0, task F-1: identity-hash algorithm version. Defaults to 1 so
    # pre-versioning clients round-trip. The sync handler applies
    # `max(existing, incoming)` on upsert — an old client can never
    # downgrade a row written by a newer client.
    identity_hash_version: int = Field(default=1, ge=1)

    # Lifecycle status — defaults to `present` so a minimal payload
    # `{"content_hash": "..."}` is interpreted as "this book exists,
    # treat metadata as unset/empty".
    status: LibrarySyncStatus = LibrarySyncStatus.PRESENT

    # Client-supplied freshness signal. The spec calls this out on the
    # wire. THIS IS NOT WRITTEN TO THE DATABASE in this PR (no column
    # exists; adding one is a deliberately separate change). The handler
    # validates it (tz-aware ISO-8601) and ignores it. The field is
    # surfaced so future server features (per-book TTL, stale-detection)
    # can adopt it without a wire-protocol change.
    last_seen_at: datetime | None = None

    # Metadata block — optional on the wire but `title` becomes required
    # for `present` entries via the model validator below.
    metadata_id: str | None = None
    title: str | None = None
    authors: list[str] = Field(default_factory=list)
    series_name: str | None = None
    series_index: Decimal | None = None
    isbn: str | None = None
    language: str | None = None
    subjects: list[str] = Field(default_factory=list)
    opds_href: str | None = None

    @field_validator("last_seen_at")
    @classmethod
    def _last_seen_at_must_be_aware(cls, v: datetime | None) -> datetime | None:
        """Reject naive datetimes.

        Server-side timestamp arithmetic assumes UTC. Accepting a naive
        timestamp here would let a misconfigured client poison future
        comparisons silently. Pydantic v2's default ISO-8601 parser keeps
        the original tzinfo, so we can detect naive values explicitly.
        """
        if v is not None and v.tzinfo is None:
            raise ValueError(
                "last_seen_at must be timezone-aware (e.g. ISO-8601 with 'Z' or +HH:MM)"
            )
        return v

    @model_validator(mode="after")
    def _check_present_requires_title(self) -> LibrarySyncEntry:
        """`present` entries need a title; `deleted` entries do not.

        The underlying `library_items.title` column is NOT NULL. The PUT
        endpoint enforces this implicitly via Pydantic — `title: str`.
        Sync makes it optional at the field level so deletions don't need
        bogus metadata, then re-asserts the constraint here for `present`.
        """
        if self.status is LibrarySyncStatus.PRESENT and not (self.title and self.title.strip()):
            raise ValueError("title is required when status='present'")
        return self


class LibrarySyncRequest(BaseModel):
    """Body of `POST /library/v1/sync`.

    Empty `items` is valid (the client telling the server "I've seen
    nothing new this cycle" is a legitimate no-op heartbeat).
    """

    model_config = ConfigDict(extra="forbid")

    items: list[LibrarySyncEntry] = Field(default_factory=list)


class LibrarySyncSummary(BaseModel):
    """Response body of `POST /library/v1/sync` — counts plus server time.

    The handler returns counts, not per-row diffs. For 500 entries a
    per-row response is wasted bytes (the client trusts the counts; if it
    wants per-row state it calls `GET /library/v1/items?since=`).
    """

    model_config = ConfigDict(extra="forbid")

    # Raw count of entries in the request body, pre-dedup. Useful to
    # confirm the client and server agree on what was sent.
    received: int
    # Entries the server actually inspected, post intra-payload dedupe
    # (`processed <= received`).
    processed: int
    # New rows inserted.
    created: int
    # Existing alive rows whose persisted fields changed.
    updated: int
    # Existing tombstoned rows that were brought back to life via a
    # `present` entry — a write-amplification metric that helps the
    # client understand churn.
    reactivated: int
    # Existing alive rows transitioned to tombstone via `status=deleted`.
    deleted: int
    # Entries that produced no DB change. Either:
    #   - `present` for a row whose persisted fields already match
    #     (no-op replay), or
    #   - `deleted` for an already-tombstoned row (preserves timestamps so
    #     `?since=` doesn't re-deliver the same tombstone forever).
    skipped: int
    # `deleted` entries that referenced an `identity_hash` the server has
    # never seen for this user. Counted, not failed — the server is not
    # the source of truth for what the client believes it deleted, and a
    # stale tombstone command is harmless.
    missing_deleted: int
    # Wall-clock from BEFORE the writes. Same cursor shape as the
    # existing `GET ?since=` endpoint — clients use this as the next
    # delta-fetch cursor.
    server_time: datetime

    @field_serializer("server_time")
    def _serialize_server_time(self, v: datetime) -> str:
        return _iso(v)
