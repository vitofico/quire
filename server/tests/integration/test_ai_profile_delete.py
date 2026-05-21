"""Integration tests for pr-δ — DELETE /ai/v1/profile.

Covers:
 * 204 idempotent when no reader_profiles row exists.
 * 204 + row removed when a profile exists.
 * 409 ``ai_not_opted_in`` when the caller has not opted in.
 * 503 ``ai_disabled`` when AI is enabled at the env level but no
   orchestrator is mounted on ``app.state`` (mirrors the existing
   mutating-handler guard).

PR-δ does NOT write audit-log rows; these tests deliberately make
no ``ai_generation_log`` assertions.
"""

from __future__ import annotations

import base64

import pytest
from sqlalchemy import select

from quire_server.api.ai_schemas import ReaderProfilePayload, ReaderStats
from quire_server.db.models import ReaderProfile, UserAIPreference

pytestmark = [pytest.mark.requires_ai]


def _basic_header(user: str, password: str = "p") -> dict[str, str]:
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()}


def _stub_payload() -> dict:
    return ReaderProfilePayload(
        stats=ReaderStats(
            total_books=0,
            finished_count=0,
            in_progress_count=0,
            abandoned_count=0,
        )
    ).model_dump()


async def _seed_profile(session, *, subject: str = "alice", tenant_id: str = "local") -> None:
    session.add(
        ReaderProfile(
            tenant_id=tenant_id,
            subject=subject,
            payload=_stub_payload(),
            schema_version=1,
            model_id="test-model",
            prompt_version="0",
            input_fingerprint=None,
        )
    )
    await session.commit()


async def _opt_in(session, *, user_id: str = "alice") -> None:
    session.add(UserAIPreference(user_id=user_id, ai_enabled=True))
    await session.commit()


async def test_delete_profile_idempotent_when_no_row(client_factory, configure_ai, app, session):
    """No reader_profiles row at (tenant, subject) — still 204."""
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(session)

        r = await client.delete("/ai/v1/profile", headers=_basic_header("alice"))

    assert r.status_code == 204, r.text
    # Row count remains 0.
    rows = (await session.execute(select(ReaderProfile))).scalars().all()
    assert rows == []


async def test_delete_profile_removes_existing_row(client_factory, configure_ai, app, session):
    """Seed a row, hit DELETE, assert it's gone."""
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(session)
        await _seed_profile(session)

        # Pre-condition: row exists.
        pre = (await session.execute(select(ReaderProfile))).scalars().all()
        assert len(pre) == 1

        r = await client.delete("/ai/v1/profile", headers=_basic_header("alice"))

    assert r.status_code == 204, r.text
    post = (await session.execute(select(ReaderProfile))).scalars().all()
    assert post == []


async def test_delete_profile_only_removes_callers_row(client_factory, configure_ai, app, session):
    """Idempotency must not be over-eager — only the caller's row is removed."""
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(session, user_id="alice")
        await _seed_profile(session, subject="alice")
        await _seed_profile(session, subject="bob")

        r = await client.delete("/ai/v1/profile", headers=_basic_header("alice"))

    assert r.status_code == 204, r.text
    rows = (await session.execute(select(ReaderProfile))).scalars().all()
    subjects = {row.subject for row in rows}
    assert subjects == {"bob"}


async def test_delete_profile_not_opted_in_returns_409(client_factory, configure_ai, app, session):
    """User pref ai_enabled=False — 409 ai_not_opted_in (Lock #10 / CC-1).

    Soft-assert on the detail suffix so a future rename of the literal
    (which is currently ``ai_not_opted_in``) doesn't ripple into this test.
    """
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        session.add(UserAIPreference(user_id="alice", ai_enabled=False))
        await session.commit()
        # Seed a row to confirm it's NOT removed when the opt-in gate fires.
        await _seed_profile(session)

        r = await client.delete("/ai/v1/profile", headers=_basic_header("alice"))

    assert r.status_code == 409, r.text
    detail = r.json()["detail"]
    assert isinstance(detail, str) and detail.endswith("not_opted_in")
    # Row still present — opt-in gate rejected before delete.
    rows = (await session.execute(select(ReaderProfile))).scalars().all()
    assert len(rows) == 1


async def test_delete_profile_ai_disabled_returns_503(client_factory, app, session):
    """AI is mounted (ai_enabled=true env) but the orchestrator is absent.

    This mirrors the boot path on a deploy where the AI client is not
    configured — every mutating /ai/v1/* handler returns 503 ai_disabled.
    """
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        # No configure_ai(app, ...) — orchestrator stays None.
        app.state.ai_orchestrator = None  # type: ignore[attr-defined]
        await _opt_in(session)

        r = await client.delete("/ai/v1/profile", headers=_basic_header("alice"))

    assert r.status_code == 503, r.text
    assert r.json()["detail"] == "ai_disabled"
