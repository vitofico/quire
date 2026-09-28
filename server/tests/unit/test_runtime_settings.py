"""Unit tests for the AI settings the status page can change (issue #102, phase 3)."""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import UTC, datetime

import pytest

from quire_server.api.admin import editable_view, form_changes, settings_view
from quire_server.api.admin_page import render_page
from quire_server.config import Settings
from quire_server.core import runtime_settings as rs
from quire_server.core.ai.service import TokenBucket
from quire_server.core.runtime_settings import (
    EDITABLE,
    InvalidSetting,
    RuntimeSettings,
    SettingsRejected,
    parse_value,
)

WHEN = datetime(2026, 9, 28, 12, 30, tzinfo=UTC)


@dataclass
class _Row:
    key: str
    value: str
    updated_at: datetime = WHEN
    updated_by: str = "alice"


def _runtime(settings: Settings | None = None, **saved: str) -> RuntimeSettings:
    runtime = RuntimeSettings(settings or Settings())
    runtime._replace([_Row(key, value) for key, value in saved.items()])
    return runtime


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("key", "raw", "expected"),
    [
        ("ai_timeout_s", "300", 300.0),
        ("ai_timeout_s", " 12.5 ", 12.5),
        ("ai_timeout_s", 285, 285.0),
        ("ai_profile_timeout_s", "0.5", 0.5),
        ("ai_rate_per_min", "30", 30),
        ("ai_rate_per_min", 1, 1),
        ("ai_daily_budget", "0", 0),
        ("ai_regen_daily_limit", 10.0, 10),
        ("ai_sources", "Wikipedia, OpenLibrary", "wikipedia,openlibrary"),
        ("ai_sources", "openlibrary,wikipedia,openlibrary", "openlibrary,wikipedia"),
        ("ai_sources", "", ""),
        ("ai_sources", ["openlibrary"], "openlibrary"),
        ("ai_sources", [], ""),
    ],
)
def test_parse_value_accepts_what_forms_and_json_send(key, raw, expected):
    assert parse_value(key, raw) == expected


@pytest.mark.parametrize(
    ("key", "raw", "message"),
    [
        (
            "ai_timeout_s",
            "0",
            "QUIRE_SERVER_AI_TIMEOUT_S must be a number of seconds above 0, at most 3600.",
        ),
        ("ai_timeout_s", "-5", "above 0"),
        ("ai_profile_timeout_s", "3601", "at most 3600"),
        ("ai_timeout_s", "soon", "above 0"),
        ("ai_timeout_s", "nan", "above 0"),
        ("ai_timeout_s", "inf", "above 0"),
        ("ai_timeout_s", True, "above 0"),
        (
            "ai_rate_per_min",
            "0",
            "QUIRE_SERVER_AI_RATE_PER_MIN must be a whole number from 1 to 1000000.",
        ),
        # Too large for the rate limiter to turn into a float.
        ("ai_rate_per_min", 10**309, "from 1 to 1000000"),
        # JSON bodies can carry Infinity; int() of it overflows.
        ("ai_rate_per_min", float("inf"), "from 1 to 1000000"),
        ("ai_daily_budget", "-1", "from 0 to 1000000"),
        ("ai_daily_budget", "1000001", "from 0 to 1000000"),
        ("ai_daily_budget", "3.5", "from 0 to 1000000"),
        ("ai_regen_daily_limit", 3.5, "from 0 to 1000000"),
        ("ai_regen_daily_limit", None, "from 0 to 1000000"),
        (
            "ai_sources",
            "wikipedia,open-library",
            "QUIRE_SERVER_AI_SOURCES knows only wikipedia and openlibrary, not open-library.",
        ),
        ("ai_sources", [1], "must be a list of source names"),
    ],
)
def test_parse_value_refuses_with_a_sentence_naming_the_variable(key, raw, message):
    with pytest.raises(InvalidSetting, match=message.replace(".", r"\.")):
        parse_value(key, raw)


def test_model_and_provider_are_not_editable():
    # The server decides at boot whether AI is configured from these, and the
    # key is a secret; they stay environment-only.
    assert not {"ai_model", "ai_base_url", "ai_api_key"} & set(EDITABLE)
    assert set(EDITABLE) <= set(Settings.model_fields)


# ---------------------------------------------------------------------------
# Precedence
# ---------------------------------------------------------------------------


def test_environment_wins_over_a_saved_value_and_locks_the_setting():
    runtime = _runtime(Settings(ai_timeout_s=200), ai_timeout_s="300")

    assert runtime.locked("ai_timeout_s")
    assert runtime.source("ai_timeout_s") == "set"
    assert runtime.get("ai_timeout_s") == 200
    assert runtime.saved("ai_timeout_s") is None


def test_a_saved_value_wins_over_the_default():
    runtime = _runtime(ai_daily_budget="50", ai_sources="openlibrary")

    assert runtime.source("ai_daily_budget") == "saved"
    assert runtime.get("ai_daily_budget") == 50
    assert runtime.sources == ("openlibrary",)
    assert runtime.source("ai_regen_daily_limit") == "default"
    assert runtime.get("ai_regen_daily_limit") == 3


