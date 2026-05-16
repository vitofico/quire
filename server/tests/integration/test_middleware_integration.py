"""End-to-end middleware verification: real FastAPI app + ASGI roundtrip."""

from __future__ import annotations

import base64

from httpx import ASGITransport, AsyncClient


def _basic(user: str, pw: str = "x") -> dict[str, str]:
    token = base64.b64encode(f"{user}:{pw}".encode()).decode()
    return {"Authorization": f"Basic {token}"}


async def test_request_id_echoed_on_200(monkeypatch, postgres_url, alembic_upgrade):
    monkeypatch.setenv("OPDS_SYNC_DATABASE_URL", postgres_url)
    monkeypatch.setenv("OPDS_SYNC_CWA_BASE_URL", "http://test-cwa")
    from opds_sync.config import get_settings

    get_settings.cache_clear()
    from opds_sync.main import create_app

    app = create_app()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/health", headers={"X-Request-ID": "trace-abc-123"})
    assert r.status_code == 200
    assert r.headers["X-Request-ID"] == "trace-abc-123"


async def test_request_id_generated_when_absent(monkeypatch, postgres_url, alembic_upgrade):
    monkeypatch.setenv("OPDS_SYNC_DATABASE_URL", postgres_url)
    monkeypatch.setenv("OPDS_SYNC_CWA_BASE_URL", "http://test-cwa")
    from opds_sync.config import get_settings

    get_settings.cache_clear()
    from opds_sync.main import create_app

    app = create_app()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/health")
    assert r.status_code == 200
    rid = r.headers["X-Request-ID"]
    assert rid and len(rid) == 32


async def test_request_id_present_on_413_content_length(monkeypatch, postgres_url, alembic_upgrade):
    """Critical: middleware ordering puts RequestID outermost, so 413 from
    RequestSize must still carry X-Request-ID."""
    monkeypatch.setenv("OPDS_SYNC_DATABASE_URL", postgres_url)
    monkeypatch.setenv("OPDS_SYNC_CWA_BASE_URL", "http://test-cwa")
    monkeypatch.setenv("OPDS_SYNC_MAX_REQUEST_BYTES", "32")
    from opds_sync.config import get_settings

    get_settings.cache_clear()
    from opds_sync.main import create_app

    app = create_app()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.post(
            "/sync/v1/progress",
            content=b"x" * 200,  # > 32 byte cap
            headers={**_basic("alice"), "X-Request-ID": "size-trace-1"},
        )
    assert r.status_code == 413
    assert r.headers.get("X-Request-ID") == "size-trace-1"


async def test_request_id_present_on_413_chunked_minimal_app():
    """Chunked-body 413+request-id verification.

    Routed at a minimal Starlette test endpoint rather than a real FastAPI
    route because FastAPI's pydantic validator can reject the first chunk
    (as malformed JSON) before our middleware sees the second chunk and
    enforces the size cap. The size+request-id ordering is what's under
    test here, not FastAPI's body-parser semantics.
    """
    from starlette.applications import Starlette
    from starlette.responses import JSONResponse
    from starlette.routing import Route

    from opds_sync.api.middleware.request_id import RequestIDMiddleware
    from opds_sync.api.middleware.request_size import RequestSizeMiddleware

    async def echo(request):
        b = await request.body()
        return JSONResponse({"got": len(b)})

    app = Starlette(routes=[Route("/echo", echo, methods=["POST"])])
    app.add_middleware(RequestSizeMiddleware, max_bytes=32)
    app.add_middleware(RequestIDMiddleware)

    async def big_chunked():
        yield b"a" * 20
        yield b"b" * 40

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.post(
            "/echo",
            content=big_chunked(),
            headers={"X-Request-ID": "chunked-trace-2"},
        )
    assert r.status_code == 413
    assert r.headers.get("X-Request-ID") == "chunked-trace-2"
