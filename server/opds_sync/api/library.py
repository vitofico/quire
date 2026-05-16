"""`/library/v1/items` router.

Mode-gated: mounts only when `OPDS_SYNC_PROGRESS_ENABLED=true` (see
`main.py`). The migration this endpoint depends on lives on the `progress`
alembic branch (`progress_001_library_items`).

Endpoint shape highlights:
- Identity in body, never the path. Single-item-per-request (a future bulk
  endpoint can ship as `{"items": [...]}` without breaking clients).
- Every state change advances `updated_at = now()` so `GET ?since=` reliably
  delivers tombstones. Idempotent DELETE on an already-deleted row preserves
  both timestamps (no spurious tombstone re-delivery).
- `GET ?since=` returns rows with `updated_at > since`, including tombstones.
- `GET` without `since` returns alive rows only (this is the reconcile-pass
  shape).
- Ordering is `(updated_at ASC, pk ASC)`; the `pk` tiebreaker prevents
  same-timestamp collisions from skipping rows across pages.
- The server captures `server_time = now()` BEFORE the SELECT and additionally
  filters `updated_at <= server_time` so concurrent writes don't leak into
  the current page.

`library_items` is USER-SCOPED — the user_id from Basic auth is in every
filter. The cache-key audit test (which protects the shared cache tables)
does not cover this table.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.api.library_schemas import (
    LibraryItemDeleteBody,
    LibraryItemListResponse,
    LibraryItemPutBody,
    LibraryItemRequest,
    LibraryItemResponse,
)
from opds_sync.core.auth import current_user_id
from opds_sync.db.models import LibraryItem
from opds_sync.db.session import get_session

router = APIRouter(tags=["library"])


def _to_response(row: LibraryItem) -> LibraryItemResponse:
    return LibraryItemResponse(
        metadata_id=row.metadata_id,
        content_hash=row.content_hash,
        title=row.title,
        authors=list(row.authors or []),
        series_name=row.series_name,
        series_index=row.series_index,
        isbn=row.isbn,
        language=row.language,
        subjects=list(row.subjects or []),
        opds_href=row.opds_href,
        created_at=row.created_at,
        updated_at=row.updated_at,
        deleted_at=row.deleted_at,
    )


def _apply_payload(row: LibraryItem, payload: LibraryItemRequest) -> None:
    """Write payload fields onto `row`. Caller is responsible for timestamps."""
    row.metadata_id = payload.metadata_id
    row.title = payload.title
    row.authors = list(payload.authors)
    row.series_name = payload.series_name
    row.series_index = payload.series_index
    row.isbn = payload.isbn
    row.language = payload.language
    row.subjects = list(payload.subjects)
    row.opds_href = payload.opds_href


@router.put("/items", response_model=LibraryItemResponse)
async def put_item(
    body: LibraryItemPutBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> LibraryItemResponse:
    payload = body.item
    now = datetime.now(UTC)

    # Look up by `(user_id, content_hash)` — the hard uniqueness.
    existing = (
        await session.execute(
            select(LibraryItem).where(
                LibraryItem.user_id == user_id,
                LibraryItem.content_hash == payload.content_hash,
            )
        )
    ).scalar_one_or_none()

    # If the client sent a metadata_id, check for a conflict against a
    # different row (rare: client learned a stronger metadata_id for a row
    # keyed under the old content_hash). PR2 identity-aliases fixes this
    # properly; PR1 surfaces it as 409 rather than silently merging.
    if payload.metadata_id is not None:
        conflict = (
            await session.execute(
                select(LibraryItem).where(
                    LibraryItem.user_id == user_id,
                    LibraryItem.metadata_id == payload.metadata_id,
                    LibraryItem.content_hash != payload.content_hash,
                )
            )
        ).scalar_one_or_none()
        if conflict is not None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail={
                    "error": "metadata_id_conflict",
                    "existing_content_hash": conflict.content_hash,
                },
            )

    if existing is None:
        row = LibraryItem(
            user_id=user_id,
            content_hash=payload.content_hash,
            title=payload.title,
            authors=list(payload.authors),
            metadata_id=payload.metadata_id,
            series_name=payload.series_name,
            series_index=payload.series_index,
            isbn=payload.isbn,
            language=payload.language,
            subjects=list(payload.subjects),
            opds_href=payload.opds_href,
            created_at=now,
            updated_at=now,
            deleted_at=None,
        )
        session.add(row)
    else:
        _apply_payload(existing, payload)
        existing.updated_at = now
        existing.deleted_at = None  # reactivate if previously soft-deleted
        row = existing

    await session.commit()
    await session.refresh(row)
    return _to_response(row)


@router.get("/items", response_model=LibraryItemListResponse)
async def list_items(
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
    since: Annotated[str | None, Query()] = None,
    limit: Annotated[int, Query(ge=1, le=1000)] = 200,
    offset: Annotated[int, Query(ge=0)] = 0,
) -> LibraryItemListResponse:
    # Capture server_time BEFORE the SELECT so the page is bounded and a
    # concurrent write to a later timestamp can't leak in.
    server_time = datetime.now(UTC)

    where = [LibraryItem.user_id == user_id, LibraryItem.updated_at <= server_time]
    if since is not None:
        # httpx encodes `+` as space in query strings; the same trick `progress.py`
        # uses normalizes it back.
        since_dt = datetime.fromisoformat(since.replace(" ", "+"))
        where.append(LibraryItem.updated_at > since_dt)
        # With `since`, tombstones (deleted_at IS NOT NULL) are included so
        # clients can mirror them.
    else:
        where.append(LibraryItem.deleted_at.is_(None))

    rows = (
        (
            await session.execute(
                select(LibraryItem)
                .where(and_(*where))
                .order_by(LibraryItem.updated_at.asc(), LibraryItem.pk.asc())
                .limit(limit)
                .offset(offset)
            )
        )
        .scalars()
        .all()
    )

    return LibraryItemListResponse(
        items=[_to_response(r) for r in rows],
        server_time=server_time,
    )


@router.delete("/items", response_model=LibraryItemResponse)
async def delete_item(
    body: LibraryItemDeleteBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> LibraryItemResponse:
    row = (
        await session.execute(
            select(LibraryItem).where(
                LibraryItem.user_id == user_id,
                LibraryItem.content_hash == body.item.content_hash,
            )
        )
    ).scalar_one_or_none()

    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="not_found")

    # Idempotency: DELETE on an already-deleted row is a no-op. Crucially the
    # timestamps are preserved — refreshing `updated_at` here would re-deliver
    # the tombstone on every subsequent `GET ?since=<old_cursor>` call.
    if row.deleted_at is None:
        now = datetime.now(UTC)
        row.deleted_at = now
        row.updated_at = now
        await session.commit()
        await session.refresh(row)

    return _to_response(row)
