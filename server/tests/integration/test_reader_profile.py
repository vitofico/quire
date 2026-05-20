"""Integration tests for pr-α — Reader Profile substrate.

Covers:
 * `_compute_reader_stats` deterministic results.
 * `pick_priority` regression (REJECT (e)) — metadata-id beats content-hash
   even when both candidates share the same tie value on a naive
   `(metadata_id IS NOT NULL) DESC` ordering.
 * `GET /ai/v1/profile` cache-only semantics: 404 missing, 200 present,
   200 even for opted-out callers (no opt-in gate on the read endpoint).
 * Terminal-state invariant: DB check constraint, push write path clears
   the opposite flag, push preserves percent on the abandon transition,
   pull defensive read drops `abandoned_at` when both are set on a
   corrupt row.
 * Server LWW guard drops stale client_updated_at on abandon push.
 * `input_fingerprint` column is VARCHAR(16) — width violation on >16.
"""

from __future__ import annotations

import base64
import logging
import uuid
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import inspect, select, text
from sqlalchemy.exc import DBAPIError, IntegrityError

from quire_server.api.ai_schemas import (
    AuthorCount,
    ReaderProfilePayload,
    ReaderStats,
)
from quire_server.core.ai.service import _compute_reader_stats
from quire_server.db.models import (
    BookInsight,
    BookTheme,
    Document,
    LibraryItem,
    Progress,
    ReaderProfile,
    UserAIPreference,
)

pytestmark = [pytest.mark.requires_ai, pytest.mark.requires_progress]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _basic_header(user: str, password: str = "p") -> dict[str, str]:
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()}


async def _seed_library_item(
    session,
    *,
    user_id: str,
    metadata_id: str | None,
    content_hash: str,
    title: str = "T",
    authors: list[str] | None = None,
) -> LibraryItem:
    item = LibraryItem(
        user_id=user_id,
        metadata_id=metadata_id,
        content_hash=content_hash,
        title=title,
        authors=authors or [],
    )
    session.add(item)
    await session.flush()
    return item


async def _seed_document(
    session,
    *,
    user_id: str,
    metadata_id: str | None,
    content_hash: str,
) -> Document:
    doc = Document(user_id=user_id, metadata_id=metadata_id, content_hash=content_hash)
    session.add(doc)
    await session.flush()
    return doc


async def _seed_progress(
    session,
    *,
    document_pk: int,
    percent: float = 0.0,
    finished: bool = False,
    abandoned: bool = False,
    when: datetime | None = None,
) -> Progress:
    when = when or datetime.now(UTC)
    p = Progress(
        document_pk=document_pk,
        locator="{}",
        percent=percent,
        client_updated_at=when,
        finished_at=when if finished else None,
        abandoned_at=when if abandoned else None,
    )
    session.add(p)
    await session.flush()
    return p


async def _seed_insight(
    session,
    *,
    metadata_id: str | None,
    content_hash: str,
    themes: list[tuple[str, float]] | None = None,
) -> BookInsight:
    row = BookInsight(
        metadata_id=metadata_id,
        content_hash=content_hash,
        model_id="test-model",
        prompt_version="t1",
        tone="neutral",
        language="auto",
        sources_used=[],
        payload={"schema_version": 4, "intro": "i", "confidence": "low"},
        sources=[],
        generated_by="test",
    )
    session.add(row)
    await session.flush()
    for theme, confidence in themes or []:
        session.add(BookTheme(book_insight_id=row.id, theme=theme, confidence=confidence))
    await session.flush()
    return row


# ---------------------------------------------------------------------------
# _compute_reader_stats
# ---------------------------------------------------------------------------


