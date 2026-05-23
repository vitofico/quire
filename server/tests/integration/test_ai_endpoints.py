"""Integration tests for /ai/v1 endpoints.

Uses a real Postgres database (via testcontainers) and a fake AI provider
injected into the InsightOrchestrator so no real LLM calls are made.
"""

from __future__ import annotations

import base64
import json
from decimal import Decimal

import pytest
from sqlalchemy import select

from quire_server.db.models import BookInsight, LibraryItem

# All tests in this file hit /ai/v1/* and so require the ai router.
pytestmark = pytest.mark.requires_ai

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _basic_header(user: str, password: str = "p") -> dict:
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()}


def _ai_chat_response(payload: dict) -> dict:
    return {
        "id": "x",
        "model": "test-model",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": json.dumps(payload)},
            }
        ],
    }


# ---------------------------------------------------------------------------
# Tests (configure_ai is provided by tests/integration/conftest.py)
# ---------------------------------------------------------------------------


async def test_ai_router_not_mounted_when_disabled(client_factory):
    """PR-A: ai_enabled=false means the entire /ai/v1/* namespace is unmounted."""
    async with client_factory(ai_enabled=False) as client:
        r = await client.get("/ai/v1/config", headers=_basic_header("alice"))
    assert r.status_code == 404


async def test_config_endpoint_when_enabled_but_unconfigured(client_factory):
    """ai_enabled=true with base_url/model unset: router mounts, config reports unconfigured."""
    from quire_server.core.ai.prompts import PROMPT_VERSION

    async with client_factory(ai_enabled=True) as client:
        r = await client.get("/ai/v1/config", headers=_basic_header("alice"))
    assert r.status_code == 200
    body = r.json()
    assert body["configured"] is False
    assert body["base_url_host"] is None
    assert body["model_id"] is None
    # sources_enabled reflects the configured sources string regardless of
    # whether the provider is reachable — it's a static config view.
    assert body["sources_enabled"] == ["wikipedia", "openlibrary"]
    assert body["daily_budget"] == 200
    assert body["regen_daily_limit"] == 3
    # PR-η / Lock #24: prompt_version is the runtime-resolved value
    # (legacy "1" sentinel resolves to the in-code constant).
    assert body["prompt_version"] == PROMPT_VERSION


async def test_config_endpoint_when_enabled(client_factory):
    from quire_server.core.ai.prompts import PROMPT_VERSION

    async with client_factory(
        ai_enabled=True,
        ai_base_url="http://ollama.lan:11434/v1",
        ai_model="llama3.1:8b",
    ) as client:
        r = await client.get("/ai/v1/config", headers=_basic_header("alice"))
    assert r.status_code == 200
    body = r.json()
    assert body["configured"] is True
    assert body["base_url_host"] == "ollama.lan"
    assert body["model_id"] == "llama3.1:8b"
    assert body["daily_budget"] == 200
    assert body["regen_daily_limit"] == 3
    assert body["prompt_version"] == PROMPT_VERSION


async def test_config_prompt_version_honors_emergency_override(client_factory, monkeypatch):
    """Lock #24 + Lock #2: setting the env var to a non-default value pins it."""
    monkeypatch.setenv("QUIRE_SERVER_AI_PROMPT_VERSION", "4")
    async with client_factory(
        ai_enabled=True,
        ai_base_url="http://ollama.lan:11434/v1",
        ai_model="llama3.1:8b",
        ai_prompt_version="4",
    ) as client:
        r = await client.get("/ai/v1/config", headers=_basic_header("alice"))
    assert r.status_code == 200
    assert r.json()["prompt_version"] == "4"


async def test_lookup_blocked_when_not_opted_in(client_factory, configure_ai, app):
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        # Now that client_factory has populated app, install the fake AI.
        configure_ai(app, {"schema_version": 2, "intro": "ok", "confidence": "low"})
        r = await client.post(
            "/ai/v1/insights/lookup",
            headers=_basic_header("alice"),
            json={
                "identity": {"content_hash": "ch1"},
                "bundle": {"title": "Foundation"},
            },
        )
    assert r.status_code == 409
    assert r.json()["detail"] == "ai_not_opted_in"


