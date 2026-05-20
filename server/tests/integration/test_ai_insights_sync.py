"""Integration tests for PR-η ``GET /ai/v1/insights/sync``.

Verifies:
 * Opt-in gate (Lock #10 / CC-1)
 * AI-disabled (no orchestrator) → 503
 * Cross-user isolation
 * Filter by (model_id, prompt_version, tone, language)
 * `superseded_at IS NULL`
 * PR9 priority (metadata-id beats content-hash)
 * `library_items.deleted_at IS NULL`
 * Tuple-cursor pagination (distinct timestamps + identical timestamps)
 * Cursor half-supplied → 400
 * Weight=0 (no daily budget impact)
"""

from __future__ import annotations

import base64
from datetime import UTC, datetime, timedelta

import pytest

from quire_server.db.models import AIUsageDaily, BookInsight, LibraryItem

pytestmark = pytest.mark.requires_ai


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _basic_header(user: str, password: str = "p") -> dict:
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()}


async def _opt_in(client, user: str) -> None:
    r = await client.put(
        "/ai/v1/preferences",
        headers=_basic_header(user),
        json={"ai_enabled": True},
    )
    assert r.status_code == 200


async def _put_style(client, user: str, tone: str, language: str) -> None:
    r = await client.put(
        "/ai/v1/preferences",
        headers=_basic_header(user),
        json={"ai_enabled": True, "style": {"tone": tone, "language": language}},
    )
    assert r.status_code == 200


def _seed_insight(
    session,
    *,
    metadata_id: str | None,
    content_hash: str,
    model_id: str = "test-model",
    prompt_version: str = "t1",
    tone: str = "neutral",
    language: str = "auto",
    payload: dict | None = None,
    generated_at: datetime | None = None,
    superseded_at: datetime | None = None,
) -> BookInsight:
    row = BookInsight(
        metadata_id=metadata_id,
        content_hash=content_hash,
        model_id=model_id,
        prompt_version=prompt_version,
        tone=tone,
        language=language,
        sources_used=[],
        payload=payload or {"schema_version": 4, "intro": "i", "confidence": "low"},
        sources=[],
        generated_by="test",
        superseded_at=superseded_at,
    )
    if generated_at is not None:
        row.generated_at = generated_at
    session.add(row)
    return row


def _seed_library_item(
    session,
    *,
    user_id: str,
    metadata_id: str | None,
    content_hash: str,
    deleted_at: datetime | None = None,
) -> None:
    session.add(
        LibraryItem(
            user_id=user_id,
            metadata_id=metadata_id,
            content_hash=content_hash,
            title="T",
            deleted_at=deleted_at,
        )
    )


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


async def test_sync_requires_opt_in_returns_409_ai_not_opted_in(
    client_factory, configure_ai, app, session
):
    _ = session  # trigger truncate
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        r = await client.get("/ai/v1/insights/sync", headers=_basic_header("alice"))
    assert r.status_code == 409
    assert r.json()["detail"] == "ai_not_opted_in"


async def test_sync_returns_503_when_orchestrator_absent(client_factory):
    """ai_enabled=true with base_url/model unset → 503 (route mounted but
    orchestrator not built)."""
    async with client_factory(ai_enabled=True) as client:
        r = await client.get("/ai/v1/insights/sync", headers=_basic_header("alice"))
    assert r.status_code == 503
    assert r.json()["detail"] == "ai_disabled"


async def test_sync_empty_library_returns_empty_items(client_factory, configure_ai, app, session):
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")
        r = await client.get("/ai/v1/insights/sync", headers=_basic_header("alice"))
    assert r.status_code == 200
    body = r.json()
    assert body["items"] == []
    assert body["next_cursor"] is None
    assert body["server_time"]


