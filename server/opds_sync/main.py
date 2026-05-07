import logging

import httpx
from fastapi import FastAPI

from opds_sync.api import health, progress
from opds_sync.config import get_settings
from opds_sync.core.auth import CalibreAuthValidator
from opds_sync.db.session import configure, make_engine


def create_app() -> FastAPI:
    settings = get_settings()
    logging.basicConfig(level=settings.log_level)

    configure(make_engine(settings.database_url))

    app = FastAPI(title="opds-sync", version="0.2.0")

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

    app.include_router(health.router, prefix="/sync/v1")
    app.include_router(progress.router, prefix="/sync/v1")
    return app


app = create_app()
