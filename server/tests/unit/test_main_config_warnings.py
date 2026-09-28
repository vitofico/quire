"""Boot-time configuration warnings emitted by `create_app()`.

Issue #104: the server used to accept an unknown variable, or AI enabled with
no provider, without saying anything. `create_app()` now logs one
`event=config.warning` line per problem and stores the same messages on
`app.state.config_warnings` so `GET /health` can show them.

Mirrors the wiring pattern in `test_main_deprecations.py`: the real factory
runs with the DB and calibre-web URLs stubbed (the engine is built but never
connects).
"""

from __future__ import annotations

import logging
import os

import pytest
from httpx import ASGITransport, AsyncClient

from quire_server.config import get_settings

# Imported at module scope, not inside a test or fixture. `quire_server.main`
# has a pre-existing module-level `app = create_app()` line (its ASGI
# entrypoint); the first import of the module in a process runs that line as
# a side effect and logs whatever config warnings apply at that moment. Doing
# the import here means that cold-import side effect fires at collection
# time, before any test's `caplog.at_level(...)` block attaches its handler,
# so it never lands in a test's captured records.
from quire_server.main import create_app

UNKNOWN_HINT = "is ignored; check the spelling against docs/configuration.md"


@pytest.fixture(autouse=True)
def _isolate_env(monkeypatch):
    """Start from zero QUIRE_SERVER_* variables, then set the two the factory needs.

    A stray variable in the developer's shell would otherwise show up as an
    unknown-variable warning and make the exact-list assertions flaky.
    """
    for name in list(os.environ):
        if name.upper().startswith("QUIRE_SERVER_"):
            monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("QUIRE_SERVER_DATABASE_URL", "postgresql+asyncpg://x/y")
    monkeypatch.setenv("QUIRE_SERVER_CWA_BASE_URL", "http://stub")
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _create_app():
    return create_app()


def test_unknown_variable_is_logged_and_stored(monkeypatch, caplog):
    monkeypatch.setenv("QUIRE_SERVER_AI_MODLE", "typo")
    with caplog.at_level(logging.WARNING, logger="quire_server.main"):
        app = _create_app()
    assert app.state.config_warnings == [f"Unknown setting QUIRE_SERVER_AI_MODLE {UNKNOWN_HINT}"]
    logged = [r.getMessage() for r in caplog.records if "event=config.warning" in r.getMessage()]
    assert logged == [
        f"event=config.warning msg=Unknown setting QUIRE_SERVER_AI_MODLE {UNKNOWN_HINT}"
    ]


def test_compose_only_port_is_not_flagged(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_PORT", "9000")
    app = _create_app()
    assert app.state.config_warnings == []


def test_clean_config_has_no_warnings():
    app = _create_app()
    assert app.state.config_warnings == []


def test_ai_enabled_without_provider_warns(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "true")
    app = _create_app()
    assert app.state.config_warnings == [
        "AI is enabled but QUIRE_SERVER_AI_BASE_URL and QUIRE_SERVER_AI_MODEL are not set; "
        "the app will report AI as unconfigured. Set them or set QUIRE_SERVER_AI_ENABLED=false"
    ]
    assert getattr(app.state, "ai_orchestrator", None) is None


def test_blank_compose_fallback_counts_as_unset(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "true")
    monkeypatch.setenv("QUIRE_SERVER_AI_BASE_URL", "")
    monkeypatch.setenv("QUIRE_SERVER_AI_MODEL", "")
    app = _create_app()
    assert getattr(app.state, "ai_orchestrator", None) is None
    assert app.state.config_warnings == [
        "AI is enabled but QUIRE_SERVER_AI_BASE_URL and QUIRE_SERVER_AI_MODEL are not set; "
        "the app will report AI as unconfigured. Set them or set QUIRE_SERVER_AI_ENABLED=false"
    ]


def test_unknown_variables_are_listed_before_semantic_warnings(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "true")
    monkeypatch.setenv("QUIRE_SERVER_AI_MODLE", "typo")
    app = _create_app()
    assert len(app.state.config_warnings) == 2
    assert app.state.config_warnings[0].startswith("Unknown setting QUIRE_SERVER_AI_MODLE")
    assert app.state.config_warnings[1].startswith("AI is enabled but")


async def test_health_repeats_config_warnings(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_MODLE", "typo")
    app = _create_app()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        r = await client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["ready"] is True
    assert body["modes"] == ["progress"]
    assert body["warnings"] == [f"Unknown setting QUIRE_SERVER_AI_MODLE {UNKNOWN_HINT}"]


async def test_health_warnings_empty_on_clean_config():
    app = _create_app()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        r = await client.get("/health")
    assert r.json()["warnings"] == []
