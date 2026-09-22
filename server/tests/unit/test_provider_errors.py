"""Issue #102: every provider failure gets a code, a status, a sentence for
the reader and a hint for the operator. These pin the exact strings; the
Android side and the docs quote them."""

from __future__ import annotations

import json

import pytest

from quire_server.core.ai.client import (
    ProviderError,
    ProviderParseError,
    ProviderRejected,
    ProviderTimeout,
    ProviderUnreachable,
)
from quire_server.core.ai.provider_errors import ProviderErrorInfo, describe

TIMEOUT_HINT = "Raise QUIRE_SERVER_AI_TIMEOUT_S for slow local models, or pick a faster model."


def test_timeout_is_504_and_names_the_timeout_variable():
    info = describe(ProviderTimeout("ReadTimeout('simulated')"), timeout_s=120.0, model="m")
    assert info == ProviderErrorInfo(
        code="provider_timeout",
        http_status=504,
        message="The AI provider did not answer within 120 seconds.",
        hint=TIMEOUT_HINT,
        provider_status=None,
    )


def test_timeout_rounds_seconds_up_and_can_name_the_profile_variable():
    info = describe(
        ProviderTimeout("x"),
        timeout_s=89.2,
        model="m",
        timeout_var="QUIRE_SERVER_AI_PROFILE_TIMEOUT_S",
    )
    assert info.message == "The AI provider did not answer within 90 seconds."
    assert info.hint == (
        "Raise QUIRE_SERVER_AI_PROFILE_TIMEOUT_S for slow local models, or pick a faster model."
    )


def test_unreachable_is_502():
    info = describe(ProviderUnreachable("provider 503: overloaded"), timeout_s=120.0, model="m")
    assert info.code == "provider_unreachable"
    assert info.http_status == 502
    assert info.message == "The server could not reach the AI provider."
    assert info.hint == (
        "Check QUIRE_SERVER_AI_BASE_URL and that the provider is running and reachable "
        "from the quire-server container."
    )
    assert info.provider_status is None


@pytest.mark.parametrize("status", [401, 403])
def test_rejected_credentials_hint_the_api_key(status):
    info = describe(ProviderRejected(status, f"provider {status}: nope"), timeout_s=1.0, model="m")
    assert info.code == "provider_rejected"
    assert info.http_status == 502
    assert info.message == "The AI provider rejected the server's credentials."
    assert info.hint == "Check QUIRE_SERVER_AI_API_KEY."
    assert info.provider_status == status


def test_rejected_404_hints_the_model():
    info = describe(
        ProviderRejected(404, "provider 404: no such model"), timeout_s=1.0, model="llama3.1:8b"
    )
    assert info.message == "The AI provider does not know the configured model."
    assert (
        info.hint == "Check QUIRE_SERVER_AI_MODEL; for a local Ollama, run: ollama pull llama3.1:8b"
    )
    assert info.provider_status == 404


def test_rejected_404_without_a_model_name():
    info = describe(ProviderRejected(404, "x"), timeout_s=1.0, model=None)
    assert info.hint == "Check QUIRE_SERVER_AI_MODEL; for a local Ollama, run: ollama pull <model>"


def test_rejected_other_status_points_at_provider_logs():
    info = describe(ProviderRejected(400, "provider 400: bad request"), timeout_s=1.0, model="m")
    assert info.message == "The AI provider rejected the request (HTTP 400)."
    assert info.hint == (
        "Check the provider logs, the model name and the API key. The server "
        "already retries once in a plainer request shape before reporting this."
    )
    assert info.provider_status == 400


def test_parse_error_names_the_model():
    info = describe(ProviderParseError("no choices"), timeout_s=1.0, model="llama3.1:8b")
    assert info.code == "provider_invalid_output"
    assert info.http_status == 502
    assert info.message == (
        "The AI provider answered, but not in the structured format Quire needs."
    )
    assert info.hint == (
        "The model llama3.1:8b may be too small for structured JSON output; "
        "gpt-oss:120b-cloud on Ollama is known to work."
    )


def test_parse_error_without_a_model_name():
    info = describe(ProviderParseError("x"), timeout_s=1.0, model=None)
    assert info.hint == (
        "The configured model may be too small for structured JSON output; "
        "gpt-oss:120b-cloud on Ollama is known to work."
    )


def test_unknown_provider_error_falls_back():
    info = describe(ProviderError("something new"), timeout_s=1.0, model="m")
    assert info == ProviderErrorInfo(
        code="provider_error",
        http_status=502,
        message="The AI provider call failed.",
        hint=None,
        provider_status=None,
    )


def test_as_detail_has_exactly_the_documented_keys():
    detail = describe(ProviderTimeout("x"), timeout_s=5.0, model="m").as_detail()
    assert set(detail) == {"code", "message", "hint", "provider_status"}
    assert detail["code"] == "provider_timeout"
    assert detail["provider_status"] is None


def test_exception_text_never_reaches_the_detail():
    # AIClient puts the first 200 chars of the provider body into the exception
    # message. That text must not travel to the phone.
    exc = ProviderRejected(400, "provider 400: leaked sk-secret-token")
    detail = describe(exc, timeout_s=5.0, model="m").as_detail()
    assert "sk-secret-token" not in json.dumps(detail)
