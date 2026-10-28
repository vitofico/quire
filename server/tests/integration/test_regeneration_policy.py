"""Rejected regeneration must preserve the shared cache for every reader."""

import base64
from datetime import UTC, datetime

import httpx
import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker

from quire_server.core.ai.monthly_policy import MonthlyPolicy, PolicySettings
from quire_server.db.models import BookInsight

pytestmark = pytest.mark.requires_ai


def credentials(user):
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:p".encode()).decode()}


@pytest.mark.parametrize("policy_status,expected", [(200, 429), (503, 503)])
async def test_rejected_regeneration_preserves_shared_insight(
    client_factory, app, configure_ai, session, engine, monkeypatch, policy_status, expected
):
    now = datetime(2026, 10, 25, tzinfo=UTC)

    def policy_handler(request):
        assert request.headers["authorization"] == "Bearer " + "x" * 32
        return httpx.Response(
            policy_status,
            json={
                "version": 1,
                "account_id": "alice",
                "expires_at": int(now.timestamp()) + 120,
                "limits": {"monthly_jobs": 0},
            },
        )

    async with client_factory(
        ai_enabled=True, ai_base_url="http://fake", ai_model="synthetic"
    ) as client:
        orch = configure_ai(
            app, {"schema_version": 2, "intro": "Shared insight", "confidence": "low"}
        )
        await client.put(
            "/ai/v1/preferences", headers=credentials("alice"), json={"ai_enabled": True}
        )
        payload = {
            "identity": {"content_hash": "shared-regeneration-policy"},
            "bundle": {"title": "Synthetic shared book"},
        }
        original = await client.post(
            "/ai/v1/insights/lookup", headers=credentials("alice"), json=payload
        )
        assert original.status_code == 200, original.text
        provider_calls = []

        async def unexpected_provider_call(*args, **kwargs):
            provider_calls.append(True)
            raise AssertionError("Rejected regeneration must not call the model provider")

        monkeypatch.setattr(orch.ai, "chat_structured", unexpected_provider_call)
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(policy_handler)
        ) as policy_client:
            orch._monthly_policy = MonthlyPolicy(
                PolicySettings(url="http://policy", token="x" * 32),
                policy_client,
                async_sessionmaker(engine),
                clock=lambda: now,
            )
            rejected = await client.post(
                "/ai/v1/insights/regenerate",
                headers=credentials("alice"),
                json=payload | {"reason": "Synthetic regeneration request"},
            )
        assert rejected.status_code == expected, rejected.text
        rows = (await session.execute(select(BookInsight))).scalars().all()
        assert len(rows) == 1
        assert rows[0].superseded_at is None
        other_reader = await client.post(
            "/ai/v1/insights/get",
            headers=credentials("bob"),
            json={"identity": payload["identity"]},
        )
        assert other_reader.status_code == 200, other_reader.text
        assert other_reader.json()["payload"]["intro"] == "Shared insight"
        assert provider_calls == []


async def test_provider_timeout_preserves_shared_insight(
    client_factory, app, configure_ai, session, monkeypatch
):
    from quire_server.core.ai.client import ProviderTimeout

    async with client_factory(
        ai_enabled=True, ai_base_url="http://fake", ai_model="synthetic"
    ) as client:
        orch = configure_ai(
            app, {"schema_version": 2, "intro": "Original insight", "confidence": "low"}
        )
        await client.put(
            "/ai/v1/preferences", headers=credentials("alice"), json={"ai_enabled": True}
        )
        payload = {
            "identity": {"content_hash": "shared-regeneration-timeout"},
            "bundle": {"title": "Synthetic shared book"},
        }
        original = await client.post(
            "/ai/v1/insights/lookup", headers=credentials("alice"), json=payload
        )
        assert original.status_code == 200, original.text

        async def timeout(**kwargs):
            raise ProviderTimeout("Synthetic provider timeout")

        monkeypatch.setattr(orch.ai, "chat_structured", timeout)
        failed = await client.post(
            "/ai/v1/insights/regenerate",
            headers=credentials("alice"),
            json=payload | {"reason": "Synthetic correction"},
        )
        assert failed.status_code == 504, failed.text
        rows = (await session.execute(select(BookInsight))).scalars().all()
        assert len(rows) == 1
        assert rows[0].superseded_at is None
        other_reader = await client.post(
            "/ai/v1/insights/get",
            headers=credentials("bob"),
            json={"identity": payload["identity"]},
        )
        assert other_reader.status_code == 200, other_reader.text
        assert other_reader.json()["payload"]["intro"] == "Original insight"


