from functools import lru_cache
from typing import Literal

from pydantic_settings import (
    BaseSettings,
    PydanticBaseSettingsSource,
    SettingsConfigDict,
)

from quire_server._env_compat import LegacyEnvSettingsSource


class Settings(BaseSettings):
    # env_prefix is the NEW prefix; LegacyEnvSettingsSource consults BOTH
    # prefixes per field at read time. Per Lock #21 the dotenv source still
    # uses env_prefix="QUIRE_SERVER_"; legacy names in .env files are NOT
    # honored.
    model_config = SettingsConfigDict(env_prefix="QUIRE_SERVER_", env_file=".env", extra="ignore")

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: type[BaseSettings],
        init_settings: PydanticBaseSettingsSource,
        env_settings: PydanticBaseSettingsSource,
        dotenv_settings: PydanticBaseSettingsSource,
        file_secret_settings: PydanticBaseSettingsSource,
    ) -> tuple[PydanticBaseSettingsSource, ...]:
        # Replace the default env source with our dual-prefix one. Keep
        # init (kwargs to Settings()), dotenv (.env file — NEW prefix only
        # per Lock #21), and secrets file sources in their default
        # precedence: init > env > dotenv > secrets.
        legacy_env = LegacyEnvSettingsSource(settings_cls)
        return init_settings, legacy_env, dotenv_settings, file_secret_settings

    # DB name remains `opds_sync` as a deliberate non-rename (Lock #20).
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

    # AI substrate (Phase 1). Default flipped from False → True in PR-A so the
    # full-stack mode is the documented default. Existing prod deployments
    # already set OPDS_SYNC_AI_ENABLED=true explicitly, so this flip is
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

    # Quota protection — important when AI_BASE_URL points at a metered/cloud provider
    # (Ollama Cloud subscription, OpenAI, Anthropic, OpenRouter, …). Free-tier Ollama
    # Cloud burns quota the same as a paid API.
    ai_rate_per_min: int = 10  # process-wide token bucket against AI_BASE_URL
    ai_daily_budget: int = 200  # generations per user per UTC day; 0 disables
    ai_regen_daily_limit: int = 3  # tighter ceiling for /insights/regenerate per user/day

    # ---------------------------------------------------------------------
    # PR-B: AI auth abstraction (seam-only). Sync routes unaffected.
    # ---------------------------------------------------------------------
    # Mode of the /ai/v1/* authenticator:
    #   * "basic"  – wraps the existing calibre-web Basic-auth verifier;
    #                tenant_id is always "local". Default.
    #   * "token"  – validates HMAC-SHA256 bearer tokens with claims
    #                {iss, aud, exp, iat, sub, tenant_id, scope?} and a
    #                header {alg=HS256, kid}. Multi-tenant.
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


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
