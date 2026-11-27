import pytest
from httpx import ASGITransport, AsyncClient

from quire_server.api import reading_activity

pytestmark = [pytest.mark.requires_progress, pytest.mark.usefixtures("session")]

PATH = "/library/v1/reading-activity"
SESSION = {
    "id": "phone-session",
    "book_key": "hash",
    "title": "One private book",
    "started_at": 1_600_000_000_000,
    "utc_offset_seconds": 7200,
    "active_seconds": 120,
    "words": 320,
}


async def test_activity_moves_between_clients_without_duplicates_or_cross_account_leaks(
    app_under_test,
    cwa_users,
):
    cwa_users.update(alice="pw", bob="pw")
    async with AsyncClient(
        transport=ASGITransport(app=app_under_test), base_url="http://test"
    ) as c:
        alice = ("alice", "pw")
        bob = ("bob", "pw")
        assert (await c.get(PATH)).status_code == 401
        batch = {"cleared_before": 0, "sessions": [SESSION]}
        assert (await c.put(PATH, auth=alice, json=batch)).status_code == 204
        assert (await c.put(PATH, auth=alice, json=batch)).status_code == 204
        current = {**SESSION, "active_seconds": 180, "words": 500}
        assert (
            await c.put(PATH, auth=alice, json={**batch, "sessions": [current]})
        ).status_code == 204
        assert (await c.put(PATH, auth=alice, json=batch)).status_code == 204
        browser = await c.get(PATH, auth=alice)
        assert browser.headers["Cache-Control"] == "no-store"
        assert browser.json() == {"cleared_before": 0, "sessions": [current], "next": None}
        assert (await c.get(PATH, auth=bob)).json()["sessions"] == []
        invalid = await c.put(
            PATH, auth=alice, json={**batch, "sessions": [{**SESSION, "active_seconds": 0}]}
        )
        assert invalid.status_code == 422
        assert SESSION["title"] not in invalid.text


async def test_clear_rejects_stale_writes_and_excludes_older_history(app_under_test, cwa_users):
    cwa_users.update(alice="pw", bob="pw")
    async with AsyncClient(
        transport=ASGITransport(app=app_under_test), base_url="http://test"
    ) as c:
        alice = ("alice", "pw")
        bob = ("bob", "pw")
        batch = {"cleared_before": 0, "sessions": [SESSION]}
        for auth in (alice, bob):
            assert (await c.put(PATH, auth=auth, json=batch)).status_code == 204
        cleared = (await c.delete(PATH, auth=alice)).json()
        assert cleared["cleared_before"] > SESSION["started_at"]
        assert (await c.put(PATH, auth=alice, json=batch)).status_code == 409
        assert (await c.put(PATH, auth=alice, json={**batch, **cleared})).status_code == 204
        assert (await c.get(PATH, auth=alice)).json()["sessions"] == []
        assert (await c.get(PATH, auth=bob)).json()["sessions"] == [SESSION]
        new = {**SESSION, "id": "new-session", "started_at": cleared["cleared_before"] + 1}
        assert (
            await c.put(PATH, auth=alice, json={**cleared, "sessions": [new]})
        ).status_code == 204
        assert (await c.get(PATH, auth=alice)).json()["sessions"] == [new]


async def test_activity_pages_keep_a_stable_cursor(app_under_test, cwa_users, monkeypatch):
    monkeypatch.setattr(reading_activity, "PAGE", 1)
    cwa_users["alice"] = "pw"
    async with AsyncClient(
        transport=ASGITransport(app=app_under_test), base_url="http://test", auth=("alice", "pw")
    ) as client:
        first, last = {**SESSION, "id": "a"}, {**SESSION, "id": "b"}
        uploaded = await client.put(PATH, json={"cleared_before": 0, "sessions": [last, first]})
        assert uploaded.status_code == 204
        page = (await client.get(PATH)).json()
        assert page == {"cleared_before": 0, "sessions": [first], "next": "a"}
        tail = (await client.get(PATH, params={"after": page["next"]})).json()
        assert tail == {"cleared_before": 0, "sessions": [last], "next": None}
