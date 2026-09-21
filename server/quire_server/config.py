import os
from collections.abc import Mapping
from functools import lru_cache
from pathlib import Path
from typing import Literal

from dotenv import dotenv_values
from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

ENV_PREFIX = "QUIRE_SERVER_"

# Variables that share the prefix but are consumed by docker compose, never by
# the server process. Listed so the unknown-variable scan does not flag them.
COMPOSE_ONLY_ENV_VARS: frozenset[str] = frozenset({"QUIRE_SERVER_PORT"})


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix=ENV_PREFIX, env_file=".env", extra="ignore")

    database_url: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/opds_sync"
    cwa_base_url: str = "http://calibre-web.calibre-web.svc.cluster.local:8083"
    cwa_probe_path: str = "/opds"
    cwa_probe_timeout_s: float = 3.0
    auth_cache_positive_ttl_s: int = 60
    auth_cache_negative_ttl_s: int = 10
    auth_cache_max_entries: int = 1024
    log_level: str = "INFO"

    # Deploy mode flags (PR-A). Both default true → full-stack mode. Flip to
    # `false` to disable a domain entirely (router not mounted, migration
    # branch skipped, lazy provider imports inhibited).
    progress_enabled: bool = True

    # Maximum allowed request body size in bytes (default 1 MiB). Bounds the
    # hosted cost surface and protects against accidental large uploads.
    # Enforced by RequestSizeMiddleware; oversized requests get 413.
    max_request_bytes: int = 1_048_576

    # Phase 0, task S-2: hard cap on the number of entries `POST
    # /library/v1/sync` will accept in a single request. Middleware
    # already enforces a byte ceiling (max_request_bytes); this guards the
    # parsed-entry count so a tightly-packed but valid payload can't blow
    # past Postgres bind-parameter ceilings or balloon a single transaction.
    # Conservative default; raise once we have data on real Android-side
    # library sizes.
    library_sync_max_items: int = 500

    # AI substrate (Phase 1). Default flipped from False → True in PR-A so the
    # full-stack mode is the documented default. Existing prod deployments
    # already set QUIRE_SERVER_AI_ENABLED=true explicitly, so this flip is
    # invisible there; sync-only deploys must now explicitly set false.
    ai_enabled: bool = True
    ai_base_url: str | None = None
    ai_api_key: str | None = None
    ai_model: str | None = None
    ai_timeout_s: float = 120.0
    ai_max_concurrency: int = 4
    ai_sources: str = "wikipedia,openlibrary"  # CSV; "" disables retrieval
    ai_retrieval_timeout_s: float = 8.0
    ai_prompt_version: str = "1"

    # Phase 0, task S-3: deprecated server-side metadata fallback for the
    # AI insight endpoints. When False (the default per the push-model API
    # direction), POST /ai/v1/insights/{lookup,regenerate} reject requests
    # that omit the `bundle` block with 400 `metadata_required`. When True,
    # the server falls back to reconstructing a MetadataBundle from the
    # caller's local `library_items` row keyed by identity_hash. The flag
    # exists only for backward compatibility during the OSS push-model
    # migration.
    #
    # DEPRECATED since the Phase 0 release (2026-05-22). Slated for removal
    # in 2 minor releases. When the flag is True at boot, `create_app()`
    # emits both a `DeprecationWarning` (Python tooling channel) and a
    # `logging.warning` (operator channel) naming the env var and the
    # removal window. See `_warn_deprecated_ai_metadata_lookup` in
    # `quire_server/main.py` and the "Environment variables" table in
    # `server/README.md`. The push-model contract (clients send `bundle`
    # in the request body) is documented in `docs/sync-api.md` under
    # POST /ai/v1/insights/lookup.
    ai_metadata_server_lookup_enabled: bool = False

    # Quota protection — important when AI_BASE_URL points at a metered/cloud provider
    # (Ollama Cloud subscription, OpenAI, Anthropic, OpenRouter, …). Free-tier Ollama
    # Cloud burns quota the same as a paid API.
    ai_rate_per_min: int = 10  # process-wide token bucket against AI_BASE_URL
    ai_daily_budget: int = 200  # generations per user per UTC day; 0 disables
    ai_regen_daily_limit: int = 3  # tighter ceiling for /insights/regenerate per user/day
    # PR-ζ: /insights/promote per-user, per-UTC-day cap. Process-local
    # counter (no DB row); pod restart resets — acceptable because cost of
    # a promote is dominated by the row-copy, not LLM. 0 disables the limit.
    ai_promote_daily_limit: int = 100

    # pr-β (Bundle 3, coordinator §3.11): per-user, per-UTC-day cap on
    # POST /ai/v1/profile/refresh. Counted against ai_usage_daily.profile_count.
    # 0 disables the cap entirely.
    ai_profile_refresh_daily_limit: int = 3
    # pr-β (Bundle 3, coordinator §3.7 + §3.14): wall-clock timeout for one
    # /profile/refresh model call. Discovery fetches (sequential, up to 5
    # authors at ~8s each) plus the LLM call must fit under this cap.
    ai_profile_timeout_s: float = 90.0

    # ---------------------------------------------------------------------
    # PR-B: AI auth abstraction (seam-only). Sync routes unaffected.
    # ---------------------------------------------------------------------
    # Mode of the /ai/v1/* authenticator:
    #   * "basic"  – wraps the existing calibre-web Basic-auth verifier;
    #                tenant_id is always "local". Default.
    #   * "token"  – DEPRECATED (Phase 0, task X-2): HMAC-SHA256 bearer
    #                tokens with claims {iss, aud, exp, iat, sub,
    #                tenant_id, scope?} and a header {alg=HS256, kid}.
    #                Predates the primary AuthBackend abstraction; will be
    #                removed in 2 minor releases. Use
    #                ``QUIRE_SERVER_AUTH_BACKEND=native`` (NativeAuth) as
    #                the long-term replacement. A startup warning fires
    #                when this mode is active with ai_enabled=true.
    ai_auth_mode: Literal["basic", "token"] = "basic"

    # JSON object env var mapping `kid -> secret` (UTF-8 string >= 32 bytes).
    # Required when ai_auth_mode == "token". Multiple kids enable rotation:
    # tokens signed under any listed kid are accepted. Token issuance is NOT
    # implemented here — this server only verifies. Token `sub` claims must
    # be globally unique under the issuer (e.g. tenant-qualified at
    # issuance) since `principal.subject` is stored verbatim in user-scoped
    # tables (preferences, daily quota).
    ai_token_secrets: dict[str, str] | None = None

    # Required when ai_auth_mode == "token". Validated against token `iss`.
    ai_token_issuer: str | None = None

    # Required when ai_auth_mode == "token". Validated against token `aud`.
    ai_token_audience: str | None = None

    # ---------------------------------------------------------------------
    # Phase 0, task S-1: primary AuthBackend selector.
    # ---------------------------------------------------------------------
    # Which AuthBackend resolves ``Depends(current_user_id)``:
    #   * ``"calibreweb"`` (default, OSS) – wraps the existing
    #     :class:`CalibreAuthValidator`. ``Authorization: Basic ...``.
    #     ``user_id`` is the lowercase CWA username.
    #   * ``"native"`` – Quire Cloud. Email/password + opaque bearer
    #     session tokens. ``user_id`` is ``"native:<NativeUser.id>"``.
    #     Mounts the ``/auth/v1/*`` router; CalibreWeb mode does not.
    auth_backend: Literal["calibreweb", "native"] = "calibreweb"

    # NativeAuth session lifetime. Default 30 days; clients should refresh
    # by logging in again before expiry. No refresh-token mechanism exists
    # at this stage (deferred per spec).
    native_session_ttl_s: int = 30 * 24 * 3600

    @field_validator("ai_base_url", "ai_api_key", "ai_model", mode="before")
    @classmethod
    def _blank_means_unset(cls, value: object) -> object:
        """Treat an empty or whitespace-only string as ``None``.

        Compose files and shells hand the container ``""`` for an unset
        variable more often than they omit it. Issue #104.
        """
        if isinstance(value, str) and not value.strip():
            return None
        return value


