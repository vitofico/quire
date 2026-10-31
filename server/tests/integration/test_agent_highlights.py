"""Real PostgreSQL coverage for authenticated, read-only highlight retrieval."""

import base64
import copy
import json
import logging
from pathlib import Path
from uuid import UUID, uuid4

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.exc import StatementError

from quire_server.db.models import Highlight, HighlightSyncState

PATH = "/agent/v1/highlights"
FIXTURE = json.loads((Path(__file__).parents[1] / "fixtures/highlight-sync.json").read_text())
progress = pytest.mark.requires_progress


def headers(user="alice"):
    token = base64.b64encode(f"{user}:{user}pass".encode()).decode()
    return {"Authorization": f"Basic {token}"}


def item(**updates):
    return copy.deepcopy(FIXTURE["push"]["items"][0]) | {"id": str(uuid4())} | updates


@pytest.fixture
async def client(app_under_test, session):
    async with AsyncClient(
        transport=ASGITransport(app=app_under_test), base_url="http://test"
    ) as client:
        yield client


async def push(client, rows, user="alice", auth=None):
    response = await client.post(
        "/sync/v1/highlights", headers=auth or headers(user), json={"items": rows}
    )
    assert response.status_code == 200, response.text


async def listing(client, user="alice", **params):
    response = await client.get(PATH, headers=headers(user), params=params)
    assert response.status_code == 200, response.text
    return response.json()


@progress
async def test_auth_exact_get_same_uuid_isolation_and_tombstones(client):
    ident = str(uuid4())
    alice, bob = item(id=ident), item(id=ident, text="Bob's private quote")
    missing = str(uuid4())
    assert (await client.get(PATH)).status_code == 401
    assert (await client.get(f"{PATH}/{ident}")).status_code == 401
    assert (await client.get(PATH, headers=headers("wrong"))).status_code == 401
    await push(client, [alice])
    assert (await listing(client, user="bob"))["items"] == []
    before = await client.get(f"{PATH}/{ident}", headers=headers("bob"))
    assert before.status_code == 404
    await push(client, [bob], user="bob")
    assert (await listing(client))["items"][0]["text"] == alice["text"]
    assert (await listing(client, user="bob"))["items"][0]["text"] == bob["text"]
    exact = await client.get(f"{PATH}/{ident}", headers=headers())
    assert exact.json() == alice
    assert "user_id" not in exact.json()
    assert (await client.get(f"{PATH}/{missing}", headers=headers())).status_code == 404
    tombstone = dict(alice, deleted_at=alice["updated_at"])
    await push(client, [tombstone])
    assert (await listing(client))["items"] == []
    deleted = await client.get(f"{PATH}/{ident}", headers=headers())
    assert deleted.status_code == 404 and deleted.json() == before.json()
    assert (await listing(client, user="bob"))["items"][0]["text"] == bob["text"]


@progress
async def test_browse_all_books_without_documents_and_does_not_write(client, session):
    rows = [
        item(id=str(UUID(int=2)), book_title="Second book", book_author=None),
        item(id=str(UUID(int=1)), book_title="First book", text="First quote"),
    ]
    await push(client, rows)
    revisions = (await session.scalars(select(Highlight.revision).order_by(Highlight.id))).all()
    state = await session.get(HighlightSyncState, "alice")
    page = await listing(client)
    assert [row["id"] for row in page["items"]] == [row["id"] for row in reversed(rows)]
    assert {row["book_title"] for row in page["items"]} == {"First book", "Second book"}
    assert page["items"][1]["book_author"] is None
    assert not any(row["text_truncated"] for row in page["items"])
    assert page["next_cursor"] is None and not page["has_more"]
    assert "locator" not in page["items"][0]
    # Re-read in the same database after several queries: browsing must not
    # allocate a sync revision, update rows or require a documents join.
    await client.get(f"{PATH}/{rows[0]['id']}", headers=headers())
    await session.refresh(state)
    assert state.revision == 2
    assert (
        await session.scalars(select(Highlight.revision).order_by(Highlight.id))
    ).all() == revisions


