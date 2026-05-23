import base64
import hashlib
import logging
import time
from collections import OrderedDict
from collections.abc import Callable
from typing import Annotated

import httpx
from fastapi import Depends, HTTPException, Request, status

logger = logging.getLogger(__name__)


class _CacheEntry:
    __slots__ = ("user_id", "is_valid", "expires_at")

    def __init__(self, user_id: str | None, is_valid: bool, expires_at: float) -> None:
        self.user_id = user_id
        self.is_valid = is_valid
        self.expires_at = expires_at


class CalibreAuthValidator:
    """Validates incoming Basic auth headers by probing CWA. TTL-cached."""

    def __init__(
        self,
        client: httpx.AsyncClient,
        cwa_base_url: str,
        probe_path: str = "/opds",
        positive_ttl_s: int = 60,
        negative_ttl_s: int = 10,
        max_entries: int = 1024,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._client = client
        self._cwa = cwa_base_url.rstrip("/")
        self._probe_path = probe_path
        self._pos_ttl = positive_ttl_s
        self._neg_ttl = negative_ttl_s
        self._max = max_entries
        self._cache: OrderedDict[bytes, _CacheEntry] = OrderedDict()
        self._clock = clock

    async def validate(self, auth_header: str) -> str:
        """Returns the user_id (lowercased CWA username) or raises HTTPException(401/503)."""
        if not auth_header.lower().startswith("basic "):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="basic auth required"
            )

        b64 = auth_header[6:].strip()
        key = hashlib.sha256(b64.encode("ascii")).digest()
        now = self._clock()

        cached = self._cache.get(key)
        if cached and cached.expires_at > now:
            self._cache.move_to_end(key)
            if cached.is_valid:
                return cached.user_id  # type: ignore[return-value]
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid credentials"
            )

        try:
            resp = await self._client.get(
                f"{self._cwa}{self._probe_path}",
                headers={"Authorization": auth_header},
                follow_redirects=False,
            )
        except httpx.RequestError as e:
            logger.warning("upstream auth unavailable: %s", e)
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="upstream auth unavailable"
            ) from e

        if resp.status_code == 200:
            user_id = self._extract_username(b64)
            self._put(key, _CacheEntry(user_id, True, now + self._pos_ttl))
            return user_id
        if resp.status_code == 401:
            self._put(key, _CacheEntry(None, False, now + self._neg_ttl))
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid credentials"
            )
        logger.warning("CWA returned %s on auth probe", resp.status_code)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="upstream auth unavailable"
        )

    def _put(self, key: bytes, entry: _CacheEntry) -> None:
        self._cache[key] = entry
        self._cache.move_to_end(key)
        while len(self._cache) > self._max:
            self._cache.popitem(last=False)

    @staticmethod
    def _extract_username(b64_value: str) -> str:
        try:
            decoded = base64.b64decode(b64_value, validate=True).decode("utf-8", errors="strict")
        except (ValueError, UnicodeDecodeError) as e:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="malformed credentials"
            ) from e
        if ":" not in decoded:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="malformed credentials"
            )
        return decoded.split(":", 1)[0].lower()


async def get_validator(request: Request) -> CalibreAuthValidator:
    return request.app.state.auth_validator


# Phase 0, task S-1: primary-auth dependency now resolves through the
# configured :class:`AuthBackend` instead of going straight to the CalibreWeb
# validator. The public signature stays ``-> str`` so every existing route
# and every test that overrides ``current_user_id`` via
# ``app.dependency_overrides`` keeps working unchanged.
#
# Routes that need the full :class:`AuthUser` (notably ``/auth/v1/logout``,
# which needs the session token hash) use :func:`current_auth_user` instead.
#
# Implementation note: importing :class:`AuthBackend` at module level would
# create a circular import (auth_backend imports from this module). The
# dependency loads it lazily.


async def get_auth_backend(request: Request):
    """FastAPI dependency: pull the configured backend off ``app.state``."""
    backend = getattr(request.app.state, "auth_backend", None)
    if backend is None:
        # main.py always wires this. A 500 surfaces the misconfiguration
        # rather than silently 401-ing every request.
        logger.error("auth_backend missing from app.state")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="auth_backend_not_configured",
        )
    return backend


async def current_user_id(
    request: Request,
    backend: Annotated[object, Depends(get_auth_backend)],
) -> str:
    """Return the storage-scope user id for the authenticated request.

    Format depends on the configured backend: CalibreWeb yields a lowercase
    CWA username; NativeAuth yields ``"native:<id>"``. Existing user-scoped
    tables persist this string verbatim.
    """
    user = await backend.current_user(request)  # type: ignore[attr-defined]
    return user.user_id


async def current_auth_user(
    request: Request,
    backend: Annotated[object, Depends(get_auth_backend)],
):
    """Return the full :class:`AuthUser`. Used by ``/auth/v1/logout``."""
    return await backend.current_user(request)  # type: ignore[attr-defined]