async def test_compute_reader_stats_fixture(session):
    """5 books: 3 finished, 1 abandoned (60%), 1 in-progress (30%). One book
    has theme `noir`, two books have theme `dystopia` (one finished, one
    not). One book has theme `noir` and is finished. Two books by Le Guin,
    one by Stross.
    """
    user = "alice"
    # 5 books, deterministic content_hashes
    books = [
        # (content_hash, metadata_id, authors, finished, abandoned, percent, themes)
        ("h1", "m1", ["Le Guin"], True, False, 1.0, [("noir", 1.0)]),
        ("h2", "m2", ["Le Guin"], True, False, 1.0, [("dystopia", 1.0)]),
        ("h3", "m3", ["Stross"], True, False, 1.0, [("dystopia", 1.0)]),
        ("h4", "m4", ["Wells"], False, True, 0.6, []),  # abandoned at 60%
        ("h5", "m5", ["Verne"], False, False, 0.3, []),
    ]
    # Sentinel: book h2 is dystopia AND finished, h3 is dystopia AND finished.
    # We want a non-100% finish-rate on at least one theme. Change h3 so
    # it has dystopia but is NOT finished.
    books = [
        ("h1", "m1", ["Le Guin"], True, False, 1.0, [("noir", 1.0)]),
        ("h2", "m2", ["Le Guin"], True, False, 1.0, [("dystopia", 1.0)]),
        ("h3", "m3", ["Stross"], False, False, 0.5, [("dystopia", 1.0)]),
        ("h4", "m4", ["Wells"], False, True, 0.6, []),
        ("h5", "m5", ["Verne"], False, False, 0.3, []),
    ]
    for ch, mid, authors, finished, abandoned, percent, themes in books:
        await _seed_library_item(
            session,
            user_id=user,
            metadata_id=mid,
            content_hash=ch,
            authors=authors,
        )
        doc = await _seed_document(session, user_id=user, metadata_id=mid, content_hash=ch)
        # Only seed Progress if the book has any progress (h5 has 30%).
        # Books with no progress row never appear in counts.
        if finished or abandoned or percent > 0:
            await _seed_progress(
                session,
                document_pk=doc.pk,
                percent=percent,
                finished=finished,
                abandoned=abandoned,
            )
        if themes:
            await _seed_insight(
                session,
                metadata_id=mid,
                content_hash=ch,
                themes=themes,
            )

    stats = await _compute_reader_stats(session, user)

    assert stats.total_books == 5
    assert stats.finished_count == 2  # h1, h2
    assert stats.in_progress_count == 2  # h3 (50%), h5 (30%)
    assert stats.abandoned_count == 1  # h4
    assert stats.avg_session_minutes is None
    # noir: 1 book, finished. dystopia: 2 books, 1 finished -> 0.5.
    assert stats.finish_rate_by_theme == {"noir": 1.0, "dystopia": 0.5}
    # Le Guin: 2, Stross: 1, Wells: 1, Verne: 1.
    assert stats.most_read_authors[:1] == [AuthorCount(name="Le Guin", count=2)]
    names = {a.name for a in stats.most_read_authors}
    assert "Le Guin" in names and "Stross" in names
    # pr-α leaves at 0; pr-β populates.
    assert stats.books_with_themes_count == 0


async def test_compute_reader_stats_pick_priority_regression(session):
    """REJECT (e) regression: `pick_priority` MUST pick the metadata-id match.

    The library_item has metadata_id="m-A" and content_hash="h-X".
    Two BookInsight rows:
      - Row A: metadata_id="m-B" (different), content_hash="h-X"
               (matches via content). Theme `alpha`, finished_at set later.
      - Row B: metadata_id="m-A" (matches via metadata), content_hash="h-Y"
               (different). Theme `beta`, NOT finished.

    Under the correct `pick_priority` (case expression):
      - Row B wins because its metadata_id matches the library item's.
      - finish_rate_by_theme contains `beta` only.

    Under the WRONG naive ordering `(metadata_id IS NOT NULL) DESC` both
    rows tie (both have non-null metadata_id), so order falls back to
    `generated_at DESC` — Row B happens to be older, so Row A wins,
    `alpha` appears, and `beta` does not. This test FAILS under the bug.
    """
    user = "bob"
    await _seed_library_item(
        session,
        user_id=user,
        metadata_id="m-A",
        content_hash="h-X",
        authors=["Z"],
    )
    # Book is not finished (we just want to assert pick selects Row B).
    await _seed_document(session, user_id=user, metadata_id="m-A", content_hash="h-X")
    # Row A generated LATER so naive ordering picks it.
    await _seed_insight(
        session,
        metadata_id="m-B",
        content_hash="h-X",
        themes=[("alpha", 1.0)],
    )
    # Bump Row A generated_at to be max.
    row_a = (
        await session.execute(select(BookInsight).where(BookInsight.metadata_id == "m-B"))
    ).scalar_one()
    row_a.generated_at = datetime.now(UTC) + timedelta(days=1)
    # Row B - metadata match, earlier generated_at.
    await _seed_insight(
        session,
        metadata_id="m-A",
        content_hash="h-Y",
        themes=[("beta", 1.0)],
    )
    await session.flush()

    # Mark the picked book finished so finish_rate is computable.
    # We don't have a Progress row yet — book is in-progress (no progress at all).
    # finish_rate counts a theme as long as the picked insight exists; the
    # finish ratio uses the LEFT JOIN to Progress, so unfinished books still
    # appear in the denominator. Picked theme = `beta` only.
    stats = await _compute_reader_stats(session, user)
    assert "beta" in stats.finish_rate_by_theme
    assert "alpha" not in stats.finish_rate_by_theme