async def test_lookup_generates_then_get_serves_from_cache(
    client_factory, configure_ai, app, session
):
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(
            app,
            {"schema_version": 2, "intro": "Foundational sci-fi.", "confidence": "high"},
        )

        # Opt alice in.
        r = await client.put(
            "/ai/v1/preferences",
            headers=_basic_header("alice"),
            json={"ai_enabled": True},
        )
        assert r.status_code == 200

        body = {
            "identity": {"metadata_id": "9780553293357", "content_hash": "ch1"},
            "bundle": {"title": "Foundation", "author": "Isaac Asimov"},
        }

        # Alice generates an insight.
        r1 = await client.post("/ai/v1/insights/lookup", headers=_basic_header("alice"), json=body)
        assert r1.status_code == 200
        assert r1.json()["payload"]["intro"] == "Foundational sci-fi."

        # Bob is not opted in: lookup must 409.
        r2 = await client.post("/ai/v1/insights/lookup", headers=_basic_header("bob"), json=body)
        assert r2.status_code == 409
        assert r2.json()["detail"] == "ai_not_opted_in"

        # GET path serves from cache without opt-in.
        r3 = await client.post(
            "/ai/v1/insights/get",
            headers=_basic_header("bob"),
            json={"identity": {"metadata_id": "9780553293357", "content_hash": "ch1"}},
        )
        assert r3.status_code == 200
        assert r3.json()["payload"]["intro"] == "Foundational sci-fi."


async def test_invalidate_drops_cache(client_factory, configure_ai, app, session):
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 2, "intro": "first version", "confidence": "low"})

        await client.put(
            "/ai/v1/preferences",
            headers=_basic_header("alice"),
            json={"ai_enabled": True},
        )

        await client.post(
            "/ai/v1/insights/lookup",
            headers=_basic_header("alice"),
            json={
                "identity": {"content_hash": "ch-inv"},
                "bundle": {"title": "X"},
            },
        )

        rows_before = (await session.execute(select(BookInsight))).scalars().all()
        assert len(rows_before) == 1

        r = await client.post(
            "/ai/v1/insights/invalidate",
            headers=_basic_header("alice"),
            json={"identity": {"content_hash": "ch-inv"}},
        )
        assert r.status_code == 200
        assert r.json()["deleted"] >= 1

        r2 = await client.post(
            "/ai/v1/insights/get",
            headers=_basic_header("alice"),
            json={"identity": {"content_hash": "ch-inv"}},
        )
        assert r2.status_code == 404


# ---------------------------------------------------------------------------
# Phase 0, task S-3: client-supplied metadata + deprecated server-side
# fallback. These tests pin the push-model contract on
# /ai/v1/insights/{lookup,regenerate}.
# ---------------------------------------------------------------------------


async def _opt_in(client, user: str = "alice") -> None:
    r = await client.put(
        "/ai/v1/preferences",
        headers=_basic_header(user),
        json={"ai_enabled": True},
    )
    assert r.status_code == 200


async def _seed_library_item(
    session,
    *,
    user_id: str,
    metadata_id: str | None,
    content_hash: str,
    title: str = "Foundation",
    authors: list[str] | None = None,
    series_name: str | None = None,
    series_index: Decimal | None = None,
    language: str | None = None,
    isbn: str | None = None,
    subjects: list[str] | None = None,
) -> None:
    session.add(
        LibraryItem(
            user_id=user_id,
            metadata_id=metadata_id,
            content_hash=content_hash,
            title=title,
            authors=authors if authors is not None else ["Isaac Asimov"],
            series_name=series_name,
            series_index=series_index,
            language=language,
            isbn=isbn,
            subjects=subjects if subjects is not None else [],
        )
    )
    await session.commit()


async def test_lookup_400_when_bundle_omitted_and_flag_off(
    client_factory, configure_ai, app, session
):
    """S-3: push-model default — clients MUST send their own metadata."""
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 2, "intro": "ok", "confidence": "low"})
        await _opt_in(client)

        r = await client.post(
            "/ai/v1/insights/lookup",
            headers=_basic_header("alice"),
            json={"identity": {"content_hash": "ch-no-meta"}},
        )
        assert r.status_code == 400
        assert r.json()["detail"] == "metadata_required"

        # No BookInsight row was written — the 400 short-circuits before
        # the orchestrator reserves quota or acquires a generation lock.
        rows = (await session.execute(select(BookInsight))).scalars().all()
        assert rows == []


