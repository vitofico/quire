"""The AI timeout settings the operator sets must reach the orchestrator.

Issue #102: `/profile/refresh` quotes `QUIRE_SERVER_AI_PROFILE_TIMEOUT_S` in its
504 body, so an operator who raises that variable and still gets cut off at the
constructor default is told a number that never applied. These tests pin both
generation budgets to the settings they come from.

Mirrors the wiring pattern in `test_main_config_warnings.py`: the real factory
runs with the DB and calibre-web URLs stubbed (the engine is built but never
connects).
"""

from __future__ import annotations

import os

import pytest

from quire_server.config import get_settings

# Imported at module scope, not inside a test or fixture: `quire_server.main`
# has a pre-existing module-level `app = create_app()` line (its ASGI
# entrypoint), and the first import of the module in a process runs that line
# as a side effect. Doing the import here keeps that side effect at collection
# time, outside any test.
from quire_server.main import create_app


@pytest.fixture(autouse=True)
def _isolate_env(monkeypatch):
    """Start from zero QUIRE_SERVER_* variables, then set what the factory needs."""
    for name in list(os.environ):
        if name.upper().startswith("QUIRE_SERVER_"):
            monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("QUIRE_SERVER_DATABASE_URL", "postgresql+asyncpg://x/y")
    monkeypatch.setenv("QUIRE_SERVER_CWA_BASE_URL", "http://stub")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def test_orchestrator_gets_both_configured_timeouts(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "true")
    monkeypatch.setenv("QUIRE_SERVER_AI_BASE_URL", "http://stub/v1")
    monkeypatch.setenv("QUIRE_SERVER_AI_MODEL", "m")
    monkeypatch.setenv("QUIRE_SERVER_AI_PROFILE_TIMEOUT_S", "600")
    monkeypatch.setenv("QUIRE_SERVER_AI_TIMEOUT_S", "42")

    app = create_app()

    orch = app.state.ai_orchestrator
    assert orch._profile_timeout_s == 600.0
    assert orch._ai_timeout_s == 42.0