# ---------------------------------------------------------------------------
# GET /ai/v1/profile
# ---------------------------------------------------------------------------


async def test_get_profile_404_when_missing(client_factory, configure_ai, app, session):
    _ = session
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})
        r = await client.get("/ai/v1/profile", headers=_basic_header("alice"))
    assert r.status_code == 404
    assert r.json()["detail"] == "no_profile"


async def test_get_profile_200_when_present(client_factory, configure_ai, app, session):
    """Insert a reader_profiles row directly (no orchestrator yet) and
    assert the envelope round-trips field-for-field.
    """
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})

        payload = ReaderProfilePayload(
            schema_version=1,
            stats=ReaderStats(
                total_books=4,
                finished_count=2,
                in_progress_count=1,
                abandoned_count=1,
                avg_session_minutes=None,
                finish_rate_by_theme={"noir": 0.5},
                most_read_authors=[AuthorCount(name="X", count=2)],
                books_with_themes_count=3,
            ),
            narrative="hello",
            in_library_recommendations=[],
            discovery_recommendations=[],
            confidence="medium",
        )
        session.add(
            ReaderProfile(
                tenant_id="local",
                subject="alice",
                payload=payload.model_dump(),
                schema_version=1,
                model_id="test-model",
                prompt_version="0",
                input_fingerprint=None,
            )
        )
        await session.commit()

        r = await client.get("/ai/v1/profile", headers=_basic_header("alice"))
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["schema_version"] == 1
    assert body["model_id"] == "test-model"
    assert body["prompt_version"] == "0"
    assert body["input_fingerprint"] is None
    assert body["payload"]["narrative"] == "hello"
    assert body["payload"]["confidence"] == "medium"
    assert body["payload"]["stats"]["books_with_themes_count"] == 3
    assert body["payload"]["stats"]["finish_rate_by_theme"] == {"noir": 0.5}
    assert body["payload"]["stats"]["most_read_authors"] == [{"name": "X", "count": 2}]


async def test_get_profile_200_when_opted_out(client_factory, configure_ai, app, session):
    """No opt-in gate on GET /profile — opted-out callers can still read
    their last generation (spec line 289 / brief line 42).
    """
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {"schema_version": 4, "intro": "ok", "confidence": "low"})

        # Explicitly opt out.
        session.add(UserAIPreference(user_id="alice", ai_enabled=False))
        # Hand-insert a profile row.
        payload = ReaderProfilePayload(
            stats=ReaderStats(
                total_books=0,
                finished_count=0,
                in_progress_count=0,
                abandoned_count=0,
            )
        )
        session.add(
            ReaderProfile(
                tenant_id="local",
                subject="alice",
                payload=payload.model_dump(),
                schema_version=1,
                model_id="test-model",
                prompt_version="0",
                input_fingerprint=None,
            )
        )
        await session.commit()

        r = await client.get("/ai/v1/profile", headers=_basic_header("alice"))
    assert r.status_code == 200, r.text
    assert r.json()["payload"]["stats"]["total_books"] == 0


# ---------------------------------------------------------------------------
# Terminal-state invariant
# ---------------------------------------------------------------------------


async def test_check_constraint_rejects_both_terminal_states(session):
    doc = await _seed_document(session, user_id="alice", metadata_id="m-c", content_hash="h-c")
    now = datetime.now(UTC)
    bad = Progress(
        document_pk=doc.pk,
        locator="{}",
        percent=0.5,
        client_updated_at=now,
        finished_at=now,
        abandoned_at=now,
    )
    session.add(bad)
    with pytest.raises(IntegrityError) as excinfo:
        await session.flush()
    assert "ck_progress_abandoned_xor_finished" in str(excinfo.value)
    await session.rollback()