@pytest.mark.parametrize("existing", [False, True])
@pytest.mark.parametrize("is_regen", [False, True])
async def test_daily_admission_is_atomic_across_requests(engine, session, existing, is_regen):
    """Different books/workers have independent sessions and must share a cap."""
    import asyncio

    from quire_server.core.ai.service import InsightOrchestrator, QuotaExceeded
    from quire_server.db.models import AIUsageDaily

    factory = async_sessionmaker(engine, expire_on_commit=False)
    user = "daily-quota-race"
    today = datetime.now(UTC).date()
    if existing:
        session.add(AIUsageDaily(user_id=user, day=today, count=1, regen_count=1))
        await session.commit()
    # Admission is independent of provider wiring and book-specific locks.
    orch = object.__new__(InsightOrchestrator)
    orch._daily_budget = 2
    orch._regen_daily_limit = 2
    orch._monthly_policy = None

    async def reserve():
        async with factory() as independent:
            try:
                await orch._reserve_budget(independent, user_id=user, is_regen=is_regen)
                return "admitted"
            except QuotaExceeded:
                return "rejected"

    outcomes = await asyncio.gather(*(reserve() for _ in range(8)))
    remaining = 1 if existing else 2
    assert outcomes.count("admitted") == remaining
    assert outcomes.count("rejected") == 8 - remaining
    row = await session.scalar(select(AIUsageDaily).where(AIUsageDaily.user_id == user))
    await session.refresh(row)
    assert row.count == 2
    assert row.regen_count == (2 if is_regen else int(existing))


async def test_profile_admission_and_book_reservation_share_initial_usage_row(engine, session):
    """First profile admission must not race creation by a different book."""
    import asyncio
    from types import SimpleNamespace

    from quire_server.core.ai.service import InsightOrchestrator
    from quire_server.db.models import AIUsageDaily

    factory = async_sessionmaker(engine, expire_on_commit=False)
    principal = SimpleNamespace(subject="profile-book-first-use")
    orch = object.__new__(InsightOrchestrator)
    orch._daily_budget = 10
    orch._regen_daily_limit = 3
    orch._monthly_policy = None
    orch._profile_refresh_daily_limit = 3

    async def profile_admission():
        async with factory() as independent:
            await orch._enforce_profile_daily_cap(independent, principal)
            # No external work or successful charge has happened yet.

    async def book_reservation():
        async with factory() as independent:
            await orch._reserve_budget(independent, user_id=principal.subject, is_regen=False)

    await asyncio.gather(*(profile_admission() for _ in range(4)), book_reservation())
    row = await session.scalar(
        select(AIUsageDaily).where(AIUsageDaily.user_id == principal.subject)
    )
    assert row.count == 1
    assert row.regen_count == 0
    assert row.profile_count == 0


@pytest.mark.parametrize("existing", [False, True])
async def test_concurrent_successful_profile_charges_preserve_book_counts(
    engine, session, existing
):
    import asyncio
    from types import SimpleNamespace

    from quire_server.core.ai.service import InsightOrchestrator
    from quire_server.db.models import AIUsageDaily

    factory = async_sessionmaker(engine, expire_on_commit=False)
    principal = SimpleNamespace(subject="profile-increment-race")
    if existing:
        session.add(
            AIUsageDaily(
                user_id=principal.subject,
                day=datetime.now(UTC).date(),
                count=5,
                regen_count=2,
                profile_count=1,
            )
        )
        await session.commit()
    orch = object.__new__(InsightOrchestrator)

    async def charge():
        async with factory() as independent:
            await orch._increment_profile_count(independent, principal)
            await independent.commit()

    await asyncio.gather(*(charge() for _ in range(8)))
    row = await session.scalar(
        select(AIUsageDaily).where(AIUsageDaily.user_id == principal.subject)
    )
    assert row.count == (5 if existing else 0)
    assert row.regen_count == (2 if existing else 0)
    assert row.profile_count == (9 if existing else 8)


async def test_uncapped_first_profile_charge_can_race_book_reservation(engine, session):
    """Disabling profile cap skips row initialization but must preserve book spend."""
    import asyncio
    from types import SimpleNamespace

    from quire_server.core.ai.service import InsightOrchestrator
    from quire_server.db.models import AIUsageDaily

    factory = async_sessionmaker(engine, expire_on_commit=False)
    principal = SimpleNamespace(subject="uncapped-profile-book-first-use")
    orch = object.__new__(InsightOrchestrator)
    orch._daily_budget = 10
    orch._regen_daily_limit = 3
    orch._monthly_policy = None
    orch._profile_refresh_daily_limit = 0

    async def successful_profile():
        async with factory() as independent:
            await orch._enforce_profile_daily_cap(independent, principal)
            await orch._increment_profile_count(independent, principal)
            await independent.commit()

    async def book_reservation():
        async with factory() as independent:
            await orch._reserve_budget(independent, user_id=principal.subject, is_regen=False)

    await asyncio.gather(*(successful_profile() for _ in range(4)), book_reservation())
    row = await session.scalar(
        select(AIUsageDaily).where(AIUsageDaily.user_id == principal.subject)
    )
    assert row.count == 1
    assert row.regen_count == 0
    assert row.profile_count == 4
