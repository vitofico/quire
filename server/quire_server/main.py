"""FastAPI app factory. Mounts routers conditionally based on deploy mode flags.

PR-A introduces three deploy modes controlled by env vars:
  * QUIRE_SERVER_PROGRESS_ENABLED=true (default) → /sync/v1/* mounted
  * QUIRE_SERVER_AI_ENABLED=true (default) → /ai/v1/* mounted + AI orchestrator wired

Always-on regardless of mode: /health and /readyz.

Provider lazy-import boundary: quire_server.core.ai.* and quire_server.api.ai
import only inside the ai_enabled block. quire_server.api.progress imports only
inside the progress_enabled block. This keeps sync-only and ai-only deploys
from paying the cost of the other domain's modules.
"""

from __future__ import annotations

import logging
import warnings

import httpx
from fastapi import FastAPI

from quire_server.api import health
from quire_server.api.middleware import RequestIDMiddleware, RequestSizeMiddleware
from quire_server.config import Settings, get_settings
from quire_server.core.auth import CalibreAuthValidator
from quire_server.core.auth_backend import CalibreWebBasicAuth, NativeAuth
from quire_server.core.logging_ctx import RequestIdLogFilter
from quire_server.db.session import configure, make_engine, make_session_factory


def _warn_deprecated_ai_metadata_lookup(settings: Settings) -> None:
    """Emit a startup deprecation notice for ``ai_metadata_server_lookup_enabled``.

    Phase 0, task S-4: the server-side AI metadata lookup fallback (added by
    task S-3) is the only path on which `quire_server` still pulls book
    metadata from the operator's own DB instead of accepting it on the
    request body. The Phase 0 push-model contract makes the client the sole
    source of metadata; this flag is the migration escape hatch.

    Deprecated since the Phase 0 release (2026-05-22). Removal target:
    2 minor releases later. Both channels fire so that:

    * `warnings.warn(..., DeprecationWarning)` surfaces in test runs, IDEs,
      and any tooling that opts into `-W error::DeprecationWarning`.
    * `logging.warning` is the channel operators actually read in container
      logs and Loki/Grafana dashboards.

    Fires once at boot. Per-request signalling on the fallback path is
    intentionally NOT added — the operator already has the boot warning,
    and per-request log spam would not change their behavior.
    """
    if not settings.ai_metadata_server_lookup_enabled:
        return
    message = (
        "QUIRE_SERVER_AI_METADATA_SERVER_LOOKUP_ENABLED is deprecated and will be "
        "removed in 2 minor releases. Have clients push metadata via the `bundle` "
        "block on POST /ai/v1/insights/{lookup,regenerate} instead. See "
        "server/README.md and docs/sync-api.md for the push-model contract."
    )
    # stacklevel=3: warn() -> this helper -> create_app() -> caller of create_app().
    # Points the warning at the boot site (e.g. uvicorn factory) rather than this
    # helper, which is the useful frame for tooling.
    warnings.warn(message, DeprecationWarning, stacklevel=3)
    logging.getLogger(__name__).warning(message)


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
            "QUIRE_SERVER_AI_AUTH_MODE=token requires QUIRE_SERVER_AI_TOKEN_SECRETS "
            "to be a non-empty JSON object mapping kid -> secret"
        )
    for kid, secret in secrets.items():
        if not isinstance(kid, str) or not kid:
            raise RuntimeError(
                "QUIRE_SERVER_AI_TOKEN_SECRETS has an empty kid; "
                "every kid must be a non-empty string"
            )
        if not isinstance(secret, str):
            raise RuntimeError(f"QUIRE_SERVER_AI_TOKEN_SECRETS[{kid!r}] must be a string")
        if len(secret.encode("utf-8")) < 32:
            raise RuntimeError(
                f"QUIRE_SERVER_AI_TOKEN_SECRETS[{kid!r}] is shorter than 32 bytes; "
                "use a random 32+ byte secret"
            )
    if not settings.ai_token_issuer or not settings.ai_token_issuer.strip():
        raise RuntimeError("QUIRE_SERVER_AI_AUTH_MODE=token requires QUIRE_SERVER_AI_TOKEN_ISSUER")
    if not settings.ai_token_audience or not settings.ai_token_audience.strip():
        raise RuntimeError(
            "QUIRE_SERVER_AI_AUTH_MODE=token requires QUIRE_SERVER_AI_TOKEN_AUDIENCE"
        )


