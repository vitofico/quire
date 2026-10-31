"""MCP security defaults, independent mode gates, and host lifecycle composition."""

import os
import subprocess
import sys

import httpx
import pytest
from pydantic import ValidationError

from quire_server.config import Settings, get_settings


@pytest.mark.parametrize(
    "value",
    [
        "http://example.com",
        "https://example.com/mcp",
        # pragma: allowlist nextline secret - test fixture
        "https://user:secret@example.com",
        "https://example.com?secret=1",
        "https://example.com#fragment",
        "https://example.com:bad",
        "https://*",
        "https://example.com\n",
        "https://example.com:",
    ],
)
def test_mcp_public_url_rejects_invalid_origins(value):
    with pytest.raises(ValidationError):
        Settings(mcp_public_url=value, _env_file=None)


def test_mcp_public_url_normalizes_without_identity_side_effects():
    settings = Settings(mcp_public_url="https://Quire.Example:8443/", _env_file=None)
    assert settings.mcp_public_url == "https://quire.example:8443"
    assert Settings(mcp_public_url="https://EXAMPLE.com:443/", _env_file=None).mcp_public_url == (
        "https://example.com"
    )
    assert Settings(mcp_public_url="", _env_file=None).mcp_public_url is None


@pytest.mark.parametrize(
    "progress,ai", [(True, True), (True, False), (False, True), (False, False)]
)
async def test_mode_gate_and_shutdown_hooks(monkeypatch, progress, ai):
    monkeypatch.setenv("QUIRE_SERVER_PROGRESS_ENABLED", str(progress))
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", str(ai))
    monkeypatch.setenv("QUIRE_SERVER_AUTH_BACKEND", "calibreweb")
    get_settings.cache_clear()
    from quire_server.main import create_app

    app = create_app()
    async with app.router.lifespan_context(app):
        assert not app.state.httpx_client.is_closed
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app), base_url="http://localhost"
        ) as client:
            assert (await client.post("/mcp", json={})).status_code == (401 if progress else 404)
            assert (await client.get("/health")).status_code == 200
    assert app.state.httpx_client.is_closed
    get_settings.cache_clear()


async def test_remote_origin_and_no_sdk_tracing(monkeypatch):
    from fastapi import FastAPI
    from mcp.server import _otel

    from quire_server.core.auth_backend import AuthUser
    from quire_server.mcp import mount_highlight_mcp

    def forbidden_span(*args, **kwargs):
        raise AssertionError("Quire must not create telemetry spans")

    monkeypatch.setattr(_otel, "otel_span", forbidden_span)

    class Auth:
        async def current_user(self, request):
            return AuthUser("alice", "calibreweb")

    app = FastAPI()
    app.state.auth_backend = Auth()
    mount_highlight_mcp(app, Settings(mcp_public_url="https://quire.example", _env_file=None), None)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app), base_url="https://quire.example"
        ) as client:
            message = {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}
            response = await client.post(
                "/mcp",
                json=message,
                headers={
                    "Authorization": "Basic test",
                    "Accept": "application/json",
                    "Origin": "https://quire.example",
                },
            )
            assert response.status_code == 200
            assert len(response.json()["result"]["tools"]) == 3
            assert "mcp-session-id" not in response.headers
            assert (
                await client.post("/mcp", headers={"Origin": "https://evil.example"}, json={})
            ).status_code == 403


def test_disabled_mode_does_not_import_sdk():
    code = """
import builtins
original = builtins.__import__
def guarded(name, *args, **kwargs):
    if name == "mcp" or name.startswith("mcp.") or name == "quire_server.mcp":
        raise AssertionError("MCP imported with progress disabled")
    return original(name, *args, **kwargs)
builtins.__import__ = guarded
from quire_server.main import create_app
create_app()
"""
    env = os.environ | {
        "QUIRE_SERVER_PROGRESS_ENABLED": "false",
        "QUIRE_SERVER_AI_ENABLED": "false",
    }
    result = subprocess.run([sys.executable, "-c", code], env=env, capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