async def test_push_progress_finishing_clears_abandoned(app_under_test, cwa_users):
    user = f"user-{uuid.uuid4().hex[:8]}"
    cwa_users[user] = "pw"
    from httpx import ASGITransport, AsyncClient

    headers = _basic_header(user, "pw")
    now = datetime.now(UTC)
    later = now + timedelta(seconds=10)

    async with AsyncClient(
        transport=ASGITransport(app=app_under_test), base_url="http://test"
    ) as c:
        # First: push an abandoned row.
        r = await c.post(
            "/sync/v1/progress",
            json={
                "items": [
                    {
                        "document": {"metadata_id": None, "content_hash": "h-cf"},
                        "locator": "{}",
                        "percent": 0.6,
                        "client_updated_at": now.isoformat(),
                        "abandoned_at": now.isoformat(),
                    }
                ]
            },
            headers=headers,
        )
        assert r.status_code == 200, r.text

        # Then: push a finished update with later timestamp.
        r = await c.post(
            "/sync/v1/progress",
            json={
                "items": [
                    {
                        "document": {"metadata_id": None, "content_hash": "h-cf"},
                        "locator": "{}",
                        "percent": 1.0,
                        "client_updated_at": later.isoformat(),
                        "finished_at": later.isoformat(),
                        "abandoned_at": None,
                    }
                ]
            },
            headers=headers,
        )
        assert r.status_code == 200, r.text

        # Pull back and verify.
        r = await c.get(
            f"/sync/v1/progress?since={(now - timedelta(seconds=60)).isoformat()}",
            headers=headers,
        )
    assert r.status_code == 200
    items = r.json()["items"]
    assert len(items) == 1
    assert items[0]["finished_at"] is not None
    assert items[0]["abandoned_at"] is None


async def test_push_progress_abandoning_clears_finished_and_keeps_percent(
    app_under_test, cwa_users
):
    """Seed a finished row at 100%; push an abandoned event at 60% with a
    later timestamp. The row should be (finished_at=null, abandoned_at set,
    percent=0.6).
    """
    user = f"user-{uuid.uuid4().hex[:8]}"
    cwa_users[user] = "pw"
    from httpx import ASGITransport, AsyncClient

    headers = _basic_header(user, "pw")
    now = datetime.now(UTC)
    later = now + timedelta(seconds=10)

    async with AsyncClient(
        transport=ASGITransport(app=app_under_test), base_url="http://test"
    ) as c:
        r = await c.post(
            "/sync/v1/progress",
            json={
                "items": [
                    {
                        "document": {"metadata_id": None, "content_hash": "h-pk"},
                        "locator": "{}",
                        "percent": 1.0,
                        "client_updated_at": now.isoformat(),
                        "finished_at": now.isoformat(),
                    }
                ]
            },
            headers=headers,
        )
        assert r.status_code == 200, r.text
        r = await c.post(
            "/sync/v1/progress",
            json={
                "items": [
                    {
                        "document": {"metadata_id": None, "content_hash": "h-pk"},
                        "locator": "{}",
                        "percent": 0.6,
                        "client_updated_at": later.isoformat(),
                        "finished_at": None,
                        "abandoned_at": later.isoformat(),
                    }
                ]
            },
            headers=headers,
        )
        assert r.status_code == 200, r.text

        r = await c.get(
            f"/sync/v1/progress?since={(now - timedelta(seconds=60)).isoformat()}",
            headers=headers,
        )
    items = r.json()["items"]
    assert len(items) == 1
    assert items[0]["finished_at"] is None
    assert items[0]["abandoned_at"] is not None
    assert items[0]["percent"] == 0.6


