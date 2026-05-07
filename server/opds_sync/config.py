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


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
