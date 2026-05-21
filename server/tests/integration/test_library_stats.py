"""Integration tests for `GET /library/v1/stats` (PR9).

Mode-gated: requires_progress. The CI mode matrix skips this whole module
when QUIRE_SERVER_PROGRESS_ENABLED=false; the mode-gated 404 case is asserted
in test_modes.py once routing is verified.

Theme tests additionally require AI mode for the orchestrator-seeded
`book_insights` + `book_themes` rows. Themes tests carry `requires_ai`
on top of the module-level `requires_progress` marker.
"""

from __future__ import annotations

import base64
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import async_sessionmaker

from quire_server.api.ai_schemas import DocumentIdentity, MetadataBundle
from quire_server.core.ai.service import InsightOrchestrator
from quire_server.db.models import BookInsight, BookTheme

pytestmark = pytest.mark.requires_progress


def _basic(user: str, pw: str) -> dict[str, str]:
    token = base64.b64encode(f"{user}:{pw}".encode()).decode("ascii")
    return {"Authorization": f"Basic {token}"}


@pytest.fixture
def unique_user(cwa_users) -> tuple[str, str]:
    user = f"user-{uuid.uuid4().hex[:8]}"
    pw = "pw"
    cwa_users[user] = pw
    return user, pw


def _put_body(**overrides) -> dict:
    base = {
        "metadata_id": "md-1",
        "content_hash": "ch-1",
        "title": "Foundation",
        "authors": ["Isaac Asimov"],
        "series_name": None,
        "series_index": None,
        "isbn": None,
        "language": None,
        "subjects": [],
        "opds_href": None,
    }
    base.update(overrides)
    return {"item": base}


def _progress_body(content_hash: str, percent: float, finished: bool, when: str) -> dict:
    return {
        "items": [
            {
                "document": {"metadata_id": None, "content_hash": content_hash},
                "locator": "{}",
                "percent": percent,
                "client_updated_at": when,
                "finished_at": when if finished else None,
            }
        ]
    }


def _abandon_body(content_hash: str, percent: float, when: str) -> dict:
    """Progress payload that marks a book abandoned.

    PR-α's progress endpoint enforces the XOR invariant: setting
    `abandoned_at` requires `finished_at` to be null. `percent` is
    preserved so e.g. abandoning at 60% remembers 60%.
    """
    return {
        "items": [
            {
                "document": {"metadata_id": None, "content_hash": content_hash},
                "locator": "{}",
                "percent": percent,
                "client_updated_at": when,
                "finished_at": None,
                "abandoned_at": when,
            }
        ]
    }


# ---------------------------------------------------------------------------
# Counts
# ---------------------------------------------------------------------------


async def test_empty_library_returns_zero_counts(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.get("/library/v1/stats", headers=headers)
    assert r.status_code == 200, r.text
    data = r.json()
    assert data["total_books"] == 0
    assert data["finished_count"] == 0
    assert data["in_progress_count"] == 0
    assert data["abandoned_count"] == 0
    assert data["top_authors"] == []
    assert data["top_themes"] == []
    assert "may be missing" in data["themes_caveat"]


async def test_total_books_counts_alive_only(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put("/library/v1/items", json=_put_body(content_hash="a"), headers=headers)
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="b", metadata_id="md-2"),
            headers=headers,
        )
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="c", metadata_id="md-3"),
            headers=headers,
        )
        await c.request(
            "DELETE",
            "/library/v1/items",
            json={"item": {"content_hash": "c"}},
            headers=headers,
        )
        r = await c.get("/library/v1/stats", headers=headers)
    assert r.status_code == 200
    assert r.json()["total_books"] == 2


