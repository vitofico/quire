"""/ai/v1/* endpoints. Auth = same Basic-auth proxy as /sync/v1."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Annotated
from urllib.parse import urlparse

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.api.ai_schemas import (
    AiStyle,
    BookInsightResponse,
    ConfigResponse,
    InsightGetBody,
    InsightInvalidateBody,
    InsightLookupBody,
    InsightRegenerateBody,
    PreferencesBody,
    PreferencesResponse,
    QuotaResponse,
)
from opds_sync.config import get_settings
from opds_sync.core.ai.service import InsightOrchestrator, QuotaExceeded
from opds_sync.core.auth import current_user_id
from opds_sync.db.models import UserAIPreference
from opds_sync.db.session import get_session

router = APIRouter(tags=["ai"])


def _orchestrator(request: Request) -> InsightOrchestrator | None:
    return getattr(request.app.state, "ai_orchestrator", None)


def _enabled_sources() -> list[str]:
    raw = (get_settings().ai_sources or "").strip()
    if not raw:
        return []
    return [s.strip() for s in raw.split(",") if s.strip()]


def _base_url_host() -> str | None:
    base = get_settings().ai_base_url
    if not base:
        return None
    return urlparse(base).hostname


async def _require_opt_in(session: AsyncSession, user_id: str) -> UserAIPreference:
    pref = (
        await session.execute(
            select(UserAIPreference).where(UserAIPreference.user_id == user_id)
        )
    ).scalar_one_or_none()
    if pref is None or not pref.ai_enabled:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="not_opted_in")
    return pref


def _style_from_pref(pref: UserAIPreference) -> AiStyle:
    """Build a validated AiStyle from the user's stored prefs, falling back to defaults."""
    if not pref.style:
        return AiStyle()
    try:
        return AiStyle.model_validate(pref.style)
    except Exception:
        # Old / malformed prefs row → use defaults rather than 500-ing the read.
        return AiStyle()


def _quota_http_exception(exc: QuotaExceeded) -> HTTPException:
    body = QuotaResponse(
        used=exc.used, limit=exc.limit, resets_at=exc.resets_at.isoformat()
    )
    return HTTPException(
        status_code=status.HTTP_429_TOO_MANY_REQUESTS,
        detail=body.model_dump(),
        headers={"Retry-After": str(max(int((exc.resets_at - datetime.now(UTC)).total_seconds()), 60))},
    )


@router.get("/config", response_model=ConfigResponse)
async def get_config(
    user_id: Annotated[str, Depends(current_user_id)],
) -> ConfigResponse:
    """Public to authed users; the app needs this to render the AI toggle."""
    settings = get_settings()
    return ConfigResponse(
        configured=bool(
            settings.ai_enabled and settings.ai_base_url and settings.ai_model
        ),
        base_url_host=_base_url_host() if settings.ai_enabled else None,
        model_id=settings.ai_model if settings.ai_enabled else None,
        sources_enabled=_enabled_sources() if settings.ai_enabled else [],
        daily_budget=settings.ai_daily_budget,
        regen_daily_limit=settings.ai_regen_daily_limit,
    )


@router.get("/preferences", response_model=PreferencesResponse)
async def get_preferences(
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> PreferencesResponse:
    pref = (
        await session.execute(
            select(UserAIPreference).where(UserAIPreference.user_id == user_id)
        )
    ).scalar_one_or_none()
    if pref is None:
        return PreferencesResponse(ai_enabled=False, style=AiStyle())
    return PreferencesResponse(
        ai_enabled=pref.ai_enabled,
        style=_style_from_pref(pref),
    )


@router.put("/preferences", response_model=PreferencesResponse)
async def put_preferences(
    body: PreferencesBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> PreferencesResponse:
    pref = (
        await session.execute(
            select(UserAIPreference).where(UserAIPreference.user_id == user_id)
        )
    ).scalar_one_or_none()
    if pref is None:
        pref = UserAIPreference(
            user_id=user_id,
            ai_enabled=body.ai_enabled if body.ai_enabled is not None else False,
            style=body.style.model_dump() if body.style else None,
        )
        session.add(pref)
    else:
        if body.ai_enabled is not None:
            pref.ai_enabled = body.ai_enabled
        if body.style is not None:
            pref.style = body.style.model_dump()
    await session.commit()
    await session.refresh(pref)
    return PreferencesResponse(
        ai_enabled=pref.ai_enabled,
        style=_style_from_pref(pref),
    )


@router.post("/insights/lookup", response_model=BookInsightResponse)
async def lookup_insight(
    request: Request,
    body: InsightLookupBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> BookInsightResponse:
    orch = _orchestrator(request)
    if orch is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")
    pref = await _require_opt_in(session, user_id)
    try:
        return await orch.generate(
            session,
            body.identity,
            body.bundle,
            user_id=user_id,
            style=_style_from_pref(pref),
        )
    except QuotaExceeded as exc:
        raise _quota_http_exception(exc) from exc


@router.post("/insights/regenerate", response_model=BookInsightResponse)
async def regenerate_insight(
    request: Request,
    body: InsightRegenerateBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> BookInsightResponse:
    """Mark the existing live row as superseded and generate a fresh one
    incorporating the user's `reason`. Counts against regen budget."""
    orch = _orchestrator(request)
    if orch is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")
    pref = await _require_opt_in(session, user_id)
    try:
        return await orch.regenerate(
            session,
            body.identity,
            body.bundle,
            user_id=user_id,
            reason=body.reason,
            style=_style_from_pref(pref),
        )
    except QuotaExceeded as exc:
        raise _quota_http_exception(exc) from exc


@router.post("/insights/get", response_model=BookInsightResponse)
async def get_insight(
    request: Request,
    body: InsightGetBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> BookInsightResponse:
    orch = _orchestrator(request)
    if orch is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")
    out = await orch.get(session, body.identity)
    if out is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="not_cached")
    return out


@router.post("/insights/invalidate")
async def invalidate_insight(
    request: Request,
    body: InsightInvalidateBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> dict:
    orch = _orchestrator(request)
    if orch is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")
    await _require_opt_in(session, user_id)
    n = await orch.invalidate(session, body.identity)
    return {"deleted": n}
