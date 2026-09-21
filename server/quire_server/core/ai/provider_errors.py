"""Describe an AI provider failure for humans (issue #102).

`AIClient` raises a typed `ProviderError`; before this module the type name
reached the log and nothing reached the phone. `describe()` is the single
place that decides the HTTP status, a stable machine-readable code, a
sentence for the reader and a hint for the operator.

Messages are built from the exception class and the upstream status code
only. Exception text (which carries provider response bytes) never enters
the result, so nothing here can leak a key or a prompt.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from quire_server.core.ai.client import (
    ProviderError,
    ProviderParseError,
    ProviderRejected,
    ProviderTimeout,
    ProviderUnreachable,
)


@dataclass(frozen=True, slots=True)
class ProviderErrorInfo:
    code: str
    http_status: int
    message: str
    hint: str | None
    provider_status: int | None

    def as_detail(self) -> dict[str, object]:
        """The JSON object placed under ``detail`` in the HTTP response."""
        return {
            "code": self.code,
            "message": self.message,
            "hint": self.hint,
            "provider_status": self.provider_status,
        }


def describe(
    exc: ProviderError,
    *,
    timeout_s: float,
    model: str | None,
    timeout_var: str = "QUIRE_SERVER_AI_TIMEOUT_S",
) -> ProviderErrorInfo:
    """Map a provider exception to what the reader and the operator need.

    ``timeout_s`` and ``timeout_var`` describe the budget the failed call
    ran under (insights use ``QUIRE_SERVER_AI_TIMEOUT_S``, the reader
    profile uses ``QUIRE_SERVER_AI_PROFILE_TIMEOUT_S``). ``model`` is only
    quoted in hints.
    """
    if isinstance(exc, ProviderTimeout):
        return ProviderErrorInfo(
            code="provider_timeout",
            http_status=504,
            message=f"The AI provider did not answer within {math.ceil(timeout_s)} seconds.",
            hint=f"Raise {timeout_var} for slow local models, or pick a faster model.",
            provider_status=None,
        )
    if isinstance(exc, ProviderUnreachable):
        return ProviderErrorInfo(
            code="provider_unreachable",
            http_status=502,
            message="The server could not reach the AI provider.",
            hint=(
                "Check QUIRE_SERVER_AI_BASE_URL and that the provider is running and "
                "reachable from the quire-server container."
            ),
            provider_status=None,
        )
    if isinstance(exc, ProviderRejected):
        status = exc.status_code
        if status in (401, 403):
            message = "The AI provider rejected the server's credentials."
            hint = "Check QUIRE_SERVER_AI_API_KEY."
        elif status == 404:
            pull = f"ollama pull {model}" if model else "ollama pull <model>"
            message = "The AI provider does not know the configured model."
            hint = f"Check QUIRE_SERVER_AI_MODEL; for a local Ollama, run: {pull}"
        else:
            message = f"The AI provider rejected the request (HTTP {status})."
            hint = (
                "Check the provider logs. Some providers reject response_format=json_object; "
                "a different model may be needed."
            )
        return ProviderErrorInfo(
            code="provider_rejected",
            http_status=502,
            message=message,
            hint=hint,
            provider_status=status,
        )
    if isinstance(exc, ProviderParseError):
        subject = f"The model {model}" if model else "The configured model"
        return ProviderErrorInfo(
            code="provider_invalid_output",
            http_status=502,
            message="The AI provider answered, but not in the structured format Quire needs.",
            hint=(
                f"{subject} may be too small for structured JSON output; "
                "gpt-oss:120b-cloud on Ollama is known to work."
            ),
            provider_status=None,
        )
    return ProviderErrorInfo(
        code="provider_error",
        http_status=502,
        message="The AI provider call failed.",
        hint=None,
        provider_status=None,
    )
