"""/details/v1/* endpoints: book details and designed covers.

Mounted next to ``/ai/v1`` when AI is on and ``QUIRE_SERVER_DETAILS_ENABLED``
is true, with the same auth (``AiPrincipal``), the same opt-in and the same
allowance. The work is in ``core/ai/details.py`` and ``core/covers.py``.
"""

from __future__ import annotations

import asyncio
import time
from collections import OrderedDict, deque
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.api.ai import _orchestrator, _quota_http_exception, _require_opt_in
from quire_server.api.ai_auth import AiPrincipal, get_ai_principal
from quire_server.api.details_schemas import DetailsLookupBody, DetailsResponse
from quire_server.config import get_settings
from quire_server.core.ai import details
from quire_server.core.ai.service import QuotaExceeded
from quire_server.core.covers import cover_png
from quire_server.db.models import BookDetails
from quire_server.db.session import get_session

router = APIRouter(tags=["details"])


class RateLimit:
    """Requests each reader may make per minute, counted over the last 60 s
    in this process. Readers idle for a minute are forgotten once more than
    ``max_readers`` are tracked, so the table stays bounded."""

    def __init__(self, *, max_readers: int = 10_000) -> None:
        self._seen: dict[str, deque[float]] = {}
        self._max_readers = max_readers

    def retry_after(self, subject: str, per_minute: int) -> int | None:
        """None when this request is allowed, else the seconds to wait."""
        now = time.monotonic()
        if subject not in self._seen and len(self._seen) >= self._max_readers:
            self._forget_idle(now)
        seen = self._seen.setdefault(subject, deque())
        while seen and seen[0] <= now - 60:
            seen.popleft()
        if len(seen) >= max(per_minute, 1):
            return max(1, int(seen[0] + 60 - now) + 1)
        seen.append(now)
        return None

    def _forget_idle(self, now: float) -> None:
        for subject in [s for s, seen in self._seen.items() if not seen or seen[-1] <= now - 60]:
            del self._seen[subject]

    def __len__(self) -> int:
        return len(self._seen)


class CoverCache:
    """Drawn covers kept in this process, keyed by ETag, and the gate that
    lets at most ``concurrency`` draw at once so a burst of covers cannot
    take every worker thread."""

    def __init__(self, *, size: int = 64, concurrency: int = 2) -> None:
        self._png: OrderedDict[str, bytes] = OrderedDict()
        self._size = size
        self.gate = asyncio.Semaphore(concurrency)

    def get(self, etag: str) -> bytes | None:
        png = self._png.get(etag)
        if png is not None:
            self._png.move_to_end(etag)
        return png

    def put(self, etag: str, png: bytes) -> None:
        self._png[etag] = png
        self._png.move_to_end(etag)
        while len(self._png) > self._size:
            self._png.popitem(last=False)


def _rate_limited(detail: str, wait: int) -> HTTPException:
    return HTTPException(
        status.HTTP_429_TOO_MANY_REQUESTS, detail=detail, headers={"Retry-After": str(wait)}
    )


async def _run(
    request: Request,
    body: DetailsLookupBody,
    principal: AiPrincipal,
    session: AsyncSession,
    *,
    force: bool,
) -> DetailsResponse:
    # Counted before anything else so a flood reaches neither Open Library
    # nor the model.
    limiter: RateLimit = request.app.state.details_lookup_limit
    wait = limiter.retry_after(principal.subject, get_settings().details_lookup_rate_per_min)
    if wait is not None:
        raise _rate_limited("details_rate_limited", wait)
    orch = await _orchestrator(request, session, principal)
    if orch is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")
    await _require_opt_in(session, principal.subject)
    identity = await details.canonical_identity(session, body.identity, user_id=principal.subject)
    if identity is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="no canonical identity (metadata_id or content_hash) supplied "
            "and no alias hint resolved to one",
        )
    try:
        return await details.lookup(
            orch,
            session,
            body,
            identity=identity,
            user_id=principal.subject,
            tenant_id=principal.tenant_id,
            force=force,
            failures=request.app.state.details_failures,
        )
    except QuotaExceeded as exc:
        raise _quota_http_exception(exc) from exc


@router.post("/lookup", response_model=DetailsResponse)
async def lookup_details(
    request: Request,
    body: DetailsLookupBody,
    principal: Annotated[AiPrincipal, Depends(get_ai_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> DetailsResponse:
    """Fill and tidy a book's details; tidies only when something calls for it."""
    return await _run(request, body, principal, session, force=False)


@router.post("/tidy", response_model=DetailsResponse)
async def tidy_details(
    request: Request,
    body: DetailsLookupBody,
    principal: Annotated[AiPrincipal, Depends(get_ai_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> DetailsResponse:
    """Tidy now, replacing the stored record: the reader asked for it."""
    return await _run(request, body, principal, session, force=True)


@router.get("/covers/{key}")
async def get_cover(
    key: str,
    request: Request,
    principal: Annotated[AiPrincipal, Depends(get_ai_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
    v: str | None = None,
) -> Response:
    """The designed cover of a tidied record, as PNG."""
    await _require_opt_in(session, principal.subject)
    row = await session.get(BookDetails, key) if len(key) == 64 else None
    current = details.cover_etag(row) if row is not None else None
    # An address for a cover since redrawn names a picture that no longer exists.
    if current is None or (v is not None and v != current):
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="cover_not_found")
    etag = f'"{current}"'
    headers = {
        "ETag": etag,
        # The address carries the ETag (`?v=`), so a changed cover has a new one.
        "Cache-Control": "private, max-age=31536000, immutable",
        "X-Quire-Cover": "generated",
    }
    if request.headers.get("If-None-Match") == etag:
        return Response(status_code=status.HTTP_304_NOT_MODIFIED, headers=headers)
    limiter: RateLimit = request.app.state.details_cover_limit
    wait = limiter.retry_after(principal.subject, get_settings().details_cover_rate_per_min)
    if wait is not None:
        raise _rate_limited("cover_rate_limited", wait)
    cache: CoverCache = request.app.state.details_covers
    png = cache.get(current)
    if png is None:
        async with cache.gate:
            png = cache.get(current) or await asyncio.to_thread(
                cover_png,
                row.design,
                row.record.get("title") or "",
                row.record.get("authors") or [],
            )
        cache.put(current, png)
    return Response(content=png, media_type="image/png", headers=headers)
