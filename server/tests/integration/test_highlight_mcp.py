"""Official SDK clients over real HTTP ASGI transport and PostgreSQL storage."""

import asyncio
import base64
import copy
import json
import logging
from contextlib import asynccontextmanager
from pathlib import Path
from uuid import uuid4

import httpx
import httpx2
import pytest
from mcp.client import Client, ClientSession
from mcp.client.streamable_http import streamable_http_client
from sqlalchemy import event

FIXTURE = json.loads((Path(__file__).parents[1] / "fixtures/highlight-sync.json").read_text())
pytestmark = pytest.mark.requires_progress


def basic(user="alice"):
    return "Basic " + base64.b64encode(f"{user}:{user}pass".encode()).decode()


def highlight(**updates):
    return copy.deepcopy(FIXTURE["push"]["items"][0]) | {"id": str(uuid4())} | updates


@asynccontextmanager
async def sdk_client(app, authorization):
    async with httpx2.AsyncClient(
        transport=httpx2.ASGITransport(app=app),
        base_url="http://localhost",
        headers={"Authorization": authorization},
    ) as client:
        async with streamable_http_client("http://localhost/mcp", http_client=client) as streams:
            async with ClientSession(*streams) as session:
                initialized = await session.initialize()
                assert initialized.server_info.name == "Quire highlights"
                yield session, client


async def seed(app, authorization, rows):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://localhost"
    ) as client:
        response = await client.post(
            "/sync/v1/highlights", headers={"Authorization": authorization}, json={"items": rows}
        )
        assert response.status_code == 200, response.text


async def test_sdk_initialize_discovery_calls_and_request_auth(app_under_test, caplog, session):
    alice = highlight(text="Unique MCP private quote")
    await seed(app_under_test, basic(), [alice])
    async with app_under_test.router.lifespan_context(app_under_test):
        async with sdk_client(app_under_test, basic()) as (sdk, http):
            tools = (await sdk.list_tools()).tools
            assert {tool.name for tool in tools} == {
                "list_highlights",
                "search_highlights",
                "get_highlight",
            }
            assert all(tool.annotations.read_only_hint for tool in tools)
            assert all(not tool.annotations.destructive_hint for tool in tools)
            schemas = {tool.name: tool.input_schema for tool in tools}
            assert schemas["list_highlights"]["properties"]["limit"]["maximum"] == 100
            assert schemas["search_highlights"]["properties"]["q"]["maxLength"] == 512
            assert "user_id" not in json.dumps(schemas)
            with caplog.at_level(logging.DEBUG):
                found = await sdk.call_tool("search_highlights", {"q": alice["text"]})
                assert found.structured_content["items"][0]["id"] == alice["id"]
                exact = await sdk.call_tool("get_highlight", {"highlight_id": alice["id"]})
                assert exact.structured_content == alice
            server_logs = "\n".join(
                record.getMessage()
                for record in caplog.records
                if not record.name.startswith("mcp.client")
            )
            assert alice["text"] not in server_logs
            assert basic() not in server_logs
            assert (await sdk.call_tool("list_highlights", {"limit": 101})).is_error
            assert (await sdk.call_tool("search_highlights", {"q": " "})).is_error
            assert (await sdk.call_tool("list_highlights", {"cursor": "broken"})).is_error
            assert (await sdk.call_tool("delete_highlight", {"highlight_id": alice["id"]})).is_error
            # Authentication is checked again after initialization, including
            # tools/list; there is no session whose identity can be reused.
            http.headers.pop("authorization")
            unauthenticated = await http.post(
                "/mcp", json={"jsonrpc": "2.0", "id": 90, "method": "tools/list"}
            )
            assert unauthenticated.status_code == 401
            for method in ("GET", "DELETE", "OPTIONS", "PUT"):
                assert (await http.request(method, "/mcp")).status_code == 401


