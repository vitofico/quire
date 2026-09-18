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

from quire_server.config import get_settings

UNKNOWN_HINT = "is ignored; check the spelling against server/README.md (Environment variables)"


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
    from quire_server.main import create_app

    return create_app()


def test_unknown_variable_is_logged_and_stored(monkeypatch, caplog):
    monkeypatch.setenv("QUIRE_SERVER_AI_MODLE", "typo")
    with caplog.at_level(logging.WARNING, logger="quire_server.main"):
        app = _create_app()
    assert app.state.config_warnings == [f"Unknown setting QUIRE_SERVER_AI_MODLE {UNKNOWN_HINT}"]
    # De-duplicated: quire_server/main.py builds a module-level `app` at
    # import time (`app = create_app()`), so the first test in a session to
    # import quire_server.main also observes that boot's warning alongside
    # this call's own, both carrying the identical message. Dedup keeps the
    # assertion about message content, not about how many times a cold
    # import happens to fire in this process.
    logged = list(
        dict.fromkeys(
            r.getMessage() for r in caplog.records if "event=config.warning" in r.getMessage()
        )
    )
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