def _warn_if_ai_auth_mode_deprecated(settings: Settings) -> None:
    """Phase 0, task X-2: deprecate ``QUIRE_SERVER_AI_AUTH_MODE=token``.

    The AI-only Bearer auth seam predates the primary :class:`AuthBackend`
    abstraction introduced in S-1. ``NativeAuth`` is now the long-term home
    for session-token-based authentication. Token mode of ``AI_AUTH_MODE``
    is being retired with a removal window of two minor releases.

    Only warns when token mode would actually be active — i.e. AI is
    enabled. Operators with stale ``AI_AUTH_MODE=token`` in a sync-only
    ``.env`` are not nagged because the setting is inert for them.

    Emitted **after** ``_validate_ai_auth_settings`` so misconfigured
    token-mode deploys still crashloop with the original ``RuntimeError``
    rather than producing a deprecation warning that gets eaten by the
    subsequent raise.

    The structured ``event=config.deprecated`` suffix matches the existing
    log-style convention so log scrapers can match on a stable key. Never
    log token secrets, issuer, or audience.
    """
    if not settings.ai_enabled:
        return
    if settings.ai_auth_mode != "token":
        return
    msg = (
        "QUIRE_SERVER_AI_AUTH_MODE=token is deprecated and will be removed "
        "in 2 minor releases. Use QUIRE_SERVER_AUTH_BACKEND=native as the "
        "long-term replacement for token-based authentication; existing "
        "HS256 tokens continue to validate during the deprecation window. "
        "See server/README.md for the migration note. "
        "event=config.deprecated setting=QUIRE_SERVER_AI_AUTH_MODE value=token"
    )
    logging.getLogger(__name__).warning(msg)
    warnings.warn(msg, DeprecationWarning, stacklevel=2)