def test_an_empty_saved_source_list_turns_lookups_off():
    assert _runtime(ai_sources="").sources == ()


def test_rows_that_do_not_parse_or_are_not_editable_are_ignored():
    runtime = _runtime(ai_timeout_s="-1", ai_model="other-model", ai_daily_budget="7")

    assert runtime.source("ai_timeout_s") == "default"
    assert runtime.get("ai_daily_budget") == 7


def test_listeners_run_at_once_and_only_when_values_change():
    runtime = RuntimeSettings(Settings())
    calls: list[int] = []
    runtime.on_change(lambda: calls.append(runtime.get("ai_daily_budget")))

    runtime._replace([_Row("ai_daily_budget", "5")])
    runtime._replace([_Row("ai_daily_budget", "5")])
    runtime._replace([])

    assert calls == [200, 5, 200]


def test_check_collects_every_problem_before_anything_is_written():
    runtime = RuntimeSettings(Settings(ai_rate_per_min=5))

    with pytest.raises(SettingsRejected) as exc:
        runtime.check(
            {"ai_rate_per_min": 10, "ai_timeout_s": "0", "ai_model": "x", "ai_daily_budget": 5}
        )

    assert set(exc.value.errors) == {"ai_rate_per_min", "ai_timeout_s", "ai_model"}
    assert "set in the server's environment" in exc.value.errors["ai_rate_per_min"]
    assert "cannot be changed on the status page" in exc.value.errors["ai_model"]


def test_check_passes_none_through_as_remove():
    assert RuntimeSettings(Settings()).check({"ai_daily_budget": None}) == {"ai_daily_budget": None}


async def test_refresh_keeps_the_values_in_force_when_the_table_cannot_be_read(monkeypatch):
    runtime = _runtime(ai_daily_budget="9")

    @asynccontextmanager
    async def _broken():
        raise RuntimeError("database down")
        yield  # pragma: no cover

    monkeypatch.setattr(rs, "session_scope", _broken)
    await runtime.refresh(force=True)

    assert runtime.get("ai_daily_budget") == 9


def test_token_bucket_takes_a_new_rate_in_place():
    bucket = TokenBucket(rate_per_min=60)

    bucket.set_rate(6)

    assert bucket._capacity == 6
    assert bucket._tokens == 6
    assert bucket._refill_per_s == pytest.approx(0.1)


async def test_raising_the_rate_wakes_a_request_waiting_under_the_old_one():
    bucket = TokenBucket(rate_per_min=1)
    await bucket.acquire()  # the only token; the next one is a minute away
    waiter = asyncio.create_task(bucket.acquire())
    await asyncio.sleep(0.05)
    assert not waiter.done()

    bucket.set_rate(600)  # a token every 0.1 s

    await asyncio.wait_for(waiter, timeout=1.0)


# ---------------------------------------------------------------------------
# The page's form
# ---------------------------------------------------------------------------


def _form(runtime: RuntimeSettings, **overrides: str | list[str]) -> list[tuple[str, str]]:
    """The fields a browser sends for the page as rendered, with ``overrides``."""
    values: dict[str, str | list[str]] = {}
    for row in editable_view(runtime):
        if row["locked"]:
            continue
        if row["name"] == "QUIRE_SERVER_AI_SOURCES":
            values[row["name"]] = ["", *filter(None, str(row["value"]).split(","))]
            values[f"{row['name']}__was"] = str(row["value"])
        else:
            value = row["value"]
            shown = str(int(value) if float(value).is_integer() else value)
            values[row["name"]] = shown
            values[f"{row['name']}__was"] = shown
    values.update(overrides)
    pairs: list[tuple[str, str]] = []
    for name, value in values.items():
        pairs.extend((name, v) for v in (value if isinstance(value, list) else [value]))
    return pairs


def test_an_untouched_form_changes_nothing():
    runtime = _runtime(Settings(ai_sources="openlibrary,wikipedia"), ai_daily_budget="50")

    assert form_changes(runtime, _form(runtime)) == {}


def test_the_form_changes_only_the_fields_that_differ():
    runtime = _runtime()

    changes = form_changes(
        runtime, _form(runtime, QUIRE_SERVER_AI_TIMEOUT_S="300", QUIRE_SERVER_AI_DAILY_BUDGET="x")
    )

    assert changes == {"ai_timeout_s": "300", "ai_daily_budget": "x"}


def test_emptying_a_field_removes_its_saved_value_only_if_there_is_one():
    runtime = _runtime(ai_daily_budget="50")

    changes = form_changes(
        runtime,
        _form(runtime, QUIRE_SERVER_AI_DAILY_BUDGET="", QUIRE_SERVER_AI_REGEN_DAILY_LIMIT=" "),
    )

    assert changes == {"ai_daily_budget": None}


def test_no_source_ticked_saves_lookups_off():
    runtime = _runtime()

    assert form_changes(runtime, _form(runtime, QUIRE_SERVER_AI_SOURCES=[""])) == {"ai_sources": ""}


