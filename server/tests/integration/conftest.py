"""Integration test fixtures for /ai/v1 and friends.

Design notes
------------
* ``client_factory`` creates the FastAPI app EAGERLY (on call, before ``async with``)
  so that ``configure_ai(app, ...)`` can patch ``app.state`` before the HTTP client
  is opened.
* Auth is stubbed via ``app.dependency_overrides`` (FastAPI's official testing hook),
  not by monkeypatching the module attribute.  The fake simply decodes the Basic header
  and returns the username portion as the user_id.
* ``app`` is a thin proxy that forwards attribute access to the most-recently-created
  app object so tests can hold a reference before the factory is called.
"""

from __future__ import annotations

import base64
from contextlib import asynccontextmanager

import httpx
import pytest
from fastapi import HTTPException, Request, status
from httpx import ASGITransport
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine


@pytest.fixture(autouse=True)
async def _truncate_ai_tables_between_tests(request, engine: AsyncEngine):
    """Reset AI-related tables before each integration test that touches the DB.

    The app's own request-scoped DB sessions ``commit()`` rows that the test's
    ``session`` fixture rollback cannot undo, so we explicitly TRUNCATE before
    each test for clean isolation.  Mirrors the unit-test pattern in
    ``tests/unit/conftest.py``: only fires when the test requests the ``session``
    fixture so pure-unit tests stay fast.
    """
    if "session" not in request.fixturenames:
        return
    async with engine.begin() as conn:
        await conn.execute(
            text(
                "TRUNCATE TABLE book_insights, user_ai_preferences, "
                "external_source_cache, ai_usage_daily RESTART IDENTITY CASCADE"
            )
        )


class _AppProxy:
    """Proxy to the most-recently created app.  Populated by ``client_factory``."""

    def __init__(self) -> None:
        object.__setattr__(self, "_real", None)

    def _set(self, real_app) -> None:
        object.__setattr__(self, "_real", real_app)

    def __getattr__(self, name: str):
        real = object.__getattribute__(self, "_real")
        if real is None:
            raise RuntimeError(
                "app fixture used before client_factory created the app. "
                "Call client_factory(...) first or call configure_ai inside the context."
            )
        return getattr(real, name)

    def __setattr__(self, name: str, value) -> None:
        if name.startswith("_"):
            object.__setattr__(self, name, value)
        else:
            real = object.__getattribute__(self, "_real")
            setattr(real, name, value)


@pytest.fixture
def app() -> _AppProxy:
    """Proxy to the app created by the most-recent ``client_factory(...)`` call."""
    return _AppProxy()


@pytest.fixture
def client_factory(monkeypatch, postgres_url, alembic_upgrade, app: _AppProxy):
    """Return an async context-manager factory that spins up a fresh FastAPI app.

    The app is built EAGERLY when the factory function is called so that callers
    can patch ``app.state`` (e.g. inject a fake orchestrator) before entering the
    ``async with`` block.

    Usage::

        async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
            r = await client.get("/ai/v1/config", headers=_basic_header("alice"))
    """

    def _factory(*, skip_auth_overrides: bool = False, **env_kwargs):
        # --- 1. Configure environment ----------------------------------------
        monkeypatch.setenv("OPDS_SYNC_DATABASE_URL", postgres_url)
        monkeypatch.setenv("OPDS_SYNC_CWA_BASE_URL", "http://test-cwa")
        for k, v in env_kwargs.items():
            monkeypatch.setenv(f"OPDS_SYNC_{k.upper()}", str(v))

        # --- 2. Clear the settings cache and build the app -------------------
        from opds_sync.config import get_settings

        get_settings.cache_clear()

        from opds_sync.main import create_app

        real_app = create_app()

        # Expose the app via the proxy so tests can reach into app.state.
        app._set(real_app)

        # --- 3. Stub auth via dependency_overrides ---------------------------
        # Tests that exercise REAL auth (PR-B token-mode tests, for example)
        # pass `skip_auth_overrides=True` to opt out of the fakes.
        if not skip_auth_overrides:
            from opds_sync.core.auth import current_user_id as _real_cuid

            async def _fake_current_user_id(request: Request) -> str:
                header = request.headers.get("Authorization", "")
                if not header.lower().startswith("basic "):
                    raise HTTPException(
                        status_code=status.HTTP_401_UNAUTHORIZED,
                        detail="missing credentials",
                    )
                try:
                    decoded = base64.b64decode(header[6:].strip()).decode("utf-8")
                except Exception:
                    raise HTTPException(
                        status_code=status.HTTP_401_UNAUTHORIZED,
                        detail="malformed credentials",
                    ) from None
                if ":" not in decoded:
                    raise HTTPException(
                        status_code=status.HTTP_401_UNAUTHORIZED,
                        detail="malformed credentials",
                    )
                return decoded.split(":", 1)[0].lower()

            real_app.dependency_overrides[_real_cuid] = _fake_current_user_id

            # PR-B: AI routes depend on get_ai_principal instead of
            # current_user_id. Mirror the basic-auth fake so existing tests
            # that use _basic_header() keep working without modification.
            try:
                from opds_sync.api.ai_auth import (
                    AiPrincipal,
                )
                from opds_sync.api.ai_auth import (
                    get_ai_principal as _real_principal,
                )
            except ImportError:
                _real_principal = None

            if _real_principal is not None:

                async def _fake_ai_principal(request: Request) -> AiPrincipal:
                    subject = await _fake_current_user_id(request)
                    return AiPrincipal(
                        subject=subject,
                        tenant_id="local",
                        scopes=(),
                        auth_mode="basic",
                        request_id=None,
                    )

                real_app.dependency_overrides[_real_principal] = _fake_ai_principal

        # --- 4. Return async context manager ---------------------------------
        @asynccontextmanager
        async def _ctx():
            async with httpx.AsyncClient(
                transport=ASGITransport(app=real_app),
                base_url="http://test",
            ) as ac:
                yield ac

        return _ctx()

    return _factory
