"""Authenticated read-only access to the caller's active highlights."""

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.api.highlights import HighlightItem
from quire_server.core.agent_highlights import (
    HighlightPage,
    HighlightQuery,
    InvalidHighlightCursor,
    get_highlight,
    list_highlights,
)
from quire_server.core.auth import current_user_id
from quire_server.db.session import get_session

router = APIRouter(prefix="/agent/v1", tags=["agent highlights"])


@router.get("/highlights", response_model=HighlightPage)
async def query_highlights(
    query: Annotated[HighlightQuery, Query()],
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> HighlightPage:
    try:
        return await list_highlights(session, user_id, query)
    except InvalidHighlightCursor:
        raise HTTPException(422, "invalid_cursor") from None
    except Exception:
        # SQL/serialization errors can embed private query or highlight text.
        # Do not send them to the client or an uncaught-exception logger.
        raise HTTPException(503, "highlight_read_failed") from None


@router.get("/highlights/{highlight_id}", response_model=HighlightItem)
async def read_highlight(
    highlight_id: UUID,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> HighlightItem:
    try:
        item = await get_highlight(session, user_id, highlight_id)
    except Exception:
        raise HTTPException(503, "highlight_read_failed") from None
    if item is None:
        raise HTTPException(404, "highlight_not_found")
    return item
