"""/ai/v1/* endpoints.

Auth flows through `AiPrincipal` (PR-B). Default deploy: basic-auth wrapper
around the calibre-web verifier; `principal.tenant_id == "local"`. Hosted
deploys: HMAC-token verifier; `principal.tenant_id` carries the tenant claim.

Sync routes (`/sync/v1/*`) keep depending on `current_user_id` directly —
this seam only swings on `/ai/v1/*`.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Annotated
from urllib.parse import urlparse

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.api.ai_auth import AiPrincipal, get_ai_principal
from quire_server.api.ai_schemas import (
    AiHealthResponse,
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
    RetrievalSourceHealth,
)
from quire_server.config import get_settings
from quire_server.core.ai.health_state import AiHealthState
from quire_server.core.ai.service import IdentityUnresolvable, InsightOrchestrator, QuotaExceeded
from quire_server.db.models import UserAIPreference
from quire_server.db.session import get_session

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
        await session.execute(select(UserAIPreference).where(UserAIPreference.user_id == user_id))
    ).scalar_one_or_none()
    if pref is None or not pref.ai_enabled:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="not_opted_in")
    return pref


def _style_from_pref(pref: UserAIPreference) -> AiStyle:
    """Build a validated AiStyle from the user's stored prefs, falling back to defaults.

    Tolerates prefs rows from older schema versions (which carried extra fields
    like `length` / `author_focus`) by extracting only the keys AiStyle knows.
    """
    if not pref.style:
        return AiStyle()
    raw = pref.style if isinstance(pref.style, dict) else {}
    filtered = {k: v for k, v in raw.items() if k in AiStyle.model_fields}
    try:
        return AiStyle.model_validate(filtered)
    except Exception:
        return AiStyle()


def _quota_http_exception(exc: QuotaExceeded) -> HTTPException:
    body = QuotaResponse(used=exc.used, limit=exc.limit, resets_at=exc.resets_at.isoformat())
    return HTTPException(
        status_code=status.HTTP_429_TOO_MANY_REQUESTS,
        detail=body.model_dump(),
        headers={
            "Retry-After": str(max(int((exc.resets_at - datetime.now(UTC)).total_seconds()), 60))
        },
    )


@router.get("/config", response_model=ConfigResponse)
async def get_config(
    principal: Annotated[AiPrincipal, Depends(get_ai_principal)],
) -> ConfigResponse:
    """Public to authed users; the app needs this to render the AI toggle."""
    settings = get_settings()
    _ = principal  # auth gate only; config is non-personalized.
    return ConfigResponse(
        configured=bool(settings.ai_enabled and settings.ai_base_url and settings.ai_model),
        base_url_host=_base_url_host() if settings.ai_enabled else None,
        model_id=settings.ai_model if settings.ai_enabled else None,
        sources_enabled=_enabled_sources() if settings.ai_enabled else [],
        daily_budget=settings.ai_daily_budget,
        regen_daily_limit=settings.ai_regen_daily_limit,
    )


@router.get("/preferences", response_model=PreferencesResponse)
async def get_preferences(
    principal: Annotated[AiPrincipal, Depends(get_ai_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> PreferencesResponse:
    pref = (
        await session.execute(
            select(UserAIPreference).where(UserAIPreference.user_id == principal.subject)
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
    principal: Annotated[AiPrincipal, Depends(get_ai_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> PreferencesResponse:
    pref = (
        await session.execute(
            select(UserAIPreference).where(UserAIPreference.user_id == principal.subject)
        )
    ).scalar_one_or_none()
    if pref is None:
        pref = UserAIPreference(
            user_id=principal.subject,
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
    principal: Annotated[AiPrincipal, Depends(get_ai_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> BookInsightResponse:
    orch = _orchestrator(request)
    if orch is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")
    pref = await _require_opt_in(session, principal.subject)
    try:
        return await orch.generate(
            session,
            body.identity,
            body.bundle,
            user_id=principal.subject,
            style=_style_from_pref(pref),
            tenant_id=principal.tenant_id,
        )
    except QuotaExceeded as exc:
        raise _quota_http_exception(exc) from exc
    except IdentityUnresolvable as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="no canonical identity (metadata_id or content_hash) supplied "
            "and no alias hint resolved to one",
        ) from exc


@router.post("/insights/regenerate", response_model=BookInsightResponse)
async def regenerate_insight(
    request: Request,
    body: InsightRegenerateBody,
    principal: Annotated[AiPrincipal, Depends(get_ai_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> BookInsightResponse:
    """Mark the existing live row as superseded and generate a fresh one
    incorporating the user's `reason`. Counts against regen budget."""
    orch = _orchestrator(request)
    if orch is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")
    pref = await _require_opt_in(session, principal.subject)
    try:
        return await orch.regenerate(
            session,
            body.identity,
            body.bundle,
            user_id=principal.subject,
            reason=body.reason,
            style=_style_from_pref(pref),
            tenant_id=principal.tenant_id,
        )
    except QuotaExceeded as exc:
        raise _quota_http_exception(exc) from exc
    except IdentityUnresolvable as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="no canonical identity (metadata_id or content_hash) supplied "
            "and no alias hint resolved to one",
        ) from exc


