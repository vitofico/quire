"""Unit tests for RequestSizeMiddleware."""

from __future__ import annotations

import pytest
from httpx import ASGITransport, AsyncClient
from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route

from quire_server.api.middleware.request_size import RequestSizeMiddleware


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
@pytest.mark.parametrize(
    "chunked,size,expected_status",
    [
        pytest.param(False, 10, 200, id="content-length-at-limit"),
        pytest.param(False, 11, 413, id="content-length-over-limit"),
        pytest.param(False, 50, 413, id="content-length-large"),
        pytest.param(True, 10, 200, id="chunked-at-limit"),
        pytest.param(True, 11, 413, id="chunked-over-limit"),
        pytest.param(True, 14, 413, id="chunked-large"),
    ],
)
async def test_request_size_bounds(chunked, size, expected_status):
    app = _make_app(max_bytes=10)

    async def chunked_iter():
        # An async iterable leaves Content-Length unset in httpx.
        yield b"a" * 6
        yield b"b" * (size - 6)

    content = chunked_iter() if chunked else b"x" * size
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.post("/echo", content=content)
    assert r.status_code == expected_status
    if expected_status == 200:
        assert r.json() == {"got": size}
    else:
        assert "exceeds" in r.json()["detail"]


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


@pytest.mark.asyncio
@pytest.mark.parametrize("chunked", [False, True], ids=["content-length", "chunked"])
async def test_a_named_route_has_its_own_ceiling(chunked):
    import re

    app = Starlette(routes=[Route("/upload/{n}", _echo, methods=["PUT", "POST"])])
    app.add_middleware(
        RequestSizeMiddleware, max_bytes=10, routes=[("PUT", re.compile(r"/upload/[0-9]+"), 30)]
    )

    def body(size):
        if not chunked:
            return b"x" * size

        async def chunks():
            yield b"a" * (size // 2)
            yield b"b" * (size - size // 2)

        return chunks()

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        assert (await c.put("/upload/1", content=body(30))).json() == {"got": 30}
        over = await c.put("/upload/1", content=body(31))
        assert over.status_code == 413
        assert over.json() == {"detail": "request body exceeds 30 bytes"}
        # Only that method and path: everything else keeps the global limit.
        assert (await c.post("/upload/1", content=body(11))).status_code == 413
        assert (await c.put("/upload/1/x", content=body(11))).status_code in (404, 413)


async def _echo(request):
    return JSONResponse({"got": len(await request.body())})
