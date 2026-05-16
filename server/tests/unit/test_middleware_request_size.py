"""Unit tests for RequestSizeMiddleware."""

from __future__ import annotations

import pytest
from httpx import ASGITransport, AsyncClient
from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route

from opds_sync.api.middleware.request_size import RequestSizeMiddleware


def _make_app(max_bytes: int) -> Starlette:
    async def echo(request):
        body = await request.body()
        return JSONResponse({"got": len(body)})

    async def get_endpoint(request):
        return JSONResponse({"ok": True})

    app = Starlette(
        routes=[
            Route("/echo", echo, methods=["POST", "PUT"]),
            Route("/get", get_endpoint, methods=["GET", "DELETE"]),
        ]
    )
    app.add_middleware(RequestSizeMiddleware, max_bytes=max_bytes)
    return app


@pytest.mark.asyncio
async def test_passthrough_under_cap():
    app = _make_app(max_bytes=100)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.post("/echo", content=b"hello")
    assert r.status_code == 200
    assert r.json() == {"got": 5}


@pytest.mark.asyncio
async def test_413_when_content_length_exceeds_cap():
    app = _make_app(max_bytes=10)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.post("/echo", content=b"x" * 50)
    assert r.status_code == 413
    assert "exceeds" in r.json()["detail"]


@pytest.mark.asyncio
async def test_413_for_chunked_body_exceeding_cap():
    app = _make_app(max_bytes=10)

    async def chunked_iter():
        # Yielding bytes via httpx triggers chunked transfer encoding (no
        # Content-Length).
        yield b"a" * 6
        yield b"b" * 8  # cumulative 14 > 10

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.post("/echo", content=chunked_iter())
    assert r.status_code == 413


@pytest.mark.asyncio
async def test_get_bypasses_check():
    app = _make_app(max_bytes=10)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/get")
    assert r.status_code == 200


@pytest.mark.asyncio
async def test_delete_bypasses_check():
    app = _make_app(max_bytes=10)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.delete("/get")
    assert r.status_code == 200
