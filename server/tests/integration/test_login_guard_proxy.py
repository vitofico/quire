"""The login guard counts readers, not the proxy in front of them.

The server runs under uvicorn, which rewrites the client address from
``X-Forwarded-For`` only for peers listed in ``FORWARDED_ALLOW_IPS``. These
tests drive the app through that same uvicorn wrapping.
"""

import httpx
import pytest
import uvicorn

PROXY = "172.18.0.5"
STRANGER = "203.0.113.7"
LOGIN = {"email": "nobody@example.test", "password": "not-a-real-password"}


async def _attempts(app, peer: str, forwarded_for: list[str]) -> list[int]:
    config = uvicorn.Config(object.__getattribute__(app, "_real"), proxy_headers=True)
    config.load()
    transport = httpx.ASGITransport(app=config.loaded_app, client=(peer, 40000))
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        return [
            (
                await client.post(
                    "/auth/v1/login",
                    json=LOGIN,
                    headers={"X-Forwarded-For": address},
                )
            ).status_code
            for address in forwarded_for
        ]


def _clients(count: int) -> list[str]:
    return [f"198.51.100.{n}" for n in range(1, count + 1)]


async def test_without_a_trusted_proxy_every_reader_shares_the_proxy_budget(
    client_factory, app, monkeypatch
):
    monkeypatch.delenv("FORWARDED_ALLOW_IPS", raising=False)
    client_factory(auth_backend="transition", ai_enabled=False, skip_auth_overrides=True)
    statuses = await _attempts(app, PROXY, _clients(9))
    assert statuses[:8] == [401] * 8
    assert statuses[8] == 429


async def test_a_trusted_proxy_gives_each_reader_their_own_budget(client_factory, app, monkeypatch):
    monkeypatch.setenv("FORWARDED_ALLOW_IPS", PROXY)
    client_factory(auth_backend="transition", ai_enabled=False, skip_auth_overrides=True)
    assert await _attempts(app, PROXY, _clients(9)) == [401] * 9
    one_reader = await _attempts(app, PROXY, ["198.51.100.200"] * 9)
    assert one_reader[8] == 429


@pytest.mark.parametrize("trusted", [PROXY, "172.18.0.0/16"])
async def test_a_forged_header_from_an_untrusted_client_is_ignored(
    client_factory, app, monkeypatch, trusted
):
    monkeypatch.setenv("FORWARDED_ALLOW_IPS", trusted)
    client_factory(auth_backend="transition", ai_enabled=False, skip_auth_overrides=True)
    statuses = await _attempts(app, STRANGER, _clients(9))
    assert statuses[8] == 429
