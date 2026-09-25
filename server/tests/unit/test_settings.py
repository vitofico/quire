"""Tests for quire_server.config.Settings defaults.

PR-A flips ai_enabled default to True and adds progress_enabled +
max_request_bytes.
"""

from __future__ import annotations

import pytest

from quire_server.config import (
    COMPOSE_ONLY_ENV_VARS,
    Settings,
    config_warnings,
    get_settings,
    parse_ai_sources,
    unknown_env_vars,
)


@pytest.fixture(autouse=True)
def _clear_settings_cache(monkeypatch):
    """Ensure each test sees a fresh settings instance (no env-bleed)."""
    for var in (
        "QUIRE_SERVER_AI_ENABLED",
        "QUIRE_SERVER_PROGRESS_ENABLED",
        "QUIRE_SERVER_MAX_REQUEST_BYTES",
        "QUIRE_SERVER_AI_AUTH_MODE",
        "QUIRE_SERVER_AI_TOKEN_SECRETS",
        "QUIRE_SERVER_AI_TOKEN_ISSUER",
        "QUIRE_SERVER_AI_TOKEN_AUDIENCE",
    ):
        monkeypatch.delenv(var, raising=False)
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def test_defaults_match_pr_a_contract(monkeypatch):
    s = Settings()
    assert s.ai_enabled is True
    assert s.progress_enabled is True
    assert s.max_request_bytes == 1_048_576


