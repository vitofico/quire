"""Private highlight sync with deterministic conflicts and commit-ordered deltas."""

from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.core.auth import current_user_id
from quire_server.core.identity import normalize_metadata_id
from quire_server.db.models import Highlight, HighlightSyncState
from quire_server.db.session import get_session

router = APIRouter(tags=["highlights"])
MAX_INT = 2**63 - 1


class HighlightDocument(BaseModel):
    metadata_id: str | None = None
    content_hash: str = Field(min_length=1, max_length=1024)
    identity_hash_version: int = Field(default=1, ge=1, le=2**31 - 1)

    @field_validator("metadata_id")
    @classmethod
    def normalize_metadata(cls, value: str | None) -> str | None:
        return normalize_metadata_id(value)

    @field_validator("content_hash")
    @classmethod
    def normalize_hash(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("content_hash must not be blank")
        return value


class HighlightItem(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    document: HighlightDocument
    locator: str = Field(min_length=1, max_length=262144)
    text: str = Field(min_length=1, max_length=262144)
    colour: Literal["YELLOW", "GREEN", "PINK"]
    book_title: str = Field(max_length=16384)
    book_author: str | None = Field(default=None, max_length=16384)
    created_at: int = Field(ge=0, le=MAX_INT)
    updated_at: int = Field(ge=0, le=MAX_INT)
    deleted_at: int | None = Field(default=None, ge=0, le=MAX_INT)

    @field_validator("locator", "text")
    @classmethod
    def nonblank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("must not be blank")
        return value  # The locator is opaque: never parse or reserialize it.

    @model_validator(mode="after")
    def timestamps(self):
        if self.created_at > self.updated_at:
            raise ValueError("created_at must be <= updated_at")
        if self.deleted_at is not None and self.deleted_at > self.updated_at:
            raise ValueError("deleted_at must be <= updated_at")
        return self


def conflict_key(item: HighlightItem) -> tuple:
    """Shared with Android; strings compare by unsigned UTF-8 lexical order."""
    return (
        item.updated_at,
        item.deleted_at is not None,
        item.deleted_at or 0,
        item.document.metadata_id or "",
        item.document.content_hash,
        item.document.identity_hash_version,
        item.locator,
        item.text,
        item.colour,
        item.book_title,
        item.book_author is not None,
        item.book_author or "",
        item.created_at,
    )


def wire(row: Highlight) -> HighlightItem:
    return HighlightItem(
        id=row.id,
        document=HighlightDocument(
            metadata_id=row.metadata_id,
            content_hash=row.content_hash,
            identity_hash_version=row.identity_hash_version,
        ),
        **{
            key: getattr(row, key)
            for key in HighlightItem.model_fields
            if key not in {"id", "document"}
        },
    )


class HighlightPushBody(BaseModel):
    items: list[HighlightItem] = Field(max_length=500)


class HighlightPushResult(BaseModel):
    status: Literal["accepted", "stale"]
    item: HighlightItem


class HighlightPushResponse(BaseModel):
    results: list[HighlightPushResult]


class HighlightPullResponse(BaseModel):
    items: list[HighlightItem]
    next_cursor: str
    has_more: bool


async def lock_user(session: AsyncSession, user_id: str) -> None:
    # A sequence alone is unsafe: a transaction can allocate a lower value and
    # commit after the pull has advanced past it. Both writers AND readers use
    # this transaction lock. Allocation, row changes and commit are serialized
    # for each principal across processes, including creation of the first row.
    await session.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))"),
        {"key": "highlights:" + user_id},
    )


@router.post("/highlights", response_model=HighlightPushResponse)
async def push_highlights(
    body: HighlightPushBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> HighlightPushResponse:
    await lock_user(session, user_id)
    state = await session.get(HighlightSyncState, user_id)
    if state is None:
        state = HighlightSyncState(user_id=user_id, revision=0)
        session.add(state)
    results = []
    for item in body.items:
        row = await session.get(Highlight, (user_id, item.id))
        current = wire(row) if row is not None else None
        if current is not None:
            # Identity versions are monotonic metadata, independent of LWW.
            # Normalize BOTH candidates before comparing so equal-clock
            # conflicts converge regardless of which version arrived first.
            version = max(
                current.document.identity_hash_version, item.document.identity_hash_version
            )
            current = current.model_copy(
                update={
                    "document": current.document.model_copy(
                        update={"identity_hash_version": version}
                    )
                }
            )
            item = item.model_copy(
                update={
                    "document": item.document.model_copy(update={"identity_hash_version": version})
                }
            )
        winner = item if current is None or conflict_key(item) > conflict_key(current) else current
        if row is None or winner != wire(row):
            state.revision += 1
            fields = winner.model_dump(exclude={"document"}) | winner.document.model_dump()
            if row is None:
                row = Highlight(user_id=user_id, revision=state.revision, **fields)
                session.add(row)
            else:
                for name, value in fields.items():
                    setattr(row, name, value)
                row.revision = state.revision
            await session.flush()
        status = "accepted" if item == winner else "stale"
        results.append(HighlightPushResult(status=status, item=wire(row)))
    await session.commit()
    return HighlightPushResponse(results=results)


@router.get("/highlights", response_model=HighlightPullResponse)
async def pull_highlights(
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
    since: Annotated[str, Query(pattern=r"^[0-9]{1,19}$")] = "0",
    limit: Annotated[int, Query(ge=1, le=500)] = 500,
) -> HighlightPullResponse:
    cursor = int(since)
    if cursor > MAX_INT:
        raise HTTPException(422, "invalid_cursor")
    await lock_user(session, user_id)
    state = await session.get(HighlightSyncState, user_id)
    if cursor > (state.revision if state else 0):
        # A cursor belongs to one server/account. Fail explicitly after a
        # restore or account switch rather than silently suppressing changes.
        raise HTTPException(409, "highlight_cursor_reset_required")
    rows = (
        await session.scalars(
            select(Highlight)
            .where(Highlight.user_id == user_id, Highlight.revision > cursor)
            .order_by(Highlight.revision)
            .limit(limit + 1)
        )
    ).all()
    page = rows[:limit]
    response = HighlightPullResponse(
        items=[wire(row) for row in page],
        next_cursor=str(page[-1].revision if page else cursor),
        has_more=len(rows) > limit,
    )
    await session.commit()
    return response
