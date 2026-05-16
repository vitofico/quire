"""Unit tests for RequestIDMiddleware via a minimal Starlette app."""

from __future__ import annotations

import pytest
from httpx import ASGITransport, AsyncClient
from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route

from opds_sync.api.middleware.request_id import RequestIDMiddleware
from opds_sync.core.logging_ctx import request_id_var


def _make_app(capture: dict) -> Starlette:
    async def endpoint(request):
        capture["seen_during_request"] = request_id_var.get()
        return JSONResponse({"ok": True})

    app = Starlette(routes=[Route("/", endpoint)])
    app.add_middleware(RequestIDMiddleware)
    return app


@pytest.mark.asyncio
async def test_preserves_valid_incoming_header():
    capture: dict = {}
    app = _make_app(capture)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/", headers={"X-Request-ID": "client-abc-123"})
    assert r.status_code == 200
    assert r.headers["X-Request-ID"] == "client-abc-123"
    assert capture["seen_during_request"] == "client-abc-123"


@pytest.mark.asyncio
async def test_generates_when_absent():
    capture: dict = {}
    app = _make_app(capture)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/")
    assert r.status_code == 200
    rid = r.headers["X-Request-ID"]
    assert rid and len(rid) == 32  # uuid4 hex
    assert capture["seen_during_request"] == rid


@pytest.mark.asyncio
async def test_rejects_oversized_header_and_generates_new():
    capture: dict = {}
    app = _make_app(capture)
    too_long = "x" * 200
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/", headers={"X-Request-ID": too_long})
    rid = r.headers["X-Request-ID"]
    assert rid != too_long
    assert len(rid) == 32


@pytest.mark.asyncio
async def test_rejects_non_printable_header():
    capture: dict = {}
    app = _make_app(capture)
    bad = "abc\x01def"
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/", headers={"X-Request-ID": bad})
    assert r.headers["X-Request-ID"] != bad


@pytest.mark.asyncio
async def test_resets_contextvar_after_response():
    capture: dict = {}
    app = _make_app(capture)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        await c.get("/", headers={"X-Request-ID": "first-id"})
        # Outside the request scope, the var should be empty again.
        assert request_id_var.get() == ""
        # Second request without header generates a new one independent of first.
        r = await c.get("/")
    assert r.headers["X-Request-ID"] != "first-id"
