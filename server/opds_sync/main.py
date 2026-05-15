import logging

import httpx
from fastapi import FastAPI

from opds_sync.api import ai, health, progress
from opds_sync.config import get_settings
from opds_sync.core.ai.client import AIClient
from opds_sync.core.ai.retrieval import Retriever
from opds_sync.core.ai.service import InsightOrchestrator
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

    if settings.ai_enabled and settings.ai_base_url and settings.ai_model:
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

    app.include_router(health.router, prefix="/sync/v1")
    app.include_router(progress.router, prefix="/sync/v1")
    app.include_router(ai.router, prefix="/ai/v1")
    return app


app = create_app()