async def test_regenerate_400_when_bundle_omitted_and_flag_off(
    client_factory, configure_ai, app, session
):
    """S-3: regenerate inherits the same push-model contract.

    Bundle resolution happens BEFORE the orchestrator supersedes any live
    row, so an unusable request leaves the existing row alone.
    """
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 2, "intro": "first", "confidence": "low"})
        await _opt_in(client)

        # Seed a live insight via the happy lookup path.
        r0 = await client.post(
            "/ai/v1/insights/lookup",
            headers=_basic_header("alice"),
            json={
                "identity": {"content_hash": "ch-regen"},
                "bundle": {"title": "X"},
            },
        )
        assert r0.status_code == 200

        live_before = (
            (await session.execute(select(BookInsight).where(BookInsight.superseded_at.is_(None))))
            .scalars()
            .all()
        )
        assert len(live_before) == 1

        # Now regenerate WITHOUT a bundle, with the flag off → 400.
        r1 = await client.post(
            "/ai/v1/insights/regenerate",
            headers=_basic_header("alice"),
            json={
                "identity": {"content_hash": "ch-regen"},
                "reason": "force",
            },
        )
        assert r1.status_code == 400
        assert r1.json()["detail"] == "metadata_required"

        # The existing live row was NOT superseded.
        live_after = (
            (await session.execute(select(BookInsight).where(BookInsight.superseded_at.is_(None))))
            .scalars()
            .all()
        )
        assert len(live_after) == 1
        assert live_after[0].id == live_before[0].id


async def test_lookup_falls_back_to_library_item_when_flag_on(
    client_factory, configure_ai, app, session
):
    """S-3: deprecated server-side fallback regression test.

    With ``ai_metadata_server_lookup_enabled=true``, an omitted bundle is
    reconstructed from the caller's ``library_items`` row. The
    ``series_name`` + ``series_index`` from that row drive the post-LLM
    ``payload.series`` override — proving the reconstructed bundle is
    actually used by ``_do_generate``, not just silently dropped.
    """
    async with client_factory(
        ai_enabled=True,
        ai_base_url="http://x",
        ai_model="m",
        ai_metadata_server_lookup_enabled=True,
        progress_enabled=True,
    ) as client:
        # Fake LLM returns NO `series` block; the bundle override must
        # supply it from LibraryItem.series_name.
        configure_ai(
            app,
            {"schema_version": 2, "intro": "Reconstructed.", "confidence": "low"},
        )
        await _opt_in(client)

        await _seed_library_item(
            session,
            user_id="alice",
            metadata_id="md-fallback",
            content_hash="ch-fallback",
            title="Foundation",
            authors=["Isaac Asimov"],
            series_name="Foundation Saga",
            series_index=Decimal("1"),
            language="en",
        )

        r = await client.post(
            "/ai/v1/insights/lookup",
            headers=_basic_header("alice"),
            json={"identity": {"metadata_id": "md-fallback", "content_hash": "ch-fallback"}},
        )
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["payload"]["intro"] == "Reconstructed."
        # The series override only fires when bundle.series_name is set.
        assert body["payload"]["series"]["name"] == "Foundation Saga"
        assert body["payload"]["series"]["position"] == 1


async def test_lookup_400_when_flag_on_but_no_library_item(
    client_factory, configure_ai, app, session
):
    """S-3: fallback can't fabricate metadata — no row, no insight."""
    async with client_factory(
        ai_enabled=True,
        ai_base_url="http://x",
        ai_model="m",
        ai_metadata_server_lookup_enabled=True,
        progress_enabled=True,
    ) as client:
        configure_ai(app, {"schema_version": 2, "intro": "x", "confidence": "low"})
        await _opt_in(client)

        r = await client.post(
            "/ai/v1/insights/lookup",
            headers=_basic_header("alice"),
            json={"identity": {"content_hash": "ch-missing"}},
        )
        assert r.status_code == 400
        assert r.json()["detail"] == "metadata_required"

        rows = (await session.execute(select(BookInsight))).scalars().all()
        assert rows == []


