import base64

from httpx import ASGITransport, AsyncClient


def _basic(user: str, pw: str) -> dict[str, str]:
    token = base64.b64encode(f"{user}:{pw}".encode()).decode("ascii")
    return {"Authorization": f"Basic {token}"}


async def test_post_progress_creates_document_and_progress(app_under_test):
    transport = ASGITransport(app=app_under_test)
    headers = _basic("alice", "alicepass")
    body = {
        "items": [
            {
                "document": {"metadata_id": "abc", "content_hash": "hash1"},
                "locator": "epubcfi(/6/4!/4)",
                "percent": 0.42,
                "client_updated_at": "2026-05-05T12:00:00+00:00",
            }
        ]
    }
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post("/sync/v1/progress", json=body, headers=headers)
    assert r.status_code == 200, r.text
    data = r.json()
    assert len(data["results"]) == 1
    assert data["results"][0]["status"] == "accepted"
    assert data["results"][0]["server_client_updated_at"] == "2026-05-05T12:00:00+00:00"


async def test_post_progress_lww_keeps_newer(app_under_test):
    transport = ASGITransport(app=app_under_test)
    headers = _basic("alice", "alicepass")
    base = {
        "document": {"metadata_id": "abc", "content_hash": "hash1"},
        "locator": "loc",
        "percent": 0.1,
    }
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # newer first
        r1 = await c.post(
            "/sync/v1/progress",
            headers=headers,
            json={
                "items": [
                    {**base, "percent": 0.5, "client_updated_at": "2026-05-05T13:00:00+00:00"}
                ]
            },
        )
        assert r1.status_code == 200
        # older comes after
        r2 = await c.post(
            "/sync/v1/progress",
            headers=headers,
            json={
                "items": [
                    {**base, "percent": 0.1, "client_updated_at": "2026-05-05T12:00:00+00:00"}
                ]
            },
        )
        assert r2.status_code == 200
        assert r2.json()["results"][0]["status"] == "stale"
        # GET — should reflect the 0.5 value
        r3 = await c.get("/sync/v1/progress?since=2026-01-01T00:00:00+00:00", headers=headers)
        assert r3.status_code == 200
        items = r3.json()["items"]
        assert len(items) == 1
        assert items[0]["percent"] == 0.5


async def test_get_progress_filters_by_user(app_under_test):
    transport = ASGITransport(app=app_under_test)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.post(
            "/sync/v1/progress",
            headers=_basic("alice", "alicepass"),
            json={
                "items": [
                    {
                        "document": {"metadata_id": "a", "content_hash": "h"},
                        "locator": "l",
                        "percent": 0.1,
                        "client_updated_at": "2026-05-05T12:00:00+00:00",
                    }
                ]
            },
        )
        r = await c.get(
            "/sync/v1/progress?since=2026-01-01T00:00:00+00:00", headers=_basic("bob", "bobpass")
        )
    assert r.status_code == 200
    assert r.json()["items"] == []


async def test_unauthenticated_request_rejected(app_under_test):
    transport = ASGITransport(app=app_under_test)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.get("/sync/v1/progress?since=2026-01-01T00:00:00+00:00")
    assert r.status_code == 401


async def test_post_progress_round_trips_finished_at(app_under_test):
    transport = ASGITransport(app=app_under_test)
    headers = _basic("alice", "alicepass")
    body = {
        "items": [
            {
                "document": {"metadata_id": "fa1", "content_hash": "fa1"},
                "locator": "loc",
                "percent": 0.99,
                "client_updated_at": "2026-05-09T12:00:00+00:00",
                "finished_at": "2026-05-09T12:00:00+00:00",
            }
        ]
    }
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post("/sync/v1/progress", json=body, headers=headers)
        assert r.status_code == 200, r.text
        r2 = await c.get("/sync/v1/progress?since=2026-01-01T00:00:00+00:00", headers=headers)
    items = r2.json()["items"]
    pulled = next(i for i in items if i["document"]["content_hash"] == "fa1")
    assert pulled["finished_at"] == "2026-05-09T12:00:00+00:00"


async def test_post_progress_omits_finished_at_when_absent(app_under_test):
    transport = ASGITransport(app=app_under_test)
    headers = _basic("alice", "alicepass")
    body = {
        "items": [
            {
                "document": {"metadata_id": "noFa", "content_hash": "noFa"},
                "locator": "loc",
                "percent": 0.5,
                "client_updated_at": "2026-05-09T12:00:00+00:00",
            }
        ]
    }
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post("/sync/v1/progress", json=body, headers=headers)
        assert r.status_code == 200
        r2 = await c.get("/sync/v1/progress?since=2026-01-01T00:00:00+00:00", headers=headers)
    items = r2.json()["items"]
    pulled = next(i for i in items if i["document"]["content_hash"] == "noFa")
    assert pulled["finished_at"] is None


async def test_post_progress_lww_overwrites_finished_with_unfinished(app_under_test):
    """Restart on a newer client must clear server-side finished_at."""
    transport = ASGITransport(app=app_under_test)
    headers = _basic("alice", "alicepass")
    base_doc = {"metadata_id": "lww", "content_hash": "lww"}
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # finished
        await c.post(
            "/sync/v1/progress",
            json={
                "items": [
                    {
                        "document": base_doc,
                        "locator": "end",
                        "percent": 0.99,
                        "client_updated_at": "2026-05-09T12:00:00+00:00",
                        "finished_at": "2026-05-09T12:00:00+00:00",
                    }
                ]
            },
            headers=headers,
        )
        # restart pushes newer unfinished
        r = await c.post(
            "/sync/v1/progress",
            json={
                "items": [
                    {
                        "document": base_doc,
                        "locator": "",
                        "percent": 0.0,
                        "client_updated_at": "2026-05-09T13:00:00+00:00",
                    }
                ]
            },
            headers=headers,
        )
        assert r.status_code == 200
        assert r.json()["results"][0]["status"] == "accepted"
        r2 = await c.get("/sync/v1/progress?since=2026-01-01T00:00:00+00:00", headers=headers)
    items = [i for i in r2.json()["items"] if i["document"]["content_hash"] == "lww"]
    assert len(items) == 1
    assert items[0]["percent"] == 0.0
    assert items[0]["finished_at"] is None
