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


# ---------------------------------------------------------------------------
# Flag enabled: both channels fire
# ---------------------------------------------------------------------------


def test_metadata_fallback_flag_true_emits_deprecation_warning(monkeypatch):
    """Python tooling channel: `warnings.warn(..., DeprecationWarning)`.

    Use a narrowly-scoped `catch_warnings` + `simplefilter("always", ...)`
    so the warning is captured deterministically regardless of any global
    once-per-line de-duplication accumulated by prior tests in the session.
    `pytest.warns(DeprecationWarning, match=...)` would also work, but
    explicit capture keeps the assertion strict on the message content.
    """
    monkeypatch.setenv("QUIRE_SERVER_AI_METADATA_SERVER_LOOKUP_ENABLED", "true")

    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always", DeprecationWarning)
        _create_app()

    matched = [
        w
        for w in caught
        if issubclass(w.category, DeprecationWarning)
        and "AI_METADATA_SERVER_LOOKUP_ENABLED" in str(w.message)
    ]
    assert matched, (
        "expected a DeprecationWarning mentioning "
        "AI_METADATA_SERVER_LOOKUP_ENABLED, got: "
        f"{[(w.category.__name__, str(w.message)) for w in caught]}"
    )
    message = str(matched[0].message)
    # Removal window must be explicit so operators know how long they have.
    assert "2 minor releases" in message
    # Migration pointer must be present so operators know what to do.
    assert "bundle" in message


def test_metadata_fallback_flag_true_emits_logging_warning(monkeypatch, caplog):
    """Operator channel: `logging.warning` on the module logger.

    `caplog` captures records emitted during the `create_app()` call. We
    scope the level capture to the module logger so unrelated debug noise
    from FastAPI / SQLAlchemy doesn't crowd the assertion.
    """
    monkeypatch.setenv("QUIRE_SERVER_AI_METADATA_SERVER_LOOKUP_ENABLED", "true")

    with caplog.at_level(logging.WARNING, logger="quire_server.main"):
        _create_app()

    matching_records = [
        r
        for r in caplog.records
        if r.name == "quire_server.main"
        and r.levelno == logging.WARNING
        and "AI_METADATA_SERVER_LOOKUP_ENABLED" in r.getMessage()
    ]
    assert matching_records, (
        "expected a WARNING log on quire_server.main mentioning "
        "AI_METADATA_SERVER_LOOKUP_ENABLED; got: "
        f"{[(r.name, r.levelname, r.getMessage()) for r in caplog.records]}"
    )


# ---------------------------------------------------------------------------
# Flag disabled (the new default): no notice about this flag
# ---------------------------------------------------------------------------


def test_metadata_fallback_flag_false_emits_no_notice(monkeypatch, caplog):
    """When the flag is at its default (False), neither channel must emit
    anything *about this flag*.

    We do NOT use `simplefilter("error", DeprecationWarning)` — FastAPI /
    SQLAlchemy / pydantic occasionally emit unrelated DeprecationWarnings
    on import, and failing on those would make the test brittle. Instead
    we scope the assertion to messages that mention the flag name.
    """
    # Default state — flag not set at all.
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always", DeprecationWarning)
        with caplog.at_level(logging.WARNING, logger="quire_server.main"):
            _create_app()

    leaked_warnings = [w for w in caught if "AI_METADATA_SERVER_LOOKUP_ENABLED" in str(w.message)]
    assert not leaked_warnings, (
        "DeprecationWarning about the metadata fallback fired when the flag "
        f"was disabled: {[str(w.message) for w in leaked_warnings]}"
    )
    leaked_logs = [
        r
        for r in caplog.records
        if r.name == "quire_server.main" and "AI_METADATA_SERVER_LOOKUP_ENABLED" in r.getMessage()
    ]
    assert not leaked_logs, (
        "logging.warning about the metadata fallback fired when the flag "
        f"was disabled: {[r.getMessage() for r in leaked_logs]}"
    )


def test_metadata_fallback_flag_explicit_false_emits_no_notice(monkeypatch, caplog):
    """Explicit `false` should behave identically to the unset default."""
    monkeypatch.setenv("QUIRE_SERVER_AI_METADATA_SERVER_LOOKUP_ENABLED", "false")

    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always", DeprecationWarning)
        with caplog.at_level(logging.WARNING, logger="quire_server.main"):
            _create_app()

    leaked_warnings = [w for w in caught if "AI_METADATA_SERVER_LOOKUP_ENABLED" in str(w.message)]
    leaked_logs = [
        r
        for r in caplog.records
        if r.name == "quire_server.main" and "AI_METADATA_SERVER_LOOKUP_ENABLED" in r.getMessage()
    ]
    assert not leaked_warnings and not leaked_logs
