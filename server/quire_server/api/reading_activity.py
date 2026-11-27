"""Reading sessions exchanged only by clients the reader has opted in."""

from time import time_ns
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import and_, delete, func, or_, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.api.no_store import NoStoreRoute
from quire_server.core.auth import current_user_id
from quire_server.db.models import ReadingActivity, ReadingActivityReset
from quire_server.db.session import get_session

router = APIRouter(tags=["reading activity"], route_class=NoStoreRoute)
UserId = Annotated[str, Depends(current_user_id)]
Session = Annotated[AsyncSession, Depends(get_session)]
ActivityId = Annotated[str, Field(pattern=r"^[a-zA-Z0-9_-]{1,64}$")]
PAGE = 500


class Activity(BaseModel):
    model_config = ConfigDict(extra="forbid", from_attributes=True)

    id: ActivityId
    book_key: str = Field(min_length=1, max_length=256)
    title: str = Field(max_length=1024)
    started_at: int = Field(ge=0, le=253_402_214_400_000)
    utc_offset_seconds: int = Field(ge=-64_800, le=64_800)
    active_seconds: int = Field(ge=30, le=604_800)
    words: int | None = Field(default=None, ge=0, le=1_000_000_000)


class ActivityState(BaseModel):
    cleared_before: int


class ActivityPage(ActivityState):
    sessions: list[Activity]
    next: str | None


class ActivityUpload(ActivityState):
    sessions: list[Activity] = Field(min_length=1, max_length=100)


async def activity_cutoff(session: AsyncSession, user_id: str) -> int:
    # Serialize this account's reads, writes and reset. A device must see
    # either side of a reset in a page, and stale uploads must not undo it.
    await session.execute(select(func.pg_advisory_xact_lock(func.hashtextextended(user_id, 813))))
    return (
        await session.scalar(
            select(ReadingActivityReset.cleared_before).where(
                ReadingActivityReset.user_id == user_id
            )
        )
        or 0
    )


@router.get("/reading-activity")
async def list_activity(
    user_id: UserId,
    session: Session,
    after: Annotated[ActivityId | None, Query()] = None,
) -> ActivityPage:
    cutoff = await activity_cutoff(session, user_id)
    query = select(ReadingActivity).where(ReadingActivity.user_id == user_id)
    if after is not None:
        query = query.where(ReadingActivity.id > after)
    rows = (await session.scalars(query.order_by(ReadingActivity.id).limit(PAGE + 1))).all()
    return ActivityPage(
        cleared_before=cutoff,
        sessions=[Activity.model_validate(row) for row in rows[:PAGE]],
        next=rows[PAGE - 1].id if len(rows) > PAGE else None,
    )


@router.put("/reading-activity", status_code=204)
async def upload_activity(body: ActivityUpload, user_id: UserId, session: Session) -> Response:
    cutoff = await activity_cutoff(session, user_id)
    if body.cleared_before != cutoff:
        raise HTTPException(409, "reading_activity_reset")
    rows = {}
    for activity in body.sessions:
        if activity.started_at <= cutoff:
            continue
        if activity.id in rows:
            raise HTTPException(422, "duplicate_reading_activity_id")
        rows[activity.id] = {"user_id": user_id, **activity.model_dump()}
    if rows:
        query = insert(ReadingActivity).values(list(rows.values()))
        incoming = query.excluded
        await session.execute(
            query.on_conflict_do_update(
                index_elements=[ReadingActivity.user_id, ReadingActivity.id],
                set_={"active_seconds": incoming.active_seconds, "words": incoming.words},
                where=and_(
                    incoming.started_at == ReadingActivity.started_at,
                    incoming.book_key == ReadingActivity.book_key,
                    or_(
                        incoming.active_seconds > ReadingActivity.active_seconds,
                        and_(
                            incoming.active_seconds == ReadingActivity.active_seconds,
                            func.coalesce(incoming.words, -1)
                            > func.coalesce(ReadingActivity.words, -1),
                        ),
                    ),
                ),
            )
        )
    await session.commit()
    return Response(status_code=204)


@router.delete("/reading-activity")
async def clear_activity(user_id: UserId, session: Session) -> ActivityState:
    previous = await activity_cutoff(session, user_id)
    cutoff = max(previous + 1, time_ns() // 1_000_000)
    await session.execute(delete(ReadingActivity).where(ReadingActivity.user_id == user_id))
    await session.execute(
        insert(ReadingActivityReset)
        .values(user_id=user_id, cleared_before=cutoff)
        .on_conflict_do_update(
            index_elements=[ReadingActivityReset.user_id],
            set_={"cleared_before": cutoff},
        )
    )
    await session.commit()
    return ActivityState(cleared_before=cutoff)
