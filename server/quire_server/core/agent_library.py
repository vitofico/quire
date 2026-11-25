"""Read-only, principal-scoped views of the reader's library for MCP: the
shelf with reading progress, and one book with its tidied details."""

import hashlib
from datetime import datetime
from typing import Literal

from pydantic import BaseModel
from sqlalchemy import and_, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.api.highlights import HighlightDocument
from quire_server.core.agent_highlights import decode_cursor, encode_cursor, scope
from quire_server.db.models import (
    BookDetails,
    BookDetailsReader,
    Document,
    Highlight,
    LibraryItem,
    Progress,
)

Status = Literal["unread", "reading", "finished", "abandoned"]
_SUBJECTS = 20
_DESCRIPTION = 4000


class LibraryBook(BaseModel):
    document: HighlightDocument
    title: str
    authors: list[str]
    series_name: str | None
    series_index: float | None
    language: str | None
    subjects: list[str]
    status: Status
    percent: float | None
    last_read_at: datetime | None
    finished_at: datetime | None
    abandoned_at: datetime | None
    highlight_count: int


class LibraryPage(BaseModel):
    items: list[LibraryBook]
    next_cursor: str | None
    has_more: bool


class BookRecord(BaseModel):
    """The book's details as tidied by ``/details/v1`` for this reader."""

    title: str
    authors: list[str]
    series_name: str | None
    series_index: float | None
    description: str | None
    subjects: list[str]
    generated_at: datetime


class BookView(LibraryBook):
    isbn: str | None
    details: BookRecord | None


def _status(row) -> Status:
    if row.finished_at is not None:
        return "finished"
    if row.abandoned_at is not None:
        return "abandoned"
    return "reading" if row.percent is not None else "unread"


def _shelf(user_id: str):
    # The same (user_id, content_hash) bridge as /library/v1/stats: a
    # library row and a progress document are separate records.
    return (
        select(
            LibraryItem,
            Progress.percent,
            Progress.client_updated_at.label("last_read_at"),
            Progress.finished_at,
            Progress.abandoned_at,
        )
        .outerjoin(
            Document,
            and_(
                Document.user_id == LibraryItem.user_id,
                Document.content_hash == LibraryItem.content_hash,
            ),
        )
        .outerjoin(Progress, Progress.document_pk == Document.pk)
        .where(LibraryItem.user_id == user_id, LibraryItem.deleted_at.is_(None))
    )


async def _highlight_counts(session: AsyncSession, user_id: str, rows) -> dict[str, int]:
    """Active highlights per book on the page, in one grouped query."""
    hashes = {row.LibraryItem.content_hash for row in rows}
    if not hashes:
        return {}
    counted = await session.execute(
        select(Highlight.content_hash, func.count())
        .where(
            Highlight.user_id == user_id,
            Highlight.content_hash.in_(hashes),
            Highlight.deleted_at.is_(None),
        )
        .group_by(Highlight.content_hash)
    )
    return dict(counted.all())


def _book(row, highlights: dict[str, int]) -> dict:
    item: LibraryItem = row.LibraryItem
    return {
        "document": HighlightDocument(
            metadata_id=item.metadata_id,
            content_hash=item.content_hash,
            identity_hash_version=item.identity_hash_version,
        ),
        "title": item.title,
        "authors": item.authors or [],
        "series_name": item.series_name,
        "series_index": float(item.series_index) if item.series_index is not None else None,
        "language": item.language,
        "subjects": (item.subjects or [])[:_SUBJECTS],
        "status": _status(row),
        "percent": row.percent,
        "last_read_at": row.last_read_at,
        # Finished wins over abandoned on legacy rows, as the sync pull does.
        "finished_at": row.finished_at,
        "abandoned_at": None if row.finished_at is not None else row.abandoned_at,
        "highlight_count": highlights.get(item.content_hash, 0),
    }


