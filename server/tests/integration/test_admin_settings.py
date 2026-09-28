"""Integration tests for changing AI settings on the status page (issue #102, phase 3).

Auth runs through the real calibre-web login against the mock calibre-web
(``admin_client`` in the conftest). ``GET /ai/v1/config`` goes through the
suite's stubbed AI principal, which accepts the same Basic header. Tests set
the AI variables themselves, so they run in every cell of the CI mode matrix.
"""

from __future__ import annotations

from urllib.parse import urlencode

import pytest
from sqlalchemy import text

SETTINGS = "/quire-admin/v1/settings"
STATUS = "/quire-admin/v1/status"
PAGE = "/quire-admin"
PAGE_SETTINGS = "/quire-admin/settings"
CONFIG = "/ai/v1/config"

AI_ENV = {
    "admin_users": "alice",
    "ai_enabled": "true",
    "ai_base_url": "http://fake/v1",
    "ai_model": "test-model",
}
SAME_ORIGIN = {"Sec-Fetch-Site": "same-origin"}
FORM = {"Content-Type": "application/x-www-form-urlencoded", **SAME_ORIGIN}


@pytest.fixture(autouse=True)
async def _no_saved_settings(engine):
    """Start and end every test with no saved values.

    The shared truncate runs before a test only, so a value saved here would
    otherwise reach later modules' ``GET /ai/v1/config``.
    """
    async with engine.begin() as conn:
        await conn.execute(text("DELETE FROM server_settings"))
    yield
    async with engine.begin() as conn:
        await conn.execute(text("DELETE FROM server_settings"))


async def _saved_rows(engine) -> dict[str, tuple[str, str]]:
    async with engine.connect() as conn:
        rows = await conn.execute(text("SELECT key, value, updated_by FROM server_settings"))
        return {key: (value, by) for key, value, by in rows}


def _editable(body: dict) -> dict[str, dict]:
    return {row["name"]: row for row in body["editable"]}


# ---------------------------------------------------------------------------
# JSON
# ---------------------------------------------------------------------------


async def test_a_saved_value_applies_to_the_next_ai_request(admin_client, app, alice, engine):
    async with admin_client(**AI_ENV) as client:
        r = await client.put(
            SETTINGS,
            headers=alice,
            json={
                "QUIRE_SERVER_AI_TIMEOUT_S": 300,
                "QUIRE_SERVER_AI_DAILY_BUDGET": "50",
                "QUIRE_SERVER_AI_RATE_PER_MIN": 30,
                "QUIRE_SERVER_AI_SOURCES": ["openlibrary"],
            },
        )
        config = await client.get(CONFIG, headers=alice)

    assert r.status_code == 200, r.text
    assert r.headers["cache-control"] == "no-store"
    rows = _editable(r.json())
    assert rows["QUIRE_SERVER_AI_TIMEOUT_S"]["value"] == 300
    assert rows["QUIRE_SERVER_AI_TIMEOUT_S"]["source"] == "saved"
    assert rows["QUIRE_SERVER_AI_TIMEOUT_S"]["updated_by"] == "alice"
    assert rows["QUIRE_SERVER_AI_REGEN_DAILY_LIMIT"]["source"] == "default"

    body = config.json()
    assert body["generation_timeout_s"] == 300
    assert body["daily_budget"] == 50
    assert body["sources_enabled"] == ["openlibrary"]

    orch = app.state.ai_orchestrator
    assert orch._ai_timeout_s == 300
    assert orch._daily_budget == 50
    assert orch.sources_enabled == ("openlibrary",)
    assert orch._bucket._capacity == 30

    assert await _saved_rows(engine) == {
        "ai_timeout_s": ("300.0", "alice"),
        "ai_daily_budget": ("50", "alice"),
        "ai_rate_per_min": ("30", "alice"),
        "ai_sources": ("openlibrary", "alice"),
    }


async def test_a_save_holds_even_if_reading_it_back_fails(admin_client, app, alice, monkeypatch):
    async with admin_client(**AI_ENV) as client:

        async def _unreadable(*, force: bool = False) -> None:
            return None  # what refresh does when the table cannot be read

        monkeypatch.setattr(app.state.runtime_settings, "refresh", _unreadable)
        r = await client.put(SETTINGS, headers=alice, json={"QUIRE_SERVER_AI_DAILY_BUDGET": 5})
        config = await client.get(CONFIG, headers=alice)

    assert r.status_code == 200
    assert _editable(r.json())["QUIRE_SERVER_AI_DAILY_BUDGET"]["updated_by"] == "alice"
    assert config.json()["daily_budget"] == 5
    assert app.state.ai_orchestrator._daily_budget == 5


