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

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status
from sqlalchemy import and_, case, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.api.ai_auth import AiPrincipal, get_ai_principal
from quire_server.api.ai_schemas import (
    AiHealthResponse,
    AiStyle,
    BookInsightPayload,
    BookInsightResponse,
    Citation,
    ConfigResponse,
    DocumentIdentity,
    InsightGetBody,
    InsightInvalidateBody,
    InsightLookupBody,
    InsightPromoteBody,
    InsightPromoteResponse,
    InsightRegenerateBody,
    InsightSyncCursor,
    InsightSyncItem,
    InsightSyncResponse,
    PreferencesBody,
    PreferencesResponse,
    QuotaResponse,
    ReaderProfilePayload,
    ReaderProfileResponse,
    RetrievalSourceHealth,
)
from quire_server.config import get_settings
from quire_server.core.ai.health_state import AiHealthState
from quire_server.core.ai.service import (
    IdentityUnresolvable,
    InsightOrchestrator,
    PromoteOwnershipError,
    QuotaExceeded,
)
from quire_server.db.models import BookInsight, LibraryItem, ReaderProfile, UserAIPreference
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
        # PR-ζ / Lock #10 / CC-1: body normalized to `ai_not_opted_in` so the
        # client can pin the literal across the whole /ai/v1/insights/* surface.
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="ai_not_opted_in")
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
    # PR-η / Lock #24: expose the runtime-resolved PROMPT_VERSION so the
    # Android client can key its local-cache PK on the same value the
    # server uses. The helper applies Lock #19 sentinel semantics (legacy
    # "1" → in-code constant). Lazy-import inside the route so sync-only
    # deploys never load `core.ai._compat` (coordinator §3.18).
    from quire_server.core.ai._compat import _resolve_prompt_version

    return ConfigResponse(
        configured=bool(settings.ai_enabled and settings.ai_base_url and settings.ai_model),
        base_url_host=_base_url_host() if settings.ai_enabled else None,
        model_id=settings.ai_model if settings.ai_enabled else None,
        sources_enabled=_enabled_sources() if settings.ai_enabled else [],
        daily_budget=settings.ai_daily_budget,
        regen_daily_limit=settings.ai_regen_daily_limit,
        prompt_version=_resolve_prompt_version(settings.ai_prompt_version),
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


@router.post("/insights/promote", response_model=InsightPromoteResponse)
async def promote_insight(
    request: Request,
    body: InsightPromoteBody,
    principal: Annotated[AiPrincipal, Depends(get_ai_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> InsightPromoteResponse | Response:
    """Promote a cached catalog insight onto the post-download identity.

    PR-ζ / Lock #1: row-copy + alias-link. Alias is the idempotency anchor;
    different ``(tone, language)`` re-copies under an existing alias. No LLM
    call. Returns ``204`` when there's nothing to promote (no source row at
    ``from`` for the requested variant).
    """
    orch = _orchestrator(request)
    if orch is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")
    pref = await _require_opt_in(session, principal.subject)
    settings = get_settings()
    try:
        await orch.reserve_promote_budget(
            user_id=principal.subject,
            limit=settings.ai_promote_daily_limit,
        )
    except QuotaExceeded as exc:
        raise _quota_http_exception(exc) from exc
    style = _style_from_pref(pref)
    tone = body.tone or style.tone
    language = body.language or style.language
    try:
        result = await orch.promote_insight(
            session,
            from_identity=body.from_,
            to_identity=body.to,
            user_id=principal.subject,
            tenant_id=principal.tenant_id,
            tone=tone,
            language=language,
        )
    except PromoteOwnershipError as exc:
        raise HTTPException(status.HTTP_403_FORBIDDEN, detail="not_owned") from exc
    except QuotaExceeded as exc:
        raise _quota_http_exception(exc) from exc
    if result is None:
        return Response(status_code=status.HTTP_204_NO_CONTENT)
    return InsightPromoteResponse(
        promoted=True,
        insight_id=result.insight_id,
        already_promoted=result.already_promoted,
    )


@router.get("/insights/sync", response_model=InsightSyncResponse)
async def sync_insights(
    request: Request,
    principal: Annotated[AiPrincipal, Depends(get_ai_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
    since_ts: str | None = Query(None),
    since_id: int | None = Query(None),
    limit: int = Query(50, ge=1, le=200),
) -> InsightSyncResponse:
    """PR-η: paginated, read-only bulk export of the caller's owned insights.

    Filters: caller's ``library_items`` (alive, non-deleted) JOIN ed against
    ``book_insights`` at the caller's current ``(model_id, prompt_version,
    tone, language)``. Cursor is the ``(generated_at, id)`` tuple of the
    last item returned (Lock #23). Weight=0: never touches the daily budget,
    never acquires a generation lock, never calls the model.
    """
    orch = _orchestrator(request)
    if orch is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")
    pref = await _require_opt_in(session, principal.subject)
    style = _style_from_pref(pref)

    if (since_ts is None) != (since_id is None):
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST,
            detail="since_ts and since_id must be provided together",
        )

    settings = get_settings()
    # PR-η imports the same helper PR-ε introduced so the cache-key prompt
    # version matches the one the model is being driven at — even if the
    # orchestrator was constructed under a different value (it isn't, but
    # the contract is single-sourced via the helper).
    from quire_server.core.ai._compat import _resolve_prompt_version

    current_model = settings.ai_model
    current_pv = _resolve_prompt_version(settings.ai_prompt_version)

    if not current_model:
        # Defensive: orchestrator is present (above 503 check) so model_id
        # must be set; treat the absence as a 503 rather than a 500.
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")

    # PR9 priority pattern: prefer metadata-id matches over content-hash-only.
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

    inner = (
        select(
            BookInsight.id.label("ins_id"),
            BookInsight.metadata_id.label("ins_metadata_id"),
            BookInsight.content_hash.label("ins_content_hash"),
            BookInsight.model_id.label("ins_model_id"),
            BookInsight.prompt_version.label("ins_prompt_version"),
            BookInsight.tone.label("ins_tone"),
            BookInsight.language.label("ins_language"),
            BookInsight.payload.label("ins_payload"),
            BookInsight.sources.label("ins_sources"),
            BookInsight.generated_at.label("ins_generated_at"),
        )
        .select_from(LibraryItem)
        .join(
            BookInsight,
            and_(
                BookInsight.superseded_at.is_(None),
                BookInsight.model_id == current_model,
                BookInsight.prompt_version == current_pv,
                BookInsight.tone == style.tone,
                BookInsight.language == style.language,
                or_(
                    and_(
                        BookInsight.metadata_id.is_not(None),
                        BookInsight.metadata_id == LibraryItem.metadata_id,
                    ),
                    BookInsight.content_hash == LibraryItem.content_hash,
                ),
            ),
        )
        .where(
            LibraryItem.user_id == principal.subject,
            LibraryItem.deleted_at.is_(None),
        )
        .order_by(LibraryItem.pk, pick_priority, BookInsight.generated_at.desc())
        .distinct(LibraryItem.pk)
        .subquery()
    )

    stmt = select(inner).order_by(inner.c.ins_generated_at.asc(), inner.c.ins_id.asc())

    # Tuple cursor: strict lexicographic `>` on (generated_at, id).
    if since_ts is not None and since_id is not None:
        from datetime import datetime as _dt

        try:
            since_dt = _dt.fromisoformat(since_ts)
        except ValueError as exc:
            raise HTTPException(
                status.HTTP_400_BAD_REQUEST,
                detail="since_ts must be ISO-8601",
            ) from exc
        stmt = stmt.where(
            or_(
                inner.c.ins_generated_at > since_dt,
                and_(
                    inner.c.ins_generated_at == since_dt,
                    inner.c.ins_id > since_id,
                ),
            )
        )

    stmt = stmt.limit(limit + 1)
    rows = (await session.execute(stmt)).all()

    next_cursor: InsightSyncCursor | None = None
    if len(rows) > limit:
        boundary = rows[limit - 1]
        next_cursor = InsightSyncCursor(
            generated_at=boundary.ins_generated_at.isoformat(),
            id=boundary.ins_id,
        )
        rows = rows[:limit]

    items = [
        InsightSyncItem(
            id=r.ins_id,
            identity=DocumentIdentity(
                metadata_id=r.ins_metadata_id,
                content_hash=r.ins_content_hash,
            ),
            payload=BookInsightPayload.model_validate(r.ins_payload),
            sources=[Citation.model_validate(c) for c in (r.ins_sources or [])],
            model_id=r.ins_model_id,
            prompt_version=r.ins_prompt_version,
            schema_version=(r.ins_payload or {}).get("schema_version", 4),
            tone=r.ins_tone,
            language=r.ins_language,
            generated_at=r.ins_generated_at.isoformat(),
        )
        for r in rows
    ]
    return InsightSyncResponse(
        items=items,
        server_time=datetime.now(UTC).isoformat(),
        next_cursor=next_cursor,
    )


@router.get("/profile", response_model=ReaderProfileResponse)
async def get_profile(
    principal: Annotated[AiPrincipal, Depends(get_ai_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> ReaderProfileResponse:
    """Cache-only profile read.

    No opt-in gate (opted-out users can still read their last generation),
    no LLM call. 404 when no row exists. Refresh / generation lives in
    pr-β's `POST /ai/v1/profile/refresh`.
    """
    row = (
        await session.execute(
            select(ReaderProfile).where(
                ReaderProfile.tenant_id == principal.tenant_id,
                ReaderProfile.subject == principal.subject,
            )
        )
    ).scalar_one_or_none()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="no_profile")
    return ReaderProfileResponse(
        payload=ReaderProfilePayload.model_validate(row.payload),
        schema_version=row.schema_version,
        model_id=row.model_id,
        prompt_version=row.prompt_version,
        input_fingerprint=row.input_fingerprint,
        generated_at=row.generated_at,
    )


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