async def test_finished_count_requires_finished_at(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    now = datetime.now(UTC).isoformat()
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put("/library/v1/items", json=_put_body(content_hash="a"), headers=headers)
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="b", metadata_id="md-2"),
            headers=headers,
        )
        # `a` finished; `b` has progress but never finished.
        await c.post(
            "/sync/v1/progress",
            json=_progress_body("a", 1.0, True, now),
            headers=headers,
        )
        await c.post(
            "/sync/v1/progress",
            json=_progress_body("b", 0.4, False, now),
            headers=headers,
        )
        r = await c.get("/library/v1/stats", headers=headers)
    data = r.json()
    assert data["finished_count"] == 1
    assert data["in_progress_count"] == 1


async def test_in_progress_excludes_zero_percent(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    now = datetime.now(UTC).isoformat()
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put("/library/v1/items", json=_put_body(content_hash="a"), headers=headers)
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="b", metadata_id="md-2"),
            headers=headers,
        )
        await c.post(
            "/sync/v1/progress",
            json=_progress_body("a", 0.0, False, now),
            headers=headers,
        )
        await c.post(
            "/sync/v1/progress",
            json=_progress_body("b", 0.5, False, now),
            headers=headers,
        )
        r = await c.get("/library/v1/stats", headers=headers)
    data = r.json()
    assert data["in_progress_count"] == 1  # only b


async def test_in_progress_includes_percent_one_without_finished_at(app_under_test, unique_user):
    """Edge case: percent=1 but finished_at IS NULL. Architect-reviewed
    semantics — "not done until the device says so". Still counts as in
    progress.
    """
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    now = datetime.now(UTC).isoformat()
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="edge"),
            headers=headers,
        )
        await c.post(
            "/sync/v1/progress",
            json=_progress_body("edge", 1.0, False, now),
            headers=headers,
        )
        r = await c.get("/library/v1/stats", headers=headers)
    data = r.json()
    assert data["in_progress_count"] == 1
    assert data["finished_count"] == 0


async def test_user_scoping(app_under_test, unique_user):
    """User A's books invisible to user B; both use real `cwa_users` entries."""
    transport = ASGITransport(app=app_under_test)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="aaa"),
            headers=_basic("alice", "alicepass"),
        )
        r = await c.get("/library/v1/stats", headers=_basic("bob", "bobpass"))
    assert r.status_code == 200
    assert r.json()["total_books"] == 0


# ---------------------------------------------------------------------------
# Abandoned counts (PR-9 Bundle 4)
# ---------------------------------------------------------------------------


async def test_abandoned_count_requires_abandoned_at(app_under_test, unique_user):
    """abandoned_count counts only library_items whose Progress.abandoned_at
    is set. Constructed via valid XOR-satisfying rows.
    """
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    now = datetime.now(UTC).isoformat()
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # a: finished (finished_at set, abandoned_at NULL)
        # b: abandoned at percent=0 (abandoned_at set, finished_at NULL)
        # c: abandoned at percent=0.5 (abandoned_at set, finished_at NULL,
        #    percent>0 — must NOT count as in_progress after PR-9 tightening)
        await c.put("/library/v1/items", json=_put_body(content_hash="a"), headers=headers)
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="b", metadata_id="md-2"),
            headers=headers,
        )
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="c", metadata_id="md-3"),
            headers=headers,
        )
        await c.post(
            "/sync/v1/progress",
            json=_progress_body("a", 1.0, True, now),
            headers=headers,
        )
        await c.post(
            "/sync/v1/progress",
            json=_abandon_body("b", 0.0, now),
            headers=headers,
        )
        await c.post(
            "/sync/v1/progress",
            json=_abandon_body("c", 0.5, now),
            headers=headers,
        )
        r = await c.get("/library/v1/stats", headers=headers)
    data = r.json()
    assert data["abandoned_count"] == 2  # b and c
    assert data["finished_count"] == 1  # a
    # PR-9 tightening: c (percent=0.5 + abandoned) does NOT count as in_progress.
    assert data["in_progress_count"] == 0


