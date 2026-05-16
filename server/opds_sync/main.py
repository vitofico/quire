"""FastAPI app factory. Mounts routers conditionally based on deploy mode flags.

PR-A introduces three deploy modes controlled by env vars:
  * OPDS_SYNC_PROGRESS_ENABLED=true (default) → /sync/v1/* mounted
  * OPDS_SYNC_AI_ENABLED=true (default) → /ai/v1/* mounted + AI orchestrator wired

Always-on regardless of mode: /health and /readyz.

Provider lazy-import boundary: opds_sync.core.ai.* and opds_sync.api.ai
import only inside the ai_enabled block. opds_sync.api.progress imports only
inside the progress_enabled block. This keeps sync-only and ai-only deploys
from paying the cost of the other domain's modules.
"""

from __future__ import annotations

import logging

import httpx
from fastapi import FastAPI

from opds_sync.api import health
from opds_sync.api.middleware import RequestIDMiddleware, RequestSizeMiddleware
from opds_sync.config import Settings, get_settings
from opds_sync.core.auth import CalibreAuthValidator
from opds_sync.core.logging_ctx import RequestIdLogFilter
from opds_sync.db.session import configure, make_engine


def _validate_ai_auth_settings(settings: Settings) -> None:
    """Fail loudly when token-mode AI auth is misconfigured.

    Called once during create_app() when settings.ai_enabled is true. The
    intent: a hosted multi-tenant deployment that intended token mode but
    forgot the secrets must crashloop rather than silently accept anything.

    Basic mode requires no extra config (today's default).
    """
    if settings.ai_auth_mode != "token":
        return
    secrets = settings.ai_token_secrets
    if not isinstance(secrets, dict) or not secrets:
        raise RuntimeError(
            "OPDS_SYNC_AI_AUTH_MODE=token requires OPDS_SYNC_AI_TOKEN_SECRETS "
            "to be a non-empty JSON object mapping kid -> secret"
        )
    for kid, secret in secrets.items():
        if not isinstance(kid, str) or not kid:
            raise RuntimeError(
                "OPDS_SYNC_AI_TOKEN_SECRETS has an empty kid; every kid must "
                "be a non-empty string"
            )
        if not isinstance(secret, str):
            raise RuntimeError(
                f"OPDS_SYNC_AI_TOKEN_SECRETS[{kid!r}] must be a string"
            )
        if len(secret.encode("utf-8")) < 32:
            raise RuntimeError(
                f"OPDS_SYNC_AI_TOKEN_SECRETS[{kid!r}] is shorter than 32 bytes; "
                "use a random 32+ byte secret"
            )
    if not settings.ai_token_issuer or not settings.ai_token_issuer.strip():
        raise RuntimeError(
            "OPDS_SYNC_AI_AUTH_MODE=token requires OPDS_SYNC_AI_TOKEN_ISSUER"
        )
    if not settings.ai_token_audience or not settings.ai_token_audience.strip():
        raise RuntimeError(
            "OPDS_SYNC_AI_AUTH_MODE=token requires OPDS_SYNC_AI_TOKEN_AUDIENCE"
        )


def _build_ai_authenticator(settings: Settings, validator: CalibreAuthValidator):
    """Construct the AiAuthenticator implied by settings.ai_auth_mode.

    Imported here (rather than at module top) to keep the AI auth surface
    lazy alongside the rest of the AI imports — sync-only deploys never pay
    for the HMAC / token code.
    """
    from opds_sync.api.ai_auth import (
        BasicAuthAiAuthenticator,
        TokenAiAuthenticator,
    )

    if settings.ai_auth_mode == "basic":
        return BasicAuthAiAuthenticator(validator=validator)
    # token mode — validation already ran, so secrets/iss/aud are guaranteed.
    assert settings.ai_token_secrets is not None
    assert settings.ai_token_issuer is not None
    assert settings.ai_token_audience is not None
    return TokenAiAuthenticator(
        secrets=settings.ai_token_secrets,
        issuer=settings.ai_token_issuer,
        audience=settings.ai_token_audience,
    )


