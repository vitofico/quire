"""Integration tests for the admin status page and its JSON (issue #102).

Auth runs through the real ``CalibreWebBasicAuth`` against the mock
calibre-web in ``tests/conftest.py`` (alice/alicepass, bob/bobpass), because
the login challenge and the allowlist check are the point of these routes.
Tests that need AI set the AI variables themselves, so they run in every
cell of the CI mode matrix.
"""

from __future__ import annotations

import json

import httpx
import pytest

from quire_server.core.ai.client import AIClient
from quire_server.core.auth import CalibreAuthValidator
from quire_server.core.auth_backend import CalibreWebBasicAuth

STATUS = "/quire-admin/v1/status"
PROBE = "/quire-admin/v1/ai/probe"
PAGE = "/quire-admin"
PAGE_PROBE = "/quire-admin/probe"

AI_ENV = {"ai_enabled": "true", "ai_base_url": "http://fake/v1", "ai_model": "test-model"}


@pytest.fixture
async def admin_client(client_factory, app, cwa_transport):
    """``client_factory`` with the real calibre-web auth backend put back."""
    cwa_client = httpx.AsyncClient(transport=cwa_transport, base_url="http://test-cwa")

    def _make(**env):
        ctx = client_factory(**env)
        app.state.auth_backend = CalibreWebBasicAuth(
            CalibreAuthValidator(client=cwa_client, cwa_base_url="http://test-cwa")
        )
        return ctx

    yield _make
    await cwa_client.aclose()


@pytest.fixture
def alice(basic_header) -> dict[str, str]:
    return {"Authorization": basic_header("alice", "alicepass")}


