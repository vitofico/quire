import asyncio
from uuid import uuid4

import httpx
import pytest
from sqlalchemy import select, text

from quire_server.api.calibre_migration import MigrationError, provision
from quire_server.core.auth import CalibreAuthValidator
from quire_server.core.auth_backend import CalibreWebBasicAuth
from quire_server.db.models import CalibreAccountBinding, LibraryItem

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
    origin = "https://quire.example.test"
    monkeypatch.setenv("QUIRE_IDENTITY_PUBLIC_URL", origin)
    monkeypatch.setenv("QUIRE_IDENTITY_SERVICE_TOKEN", SERVICE)
    async with (
        client_factory(
            auth_backend="transition",
            ai_enabled=True,
            progress_enabled=True,
            admin_users="alice",
            web_native_auth_enabled=True,
            skip_auth_overrides=True,
        ) as client,
        install_mock_calibre(app, cwa_transport),
    ):
        alice = {"Authorization": basic_header("alice", "alicepass")}
        bob = {"Authorization": basic_header("bob", "bobpass")}
        service = {"Authorization": "Bearer " + SERVICE}
        session.add(LibraryItem(user_id="alice", content_hash="fixture", title="Alice's book"))
        operation_id = uuid4()
        await session.execute(
            text(
                "INSERT INTO library_sync_receipts "
                "(user_id, operation_id, payload_hash, response) "
                "VALUES (:id, :operation, 'fixture', '{}'), ('bob', :operation, 'fixture', '{}')"
            ),
            {"id": "alice", "operation": operation_id},
        )
        await session.commit()

        verified = await client.get("/quire/v1/session", headers=alice)
        assert verified.json()["user"] == {"id": "alice", "backend": "calibreweb"}
        before = await client.get("/library/v1/items", headers=alice)
        assert before.status_code == 200
        assert "Alice's book" in before.text
        assert (await client.get("/auth/v1/me", headers=alice)).status_code == 401
        assert (await client.get("/quire-admin", headers=alice)).status_code == 200

        factory = app.state.identity_factory
        number, counts = await provision(factory, "Alice", EMAIL, None)
        assert number is None
        assert counts["library_items"] == 1
        assert counts["library_sync_receipts"] == 1
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
        assert "server_url" not in me.json()
        app.state.canonical_url = "https://quire.example.test"
        moved = await client.get("/auth/v1/me", headers=bearer)
        assert moved.json()["server_url"] == "https://quire.example.test"
        assert "Alice's book" in (await client.get("/library/v1/items", headers=bearer)).text
        export = await client.get("/auth/v1/account/export", headers=bearer)
        assert export.json()["account_id"] == account
        assert export.json()["library_items"][0]["title"] == "Alice's book"
        assert [record["user_id"] for record in export.json()["library_sync_receipts"]] == ["alice"]
        assert (await client.get("/quire-admin", headers=bearer)).status_code == 403

        assert (await client.get("/library/v1/items", headers=alice)).status_code == 401
        assert (await client.get("/quire/v1/session", headers=alice)).json() == {
            "detail": "native_login_required"
        }
        assert (await client.get("/quire/v1/session", headers=bob)).status_code == 200
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

        client.base_url = httpx.URL(origin)
        browser_login = await client.post(
            "/auth/v1/browser/login",
            headers={"Origin": origin},
            json={"email": EMAIL, "password": PASSWORD, "destination": "quire"},
        )
        assert browser_login.json()["account_id"] == account
        browser_session = (await client.get("/auth/v1/me")).json()
        assert browser_session["user"] == me.json()["user"]
        assert (await client.get("/quire/v1/session")).status_code == 401
        assert (
            await client.get("/auth/v1/me", headers={"Host": "books.example.test"})
        ).status_code == 401
        assert (
            await client.get("/library/v1/items", headers={"X-Quire-Account": b"alice".hex()})
        ).status_code == 409
        web_headers = {"X-Quire-Account": account.encode().hex()}
        library = await client.get("/library/v1/items", headers=web_headers)
        assert "Alice's book" in library.text
        assert library.headers["cache-control"] == "no-store"
        assert (await client.post("/auth/v1/logout", headers=web_headers)).status_code == 403
        web_headers.update({"Origin": origin, "X-CSRF-Token": browser_session["csrf_token"]})
        assert (await client.post("/auth/v1/logout", headers=web_headers)).status_code == 204
        assert (await client.get("/auth/v1/me")).status_code == 401
        assert (await client.get("/auth/v1/me", headers=bearer)).status_code == 200

        for action in ("close", "purge", "finalize"):
            assert (
                await client.post(f"/internal/v1/accounts/{account}/{action}", headers=service)
            ).status_code == 200
        assert (await client.get("/library/v1/items", headers=bearer)).status_code == 401
        assert (await client.get("/library/v1/items", headers=alice)).status_code == 401
        assert not await session.scalar(select(LibraryItem).where(LibraryItem.user_id == "alice"))
        assert (
            await session.scalars(
                text("SELECT user_id FROM library_sync_receipts WHERE operation_id=:operation"),
                {"operation": operation_id},
            )
        ).all() == ["bob"]
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


