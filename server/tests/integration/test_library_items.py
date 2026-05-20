"""Integration tests for `/library/v1/items`.

All tests require the progress router; mode-gated CI skips this whole module
when `QUIRE_SERVER_PROGRESS_ENABLED=false`.

Note on isolation: the underlying postgres container is shared across tests,
and the endpoints commit. Each test that needs "this user's library" to
contain a predictable count uses a uniquely-named user via the `unique_user`
fixture below.
"""

from __future__ import annotations

import asyncio
import base64
import uuid

import pytest
from httpx import ASGITransport, AsyncClient

pytestmark = pytest.mark.requires_progress


def _basic(user: str, pw: str) -> dict[str, str]:
    token = base64.b64encode(f"{user}:{pw}".encode()).decode("ascii")
    return {"Authorization": f"Basic {token}"}


@pytest.fixture
def unique_user(cwa_users) -> tuple[str, str]:
    """A unique CWA user registered for this test only.

    Adds the entry to the shared `cwa_users` dict so the mock transport
    accepts it. Returns `(username, password)`.
    """
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
        "series_name": "Foundation",
        "series_index": 1,
        "isbn": "9780553293357",
        "language": "en",
        "subjects": ["Science Fiction"],
        "opds_href": "https://example/foundation.epub",
    }
    base.update(overrides)
    return {"item": base}