def _install_provider(app, handler) -> list[httpx.Request]:
    """Point the app's AI client at ``handler``; return the requests it sees."""
    seen: list[httpx.Request] = []

    def _record(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return handler(request)

    app.state.ai_client = AIClient(
        base_url="http://fake/v1",
        api_key=None,
        model="test-model",
        transport=httpx.MockTransport(_record),
    )
    return seen


def _answers_ok(request: httpx.Request) -> httpx.Response:
    content = json.dumps({"ok": True})
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


def _unknown_model(request: httpx.Request) -> httpx.Response:
    return httpx.Response(404, json={"error": "model 'test-model' not found"})


# ---------------------------------------------------------------------------
# Mounting and access
# ---------------------------------------------------------------------------


async def test_no_admin_users_means_no_admin_routes(admin_client, alice):
    async with admin_client() as client:
        for method, path in (("GET", STATUS), ("POST", PROBE), ("GET", PAGE), ("POST", PAGE_PROBE)):
            r = await client.request(method, path, headers=alice)
            assert r.status_code == 404, (method, path)


@pytest.mark.parametrize("path", [STATUS, PAGE])
async def test_no_credentials_gets_a_login_challenge(admin_client, path):
    async with admin_client(admin_users="alice") as client:
        r = await client.get(path)

    assert r.status_code == 401
    assert r.headers["www-authenticate"] == 'Basic realm="Quire Server", charset="UTF-8"'


async def test_wrong_password_gets_a_login_challenge(admin_client, basic_header):
    async with admin_client(admin_users="alice") as client:
        r = await client.get(STATUS, headers={"Authorization": basic_header("alice", "nope")})

    assert r.status_code == 401
    assert r.headers["www-authenticate"].startswith("Basic ")


async def test_a_user_outside_the_allowlist_is_refused(admin_client, basic_header):
    async with admin_client(admin_users="alice") as client:
        r = await client.get(STATUS, headers={"Authorization": basic_header("bob", "bobpass")})

    assert r.status_code == 403
    assert "QUIRE_SERVER_ADMIN_USERS" in r.json()["detail"]


# ---------------------------------------------------------------------------
# Status document
# ---------------------------------------------------------------------------


async def test_status_reports_what_the_server_runs_with(admin_client, alice):
    # "Alice" also checks that the allowlist ignores case.
    async with admin_client(admin_users="Alice", ai_api_key="sk-test-secret") as client:
        r = await client.get(STATUS, headers=alice)

    assert r.status_code == 200
    assert r.headers["cache-control"] == "no-store"
    assert "sk-test-secret" not in r.text
    body = r.json()
    assert body["version"] == "dev"
    assert isinstance(body["modes"], list)
    assert isinstance(body["warnings"], list)
    assert body["migrations"]["missing"] == []
    assert body["migrations"]["applied"] == ["ai_007", "auth_001", "progress_003"]
    rows = {row["name"]: row for row in body["settings"]}
    assert rows["QUIRE_SERVER_ADMIN_USERS"] == {
        "name": "QUIRE_SERVER_ADMIN_USERS",
        "value": "Alice",
        "source": "set",
    }
    assert rows["QUIRE_SERVER_AI_API_KEY"]["value"] == "***"
    assert ":***@" in rows["QUIRE_SERVER_DATABASE_URL"]["value"]


async def test_status_embeds_the_ai_health_snapshot(admin_client, alice):
    async with admin_client(admin_users="alice", **AI_ENV) as client:
        r = await client.get(STATUS, headers=alice)

    ai = r.json()["ai"]
    assert ai["configured"] is True
    assert ai["health"]["provider_reachable"] is None


async def test_status_has_no_ai_block_when_ai_is_off(admin_client, alice):
    async with admin_client(admin_users="alice", ai_enabled="false") as client:
        r = await client.get(STATUS, headers=alice)

    assert r.json()["ai"] is None


# ---------------------------------------------------------------------------
# Probe
# ---------------------------------------------------------------------------


async def test_probe_without_a_provider_says_ai_is_not_configured(admin_client, alice):
    async with admin_client(admin_users="alice", ai_enabled="true") as client:
        r = await client.post(PROBE, headers=alice)

    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "ai_not_configured"


async def test_probe_reports_a_provider_that_answers(admin_client, app, alice):
    ctx = admin_client(admin_users="alice", **AI_ENV)
    seen = _install_provider(app, _answers_ok)
    async with ctx as client:
        r = await client.post(PROBE, headers=alice)
        status = await client.get(STATUS, headers=alice)

    body = r.json()
    assert body["ok"] is True
    assert body["model"] == "test-model"
    assert body["error"] is None
    assert len(seen) == 1
    assert json.loads(seen[0].content)["model"] == "test-model"
    assert status.json()["ai"]["health"]["provider_reachable"] is True


async def test_probe_explains_a_provider_that_refuses(admin_client, app, alice):
    ctx = admin_client(admin_users="alice", **AI_ENV)
    _install_provider(app, _unknown_model)
    async with ctx as client:
        r = await client.post(PROBE, headers=alice)
        status = await client.get(STATUS, headers=alice)

    body = r.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "provider_rejected"
    assert body["error"]["provider_status"] == 404
    assert "QUIRE_SERVER_AI_MODEL" in body["error"]["hint"]
    health = status.json()["ai"]["health"]
    assert health["provider_reachable"] is False
    assert health["last_failure_class"] == "ProviderRejected"


@pytest.mark.parametrize(
    ("headers", "expected"),
    [
        ({"Sec-Fetch-Site": "cross-site"}, 403),
        ({"Sec-Fetch-Site": "same-site"}, 403),
        ({"Origin": "https://evil.example"}, 403),
        ({"Sec-Fetch-Site": "same-origin"}, 200),
        ({"Origin": "http://test"}, 200),
        ({}, 200),
    ],
)
async def test_probe_refuses_posts_from_other_sites(admin_client, alice, headers, expected):
    async with admin_client(admin_users="alice") as client:
        api = await client.post(PROBE, headers={**alice, **headers})
        page = await client.post(PAGE_PROBE, headers={**alice, **headers})

    assert api.status_code == expected
    assert page.status_code == expected


# ---------------------------------------------------------------------------
# HTML page
# ---------------------------------------------------------------------------


async def test_page_renders_the_status_for_an_admin(admin_client, alice):
    async with admin_client(admin_users="alice", ai_api_key="sk-test-secret", **AI_ENV) as client:
        r = await client.get(PAGE, headers=alice)

    assert r.status_code == 200
    assert r.headers["content-type"].startswith("text/html")
    assert r.headers["content-security-policy"].startswith("default-src 'none'")
    assert r.headers["cache-control"] == "no-store"
    assert "Build <code>dev</code>" in r.text
    assert 'action="/quire-admin/probe"' in r.text
    assert "sk-test-secret" not in r.text


async def test_page_button_shows_the_probe_result(admin_client, app, alice):
    ctx = admin_client(admin_users="alice", **AI_ENV)
    _install_provider(app, _unknown_model)
    async with ctx as client:
        r = await client.post(PAGE_PROBE, headers={**alice, "Sec-Fetch-Site": "same-origin"})

    assert r.status_code == 200
    assert "The AI provider does not know the configured model." in r.text
    assert "ollama pull test-model" in r.text
