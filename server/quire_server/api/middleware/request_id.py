"""Request-ID middleware: read or generate X-Request-ID; bind to contextvar."""

from __future__ import annotations

import uuid

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response
from starlette.types import ASGIApp

from quire_server.core.logging_ctx import request_id_var

# Practical cap so a misbehaving client can't blow up log lines.
_MAX_REQUEST_ID_LEN = 128


def _sanitize(raw: str | None) -> str | None:
    """Return raw if it is a valid request-id, else None (caller generates one).

    Valid = non-empty, ≤ 128 chars, all printable ASCII (RFC 3986 unreserved + a
    few common separators). We do not attempt to parse vendor formats; we just
    reject anything obviously bogus.
    """
    if not raw:
        return None
    if len(raw) > _MAX_REQUEST_ID_LEN:
        return None
    if not all(33 <= ord(c) <= 126 for c in raw):
        return None
    return raw


class RequestIDMiddleware(BaseHTTPMiddleware):
    """Bind X-Request-ID for the duration of each request.

    - Reads `X-Request-ID` from the request; validates; falls back to uuid4().hex.
    - Binds the resolved id to `request_id_var` for downstream code.
    - Always echoes the id back in the response's `X-Request-ID` header,
      including on 4xx / 5xx responses produced by inner middleware or routes.
    - Resets the contextvar after the response, so subsequent requests in the
      same async task don't see a stale id.
    """

    def __init__(self, app: ASGIApp) -> None:
        super().__init__(app)

    async def dispatch(self, request: Request, call_next):
        incoming = request.headers.get("X-Request-ID")
        request_id = _sanitize(incoming) or uuid.uuid4().hex
        token = request_id_var.set(request_id)
        try:
            response: Response = await call_next(request)
        finally:
            request_id_var.reset(token)
        response.headers["X-Request-ID"] = request_id
        return response
