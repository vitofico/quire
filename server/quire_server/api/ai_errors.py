"""HTTP shape of AI provider failures (issue #102).

Registered by ``main.create_app`` only when AI is configured, so sync-only
deploys never import the provider client. Any ``ProviderError`` that
escapes an ``/ai/v1`` route becomes a 502 or 504 whose ``detail`` is the
object built by ``core.ai.provider_errors.describe``.
"""

from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from quire_server.core.ai.client import ProviderError
from quire_server.core.ai.provider_errors import describe


def register_provider_error_handler(app: FastAPI, *, timeout_s: float, model: str | None) -> None:
    """Attach the ``ProviderError`` handler to ``app``.

    ``timeout_s`` and ``model`` are the deploy's ``QUIRE_SERVER_AI_TIMEOUT_S``
    and ``QUIRE_SERVER_AI_MODEL``; they only feed the human-readable text.
    """

    async def _handle(request: Request, exc: Exception) -> JSONResponse:
        assert isinstance(exc, ProviderError)  # registered for this class only
        info = describe(exc, timeout_s=timeout_s, model=model)
        return JSONResponse(status_code=info.http_status, content={"detail": info.as_detail()})

    app.add_exception_handler(ProviderError, _handle)