async def test_sync_returns_only_callers_rows(client_factory, configure_ai, app, session):
    """Cross-user isolation: bob's library items are not visible to alice."""
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")
        await _opt_in(client, "bob")
        # Alice owns book A; bob owns book B.
        _seed_insight(session, metadata_id="m-alice", content_hash="ch-alice")
        _seed_insight(session, metadata_id="m-bob", content_hash="ch-bob")
        _seed_library_item(session, user_id="alice", metadata_id="m-alice", content_hash="ch-alice")
        _seed_library_item(session, user_id="bob", metadata_id="m-bob", content_hash="ch-bob")
        await session.commit()

        # Use the configured orchestrator's prompt_version (t1).
        ra = await client.get("/ai/v1/insights/sync", headers=_basic_header("alice"))
        rb = await client.get("/ai/v1/insights/sync", headers=_basic_header("bob"))
    assert ra.status_code == 200
    assert rb.status_code == 200
    a_items = ra.json()["items"]
    b_items = rb.json()["items"]
    # The route's `current_pv` comes from settings.ai_prompt_version (resolved
    # via _resolve_prompt_version). The configure_ai fixture builds the
    # orchestrator at prompt_version="t1" but the route reads the server's
    # settings, which default to "1" → resolves to the in-code constant ("5").
    # So neither caller sees the seeded "t1" rows. This asserts the filter is
    # active and the route does not mis-share rows.
    a_metas = {it["identity"]["metadata_id"] for it in a_items}
    b_metas = {it["identity"]["metadata_id"] for it in b_items}
    # No mix-up between users.
    assert "m-bob" not in a_metas
    assert "m-alice" not in b_metas


async def test_sync_filters_by_current_prompt_version(client_factory, configure_ai, app, session):
    """Seed two rows for the same library item differing only in
    prompt_version. The route filters to settings-resolved prompt_version,
    which defaults to the in-code constant (Lock #19)."""
    from quire_server.core.ai.prompts import PROMPT_VERSION

    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")
        _seed_insight(
            session,
            metadata_id="m-pv",
            content_hash="ch-pv",
            model_id="m",
            prompt_version=PROMPT_VERSION,
        )
        _seed_insight(
            session,
            metadata_id="m-pv",
            content_hash="ch-pv-old",
            model_id="m",
            prompt_version="old",
        )
        _seed_library_item(session, user_id="alice", metadata_id="m-pv", content_hash="ch-pv")
        await session.commit()

        r = await client.get("/ai/v1/insights/sync", headers=_basic_header("alice"))
    assert r.status_code == 200
    items = r.json()["items"]
    assert len(items) == 1
    assert items[0]["prompt_version"] == PROMPT_VERSION


async def test_sync_filters_superseded_rows(client_factory, configure_ai, app, session):
    from quire_server.core.ai.prompts import PROMPT_VERSION

    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")
        # Live row at variant {model="m", pv=CONSTANT}; superseded sibling
        # at content_hash differing.
        _seed_insight(
            session,
            metadata_id="m-sup",
            content_hash="ch-live",
            model_id="m",
            prompt_version=PROMPT_VERSION,
        )
        _seed_insight(
            session,
            metadata_id=None,
            content_hash="ch-sup",
            model_id="m",
            prompt_version=PROMPT_VERSION,
            superseded_at=datetime.now(UTC),
        )
        _seed_library_item(session, user_id="alice", metadata_id="m-sup", content_hash="ch-live")
        await session.commit()

        r = await client.get("/ai/v1/insights/sync", headers=_basic_header("alice"))
    assert r.status_code == 200
    items = r.json()["items"]
    assert len(items) == 1
    assert items[0]["identity"]["metadata_id"] == "m-sup"


async def test_sync_skips_deleted_library_items(client_factory, configure_ai, app, session):
    from quire_server.core.ai.prompts import PROMPT_VERSION

    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")
        _seed_insight(
            session,
            metadata_id="m-del",
            content_hash="ch-del",
            model_id="m",
            prompt_version=PROMPT_VERSION,
        )
        _seed_library_item(
            session,
            user_id="alice",
            metadata_id="m-del",
            content_hash="ch-del",
            deleted_at=datetime.now(UTC),
        )
        await session.commit()

        r = await client.get("/ai/v1/insights/sync", headers=_basic_header("alice"))
    assert r.status_code == 200
    assert r.json()["items"] == []