async def test_counts_are_disjoint(app_under_test, unique_user):
    """The three count buckets are mutually disjoint and never double-count."""
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    now = datetime.now(UTC).isoformat()
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # 5 books:
        #  fin: finished
        #  ab0: abandoned at 0
        #  ab5: abandoned at 0.5
        #  ip:  in-progress (percent=0.3, neither terminal flag)
        #  un:  untouched (no progress row at all)
        for ch, md in [
            ("fin", "md-fin"),
            ("ab0", "md-ab0"),
            ("ab5", "md-ab5"),
            ("ip", "md-ip"),
            ("un", "md-un"),
        ]:
            await c.put(
                "/library/v1/items",
                json=_put_body(content_hash=ch, metadata_id=md),
                headers=headers,
            )
        await c.post(
            "/sync/v1/progress",
            json=_progress_body("fin", 1.0, True, now),
            headers=headers,
        )
        await c.post(
            "/sync/v1/progress",
            json=_abandon_body("ab0", 0.0, now),
            headers=headers,
        )
        await c.post(
            "/sync/v1/progress",
            json=_abandon_body("ab5", 0.5, now),
            headers=headers,
        )
        await c.post(
            "/sync/v1/progress",
            json=_progress_body("ip", 0.3, False, now),
            headers=headers,
        )
        r = await c.get("/library/v1/stats", headers=headers)
    data = r.json()
    assert data["total_books"] == 5
    assert data["finished_count"] == 1
    assert data["abandoned_count"] == 2
    assert data["in_progress_count"] == 1
    assert (data["finished_count"] + data["abandoned_count"] + data["in_progress_count"]) <= data[
        "total_books"
    ]


async def test_abandoned_count_excludes_tombstoned_books(app_under_test, unique_user):
    """LibraryItem.deleted_at IS NOT NULL → not counted even if the
    Progress row still has abandoned_at set (matches finished_count's
    tombstone semantics).
    """
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    now = datetime.now(UTC).isoformat()
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put("/library/v1/items", json=_put_body(content_hash="x"), headers=headers)
        await c.post(
            "/sync/v1/progress",
            json=_abandon_body("x", 0.3, now),
            headers=headers,
        )
        # Tombstone the library item — the progress row survives.
        await c.request(
            "DELETE",
            "/library/v1/items",
            json={"item": {"content_hash": "x"}},
            headers=headers,
        )
        r = await c.get("/library/v1/stats", headers=headers)
    data = r.json()
    assert data["abandoned_count"] == 0
    assert data["total_books"] == 0


async def test_abandoned_count_user_scoped(app_under_test, cwa_users):
    """User A's abandoned books don't appear in user B's count."""
    user_a = f"user-{uuid.uuid4().hex[:8]}"
    user_b = f"user-{uuid.uuid4().hex[:8]}"
    cwa_users[user_a] = "pw"
    cwa_users[user_b] = "pw"
    transport = ASGITransport(app=app_under_test)
    now = datetime.now(UTC).isoformat()
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="a-ch"),
            headers=_basic(user_a, "pw"),
        )
        await c.post(
            "/sync/v1/progress",
            json=_abandon_body("a-ch", 0.3, now),
            headers=_basic(user_a, "pw"),
        )
        r_a = await c.get("/library/v1/stats", headers=_basic(user_a, "pw"))
        r_b = await c.get("/library/v1/stats", headers=_basic(user_b, "pw"))
    assert r_a.json()["abandoned_count"] == 1
    assert r_b.json()["abandoned_count"] == 0


