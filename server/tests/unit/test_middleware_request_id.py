"""Unit tests for RequestIDMiddleware via a minimal Starlette app."""

from __future__ import annotations

import pytest
from httpx import ASGITransport, AsyncClient
from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route

from quire_server.api.middleware.request_id import RequestIDMiddleware
from quire_server.core.logging_ctx import request_id_var


def _make_app(capture: dict) -> Starlette:
    async def endpoint(request):
        capture["seen_during_request"] = request_id_var.get()
        return JSONResponse({"ok": True})

    app = Starlette(routes=[Route("/", endpoint)])
    app.add_middleware(RequestIDMiddleware)
    return app


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "incoming,preserved",
    [
        pytest.param("client-abc-123", True, id="valid"),
        pytest.param(None, False, id="absent"),
        pytest.param("x" * 200, False, id="oversized"),
        pytest.param("abc\x01def", False, id="non-printable"),
    ],
)
async def test_request_id_header_and_context(incoming, preserved):
    capture: dict = {}
    app = _make_app(capture)
    headers = {"X-Request-ID": incoming} if incoming is not None else {}
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as client:
        response = await client.get("/", headers=headers)
    assert response.status_code == 200
    request_id = response.headers["X-Request-ID"]
    assert capture["seen_during_request"] == request_id
    if preserved:
        assert request_id == incoming
    else:
        assert request_id != incoming
        assert len(request_id) == 32


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
