"""Integration tests for PR-ζ ``POST /ai/v1/insights/promote``.

Locks honored:
 * #1 (row-copy + alias-link, alias is idempotency anchor)
 * #10 (``409 {"detail":"ai_not_opted_in"}``)
 * #11 amendment (pr-β wire-up: stdout audit + ``kind='promote'`` row in
   ai_generation_log once ai_006 is applied)
 * #13 (caller sequencing — server-side simulated by manually inserting
   library_items rows before promote)
 * #23 (``generated_at = NOW()`` on copy)
"""

from __future__ import annotations

import base64
import logging
from datetime import UTC, datetime

import pytest
from sqlalchemy import select

from quire_server.db.models import (
    AIGenerationLog,
    BookInsight,
    BookTheme,
    InsightIdentityAlias,
    LibraryItem,
)

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


async def _seed_source_insight(
    session,
    *,
    from_metadata_id: str,
    content_hash: str,
    model_id: str,
    prompt_version: str,
    tone: str = "neutral",
    language: str = "auto",
    payload: dict | None = None,
    themes: list[str] | None = None,
) -> BookInsight:
    row = BookInsight(
        metadata_id=from_metadata_id,
        content_hash=content_hash,
        model_id=model_id,
        prompt_version=prompt_version,
        tone=tone,
        language=language,
        sources_used=["wikipedia"],
        payload=payload or {"schema_version": 4, "intro": "i", "confidence": "low"},
        sources=[],
        generated_by="test",
    )
    session.add(row)
    await session.flush()
    for t in themes or []:
        session.add(BookTheme(book_insight_id=row.id, theme=t, confidence=1.0))
    await session.commit()
    await session.refresh(row)
    return row


async def _seed_library_item(
    session,
    *,
    user_id: str,
    metadata_id: str,
    content_hash: str,
) -> None:
    session.add(
        LibraryItem(
            user_id=user_id,
            metadata_id=metadata_id,
            content_hash=content_hash,
            title="T",
        )
    )
    await session.commit()


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