def test_a_use_default_button_resets_that_setting_alone():
    runtime = _runtime(ai_daily_budget="50")
    pairs = [("reset", "QUIRE_SERVER_AI_DAILY_BUDGET"), ("QUIRE_SERVER_AI_TIMEOUT_S", "999")]

    assert form_changes(runtime, pairs) == {"ai_daily_budget": None}


def test_a_page_loaded_before_another_admins_save_does_not_undo_it():
    shown = _runtime()  # Alice loads the page: budget 200, sources both
    pairs = _form(shown, QUIRE_SERVER_AI_TIMEOUT_S="300")  # she edits the timeout only
    # Meanwhile Bob saves a budget of 100 and turns lookups off.
    now = _runtime(ai_daily_budget="100", ai_sources="")

    assert form_changes(now, pairs) == {"ai_timeout_s": "300"}


def test_a_locked_field_sent_anyway_is_left_alone():
    runtime = _runtime(Settings(ai_timeout_s=200))

    assert form_changes(runtime, [("QUIRE_SERVER_AI_TIMEOUT_S", "5")]) == {}


# ---------------------------------------------------------------------------
# Status document and page
# ---------------------------------------------------------------------------


def test_views_show_where_each_value_comes_from():
    runtime = _runtime(Settings(ai_rate_per_min=5), ai_daily_budget="50", ai_rate_per_min="9")

    rows = {row["name"]: row for row in editable_view(runtime)}
    assert rows["QUIRE_SERVER_AI_DAILY_BUDGET"] == {
        "name": "QUIRE_SERVER_AI_DAILY_BUDGET",
        "value": 50,
        "default": 200,
        "source": "saved",
        "locked": False,
        "updated_at": "2026-09-28T12:30:00+00:00",
        "updated_by": "alice",
    }
    assert rows["QUIRE_SERVER_AI_RATE_PER_MIN"]["locked"] is True
    assert rows["QUIRE_SERVER_AI_RATE_PER_MIN"]["value"] == 5
    assert rows["QUIRE_SERVER_AI_RATE_PER_MIN"]["updated_by"] is None

    settings_rows = {row["name"]: row for row in settings_view(Settings(), runtime)}
    assert settings_rows["QUIRE_SERVER_AI_DAILY_BUDGET"]["source"] == "saved"
    assert settings_rows["QUIRE_SERVER_AI_DAILY_BUDGET"]["value"] == 50


def _status(runtime: RuntimeSettings) -> dict:
    return {
        "version": "abc123",
        "modes": ["ai"],
        "warnings": [],
        "ai": None,
        "migrations": {"applied": ["ai_008"], "required": ["ai_008"], "missing": []},
        "editable": editable_view(runtime),
        "settings": settings_view(Settings(), runtime),
    }


def test_page_renders_the_settings_form():
    runtime = RuntimeSettings(Settings(ai_rate_per_min=5))
    runtime._replace([_Row("ai_daily_budget", "50", updated_by='<b>"x"</b>')])

    page = render_page(_status(runtime))

    assert 'action="/quire-admin/settings"' in page
    assert 'name="QUIRE_SERVER_AI_TIMEOUT_S" value="120" step="any" min="0">' in page
    assert 'name="QUIRE_SERVER_AI_RATE_PER_MIN" value="5" step="1" min="1" disabled>' in page
    assert "Set in the server's environment, which takes precedence." in page
    assert 'form="reset-settings" name="reset" value="QUIRE_SERVER_AI_DAILY_BUDGET"' in page
    assert "Saved here by &lt;b&gt;&quot;x&quot;&lt;/b&gt;, 2026-09-28 12:30 UTC." in page
    assert '<input type="hidden" name="QUIRE_SERVER_AI_SOURCES" value="">' in page
    assert (
        '<input type="hidden" name="QUIRE_SERVER_AI_SOURCES__was" '
        'value="wikipedia,openlibrary">' in page
    )
    assert '<input type="hidden" name="QUIRE_SERVER_AI_DAILY_BUDGET__was" value="50">' in page
    assert "QUIRE_SERVER_AI_RATE_PER_MIN__was" not in page
    assert 'value="wikipedia" checked>' in page
    assert "<b>" not in page


def test_page_reports_the_outcome_of_a_save():
    runtime = RuntimeSettings(Settings())

    saved = render_page(
        _status(runtime), settings_notice={"ok": True, "changed": ["QUIRE_SERVER_AI_TIMEOUT_S"]}
    )
    nothing = render_page(_status(runtime), settings_notice={"ok": True, "changed": []})
    refused = render_page(
        _status(runtime), settings_notice={"ok": False, "errors": ["X must be <1>."]}
    )

    assert "Saved. The next AI request uses the new values." in saved
    assert "Changed: <code>QUIRE_SERVER_AI_TIMEOUT_S</code>." in saved
    assert "Nothing changed." in nothing
    assert "Nothing was saved." in refused
    assert "X must be &lt;1&gt;." in refused


def test_page_has_no_settings_form_when_ai_is_off():
    status = _status(RuntimeSettings(Settings()))
    status["editable"] = None

    assert "/quire-admin/settings" not in render_page(status)
