"""Startup deprecation notices emitted by `create_app()`.

Phase 0, task S-4: when `QUIRE_SERVER_AI_METADATA_SERVER_LOOKUP_ENABLED=true`,
the server-side AI metadata lookup fallback is active and the operator must
be warned via both Python tooling (`DeprecationWarning`) and operator logs
(`logging.warning`). When the flag is False (the new default), neither
channel must emit a notice about this flag.

Mirrors the wiring pattern in `test_main_ai_auth_validation.py` — exercises
the actual `quire_server.main.create_app()` factory rather than a synthetic
double.
"""

from __future__ import annotations

import logging
import warnings

import pytest

from quire_server.config import get_settings


@pytest.fixture(autouse=True)
def _isolate_env(monkeypatch):
    """Wipe metadata-fallback + DB / CWA env so each test starts clean.

    Mirrors `test_main_ai_auth_validation._isolate_env`. The DB and CWA
    URLs are set to non-network stubs because `create_app()` constructs an
    engine but does not connect; we just need the URL to be parseable.
    """
    for var in (
        "QUIRE_SERVER_AI_METADATA_SERVER_LOOKUP_ENABLED",
        "QUIRE_SERVER_AI_ENABLED",
        "QUIRE_SERVER_AI_AUTH_MODE",
        "QUIRE_SERVER_AI_TOKEN_SECRETS",
        "QUIRE_SERVER_AI_TOKEN_ISSUER",
        "QUIRE_SERVER_AI_TOKEN_AUDIENCE",
        "QUIRE_SERVER_AI_BASE_URL",
        "QUIRE_SERVER_AI_MODEL",
        "QUIRE_SERVER_AI_API_KEY",
    ):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("QUIRE_SERVER_DATABASE_URL", "postgresql+asyncpg://x/y")
    monkeypatch.setenv("QUIRE_SERVER_CWA_BASE_URL", "http://stub")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _create_app():
    # Re-import inside each call so the function captures the freshly-cleared
    # settings cache. Matches the helper in `test_main_ai_auth_validation`.
    from quire_server.main import create_app

    return create_app()


@pytest.mark.parametrize(
    "raw",
    [
        pytest.param(None, id="unset"),
        pytest.param("false", id="disabled"),
        pytest.param("true", id="enabled"),
    ],
)
def test_metadata_fallback_startup_notice(monkeypatch, caplog, raw):
    if raw is not None:
        monkeypatch.setenv("QUIRE_SERVER_AI_METADATA_SERVER_LOOKUP_ENABLED", raw)
    flag = "AI_METADATA_SERVER_LOOKUP_ENABLED"
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always", DeprecationWarning)
        with caplog.at_level(logging.WARNING, logger="quire_server.main"):
            _create_app()

    notices = [warning for warning in caught if flag in str(warning.message)]
    logs = [
        record
        for record in caplog.records
        if record.name == "quire_server.main" and flag in record.getMessage()
    ]
    if raw == "true":
        assert notices and issubclass(notices[0].category, DeprecationWarning)
        assert "2 minor releases" in str(notices[0].message)
        assert "bundle" in str(notices[0].message)
        assert any(record.levelno == logging.WARNING for record in logs)
    else:
        assert not notices
        assert not logs