@progress
async def test_keyset_pagination_bound_to_account_and_normalized_filters(client):
    rows = [item(id=str(UUID(int=i)), text="matching") for i in range(1, 6)]
    await push(client, list(reversed(rows)))
    metadata = rows[0]["document"]["metadata_id"]
    first = await listing(client, q="matching", metadata_id=metadata, limit=2)
    assert [row["id"] for row in first["items"]] == [row["id"] for row in rows[:2]]
    assert first["has_more"]
    cursor = first["next_cursor"]
    # The normalized metadata spelling and page size can change.
    second = await listing(
        client, q="matching", metadata_id=f"URN:ISBN:{metadata}", limit=1, cursor=cursor
    )
    assert second["items"][0]["id"] == rows[2]["id"]
    last = await listing(
        client, q="matching", metadata_id=metadata, limit=100, cursor=second["next_cursor"]
    )
    assert [row["id"] for row in last["items"]] == [row["id"] for row in rows[3:]]
    assert not last["has_more"] and last["next_cursor"] is None
    for auth, params in [
        (headers("bob"), {"q": "matching", "metadata_id": metadata}),
        (headers(), {"q": "other", "metadata_id": metadata}),
        (headers(), {"q": "matching"}),
        (headers(), {"q": "matching", "metadata_id": metadata, "content_hash": "other"}),
    ]:
        response = await client.get(PATH, headers=auth, params=params | {"cursor": cursor})
        assert response.status_code == 422
        assert response.json() == {"detail": "invalid_cursor"}


@progress
@pytest.mark.parametrize("query", ["café", "😀", "%_", "\\", "' OR 1=1 --"])
async def test_literal_unicode_search_across_quote_title_and_author(client, query):
    rows = [
        item(text=f"Quote {query} tail", book_title="Ordinary", book_author=None),
        item(text="Ordinary", book_title=f"Title {query}", book_author=None),
        item(text="Ordinary", book_title="Ordinary", book_author=f"Author {query}"),
        item(text="Unrelated wildcard XYZ", book_title="Ordinary", book_author="Other"),
        item(text=f"Deleted {query}", deleted_at=1700000000000),
    ]
    await push(client, rows)
    await push(client, [item(text=f"Private {query}")], user="bob")
    found = await listing(client, q=query)
    assert {row["id"] for row in found["items"]} == {row["id"] for row in rows[:3]}
    if query == "café":
        assert {row["id"] for row in (await listing(client, q="CAFÉ"))["items"]} == {
            row["id"] for row in rows[:3]
        }


@progress
async def test_metadata_and_hash_filters_are_exact_and_combined(client):
    first = item(document={"metadata_id": "12345", "content_hash": "hash-one"})
    second = item(document={"metadata_id": "12345", "content_hash": "hash-two"})
    third = item(document={"metadata_id": "67890", "content_hash": "hash-one"})
    await push(client, [first, second, third])
    page = await listing(client, metadata_id=" URN:ISBN:1-2 345 ", content_hash=" hash-one ")
    assert [row["id"] for row in page["items"]] == [first["id"]]
    assert len((await listing(client, content_hash="hash-one"))["items"]) == 2
    assert (await listing(client, metadata_id="123"))["items"] == []


@progress
async def test_bounded_previews_and_get_preserve_full_quote_and_locator(client):
    row = item(
        document={"metadata_id": "m" * 2000, "content_hash": "hash", "identity_hash_version": 1},
        text="😀" * 3000 + "outside-preview",
        book_title="T" * 1100,
        book_author="A" * 1100,
        locator="opaque:" + "x" * 3000,
    )
    await push(client, [row])
    summary = (await listing(client))["items"][0]
    for name, size in [("text", 2000), ("book_title", 1000), ("book_author", 1000)]:
        assert summary[name] == row[name][:size]
        assert summary[name + "_truncated"] is True
    assert summary["document"]["metadata_id"] == "m" * 1024
    assert summary["metadata_id_truncated"] is True
    # Search matches the complete stored text, including text outside previews.
    assert (await listing(client, q="outside-preview"))["items"][0]["id"] == row["id"]
    exact = await client.get(f"{PATH}/{row['id']}", headers=headers())
    assert exact.json() == row