def _build_ai_authenticator(settings: Settings, validator: CalibreAuthValidator, auth_backend):
    """Construct the AiAuthenticator implied by the auth config.

    The concrete authenticator is chosen from BOTH ``settings.auth_backend``
    and ``settings.ai_auth_mode`` (the env stays a two-value ``basic|token``
    switch — there is no third ``native`` env mode):

    * ``ai_auth_mode=token`` → :class:`TokenAiAuthenticator` (the deprecated
      HMAC seam, regardless of primary backend; kept for its removal window).
    * ``ai_auth_mode=basic`` + ``auth_backend=native`` →
      :class:`BackendAiAuthenticator`, delegating to the configured
      ``NativeAuth`` so ``/ai/v1/*`` shares the primary session-token identity
      layer. This is the long-term replacement for token mode.
    * ``ai_auth_mode=basic`` + ``auth_backend=calibreweb`` →
      :class:`BasicAuthAiAuthenticator` (today's OSS default, byte-exact).

    Imported here (rather than at module top) to keep the AI auth surface
    lazy alongside the rest of the AI imports — sync-only deploys never pay
    for the HMAC / token code.
    """
    from quire_server.api.ai_auth import (
        BackendAiAuthenticator,
        BasicAuthAiAuthenticator,
        TokenAiAuthenticator,
    )

    if settings.ai_auth_mode == "basic":
        if settings.auth_backend == "native":
            return BackendAiAuthenticator(auth_backend)
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

    # Phase 0, task S-4: surface the deprecation of the server-side AI
    # metadata lookup fallback. Fires once at boot when the flag is True;
    # the helper is a no-op otherwise. Placed AFTER logging setup so the
    # operator-channel warning goes through the configured root handler,
    # and BEFORE engine creation so a misconfigured DB URL doesn't
    # accidentally mask the notice.
    _warn_deprecated_ai_metadata_lookup(settings)

    engine = make_engine(settings.database_url)
    configure(engine)
    session_factory = make_session_factory(engine)

    app = FastAPI(title="quire-server", version="0.3.0")

    httpx_client = httpx.AsyncClient(timeout=settings.cwa_probe_timeout_s)
    app.state.httpx_client = httpx_client
    # The CalibreWeb validator is kept constructed unconditionally so that
    # tests overriding ``app.state.auth_validator`` (the pre-S-1 pattern)
    # still work even in NativeAuth deployments. The AI-routes Basic
    # authenticator also relies on its existence. In NativeAuth mode the
    # primary-auth path simply doesn't dispatch through it.
    app.state.auth_validator = CalibreAuthValidator(
        client=httpx_client,
        cwa_base_url=settings.cwa_base_url,
        probe_path=settings.cwa_probe_path,
        positive_ttl_s=settings.auth_cache_positive_ttl_s,
        negative_ttl_s=settings.auth_cache_negative_ttl_s,
        max_entries=settings.auth_cache_max_entries,
    )

    # Phase 0, task S-1: pick the primary AuthBackend before any router
    # mounts so /readyz and the AI-auth wiring below see a consistent
    # picture. Under ``auth_backend=native`` the AI seam delegates to this
    # same backend (see ``_build_ai_authenticator``), so there is no longer a
    # forbidden ``native + ai_auth_mode=basic`` combination to guard against.
    if settings.auth_backend == "native":
        app.state.auth_backend = NativeAuth(
            session_factory=session_factory,
            session_ttl_s=settings.native_session_ttl_s,
        )
    else:
        app.state.auth_backend = CalibreWebBasicAuth(app.state.auth_validator)

    @app.on_event("shutdown")
    async def _close() -> None:
        await httpx_client.aclose()

    # Always-on root endpoints (no prefix). Mounted before mode gates so they
    # remain available even when both flags are false.
    app.include_router(health.router)

    # Phase 0, task S-1: ``/auth/v1/*`` exists only when NativeAuth is the
    # primary backend. A CalibreWeb deployment treats those URLs as 404 —
    # which is the correct shape for "this server doesn't speak that".
    if settings.auth_backend == "native":
        from quire_server.api.auth import router as auth_router

        app.include_router(auth_router, prefix="/auth/v1")

    if settings.progress_enabled:
        # Lazy import: only pull progress + library routers when progress mode
        # is on. The `library_items` migration lives on the `progress` alembic
        # branch, so the gate must match for the table to exist.
        from quire_server.api.library import router as library_router
        from quire_server.api.progress import router as progress_router

        app.include_router(progress_router, prefix="/sync/v1")
        app.include_router(library_router, prefix="/library/v1")

    if settings.ai_enabled:
        # PR-B: validate AI auth settings and build the authenticator BEFORE
        # either AI router branch mounts. Token-mode misconfiguration must
        # crashloop here — never silently downgrade to basic. Sync-only
        # deploys (ai_enabled=false) skip this block entirely.
        _validate_ai_auth_settings(settings)
        # Phase 0, task X-2: surface the deprecation only once token mode
        # is known to be valid. Misconfigured token deploys still crashloop
        # via _validate_ai_auth_settings above.
        _warn_if_ai_auth_mode_deprecated(settings)
        app.state.ai_authenticator = _build_ai_authenticator(
            settings, app.state.auth_validator, app.state.auth_backend
        )

        if settings.ai_base_url and settings.ai_model:
            # Lazy imports: only pull AI modules when AI mode is on AND configured.
            # This is the "provider lazy-import boundary" — keeps the openai-client
            # surface (httpx wrapper today; possibly the openai SDK tomorrow) and
            # the Wikipedia/OpenLibrary clients out of sync-only deploys.
            from quire_server.api.ai import router as ai_router
            from quire_server.core.ai._compat import _resolve_prompt_version
            from quire_server.core.ai.client import AIClient
            from quire_server.core.ai.health_state import AiHealthState
            from quire_server.core.ai.retrieval import Retriever
            from quire_server.core.ai.service import InsightOrchestrator

            ai_client = AIClient(
                base_url=settings.ai_base_url,
                api_key=settings.ai_api_key,
                model=settings.ai_model,
            )
            sources_enabled = tuple(
                s.strip() for s in (settings.ai_sources or "").split(",") if s.strip()
            )
            # PR5: process-local reachability holder, fed by chat_structured +
            # retrieval calls. Exposed via GET /ai/v1/health.
            ai_health = AiHealthState()
            app.state.ai_health = ai_health
            orch = InsightOrchestrator(
                ai=ai_client,
                retriever_factory=lambda s: Retriever(
                    session=s,
                    timeout_s=settings.ai_retrieval_timeout_s,
                    health_state=ai_health,
                ),
                sources_enabled=sources_enabled,
                model_id=settings.ai_model,
                # PR-ε / coordinator §3.1 / Lock #19: the in-code constant
                # ``prompts.PROMPT_VERSION`` is the source of truth. The legacy
                # default value ``"1"`` is treated as "unset" so the constant
                # wins. Any other value (set in an env override) is honored as
                # an emergency rollback per Lock #2.
                prompt_version=_resolve_prompt_version(settings.ai_prompt_version),
                max_concurrency=settings.ai_max_concurrency,
                ai_timeout_s=settings.ai_timeout_s,
                rate_per_min=settings.ai_rate_per_min,
                daily_budget=settings.ai_daily_budget,
                regen_daily_limit=settings.ai_regen_daily_limit,
                health_state=ai_health,
                # Per-task session factory: `_retrieve` mints a fresh
                # AsyncSession per source-lookup so wikipedia + openlibrary
                # don't race on a single asyncpg connection.
                session_factory=session_factory,
            )
            app.state.ai_orchestrator = orch
            app.include_router(ai_router, prefix="/ai/v1")
        else:
            # AI enabled but missing base_url/model — still mount the router
            # so the /ai/v1/config endpoint can report `configured: false`.
            # The authenticator is already wired above, so token-mode deploys
            # still require valid Bearer tokens on /config (no silent downgrade).
            from quire_server.api.ai import router as ai_router
            from quire_server.core.ai.health_state import AiHealthState

            # Attach an empty holder so GET /ai/v1/health returns an all-null
            # snapshot rather than the "defensive empty body" branch.
            app.state.ai_health = AiHealthState()
            app.include_router(ai_router, prefix="/ai/v1")

    # Middleware: registered LAST is OUTERMOST in ASGI execution order.
    # We want RequestID outermost so it can attach X-Request-ID to ANY
    # response (including 413s from RequestSize). So add RequestSize first,
    # then RequestID.
    app.add_middleware(RequestSizeMiddleware, max_bytes=settings.max_request_bytes)
    app.add_middleware(RequestIDMiddleware)

    return app


app = create_app()
