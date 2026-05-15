from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="OPDS_SYNC_", env_file=".env", extra="ignore")

    database_url: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/opds_sync"
    cwa_base_url: str = "http://calibre-web.calibre-web.svc.cluster.local:8083"
    cwa_probe_path: str = "/opds"
    cwa_probe_timeout_s: float = 3.0
    auth_cache_positive_ttl_s: int = 60
    auth_cache_negative_ttl_s: int = 10
    auth_cache_max_entries: int = 1024
    log_level: str = "INFO"

    # AI substrate (Phase 1)
    ai_enabled: bool = False
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
    ai_rate_per_min: int = 10          # process-wide token bucket against AI_BASE_URL
    ai_daily_budget: int = 200         # generations per user per UTC day; 0 disables
    ai_regen_daily_limit: int = 3      # tighter ceiling for /insights/regenerate per user/day


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
