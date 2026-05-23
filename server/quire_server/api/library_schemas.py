"""Pydantic schemas for `/library/v1/items`.

Identity travels in the JSON body, never the path (URL-encoded sha256s are a
footgun). Wrapping each request under `{"item": {...}}` keeps the door open
for a future bulk endpoint shaped `{"items": [...]}` without breaking clients.

Request and response shapes are intentionally distinct so they can evolve
independently:

- `LibraryItemRequest` is what the client sends. Server timestamps are
  forbidden here.
- `LibraryItemResponse` is what the server returns. Always includes
  `created_at`, `updated_at`, and (possibly null) `deleted_at`.
"""

from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal

from pydantic import BaseModel, Field, field_serializer


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