async def test_concurrent_principals_guessed_id_and_read_only(app_under_test, session):
    ident = str(uuid4())
    alice, bob = highlight(id=ident, text="Alice private"), highlight(id=ident, text="Bob private")
    other = highlight(text="Alice other")
    tombstone = highlight(deleted_at=FIXTURE["push"]["items"][0]["updated_at"])
    await seed(app_under_test, basic(), [alice, other, tombstone])
    await seed(app_under_test, basic("bob"), [bob])
    statements = []

    def record(conn, cursor, statement, parameters, context, executemany):
        statements.append(statement.lstrip().split(None, 1)[0].upper())

    # Listen on every connection, including the app factory's own engine.
    from sqlalchemy.engine import Engine

    event.listen(Engine, "before_cursor_execute", record)
    try:
        async with app_under_test.router.lifespan_context(app_under_test):
            async with sdk_client(app_under_test, basic()) as (a, _):
                async with sdk_client(app_under_test, basic("bob")) as (b, _):
                    for _ in range(3):
                        result_a, result_b = await asyncio.gather(
                            a.call_tool("get_highlight", {"highlight_id": ident}),
                            b.call_tool("get_highlight", {"highlight_id": ident}),
                        )
                        assert result_a.structured_content["text"] == "Alice private"
                        assert result_b.structured_content["text"] == "Bob private"
                    foreign = await b.call_tool("get_highlight", {"highlight_id": other["id"]})
                    missing = await b.call_tool("get_highlight", {"highlight_id": str(uuid4())})
                    deleted = await a.call_tool("get_highlight", {"highlight_id": tombstone["id"]})
                    assert (
                        foreign.is_error and foreign.content == missing.content == deleted.content
                    )
                    listing = await a.call_tool("list_highlights", {"limit": 1})
                    assert listing.structured_content["has_more"]
                    cursor = listing.structured_content["next_cursor"]
                    next_page = await a.call_tool("list_highlights", {"cursor": cursor})
                    assert len(next_page.structured_content["items"]) == 1
                    assert (await b.call_tool("list_highlights", {"cursor": cursor})).is_error
    finally:
        event.remove(Engine, "before_cursor_execute", record)
    assert statements and set(statements) == {"SELECT"}


async def test_native_bearer_cookie_rejection_and_revocation(
    app_under_test, monkeypatch, postgres_url, session
):
    from quire_server.config import get_settings
    from quire_server.main import create_app

    monkeypatch.setenv("QUIRE_SERVER_AUTH_BACKEND", "native")
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")
    monkeypatch.setenv("QUIRE_SERVER_DATABASE_URL", postgres_url)
    get_settings.cache_clear()
    app = create_app()
    backend = app.state.auth_backend
    email = f"mcp-{uuid4()}@example.com"
    fixture_password = "strong-test-password"  # pragma: allowlist secret
    await backend.register(email=email, password=fixture_password)
    token, _ = await backend.login(email=email, password=fixture_password)
    row = highlight(text="Native MCP quote")
    await seed(app, f"Bearer {token}", [row])
    async with app.router.lifespan_context(app):
        async with sdk_client(app, f"Bearer {token}") as (sdk, client):
            assert (
                await sdk.call_tool("get_highlight", {"highlight_id": row["id"]})
            ).structured_content == row
            client.headers.pop("authorization")
            client.cookies.set("quire-session", token)
            assert (await client.get("/mcp")).status_code == 401
            assert (await client.post("/mcp", json={})).status_code in {401, 403}
            from quire_server.core.native_auth import hash_session_token

            await backend.revoke(hash_session_token(token))
            client.headers["authorization"] = f"Bearer {token}"
            assert (await client.post("/mcp", json={})).status_code == 401
    get_settings.cache_clear()


async def test_transport_security_stateless_replay_and_body_limit(app_under_test, session):
    async with app_under_test.router.lifespan_context(app_under_test):
        async with sdk_client(app_under_test, basic()) as (sdk, client):
            for origin in ("https://evil.example", "null", ""):
                response = await client.post("/mcp", headers={"Origin": origin}, json={})
                assert response.status_code == 403
            for host in ("evil.example", "localhost.evil.example", "localhost:evil.example"):
                assert (
                    await client.post("/mcp", headers={"Host": host}, json={})
                ).status_code == 421
            ambiguous = await client.post(
                "/mcp", headers=[("Origin", "null"), ("Origin", "https://evil.example")], json={}
            )
            assert ambiguous.status_code == 400
            large = await client.post("/mcp", content=b"x" * 1_048_577)
            assert large.status_code == 413
            assert "x-request-id" in large.headers
            client.headers["Mcp-Session-Id"] = "another-readers-session"
            assert not (await sdk.list_tools()).tools == []
            client.headers.pop("authorization")
            assert (await client.post("/mcp", json={})).status_code == 401


async def test_cancelled_sdk_call_cleans_up_request(app_under_test, monkeypatch, session):
    from quire_server import mcp

    started, finished = asyncio.Event(), asyncio.Event()

    async def blocked_read(*args):
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            finished.set()

    monkeypatch.setattr(mcp, "query_highlights", blocked_read)
    async with app_under_test.router.lifespan_context(app_under_test):
        async with httpx2.AsyncClient(
            transport=httpx2.ASGITransport(app_under_test), headers={"Authorization": basic()}
        ) as http:
            # Modern MCP cancels by aborting the HTTP request. Handshake-era
            # cancellation notifications cannot address a separate stateless POST.
            async with Client(
                streamable_http_client("http://localhost/mcp", http_client=http),
                mode="2026-07-28",
                cache=None,
            ) as sdk:
                call = asyncio.create_task(sdk.call_tool("list_highlights", {}))
                await asyncio.wait_for(started.wait(), timeout=5)
                call.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await call
                await asyncio.wait_for(finished.wait(), timeout=5)