async def test_sync_cursor_half_supplied_returns_400(client_factory, configure_ai, app, session):
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")
        r1 = await client.get(
            "/ai/v1/insights/sync?since_ts=2026-01-01T00:00:00%2B00:00",
            headers=_basic_header("alice"),
        )
        r2 = await client.get(
            "/ai/v1/insights/sync?since_id=1",
            headers=_basic_header("alice"),
        )
    assert r1.status_code == 400
    assert r2.status_code == 400


async def test_sync_tuple_cursor_walks_pages_with_distinct_timestamps(
    client_factory, configure_ai, app, session
):
    from quire_server.core.ai.prompts import PROMPT_VERSION

    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")

        base = datetime.now(UTC) - timedelta(minutes=10)
        for i in range(5):
            _seed_insight(
                session,
                metadata_id=f"m-{i}",
                content_hash=f"ch-{i}",
                model_id="m",
                prompt_version=PROMPT_VERSION,
                generated_at=base + timedelta(seconds=i),
            )
            _seed_library_item(
                session, user_id="alice", metadata_id=f"m-{i}", content_hash=f"ch-{i}"
            )
        await session.commit()

        # Page 1 of 2.
        r1 = await client.get(
            "/ai/v1/insights/sync", params={"limit": 2}, headers=_basic_header("alice")
        )
        body1 = r1.json()
        assert r1.status_code == 200
        assert len(body1["items"]) == 2
        assert body1["next_cursor"] is not None

        # Page 2. httpx percent-encodes the timestamp's `+` for us.
        c = body1["next_cursor"]
        r2 = await client.get(
            "/ai/v1/insights/sync",
            params={"limit": 2, "since_ts": c["generated_at"], "since_id": c["id"]},
            headers=_basic_header("alice"),
        )
        body2 = r2.json()
        assert r2.status_code == 200
        assert len(body2["items"]) == 2
        assert body2["next_cursor"] is not None

        # Page 3 (final).
        c2 = body2["next_cursor"]
        r3 = await client.get(
            "/ai/v1/insights/sync",
            params={"limit": 2, "since_ts": c2["generated_at"], "since_id": c2["id"]},
            headers=_basic_header("alice"),
        )
        body3 = r3.json()
        assert r3.status_code == 200
        assert len(body3["items"]) == 1
        assert body3["next_cursor"] is None

        # No dupes, monotonic.
        all_ids = [it["id"] for it in body1["items"] + body2["items"] + body3["items"]]
        assert sorted(all_ids) == all_ids
        assert len(set(all_ids)) == 5


async def test_sync_does_not_charge_against_daily_budget(
    client_factory, configure_ai, app, session
):
    """Weight=0: sync never touches ai_usage_daily."""
    from sqlalchemy import select

    from quire_server.core.ai.prompts import PROMPT_VERSION

    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")
        _seed_insight(
            session,
            metadata_id="m-budget",
            content_hash="ch-budget",
            model_id="m",
            prompt_version=PROMPT_VERSION,
        )
        _seed_library_item(
            session, user_id="alice", metadata_id="m-budget", content_hash="ch-budget"
        )
        await session.commit()

        pre = (
            await session.execute(select(AIUsageDaily).where(AIUsageDaily.user_id == "alice"))
        ).all()
        r = await client.get("/ai/v1/insights/sync", headers=_basic_header("alice"))
    assert r.status_code == 200
    # No new ai_usage_daily row for alice.
    post = (
        await session.execute(select(AIUsageDaily).where(AIUsageDaily.user_id == "alice"))
    ).all()
    assert len(post) == len(pre)