async def test_in_progress_excludes_abandoned_rows(app_under_test, unique_user):
    """PR-9 behaviour change: a book marked abandoned no longer counts as
    in_progress even if percent > 0 and finished_at IS NULL. Backs the
    'Reading excludes Abandoned' release-note guarantee.
    """
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    now = datetime.now(UTC).isoformat()
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put("/library/v1/items", json=_put_body(content_hash="r"), headers=headers)
        # Start reading at 0.5
        await c.post(
            "/sync/v1/progress",
            json=_progress_body("r", 0.5, False, now),
            headers=headers,
        )
        # Then mark abandoned (advance client_updated_at to win LWW).
        later = (datetime.now(UTC) + timedelta(seconds=1)).isoformat()
        await c.post(
            "/sync/v1/progress",
            json=_abandon_body("r", 0.5, later),
            headers=headers,
        )
        r = await c.get("/library/v1/stats", headers=headers)
    data = r.json()
    assert data["abandoned_count"] == 1
    assert data["in_progress_count"] == 0


# ---------------------------------------------------------------------------
# Top authors
# ---------------------------------------------------------------------------


async def test_top_authors_groups_jsonb_and_orders_by_count_desc(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # 3 Asimov, 2 Le Guin, 1 co-authored (Asimov + Pohl).
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="a1", authors=["Isaac Asimov"]),
            headers=headers,
        )
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="a2", metadata_id="md-a2", authors=["Isaac Asimov"]),
            headers=headers,
        )
        await c.put(
            "/library/v1/items",
            json=_put_body(
                content_hash="a3",
                metadata_id="md-a3",
                authors=["Isaac Asimov", "Frederik Pohl"],
            ),
            headers=headers,
        )
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="l1", metadata_id="md-l1", authors=["Ursula K. Le Guin"]),
            headers=headers,
        )
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="l2", metadata_id="md-l2", authors=["Ursula K. Le Guin"]),
            headers=headers,
        )
        r = await c.get("/library/v1/stats", headers=headers)
    top = r.json()["top_authors"]
    assert top == [
        {"name": "Isaac Asimov", "count": 3},
        {"name": "Ursula K. Le Guin", "count": 2},
        {"name": "Frederik Pohl", "count": 1},
    ]


async def test_top_authors_limit_5_with_stable_tiebreak(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # 6 distinct authors, one book each → ties on count=1, alphabetical wins.
        for i, name in enumerate(["F", "E", "D", "C", "B", "A"]):
            await c.put(
                "/library/v1/items",
                json=_put_body(content_hash=f"ch-{i}", metadata_id=f"md-{i}", authors=[name]),
                headers=headers,
            )
        r = await c.get("/library/v1/stats", headers=headers)
    top = [x["name"] for x in r.json()["top_authors"]]
    assert top == ["A", "B", "C", "D", "E"]  # top 5, alphabetical


async def test_top_authors_ignores_tombstoned_books(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="x", authors=["Tombstone Author"]),
            headers=headers,
        )
        await c.request(
            "DELETE",
            "/library/v1/items",
            json={"item": {"content_hash": "x"}},
            headers=headers,
        )
        r = await c.get("/library/v1/stats", headers=headers)
    assert r.json()["top_authors"] == []


# ---------------------------------------------------------------------------
# Top themes — require AI mode for orchestrator-driven seeding.
# ---------------------------------------------------------------------------


class _FakeAIClient:
    def __init__(self, themes: list[str] | None) -> None:
        self.themes = themes

    async def chat_structured(self, *, system, user, schema, timeout_s):  # noqa: ARG002
        return schema.model_validate(
            {
                "schema_version": 3,
                "intro": "Fake.",
                "confidence": "high",
                "themes": self.themes,
            }
        )


class _FakeRetriever:
    async def lookup_wikipedia(self, **_kw):
        return []

    async def lookup_openlibrary(self, **_kw):
        return []


def _orch(ai: _FakeAIClient) -> InsightOrchestrator:
    return InsightOrchestrator(
        ai=ai,
        retriever_factory=lambda _s: _FakeRetriever(),
        sources_enabled=(),
        model_id="stats-test-model",
        prompt_version="4",
        max_concurrency=2,
        ai_timeout_s=5.0,
    )


@pytest.fixture
async def session_factory(engine) -> AsyncIterator[async_sessionmaker]:
    yield async_sessionmaker(engine, expire_on_commit=False)