def create_app() -> FastAPI:
    settings = get_settings()
    logging.basicConfig(level=settings.log_level)
    # Inject request_id into every log record routed through the root
    # handlers. Logger-level filters on the root logger do NOT apply to
    # records propagated up from child loggers, so we attach the filter to
    # the handlers themselves. Idempotent if create_app() runs more than
    # once (e.g., in tests).
    _filter = RequestIdLogFilter()
    for _h in logging.getLogger().handlers:
        if not any(isinstance(f, RequestIdLogFilter) for f in _h.filters):
            _h.addFilter(_filter)

    configure(make_engine(settings.database_url))

    app = FastAPI(title="opds-sync", version="0.3.0")

    httpx_client = httpx.AsyncClient(timeout=settings.cwa_probe_timeout_s)
    app.state.httpx_client = httpx_client
    app.state.auth_validator = CalibreAuthValidator(
        client=httpx_client,
        cwa_base_url=settings.cwa_base_url,
        probe_path=settings.cwa_probe_path,
        positive_ttl_s=settings.auth_cache_positive_ttl_s,
        negative_ttl_s=settings.auth_cache_negative_ttl_s,
        max_entries=settings.auth_cache_max_entries,
    )

    @app.on_event("shutdown")
    async def _close() -> None:
        await httpx_client.aclose()

    # Always-on root endpoints (no prefix). Mounted before mode gates so they
    # remain available even when both flags are false.
    app.include_router(health.router)

    if settings.progress_enabled:
        # Lazy import: only pull progress router when progress mode is on.
        from opds_sync.api.progress import router as progress_router

        app.include_router(progress_router, prefix="/sync/v1")

    if settings.ai_enabled:
        # PR-B: validate AI auth settings and build the authenticator BEFORE
        # either AI router branch mounts. Token-mode misconfiguration must
        # crashloop here — never silently downgrade to basic. Sync-only
        # deploys (ai_enabled=false) skip this block entirely.
        _validate_ai_auth_settings(settings)
        app.state.ai_authenticator = _build_ai_authenticator(
            settings, app.state.auth_validator
        )

        if settings.ai_base_url and settings.ai_model:
            # Lazy imports: only pull AI modules when AI mode is on AND configured.
            # This is the "provider lazy-import boundary" — keeps the openai-client
            # surface (httpx wrapper today; possibly the openai SDK tomorrow) and
            # the Wikipedia/OpenLibrary clients out of sync-only deploys.
            from opds_sync.api.ai import router as ai_router
            from opds_sync.core.ai.client import AIClient
            from opds_sync.core.ai.retrieval import Retriever
            from opds_sync.core.ai.service import InsightOrchestrator

            ai_client = AIClient(
                base_url=settings.ai_base_url,
                api_key=settings.ai_api_key,
                model=settings.ai_model,
            )
            sources_enabled = tuple(
                s.strip() for s in (settings.ai_sources or "").split(",") if s.strip()
            )
            orch = InsightOrchestrator(
                ai=ai_client,
                retriever_factory=lambda s: Retriever(
                    session=s, timeout_s=settings.ai_retrieval_timeout_s
                ),
                sources_enabled=sources_enabled,
                model_id=settings.ai_model,
                prompt_version=settings.ai_prompt_version,
                max_concurrency=settings.ai_max_concurrency,
                ai_timeout_s=settings.ai_timeout_s,
                rate_per_min=settings.ai_rate_per_min,
                daily_budget=settings.ai_daily_budget,
                regen_daily_limit=settings.ai_regen_daily_limit,
            )
            app.state.ai_orchestrator = orch
            app.include_router(ai_router, prefix="/ai/v1")
        else:
            # AI enabled but missing base_url/model — still mount the router
            # so the /ai/v1/config endpoint can report `configured: false`.
            # The authenticator is already wired above, so token-mode deploys
            # still require valid Bearer tokens on /config (no silent downgrade).
            from opds_sync.api.ai import router as ai_router

            app.include_router(ai_router, prefix="/ai/v1")

    # Middleware: registered LAST is OUTERMOST in ASGI execution order.
    # We want RequestID outermost so it can attach X-Request-ID to ANY
    # response (including 413s from RequestSize). So add RequestSize first,
    # then RequestID.
    app.add_middleware(RequestSizeMiddleware, max_bytes=settings.max_request_bytes)
    app.add_middleware(RequestIDMiddleware)

    return app


app = create_app()