async def test_promote_copies_row_and_writes_alias(client_factory, configure_ai, app, session):
    """Lock #1 + Lock #23 happy path."""
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")

        await _seed_source_insight(
            session,
            from_metadata_id="opds-href:abc",
            content_hash="ch-catalog",
            model_id="test-model",
            prompt_version="t1",
            themes=["mystery", "noir"],
        )
        await _seed_library_item(
            session,
            user_id="alice",
            metadata_id="urn:isbn:9780000000001",
            content_hash="ch-downloaded",
        )

        test_start = datetime.now(UTC)
        r = await client.post(
            "/ai/v1/insights/promote",
            headers=_basic_header("alice"),
            json={
                "from": {"metadata_id": "opds-href:abc"},
                "to": {
                    "metadata_id": "urn:isbn:9780000000001",
                    "content_hash": "ch-downloaded",
                },
                "tone": "neutral",
                "language": "auto",
            },
        )

    assert r.status_code == 200, r.text
    body = r.json()
    assert body["promoted"] is True
    assert body["already_promoted"] is False
    new_id = body["insight_id"]
    assert new_id is not None

    # Row-copy assertions.
    new_row = (
        await session.execute(select(BookInsight).where(BookInsight.id == new_id))
    ).scalar_one()
    assert new_row.metadata_id == "urn:isbn:9780000000001"
    assert new_row.content_hash == "ch-downloaded"
    assert new_row.model_id == "test-model"
    assert new_row.prompt_version == "t1"
    assert new_row.payload["intro"] == "i"
    assert new_row.generated_by == "promote:alice"
    assert new_row.generated_at >= test_start  # Lock #23

    # Theme copy.
    themes = (
        (await session.execute(select(BookTheme.theme).where(BookTheme.book_insight_id == new_id)))
        .scalars()
        .all()
    )
    assert sorted(themes) == ["mystery", "noir"]

    # Alias write.
    aliases = (
        (
            await session.execute(
                select(InsightIdentityAlias).where(
                    InsightIdentityAlias.source == "promoted_on_download",
                    InsightIdentityAlias.user_id == "alice",
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(aliases) == 1
    a = aliases[0]
    assert a.alias_scheme == "metadata_id"
    assert a.alias_value == "opds-href:abc"
    assert a.canonical_scheme == "metadata_id"
    assert a.canonical_value == "urn:isbn:9780000000001"


async def test_promote_idempotent_via_alias(client_factory, configure_ai, app, session):
    """Lock #1: second call with same args returns same insight_id, no duplicates."""
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")
        await _seed_source_insight(
            session,
            from_metadata_id="opds-href:idem",
            content_hash="ch-cat",
            model_id="test-model",
            prompt_version="t1",
        )
        await _seed_library_item(
            session, user_id="alice", metadata_id="md-idem", content_hash="ch-dl"
        )

        body = {
            "from": {"metadata_id": "opds-href:idem"},
            "to": {"metadata_id": "md-idem", "content_hash": "ch-dl"},
            "tone": "neutral",
            "language": "auto",
        }
        r1 = await client.post("/ai/v1/insights/promote", headers=_basic_header("alice"), json=body)
        r2 = await client.post("/ai/v1/insights/promote", headers=_basic_header("alice"), json=body)

    assert r1.status_code == 200
    assert r2.status_code == 200
    assert r1.json()["already_promoted"] is False
    assert r2.json()["already_promoted"] is True
    assert r1.json()["insight_id"] == r2.json()["insight_id"]

    # No duplicate alias rows.
    aliases = (
        (
            await session.execute(
                select(InsightIdentityAlias).where(
                    InsightIdentityAlias.source == "promoted_on_download",
                    InsightIdentityAlias.alias_value == "opds-href:idem",
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(aliases) == 1


async def test_promote_different_variant_recopies_under_existing_alias(
    client_factory, configure_ai, app, session
):
    """Lock #1 critical case: same alias, new (tone, language) creates a new row."""
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")
        # Two source rows: one per (tone, language) the user will request.
        await _seed_source_insight(
            session,
            from_metadata_id="opds-href:vv",
            content_hash="ch-cat",
            model_id="test-model",
            prompt_version="t1",
            tone="neutral",
            language="auto",
        )
        await _seed_source_insight(
            session,
            from_metadata_id="opds-href:vv",
            content_hash="ch-cat",
            model_id="test-model",
            prompt_version="t1",
            tone="scholarly",
            language="auto",
        )
        await _seed_library_item(
            session, user_id="alice", metadata_id="md-vv", content_hash="ch-dl"
        )
        from_id = {"metadata_id": "opds-href:vv"}
        to_id = {"metadata_id": "md-vv", "content_hash": "ch-dl"}

        r1 = await client.post(
            "/ai/v1/insights/promote",
            headers=_basic_header("alice"),
            json={"from": from_id, "to": to_id, "tone": "neutral", "language": "auto"},
        )
        r2 = await client.post(
            "/ai/v1/insights/promote",
            headers=_basic_header("alice"),
            json={"from": from_id, "to": to_id, "tone": "scholarly", "language": "auto"},
        )

    assert r1.status_code == 200
    assert r2.status_code == 200
    assert r1.json()["already_promoted"] is False
    assert r2.json()["already_promoted"] is False  # NEW row at the new variant
    assert r1.json()["insight_id"] != r2.json()["insight_id"]

    # Alias still single-row.
    aliases = (
        (
            await session.execute(
                select(InsightIdentityAlias).where(
                    InsightIdentityAlias.source == "promoted_on_download",
                    InsightIdentityAlias.alias_value == "opds-href:vv",
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(aliases) == 1

    # Two distinct rows at `to`, one per tone.
    dst_rows = (
        (await session.execute(select(BookInsight).where(BookInsight.metadata_id == "md-vv")))
        .scalars()
        .all()
    )
    tones = sorted(r.tone for r in dst_rows)
    assert tones == ["neutral", "scholarly"]


async def test_promote_with_no_source_row_returns_204(client_factory, configure_ai, app, session):
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")
        await _seed_library_item(
            session, user_id="alice", metadata_id="md-204", content_hash="ch-dl"
        )
        r = await client.post(
            "/ai/v1/insights/promote",
            headers=_basic_header("alice"),
            json={
                "from": {"metadata_id": "opds-href:does-not-exist"},
                "to": {"metadata_id": "md-204", "content_hash": "ch-dl"},
            },
        )
    assert r.status_code == 204


async def test_promote_cross_user_returns_403(client_factory, configure_ai, app, session):
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")
        await _seed_source_insight(
            session,
            from_metadata_id="opds-href:cross",
            content_hash="ch-cat",
            model_id="test-model",
            prompt_version="t1",
        )
        # bob owns the library_items row at `to`; alice tries to promote.
        await _seed_library_item(
            session, user_id="bob", metadata_id="md-cross", content_hash="ch-dl"
        )
        r = await client.post(
            "/ai/v1/insights/promote",
            headers=_basic_header("alice"),
            json={
                "from": {"metadata_id": "opds-href:cross"},
                "to": {"metadata_id": "md-cross", "content_hash": "ch-dl"},
            },
        )
    assert r.status_code == 403
    assert r.json()["detail"] == "not_owned"


async def test_promote_not_opted_in_returns_409_ai_not_opted_in(
    client_factory, configure_ai, app, session
):
    """Lock #10: exact body literal."""
    # `session` fixture requested only to trigger the auto-truncate hook so
    # alice's opt-in from a prior test is wiped before this one runs.
    _ = session
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        r = await client.post(
            "/ai/v1/insights/promote",
            headers=_basic_header("alice"),
            json={
                "from": {"metadata_id": "a"},
                "to": {"metadata_id": "b", "content_hash": "c"},
            },
        )
    assert r.status_code == 409
    assert r.json()["detail"] == "ai_not_opted_in"


async def test_promote_rate_limit(client_factory, configure_ai, app, session, monkeypatch):
    """101st call hits the daily limit and returns 429 with Retry-After."""
    monkeypatch.setenv("QUIRE_SERVER_AI_PROMOTE_DAILY_LIMIT", "2")
    async with client_factory(
        ai_enabled=True,
        ai_base_url="http://x",
        ai_model="m",
        ai_promote_daily_limit=2,
    ) as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")
        await _seed_source_insight(
            session,
            from_metadata_id="opds-href:rl",
            content_hash="ch-cat",
            model_id="test-model",
            prompt_version="t1",
        )
        await _seed_library_item(
            session, user_id="alice", metadata_id="md-rl", content_hash="ch-dl"
        )
        body = {
            "from": {"metadata_id": "opds-href:rl"},
            "to": {"metadata_id": "md-rl", "content_hash": "ch-dl"},
        }
        r1 = await client.post("/ai/v1/insights/promote", headers=_basic_header("alice"), json=body)
        r2 = await client.post("/ai/v1/insights/promote", headers=_basic_header("alice"), json=body)
        r3 = await client.post("/ai/v1/insights/promote", headers=_basic_header("alice"), json=body)

    assert r1.status_code == 200
    assert r2.status_code == 200
    assert r3.status_code == 429
    assert "Retry-After" in r3.headers


async def test_promote_emits_stdout_audit_and_db_row(
    client_factory, configure_ai, app, session, caplog
):
    """Lock #11 amendment (pr-β wire-up): the stdout audit line is retained for
    operator-grep convenience, AND a ``kind='promote'`` row is written to
    ai_generation_log once ai_006 lands. Pre-pr-β behavior was stdout-only;
    this test was updated when pr-β wired _log_generation through the promote
    path.
    """
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")
        await _seed_source_insight(
            session,
            from_metadata_id="opds-href:audit",
            content_hash="ch-cat",
            model_id="test-model",
            prompt_version="t1",
        )
        await _seed_library_item(
            session, user_id="alice", metadata_id="md-audit", content_hash="ch-dl"
        )
        # Snapshot ai_generation_log BEFORE the promote so we can assert on the
        # delta (other ops emit their own rows).
        pre = set((await session.execute(select(AIGenerationLog.id))).scalars().all())
        with caplog.at_level(logging.INFO, logger="quire_server.core.ai.service"):
            r = await client.post(
                "/ai/v1/insights/promote",
                headers=_basic_header("alice"),
                json={
                    "from": {"metadata_id": "opds-href:audit"},
                    "to": {"metadata_id": "md-audit", "content_hash": "ch-dl"},
                },
            )
    assert r.status_code == 200
    # Stdout audit line still present (retained alongside the DB row).
    msgs = [rec.getMessage() for rec in caplog.records]
    assert any("event=ai.promote" in m and "outcome=copied" in m for m in msgs), msgs
    # Exactly one new ai_generation_log row, kind='promote', status='hit'.
    post_rows = (await session.execute(select(AIGenerationLog))).scalars().all()
    new_rows = [r for r in post_rows if r.id not in pre]
    assert len(new_rows) == 1
    row = new_rows[0]
    assert row.kind == "promote"
    assert row.status == "hit"
    assert row.book_insight_id is not None
    assert row.subject == "alice"
    # Model id + prompt_version are copied from the source insight row.
    assert row.model_id == "test-model"
    assert row.prompt_version == "t1"


async def test_promote_copied_row_picks_up_now_generated_at(
    client_factory, configure_ai, app, session
):
    """Lock #23: ``generated_at = NOW()`` on the copy (covered indirectly in
    the happy-path test; this is the focused regression to prevent silent
    drift)."""
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        await _opt_in(client, "alice")
        # Source row's generated_at is set by ``server_default=NOW()`` at
        # insert time; capture it for the comparison.
        src = await _seed_source_insight(
            session,
            from_metadata_id="opds-href:lock23",
            content_hash="ch-cat",
            model_id="test-model",
            prompt_version="t1",
        )
        src_generated_at = src.generated_at
        await _seed_library_item(
            session, user_id="alice", metadata_id="md-23", content_hash="ch-dl"
        )

        r = await client.post(
            "/ai/v1/insights/promote",
            headers=_basic_header("alice"),
            json={
                "from": {"metadata_id": "opds-href:lock23"},
                "to": {"metadata_id": "md-23", "content_hash": "ch-dl"},
            },
        )
    assert r.status_code == 200
    new_id = r.json()["insight_id"]
    new_row = (
        await session.execute(select(BookInsight).where(BookInsight.id == new_id))
    ).scalar_one()
    assert new_row.generated_at >= src_generated_at
    # And the lineage is recorded.
    assert new_row.previous_insight_ids == [src.id]