async def _seed_insight(
    session_factory, *, metadata_id: str, content_hash: str, themes: list[str]
) -> None:
    ai = _FakeAIClient(themes=themes)
    orch = _orch(ai)
    ident = DocumentIdentity(metadata_id=metadata_id, content_hash=content_hash)
    bundle = MetadataBundle(title=content_hash)
    async with session_factory() as s:
        await orch.generate(s, ident, bundle, user_id="stats-seed", tenant_id="local")


async def _seed_regen(
    session_factory, *, metadata_id: str, content_hash: str, themes: list[str]
) -> None:
    ai = _FakeAIClient(themes=themes)
    orch = _orch(ai)
    ident = DocumentIdentity(metadata_id=metadata_id, content_hash=content_hash)
    bundle = MetadataBundle(title=content_hash)
    async with session_factory() as s:
        await orch.regenerate(
            s, ident, bundle, user_id="stats-seed", reason="redo", tenant_id="local"
        )


@pytest.mark.requires_ai
async def test_top_themes_basic(app_under_test, unique_user, session_factory):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    await _seed_insight(
        session_factory,
        metadata_id="t-md-1",
        content_hash="t-ch-1",
        themes=["mystery", "noir"],
    )
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="t-ch-1", metadata_id="t-md-1"),
            headers=headers,
        )
        r = await c.get("/library/v1/stats", headers=headers)
    themes = r.json()["top_themes"]
    names = sorted([t["theme"] for t in themes])
    assert names == ["mystery", "noir"]
    assert all(t["count"] == 1 for t in themes)
    assert all(t["note"] == "v3+ insights only" for t in themes)


@pytest.mark.requires_ai
async def test_top_themes_filters_superseded(app_under_test, unique_user, session_factory):
    """Regenerate produces a new live row + supersedes the old. Old themes
    must NOT count. Filter 1 from the three load-bearing filters.
    """
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    await _seed_insight(
        session_factory,
        metadata_id="t-md-2",
        content_hash="t-ch-2",
        themes=["mystery", "noir"],
    )
    await _seed_regen(
        session_factory,
        metadata_id="t-md-2",
        content_hash="t-ch-2",
        themes=["thriller", "crime"],
    )
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="t-ch-2", metadata_id="t-md-2"),
            headers=headers,
        )
        r = await c.get("/library/v1/stats", headers=headers)
    names = sorted([t["theme"] for t in r.json()["top_themes"]])
    assert names == ["crime", "thriller"]  # superseded mystery/noir excluded


@pytest.mark.requires_ai
async def test_top_themes_filters_off_vocab_confidence(
    app_under_test, unique_user, session_factory
):
    """Off-vocab strings land at confidence=0.5; filter excludes them.
    Filter 2 from the three load-bearing filters.
    """
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    await _seed_insight(
        session_factory,
        metadata_id="t-md-3",
        content_hash="t-ch-3",
        themes=["mystery", "weird genre"],
    )
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="t-ch-3", metadata_id="t-md-3"),
            headers=headers,
        )
        r = await c.get("/library/v1/stats", headers=headers)
    names = sorted([t["theme"] for t in r.json()["top_themes"]])
    assert names == ["mystery"]


