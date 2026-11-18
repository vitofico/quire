"""Structured logs carry the incoming request ID only during that request."""

import logging
from logging.handlers import BufferingHandler

import httpx


async def test_request_id_on_child_logs_and_reset_after_response(monkeypatch):
    from quire_server.config import get_settings
    from quire_server.main import create_app

    monkeypatch.setenv("QUIRE_SERVER_DATABASE_URL", "postgresql+asyncpg://unused/fixture")
    monkeypatch.setenv("QUIRE_SERVER_CWA_BASE_URL", "http://fixture")
    get_settings.cache_clear()
    logger = logging.getLogger("quire_server.test.request_scope")
    handler = BufferingHandler(capacity=10)
    root = logging.getLogger()
    root.addHandler(handler)
    try:
        app = create_app()

        @app.get("/test-log")
        async def emit():
            logger.warning("inside request")
            return {"ok": True}

        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app), base_url="http://fixture"
        ) as client:
            response = await client.get("/test-log", headers={"X-Request-ID": "fixture-request"})
        assert response.status_code == 200
        logger.warning("after request")
        assert [
            (r.getMessage(), r.request_id) for r in handler.buffer if r.name == logger.name
        ] == [
            ("inside request", "fixture-request"),
            ("after request", ""),
        ]
    finally:
        root.removeHandler(handler)
        handler.close()
        get_settings.cache_clear()