async def test_pull_progress_defensive_read_when_both_set(
    app_under_test, cwa_users, session, engine, caplog
):
    """Insert a corrupt row directly with both flags set (bypassing the
    check constraint by temporarily dropping it). The pull endpoint must
    drop `abandoned_at` (finished wins) and emit a warning.

    The constraint is restored at the end of the test so downstream tests
    in the same session keep their invariants.
    """
    user = f"user-{uuid.uuid4().hex[:8]}"
    cwa_users[user] = "pw"
    from httpx import ASGITransport, AsyncClient

    headers = _basic_header(user, "pw")
    now = datetime.now(UTC)

    # Drop the check constraint, insert the corrupt row.
    await session.execute(
        text("ALTER TABLE progress DROP CONSTRAINT ck_progress_abandoned_xor_finished")
    )
    doc = await _seed_document(session, user_id=user, metadata_id=None, content_hash="h-corrupt")
    await _seed_progress(
        session,
        document_pk=doc.pk,
        percent=0.8,
        finished=True,
        abandoned=True,
        when=now,
    )
    await session.commit()

    try:
        caplog.set_level(logging.WARNING, logger="quire_server.api.progress")
        async with AsyncClient(
            transport=ASGITransport(app=app_under_test), base_url="http://test"
        ) as c:
            r = await c.get(
                f"/sync/v1/progress?since={(now - timedelta(seconds=60)).isoformat()}",
                headers=headers,
            )
        assert r.status_code == 200
        items = r.json()["items"]
        assert len(items) == 1
        assert items[0]["finished_at"] is not None
        assert items[0]["abandoned_at"] is None
        assert any("terminal_state_both_set" in rec.message for rec in caplog.records)
    finally:
        # Delete the corrupt row, then restore the constraint so the
        # next test's autouse-truncate (and downstream invariants) work.
        async with engine.begin() as conn:
            await conn.execute(
                text("DELETE FROM progress WHERE document_pk = :pk"),
                {"pk": doc.pk},
            )
            await conn.execute(
                text(
                    "ALTER TABLE progress ADD CONSTRAINT "
                    "ck_progress_abandoned_xor_finished "
                    "CHECK (finished_at IS NULL OR abandoned_at IS NULL)"
                )
            )


async def test_push_stale_client_updated_at_dropped(app_under_test, cwa_users):
    """Server has a finished row at T2; client pushes abandoned at T1 < T2.
    The LWW guard keeps the existing finished state.
    """
    user = f"user-{uuid.uuid4().hex[:8]}"
    cwa_users[user] = "pw"
    from httpx import ASGITransport, AsyncClient

    headers = _basic_header(user, "pw")
    t1 = datetime.now(UTC)
    t2 = t1 + timedelta(seconds=10)

    async with AsyncClient(
        transport=ASGITransport(app=app_under_test), base_url="http://test"
    ) as c:
        # Push finished at T2.
        r = await c.post(
            "/sync/v1/progress",
            json={
                "items": [
                    {
                        "document": {"metadata_id": None, "content_hash": "h-stale"},
                        "locator": "{}",
                        "percent": 1.0,
                        "client_updated_at": t2.isoformat(),
                        "finished_at": t2.isoformat(),
                    }
                ]
            },
            headers=headers,
        )
        assert r.status_code == 200, r.text

        # Push stale abandoned at T1 < T2.
        r = await c.post(
            "/sync/v1/progress",
            json={
                "items": [
                    {
                        "document": {"metadata_id": None, "content_hash": "h-stale"},
                        "locator": "{}",
                        "percent": 0.5,
                        "client_updated_at": t1.isoformat(),
                        "abandoned_at": t1.isoformat(),
                    }
                ]
            },
            headers=headers,
        )
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["results"][0]["status"] == "stale"

        # Pull and verify the finished state survived.
        r = await c.get(
            f"/sync/v1/progress?since={(t1 - timedelta(seconds=60)).isoformat()}",
            headers=headers,
        )
    items = r.json()["items"]
    assert len(items) == 1
    assert items[0]["finished_at"] is not None
    assert items[0]["abandoned_at"] is None


async def test_input_fingerprint_column_is_varchar_16(session, engine):
    """Verify the column is declared at length 16, and that an attempt to
    INSERT a 17-character value raises a width violation.
    """

    def _inspect(conn):
        ins = inspect(conn)
        cols = ins.get_columns("reader_profiles")
        return next(c for c in cols if c["name"] == "input_fingerprint")

    async with engine.connect() as conn:
        col = await conn.run_sync(_inspect)
    # Postgres reports VARCHAR(16) as a String type with length=16.
    assert getattr(col["type"], "length", None) == 16

    # Width violation.
    payload = {
        "schema_version": 1,
        "stats": {
            "total_books": 0,
            "finished_count": 0,
            "in_progress_count": 0,
            "abandoned_count": 0,
        },
    }
    bad = ReaderProfile(
        tenant_id="local",
        subject="bob",
        payload=payload,
        schema_version=1,
        model_id="m",
        prompt_version="0",
        input_fingerprint="0123456789abcdefX",  # 17 chars
    )
    session.add(bad)
    with pytest.raises(DBAPIError):
        await session.flush()
    await session.rollback()
