"""Unit tests for the admin status helpers (issue #102, phase 2)."""

from __future__ import annotations

import json
import re

from quire_server.api.admin import MASK, SECRET_SETTINGS, settings_view
from quire_server.api.admin_page import render_page
from quire_server.config import Settings, admin_user_ids


def _rows(settings: Settings) -> dict[str, dict]:
    return {row["name"]: row for row in settings_view(settings)}


def test_admin_user_ids_lowercases_and_drops_blanks():
    assert admin_user_ids(Settings(admin_users=" Alice, ,bob ")) == {"alice", "bob"}
    assert admin_user_ids(Settings(admin_users="")) == frozenset()


def test_settings_view_masks_secrets_and_url_passwords():
    settings = Settings(
        ai_api_key="sk-live-123",
        ai_token_secrets={"k1": "s" * 32},
        database_url="postgresql+asyncpg://quire:hunter2@db:5432/quire",
        ai_base_url="http://user:p%40ss@ollama:11434/v1",
    )
    rows = _rows(settings)

    assert rows["QUIRE_SERVER_AI_API_KEY"]["value"] == MASK
    assert rows["QUIRE_SERVER_AI_TOKEN_SECRETS"]["value"] == MASK
    assert (
        rows["QUIRE_SERVER_DATABASE_URL"]["value"] == "postgresql+asyncpg://quire:***@db:5432/quire"
    )
    assert rows["QUIRE_SERVER_AI_BASE_URL"]["value"] == "http://user:***@ollama:11434/v1"
    dumped = json.dumps(settings_view(settings))
    for secret in ("sk-live-123", "s" * 32, "hunter2", "p%40ss"):
        assert secret not in dumped


def test_settings_view_masks_a_password_containing_an_at_sign():
    rows = _rows(Settings(database_url="postgresql+asyncpg://quire:hun@ter2@db/quire"))

    assert rows["QUIRE_SERVER_DATABASE_URL"]["value"] == "postgresql+asyncpg://quire:***@db/quire"


def test_settings_view_leaves_unset_secrets_null_and_plain_urls_alone():
    rows = _rows(Settings(ai_api_key=None, cwa_base_url="http://calibre-web:8083"))

    assert rows["QUIRE_SERVER_AI_API_KEY"]["value"] is None
    assert rows["QUIRE_SERVER_AI_TOKEN_SECRETS"]["value"] is None
    assert rows["QUIRE_SERVER_CWA_BASE_URL"]["value"] == "http://calibre-web:8083"


def test_settings_view_says_which_values_were_set():
    rows = _rows(Settings(ai_model="llama3.2"))

    assert rows["QUIRE_SERVER_AI_MODEL"] == {
        "name": "QUIRE_SERVER_AI_MODEL",
        "value": "llama3.2",
        "source": "set",
    }
    assert rows["QUIRE_SERVER_AI_TIMEOUT_S"]["source"] == "default"


def test_settings_view_lists_every_setting():
    names = [row["name"] for row in settings_view(Settings())]

    assert names == [f"QUIRE_SERVER_{name.upper()}" for name in Settings.model_fields]


def test_every_secret_looking_setting_is_masked():
    """A new setting named like a secret must be added to SECRET_SETTINGS."""
    pattern = re.compile(r"key|secret|password")
    looks_secret = {name for name in Settings.model_fields if pattern.search(name)}

    assert looks_secret <= SECRET_SETTINGS, f"unmasked: {sorted(looks_secret - SECRET_SETTINGS)}"


# ---------------------------------------------------------------------------
# HTML page
# ---------------------------------------------------------------------------


def _status(**overrides) -> dict:
    status = {
        "version": "abc123",
        "modes": ["progress", "ai"],
        "warnings": [],
        "ai": {
            "configured": True,
            "health": {
                "provider_reachable": None,
                "provider_last_checked_at": None,
                "model_id": None,
                "last_failure_at": None,
                "last_failure_class": None,
                "retrieval_sources": [
                    {"name": "wikipedia", "reachable": None, "last_checked_at": None}
                ],
            },
        },
        "migrations": {"applied": ["ai_007"], "required": ["ai_007"], "missing": []},
        "settings": settings_view(Settings(ai_model="llama3.2", ai_base_url="http://o/v1")),
    }
    status.update(overrides)
    return status


def test_render_page_escapes_every_value():
    status = _status(warnings=["<script>alert(1)</script>"])
    status["settings"].append({"name": "X", "value": '"><img src=x>', "source": "set"})

    page = render_page(status)

    assert "<script>" not in page
    assert "<img" not in page
    assert "&lt;script&gt;alert(1)&lt;/script&gt;" in page


def test_render_page_says_when_the_provider_was_never_checked():
    page = render_page(_status())

    assert "Not checked since the server started." in page
    assert 'action="/quire-admin/probe"' in page
    assert "llama3.2" in page
    assert "waits up to 120 seconds" in page


def test_render_page_shows_a_successful_probe():
    probe = {"ok": True, "model": "llama3.2", "elapsed_ms": 812, "error": None}

    page = render_page(_status(), probe=probe)

    assert "The AI provider answered in 0.8 s." in page


def test_render_page_shows_a_failed_probe_with_its_hint():
    probe = {
        "ok": False,
        "model": "llama3.2",
        "elapsed_ms": 10004,
        "error": {
            "code": "provider_rejected",
            "message": "The AI provider does not know the configured model.",
            "hint": "Check QUIRE_SERVER_AI_MODEL; for a local Ollama, run: ollama pull llama3.2",
            "provider_status": 404,
        },
    }

    page = render_page(_status(), probe=probe)

    assert "The AI provider does not know the configured model." in page
    assert "ollama pull llama3.2" in page
    assert "Code provider_rejected, provider answered HTTP 404, after 10.0 s." in page


def test_render_page_flags_missing_migrations_and_disabled_ai():
    migrations = {"applied": ["0004"], "required": ["ai_007"], "missing": ["ai_007"]}

    page = render_page(_status(ai=None, migrations=migrations))

    assert "Migrations are behind: ai_007 not applied." in page
    assert "AI insights are switched off" in page
