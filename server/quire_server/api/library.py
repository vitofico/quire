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

import logging
import time
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
    LibrarySyncEntry,
    LibrarySyncRequest,
    LibrarySyncStatus,
    LibrarySyncSummary,
    TopAuthor,
    TopTheme,
)
from quire_server.config import Settings, get_settings
from quire_server.core.auth import current_user_id
from quire_server.db.models import (
    BookInsight,
    BookTheme,
    Document,
    LibraryItem,
    Progress,
)
from quire_server.db.session import get_session

logger = logging.getLogger(__name__)

router = APIRouter(tags=["library"])


def _to_response(row: LibraryItem) -> LibraryItemResponse:
    return LibraryItemResponse(
        metadata_id=row.metadata_id,
        content_hash=row.content_hash,
        identity_hash_version=row.identity_hash_version,
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
    # Phase 0, task F-1: downgrade protection. An old client (sending the
    # default `1`) MUST NOT overwrite a row whose hash version was advanced
    # by a newer client. `max(existing, incoming)` is the load-bearing rule.
    if payload.identity_hash_version > row.identity_hash_version:
        row.identity_hash_version = payload.identity_hash_version
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
            identity_hash_version=payload.identity_hash_version,
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
# POST /sync — Phase 0, task S-2: push-model bulk library mirror.
# ---------------------------------------------------------------------------
# The client is authoritative about its library and pushes a bag of
# `{present, deleted}` entries here; the server upserts and never reaches
# outward. Per spec section "Architectural changes → Push-model API" in
# `docs/superpowers/specs/2026-05-22-quire-monetization-design.md`, the
# existing single-item PUT/GET/DELETE endpoints stay reachable unchanged
# (deprecation window comes in a sibling task S-4, not here).
#
# Design contract:
# - All-or-nothing per request. Validation conflicts (`422`) and existing-row
#   metadata_id conflicts (`409`) abort the whole batch with no partial
#   commit. Per-entry partial success would require savepoints around every
#   row and a far more complex client contract; v1 keeps it simple.
# - Idempotent on replay. A `present` entry whose persisted row already
#   matches every field is skipped, NOT rewritten — bumping `updated_at`
#   on every replay would flood `GET /library/v1/items?since=` with the
#   entire library on every sync cycle.
# - `last_seen_at` is wire-only in v1: validated tz-aware, never stored
#   (no column exists). The architect-flagged footgun was using a client
#   clock as a server cursor; the server's own `updated_at` is the only
#   trustworthy delta source.
# - Missing entries are NEVER auto-deleted. Tombstones travel via an
#   explicit `status=deleted` command. A tombstone command for an unknown
#   `identity_hash` is counted as `missing_deleted` and otherwise ignored.
#
# Wire naming (Phase 0, task X-1): the JSON wire field for book identity
# on this endpoint is `identity_hash`. The DB column is still legacy-
# named `content_hash` and is never renamed. The Pydantic schema exposes
# `identity_hash` on the Python side; this handler bridges by assigning
# `LibraryItem(content_hash=entry.identity_hash, ...)` at the ORM
# boundary.
# ---------------------------------------------------------------------------


# Fields whose values participate in the "row already matches" diff for a
# `present` entry. Order doesn't matter for set membership but does matter
# for list-valued comparisons (authors order is meaningful — `[Smith, Doe]`
# is a different shelf face than `[Doe, Smith]`).
_DIFF_FIELDS: tuple[str, ...] = (
    "metadata_id",
    "title",
    "authors",
    "series_name",
    "series_index",
    "isbn",
    "language",
    "subjects",
    "opds_href",
)


def _entry_to_payload(entry: LibrarySyncEntry) -> dict[str, object]:
    """Materialize a `present` sync entry into the field dict used for diff/apply.

    Centralises the "missing optional field → empty default" rule. Sync is
    full-replace semantics: an entry that omits `authors` is asserting "no
    authors", same as the existing PUT endpoint. Bulk sync MUST NOT
    diverge from per-item PUT on this rule.
    """
    return {
        "metadata_id": entry.metadata_id,
        "title": entry.title or "",
        "authors": list(entry.authors),
        "series_name": entry.series_name,
        "series_index": entry.series_index,
        "isbn": entry.isbn,
        "language": entry.language,
        "subjects": list(entry.subjects),
        "opds_href": entry.opds_href,
    }


def _row_matches_payload(row: LibraryItem, payload: dict[str, object]) -> bool:
    """True if every diff field on `row` already equals `payload`.

    The `identity_hash_version` is deliberately NOT compared here — it has
    its own `max(existing, incoming)` rule applied separately, and a
    client sending the same value as the existing row is the common case
    we want to skip.
    """
    for f in _DIFF_FIELDS:
        existing = getattr(row, f)
        incoming = payload[f]
        # JSONB fields (authors/subjects) come back from SQLAlchemy as
        # plain Python lists; the in-memory comparison is reliable.
        # Numeric (series_index) is tricky: a Decimal('1.0') and
        # Decimal('1.00') are equal under `==` (Decimal compares by
        # value, not representation), but Decimal('1') != int(1) under
        # `is`; the broad `!=` here covers both with Decimal semantics.
        if existing != incoming:
            return False
    return True


def _apply_sync_payload(
    row: LibraryItem, entry: LibrarySyncEntry, payload: dict[str, object]
) -> None:
    """Write a `present` sync entry's fields onto an existing row.

    Mirrors `_apply_payload` (the PUT path) but takes the pre-built
    payload dict to avoid re-materializing. Downgrade-protection on
    `identity_hash_version` is identical to the PUT path — the load-
    bearing `max(existing, incoming)` rule from F-1 stays intact.
    """
    if entry.identity_hash_version > row.identity_hash_version:
        row.identity_hash_version = entry.identity_hash_version
    row.metadata_id = payload["metadata_id"]  # type: ignore[assignment]
    row.title = payload["title"]  # type: ignore[assignment]
    row.authors = payload["authors"]  # type: ignore[assignment]
    row.series_name = payload["series_name"]  # type: ignore[assignment]
    row.series_index = payload["series_index"]  # type: ignore[assignment]
    row.isbn = payload["isbn"]  # type: ignore[assignment]
    row.language = payload["language"]  # type: ignore[assignment]
    row.subjects = payload["subjects"]  # type: ignore[assignment]
    row.opds_href = payload["opds_href"]  # type: ignore[assignment]


def _dedupe_entries(entries: list[LibrarySyncEntry]) -> list[LibrarySyncEntry]:
    """Collapse intra-payload duplicates by `identity_hash`.

    Architect-flagged footgun: Postgres `ON CONFLICT DO UPDATE` blows up
    when one INSERT batch touches the same key twice. We don't use raw
    UPSERT — we do select+merge in the ORM — but the same logical
    problem applies: a row updated twice in one transaction gets the
    last writer's payload non-deterministically.

    Rules:
    - If all fields match exactly (post `identity_hash_version` maxing),
      collapse silently. This is the "the client emitted the same entry
      twice in one sync" benign case.
    - Otherwise, raise — duplicate `identity_hash` with conflicting
      `status` or different metadata is a client bug and the safest
      response is rejection rather than picking a winner.
    """
    by_hash: dict[str, LibrarySyncEntry] = {}
    for e in entries:
        prev = by_hash.get(e.identity_hash)
        if prev is None:
            by_hash[e.identity_hash] = e
            continue
        if prev.status != e.status:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail={
                    "error": "duplicate_identity_hash_conflict",
                    "identity_hash": e.identity_hash,
                    "reason": "conflicting status values",
                },
            )
        # Compare all wire fields except `identity_hash_version` (max-merge)
        # and `last_seen_at` (wire-only, never durable). Anything else
        # diverging means the client emitted two conflicting truths.
        prev_payload = _entry_to_payload(prev) if prev.status is LibrarySyncStatus.PRESENT else {}
        curr_payload = _entry_to_payload(e) if e.status is LibrarySyncStatus.PRESENT else {}
        if prev_payload != curr_payload:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail={
                    "error": "duplicate_identity_hash_conflict",
                    "identity_hash": e.identity_hash,
                    "reason": "conflicting metadata across duplicate entries",
                },
            )
        # Identical entries (modulo hash version): keep the max version.
        if e.identity_hash_version > prev.identity_hash_version:
            by_hash[e.identity_hash] = e
    return list(by_hash.values())


