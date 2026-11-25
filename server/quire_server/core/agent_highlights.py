"""Read-only, principal-scoped highlight queries shared by REST and MCP."""

import base64
import binascii
import hashlib
import hmac
import json
from collections.abc import Callable
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
    comment: str | None
    comment_truncated: bool
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
        comment=row.comment,
        comment_truncated=row.comment_truncated,
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


def scope(*parts: str | None) -> str:
    """What a cursor is bound to: the account and the filters, hashed.
    No principal or query text is embedded in the opaque cursor. Page size
    may change between requests; account and filters may not."""
    encoded = json.dumps(list(parts), ensure_ascii=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def encode_cursor(after: object, scope: str) -> str:
    payload = json.dumps({"v": 1, "after": str(after), "scope": scope}).encode()
    return base64.urlsafe_b64encode(payload).decode().rstrip("=")


def decode_cursor[T](cursor: str, scope: str, parse: Callable[[str], T]) -> T:
    """The position a cursor names, read by ``parse``; a cursor that is
    malformed, foreign or for other filters is ``InvalidHighlightCursor``."""
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
        return parse(payload["after"])
    except (ValueError, TypeError, binascii.Error, UnicodeDecodeError, RecursionError):
        raise InvalidHighlightCursor("invalid_cursor") from None


async def list_highlights(
    session: AsyncSession, user_id: str, query: HighlightQuery
) -> HighlightPage:
    bound = scope(user_id, query.q, query.metadata_id, query.content_hash)
    after = decode_cursor(query.cursor, bound, UUID) if query.cursor else None
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
        func.left(Highlight.comment, 2000).label("comment"),
        (func.coalesce(func.length(Highlight.comment), 0) > 2000).label("comment_truncated"),
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
                Highlight.comment.ilike(pattern, escape="\\"),
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
        next_cursor=encode_cursor(page[-1].id, bound) if has_more else None,
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


class HighlightBatch(BaseModel):
    items: list[HighlightItem]
    not_found: list[UUID]


async def get_highlights(
    session: AsyncSession, user_id: str, highlight_ids: list[UUID]
) -> HighlightBatch:
    """Complete active highlights in the order asked, each once; the IDs that
    are missing, foreign or removed are listed alike in ``not_found``."""
    wanted = list(dict.fromkeys(highlight_ids))
    rows = (
        await session.scalars(
            select(Highlight).where(
                Highlight.user_id == user_id,
                Highlight.id.in_(wanted),
                Highlight.deleted_at.is_(None),
            )
        )
    ).all()
    found = {row.id: row for row in rows}
    return HighlightBatch(
        items=[wire(found[i]) for i in wanted if i in found],
        not_found=[i for i in wanted if i not in found],
    )


class HighlightedBook(BaseModel):
    document: HighlightDocument
    book_title: str
    book_title_truncated: bool
    book_author: str | None
    book_author_truncated: bool
    highlight_count: int
    noted_count: int
    first_highlight_at: int
    last_highlight_at: int


class HighlightedBookPage(BaseModel):
    items: list[HighlightedBook]
    next_cursor: str | None
    has_more: bool


def _book_position(after: str) -> tuple[int, str]:
    updated, content_hash = after.split(":", 1)
    return int(updated), content_hash


async def list_highlighted_books(
    session: AsyncSession, user_id: str, *, limit: int, cursor: str | None
) -> HighlightedBookPage:
    """The books with active highlights, one per ``content_hash`` (the key
    ``list_highlights`` filters on), most recently highlighted first."""
    bound = scope("books", user_id)
    after = decode_cursor(cursor, bound, _book_position) if cursor else None
    live = (Highlight.user_id == user_id, Highlight.deleted_at.is_(None))
    counts = (
        select(
            Highlight.content_hash,
            func.count().label("highlight_count"),
            func.count().filter(func.btrim(Highlight.comment) != "").label("noted_count"),
            func.min(Highlight.created_at).label("first_highlight_at"),
            func.max(Highlight.updated_at).label("last_highlight_at"),
        )
        .where(*live)
        .group_by(Highlight.content_hash)
        .subquery()
    )
    # Title, author and identity come from the book's latest highlight, so a
    # corrected title wins over an older spelling.
    latest = (
        select(
            Highlight.content_hash,
            Highlight.metadata_id,
            Highlight.identity_hash_version,
            func.left(Highlight.book_title, 1000).label("book_title"),
            (func.length(Highlight.book_title) > 1000).label("book_title_truncated"),
            func.left(Highlight.book_author, 1000).label("book_author"),
            (func.coalesce(func.length(Highlight.book_author), 0) > 1000).label(
                "book_author_truncated"
            ),
            func.row_number()
            .over(
                partition_by=Highlight.content_hash,
                order_by=(Highlight.updated_at.desc(), Highlight.id.desc()),
            )
            .label("rank"),
        )
        .where(*live)
        .subquery()
    )
    books = (
        select(
            counts,
            latest.c.metadata_id,
            latest.c.identity_hash_version,
            latest.c.book_title,
            latest.c.book_title_truncated,
            latest.c.book_author,
            latest.c.book_author_truncated,
        )
        .join(latest, latest.c.content_hash == counts.c.content_hash)
        .where(latest.c.rank == 1)
        .subquery()
    )
    statement = select(books)
    if after is not None:
        statement = statement.where(
            or_(
                books.c.last_highlight_at < after[0],
                (books.c.last_highlight_at == after[0]) & (books.c.content_hash > after[1]),
            )
        )
    rows = (
        await session.execute(
            statement.order_by(books.c.last_highlight_at.desc(), books.c.content_hash.asc()).limit(
                limit + 1
            )
        )
    ).all()
    page = rows[:limit]
    has_more = len(rows) > limit
    last = page[-1] if page else None
    return HighlightedBookPage(
        items=[
            HighlightedBook(
                document=HighlightDocument(
                    metadata_id=row.metadata_id,
                    content_hash=row.content_hash,
                    identity_hash_version=row.identity_hash_version,
                ),
                book_title=row.book_title,
                book_title_truncated=row.book_title_truncated,
                book_author=row.book_author,
                book_author_truncated=row.book_author_truncated,
                highlight_count=row.highlight_count,
                noted_count=row.noted_count,
                first_highlight_at=row.first_highlight_at,
                last_highlight_at=row.last_highlight_at,
            )
            for row in page
        ],
        next_cursor=(
            encode_cursor(f"{last.last_highlight_at}:{last.content_hash}", bound)
            if has_more and last is not None
            else None
        ),
        has_more=has_more,
    )
