"""Read-only, principal-scoped highlight queries shared by REST and MCP."""

import base64
import binascii
import hashlib
import hmac
import json
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import Row, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.api.highlights import HighlightDocument, HighlightItem, wire
from quire_server.core.identity import normalize_metadata_id
from quire_server.db.models import Highlight


class InvalidHighlightCursor(ValueError):
    """An expected client error, distinct from corrupt stored highlight data."""


class HighlightQuery(BaseModel):
    model_config = ConfigDict(extra="forbid")

    q: str | None = Field(default=None, min_length=1, max_length=512)
    metadata_id: str | None = Field(default=None, max_length=1024)
    content_hash: str | None = Field(default=None, min_length=1, max_length=1024)
    limit: int = Field(default=50, ge=1, le=100)
    cursor: str | None = Field(default=None, min_length=1, max_length=2048)

    @field_validator("metadata_id")
    @classmethod
    def normalize_metadata(cls, value: str | None) -> str | None:
        normalized = normalize_metadata_id(value)
        if value is not None and normalized is None:
            raise ValueError("metadata_id must not be blank")
        return normalized

    @field_validator("q", "content_hash")
    @classmethod
    def nonblank(cls, value: str | None) -> str | None:
        if value is not None:
            value = value.strip()
            if not value:
                raise ValueError("must not be blank")
        return value


class HighlightSummary(BaseModel):
    id: UUID
    document: HighlightDocument
    metadata_id_truncated: bool
    text: str
    text_truncated: bool
    book_title: str
    book_title_truncated: bool
    book_author: str | None
    book_author_truncated: bool
    colour: str
    created_at: int
    updated_at: int


def _summary(row: Row) -> HighlightSummary:
    return HighlightSummary(
        id=row.id,
        document=HighlightDocument(
            metadata_id=row.metadata_id,
            content_hash=row.content_hash,
            identity_hash_version=row.identity_hash_version,
        ),
        metadata_id_truncated=row.metadata_id_truncated,
        text=row.text,
        text_truncated=row.text_truncated,
        book_title=row.book_title,
        book_title_truncated=row.book_title_truncated,
        book_author=row.book_author,
        book_author_truncated=row.book_author_truncated,
        colour=row.colour,
        created_at=row.created_at,
        updated_at=row.updated_at,
    )


class HighlightPage(BaseModel):
    items: list[HighlightSummary]
    next_cursor: str | None
    has_more: bool


def _scope(user_id: str, query: HighlightQuery) -> str:
    # No principal or query text is embedded in the opaque cursor. Page size
    # may change between requests; account and filters may not.
    encoded = json.dumps(
        [user_id, query.q, query.metadata_id, query.content_hash],
        ensure_ascii=True,
        separators=(",", ":"),
    ).encode()
    return hashlib.sha256(encoded).hexdigest()


def _encode_cursor(after: UUID, scope: str) -> str:
    payload = json.dumps({"v": 1, "after": str(after), "scope": scope}).encode()
    return base64.urlsafe_b64encode(payload).decode().rstrip("=")


def _decode_cursor(cursor: str, scope: str) -> UUID:
    try:
        raw = base64.b64decode(cursor + "=" * (-len(cursor) % 4), altchars=b"-_", validate=True)
        payload = json.loads(raw)
        if (
            not isinstance(payload, dict)
            or set(payload) != {"v", "after", "scope"}
            or type(payload["v"]) is not int
            or payload["v"] != 1
            or not isinstance(payload["after"], str)
            or not isinstance(payload["scope"], str)
            or not hmac.compare_digest(payload["scope"], scope)
        ):
            raise ValueError
        return UUID(payload["after"])
    except (ValueError, TypeError, binascii.Error, UnicodeDecodeError, RecursionError):
        raise InvalidHighlightCursor("invalid_cursor") from None


async def list_highlights(
    session: AsyncSession, user_id: str, query: HighlightQuery
) -> HighlightPage:
    scope = _scope(user_id, query)
    after = _decode_cursor(query.cursor, scope) if query.cursor else None
    # Project previews in PostgreSQL: fetching whole rows first would read up
    # to 100 large locators and full quotes just to discard most of their bytes.
    statement = select(
        Highlight.id,
        func.left(Highlight.metadata_id, 1024).label("metadata_id"),
        (func.coalesce(func.length(Highlight.metadata_id), 0) > 1024).label(
            "metadata_id_truncated"
        ),
        Highlight.content_hash,
        Highlight.identity_hash_version,
        func.left(Highlight.text, 2000).label("text"),
        (func.length(Highlight.text) > 2000).label("text_truncated"),
        func.left(Highlight.book_title, 1000).label("book_title"),
        (func.length(Highlight.book_title) > 1000).label("book_title_truncated"),
        func.left(Highlight.book_author, 1000).label("book_author"),
        (func.coalesce(func.length(Highlight.book_author), 0) > 1000).label(
            "book_author_truncated"
        ),
        Highlight.colour,
        Highlight.created_at,
        Highlight.updated_at,
    ).where(Highlight.user_id == user_id, Highlight.deleted_at.is_(None))
    if query.metadata_id is not None:
        statement = statement.where(Highlight.metadata_id == query.metadata_id)
    if query.content_hash is not None:
        statement = statement.where(Highlight.content_hash == query.content_hash)
    if query.q is not None:
        # ILIKE supplies Unicode-aware matching according to the database
        # locale. Escape metacharacters so user input is a literal substring.
        literal = query.q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        pattern = "%" + literal + "%"
        statement = statement.where(
            or_(
                Highlight.text.ilike(pattern, escape="\\"),
                Highlight.book_title.ilike(pattern, escape="\\"),
                Highlight.book_author.ilike(pattern, escape="\\"),
            )
        )
    if after is not None:
        statement = statement.where(Highlight.id > after)
    # UUID is immutable, unlike client-supplied clocks. This is a live query,
    # not a snapshot: later inserts before the cursor require a fresh listing.
    rows = (await session.execute(statement.order_by(Highlight.id).limit(query.limit + 1))).all()
    page = rows[: query.limit]
    has_more = len(rows) > query.limit
    return HighlightPage(
        items=[_summary(row) for row in page],
        next_cursor=_encode_cursor(page[-1].id, scope) if has_more else None,
        has_more=has_more,
    )


async def get_highlight(
    session: AsyncSession, user_id: str, highlight_id: UUID
) -> HighlightItem | None:
    row = await session.scalar(
        select(Highlight).where(
            Highlight.user_id == user_id,
            Highlight.id == highlight_id,
            Highlight.deleted_at.is_(None),
        )
    )
    return wire(row) if row is not None else None
