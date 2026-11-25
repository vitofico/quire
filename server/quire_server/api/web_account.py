"""Public browser configuration and request binding for the Quire reader."""

from typing import Annotated

from fastapi import APIRouter, Depends, FastAPI, HTTPException, Request, Response
from fastapi.responses import JSONResponse

from quire_server.api.browser_identity import IdentitySettings
from quire_server.config import Settings
from quire_server.core.auth import current_auth_user
from quire_server.core.auth_backend import AuthUser, matches_identity_host


def native_web_allowed(request: Request) -> bool:
    host = request.app.state.web_native_auth_host
    return matches_identity_host(request, host)


def install_web_account(app: FastAPI, settings: Settings, identity: IdentitySettings) -> None:
    router = APIRouter(tags=["web"], prefix="/quire/v1")
    ai_compatible = settings.ai_enabled and settings.ai_auth_mode == "basic"
    account_services = settings.progress_enabled or ai_compatible
    basic_login = account_services and settings.auth_backend in {"calibreweb", "transition"}
    app.state.web_native_auth_host = (
        identity.public_host
        if settings.web_enabled
        and settings.web_native_auth_enabled
        and settings.auth_backend in {"native", "transition"}
        and account_services
        else None
    )

    @router.get("/bootstrap")
    async def bootstrap(request: Request, response: Response):
        response.headers["Cache-Control"] = "no-store"
        native = native_web_allowed(request)
        login = (
            "transition"
            if basic_login and native
            else ("basic" if basic_login else "native" if native else "none")
        )
        result = {
            "version": 1,
            "sync": settings.progress_enabled,
            "ai": settings.ai_enabled,
            "aiAvailable": bool(
                (basic_login or native)
                and ai_compatible
                and settings.ai_base_url
                and settings.ai_model
            ),
            "login": login,
            "password": native,
            "oidc": native and bool(identity.oidc_issuer),
            "migration": login == "transition" and settings.self_migration_enabled,
            "catalogUrl": settings.web_catalog_url,
            "guestReading": settings.web_guest_reading,
            "accountManagement": (
                identity.public_url + "/cloud/account" if native and identity.oidc_issuer else None
            ),
        }
        if app.state.web_native_auth_host and not native:
            result["nativeWebUrl"] = identity.public_url + "/quire/"
        return result

    if basic_login:

        @router.get("/session")
        async def session(
            user: Annotated[AuthUser, Depends(current_auth_user)], response: Response
        ):
            if user.backend != "calibreweb":
                raise HTTPException(
                    401, "basic auth required", headers={"Cache-Control": "no-store"}
                )
            response.headers["Cache-Control"] = "no-store"
            return {"version": 1, "user": {"id": user.user_id, "backend": user.backend}}

    @app.middleware("http")
    async def bind_web_account(request, call_next):
        expected = request.headers.get("x-quire-account")
        path = request.url.path
        if expected is None or not (
            path
            in {"/auth/v1/me", "/auth/v1/logout", "/auth/v1/account/export", "/quire/v1/session"}
            or path.startswith(("/sync/v1/", "/library/v1/", "/ai/v1/"))
        ):
            return await call_next(request)
        try:
            user = await app.state.auth_backend.current_user(request)
            if expected != user.user_id.encode().hex():
                raise HTTPException(409, "account_changed")
            response = await call_next(request)
        except HTTPException as exc:
            response = JSONResponse({"detail": exc.detail}, status_code=exc.status_code)
        response.headers["Cache-Control"] = "no-store"
        return response

    app.include_router(router)
