"""Generic identity and monthly policy contracts, independent of hosted companions."""

import asyncio
import json
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from pathlib import Path

import httpx
import pytest
from fastapi import HTTPException
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import async_sessionmaker

from quire_server.core.ai.monthly_policy import MonthlyPolicy, PolicySettings
from quire_server.db.models import BookInsight, Highlight, LibraryItem


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


@pytest.fixture
def hosted_policy_env(monkeypatch):
    token = "synthetic-policy-credential" * 2
    monkeypatch.setenv("QUIRE_POLICY_URL", "https://policy.example.test")
    monkeypatch.setenv("QUIRE_POLICY_TOKEN", token)
    return token


_NATIVE_PASSWORD = "synthetic-native-password"


async def _register_native(app, email):
    account_id = await app.state.auth_backend.register(email=email, password=_NATIVE_PASSWORD)
    return f"native:{account_id}"


async def _native_login(client, email):
    response = await client.post(
        "/auth/v1/login", json={"email": email, "password": _NATIVE_PASSWORD}
    )
    assert response.status_code == 200, response.text
    return {"Authorization": f"Bearer {response.json()['token']}"}


@asynccontextmanager
async def _serve_test_policy(policy, account, token, *, monthly_jobs, sync_writes):
    def handler(request):
        assert request.method == "GET"
        assert request.url.host == "policy.example.test"
        assert request.url.path == f"/cloud/internal/policy/{account}"
        assert request.headers["Authorization"] == f"Bearer {token}"
        return httpx.Response(
            200,
            json={
                "version": 1,
                "account_id": account,
                "expires_at": int(policy.clock().timestamp()) + 120,
                "limits": {"monthly_jobs": monthly_jobs},
                "sync_writes": sync_writes,
            },
        )

    await policy.client.aclose()
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        policy.client = client
        yield


@pytest.mark.parametrize("sync_writes", [True, False], ids=["allowed", "denied"])
async def test_hosted_policy_fences_sync_and_library_writes(
    client_factory, app, hosted_policy_env, session, sync_writes
):
    highlight_path = "/sync/v1/highlights"
    library_path = "/library/v1/items"
    highlight_fixture = Path(__file__).parents[1] / "fixtures" / "highlight-sync.json"
    highlight = json.loads(highlight_fixture.read_text())["push"]["items"][0]
    book = {
        "content_hash": "hosted-policy-book",
        "metadata_id": "hosted-policy-metadata",
        "title": "Hosted policy fixture",
        "authors": ["Fixture Author"],
    }

    async with client_factory(
        auth_backend="native", ai_enabled=False, progress_enabled=True, skip_auth_overrides=True
    ) as client:
        email = "sync-policy@example.test"
        account = await _register_native(app, email)
        async with _serve_test_policy(
            app.state.hosted_policy,
            account,
            hosted_policy_env,
            monthly_jobs=1,
            sync_writes=sync_writes,
        ):
            headers = await _native_login(client, email)
            highlight_write = await client.post(
                highlight_path, headers=headers, json={"items": [highlight]}
            )
            library_write = await client.put(library_path, headers=headers, json={"item": book})
            highlight_read = await client.get(
                highlight_path, headers=headers, params={"since": "0"}
            )
            library_read = await client.get(library_path, headers=headers)

    for response in (highlight_write, library_write):
        assert response.status_code == (200 if sync_writes else 403), response.text
        if not sync_writes:
            assert response.json() == {"detail": "hosting_subscription_required"}

    assert highlight_read.status_code == 200, highlight_read.text
    assert library_read.status_code == 200, library_read.text
    assert [item["id"] for item in highlight_read.json()["items"]] == (
        [highlight["id"]] if sync_writes else []
    )
    assert [item["content_hash"] for item in library_read.json()["items"]] == (
        [book["content_hash"]] if sync_writes else []
    )
    highlight_rows = await session.scalars(select(Highlight.id).where(Highlight.user_id == account))
    library_rows = await session.scalars(
        select(LibraryItem.id).where(LibraryItem.user_id == account)
    )
    assert len(highlight_rows.all()) == len(library_rows.all()) == int(sync_writes)


async def test_monthly_policy_refuses_second_lookup_without_new_insight_or_usage(
    client_factory, app, hosted_policy_env, session, monkeypatch
):
    provider_calls = []

    def provider(request):
        assert request.url.host == "model.example.test"
        provider_calls.append(request.method)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "schema_version": 2,
                                    "intro": "Hosted fixture",
                                    "confidence": "low",
                                }
                            )
                        }
                    }
                ]
            },
        )

    async with client_factory(
        auth_backend="native",
        ai_enabled=True,
        ai_auth_mode="basic",
        ai_base_url="https://model.example.test/v1",
        ai_model="synthetic",
        ai_sources="",
        skip_auth_overrides=True,
    ) as client:
        email = "ai-policy@example.test"
        account = await _register_native(app, email)
        policy = app.state.hosted_policy
        now = datetime(2040, 1, 8, tzinfo=UTC)
        monkeypatch.setattr(policy, "clock", lambda: now)
        monkeypatch.setattr(app.state.ai_client, "_transport", httpx.MockTransport(provider))
        async with _serve_test_policy(
            policy, account, hosted_policy_env, monthly_jobs=1, sync_writes=True
        ):
            headers = await _native_login(client, email)
            opt_in = await client.put(
                "/ai/v1/preferences", headers=headers, json={"ai_enabled": True}
            )
            assert opt_in.status_code == 200, opt_in.text
            first = await client.post(
                "/ai/v1/insights/lookup",
                headers=headers,
                json={"identity": {"content_hash": "quota-first"}, "bundle": {"title": "First"}},
            )
            second = await client.post(
                "/ai/v1/insights/lookup",
                headers=headers,
                json={"identity": {"content_hash": "quota-second"}, "bundle": {"title": "Second"}},
            )

    assert first.status_code == 200, first.text
    assert second.status_code == 429, second.text
    assert second.json() == {"detail": "monthly_ai_quota_exhausted"}
    assert provider_calls == ["POST"]
    insights = (await session.scalars(select(BookInsight.content_hash))).all()
    assert insights == ["quota-first"]
    counts = (
        await session.execute(
            text("SELECT count FROM ai_usage_monthly WHERE user_id = :account AND month = :month"),
            {"account": account, "month": now.date().replace(day=1)},
        )
    ).scalars()
    assert counts.all() == [1]
