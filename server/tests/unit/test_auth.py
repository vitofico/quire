import base64

import httpx
import pytest
from fastapi import HTTPException

from opds_sync.core.auth import CalibreAuthValidator


def _basic(user: str, pw: str) -> str:
    return "Basic " + base64.b64encode(f"{user}:{pw}".encode()).decode("ascii")


def _make_transport():
    calls = {"count": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["count"] += 1
        auth = request.headers.get("authorization", "")
        if not auth.lower().startswith("basic "):
            return httpx.Response(401)
        b64 = auth[6:].strip()
        try:
            user, pw = base64.b64decode(b64).decode().split(":", 1)
        except Exception:
            return httpx.Response(401)
        if user == "alice" and pw == "alicepass":
            return httpx.Response(200, text="<feed/>")
        return httpx.Response(401)

    return httpx.MockTransport(handler), calls


def _validator(transport, *, clock=None, pos_ttl=60, neg_ttl=10):
    client = httpx.AsyncClient(transport=transport, base_url="http://cwa")
    return CalibreAuthValidator(
        client=client,
        cwa_base_url="http://cwa",
        positive_ttl_s=pos_ttl,
        negative_ttl_s=neg_ttl,
        clock=clock or (lambda: 0.0),
    )


class _Clock:
    def __init__(self, start: float = 1000.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now


async def test_valid_creds_return_username():
    t, _ = _make_transport()
    v = _validator(t, clock=_Clock())
    assert (await v.validate(_basic("alice", "alicepass"))) == "alice"


async def test_invalid_creds_raise_401():
    t, _ = _make_transport()
    v = _validator(t, clock=_Clock())
    with pytest.raises(HTTPException) as exc:
        await v.validate(_basic("alice", "wrong"))
    assert exc.value.status_code == 401


async def test_positive_cache_hit_avoids_cwa():
    t, calls = _make_transport()
    v = _validator(t, clock=_Clock(), pos_ttl=60)
    await v.validate(_basic("alice", "alicepass"))
    await v.validate(_basic("alice", "alicepass"))
    await v.validate(_basic("alice", "alicepass"))
    assert calls["count"] == 1


async def test_positive_cache_expires():
    t, calls = _make_transport()
    clock = _Clock()
    v = _validator(t, clock=clock, pos_ttl=60)
    await v.validate(_basic("alice", "alicepass"))
    clock.now += 61.0
    await v.validate(_basic("alice", "alicepass"))
    assert calls["count"] == 2


async def test_negative_cache_short_ttl():
    t, calls = _make_transport()
    clock = _Clock()
    v = _validator(t, clock=clock, neg_ttl=10)
    for _ in range(3):
        with pytest.raises(HTTPException):
            await v.validate(_basic("alice", "wrong"))
    assert calls["count"] == 1
    clock.now += 11.0
    with pytest.raises(HTTPException):
        await v.validate(_basic("alice", "wrong"))
    assert calls["count"] == 2


async def test_cwa_unreachable_returns_503():
    def boom(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("nope")

    t = httpx.MockTransport(boom)
    v = _validator(t, clock=_Clock())
    with pytest.raises(HTTPException) as exc:
        await v.validate(_basic("alice", "alicepass"))
    assert exc.value.status_code == 503


async def test_non_basic_header_rejected():
    t = httpx.MockTransport(lambda r: httpx.Response(200))
    v = _validator(t, clock=_Clock())
    with pytest.raises(HTTPException) as exc:
        await v.validate("Bearer xyz")
    assert exc.value.status_code == 401