async def test_lookup_persists_identity_hash_version(client_factory, configure_ai, app, session):
    """S-3 + F-1: client-supplied identity_hash_version survives bundle
    plumbing and lands on the persisted BookInsight row."""
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 2, "intro": "v2 hash", "confidence": "low"})
        await _opt_in(client)

        r = await client.post(
            "/ai/v1/insights/lookup",
            headers=_basic_header("alice"),
            json={
                "identity": {
                    "metadata_id": "md-ihv",
                    "content_hash": "ch-ihv",
                    "identity_hash_version": 2,
                },
                "bundle": {"title": "T"},
            },
        )
        assert r.status_code == 200, r.text
        assert r.json()["identity_hash_version"] == 2

        row = (
            await session.execute(select(BookInsight).where(BookInsight.content_hash == "ch-ihv"))
        ).scalar_one()
        assert row.identity_hash_version == 2


async def test_lookup_cache_invariant_first_writer_wins(client_factory, configure_ai, app, session):
    """S-3: bundle does NOT participate in the cache key.

    Divergent bundles for the same canonical identity (and same
    ``model_id`` + ``prompt_version`` + ``tone`` + ``language``) share
    a single cache row. The first-writer determines the persisted
    payload; the second call is a pure cache hit. This is the documented
    Phase 0 limitation; bundle fingerprinting is a later task.
    """
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 2, "intro": "first writer", "confidence": "low"})
        await _opt_in(client)

        identity = {"content_hash": "ch-cache-invariant"}

        r1 = await client.post(
            "/ai/v1/insights/lookup",
            headers=_basic_header("alice"),
            json={
                "identity": identity,
                "bundle": {"title": "Original Title", "author": "A"},
            },
        )
        assert r1.status_code == 200
        assert r1.json()["payload"]["intro"] == "first writer"

        # Second call with a wildly different bundle should hit the cache
        # and return the FIRST writer's payload, NOT regenerate.
        r2 = await client.post(
            "/ai/v1/insights/lookup",
            headers=_basic_header("alice"),
            json={
                "identity": identity,
                "bundle": {"title": "Completely Different Book", "author": "B"},
            },
        )
        assert r2.status_code == 200
        assert r2.json()["payload"]["intro"] == "first writer"

        # And only one BookInsight row exists for the identity.
        rows = (
            (
                await session.execute(
                    select(BookInsight).where(BookInsight.content_hash == "ch-cache-invariant")
                )
            )
            .scalars()
            .all()
        )
        assert len(rows) == 1


async def test_regenerate_falls_back_to_library_item_when_flag_on(
    client_factory, configure_ai, app, session
):
    """S-3: regenerate's fallback path mirrors lookup's."""
    async with client_factory(
        ai_enabled=True,
        ai_base_url="http://x",
        ai_model="m",
        ai_metadata_server_lookup_enabled=True,
        progress_enabled=True,
    ) as client:
        configure_ai(app, {"schema_version": 2, "intro": "regen-fallback", "confidence": "low"})
        await _opt_in(client)

        await _seed_library_item(
            session,
            user_id="alice",
            metadata_id="md-regen-fb",
            content_hash="ch-regen-fb",
            title="Foundation",
            authors=["Isaac Asimov"],
        )

        r = await client.post(
            "/ai/v1/insights/regenerate",
            headers=_basic_header("alice"),
            json={
                "identity": {"metadata_id": "md-regen-fb", "content_hash": "ch-regen-fb"},
                "reason": "user requested a redo",
            },
        )
        assert r.status_code == 200, r.text
        assert r.json()["payload"]["intro"] == "regen-fallback"


async def test_regenerate_400_when_flag_on_but_no_library_item(
    client_factory, configure_ai, app, session
):
    """S-3: regenerate can't fabricate metadata either; existing live rows
    (if any) remain untouched."""
    async with client_factory(
        ai_enabled=True,
        ai_base_url="http://x",
        ai_model="m",
        ai_metadata_server_lookup_enabled=True,
        progress_enabled=True,
    ) as client:
        configure_ai(app, {"schema_version": 2, "intro": "n/a", "confidence": "low"})
        await _opt_in(client)

        r = await client.post(
            "/ai/v1/insights/regenerate",
            headers=_basic_header("alice"),
            json={
                "identity": {"content_hash": "ch-regen-missing"},
                "reason": "force",
            },
        )
        assert r.status_code == 400
        assert r.json()["detail"] == "metadata_required"