async def test_another_server_process_reads_the_saved_values(admin_client, app, alice):
    async with admin_client(**AI_ENV) as client:
        r = await client.put(SETTINGS, headers=alice, json={"QUIRE_SERVER_AI_DAILY_BUDGET": 7})
    assert r.status_code == 200

    # A second app is a second process: it has read nothing yet.
    async with admin_client(**AI_ENV) as client:
        assert app.state.ai_orchestrator._daily_budget == 200
        config = await client.get(CONFIG, headers=alice)

    assert config.json()["daily_budget"] == 7
    assert app.state.ai_orchestrator._daily_budget == 7


async def test_a_value_from_the_environment_wins_and_cannot_be_changed(admin_client, alice, engine):
    async with engine.begin() as conn:
        await conn.execute(
            text(
                "INSERT INTO server_settings (key, value, updated_by) "
                "VALUES ('ai_timeout_s', '999', 'someone')"
            )
        )

    async with admin_client(**AI_ENV, ai_timeout_s="200") as client:
        r = await client.put(
            SETTINGS,
            headers=alice,
            json={"QUIRE_SERVER_AI_TIMEOUT_S": 300, "QUIRE_SERVER_AI_DAILY_BUDGET": 5},
        )
        status = await client.get(STATUS, headers=alice)
        config = await client.get(CONFIG, headers=alice)

    assert r.status_code == 422
    errors = r.json()["detail"]["errors"]
    assert list(errors) == ["QUIRE_SERVER_AI_TIMEOUT_S"]
    assert "set in the server's environment" in errors["QUIRE_SERVER_AI_TIMEOUT_S"]
    row = _editable(status.json())["QUIRE_SERVER_AI_TIMEOUT_S"]
    assert (row["value"], row["source"], row["locked"]) == (200, "set", True)
    assert config.json()["generation_timeout_s"] == 200
    # All or nothing: the valid budget in the same request was not saved.
    assert "ai_daily_budget" not in await _saved_rows(engine)


async def test_invalid_and_unknown_settings_are_refused(admin_client, alice, engine):
    async with admin_client(**AI_ENV) as client:
        invalid = await client.put(
            SETTINGS,
            headers=alice,
            json={"QUIRE_SERVER_AI_TIMEOUT_S": 0, "QUIRE_SERVER_AI_DAILY_BUDGET": 5},
        )
        unknown = await client.put(SETTINGS, headers=alice, json={"QUIRE_SERVER_AI_MODEL": "x"})

    assert invalid.status_code == 422
    errors = invalid.json()["detail"]["errors"]
    assert list(errors) == ["QUIRE_SERVER_AI_TIMEOUT_S"]
    assert errors["QUIRE_SERVER_AI_TIMEOUT_S"].endswith("above 0, at most 3600.")
    assert unknown.status_code == 422
    assert "QUIRE_SERVER_AI_MODEL" in unknown.json()["detail"]["errors"]
    assert await _saved_rows(engine) == {}


async def test_null_brings_back_the_default(admin_client, alice, engine):
    async with admin_client(**AI_ENV) as client:
        await client.put(SETTINGS, headers=alice, json={"QUIRE_SERVER_AI_DAILY_BUDGET": 5})
        r = await client.put(SETTINGS, headers=alice, json={"QUIRE_SERVER_AI_DAILY_BUDGET": None})
        config = await client.get(CONFIG, headers=alice)

    assert _editable(r.json())["QUIRE_SERVER_AI_DAILY_BUDGET"]["source"] == "default"
    assert config.json()["daily_budget"] == 200
    assert await _saved_rows(engine) == {}


@pytest.mark.parametrize(
    ("who", "headers", "expected"),
    [
        ("bob", {}, 403),
        ("alice", {"Sec-Fetch-Site": "cross-site"}, 403),
        ("alice", {"Origin": "https://evil.example"}, 403),
    ],
)
async def test_only_an_admin_on_this_site_can_save(
    admin_client, basic_header, engine, who, headers, expected
):
    auth = {"Authorization": basic_header(who, f"{who}pass")}
    body = {"QUIRE_SERVER_AI_DAILY_BUDGET": 5}
    form = urlencode({"QUIRE_SERVER_AI_DAILY_BUDGET": "5"})
    async with admin_client(**AI_ENV) as client:
        api = await client.put(SETTINGS, headers={**auth, **headers}, json=body)
        page = await client.post(
            PAGE_SETTINGS,
            headers={**auth, **headers, "Content-Type": "application/x-www-form-urlencoded"},
            content=form,
        )

    assert (api.status_code, page.status_code) == (expected, expected)
    assert await _saved_rows(engine) == {}