async def list_library(
    session: AsyncSession,
    user_id: str,
    *,
    status: Status | None,
    limit: int,
    cursor: str | None,
) -> LibraryPage:
    """The reader's books, most recently added first, optionally one status."""
    bound = scope("library", user_id, status)
    after = decode_cursor(cursor, bound, int) if cursor else None
    statement = _shelf(user_id)
    if status == "finished":
        statement = statement.where(Progress.finished_at.is_not(None))
    elif status == "abandoned":
        statement = statement.where(
            Progress.abandoned_at.is_not(None), Progress.finished_at.is_(None)
        )
    elif status == "reading":
        statement = statement.where(
            Progress.document_pk.is_not(None),
            Progress.finished_at.is_(None),
            Progress.abandoned_at.is_(None),
        )
    elif status == "unread":
        statement = statement.where(Progress.document_pk.is_(None))
    if after is not None:
        statement = statement.where(LibraryItem.pk < after)
    rows = (await session.execute(statement.order_by(LibraryItem.pk.desc()).limit(limit + 1))).all()
    page = rows[:limit]
    has_more = len(rows) > limit
    highlights = await _highlight_counts(session, user_id, page)
    return LibraryPage(
        items=[LibraryBook(**_book(row, highlights)) for row in page],
        next_cursor=encode_cursor(page[-1].LibraryItem.pk, bound) if has_more else None,
        has_more=has_more,
    )


def _record(record: BookDetails, item: LibraryItem) -> BookRecord:
    found = (record.record.get("found") or {}).get("book") or {}
    index = record.record.get("series_index")
    description = record.record.get("description")
    return BookRecord(
        title=record.record.get("title") or item.title,
        authors=record.record.get("authors") or [],
        series_name=record.record.get("series_name"),
        series_index=float(index) if index is not None else None,
        description=description[:_DESCRIPTION] if description else None,
        subjects=(found.get("subjects") or [])[:_SUBJECTS],
        generated_at=record.generated_at,
    )


async def _details(session: AsyncSession, user_id: str, item: LibraryItem) -> BookRecord | None:
    """The record this reader was last given by ``/details/v1`` for the book,
    found through their own pointer, the metadata ID's first."""
    # Imported here: sync-only servers never load the AI modules.
    from quire_server.core.ai.details import DETAILS_PROMPT_VERSION

    identities = [f"content_hash:{item.content_hash}"]
    if item.metadata_id:
        identities.insert(0, f"metadata_id:{item.metadata_id}")
    for identity in identities:
        record = await session.scalar(
            select(BookDetails)
            .join(BookDetailsReader, BookDetailsReader.key == BookDetails.key)
            .where(
                BookDetailsReader.user_id == user_id,
                BookDetailsReader.identity_hash == hashlib.sha256(identity.encode()).hexdigest(),
            )
        )
        if record is None or record.prompt_version != DETAILS_PROMPT_VERSION:
            continue
        try:
            return _record(record, item)
        except (AttributeError, TypeError, ValueError):
            # A malformed stored record reads as none rather than failing the book.
            return None
    return None


async def get_book(
    session: AsyncSession,
    user_id: str,
    *,
    metadata_id: str | None,
    content_hash: str | None,
    with_details: bool,
) -> BookView | None:
    """One book on the shelf; both identities must match when both are given."""
    statement = _shelf(user_id)
    if metadata_id is not None:
        statement = statement.where(LibraryItem.metadata_id == metadata_id)
    if content_hash is not None:
        statement = statement.where(LibraryItem.content_hash == content_hash)
    row = (await session.execute(statement.order_by(LibraryItem.pk.asc()).limit(1))).first()
    if row is None:
        return None
    item: LibraryItem = row.LibraryItem
    highlights = await _highlight_counts(session, user_id, [row])
    details = await _details(session, user_id, item) if with_details else None
    return BookView(**_book(row, highlights), isbn=item.isbn, details=details)
