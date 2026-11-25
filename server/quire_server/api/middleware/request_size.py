"""Request-size limit middleware: reject bodies larger than max_bytes with 413.

Inspects Content-Length pre-body when present. For chunked transfer (no
Content-Length), wraps the ASGI `receive` callable and counts bytes as they
stream in, raising 413 mid-stream when the cap is exceeded.

GET/HEAD/OPTIONS/DELETE bypass the check (no body expected). ``routes`` gives
named uploads (method plus path pattern) a ceiling of their own.
"""

from __future__ import annotations

import json
import re
from collections.abc import Sequence

from starlette.types import ASGIApp, Message, Receive, Scope, Send

_BODYLESS_METHODS = {"GET", "HEAD", "OPTIONS", "DELETE"}


class _BodyTooLarge(Exception):
    pass


class RequestSizeMiddleware:
    """Pure ASGI middleware. We avoid BaseHTTPMiddleware here because counting
    bytes in a chunked body requires wrapping `receive`, which BaseHTTPMiddleware
    obscures."""

    def __init__(
        self,
        app: ASGIApp,
        max_bytes: int,
        routes: Sequence[tuple[str, re.Pattern[str], int]] = (),
    ) -> None:
        self.app = app
        self.max_bytes = max_bytes
        self.routes = tuple(routes)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        method = scope.get("method", "").upper()
        if method in _BODYLESS_METHODS:
            await self.app(scope, receive, send)
            return

        max_bytes = self.max_bytes
        for route_method, pattern, limit in self.routes:
            if method == route_method and pattern.fullmatch(scope.get("path", "")):
                max_bytes = limit
                break

        # Try Content-Length first; reject early if oversized.
        headers = {
            k.decode("latin-1").lower(): v.decode("latin-1") for k, v in scope.get("headers", [])
        }
        content_length = headers.get("content-length")
        if content_length is not None:
            try:
                declared = int(content_length)
            except ValueError:
                declared = None
            if declared is not None and declared > max_bytes:
                await self._reject(send, max_bytes)
                return

        # For chunked or unknown-length bodies, wrap receive to count bytes.
        received_bytes = 0
        rejection_sent = False

        async def wrapped_receive() -> Message:
            nonlocal received_bytes, rejection_sent
            message = await receive()
            if rejection_sent:
                return message
            if message["type"] == "http.request":
                body: bytes = message.get("body", b"") or b""
                received_bytes += len(body)
                if received_bytes > max_bytes:
                    rejection_sent = True
                    raise _BodyTooLarge()
            return message

        try:
            await self.app(scope, wrapped_receive, send)
        except* _BodyTooLarge:
            # ``except*``: an ``@app.middleware("http")`` layer in between hands
            # the error up inside an exception group.
            await self._reject(send, max_bytes)

    async def _reject(self, send: Send, max_bytes: int) -> None:
        detail = f"request body exceeds {max_bytes} bytes"
        body = json.dumps({"detail": detail}).encode("utf-8")
        await send(
            {
                "type": "http.response.start",
                "status": 413,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"content-length", str(len(body)).encode("ascii")),
                ],
            }
        )
        await send({"type": "http.response.body", "body": body, "more_body": False})