@pytest.mark.requires_ai
async def test_top_themes_dedup_across_tone_variants(app_under_test, unique_user, session_factory):
    """Same identity, two LIVE insight rows under different tones, SAME
    themes. Book counts ONCE per theme. Filter 3 from the three load-
    bearing filters (COUNT DISTINCT).
    """
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)

    await _seed_insight(
        session_factory,
        metadata_id="t-md-4",
        content_hash="t-ch-4",
        themes=["mystery"],
    )
    async with session_factory() as s:
        insight2 = BookInsight(
            metadata_id="t-md-4",
            content_hash="t-ch-4",
            model_id="stats-test-model",
            prompt_version="4",
            tone="scholarly",
            language="auto",
            sources_used=[],
            payload={"schema_version": 3, "intro": "v2", "confidence": "high"},
            sources=[],
            generated_by="stats-seed",
        )
        s.add(insight2)
        await s.flush()
        s.add(BookTheme(book_insight_id=insight2.id, theme="mystery", confidence=1.0))
        await s.commit()

    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="t-ch-4", metadata_id="t-md-4"),
            headers=headers,
        )
        r = await c.get("/library/v1/stats", headers=headers)
    themes = r.json()["top_themes"]
    assert themes == [{"theme": "mystery", "count": 1, "note": "v3+ insights only"}]


@pytest.mark.requires_ai
async def test_top_themes_picks_one_insight_per_book_when_variants_differ(
    app_under_test, unique_user, session_factory
):
    """Architect finding (2026-05-17): two LIVE insights for the same book
    with DIFFERENT themes (variant A → {mystery}, variant B → {noir, crime})
    would, under a naive `JOIN ... ON metadata_id OR content_hash` + COUNT
    DISTINCT, attribute the book to three theme keys.

    The DISTINCT-ON CTE picks exactly one insight per library item; most
    recent generated_at wins. So the book contributes to one canonical
    theme set, not the union.
    """
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)

    # Seed the OLDER variant first (themes={mystery}).
    await _seed_insight(
        session_factory,
        metadata_id="t-md-vs",
        content_hash="t-ch-vs",
        themes=["mystery"],
    )

    async with session_factory() as s:
        from sqlalchemy import select as _sel

        seeded = (
            await s.execute(_sel(BookInsight).where(BookInsight.content_hash == "t-ch-vs"))
        ).scalar_one()
        # Backdate the seeded row so generated_at ordering is unambiguous.
        seeded.generated_at = datetime.now(UTC) - timedelta(hours=1)

        # NEWER live variant under a different tone, different themes.
        newer = BookInsight(
            metadata_id="t-md-vs",
            content_hash="t-ch-vs",
            model_id="stats-test-model",
            prompt_version="4",
            tone="scholarly",
            language="auto",
            sources_used=[],
            payload={"schema_version": 3, "intro": "v2", "confidence": "high"},
            sources=[],
            generated_by="stats-seed",
            generated_at=datetime.now(UTC),
        )
        s.add(newer)
        await s.flush()
        s.add(BookTheme(book_insight_id=newer.id, theme="noir", confidence=1.0))
        s.add(BookTheme(book_insight_id=newer.id, theme="crime", confidence=1.0))
        await s.commit()

    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="t-ch-vs", metadata_id="t-md-vs"),
            headers=headers,
        )
        r = await c.get("/library/v1/stats", headers=headers)
    names = sorted([t["theme"] for t in r.json()["top_themes"]])
    # The newer variant wins → {noir, crime}. `mystery` from the older
    # variant must NOT appear (without the pick-one CTE we'd see all three).
    assert names == ["crime", "noir"]


@pytest.mark.requires_ai
async def test_top_themes_user_scoped(app_under_test, unique_user, session_factory):
    """`book_insights` is shared cache; the per-user filter is on `library_items`.
    Alice has the book in her library; Bob doesn't, so Bob sees no themes.
    """
    transport = ASGITransport(app=app_under_test)
    await _seed_insight(
        session_factory,
        metadata_id="t-md-5",
        content_hash="t-ch-5",
        themes=["mystery"],
    )
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="t-ch-5", metadata_id="t-md-5"),
            headers=_basic("alice", "alicepass"),
        )
        r_alice = await c.get("/library/v1/stats", headers=_basic("alice", "alicepass"))
        r_bob = await c.get("/library/v1/stats", headers=_basic("bob", "bobpass"))
    assert [t["theme"] for t in r_alice.json()["top_themes"]] == ["mystery"]
    assert r_bob.json()["top_themes"] == []