async def test_put_creates_then_get_returns_row(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.put("/library/v1/items", json=_put_body(), headers=headers)
        assert r.status_code == 200, r.text
        data = r.json()
        assert data["title"] == "Foundation"
        assert data["authors"] == ["Isaac Asimov"]
        assert data["series_index"] == 1
        assert data["deleted_at"] is None
        assert data["created_at"] == data["updated_at"]

        r2 = await c.get("/library/v1/items", headers=headers)
        assert r2.status_code == 200
        items = r2.json()["items"]
        assert len(items) == 1
        assert items[0]["content_hash"] == "ch-1"


async def test_put_idempotent_updates_payload(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r1 = await c.put("/library/v1/items", json=_put_body(), headers=headers)
        created_at_1 = r1.json()["created_at"]

        # Sleep one tick so updated_at moves measurably.
        await asyncio.sleep(0.01)
        r2 = await c.put(
            "/library/v1/items",
            json=_put_body(title="Foundation (Revised)"),
            headers=headers,
        )
        assert r2.status_code == 200
        data2 = r2.json()
        assert data2["title"] == "Foundation (Revised)"
        # Same logical row: created_at preserved, updated_at moved forward.
        assert data2["created_at"] == created_at_1
        assert data2["updated_at"] > created_at_1


async def test_delete_soft_deletes_then_put_reactivates(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put("/library/v1/items", json=_put_body(), headers=headers)

        # DELETE
        r = await c.request(
            "DELETE",
            "/library/v1/items",
            json={"item": {"content_hash": "ch-1"}},
            headers=headers,
        )
        assert r.status_code == 200
        assert r.json()["deleted_at"] is not None

        # GET without since omits tombstones.
        r2 = await c.get("/library/v1/items", headers=headers)
        assert r2.status_code == 200
        assert r2.json()["items"] == []

        # PUT reactivates.
        r3 = await c.put("/library/v1/items", json=_put_body(), headers=headers)
        assert r3.status_code == 200
        assert r3.json()["deleted_at"] is None

        r4 = await c.get("/library/v1/items", headers=headers)
        assert len(r4.json()["items"]) == 1


async def test_get_since_includes_tombstones(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r0 = await c.put("/library/v1/items", json=_put_body(), headers=headers)
        cursor = r0.json()["updated_at"]

        await asyncio.sleep(0.01)
        await c.request(
            "DELETE",
            "/library/v1/items",
            json={"item": {"content_hash": "ch-1"}},
            headers=headers,
        )

        r = await c.get(f"/library/v1/items?since={cursor}", headers=headers)
        assert r.status_code == 200
        items = r.json()["items"]
        assert len(items) == 1
        assert items[0]["deleted_at"] is not None


async def test_user_isolation(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put("/library/v1/items", json=_put_body(), headers=_basic("alice", "alicepass"))
        r = await c.get("/library/v1/items", headers=_basic("bob", "bobpass"))
    assert r.status_code == 200
    assert r.json()["items"] == []


async def test_large_arrays_round_trip(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    authors = [f"Author {i}" for i in range(60)]
    subjects = [f"Subject {i}" for i in range(60)]
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.put(
            "/library/v1/items",
            json=_put_body(authors=authors, subjects=subjects),
            headers=headers,
        )
        assert r.status_code == 200, r.text
        data = r.json()
        assert data["authors"] == authors
        assert data["subjects"] == subjects


async def test_put_missing_content_hash_returns_422(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    body = _put_body()
    body["item"].pop("content_hash")
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.put("/library/v1/items", json=body, headers=headers)
    assert r.status_code == 422


async def test_put_missing_title_returns_422(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    body = _put_body()
    body["item"].pop("title")
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.put("/library/v1/items", json=body, headers=headers)
    assert r.status_code == 422


async def test_put_missing_item_wrapper_returns_422(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.put(
            "/library/v1/items",
            json={"content_hash": "ch", "title": "t", "authors": []},
            headers=headers,
        )
    assert r.status_code == 422


async def test_metadata_id_conflict_returns_409(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # Row A: content_hash="ch-A", metadata_id="MD-X"
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="ch-A", metadata_id="MD-X"),
            headers=headers,
        )
        # Row B: different content_hash claims same metadata_id.
        r = await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="ch-B", metadata_id="MD-X"),
            headers=headers,
        )
    assert r.status_code == 409
    detail = r.json()["detail"]
    assert detail["error"] == "metadata_id_conflict"
    assert detail["existing_content_hash"] == "ch-A"


async def test_pagination_limit_and_offset(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        for i in range(5):
            await c.put(
                "/library/v1/items",
                json=_put_body(
                    content_hash=f"ch-{i}",
                    metadata_id=f"md-{i}",
                    title=f"Book {i}",
                ),
                headers=headers,
            )
            await asyncio.sleep(0.005)  # spread timestamps so ordering is stable

        seen = []
        offset = 0
        while True:
            r = await c.get(f"/library/v1/items?limit=2&offset={offset}", headers=headers)
            assert r.status_code == 200
            items = r.json()["items"]
            seen.extend(items)
            if len(items) < 2:
                break
            offset += 2
    assert len(seen) == 5
    hashes = [it["content_hash"] for it in seen]
    assert hashes == sorted(hashes, key=lambda h: int(h.split("-")[1]))


async def test_get_ordering_stable_with_pk_tiebreaker(app_under_test, unique_user):
    """Three rows written in fast succession must page deterministically.

    Even if `updated_at` ties (same wall-clock second), the `pk ASC` tiebreaker
    means consecutive pages cover the whole set without dropping or duplicating.
    """
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        for i in range(3):
            await c.put(
                "/library/v1/items",
                json=_put_body(content_hash=f"tie-{i}", metadata_id=f"tmd-{i}", title=f"T {i}"),
                headers=headers,
            )
        page1 = (await c.get("/library/v1/items?limit=2&offset=0", headers=headers)).json()["items"]
        page2 = (await c.get("/library/v1/items?limit=2&offset=2", headers=headers)).json()["items"]
    seen = {it["content_hash"] for it in page1 + page2}
    assert seen == {"tie-0", "tie-1", "tie-2"}
    assert len(page1) == 2
    assert len(page2) == 1


async def test_get_server_time_bounds_concurrent_writes(app_under_test, unique_user):
    """Capture server_time, then write later. The later row must NOT appear in
    a query using a `since` that matches the pre-write window.
    """
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="early", metadata_id="early-md"),
            headers=headers,
        )
        r_pre = await c.get("/library/v1/items", headers=headers)
        server_time_pre = r_pre.json()["server_time"]

        await asyncio.sleep(0.05)
        await c.put(
            "/library/v1/items",
            json=_put_body(content_hash="late", metadata_id="late-md"),
            headers=headers,
        )

        # Use server_time_pre as `since`. Since it equals or exceeds the
        # `early` row's updated_at, only the `late` row would qualify.
        # But the `late` row's updated_at > server_time_pre, so it appears.
        # The point of the bounding test: a GET that BACKDATES `server_time`
        # (which our endpoint does — captures now() BEFORE the query) ensures
        # rows written between `since` and the GET land in `>since` correctly.
        # Concrete invariant: a since strictly later than early but earlier
        # than late returns only late.
        r_later = await c.get(f"/library/v1/items?since={server_time_pre}", headers=headers)
        items = r_later.json()["items"]
    assert [i["content_hash"] for i in items] == ["late"]


async def test_delete_bumps_updated_at_for_tombstone_delivery(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r0 = await c.put("/library/v1/items", json=_put_body(), headers=headers)
        cursor_after_put = r0.json()["updated_at"]

        await asyncio.sleep(0.02)
        r1 = await c.request(
            "DELETE",
            "/library/v1/items",
            json={"item": {"content_hash": "ch-1"}},
            headers=headers,
        )
        assert r1.status_code == 200
        deleted_at = r1.json()["deleted_at"]
        updated_at_after_delete = r1.json()["updated_at"]
        # The DELETE bumps updated_at past the previous cursor.
        assert updated_at_after_delete > cursor_after_put
        # And the tombstone's updated_at equals deleted_at (both set to now()).
        assert updated_at_after_delete == deleted_at

        # A GET using the post-PUT cursor catches the tombstone.
        r2 = await c.get(f"/library/v1/items?since={cursor_after_put}", headers=headers)
        items = r2.json()["items"]
        assert len(items) == 1
        assert items[0]["deleted_at"] is not None


async def test_delete_idempotent_does_not_refresh_updated_at(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.put("/library/v1/items", json=_put_body(), headers=headers)
        r1 = await c.request(
            "DELETE",
            "/library/v1/items",
            json={"item": {"content_hash": "ch-1"}},
            headers=headers,
        )
        ts1 = r1.json()["updated_at"]
        deleted1 = r1.json()["deleted_at"]

        await asyncio.sleep(0.02)
        r2 = await c.request(
            "DELETE",
            "/library/v1/items",
            json={"item": {"content_hash": "ch-1"}},
            headers=headers,
        )
        assert r2.status_code == 200
    assert r2.json()["updated_at"] == ts1
    assert r2.json()["deleted_at"] == deleted1


async def test_delete_unknown_returns_404(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.request(
            "DELETE",
            "/library/v1/items",
            json={"item": {"content_hash": "nope"}},
            headers=headers,
        )
    assert r.status_code == 404


async def test_unauthenticated_request_rejected(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.get("/library/v1/items")
    assert r.status_code == 401