@progress
@pytest.mark.parametrize(
    "params",
    [
        {"limit": 0},
        {"limit": 101},
        {"q": " "},
        {"q": "x" * 513},
        {"metadata_id": " "},
        {"content_hash": " "},
        {"content_hash": "x" * 1025},
        {"user_id": "bob"},
        {"cursor": "%%%"},
        {"cursor": "e30"},
        {"cursor": base64.urlsafe_b64encode(b"[" * 1100).decode()},
        {"cursor": "x" * 2049},
    ],
)
async def test_invalid_queries_are_rejected(client, params):
    assert (await client.get(PATH, headers=headers(), params=params)).status_code == 422


@progress
async def test_only_read_methods_and_valid_uuid_get(client):
    for method in ["post", "put", "patch", "delete"]:
        assert (await getattr(client, method)(PATH, headers=headers())).status_code == 405
    assert (await client.get(f"{PATH}/not-a-uuid", headers=headers())).status_code == 422


@progress
@pytest.mark.parametrize("operation", ["list_highlights", "get_highlight"])
@pytest.mark.parametrize("failure", ["database", "serialization"])
async def test_unexpected_read_errors_do_not_expose_private_text(
    client, monkeypatch, caplog, operation, failure
):
    # pragma: allowlist nextline secret - test fixture
    secret_query = "private-query-value"
    # pragma: allowlist nextline secret - test fixture
    secret_quote = "private-stored-highlight"
    if failure == "database":
        error = StatementError(
            "read failed",
            "SELECT private_data WHERE text=:q",
            {"q": secret_query, "quote": secret_quote},
            RuntimeError("database unavailable"),
        )
    else:
        error = ValueError(f"invalid stored quote: {secret_quote}; query: {secret_query}")

    async def fail(*args):
        raise error

    monkeypatch.setattr(f"quire_server.api.agent_highlights.{operation}", fail)
    # Ignore the test client's informational URL log; uncaught server failures
    # would log errors with their SQL parameters or stored payload attached.
    caplog.set_level(logging.ERROR)
    path = PATH if operation == "list_highlights" else f"{PATH}/{uuid4()}"
    response = await client.get(path, headers=headers(), params={"q": secret_query})
    assert response.status_code == 503
    assert response.json() == {"detail": "highlight_read_failed"}
    for secret in (secret_query, secret_quote):
        assert secret not in response.text
        assert secret not in caplog.text


async def test_native_identity_reuses_existing_backend(client_factory, app, session):
    fixture_password = "reader-password"  # pragma: allowlist secret
    async with client_factory(
        auth_backend="native", progress_enabled="true", ai_enabled="false", skip_auth_overrides=True
    ) as client:
        await app.state.auth_backend.register(
            email="reader@example.com",
            password=fixture_password,
        )
        response = await client.post(
            "/auth/v1/login", json={"email": "reader@example.com", "password": fixture_password}
        )
        auth = {"Authorization": "Bearer " + response.json()["token"]}
        row = item(text="Native reader's private quote")
        await push(client, [row], auth=auth)
        response = await client.get(PATH, headers=auth)
        assert response.status_code == 200
        assert response.json()["items"][0]["text"] == row["text"]
        assert (await client.get(f"{PATH}/{row['id']}", headers=auth)).json() == row


@pytest.mark.parametrize(
    "progress_on,ai_on", [(True, True), (True, False), (False, True), (False, False)]
)
async def test_agent_router_follows_progress_mode(client_factory, session, progress_on, ai_on):
    async with client_factory(
        progress_enabled=str(progress_on).lower(),
        ai_enabled=str(ai_on).lower(),
        skip_auth_overrides=True,
    ) as client:
        for path in [PATH, f"{PATH}/{uuid4()}"]:
            assert (await client.get(path)).status_code == (401 if progress_on else 404)
