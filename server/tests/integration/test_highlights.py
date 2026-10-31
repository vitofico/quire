"""Real PostgreSQL regressions for the private highlight sync contract."""

import asyncio
import copy
import json
from pathlib import Path
from uuid import uuid4

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import async_sessionmaker

from quire_server.api.highlights import HighlightItem, conflict_key, lock_user
from quire_server.db.models import Document, Highlight, HighlightSyncState

pytestmark = pytest.mark.requires_progress
FIXTURE = json.loads((Path(__file__).parents[1] / "fixtures/highlight-sync.json").read_text())
PATH = "/sync/v1/highlights"


@pytest.mark.parametrize("case", FIXTURE["conflict_cases"], ids=lambda case: case["name"])
def test_shared_conflict_order(case):
    left, right = HighlightItem(**case["left"]), HighlightItem(**case["right"])
    assert max([left, right], key=conflict_key).model_dump(mode="json") == case["winner"]
    assert max([right, left], key=conflict_key).model_dump(mode="json") == case["winner"]


@pytest.fixture
async def client(app_under_test, session):
    async with AsyncClient(
        transport=ASGITransport(app=app_under_test), base_url="http://test"
    ) as c:
        yield c


def headers(user="alice"):
    import base64

    return {"Authorization": "Basic " + base64.b64encode(f"{user}:{user}pass".encode()).decode()}


def item(**updates):
    return copy.deepcopy(FIXTURE["push"]["items"][0]) | updates


async def push(client, rows, user="alice"):
    response = await client.post(PATH, headers=headers(user), json={"items": rows})
    assert response.status_code == 200, response.text
    return response.json()["results"]


async def pull(client, since="0", limit=500, user="alice"):
    response = await client.get(
        PATH, headers=headers(user), params={"since": since, "limit": limit}
    )
    assert response.status_code == 200, response.text
    return response.json()


async def test_shared_fixture_opaque_locator_normalization_and_replay(client):
    response = await client.post(PATH, headers=headers(), json=FIXTURE["push"])
    assert response.status_code == 200, response.text
    assert response.json() == FIXTURE["expected_results"]
    assert await pull(client) == FIXTURE["pull"]
    tombstone = FIXTURE["pull"]["items"][0]
    assert (await push(client, [tombstone]))[0]["status"] == "accepted"
    assert await pull(client, "2") == {"items": [], "next_cursor": "2", "has_more": False}
    normalized = item(id=str(uuid4()), document=FIXTURE["normalization_input"])
    winner = (await push(client, [normalized]))[0]["item"]
    assert winner["document"] == FIXTURE["push"]["items"][0]["document"]
    assert winner["locator"] == normalized["locator"]


async def test_auth_and_same_uuid_tenant_isolation(client, session):
    assert (await client.get(PATH)).status_code == 401
    assert (await client.post(PATH, json=FIXTURE["push"])).status_code == 401
    assert (await client.get(PATH, headers=headers("wrong"))).status_code == 401
    alice, bob = item(), item(text="Bob private quote")
    await push(client, [alice])
    assert (await pull(client, user="bob"))["items"] == []
    await push(client, [bob], user="bob")
    assert (await pull(client))["items"] == [alice]
    assert (await pull(client, user="bob"))["items"] == [bob]
    assert len((await session.scalars(select(Highlight))).all()) == 2


async def test_equal_timestamp_conflicts_converge_and_tombstone_blocks_replay(client):
    # Exercise every distinguishing field, including supplementary Unicode
    # versus a BMP character (different UTF-16 and UTF-8 lexical order).
    low = item(text="\ue000")
    high = item(text="😀")
    assert conflict_key(HighlightItem(**high)) > conflict_key(HighlightItem(**low))
    for order in ([low, high], [high, low]):
        ident = str(uuid4())
        await push(client, [dict(row, id=ident) for row in order])
        winner = (await push(client, [dict(low, id=ident)]))[0]
        assert winner == {"status": "stale", "item": dict(high, id=ident)}
    live = item(id=str(uuid4()), book_author="")
    tombstone = dict(live, deleted_at=live["updated_at"])
    await push(client, [tombstone])
    assert (await push(client, [live]))[0] == {"status": "stale", "item": tombstone}
    older = dict(live, updated_at=live["updated_at"] - 1)
    assert (await push(client, [older]))[0]["item"] == tombstone


async def test_identity_version_never_downgrades_even_with_newer_payload(client):
    original = item()
    original["document"]["identity_hash_version"] = 2
    await push(client, [original])
    edit = item(colour="PINK", updated_at=original["updated_at"] + 1)
    expected = copy.deepcopy(edit)
    expected["document"]["identity_hash_version"] = 2
    assert (await push(client, [edit]))[0] == {"status": "accepted", "item": expected}
    # A stale payload can still teach us a newer algorithm version; it must
    # produce a delta without reverting the winning quote, colour or clock.
    stale = item()
    stale["document"]["identity_hash_version"] = 3
    expected["document"]["identity_hash_version"] = 3
    assert (await push(client, [stale]))[0] == {"status": "stale", "item": expected}
    assert (await pull(client, "2"))["items"] == [expected]