def _dotenv_names() -> set[str]:
    """Names in the dotenv file(s) ``Settings`` reads, or an empty set.

    Same ``env_file`` config and same parser as pydantic-settings, so the
    scan sees exactly the lines the settings object sees. A missing file
    is not an error, just no names.
    """
    env_file = Settings.model_config.get("env_file")
    if not env_file:
        return set()
    paths = [env_file] if isinstance(env_file, (str, os.PathLike)) else list(env_file)
    names: set[str] = set()
    for candidate in paths:
        path = Path(candidate)
        if path.is_file():
            names.update(name for name in dotenv_values(path) if name)
    return names


def unknown_env_vars(environ: Mapping[str, str] | None = None) -> list[str]:
    """Names of ``QUIRE_SERVER_*`` variables the server does not read.

    Issue #104: a misspelled variable used to be ignored without a word.
    Comparison is case-insensitive because pydantic-settings matches names
    that way. ``environ`` defaults to ``os.environ``; tests pass a dict.
    The scan also covers the dotenv file named by
    ``Settings.model_config["env_file"]``, since ``Settings`` reads it
    directly and a locally run process never copies it into the
    environment first. The result is sorted so log output is stable.
    """
    env = os.environ if environ is None else environ
    names = set(env) | _dotenv_names()
    known = {f"{ENV_PREFIX}{name.upper()}" for name in Settings.model_fields}
    return sorted(
        name
        for name in names
        if name.upper().startswith(ENV_PREFIX)
        and name.upper() not in known
        and name.upper() not in COMPOSE_ONLY_ENV_VARS
    )


def config_warnings(settings: Settings) -> list[str]:
    """Semantic checks that must not crash boot but must not stay silent.

    Pure function so tests can call it with a constructed ``Settings``. Each
    message names the variable to fix and never echoes a value. Native auth
    has no environment prerequisites beyond migrations, which ``/readyz``
    already reports, so there is no check for it here.
    """
    out: list[str] = []
    if settings.ai_enabled:
        missing = [
            name
            for name, value in (
                ("QUIRE_SERVER_AI_BASE_URL", settings.ai_base_url),
                ("QUIRE_SERVER_AI_MODEL", settings.ai_model),
            )
            if not value
        ]
        if missing:
            names = " and ".join(missing)
            verb, pronoun = ("is", "it") if len(missing) == 1 else ("are", "them")
            out.append(
                f"AI is enabled but {names} {verb} not set; the app will report AI as "
                f"unconfigured. Set {pronoun} or set QUIRE_SERVER_AI_ENABLED=false"
            )
        if settings.ai_base_url and not settings.ai_base_url.rstrip("/").endswith("/v1"):
            out.append(
                "QUIRE_SERVER_AI_BASE_URL does not end with /v1; OpenAI-compatible providers "
                "such as Ollama expect for example http://ollama:11434/v1"
            )
    if not settings.progress_enabled and not settings.ai_enabled:
        out.append(
            "QUIRE_SERVER_PROGRESS_ENABLED and QUIRE_SERVER_AI_ENABLED are both false; "
            "only /health and /readyz are served"
        )
    return out


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
