"""`/library/v1/items` router.

Mode-gated: mounts only when `QUIRE_SERVER_PROGRESS_ENABLED=true` (see
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
from sqlalchemy import and_, case, func, literal_column, select
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.api.library_schemas import (
    LIBRARY_STATS_THEMES_CAVEAT,
    LibraryItemDeleteBody,
    LibraryItemListResponse,
    LibraryItemPutBody,
    LibraryItemRequest,
    LibraryItemResponse,
    LibraryStatsResponse,
    TopAuthor,
    TopTheme,
)
from quire_server.core.auth import current_user_id
from quire_server.db.models import (
    BookInsight,
    BookTheme,
    Document,
    LibraryItem,
    Progress,
)
from quire_server.db.session import get_session

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


# ---------------------------------------------------------------------------
# GET /stats — PR9 library stats v0.
# ---------------------------------------------------------------------------
# User-scoped throughout. The three load-bearing theme-join filters
# (documented in PR3's body and architecture.md):
#
#   1. `book_insights.superseded_at IS NULL` — regenerate is supersede-not-
#      delete; FK CASCADE only fires on actual DELETE. Without this,
#      regenerated insights double-count.
#   2. `book_themes.confidence >= 1.0` — off-vocab passthroughs and the
#      empty-input "other" fallback live at 0.5. Filter excludes them from
#      the controlled-vocab top-N.
#   3. `COUNT(DISTINCT library_items.pk)` per theme — combined with the
#      pick-one CTE below, this prevents a book with multiple cache
#      variants (different tone/language/model_id, all `superseded_at IS
#      NULL`) from contributing to multiple theme keys.
#
# Architect finding (2026-05-17): a naive `JOIN ... ON metadata_id OR
# content_hash` plus `COUNT(DISTINCT li.pk)` can still attribute one book
# to MULTIPLE theme keys when variants emit different theme sets (variant
# A says {mystery}, variant B says {noir, crime}). The DISTINCT-ON CTE
# below picks exactly one insight row per library item before aggregating
# themes; pick order mirrors the orchestrator's lookup hierarchy
# (metadata_id > content_hash; most-recent generated_at as tiebreaker).
@router.get("/stats", response_model=LibraryStatsResponse)
async def get_stats(
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> LibraryStatsResponse:
    # 1. total_books: alive library items for this user.
    total_books = (
        await session.scalar(
            select(func.count())
            .select_from(LibraryItem)
            .where(LibraryItem.user_id == user_id, LibraryItem.deleted_at.is_(None))
        )
    ) or 0

    # 2a. finished_count: library_items JOIN documents JOIN progress, where
    #     finished_at IS NOT NULL. The (user_id, content_hash) join is the
    #     only correct way to bridge — library_items.pk and documents.pk are
    #     independent identifiers.
    finished_count = (
        await session.scalar(
            select(func.count())
            .select_from(LibraryItem)
            .join(
                Document,
                and_(
                    Document.user_id == LibraryItem.user_id,
                    Document.content_hash == LibraryItem.content_hash,
                ),
            )
            .join(Progress, Progress.document_pk == Document.pk)
            .where(
                LibraryItem.user_id == user_id,
                LibraryItem.deleted_at.is_(None),
                Progress.finished_at.is_not(None),
            )
        )
    ) or 0

    # 2b. in_progress_count: started but not finished AND not abandoned.
    #     We do NOT cap at percent < 1: a book at percent=1 with finished_at
    #     IS NULL still counts as in-progress ("not done until the device
    #     says so"). PR-9 (Bundle 4) tightens this to require
    #     `abandoned_at IS NULL` so the three count buckets
    #     (`finished_count`, `in_progress_count`, `abandoned_count`) are
    #     mutually disjoint. User-visible effect: a book marked abandoned
    #     no longer counts as "Reading", matching the release-note framing
    #     "Reading excludes Abandoned".
    in_progress_count = (
        await session.scalar(
            select(func.count())
            .select_from(LibraryItem)
            .join(
                Document,
                and_(
                    Document.user_id == LibraryItem.user_id,
                    Document.content_hash == LibraryItem.content_hash,
                ),
            )
            .join(Progress, Progress.document_pk == Document.pk)
            .where(
                LibraryItem.user_id == user_id,
                LibraryItem.deleted_at.is_(None),
                Progress.finished_at.is_(None),
                Progress.abandoned_at.is_(None),
                Progress.percent > 0,
            )
        )
    ) or 0

    # 2c. abandoned_count: library_items JOIN documents JOIN progress, where
    #     abandoned_at IS NOT NULL. The XOR check constraint (PR-α migration
    #     progress_002_abandoned_at) guarantees finished_at IS NULL for
    #     these rows; we add the explicit predicate as belt-and-suspenders
    #     against future relaxation of the constraint.
    abandoned_count = (
        await session.scalar(
            select(func.count())
            .select_from(LibraryItem)
            .join(
                Document,
                and_(
                    Document.user_id == LibraryItem.user_id,
                    Document.content_hash == LibraryItem.content_hash,
                ),
            )
            .join(Progress, Progress.document_pk == Document.pk)
            .where(
                LibraryItem.user_id == user_id,
                LibraryItem.deleted_at.is_(None),
                Progress.abandoned_at.is_not(None),
                Progress.finished_at.is_(None),
            )
        )
    ) or 0

    # 3. top_authors: unnest the JSONB `authors` array via LATERAL and group.
    #    `jsonb_array_elements_text` returns typed text — no quoting weirdness.
    #    COUNT(DISTINCT LibraryItem.pk) defends against an upstream OPF
    #    parser ever emitting the same author twice in one array (it doesn't
    #    today, but cheaper to defend than to debug later). Secondary alpha
    #    sort is load-bearing for deterministic tiebreaks.
    author_col = (
        func.jsonb_array_elements_text(LibraryItem.authors)
        .table_valued("value")
        .render_derived(name="author")
    )
    author_value = literal_column("author.value")
    author_count = func.count(func.distinct(LibraryItem.pk))
    author_rows = (
        await session.execute(
            select(author_value.label("name"), author_count.label("c"))
            .select_from(LibraryItem)
            .join(author_col, literal_column("true"))
            .where(
                LibraryItem.user_id == user_id,
                LibraryItem.deleted_at.is_(None),
            )
            .group_by(author_value)
            .order_by(author_count.desc(), author_value.asc())
            .limit(5)
        )
    ).all()
    top_authors = [TopAuthor(name=row.name, count=int(row.c)) for row in author_rows]

    # 4. top_themes: pick-one-insight-per-book CTE, then aggregate themes.
    #    See the block comment at the top of this function for the full
    #    rationale.
    pick_priority = case(
        (
            and_(
                BookInsight.metadata_id.is_not(None),
                BookInsight.metadata_id == LibraryItem.metadata_id,
            ),
            0,
        ),
        else_=1,
    )

    picked = (
        select(
            LibraryItem.pk.label("library_item_pk"),
            BookInsight.id.label("book_insight_id"),
        )
        .select_from(LibraryItem)
        .join(
            BookInsight,
            and_(
                BookInsight.superseded_at.is_(None),  # filter 1
                (
                    (
                        BookInsight.metadata_id.is_not(None)
                        & (BookInsight.metadata_id == LibraryItem.metadata_id)
                    )
                    | (BookInsight.content_hash == LibraryItem.content_hash)
                ),
            ),
        )
        .where(
            LibraryItem.user_id == user_id,
            LibraryItem.deleted_at.is_(None),
        )
        .order_by(LibraryItem.pk, pick_priority, BookInsight.generated_at.desc())
        # PostgreSQL DISTINCT ON via SQLAlchemy: keep one row per
        # library_item_pk, picking the lowest priority (metadata match)
        # and most recent generated_at via the trailing ORDER BY.
        .distinct(LibraryItem.pk)
        .subquery("picked_insight")
    )

    theme_count = func.count(func.distinct(picked.c.library_item_pk))
    theme_rows = (
        await session.execute(
            select(BookTheme.theme.label("theme"), theme_count.label("c"))
            .select_from(picked)
            .join(BookTheme, BookTheme.book_insight_id == picked.c.book_insight_id)
            .where(BookTheme.confidence >= 1.0)  # filter 2
            .group_by(BookTheme.theme)
            .order_by(theme_count.desc(), BookTheme.theme.asc())
            .limit(5)
        )
    ).all()
    top_themes = [
        TopTheme(theme=row.theme, count=int(row.c), note="v3+ insights only") for row in theme_rows
    ]

    # NOTE (Lock #12): /library/v1/stats does NOT include a fingerprint. The
    # AI profile envelope owns the input_fingerprint contract; stats uses a
    # lightweight in-memory cache on the client (stale-while-revalidate) and
    # does not participate in profile staleness checks.
    return LibraryStatsResponse(
        total_books=int(total_books),
        finished_count=int(finished_count),
        in_progress_count=int(in_progress_count),
        abandoned_count=int(abandoned_count),
        top_authors=top_authors,
        top_themes=top_themes,
        themes_caveat=LIBRARY_STATS_THEMES_CAVEAT,
    )