def _check_intra_payload_metadata_collisions(entries: list[LibrarySyncEntry]) -> None:
    """Reject payloads that internally claim one `metadata_id` for two books.

    Maps `metadata_id -> first identity_hash that claimed it`. A second
    entry with the same `metadata_id` and a DIFFERENT `identity_hash`
    means the client is internally inconsistent: per the existing PUT
    semantics (and the partial unique index
    `uq_library_items_user_metadata`), one `metadata_id` belongs to one
    book per user. PR2 will introduce identity-aliases to reconcile
    these; until then, surface as `422` rather than letting one of the
    INSERTs blow up with a Postgres unique-violation midway through the
    batch.
    """
    claimants: dict[str, str] = {}
    for e in entries:
        if e.status is LibrarySyncStatus.DELETED:
            continue  # tombstones don't carry a binding metadata_id
        if e.metadata_id is None:
            continue
        prior = claimants.get(e.metadata_id)
        if prior is not None and prior != e.identity_hash:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail={
                    "error": "metadata_id_collision_in_payload",
                    "metadata_id": e.metadata_id,
                    "identity_hashes": sorted({prior, e.identity_hash}),
                },
            )
        claimants[e.metadata_id] = e.identity_hash


@router.post("/sync", response_model=LibrarySyncSummary)
async def sync_library(
    body: LibrarySyncRequest,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> LibrarySyncSummary:
    received = len(body.items)
    t0 = time.monotonic()
    now = datetime.now(UTC)

    # Bounded payload. The RequestSizeMiddleware already enforces a byte
    # ceiling (`max_request_bytes`); this guards the parsed-entry count
    # so a tightly-packed but valid body can't blow past Postgres bind-
    # parameter ceilings or balloon one transaction.
    if received > settings.library_sync_max_items:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={
                "error": "too_many_items",
                "limit": settings.library_sync_max_items,
                "received": received,
            },
        )

    # ---- Preflight (all checks abort the batch before any DB writes) ----
    entries = _dedupe_entries(list(body.items))
    _check_intra_payload_metadata_collisions(entries)

    # Bulk-fetch all rows this batch touches in a single SELECT.
    # Wire field `identity_hash` maps to the legacy DB column `content_hash`
    # (no DB rename — see module docstring for the X-1 bridge rule).
    hashes = [e.identity_hash for e in entries]
    existing_by_hash: dict[str, LibraryItem] = {}
    if hashes:
        existing_rows = (
            (
                await session.execute(
                    select(LibraryItem).where(
                        LibraryItem.user_id == user_id,
                        LibraryItem.content_hash.in_(hashes),
                    )
                )
            )
            .scalars()
            .all()
        )
        existing_by_hash = {r.content_hash: r for r in existing_rows}

    # Cross-row `metadata_id` conflict against an existing row that has a
    # DIFFERENT `identity_hash`. Mirror the per-item PUT's 409 contract.
    incoming_metadata_ids = {
        e.metadata_id: e.identity_hash
        for e in entries
        if e.status is LibrarySyncStatus.PRESENT and e.metadata_id is not None
    }
    if incoming_metadata_ids:
        conflict_rows = (
            (
                await session.execute(
                    select(LibraryItem).where(
                        LibraryItem.user_id == user_id,
                        LibraryItem.metadata_id.in_(incoming_metadata_ids.keys()),
                    )
                )
            )
            .scalars()
            .all()
        )
        for conflict in conflict_rows:
            expected_hash = incoming_metadata_ids.get(conflict.metadata_id or "")
            if expected_hash is not None and conflict.content_hash != expected_hash:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail={
                        "error": "metadata_id_conflict",
                        "metadata_id": conflict.metadata_id,
                        "existing_identity_hash": conflict.content_hash,
                        "incoming_identity_hash": expected_hash,
                    },
                )

    # ---- Apply ----
    created = updated = reactivated = deleted = skipped = missing_deleted = 0

    for entry in entries:
        existing = existing_by_hash.get(entry.identity_hash)

        if entry.status is LibrarySyncStatus.DELETED:
            if existing is None:
                # Architect rule: don't materialize tombstones for rows the
                # server has never seen. Count and move on.
                missing_deleted += 1
                continue
            if existing.deleted_at is not None:
                # Already tombstoned. Preserve timestamps to avoid the
                # `?since=` re-delivery bug documented on `delete_item`.
                skipped += 1
                continue
            existing.deleted_at = now
            existing.updated_at = now
            # Downgrade-protected version bump still applies on delete:
            # a newer client noticing a deletion shouldn't downgrade the
            # row's version metadata.
            if entry.identity_hash_version > existing.identity_hash_version:
                existing.identity_hash_version = entry.identity_hash_version
            deleted += 1
            continue

        # status == PRESENT
        payload = _entry_to_payload(entry)
        if existing is None:
            # DB bridge: wire `identity_hash` -> legacy column `content_hash`.
            row = LibraryItem(
                user_id=user_id,
                content_hash=entry.identity_hash,
                identity_hash_version=entry.identity_hash_version,
                metadata_id=payload["metadata_id"],
                title=payload["title"],
                authors=payload["authors"],
                series_name=payload["series_name"],
                series_index=payload["series_index"],
                isbn=payload["isbn"],
                language=payload["language"],
                subjects=payload["subjects"],
                opds_href=payload["opds_href"],
                created_at=now,
                updated_at=now,
                deleted_at=None,
            )
            session.add(row)
            created += 1
            continue

        if existing.deleted_at is not None:
            # Tombstoned → reactivate. Always counts as a write (timestamps
            # change); diff-skip doesn't apply because the deleted_at
            # transition is itself state-changing.
            _apply_sync_payload(existing, entry, payload)
            existing.deleted_at = None
            existing.updated_at = now
            reactivated += 1
            continue

        # Existing alive row. Diff-skip if and only if nothing meaningful
        # changes — including the hash version (clients sending the same
        # value as persisted shouldn't move `updated_at`).
        version_would_change = entry.identity_hash_version > existing.identity_hash_version
        if not version_would_change and _row_matches_payload(existing, payload):
            skipped += 1
            continue

        _apply_sync_payload(existing, entry, payload)
        existing.updated_at = now
        updated += 1

    await session.commit()

    duration_ms = int((time.monotonic() - t0) * 1000)
    # One aggregate log line per request — per-entry logging at this scale
    # is noise. `extra` fields land in structured-log adapters when
    # configured; the f-string keeps human-readable plain-text logs sane.
    logger.info(
        "library_sync user_id=%s received=%d processed=%d created=%d updated=%d "
        "reactivated=%d deleted=%d skipped=%d missing_deleted=%d duration_ms=%d",
        user_id,
        received,
        len(entries),
        created,
        updated,
        reactivated,
        deleted,
        skipped,
        missing_deleted,
        duration_ms,
    )

    return LibrarySyncSummary(
        received=received,
        processed=len(entries),
        created=created,
        updated=updated,
        reactivated=reactivated,
        deleted=deleted,
        skipped=skipped,
        missing_deleted=missing_deleted,
        server_time=now,
    )


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
