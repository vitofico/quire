"""Integration tests for the AuthBackend seam (Phase 0, task S-1).

Coverage:

* NativeAuth login success returns a token + ISO expiry; the token round-
  trips against a protected sync route (``GET /library/v1/items``).
* Wrong password and unknown email return the SAME 401 body.
* Logout revokes the session; the same token then fails.
* Logout is idempotent at the token layer (second call → 401 from dep, OK).
* Expired sessions are rejected.
* Magic-link request returns 202; consume returns 501.
* The ``/auth/v1/*`` router is NOT mounted under CalibreWeb mode.
* Schema sanity: ``native_users`` + ``native_sessions`` exist.

Tests opt out of ``client_factory``'s ``dependency_overrides`` plumbing
via ``skip_auth_overrides=True`` so the REAL backend resolves requests.

The ``app`` fixture is the shared ``_AppProxy`` from integration/conftest.py;
it forwards attribute access to the most-recently created app, so reading
``app.state.auth_backend`` after the factory returns yields the backend the
test wants to exercise.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from quire_server.core.auth_backend import NativeAuth
from quire_server.core.native_auth import get_dummy_hash, hash_session_token
from quire_server.db.models import NativeSession, NativeUser

# Common factory kwargs for every Native test. The `_isolate` mark is
# attached at the test-function level so a slip into the default CalibreWeb
# fixture path is impossible.
_NATIVE_KW = {
    "auth_backend": "native",
    "ai_enabled": "false",
    # Force the library router on so the token-round-trip / logout / expired
    # tests can exercise a real `current_user_id`-protected endpoint
    # (`GET /library/v1/items`) under the `test (ai_only)` CI matrix entry,
    # which sets `progress_enabled=false` globally. Tests that only hit
    # `/auth/v1/*` are unaffected by the extra router being mounted.
    "progress_enabled": "true",
    "skip_auth_overrides": True,
}


async def _register(app, *, email: str, password: str) -> int:
    """Create a NativeUser via the backend's register() helper.

    Bypasses HTTP because the OSS server intentionally does not mount a
    signup endpoint (Cloud's control plane owns signup).
    """
    backend = app.state.auth_backend
    assert isinstance(backend, NativeAuth)
    return await backend.register(email=email, password=password)


# ----------------------------------------------------------------------------
# Default-mode (CalibreWeb) guarantees
# ----------------------------------------------------------------------------


async def test_calibreweb_mode_does_not_mount_auth_router(client_factory):
    """In OSS / default mode the auth router is invisible."""
    async with client_factory() as client:
        r = await client.post("/auth/v1/login", json={"email": "x@y.z", "password": "p"})
    assert r.status_code == 404


# ----------------------------------------------------------------------------
# NativeAuth happy path
# ----------------------------------------------------------------------------


async def test_native_login_success_returns_token_and_expiry(client_factory, app):
    async with client_factory(**_NATIVE_KW) as client:
        await _register(app, email="alice@example.com", password="hunter2-strong-pw")
        r = await client.post(
            "/auth/v1/login",
            json={"email": "alice@example.com", "password": "hunter2-strong-pw"},
        )
    assert r.status_code == 200, r.text
    body = r.json()
    assert isinstance(body["token"], str) and len(body["token"]) >= 40
    expires = datetime.fromisoformat(body["expires_at"])
    assert expires > datetime.now(UTC)


async def test_native_login_token_round_trips_protected_endpoint(client_factory, app):
    """A login-issued token authenticates a real protected route."""
    async with client_factory(**_NATIVE_KW) as client:
        await _register(app, email="bob@example.com", password="bobs-strong-pw")
        r = await client.post(
            "/auth/v1/login",
            json={"email": "bob@example.com", "password": "bobs-strong-pw"},
        )
        token = r.json()["token"]

        # /library/v1/items is a sync route protected by current_user_id.
        # With a Bearer token resolved through NativeAuth, the request must
        # authenticate and return 200 (empty list for a fresh user).
        r2 = await client.get(
            "/library/v1/items",
            headers={"Authorization": f"Bearer {token}"},
        )
    assert r2.status_code == 200, r2.text


# ----------------------------------------------------------------------------
# Failure modes — unknown email vs wrong password are indistinguishable
# ----------------------------------------------------------------------------


async def test_unknown_email_and_wrong_password_return_identical_401(client_factory, app):
    """The response body shape must be byte-identical for both failures."""
    async with client_factory(**_NATIVE_KW) as client:
        await _register(app, email="carol@example.com", password="carols-strong-pw")
        r_unknown = await client.post(
            "/auth/v1/login",
            json={"email": "nobody@example.com", "password": "anything"},
        )
        r_wrong = await client.post(
            "/auth/v1/login",
            json={"email": "carol@example.com", "password": "WRONG"},
        )
    assert r_unknown.status_code == 401
    assert r_wrong.status_code == 401
    assert r_unknown.json() == r_wrong.json() == {"detail": "invalid credentials"}


async def test_unknown_email_triggers_dummy_hash_verify(monkeypatch, client_factory):
    """Deterministic guarantee that the unknown-email path runs a real
    verify against the dummy hash. Replaces a timing-based test which would
    be flaky in CI.
    """
    calls: list[str] = []

    async def _spy_verify(stored_hash: str, plain: str) -> bool:
        calls.append(stored_hash)
        return False

    monkeypatch.setattr("quire_server.core.auth_backend.verify_password", _spy_verify)

    async with client_factory(**_NATIVE_KW) as client:
        r = await client.post(
            "/auth/v1/login",
            json={"email": "ghost@example.com", "password": "anything"},
        )
    assert r.status_code == 401
    # Exactly one verify call, against the precomputed dummy hash.
    assert calls == [get_dummy_hash()]


# ----------------------------------------------------------------------------
# Logout / session lifecycle
# ----------------------------------------------------------------------------


async def test_logout_revokes_session(client_factory, app):
    async with client_factory(**_NATIVE_KW) as client:
        await _register(app, email="dave@example.com", password="daves-strong-pw")
        login = await client.post(
            "/auth/v1/login",
            json={"email": "dave@example.com", "password": "daves-strong-pw"},
        )
        token = login.json()["token"]
        headers = {"Authorization": f"Bearer {token}"}

        # Pre-logout: token works.
        r_before = await client.get("/library/v1/items", headers=headers)
        assert r_before.status_code == 200

        # Logout returns 204.
        r_logout = await client.post("/auth/v1/logout", headers=headers)
        assert r_logout.status_code == 204

        # Post-logout: same token is now rejected.
        r_after = await client.get("/library/v1/items", headers=headers)
    assert r_after.status_code == 401
    assert r_after.json() == {"detail": "invalid credentials"}


async def test_logout_idempotent_at_token_layer(client_factory, app):
    """A second logout with the same token gets 401 from current_auth_user
    (the dep rejects revoked sessions before the route runs). That's the
    contract: revoke-before-validate would let attackers iterate revocations.
    """
    async with client_factory(**_NATIVE_KW) as client:
        await _register(app, email="erin@example.com", password="erins-strong-pw")
        login = await client.post(
            "/auth/v1/login",
            json={"email": "erin@example.com", "password": "erins-strong-pw"},
        )
        token = login.json()["token"]
        headers = {"Authorization": f"Bearer {token}"}

        await client.post("/auth/v1/logout", headers=headers)
        r2 = await client.post("/auth/v1/logout", headers=headers)
    assert r2.status_code == 401


async def test_expired_session_is_rejected(client_factory, app, engine):
    """Backdating ``expires_at`` simulates an aged-out session."""
    async with client_factory(**_NATIVE_KW) as client:
        await _register(app, email="frank@example.com", password="franks-strong-pw")
        login = await client.post(
            "/auth/v1/login",
            json={"email": "frank@example.com", "password": "franks-strong-pw"},
        )
        token = login.json()["token"]
        token_hash = hash_session_token(token)

        sf = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
        async with sf() as s:
            row = (
                await s.execute(select(NativeSession).where(NativeSession.token_hash == token_hash))
            ).scalar_one()
            row.expires_at = datetime.now(UTC) - timedelta(seconds=1)
            await s.commit()

        r = await client.get(
            "/library/v1/items",
            headers={"Authorization": f"Bearer {token}"},
        )
    assert r.status_code == 401


# ----------------------------------------------------------------------------
# Magic-link stubs
# ----------------------------------------------------------------------------


async def test_magic_link_request_returns_202_stub(client_factory):
    async with client_factory(**_NATIVE_KW) as client:
        r = await client.post("/auth/v1/magic-link/request", json={"email": "x@y.z"})
    assert r.status_code == 202
    body = r.json()
    assert body["status"] == "accepted"
    # Body documents itself as a stub.
    detail = body["detail"].lower()
    assert "magic-link" in detail or "not implemented" in detail or "stub" in detail


async def test_magic_link_consume_returns_501(client_factory):
    async with client_factory(**_NATIVE_KW) as client:
        r = await client.get("/auth/v1/magic-link/consume?token=abc")
    assert r.status_code == 501
    assert r.json() == {"detail": "not implemented"}


# ----------------------------------------------------------------------------
# Email canonicalization at the storage layer
# ----------------------------------------------------------------------------


async def test_login_accepts_canonicalized_email(client_factory, app):
    """Mixed-case + whitespace email at login matches the canonical row."""
    async with client_factory(**_NATIVE_KW) as client:
        await _register(app, email="grace@example.com", password="graces-strong-pw")
        r = await client.post(
            "/auth/v1/login",
            json={"email": "  Grace@EXAMPLE.com  ", "password": "graces-strong-pw"},
        )
    assert r.status_code == 200


# ----------------------------------------------------------------------------
# Schema sanity
# ----------------------------------------------------------------------------


async def test_native_users_and_sessions_tables_exist(engine):
    """The auth_001 migration must create both tables."""

    def _introspect(sync_conn):
        from sqlalchemy import inspect

        insp = inspect(sync_conn)
        return set(insp.get_table_names())

    async with engine.connect() as conn:
        names = await conn.run_sync(_introspect)

    assert "native_users" in names
    assert "native_sessions" in names


@pytest.mark.parametrize("table", ["native_users", "native_sessions"])
async def test_native_tables_have_expected_columns(engine, table):
    def _introspect(sync_conn, table_name=table):
        from sqlalchemy import inspect

        insp = inspect(sync_conn)
        return {c["name"]: c for c in insp.get_columns(table_name)}

    async with engine.connect() as conn:
        cols = await conn.run_sync(_introspect)

    if table == "native_users":
        assert {"id", "email", "password_hash", "created_at"}.issubset(cols.keys())
    else:
        assert {
            "token_hash",
            "user_id",
            "created_at",
            "expires_at",
            "revoked_at",
        }.issubset(cols.keys())


# ----------------------------------------------------------------------------
# register() helper: dedup constraint
# ----------------------------------------------------------------------------


async def test_native_user_unique_email_constraint(client_factory, app):
    async with client_factory(**_NATIVE_KW):
        await _register(app, email="dup@example.com", password="x-strong-pw")
        from sqlalchemy.exc import IntegrityError

        with pytest.raises(IntegrityError):
            await _register(app, email="dup@example.com", password="other-strong-pw")
        # Confirm only one row landed.
        backend: NativeAuth = app.state.auth_backend  # type: ignore[assignment]
        async with backend._sf() as s:  # type: ignore[attr-defined]
            rows = (
                (await s.execute(select(NativeUser).where(NativeUser.email == "dup@example.com")))
                .scalars()
                .all()
            )
        assert len(rows) == 1
