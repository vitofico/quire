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
