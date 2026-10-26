"""Generic identity and monthly policy contracts, independent of hosted companions."""

import asyncio
from datetime import UTC, datetime

import httpx
import pytest
from fastapi import HTTPException
from sqlalchemy import text
from sqlalchemy.ext.asyncio import async_sessionmaker

from quire_server.core.ai.monthly_policy import MonthlyPolicy, PolicySettings


async def test_monthly_admission_atomic_across_jobs_and_months(engine):

    now = [datetime(2026, 10, 25, tzinfo=UTC)]

    def handler(_):
        return httpx.Response(
            200,
            json={
                "version": 1,
                "account_id": "native:quota-test",
                "expires_at": int(now[0].timestamp()) + 120,
                "limits": {"monthly_jobs": 3},
            },
        )

    async with engine.begin() as conn:
        await conn.execute(
            text("DELETE FROM ai_usage_monthly WHERE user_id=:id"), {"id": "native:quota-test"}
        )
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        policy = MonthlyPolicy(
            PolicySettings(url="http://cloud", token="x" * 32),
            client,
            async_sessionmaker(engine),
            clock=lambda: now[0],
        )
        results = await asyncio.gather(
            *(policy.reserve("native:quota-test") for _ in range(8)), return_exceptions=True
        )
        assert results.count(None) == 3
        errors = [r for r in results if isinstance(r, HTTPException)]
        assert len(errors) == 5 and all(e.status_code == 429 for e in errors)
        now[0] = datetime(2026, 11, 1, tzinfo=UTC)
        await policy.reserve("native:quota-test")


async def test_me_rejects_unsigned_identity_and_is_absent_for_calibre(client_factory):
    async with client_factory(
        auth_backend="native", ai_enabled="false", skip_auth_overrides=True
    ) as client:
        assert (
            await client.get("/auth/v1/me", headers={"X-User-ID": "native:1"})
        ).status_code == 401
        assert (
            await client.get("/auth/v1/me", headers={"Authorization": "Bearer malformed"})
        ).status_code == 401
    async with client_factory(auth_backend="calibreweb") as client:
        assert (await client.get("/auth/v1/me")).status_code == 404


async def test_native_me_identity_and_revocation(client_factory, app, session):
    async with client_factory(
        auth_backend="native", ai_enabled=False, progress_enabled=True, skip_auth_overrides=True
    ) as client:
        account_id = await app.state.auth_backend.register(
            email="native-me@example.test", password="synthetic-native-password"
        )
        login = await client.post(
            "/auth/v1/login",
            json={
                "email": "native-me@example.test",
                "password": "synthetic-native-password",
            },
        )
        assert login.status_code == 200
        headers = {"Authorization": "Bearer " + login.json()["token"]}
        result = await client.get("/auth/v1/me", headers=headers)
        assert result.status_code == 200
        assert result.json()["user"] == {"id": f"native:{account_id}", "backend": "native"}
        assert result.json()["capabilities"]["progress_metadata_sync"] is True
        assert result.headers["Cache-Control"] == "no-store"
        assert (await client.post("/auth/v1/logout", headers=headers)).status_code == 204
        assert (await client.get("/auth/v1/me", headers=headers)).status_code == 401


async def test_trial_quota_window_does_not_reset_at_month_boundary(engine):
    now = [datetime(2026, 10, 31, tzinfo=UTC)]
    account = "native:trial-window-test"

    def handler(_):
        return httpx.Response(
            200,
            json={
                "version": 1,
                "account_id": account,
                "expires_at": int(now[0].timestamp()) + 120,
                "limits": {"monthly_jobs": 3},
                "quota_window_start": "2026-10-31",
                "sync_writes": True,
            },
        )

    async with engine.begin() as connection:
        await connection.execute(
            text("DELETE FROM ai_usage_monthly WHERE user_id=:id"), {"id": account}
        )
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        policy = MonthlyPolicy(
            PolicySettings(url="http://policy", token="x" * 32),
            client,
            async_sessionmaker(engine),
            clock=lambda: now[0],
        )
        for _ in range(3):
            await policy.reserve(account)
        now[0] = datetime(2026, 11, 1, tzinfo=UTC)
        with pytest.raises(HTTPException) as exhausted:
            await policy.reserve(account)
        assert exhausted.value.status_code == 429