def test_progress_enabled_env_override(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_PROGRESS_ENABLED", "false")
    s = Settings()
    assert s.progress_enabled is False


def test_ai_enabled_env_override(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")
    s = Settings()
    assert s.ai_enabled is False


def test_max_request_bytes_env_override(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_MAX_REQUEST_BYTES", "2048")
    s = Settings()
    assert s.max_request_bytes == 2048


# --- PR-B: AI auth abstraction --------------------------------------------


def test_ai_auth_mode_defaults_to_basic(monkeypatch):
    s = Settings()
    assert s.ai_auth_mode == "basic"
    assert s.ai_token_secrets is None
    assert s.ai_token_issuer is None
    assert s.ai_token_audience is None


def test_ai_auth_mode_env_override(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_AUTH_MODE", "token")
    s = Settings()
    assert s.ai_auth_mode == "token"


def test_ai_token_secrets_parses_json_object(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_SECRETS", '{"k1": "a" , "k2": "b"}')
    s = Settings()
    assert s.ai_token_secrets == {"k1": "a", "k2": "b"}


def test_ai_token_issuer_audience_env_override(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_ISSUER", "quire-cloud")
    monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_AUDIENCE", "quire-server")
    s = Settings()
    assert s.ai_token_issuer == "quire-cloud"
    assert s.ai_token_audience == "quire-server"


# --- Issue #104: unknown QUIRE_SERVER_* variable detection -----------------


def test_unknown_env_vars_flags_only_unread_prefixed_names():
    env = {
        "QUIRE_SERVER_AI_MODEL": "m",  # real field
        "QUIRE_SERVER_PORT": "8000",  # compose-only, never read by the server
        "QUIRE_SERVER_AI_MODLE": "typo",
        "QUIRE_SERVER_AI_PROVIDER": "ollama",  # invented name
        "POSTGRES_PASSWORD": "x",  # other prefix, not ours to judge
    }
    assert unknown_env_vars(env) == ["QUIRE_SERVER_AI_MODLE", "QUIRE_SERVER_AI_PROVIDER"]


def test_unknown_env_vars_matches_case_insensitively_like_pydantic_settings():
    assert unknown_env_vars({"quire_server_ai_model": "m"}) == []
    assert unknown_env_vars({"quire_server_ai_modle": "x"}) == ["quire_server_ai_modle"]


def test_unknown_env_vars_empty_when_nothing_is_prefixed():
    assert unknown_env_vars({"PATH": "/bin", "HOME": "/root"}) == []


def test_compose_only_names_are_not_settings_fields():
    for name in COMPOSE_ONLY_ENV_VARS:
        assert name.startswith("QUIRE_SERVER_")
        assert name.removeprefix("QUIRE_SERVER_").lower() not in Settings.model_fields


def test_unknown_env_vars_reads_dotenv_file(monkeypatch, tmp_path):
    env_file = tmp_path / ".env"
    env_file.write_text(
        "QUIRE_SERVER_AI_MODLE=typo\n"
        "QUIRE_SERVER_PORT=8000\n"
        "QUIRE_SERVER_AI_MODEL=gpt-oss:120b-cloud\n"
        "POSTGRES_PASSWORD=x\n"
        "# QUIRE_SERVER_COMMENTED=out\n"
    )
    monkeypatch.setitem(Settings.model_config, "env_file", str(env_file))
    assert unknown_env_vars(environ={}) == ["QUIRE_SERVER_AI_MODLE"]


def test_unknown_env_vars_tolerates_missing_dotenv_file(monkeypatch, tmp_path):
    monkeypatch.setitem(Settings.model_config, "env_file", str(tmp_path / "absent.env"))
    assert unknown_env_vars(environ={"QUIRE_SERVER_AI_MODLE": "typo"}) == ["QUIRE_SERVER_AI_MODLE"]


# --- Issue #104: blank AI provider strings mean unset -----------------------


def test_blank_ai_provider_strings_are_unset(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_BASE_URL", "")
    monkeypatch.setenv("QUIRE_SERVER_AI_MODEL", "   ")
    monkeypatch.setenv("QUIRE_SERVER_AI_API_KEY", "")
    s = Settings()
    assert s.ai_base_url is None
    assert s.ai_model is None
    assert s.ai_api_key is None


def test_non_blank_ai_provider_strings_are_kept(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_BASE_URL", "https://ollama.com/v1")
    monkeypatch.setenv("QUIRE_SERVER_AI_MODEL", "gpt-oss:120b-cloud")
    monkeypatch.setenv("QUIRE_SERVER_AI_API_KEY", "k")
    s = Settings()
    assert (s.ai_base_url, s.ai_model, s.ai_api_key) == (
        "https://ollama.com/v1",
        "gpt-oss:120b-cloud",
        "k",
    )


def test_config_warnings_ai_enabled_without_provider():
    s = Settings(ai_enabled=True, ai_base_url=None, ai_model=None)
    assert config_warnings(s) == [
        "AI is enabled but QUIRE_SERVER_AI_BASE_URL and QUIRE_SERVER_AI_MODEL are not set; "
        "the app will report AI as unconfigured. Set them or set QUIRE_SERVER_AI_ENABLED=false"
    ]


def test_config_warnings_ai_enabled_missing_model_only():
    s = Settings(ai_enabled=True, ai_base_url="http://ollama:11434/v1", ai_model=None)
    assert config_warnings(s) == [
        "AI is enabled but QUIRE_SERVER_AI_MODEL is not set; the app will report AI as "
        "unconfigured. Set it or set QUIRE_SERVER_AI_ENABLED=false"
    ]


def test_config_warnings_base_url_without_v1_suffix():
    s = Settings(ai_enabled=True, ai_base_url="http://ollama:11434", ai_model="m")
    assert config_warnings(s) == [
        "QUIRE_SERVER_AI_BASE_URL does not end with /v1; OpenAI-compatible providers "
        "such as Ollama expect for example http://ollama:11434/v1"
    ]


def test_config_warnings_silent_when_ai_configured():
    s = Settings(
        ai_enabled=True, ai_base_url="https://ollama.com/v1/", ai_model="gpt-oss:120b-cloud"
    )
    assert config_warnings(s) == []


def test_config_warnings_silent_when_ai_disabled():
    assert config_warnings(Settings(ai_enabled=False, progress_enabled=True)) == []


def test_config_warnings_both_modes_off():
    s = Settings(ai_enabled=False, progress_enabled=False)
    assert config_warnings(s) == [
        "QUIRE_SERVER_PROGRESS_ENABLED and QUIRE_SERVER_AI_ENABLED are both false; "
        "only /health and /readyz are served"
    ]


# --- AI retrieval source names ----------------------------------------------

UNKNOWN_SOURCE_WARNING = (
    "QUIRE_SERVER_AI_SOURCES contains a name Quire does not recognise, so that name is "
    "ignored; the known names are wikipedia and openlibrary"
)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("wikipedia,openlibrary", ("wikipedia", "openlibrary")),
        ("Wikipedia, OpenLibrary", ("wikipedia", "openlibrary")),
        ("  OPENLIBRARY ,wikipedia  ", ("openlibrary", "wikipedia")),
        ("wikipedia,open_library,Open-Library", ("wikipedia",)),
        ("openlibrary,Wikipedia,OpenLibrary,wikipedia", ("openlibrary", "wikipedia")),
        ("", ()),
        (" , ,", ()),
        (None, ()),
    ],
)
def test_parse_ai_sources(raw, expected):
    assert parse_ai_sources(raw) == expected


def _ai_configured(**overrides) -> Settings:
    return Settings(
        ai_enabled=True, ai_base_url="http://ollama:11434/v1", ai_model="m", **overrides
    )


def test_config_warnings_unrecognised_ai_source_is_named_by_variable_only():
    assert config_warnings(_ai_configured(ai_sources="Wikipedia,open_library")) == [
        UNKNOWN_SOURCE_WARNING
    ]


@pytest.mark.parametrize("sources", ["wikipedia,openlibrary", " OpenLibrary , ", ""])
def test_config_warnings_silent_for_recognised_or_empty_ai_sources(sources):
    # The default value is covered by test_config_warnings_silent_when_ai_configured.
    assert config_warnings(_ai_configured(ai_sources=sources)) == []


def test_config_warnings_unrecognised_ai_source_silent_when_ai_disabled():
    assert config_warnings(Settings(ai_enabled=False, ai_sources="open_library")) == []