async def test_equal_clock_version_upgrade_does_not_make_conflicts_order_dependent(client):
    higher_version = item(colour="GREEN")
    higher_version["document"]["identity_hash_version"] = 2
    higher_colour = item(colour="YELLOW")
    for rows in ([higher_version, higher_colour], [higher_colour, higher_version]):
        ident = str(uuid4())
        await push(client, [dict(row, id=ident) for row in rows])
        winner = (await push(client, [dict(higher_colour, id=ident)]))[0]["item"]
        assert winner["colour"] == "YELLOW"
        assert winner["document"]["identity_hash_version"] == 2


async def test_delta_pages_offline_timestamps_mutations_and_download_removal(client, session):
    rows = [item(id=str(uuid4()), updated_at=1700000000100) for _ in range(3)]
    await push(client, rows)
    first = await pull(client, limit=1)
    assert first["items"] == rows[:1] and first["has_more"]
    # A row already delivered mutates after page 1; its new revision must
    # appear again after the other rows even though the client clock is old.
    changed = dict(rows[0], colour="PINK", updated_at=rows[0]["updated_at"] + 1)
    await push(client, [changed])
    seen = []
    cursor = first["next_cursor"]
    while True:
        page = await pull(client, cursor, limit=1)
        seen.extend(page["items"])
        cursor = page["next_cursor"]
        if not page["has_more"]:
            break
    assert seen == rows[1:] + [changed]
    assert cursor == "4"
    offline = item(id=str(uuid4()), created_at=1, updated_at=2)
    await push(client, [offline])
    assert (await pull(client, cursor))["items"] == [offline]
    await session.execute(delete(Document).where(Document.user_id == "alice"))
    await session.commit()
    assert len((await pull(client))["items"]) == 4
    bad = await client.get(PATH, headers=headers(), params={"since": "999"})
    assert bad.status_code == 409
    assert bad.json()["detail"] == "highlight_cursor_reset_required"


async def test_transaction_lock_prevents_uncommitted_revision_holes(client, engine):
    factory = async_sessionmaker(engine, expire_on_commit=False)
    first = item()
    async with factory() as pending:
        await lock_user(pending, "alice")
        state = HighlightSyncState(user_id="alice", revision=1)
        row = Highlight(
            user_id="alice",
            revision=1,
            **HighlightItem(**first).model_dump(exclude={"document"}),
            **first["document"],
        )
        pending.add_all([state, row])
        await pending.flush()  # revision allocated but transaction not yet committed
        reading = asyncio.create_task(pull(client, limit=1))
        writing = asyncio.create_task(push(client, [item(id=str(uuid4()), text="Second")]))
        for task in (reading, writing):
            with pytest.raises(TimeoutError):
                await asyncio.wait_for(asyncio.shield(task), 0.1)
        await pending.commit()
        page, _ = await asyncio.gather(reading, writing)
    assert page["items"] == [first]
    assert page["next_cursor"] == "1"
    assert (await pull(client, "1"))["items"][0]["text"] == "Second"
    # Rollback cannot leave an allocated revision visible to a pull.
    async with factory() as pending:
        await lock_user(pending, "alice")
        state = await pending.get(HighlightSyncState, "alice")
        state.revision += 1
        await pending.flush()
        await pending.rollback()
    await push(client, [item(id=str(uuid4()), text="Third")])
    assert (await pull(client, "2"))["next_cursor"] == "3"


async def test_concurrent_same_uuid_writes_have_one_deterministic_winner(client):
    rows = [item(colour=c) for c in ["YELLOW", "PINK", "GREEN", "YELLOW"]]
    await asyncio.gather(*(push(client, [row]) for row in rows))
    assert (await pull(client))["items"] == [
        max(rows, key=lambda r: conflict_key(HighlightItem(**r)))
    ]


@pytest.mark.parametrize(
    "change",
    [
        {"id": "not-a-uuid"},
        {"locator": " "},
        {"text": ""},
        {"colour": "RED"},
        {"created_at": -1},
        {"updated_at": 1},
        {"deleted_at": 2**63 - 1},
        {"document": {"content_hash": " "}},
    ],
)
async def test_invalid_highlight_rejects_entire_batch(client, change):
    response = await client.post(PATH, headers=headers(), json={"items": [item(), item(**change)]})
    assert response.status_code == 422
    assert (await pull(client))["items"] == []


@pytest.mark.parametrize(
    "params", [{"since": "-1"}, {"since": "x"}, {"since": str(2**63)}, {"limit": 0}, {"limit": 501}]
)
async def test_invalid_cursor_and_page_size(client, params):
    assert (await client.get(PATH, headers=headers(), params=params)).status_code == 422