async def test_reader_migrates_from_the_app_and_other_devices_claim(
    client_factory, app, session, cwa_transport, basic_header
):
    async with (
        client_factory(
            auth_backend="transition",
            ai_enabled=False,
            self_migration_enabled=True,
            skip_auth_overrides=True,
        ) as client,
        install_mock_calibre(app, cwa_transport),
    ):
        alice = {"Authorization": basic_header("alice", "alicepass")}
        bob = {"Authorization": basic_header("bob", "bobpass")}
        session.add(LibraryItem(user_id="alice", content_hash="fixture", title="Alice's book"))
        await session.commit()
        account = {"email": EMAIL, "password": PASSWORD}

        assert (await client.post("/auth/v1/migrate/claim", json={}, headers=alice)).json() == {
            "detail": "not_migrated"
        }
        wrong = {"Authorization": basic_header("alice", "not-her-password")}
        assert (
            await client.post("/auth/v1/migrate", json=account, headers=wrong)
        ).status_code == 401
        assert (await client.post("/auth/v1/migrate", json=account)).status_code == 401
        short = await client.post(
            "/auth/v1/migrate", json={"email": EMAIL, "password": "short"}, headers=alice
        )
        assert (short.status_code, short.json()["detail"]) == (409, "invalid_password")
        assert (await client.get("/library/v1/items", headers=alice)).status_code == 200

        migrated = await client.post(
            "/auth/v1/migrate", json={**account, "device_name": "Pixel"}, headers=alice
        )
        assert migrated.status_code == 200, migrated.text
        assert migrated.json()["account_subject"] == EMAIL
        first = {"Authorization": "Bearer " + migrated.json()["token"]}
        assert "Alice's book" in (await client.get("/library/v1/items", headers=first)).text
        assert (await client.get("/library/v1/items", headers=alice)).status_code == 401
        assert (await client.post("/auth/v1/login", json=account)).status_code == 200

        again = await client.post("/auth/v1/migrate", json=account, headers=alice)
        assert (again.status_code, again.json()["detail"]) == (409, "already_migrated")
        taken = await client.post("/auth/v1/migrate", json=account, headers=bob)
        assert (taken.status_code, taken.json()["detail"]) == (409, "email_unavailable")
        limited = [
            await client.post("/auth/v1/migrate/claim", json={}, headers=alice) for _ in "12"
        ]
        assert limited[-1].json() == {"detail": "login_rate_limited"}
        app.state.native_login_guard.ips.clear()

        claimed = await client.post(
            "/auth/v1/migrate/claim", json={"device_name": "Tablet"}, headers=alice
        )
        assert claimed.status_code == 200, claimed.text
        assert claimed.json()["account_id"] == migrated.json()["account_id"]
        assert claimed.json()["token"] != migrated.json()["token"]
        second = {"Authorization": "Bearer " + claimed.json()["token"]}
        assert "Alice's book" in (await client.get("/library/v1/items", headers=second)).text
        assert (
            await client.post("/auth/v1/migrate/claim", json={}, headers=wrong)
        ).status_code == 401

        await session.execute(
            text(
                "UPDATE calibre_account_bindings "
                "SET self_migrated_at = now() - interval '31 days' WHERE calibre_user_id='alice'"
            )
        )
        await session.commit()
        late = await client.post("/auth/v1/migrate/claim", json={}, headers=alice)
        assert (late.status_code, late.json()["detail"]) == (403, "native_login_required")
        assert (await client.get("/library/v1/items", headers=second)).status_code == 200

        # An operator migration tells the reader Basic stops at once, so it never opens a window.
        await session.execute(text("UPDATE calibre_account_bindings SET self_migrated_at = NULL"))
        await session.commit()
        assert (
            await client.post("/auth/v1/migrate/claim", json={}, headers=alice)
        ).status_code == 403


async def test_browser_migration_issues_no_bearer(
    client_factory, app, session, cwa_transport, basic_header
):
    async with (
        client_factory(
            auth_backend="transition",
            ai_enabled=False,
            self_migration_enabled=True,
            skip_auth_overrides=True,
        ) as client,
        install_mock_calibre(app, cwa_transport),
    ):
        # A browser sends no device name: it signs in afterwards for its own cookie.
        migrated = await client.post(
            "/auth/v1/migrate",
            json={"email": EMAIL, "password": PASSWORD},
            headers={"Authorization": basic_header("alice", "alicepass")},
        )
        assert (migrated.status_code, set(migrated.json())) == (200, {"account_id"})


async def test_self_migration_is_absent_unless_the_operator_enables_it(
    client_factory, app, cwa_transport, basic_header
):
    async with (
        client_factory(
            auth_backend="transition", ai_enabled=False, skip_auth_overrides=True
        ) as client,
        install_mock_calibre(app, cwa_transport),
    ):
        alice = {"Authorization": basic_header("alice", "alicepass")}
        for path in ("/auth/v1/migrate", "/auth/v1/migrate/claim"):
            assert (await client.post(path, json={}, headers=alice)).status_code == 404
