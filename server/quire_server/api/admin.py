"""Server status for operators (issue #102).

Mounted by ``main.create_app`` only when ``QUIRE_SERVER_ADMIN_USERS`` names at
least one user; otherwise every path below is a 404. Everything lives under
``/quire-admin`` because the reference Caddyfile hands each path it does not
claim to calibre-web, which serves its own admin pages under ``/admin``.

GET  /quire-admin/v1/status     what the server runs with, as JSON
POST /quire-admin/v1/ai/probe   one tiny model call: the "test connection" button
GET  /quire-admin               the status as an HTML page
POST /quire-admin/probe         the page's button: probe, then render the page

The AI provider modules are imported inside the functions that need them, so
a sync-only deploy with admin users set keeps its lazy-import boundary.
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Annotated
from urllib.parse import parse_qsl, urlsplit

from fastapi import APIRouter, Depends, FastAPI, HTTPException, Request, status
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel

from quire_server.api.admin_page import render_page
from quire_server.api.health import _enabled_modes, migration_state
from quire_server.config import ENV_PREFIX, Settings, get_settings
from quire_server.core.auth import get_auth_backend

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/quire-admin", tags=["admin"])

MASK = "***"

# Settings whose value is a credential. Shown as MASK when set, null when not.
# `test_every_secret_looking_setting_is_masked` fails if a setting named like
# a secret is added without being listed here.
SECRET_SETTINGS = frozenset({"ai_api_key", "ai_token_secrets"})

# Settings that hold a URL, masked by `_mask_url` whatever their value looks
# like. `test_every_url_setting_is_masked` fails if a `*_url` setting is added
# without being listed here.
URL_SETTINGS = frozenset({"database_url", "cwa_base_url", "ai_base_url"})
UNPARSEABLE_URL = "(not a valid URL; hidden)"

NO_STORE = {"Cache-Control": "no-store"}

PAGE_HEADERS = {
    **NO_STORE,
    "Content-Security-Policy": (
        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; "
        "frame-ancestors 'none'; base-uri 'none'"
    ),
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
}


# ---------------------------------------------------------------------------
# Access
# ---------------------------------------------------------------------------


def _challenge() -> str:
    """The ``WWW-Authenticate`` value that makes a browser ask for a login."""
    if get_settings().auth_backend == "calibreweb":
        return 'Basic realm="Quire Server", charset="UTF-8"'
    return "Bearer"


async def require_admin(
    request: Request,
    backend: Annotated[object, Depends(get_auth_backend)],
) -> str:
    """Resolve the caller and check them against ``QUIRE_SERVER_ADMIN_USERS``.

    Goes to the auth backend directly rather than through ``current_user_id``
    so a 401 can carry the challenge header; without it a browser shows the
    error body instead of a login prompt.
    """
    try:
        user = await backend.current_user(request)  # type: ignore[attr-defined]
    except HTTPException as exc:
        if exc.status_code == status.HTTP_401_UNAUTHORIZED:
            raise HTTPException(
                status_code=exc.status_code,
                detail=exc.detail,
                headers={"WWW-Authenticate": _challenge()},
            ) from None
        raise
    if user.user_id.lower() not in request.app.state.admin_users:
        logger.info("event=admin.forbidden user_id=%s", user.user_id)
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="This account is not listed in QUIRE_SERVER_ADMIN_USERS.",
        )
    return user.user_id


async def require_same_origin(request: Request) -> None:
    """Refuse a POST that another site made the admin's browser send.

    Browsers resend cached Basic credentials on a cross-site form POST, so
    without this any page could make an admin's browser spend a model call.
    ``Sec-Fetch-Site`` is sent by every current browser; ``Origin`` covers
    older ones. A request with neither (curl, scripts) is not a browser
    being tricked and passes.

    The ``Origin`` fallback compares host and port, not scheme: behind a
    TLS-terminating proxy the server cannot tell which scheme the browser
    used, and a page on the same host over plain HTTP is not another site's
    page. It only runs for browsers old enough to lack ``Sec-Fetch-Site``,
    and at worst lets through one model call.
    """
    site = request.headers.get("sec-fetch-site")
    if site is not None:
        if site == "same-origin":
            return
    else:
        origin = request.headers.get("origin")
        if origin is None:
            return
        hosts = {request.headers.get("host"), request.headers.get("x-forwarded-host")}
        if urlsplit(origin).netloc in hosts - {None}:
            return
    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail="Cross-site request refused.",
    )


Admin = Annotated[str, Depends(require_admin)]


# ---------------------------------------------------------------------------
# Status document
# ---------------------------------------------------------------------------


def _mask_url(value: str) -> str:
    """Hide everything in a URL that can carry a credential.

    The userinfo password becomes MASK (split on the last ``@``, so a
    password that itself contains ``@`` is masked whole), and so does every
    query value and the fragment, since some drivers and providers accept a
    password or key there. A value that does not parse as scheme plus host
    is hidden whole: it is misconfigured anyway, and there is no telling
    which part of it is secret.
    """
    try:
        parts = urlsplit(value)
        password = parts.password
    except ValueError:
        return UNPARSEABLE_URL
    if not parts.scheme or not parts.netloc:
        return UNPARSEABLE_URL
    netloc = parts.netloc
    if password is not None:
        userinfo, _, hostport = netloc.rpartition("@")
        netloc = f"{userinfo.split(':', 1)[0]}:{MASK}@{hostport}"
    query = "&".join(f"{key}={MASK}" for key, _ in parse_qsl(parts.query, keep_blank_values=True))
    fragment = MASK if parts.fragment else ""
    return parts._replace(netloc=netloc, query=query, fragment=fragment).geturl()


def settings_view(settings: Settings) -> list[dict[str, object]]:
    """Every setting by its environment variable name, secrets masked.

    ``source`` is ``set`` when the value came from the environment or
    ``.env`` and ``default`` when the code default applies, which answers
    "did my line in .env reach the container?" (issue #104).
    """
    rows: list[dict[str, object]] = []
    for name in Settings.model_fields:
        value = getattr(settings, name)
        if name in SECRET_SETTINGS:
            value = MASK if value else None
        elif isinstance(value, str) and (name in URL_SETTINGS or "://" in value):
            value = _mask_url(value)
        rows.append(
            {
                "name": f"{ENV_PREFIX}{name.upper()}",
                "value": value,
                "source": "set" if name in settings.model_fields_set else "default",
            }
        )
    return rows


async def build_status(app: FastAPI) -> dict[str, object]:
    settings = get_settings()
    try:
        migrations: dict[str, object] = await migration_state(settings)
    except Exception as exc:  # noqa: BLE001 — shown on the page, never raised
        logger.warning("event=admin.migrations_unreadable error_class=%s", type(exc).__name__)
        migrations = {"error": "database or migration scripts unreadable"}

    ai: dict[str, object] | None = None
    if settings.ai_enabled:
        from quire_server.api.ai import ai_health_payload

        ai = {
            "configured": bool(settings.ai_base_url and settings.ai_model),
            "health": (await ai_health_payload(app)).model_dump(),
        }

    return {
        "version": settings.version,
        "modes": _enabled_modes(settings.progress_enabled, settings.ai_enabled),
        "warnings": list(getattr(app.state, "config_warnings", [])),
        "ai": ai,
        "migrations": migrations,
        "settings": settings_view(settings),
    }


# ---------------------------------------------------------------------------
# AI probe
# ---------------------------------------------------------------------------


class _ProbeAnswer(BaseModel):
    ok: bool


_PROBE_SYSTEM = "You are a connection check for Quire Server."
_PROBE_USER = 'Reply with the JSON object {"ok": true}.'


async def run_probe(app: FastAPI) -> dict[str, object]:
    """Send one tiny structured request through the server's own AI client.

    The whole probe gets ``QUIRE_SERVER_AI_TIMEOUT_S``, the budget of one
    insight model call: a local model loading on its first call can take
    longer than any shorter fixed budget, and the timeout hint then names
    the variable that governs. The deadline covers the client's reshaped or
    corrected follow-up requests too, so the wait the page promises holds.
    The outcome is recorded in the AI health holder, so ``GET /ai/v1/health``
    reflects it too.
    """
    settings = get_settings()
    client = getattr(app.state, "ai_client", None)
    if client is None:
        return {
            "ok": False,
            "model": settings.ai_model,
            "elapsed_ms": 0,
            "error": {
                "code": "ai_not_configured",
                "message": "AI is not configured on this server.",
                "hint": (
                    "Set QUIRE_SERVER_AI_BASE_URL and QUIRE_SERVER_AI_MODEL, keep "
                    "QUIRE_SERVER_AI_ENABLED=true, then restart the server."
                ),
                "provider_status": None,
            },
        }

    from quire_server.core.ai.client import ProviderError, ProviderTimeout
    from quire_server.core.ai.provider_errors import describe

    health = getattr(app.state, "ai_health", None)
    failure: ProviderError | None = None
    started = time.monotonic()
    try:
        async with asyncio.timeout(settings.ai_timeout_s):
            await client.chat_structured(
                system=_PROBE_SYSTEM,
                user=_PROBE_USER,
                schema=_ProbeAnswer,
                timeout_s=settings.ai_timeout_s,
            )
    except TimeoutError:
        failure = ProviderTimeout("probe deadline reached")
    except ProviderError as exc:
        failure = exc
    elapsed_ms = round((time.monotonic() - started) * 1000)

    if failure is not None:
        info = describe(failure, timeout_s=settings.ai_timeout_s, model=settings.ai_model)
        if health is not None:
            await health.record_provider_failure(error_class=type(failure).__name__)
        logger.warning("event=admin.ai_probe ok=false code=%s elapsed_ms=%d", info.code, elapsed_ms)
        return {
            "ok": False,
            "model": settings.ai_model,
            "elapsed_ms": elapsed_ms,
            "error": info.as_detail(),
        }

    if health is not None:
        await health.record_provider_success(model_id=settings.ai_model)
    logger.info("event=admin.ai_probe ok=true elapsed_ms=%d", elapsed_ms)
    return {"ok": True, "model": settings.ai_model, "elapsed_ms": elapsed_ms, "error": None}


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------


@router.get("/v1/status")
async def get_status(request: Request, _: Admin) -> JSONResponse:
    return JSONResponse(await build_status(request.app), headers=NO_STORE)


@router.post("/v1/ai/probe", dependencies=[Depends(require_same_origin)])
async def post_probe(request: Request, _: Admin) -> JSONResponse:
    return JSONResponse(await run_probe(request.app), headers=NO_STORE)


@router.get("", response_class=HTMLResponse)
async def get_page(request: Request, _: Admin) -> HTMLResponse:
    return HTMLResponse(render_page(await build_status(request.app)), headers=PAGE_HEADERS)


@router.post("/probe", response_class=HTMLResponse, dependencies=[Depends(require_same_origin)])
async def post_page_probe(request: Request, _: Admin) -> HTMLResponse:
    # Probe first so the page's AI health section already shows its outcome.
    probe = await run_probe(request.app)
    return HTMLResponse(
        render_page(await build_status(request.app), probe=probe), headers=PAGE_HEADERS
    )