async def test_with_ai_off_there_is_nothing_to_change(admin_client, alice):
    async with admin_client(admin_users="alice", ai_enabled="false") as client:
        api = await client.put(SETTINGS, headers=alice, json={"QUIRE_SERVER_AI_DAILY_BUDGET": 5})
        form = await client.post(PAGE_SETTINGS, headers={**alice, **FORM}, content="")
        status = await client.get(STATUS, headers=alice)
        page = await client.get(PAGE, headers=alice)

    assert api.status_code == 404
    assert form.status_code == 404
    assert status.json()["editable"] is None
    assert PAGE_SETTINGS not in page.text


# ---------------------------------------------------------------------------
# The page's form
# ---------------------------------------------------------------------------


def _page_form(**fields: str | list[str]) -> str:
    """The form as the page renders it with defaults in force, plus ``fields``."""
    shown = {
        "QUIRE_SERVER_AI_TIMEOUT_S": "120",
        "QUIRE_SERVER_AI_PROFILE_TIMEOUT_S": "90",
        "QUIRE_SERVER_AI_SOURCES": "wikipedia,openlibrary",
        "QUIRE_SERVER_AI_RATE_PER_MIN": "10",
        "QUIRE_SERVER_AI_DAILY_BUDGET": "200",
        "QUIRE_SERVER_AI_REGEN_DAILY_LIMIT": "3",
    }
    values: dict[str, str | list[str]] = {
        **shown,
        "QUIRE_SERVER_AI_SOURCES": ["", "wikipedia", "openlibrary"],
        **{f"{name}__was": value for name, value in shown.items()},
    }
    values.update(fields)
    return urlencode(values, doseq=True)


async def test_page_shows_the_settings_form(admin_client, alice):
    async with admin_client(**AI_ENV, ai_rate_per_min="5") as client:
        r = await client.get(PAGE, headers=alice)

    assert r.status_code == 200
    assert '<form method="post" action="/quire-admin/settings">' in r.text
    assert 'name="QUIRE_SERVER_AI_TIMEOUT_S" value="120"' in r.text
    assert 'name="QUIRE_SERVER_AI_RATE_PER_MIN" value="5" step="1" min="1" disabled>' in r.text


async def test_page_form_saves_only_what_changed(admin_client, alice, engine):
    async with admin_client(**AI_ENV) as client:
        r = await client.post(
            PAGE_SETTINGS,
            headers={**alice, **FORM},
            content=_page_form(QUIRE_SERVER_AI_DAILY_BUDGET="50"),
        )
        # Saving again from the page as re-rendered, which now shows 50.
        again = await client.post(
            PAGE_SETTINGS,
            headers={**alice, **FORM},
            content=_page_form(
                QUIRE_SERVER_AI_DAILY_BUDGET="50", QUIRE_SERVER_AI_DAILY_BUDGET__was="50"
            ),
        )

    assert r.status_code == 200
    assert "Saved. The next AI request uses the new values." in r.text
    assert "Changed: <code>QUIRE_SERVER_AI_DAILY_BUDGET</code>." in r.text
    assert "Saved here by alice" in r.text
    assert "Nothing changed." in again.text
    assert await _saved_rows(engine) == {"ai_daily_budget": ("50", "alice")}


async def test_page_form_turns_lookups_off_and_back(admin_client, alice, engine):
    async with admin_client(**AI_ENV) as client:
        off = await client.post(
            PAGE_SETTINGS,
            headers={**alice, **FORM},
            content=_page_form(QUIRE_SERVER_AI_SOURCES=[""]),
        )
        config_off = await client.get(CONFIG, headers=alice)
        reset = await client.post(
            PAGE_SETTINGS,
            headers={**alice, **FORM},
            content=urlencode({"reset": "QUIRE_SERVER_AI_SOURCES"}),
        )
        config_reset = await client.get(CONFIG, headers=alice)

    assert off.status_code == 200
    assert config_off.json()["sources_enabled"] == []
    assert reset.status_code == 200
    assert config_reset.json()["sources_enabled"] == ["wikipedia", "openlibrary"]
    assert await _saved_rows(engine) == {}


async def test_page_form_explains_a_refused_value(admin_client, alice, engine):
    async with admin_client(**AI_ENV) as client:
        r = await client.post(
            PAGE_SETTINGS,
            headers={**alice, **FORM},
            content=_page_form(QUIRE_SERVER_AI_TIMEOUT_S="0", QUIRE_SERVER_AI_DAILY_BUDGET="5"),
        )

    assert r.status_code == 422
    assert "Nothing was saved." in r.text
    assert "QUIRE_SERVER_AI_TIMEOUT_S must be a number of seconds above 0, at most 3600." in r.text
    assert await _saved_rows(engine) == {}