@router.post("/insights/get", response_model=BookInsightResponse)
async def get_insight(
    request: Request,
    body: InsightGetBody,
    principal: Annotated[AiPrincipal, Depends(get_ai_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> BookInsightResponse:
    orch = _orchestrator(request)
    if orch is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")
    pref = (
        await session.execute(
            select(UserAIPreference).where(UserAIPreference.user_id == principal.subject)
        )
    ).scalar_one_or_none()
    style = _style_from_pref(pref) if pref is not None else AiStyle()
    out = await orch.get(
        session,
        body.identity,
        user_id=principal.subject,
        style=style,
        tenant_id=principal.tenant_id,
    )
    if out is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="not_cached")
    return out


@router.post("/insights/invalidate")
async def invalidate_insight(
    request: Request,
    body: InsightInvalidateBody,
    principal: Annotated[AiPrincipal, Depends(get_ai_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> dict:
    orch = _orchestrator(request)
    if orch is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")
    await _require_opt_in(session, principal.subject)
    n = await orch.invalidate(session, body.identity, user_id=principal.subject)
    return {"deleted": n}


@router.get("/health", response_model=AiHealthResponse)
async def get_ai_health(request: Request) -> AiHealthResponse:
    """Operational visibility for AI provider + retrieval reachability.

    Unauthenticated by design — operators and the Android Settings screen
    poll this without going through Basic auth (consistent with the
    always-on root ``/health`` and ``/readyz`` probes; nothing in the body
    is more sensitive than ``/ai/v1/config`` already exposes).

    Snapshot semantics:
      * Process-local: each replica reports its own state. Reset to all-null
        on restart.
      * Passive: state updates only as a side effect of real user-driven
        chat_structured + retrieval calls. We never actively ping providers.
      * Tri-state ``reachable``: see ``AiHealthResponse`` and
        ``RetrievalSourceHealth`` for the contract.
    """
    state: AiHealthState | None = getattr(request.app.state, "ai_health", None)
    sources_seed = _enabled_sources()
    if state is None:
        # AI router mounted but no health holder was wired (the
        # "enabled-but-unconfigured" branch of main.py before this PR ran;
        # defensive in case any future wiring forgets to attach the holder).
        return AiHealthResponse(
            retrieval_sources=[RetrievalSourceHealth(name=n) for n in sources_seed],
        )
    snap = await state.snapshot()
    # Seed configured sources so the UI always sees a row per source, even
    # before the first call. Observed sources override seeded null entries.
    sources: dict[str, RetrievalSourceHealth] = {
        n: RetrievalSourceHealth(name=n) for n in sources_seed
    }
    for name, s in snap.retrieval_sources.items():
        sources[name] = RetrievalSourceHealth(
            name=name,
            reachable=s.reachable,
            last_checked_at=s.last_checked_at.isoformat() if s.last_checked_at else None,
        )
    return AiHealthResponse(
        provider_reachable=snap.provider_reachable,
        provider_last_checked_at=(
            snap.provider_last_checked_at.isoformat() if snap.provider_last_checked_at else None
        ),
        model_id=snap.model_id,
        last_failure_at=snap.last_failure_at.isoformat() if snap.last_failure_at else None,
        last_failure_class=snap.last_failure_class,
        retrieval_sources=list(sources.values()),
    )
