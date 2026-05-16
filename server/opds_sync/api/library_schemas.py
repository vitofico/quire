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


class LibraryItemDeleteBody(BaseModel):
    item: LibraryItemIdentity


class LibraryItemResponse(BaseModel):
    """Server-persisted row, returned by PUT/DELETE and listed by GET."""

    metadata_id: str | None
    content_hash: str
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
