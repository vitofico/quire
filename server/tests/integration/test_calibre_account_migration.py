import asyncio

import httpx
import pytest
from sqlalchemy import select

from quire_server.core.auth import CalibreAuthValidator
from quire_server.core.auth_backend import CalibreWebBasicAuth
from quire_server.db.models import CalibreAccountBinding, LibraryItem
from scripts.migrate_calibre_account import MigrationError, provision

PASSWORD = "fixture-new-password"
SERVICE = "fixture-service-token-32-characters"
EMAIL = "native-reader@example.test"


def install_mock_calibre(app, transport):
    upstream = httpx.AsyncClient(transport=transport, base_url="http://test-cwa")
    app.state.auth_backend._legacy = CalibreWebBasicAuth(
        CalibreAuthValidator(upstream, "http://test-cwa"), app.state.identity_factory
    )
    return upstream


async def test_existing_data_survives_binding_and_basic_cannot_reclaim_scope(
    client_factory, app, session, cwa_transport, basic_header, monkeypatch
):
    monkeypatch.setenv("QUIRE_IDENTITY_SERVICE_TOKEN", SERVICE)
    async with (
        client_factory(
            auth_backend="transition",
            ai_enabled=True,
            progress_enabled=True,
            admin_users="alice",
            skip_auth_overrides=True,
        ) as client,
        install_mock_calibre(app, cwa_transport),
    ):
        alice = {"Authorization": basic_header("alice", "alicepass")}
        bob = {"Authorization": basic_header("bob", "bobpass")}
        service = {"Authorization": "Bearer " + SERVICE}
        session.add(LibraryItem(user_id="alice", content_hash="fixture", title="Alice's book"))
        await session.commit()

        before = await client.get("/library/v1/items", headers=alice)
        assert before.status_code == 200
        assert "Alice's book" in before.text
        assert (await client.get("/auth/v1/me", headers=alice)).status_code == 401
        assert (await client.get("/quire-admin", headers=alice)).status_code == 200

        factory = app.state.identity_factory
        number, counts = await provision(factory, "Alice", EMAIL, None)
        assert number is None
        assert counts["library_items"] == 1
        assert (await client.get("/library/v1/items", headers=alice)).status_code == 200

        number, counts = await provision(factory, "Alice", EMAIL, PASSWORD)
        account = f"native:{number}"
        assert counts["library_items"] == 1
        with pytest.raises(MigrationError, match="already reserved"):
            await provision(factory, "alice", "second@example.test", PASSWORD)
        with pytest.raises(MigrationError, match="already has a native account"):
            await provision(factory, "bob", EMAIL, PASSWORD)

        login = await client.post("/auth/v1/login", json={"email": EMAIL, "password": PASSWORD})
        assert login.status_code == 200, login.text
        assert login.json()["account_id"] == account
        bearer = {"Authorization": "Bearer " + login.json()["token"]}

        me = await client.get("/auth/v1/me", headers=bearer)
        assert me.json()["user"] == {
            "backend": "native",
            "id": account,
            "legacy_calibre_user_id": "alice",
        }
        assert "Alice's book" in (await client.get("/library/v1/items", headers=bearer)).text
        export = await client.get("/auth/v1/account/export", headers=bearer)
        assert export.json()["account_id"] == account
        assert export.json()["library_items"][0]["title"] == "Alice's book"
        assert (await client.get("/quire-admin", headers=bearer)).status_code == 403

        assert (await client.get("/library/v1/items", headers=alice)).status_code == 401
        assert (await client.get("/library/v1/items", headers=bob)).status_code == 200
        assert "Alice's book" not in (await client.get("/library/v1/items", headers=bob)).text

        # The actual AI authenticator resolves both schemes to their proper scopes.
        from starlette.requests import Request

        async def authenticate(headers):
            return await app.state.ai_authenticator.authenticate(
                Request(
                    {
                        "type": "http",
                        "path": "/ai/v1/preferences",
                        "headers": [(b"authorization", headers["Authorization"].encode())],
                    }
                )
            )

        assert (await authenticate(bearer)).subject == "alice"
        assert (await authenticate(bob)).auth_mode == "basic"

        for action in ("close", "purge", "finalize"):
            assert (
                await client.post(f"/internal/v1/accounts/{account}/{action}", headers=service)
            ).status_code == 200
        assert (await client.get("/library/v1/items", headers=bearer)).status_code == 401
        assert (await client.get("/library/v1/items", headers=alice)).status_code == 401
        assert not await session.scalar(select(LibraryItem).where(LibraryItem.user_id == "alice"))
        binding = await session.get(CalibreAccountBinding, "alice")
        assert binding is not None and binding.native_user_id is None


async def test_binding_waits_for_an_inflight_basic_request(
    client_factory, app, session, cwa_transport, basic_header
):
    entered, release = asyncio.Event(), asyncio.Event()
    async with (
        client_factory(
            auth_backend="transition", ai_enabled=False, skip_auth_overrides=True
        ) as client,
        install_mock_calibre(app, cwa_transport),
    ):

        @app._real.post("/library/v1/fixture-slow-write")
        async def slow_write():
            entered.set()
            await release.wait()
            async with app.state.identity_factory.begin() as db:
                db.add(LibraryItem(user_id="alice", content_hash="fixture", title="In flight"))
            return {"saved": True}

        writing = asyncio.create_task(
            client.post(
                "/library/v1/fixture-slow-write",
                headers={"Authorization": basic_header("alice", "alicepass")},
            )
        )
        await asyncio.wait_for(entered.wait(), 2)
        migrating = asyncio.create_task(
            provision(app.state.identity_factory, "alice", EMAIL, PASSWORD)
        )
        try:
            with pytest.raises(TimeoutError):
                await asyncio.wait_for(asyncio.shield(migrating), 0.25)
        finally:
            release.set()
        assert (await writing).status_code == 200
        _, counts = await migrating
        assert counts["library_items"] == 1
