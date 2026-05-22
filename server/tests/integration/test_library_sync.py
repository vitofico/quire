"""Integration tests for `POST /library/v1/sync` (Phase 0, task S-2).

The bulk-push endpoint coexists with the existing per-item PUT/GET/DELETE.
Tests here mirror the structure of `test_library_items.py` (same fixtures,
same Basic-auth setup, same `unique_user` isolation strategy) so the two
test files read together as one cohesive suite for `/library/v1/*`.

Spec reference:
`docs/superpowers/specs/2026-05-22-quire-monetization-design.md`,
sections "Architectural changes → Push-model API" and "Build sequence
→ Phase 0 item (2)".
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
    user = f"user-{uuid.uuid4().hex[:8]}"
    pw = "pw"
    cwa_users[user] = pw
    return user, pw


def _entry(**overrides) -> dict:
    """Build a `present` sync entry. Required-ish defaults match the PUT path."""
    base = {
        "content_hash": "ch-1",
        "status": "present",
        "metadata_id": "md-1",
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
    return base


# ---------------------------------------------------------------------------
# Happy paths
# ---------------------------------------------------------------------------


async def test_sync_happy_path_creates_multiple_books(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    payload = {
        "items": [
            _entry(content_hash=f"ch-{i}", metadata_id=f"md-{i}", title=f"Book {i}")
            for i in range(3)
        ]
    }
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post("/library/v1/sync", json=payload, headers=headers)
        assert r.status_code == 200, r.text
        summary = r.json()
        assert summary["received"] == 3
        assert summary["processed"] == 3
        assert summary["created"] == 3
        assert summary["updated"] == 0
        assert summary["reactivated"] == 0
        assert summary["deleted"] == 0
        assert summary["skipped"] == 0
        assert summary["missing_deleted"] == 0
        assert summary["server_time"]

        r2 = await c.get("/library/v1/items", headers=headers)
        items = r2.json()["items"]
        assert len(items) == 3
        assert {it["content_hash"] for it in items} == {"ch-0", "ch-1", "ch-2"}


async def test_sync_idempotent_does_not_bump_updated_at(app_under_test, unique_user):
    """Replay of an identical payload must not flood `?since=`.

    The architect-flagged no-op-replay footgun: bumping `updated_at` on
    every replay would deliver the whole library on the next delta sync
    even when nothing changed.
    """
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    payload = {"items": [_entry()]}
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r1 = await c.post("/library/v1/sync", json=payload, headers=headers)
        assert r1.json()["created"] == 1

        first_get = (await c.get("/library/v1/items", headers=headers)).json()
        first_updated_at = first_get["items"][0]["updated_at"]

        await asyncio.sleep(0.02)
        r2 = await c.post("/library/v1/sync", json=payload, headers=headers)
        assert r2.status_code == 200
        summary = r2.json()
        assert summary["created"] == 0
        assert summary["updated"] == 0
        assert summary["skipped"] == 1

        second_get = (await c.get("/library/v1/items", headers=headers)).json()
        assert second_get["items"][0]["updated_at"] == first_updated_at


async def test_sync_partial_update_changes_one_field_bumps_updated(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.post("/library/v1/sync", json={"items": [_entry()]}, headers=headers)
        first = (await c.get("/library/v1/items", headers=headers)).json()["items"][0]

        await asyncio.sleep(0.02)
        r = await c.post(
            "/library/v1/sync",
            json={"items": [_entry(title="Foundation (Revised)")]},
            headers=headers,
        )
        assert r.json()["updated"] == 1
        second = (await c.get("/library/v1/items", headers=headers)).json()["items"][0]
        assert second["title"] == "Foundation (Revised)"
        assert second["updated_at"] > first["updated_at"]


async def test_sync_empty_items_is_noop(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post("/library/v1/sync", json={"items": []}, headers=headers)
    assert r.status_code == 200
    summary = r.json()
    assert summary["received"] == 0
    assert summary["processed"] == 0
    assert summary["created"] == summary["updated"] == summary["deleted"] == 0


# ---------------------------------------------------------------------------
# identity_hash_version
# ---------------------------------------------------------------------------


async def test_sync_identity_hash_version_round_trips(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.post(
            "/library/v1/sync",
            json={"items": [_entry(identity_hash_version=2)]},
            headers=headers,
        )
        items = (await c.get("/library/v1/items", headers=headers)).json()["items"]
    assert items[0]["identity_hash_version"] == 2


async def test_sync_identity_hash_version_downgrade_protection(app_under_test, unique_user):
    """v1 push after v2 must NOT downgrade — `max(existing, incoming)` rule."""
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # Write v2.
        await c.post(
            "/library/v1/sync",
            json={"items": [_entry(identity_hash_version=2)]},
            headers=headers,
        )
        # Replay default (v1) — same payload otherwise. Must skip (no
        # downgrade, no content change).
        r = await c.post("/library/v1/sync", json={"items": [_entry()]}, headers=headers)
        assert r.json()["skipped"] == 1
        items = (await c.get("/library/v1/items", headers=headers)).json()["items"]
    assert items[0]["identity_hash_version"] == 2


async def test_sync_rejects_zero_identity_hash_version(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post(
            "/library/v1/sync",
            json={"items": [_entry(identity_hash_version=0)]},
            headers=headers,
        )
    assert r.status_code == 422, r.text


# ---------------------------------------------------------------------------
# Bounded payload
# ---------------------------------------------------------------------------


async def test_sync_rejects_over_max_items(app_under_test, unique_user, monkeypatch):
    """Lower the cap for the test to keep the payload small."""
    from quire_server.api import library as library_mod

    # Replace the dependency cleanly with a tiny limit. The dependency-
    # override path on the app is simpler than reaching into Settings.
    from quire_server.config import Settings, get_settings

    def small_settings() -> Settings:
        s = get_settings().model_copy()
        s.library_sync_max_items = 2
        return s

    app_under_test.dependency_overrides[get_settings] = small_settings
    try:
        transport = ASGITransport(app=app_under_test)
        headers = _basic(*unique_user)
        payload = {
            "items": [
                _entry(content_hash=f"ch-{i}", metadata_id=f"md-{i}", title=f"Book {i}")
                for i in range(3)  # 3 > limit=2
            ]
        }
        async with AsyncClient(transport=transport, base_url="http://test") as c:
            r = await c.post("/library/v1/sync", json=payload, headers=headers)
        assert r.status_code == 422
        detail = r.json()["detail"]
        assert detail["error"] == "too_many_items"
        assert detail["limit"] == 2
        assert detail["received"] == 3
    finally:
        app_under_test.dependency_overrides.pop(get_settings, None)
        # Touching the unused alias keeps the linter from pruning the import
        # if test layout changes later.
        _ = library_mod  # noqa: F841


# ---------------------------------------------------------------------------
# Auth + isolation
# ---------------------------------------------------------------------------


async def test_sync_requires_auth(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post("/library/v1/sync", json={"items": [_entry()]})
    assert r.status_code == 401


async def test_sync_user_isolation(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.post(
            "/library/v1/sync",
            json={"items": [_entry()]},
            headers=_basic("alice", "alicepass"),
        )
        r = await c.get("/library/v1/items", headers=_basic("bob", "bobpass"))
    assert r.status_code == 200
    assert r.json()["items"] == []


# ---------------------------------------------------------------------------
# Tombstone semantics
# ---------------------------------------------------------------------------


async def test_sync_status_deleted_tombstones_existing_row(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.post("/library/v1/sync", json={"items": [_entry()]}, headers=headers)
        r = await c.post(
            "/library/v1/sync",
            json={"items": [{"content_hash": "ch-1", "status": "deleted"}]},
            headers=headers,
        )
        assert r.status_code == 200
        assert r.json()["deleted"] == 1

        # No `since` → tombstones omitted, alive set is empty.
        alive = (await c.get("/library/v1/items", headers=headers)).json()
        assert alive["items"] == []

        # `since=epoch` → tombstone delivered.
        with_tombs = await c.get(
            "/library/v1/items?since=1970-01-01T00%3A00%3A00%2B00%3A00",
            headers=headers,
        )
        items = with_tombs.json()["items"]
        assert len(items) == 1
        assert items[0]["deleted_at"] is not None


async def test_sync_status_deleted_for_unknown_is_skipped(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post(
            "/library/v1/sync",
            json={"items": [{"content_hash": "never-seen", "status": "deleted"}]},
            headers=headers,
        )
        assert r.status_code == 200
        summary = r.json()
        assert summary["missing_deleted"] == 1
        assert summary["deleted"] == 0

        # No phantom row was created.
        with_tombs = await c.get(
            "/library/v1/items?since=1970-01-01T00%3A00%3A00%2B00%3A00",
            headers=headers,
        )
        assert with_tombs.json()["items"] == []


async def test_sync_repeat_delete_is_idempotent_and_preserves_timestamps(
    app_under_test, unique_user
):
    """A second `status=deleted` for an already-tombstoned row must NOT
    bump `updated_at` (would re-deliver the tombstone forever on
    `?since=`)."""
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.post("/library/v1/sync", json={"items": [_entry()]}, headers=headers)
        await c.post(
            "/library/v1/sync",
            json={"items": [{"content_hash": "ch-1", "status": "deleted"}]},
            headers=headers,
        )
        snap = await c.get(
            "/library/v1/items?since=1970-01-01T00%3A00%3A00%2B00%3A00",
            headers=headers,
        )
        first_updated = snap.json()["items"][0]["updated_at"]

        await asyncio.sleep(0.02)
        r = await c.post(
            "/library/v1/sync",
            json={"items": [{"content_hash": "ch-1", "status": "deleted"}]},
            headers=headers,
        )
        assert r.json()["skipped"] == 1
        assert r.json()["deleted"] == 0

        snap2 = await c.get(
            "/library/v1/items?since=1970-01-01T00%3A00%3A00%2B00%3A00",
            headers=headers,
        )
    assert snap2.json()["items"][0]["updated_at"] == first_updated


async def test_sync_deleted_then_present_reactivates(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.post("/library/v1/sync", json={"items": [_entry()]}, headers=headers)
        await c.post(
            "/library/v1/sync",
            json={"items": [{"content_hash": "ch-1", "status": "deleted"}]},
            headers=headers,
        )
        r = await c.post("/library/v1/sync", json={"items": [_entry()]}, headers=headers)
        assert r.status_code == 200
        assert r.json()["reactivated"] == 1

        alive = (await c.get("/library/v1/items", headers=headers)).json()["items"]
    assert len(alive) == 1
    assert alive[0]["deleted_at"] is None


# ---------------------------------------------------------------------------
# Intra-payload dedup & conflict detection
# ---------------------------------------------------------------------------


async def test_sync_dedup_collapses_identical_duplicates(app_under_test, unique_user):
    """Same content_hash listed twice, identical → treated as one entry."""
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post(
            "/library/v1/sync",
            json={"items": [_entry(), _entry()]},
            headers=headers,
        )
        assert r.status_code == 200
        summary = r.json()
        assert summary["received"] == 2
        assert summary["processed"] == 1
        assert summary["created"] == 1


async def test_sync_dedup_rejects_conflicting_status_duplicates(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post(
            "/library/v1/sync",
            json={
                "items": [
                    _entry(),
                    {"content_hash": "ch-1", "status": "deleted"},
                ]
            },
            headers=headers,
        )
    assert r.status_code == 422
    assert r.json()["detail"]["error"] == "duplicate_content_hash_conflict"


async def test_sync_dedup_rejects_conflicting_metadata_duplicates(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post(
            "/library/v1/sync",
            json={
                "items": [
                    _entry(title="Title A"),
                    _entry(title="Title B"),
                ]
            },
            headers=headers,
        )
    assert r.status_code == 422
    assert r.json()["detail"]["error"] == "duplicate_content_hash_conflict"


async def test_sync_intra_payload_metadata_id_collision_rejected(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post(
            "/library/v1/sync",
            json={
                "items": [
                    _entry(content_hash="ch-A", metadata_id="MD-X"),
                    _entry(content_hash="ch-B", metadata_id="MD-X"),
                ]
            },
            headers=headers,
        )
    assert r.status_code == 422
    assert r.json()["detail"]["error"] == "metadata_id_collision_in_payload"


# ---------------------------------------------------------------------------
# Cross-row conflicts and atomicity
# ---------------------------------------------------------------------------


async def test_sync_metadata_id_conflict_against_existing_row(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # Seed row A with metadata_id=MD-X.
        await c.post(
            "/library/v1/sync",
            json={"items": [_entry(content_hash="ch-A", metadata_id="MD-X")]},
            headers=headers,
        )
        # Push a different content_hash claiming MD-X → 409.
        r = await c.post(
            "/library/v1/sync",
            json={"items": [_entry(content_hash="ch-B", metadata_id="MD-X")]},
            headers=headers,
        )
    assert r.status_code == 409
    detail = r.json()["detail"]
    assert detail["error"] == "metadata_id_conflict"
    assert detail["existing_content_hash"] == "ch-A"
    assert detail["incoming_content_hash"] == "ch-B"


async def test_sync_metadata_conflict_aborts_whole_batch_atomically(app_under_test, unique_user):
    """A valid entry sharing a batch with a 409-triggering entry must NOT
    commit. All-or-nothing per request."""
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # Seed: ch-A holds MD-X.
        await c.post(
            "/library/v1/sync",
            json={"items": [_entry(content_hash="ch-A", metadata_id="MD-X")]},
            headers=headers,
        )
        # Batch: a fresh ch-C entry (would otherwise be created) + a
        # conflict-triggering ch-B claiming MD-X.
        r = await c.post(
            "/library/v1/sync",
            json={
                "items": [
                    _entry(content_hash="ch-C", metadata_id="MD-Y", title="Fresh"),
                    _entry(content_hash="ch-B", metadata_id="MD-X", title="Conflict"),
                ]
            },
            headers=headers,
        )
        assert r.status_code == 409
        # Atomicity: ch-C must not have been committed.
        items = (await c.get("/library/v1/items", headers=headers)).json()["items"]
        hashes = {it["content_hash"] for it in items}
        assert hashes == {"ch-A"}


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------


async def test_sync_present_without_title_rejected(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post(
            "/library/v1/sync",
            json={"items": [{"content_hash": "ch-X", "status": "present"}]},
            headers=headers,
        )
    assert r.status_code == 422


async def test_sync_deleted_entry_without_title_accepted(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # Seed first so the delete has something to act on.
        await c.post("/library/v1/sync", json={"items": [_entry()]}, headers=headers)
        r = await c.post(
            "/library/v1/sync",
            json={"items": [{"content_hash": "ch-1", "status": "deleted"}]},
            headers=headers,
        )
    assert r.status_code == 200
    assert r.json()["deleted"] == 1


async def test_sync_last_seen_at_naive_rejected(app_under_test, unique_user):
    """A naive datetime would silently poison future comparisons — reject."""
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post(
            "/library/v1/sync",
            json={"items": [_entry(last_seen_at="2026-05-22T10:00:00")]},  # no tz
            headers=headers,
        )
    assert r.status_code == 422


async def test_sync_last_seen_at_aware_accepted_but_not_durable(app_under_test, unique_user):
    """An aware `last_seen_at` is accepted, validated, and dropped on the
    server side (no column to persist into in v1)."""
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post(
            "/library/v1/sync",
            json={"items": [_entry(last_seen_at="2026-05-22T10:00:00+00:00")]},
            headers=headers,
        )
    assert r.status_code == 200, r.text
    assert r.json()["created"] == 1


async def test_sync_missing_content_hash_rejected(app_under_test, unique_user):
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    payload = {"items": [_entry()]}
    payload["items"][0].pop("content_hash")
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post("/library/v1/sync", json=payload, headers=headers)
    assert r.status_code == 422


# ---------------------------------------------------------------------------
# Coexistence with existing PUT/GET/DELETE
# ---------------------------------------------------------------------------


async def test_sync_coexists_with_per_item_put(app_under_test, unique_user):
    """A row created via PUT can be updated via sync, and vice versa.

    The two endpoints share the same uniqueness contract on
    `(user_id, content_hash)`; this test guards against drift between
    them (e.g. a future change that gives sync its own table or its own
    upsert semantics).
    """
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # PUT-create.
        r1 = await c.put(
            "/library/v1/items",
            json={"item": {
                "content_hash": "ch-shared",
                "metadata_id": "md-shared",
                "title": "Via PUT",
                "authors": ["Author"],
            }},
            headers=headers,
        )
        assert r1.status_code == 200

        # Sync-update.
        r2 = await c.post(
            "/library/v1/sync",
            json={
                "items": [
                    _entry(
                        content_hash="ch-shared",
                        metadata_id="md-shared",
                        title="Via Sync",
                    )
                ]
            },
            headers=headers,
        )
        assert r2.status_code == 200
        assert r2.json()["updated"] == 1

        items = (await c.get("/library/v1/items", headers=headers)).json()["items"]
        assert len(items) == 1
        assert items[0]["title"] == "Via Sync"


async def test_existing_per_item_endpoints_still_work(app_under_test, unique_user):
    """Regression guard — the new endpoint must not have broken the
    existing per-item flow."""
    transport = ASGITransport(app=app_under_test)
    headers = _basic(*unique_user)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.put(
            "/library/v1/items",
            json={"item": {
                "content_hash": "ch-put",
                "metadata_id": "md-put",
                "title": "Put",
                "authors": [],
            }},
            headers=headers,
        )
        assert r.status_code == 200
        items = (await c.get("/library/v1/items", headers=headers)).json()["items"]
        assert len(items) == 1
        r2 = await c.request(
            "DELETE",
            "/library/v1/items",
            json={"item": {"content_hash": "ch-put"}},
            headers=headers,
        )
    assert r2.status_code == 200
    assert r2.json()["deleted_at"] is not None
