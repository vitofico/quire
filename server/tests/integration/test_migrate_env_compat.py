"""Tests for migrate.py env-prefix back-compat (§4.3 / §6.3).

Verifies that `scripts/migrate.main()` reads either
QUIRE_SERVER_PROGRESS_ENABLED / QUIRE_SERVER_AI_ENABLED (preferred) or the
legacy OPDS_SYNC_* names, and that when both are set the new one wins.

We assert behavior by patching `scripts.migrate.run_migrations` to capture
the kwargs it receives — that's the contract `main()` resolves before
delegating to Alembic. This keeps the test fast and DB-free.
"""

from __future__ import annotations

import pytest

from opds_sync._env_compat import reset_log_state_for_testing


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch: pytest.MonkeyPatch, tmp_path):
    for var in (
        "QUIRE_SERVER_PROGRESS_ENABLED",
        "OPDS_SYNC_PROGRESS_ENABLED",
        "QUIRE_SERVER_AI_ENABLED",
        "OPDS_SYNC_AI_ENABLED",
    ):
        monkeypatch.delenv(var, raising=False)
    reset_log_state_for_testing()
    # Ensure migrate.main can find an alembic.ini; point at the repo's.
    monkeypatch.chdir(tmp_path)
    alembic_ini = tmp_path / "alembic.ini"
    alembic_ini.write_text("[alembic]\nscript_location = .\n")
    yield
    reset_log_state_for_testing()


def _capture_run_migrations(monkeypatch: pytest.MonkeyPatch):
    captured: dict[str, object] = {}

    def fake_run_migrations(cfg, *, progress_enabled, ai_enabled):
        captured["progress_enabled"] = progress_enabled
        captured["ai_enabled"] = ai_enabled

    monkeypatch.setattr("scripts.migrate.run_migrations", fake_run_migrations)
    return captured


def test_migrate_picks_up_legacy_progress(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("OPDS_SYNC_PROGRESS_ENABLED", "true")
    monkeypatch.setenv("OPDS_SYNC_AI_ENABLED", "false")
    captured = _capture_run_migrations(monkeypatch)
    from scripts.migrate import main

    assert main() == 0
    assert captured["progress_enabled"] is True


def test_migrate_picks_up_new_progress(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("QUIRE_SERVER_PROGRESS_ENABLED", "true")
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")
    captured = _capture_run_migrations(monkeypatch)
    from scripts.migrate import main

    assert main() == 0
    assert captured["progress_enabled"] is True
    assert captured["ai_enabled"] is False


def test_migrate_picks_up_legacy_ai(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("OPDS_SYNC_AI_ENABLED", "true")
    monkeypatch.setenv("OPDS_SYNC_PROGRESS_ENABLED", "false")
    captured = _capture_run_migrations(monkeypatch)
    from scripts.migrate import main

    assert main() == 0
    assert captured["ai_enabled"] is True


def test_migrate_picks_up_new_ai(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "true")
    monkeypatch.setenv("QUIRE_SERVER_PROGRESS_ENABLED", "false")
    captured = _capture_run_migrations(monkeypatch)
    from scripts.migrate import main

    assert main() == 0
    assert captured["ai_enabled"] is True


def test_migrate_both_prefixes_new_wins(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("OPDS_SYNC_AI_ENABLED", "false")
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "true")
    monkeypatch.setenv("QUIRE_SERVER_PROGRESS_ENABLED", "true")
    captured = _capture_run_migrations(monkeypatch)
    from scripts.migrate import main

    assert main() == 0
    assert captured["ai_enabled"] is True
