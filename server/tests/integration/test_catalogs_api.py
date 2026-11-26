"""``/catalogs/v1`` against real Postgres, with real native sessions."""

from __future__ import annotations

import asyncio
import base64
import json
import logging
from uuid import UUID, uuid4

import httpx
import pytest
from sqlalchemy import func, select, text

from quire_server.api import account_lifecycle
from quire_server.api.calibre_migration import provision
from quire_server.core.auth import CalibreAuthValidator
from quire_server.core.auth_backend import CalibreWebBasicAuth
from quire_server.core.catalog_secrets import CatalogSecrets
from quire_server.db.models import AccountCatalog

PASSWORD = "synthetic-password"  # pragma: allowlist secret
KEYS = json.dumps({"k1": base64.b64encode(bytes(range(32))).decode()})
# The parts of a catalog that must never show up outside `/secret` and export.
ACCOUNT_KEY = "kavita-account-key-0123456789"  # pragma: allowlist secret
LOGIN_PASSWORD = "catalog-login-password"  # pragma: allowlist secret
URL = f"https://books.example.org/api/opds/{ACCOUNT_KEY}"
CATALOG = {
    "name": "Kavita",
    "kind": "opds",
    "url": URL,
    "username": "reader",
    "password": LOGIN_PASSWORD,
}


def catalogs(client_factory, **env):
    return client_factory(
        **{"auth_backend": "native", "skip_auth_overrides": True, "catalog_keys": KEYS} | env
    )


async def bearer(app, email="reader@example.test") -> dict[str, str]:
    await app.state.auth_backend.register(email=email, password=PASSWORD)
    token, _ = await app.state.auth_backend.login(email=email, password=PASSWORD)
    return {"Authorization": f"Bearer {token}"}


def path(catalog_id) -> str:
    return f"/catalogs/v1/catalogs/{catalog_id}"


async def listed(client, auth) -> dict:
    response = await client.get("/catalogs/v1/catalogs", headers=auth)
    assert response.status_code == 200, response.text
    return response.json()


async def test_round_trip_keeps_the_address_and_login_out_of_the_list(
    client_factory, app, session, caplog
):
    caplog.set_level(logging.DEBUG)
    async with catalogs(client_factory) as client:
        auth = await bearer(app)
        first, second = uuid4(), uuid4()
        saved = await client.put(path(first), headers=auth, json=CATALOG)
        assert saved.status_code == 200, saved.text
        assert saved.headers["cache-control"] == "no-store"
        assert saved.json() | {"updated_at": None} == {
            "id": str(first),
            "name": "Kavita",
            "kind": "opds",
            "host": "https://books.example.org",
            "has_login": True,
            "needs_login": False,
            "position": 0,
            "updated_at": None,
        }
        gutenberg = {
            "name": " Gutenberg ",
            "kind": "opds",
            "url": " https://gutenberg.org/ebooks.opds/ ",
        }
        added = await client.put(path(second), headers=auth, json=gutenberg)
        assert added.json()["position"] == 1 and added.json()["has_login"] is False
        assert added.json()["name"] == "Gutenberg"

        response = await client.get("/catalogs/v1/catalogs", headers=auth)
        assert response.headers["cache-control"] == "no-store"
        body = response.json()
        assert [c["id"] for c in body["catalogs"]] == [str(first), str(second)]
        assert set(body["catalogs"][0]) == {
            "id",
            "name",
            "kind",
            "host",
            "has_login",
            "needs_login",
            "position",
            "updated_at",
        }
        for secret in (ACCOUNT_KEY, "reader", LOGIN_PASSWORD, "/api/opds", "ebooks.opds"):
            assert secret not in response.text

        secret = await client.get(path(first) + "/secret", headers=auth)
        assert secret.headers["cache-control"] == "no-store"
        assert secret.json() == {"url": URL, "username": "reader", "password": LOGIN_PASSWORD}
        assert (await client.get(path(second) + "/secret", headers=auth)).json() == {
            "url": "https://gutenberg.org/ebooks.opds/",
            "username": None,
            "password": None,
        }

        deleted = await client.delete(path(first), headers=auth)
        assert deleted.status_code == 204 and deleted.headers["cache-control"] == "no-store"
        assert (await client.delete(path(first), headers=auth)).status_code == 204
        assert [c["id"] for c in (await listed(client, auth))["catalogs"]] == [str(second)]
        assert (await client.get(path(first) + "/secret", headers=auth)).status_code == 404

    stored = (await session.scalars(select(AccountCatalog.secret))).all()
    assert stored and not any(ACCOUNT_KEY in s or LOGIN_PASSWORD in s for s in stored)
    for secret in (ACCOUNT_KEY, LOGIN_PASSWORD, "books.example.org", "gutenberg.org"):
        assert secret not in caplog.text


async def test_etag_and_a_new_revision_on_every_kind_of_write(client_factory, app, session):
    async with catalogs(client_factory) as client:
        auth = await bearer(app)
        seen = set()

        async def revision() -> str:
            response = await client.get("/catalogs/v1/catalogs", headers=auth)
            current = response.json()["revision"]
            assert response.headers["etag"] == f'"{current}"'
            unchanged = await client.get(
                "/catalogs/v1/catalogs", headers=auth | {"If-None-Match": f'"{current}"'}
            )
            assert unchanged.status_code == 304 and unchanged.content == b""
            assert unchanged.headers["etag"] == f'"{current}"'
            assert unchanged.headers["cache-control"] == "no-store"
            assert current not in seen
            seen.add(current)
            return current

        empty = await revision()
        stale = await client.get("/catalogs/v1/catalogs", headers=auth | {"If-None-Match": '"x"'})
        assert stale.status_code == 200
        first, second = uuid4(), uuid4()
        await client.put(path(first), headers=auth, json=CATALOG)
        created = await revision()
        # A retried save is a no-op, not a write.
        retried = await client.put(path(first), headers=auth, json=CATALOG)
        assert retried.status_code == 200
        assert (await listed(client, auth))["revision"] == created
        await client.put(path(first), headers=auth, json=CATALOG | {"name": "Renamed"})
        await revision()
        await client.put(path(first), headers=auth, json=CATALOG | {"password": "changed"})
        await revision()
        await client.put(path(second), headers=auth, json=CATALOG)
        await revision()
        order = await client.put(
            "/catalogs/v1/order", headers=auth, json={"ids": [str(second), str(first)]}
        )
        assert order.status_code == 200 and order.headers["cache-control"] == "no-store"
        assert order.json()["revision"] == await revision()
        assert [c["id"] for c in order.json()["catalogs"]] == [str(second), str(first)]
        await client.delete(path(second), headers=auth)
        await revision()
        await client.delete(path(first), headers=auth)
        assert (await listed(client, auth))["revision"] == empty


async def test_omitting_the_password_keeps_the_stored_one(client_factory, app, session):
    async with catalogs(client_factory) as client:
        auth = await bearer(app)
        catalog_id = uuid4()
        await client.put(path(catalog_id), headers=auth, json=CATALOG)
        without = {key: value for key, value in CATALOG.items() if key != "password"}
        for body in (without | {"name": "Renamed"}, without | {"password": None}):
            assert (await client.put(path(catalog_id), headers=auth, json=body)).status_code == 200
        assert (await client.get(path(catalog_id) + "/secret", headers=auth)).json() == {
            "url": URL,
            "username": "reader",
            "password": LOGIN_PASSWORD,
        }
        # Leaving out both removes the login.
        no_login = {"name": "Open", "kind": "opds", "url": URL}
        assert (await client.put(path(catalog_id), headers=auth, json=no_login)).json()[
            "has_login"
        ] is False
        # With no stored password there is nothing to keep.
        refused = await client.put(path(catalog_id), headers=auth, json=without)
        assert refused.status_code == 422 and refused.json() == {"detail": "login_incomplete"}
        # A new catalog has no stored password either.
        refused = await client.put(path(uuid4()), headers=auth, json=without)
        assert refused.json() == {"detail": "login_incomplete"}


async def test_a_kept_password_stays_with_its_scheme_host_and_port(client_factory, app, session):
    async with catalogs(client_factory, catalog_allow_http="true") as client:
        auth = await bearer(app)
        catalog_id = uuid4()
        await client.put(path(catalog_id), headers=auth, json=CATALOG)
        without = {key: value for key, value in CATALOG.items() if key != "password"}
        for url in (
            "https://elsewhere.example.org/api/opds/key",
            "https://books.example.org.elsewhere.example/api/opds/key",
            "https://books.example.org:8443/api/opds/key",
            "http://books.example.org/api/opds/key",
        ):
            refused = await client.put(path(catalog_id), headers=auth, json=without | {"url": url})
            assert (refused.status_code, refused.json()) == (422, {"detail": "login_incomplete"})
        assert (await client.get(path(catalog_id) + "/secret", headers=auth)).json()["url"] == URL
        # A new path on the same server keeps it; so does spelling out the default port.
        for url in ("https://books.example.org/opds/v2", "https://BOOKS.example.org:443/opds"):
            saved = await client.put(path(catalog_id), headers=auth, json=without | {"url": url})
            assert saved.status_code == 200, saved.text
            assert (await client.get(path(catalog_id) + "/secret", headers=auth)).json() == {
                "url": url,
                "username": "reader",
                "password": LOGIN_PASSWORD,
            }


async def test_a_save_without_changes_moves_the_row_to_the_current_key(
    client_factory, app, session
):
    catalog_id = uuid4()
    async with catalogs(client_factory) as client:
        auth = await bearer(app)
        await client.put(path(catalog_id), headers=auth, json=CATALOG)
        before = await listed(client, auth)
    newer = {"k2": base64.b64encode(bytes(range(32, 64))).decode()}
    async with catalogs(
        client_factory, catalog_keys=json.dumps(newer | json.loads(KEYS))
    ) as client:
        assert (await client.put(path(catalog_id), headers=auth, json=CATALOG)).status_code == 200
        # Nothing a device sees moved: same revision, same updated_at.
        assert await listed(client, auth) == before
    assert (await session.scalar(select(AccountCatalog.secret))).startswith("k2:")
    # So the old key can go.
    async with catalogs(client_factory, catalog_keys=json.dumps(newer)) as client:
        secret = await client.get(path(catalog_id) + "/secret", headers=auth)
        assert secret.json()["password"] == LOGIN_PASSWORD


async def test_a_browser_session_needs_its_csrf_token_and_exact_origin(
    client_factory, app, session, monkeypatch
):
    origin = "https://reader.example.test"
    monkeypatch.setenv("QUIRE_IDENTITY_PUBLIC_URL", origin)
    async with catalogs(client_factory, web_native_auth_enabled="true") as client:
        await app.state.auth_backend.register(email="reader@example.test", password=PASSWORD)
        client.base_url = httpx.URL(origin)
        signed_in = await client.post(
            "/auth/v1/browser/login",
            headers={"Origin": origin},
            json={"email": "reader@example.test", "password": PASSWORD},
        )
        assert signed_in.status_code == 200, signed_in.text
        token = (await client.get("/auth/v1/me")).json()["csrf_token"]
        proof = {"Origin": origin, "X-CSRF-Token": token}
        kept, new = uuid4(), uuid4()
        assert (await client.put(path(kept), headers=proof, json=CATALOG)).status_code == 200
        before = await listed(client, {})

        for headers in (
            {"Origin": origin},
            {"Origin": "https://other.example.test", "X-CSRF-Token": token},
            {"X-CSRF-Token": token},
        ):
            for method, target, body in (
                ("PUT", path(new), CATALOG),
                ("PUT", path(kept), CATALOG | {"name": "Renamed"}),
                ("DELETE", path(kept), None),
                ("PUT", "/catalogs/v1/order", {"ids": [str(kept)]}),
            ):
                response = await client.request(method, target, headers=headers, json=body)
                assert (response.status_code, response.json()) == (403, {"detail": "invalid_csrf"})
                assert response.headers["cache-control"] == "no-store"
        assert await listed(client, {}) == before
        assert (await client.delete(path(kept), headers=proof)).status_code == 204


async def test_another_accounts_catalog_looks_missing_on_every_route(client_factory, app, session):
    async with catalogs(client_factory) as client:
        alice = await bearer(app, "alice@example.test")
        bob = await bearer(app, "bob@example.test")
        catalog_id = uuid4()
        await client.put(path(catalog_id), headers=alice, json=CATALOG)
        missing = uuid4()

        for target in (catalog_id, missing):
            response = await client.get(path(target) + "/secret", headers=bob)
            assert (response.status_code, response.json()) == (404, {"detail": "not_found"})
            assert (await client.delete(path(target), headers=bob)).status_code == 204
            order = await client.put("/catalogs/v1/order", headers=bob, json={"ids": [str(target)]})
            assert (order.status_code, order.json()) == (409, {"detail": "order_mismatch"})
        assert (await listed(client, bob))["catalogs"] == []
        # Bob saving the same id makes his own row; Alice's is untouched.
        own = await client.put(path(catalog_id), headers=bob, json=CATALOG | {"password": "bob"})
        assert own.status_code == 200 and own.json()["position"] == 0
        assert (await client.get(path(catalog_id) + "/secret", headers=alice)).json()[
            "password"
        ] == LOGIN_PASSWORD
        assert [c["name"] for c in (await listed(client, alice))["catalogs"]] == ["Kavita"]


async def test_a_calibre_basic_login_is_refused(
    client_factory, app, session, cwa_transport, basic_header
):
    async with catalogs(client_factory, auth_backend="transition") as client:
        async with httpx.AsyncClient(transport=cwa_transport, base_url="http://test-cwa") as up:
            app.state.auth_backend._legacy = CalibreWebBasicAuth(
                CalibreAuthValidator(up, "http://test-cwa"), app.state.identity_factory
            )
            alice = {"Authorization": basic_header("alice", "alicepass")}
            for method, target in (
                ("GET", "/catalogs/v1/catalogs"),
                ("GET", path(uuid4()) + "/secret"),
                ("DELETE", path(uuid4())),
            ):
                response = await client.request(method, target, headers=alice)
                assert response.status_code == 401
                assert response.json() == {"detail": "native_login_required"}
            response = await client.put(path(uuid4()), headers=alice, json=CATALOG)
            assert response.json() == {"detail": "native_login_required"}
        assert (await client.get("/catalogs/v1/catalogs")).status_code == 401


async def test_a_migrated_account_keeps_catalogs_under_its_calibre_scope(
    client_factory, app, session
):
    async with catalogs(client_factory) as client:
        await provision(app.state.identity_factory, "alice", "alice@example.test", PASSWORD * 2)
        token, _ = await app.state.auth_backend.login(
            email="alice@example.test", password=PASSWORD * 2
        )
        auth = {"Authorization": f"Bearer {token}"}
        catalog_id = uuid4()
        assert (await client.put(path(catalog_id), headers=auth, json=CATALOG)).status_code == 200
        assert await session.scalar(select(AccountCatalog.user_id)) == "alice"
        assert (await client.get(path(catalog_id) + "/secret", headers=auth)).json()["url"] == URL


async def test_the_limit_holds_under_concurrent_creates(client_factory, app, session):
    async with catalogs(client_factory) as client:
        auth = await bearer(app)
        results = await asyncio.gather(
            *(client.put(path(uuid4()), headers=auth, json=CATALOG) for _ in range(25))
        )
        codes = sorted(r.status_code for r in results)
        assert codes == [200] * 20 + [409] * 5
        assert {r.json()["detail"] for r in results if r.status_code == 409} == {
            "catalog_limit_reached"
        }
        body = await listed(client, auth)
        assert [c["position"] for c in body["catalogs"]] == list(range(20))
        # Replacing one at the limit is still allowed.
        existing = body["catalogs"][0]["id"]
        renamed = await client.put(path(existing), headers=auth, json=CATALOG | {"name": "New"})
        assert renamed.status_code == 200
    assert await session.scalar(select(func.count()).select_from(AccountCatalog)) == 20


async def test_a_new_entry_for_a_held_address_names_the_existing_one_when_asked(
    client_factory, app, session
):
    async with catalogs(client_factory) as client:
        auth = await bearer(app)
        first = uuid4()
        open_catalog = {"name": "Shelf", "kind": "opds", "url": "https://shelf.example.org/opds"}
        assert (await client.put(path(first), headers=auth, json=open_catalog)).status_code == 200

        again = await client.put(
            path(uuid4()),
            headers=auth,
            json=open_catalog | {"url": " https://shelf.example.org/opds ", "unique_url": True},
        )
        assert again.status_code == 409, again.text
        assert again.json() == {"detail": "catalog_exists", "id": str(first)}
        # Another address on the same host, and the same entry saved again, are not duplicates.
        other = await client.put(
            path(uuid4()),
            headers=auth,
            json=open_catalog | {"url": "https://shelf.example.org/opds/fr", "unique_url": True},
        )
        assert other.status_code == 200, other.text
        same = await client.put(path(first), headers=auth, json=open_catalog | {"unique_url": True})
        assert same.status_code == 200, same.text
        # Without the opt-in, as older apps send it, a second entry is still saved.
        assert (await client.put(path(uuid4()), headers=auth, json=open_catalog)).status_code == 200
        assert len((await listed(client, auth))["catalogs"]) == 3


async def test_reorder_needs_exactly_the_current_set(client_factory, app, session):
    async with catalogs(client_factory) as client:
        auth = await bearer(app)
        ids = [str(uuid4()) for _ in range(3)]
        for catalog_id in ids:
            await client.put(path(catalog_id), headers=auth, json=CATALOG)
        for wrong in (ids[:2], ids + [str(uuid4())], [ids[0], ids[0], ids[1]], []):
            response = await client.put("/catalogs/v1/order", headers=auth, json={"ids": wrong})
            assert (response.status_code, response.json()) == (409, {"detail": "order_mismatch"})
        malformed = await client.put("/catalogs/v1/order", headers=auth, json={"ids": ["x"]})
        assert malformed.status_code == 422
        new_order = [ids[2], ids[0], ids[1]]
        response = await client.put("/catalogs/v1/order", headers=auth, json={"ids": new_order})
        assert [c["id"] for c in response.json()["catalogs"]] == new_order
        await client.delete(path(ids[0]), headers=auth)
        # A catalog added after a delete still goes last.
        added = await client.put(path(uuid4()), headers=auth, json=CATALOG)
        assert [c["id"] for c in (await listed(client, auth))["catalogs"]][-1] == added.json()["id"]


REFUSED = [
    ({"url": "not a url"}, "invalid_url"),
    ({"url": "ftp://books.example.org/opds"}, "invalid_url"),
    ({"url": "https:///opds"}, "invalid_url"),
    ({"url": "https://books.example.org:99999/opds"}, "invalid_url"),
    ({"url": f"http://books.example.org/{ACCOUNT_KEY}"}, "https_required"),
    ({"url": f"https://reader:{LOGIN_PASSWORD}@books.example.org/opds"}, "credentials_in_url"),
    ({"url": f"https://{ACCOUNT_KEY}@books.example.org/opds"}, "credentials_in_url"),
    # Nothing but a real host may reach the plain-text `host` column.
    ({"url": f"https://kavita.example.org\\api\\opds\\{ACCOUNT_KEY}"}, "invalid_url"),
    ({"url": f"https://kavita.example.org;{ACCOUNT_KEY}/opds"}, "invalid_url"),
    ({"url": f"https://kavita.example.org%2F{ACCOUNT_KEY}/opds"}, "invalid_url"),
    ({"url": "https://kavita.example.org%00/opds"}, "invalid_url"),
    ({"url": "https://kavita\u200b.example.org/opds"}, "invalid_url"),
    ({"url": "https://kavita\u200d.example.org/opds"}, "invalid_url"),
    ({"url": f"https://[v1.{ACCOUNT_KEY}]/opds"}, "invalid_url"),
    ({"url": f"https://[fe80::1%25{ACCOUNT_KEY}]/opds"}, "invalid_url"),
    ({"url": "https://[192.168.1.20]/opds"}, "invalid_url"),
    ({"url": "https://my_host/opds"}, "invalid_url"),
    ({"username": None}, "login_incomplete"),
    ({"username": "  "}, "login_incomplete"),
    ({"name": "   "}, "invalid_name"),
]


@pytest.mark.parametrize(("change", "code"), REFUSED)
async def test_each_rule_is_enforced_without_echoing_the_input(
    client_factory, app, session, change, code
):
    async with catalogs(client_factory) as client:
        auth = await bearer(app)
        response = await client.put(path(uuid4()), headers=auth, json=CATALOG | change)
    assert (response.status_code, response.json()) == (422, {"detail": code})
    assert response.headers["cache-control"] == "no-store"
    assert ACCOUNT_KEY not in response.text and LOGIN_PASSWORD not in response.text


@pytest.mark.parametrize(
    "change",
    [
        {"url": URL + "/" + "x" * 2048},
        {"password": LOGIN_PASSWORD * 60},
        {"username": ACCOUNT_KEY * 10},
        {"name": ACCOUNT_KEY * 3},
        {"kind": ACCOUNT_KEY},
        {"url": {"value": URL}},
        {"extra": ACCOUNT_KEY},
    ],
)
async def test_schema_errors_do_not_echo_the_input(client_factory, app, session, change):
    async with catalogs(client_factory) as client:
        auth = await bearer(app)
        response = await client.put(path(uuid4()), headers=auth, json=CATALOG | change)
        assert response.status_code == 422
        assert response.headers["cache-control"] == "no-store"
        assert ACCOUNT_KEY not in response.text and LOGIN_PASSWORD not in response.text
        broken = await client.put(
            path(uuid4()),
            headers=auth | {"Content-Type": "application/json"},
            content=b"{" + URL.encode(),
        )
        assert broken.status_code == 422 and ACCOUNT_KEY not in broken.text
        assert (await client.put(path("not-a-uuid"), headers=auth, json=CATALOG)).status_code == 422


async def test_host_is_scheme_host_and_a_non_default_port(client_factory, app, session):
    async with catalogs(client_factory) as client:
        auth = await bearer(app)
        for url, host in (
            (
                f"https://Books.Example.org:8443/api/{ACCOUNT_KEY}?q=1#top",
                "https://books.example.org:8443",
            ),
            ("https://books.example.org:443/opds", "https://books.example.org"),
            ("https://[2001:db8::1]:8443/opds", "https://[2001:db8::1]:8443"),
            ("https://[2001:0DB8:0:0:0:0:0:1]/opds", "https://[2001:db8::1]"),
            ("https://192.168.1.20/opds", "https://192.168.1.20"),
            ("https://Bücher.example/opds", "https://xn--bcher-kva.example"),
        ):
            body = CATALOG | {"url": url}
            assert (await client.put(path(uuid4()), headers=auth, json=body)).json()["host"] == host


async def test_http_only_with_the_setting(client_factory, app, session):
    async with catalogs(client_factory, catalog_allow_http="true") as client:
        auth = await bearer(app)
        body = CATALOG | {"url": "http://192.168.1.20:8083/opds"}
        saved = await client.put(path(uuid4()), headers=auth, json=body)
        assert saved.status_code == 200 and saved.json()["host"] == "http://192.168.1.20:8083"
        body = CATALOG | {"url": "http://192.168.1.20:80/opds"}
        assert (await client.put(path(uuid4()), headers=auth, json=body)).json()["host"] == (
            "http://192.168.1.20"
        )


async def test_an_unreadable_row_is_listed_as_needing_its_login(client_factory, app, session):
    async with catalogs(client_factory) as client:
        auth = await bearer(app)
        readable, lost = uuid4(), uuid4()
        await client.put(path(readable), headers=auth, json=CATALOG)
        await client.put(path(lost), headers=auth, json=CATALOG)
        # As after a restore without the key file: sealed with a key this server lacks.
        user_id = await session.scalar(select(AccountCatalog.user_id).limit(1))
        foreign = CatalogSecrets({"gone": bytes(32)}).encrypt(user_id, lost, {"url": URL})
        await session.execute(
            text("UPDATE account_catalogs SET secret=:secret WHERE id=:id"),
            {"secret": foreign, "id": lost},
        )
        await session.commit()

        flags = {
            c["id"]: (c["has_login"], c["needs_login"])
            for c in (await listed(client, auth))["catalogs"]
        }
        assert flags == {str(readable): (True, False), str(lost): (False, True)}
        response = await client.get(path(lost) + "/secret", headers=auth)
        assert (response.status_code, response.json()) == (409, {"detail": "needs_login"})
        without = {key: value for key, value in CATALOG.items() if key != "password"}
        response = await client.put(path(lost), headers=auth, json=without)
        assert (response.status_code, response.json()) == (409, {"detail": "needs_login"})
        # Entering the login again repairs it.
        assert (await client.put(path(lost), headers=auth, json=CATALOG)).json()["needs_login"] is (
            False
        )
        assert (await client.get(path(lost) + "/secret", headers=auth)).status_code == 200


async def test_feature_off_is_a_404_everywhere(client_factory, app, session):
    async with client_factory(
        auth_backend="native", skip_auth_overrides=True, catalog_keys="", catalog_key_file=""
    ) as client:
        assert app.state.catalog_secrets is None
        auth = await bearer(app)
        catalog_id = uuid4()
        for method, target, body in (
            ("GET", "/catalogs/v1/catalogs", None),
            ("GET", path(catalog_id) + "/secret", None),
            ("PUT", path(catalog_id), CATALOG),
            ("DELETE", path(catalog_id), None),
            ("PUT", "/catalogs/v1/order", {"ids": []}),
        ):
            for headers in (auth, {}):
                response = await client.request(method, target, headers=headers, json=body)
                assert response.status_code == 404, (method, target)
                assert response.json() == {"detail": "Not Found"}


async def test_bootstrap_reports_catalog_sync_for_a_quire_session_here(
    client_factory, app, session, monkeypatch
):
    origin = "https://reader.example.test"
    monkeypatch.setenv("QUIRE_IDENTITY_PUBLIC_URL", origin)
    for keys, expected in ((KEYS, True), ("", False)):
        async with client_factory(
            auth_backend="native",
            skip_auth_overrides=True,
            web_native_auth_enabled="true",
            catalog_keys=keys,
            catalog_key_file="",
        ) as client:
            client.base_url = httpx.URL(origin)
            assert (await client.get("/quire/v1/bootstrap")).json()["catalogSync"] is expected
            other = await client.get("/quire/v1/bootstrap", headers={"Host": "other.example.test"})
            assert other.json()["catalogSync"] is False


async def test_the_web_account_binding_covers_catalogs(client_factory, app, session):
    async with catalogs(client_factory) as client:
        alice = await bearer(app, "alice@example.test")
        me = (await client.get("/auth/v1/me", headers=alice)).json()["user"]["id"]
        for account, expected in (("native:0", 409), (me, 200)):
            response = await client.get(
                "/catalogs/v1/catalogs", headers=alice | {"X-Quire-Account": account.encode().hex()}
            )
            assert response.status_code == expected
            assert response.headers["cache-control"] == "no-store"


async def test_export_has_readable_logins_and_purge_removes_the_rows(
    client_factory, app, session, monkeypatch
):
    service = "synthetic-account-service-token-00000000"
    monkeypatch.setenv("QUIRE_IDENTITY_SERVICE_TOKEN", service)
    async with catalogs(client_factory) as client:
        alice = await bearer(app, "alice@example.test")
        bob = await bearer(app, "bob@example.test")
        readable, lost, bobs = uuid4(), uuid4(), uuid4()
        await client.put(path(readable), headers=alice, json=CATALOG)
        await client.put(path(lost), headers=alice, json=CATALOG)
        await client.put(path(bobs), headers=bob, json=CATALOG)
        await session.execute(
            text("UPDATE account_catalogs SET secret='gone:x' WHERE id=:id"), {"id": lost}
        )
        await session.commit()

        exported = (await client.get("/auth/v1/account/export", headers=alice)).json()
        rows = {UUID(row["id"]): row for row in exported["account_catalogs"]}
        assert set(rows) == {readable, lost}
        assert "secret" not in rows[readable] and "secret" not in rows[lost]
        assert rows[readable]["url"] == URL
        assert rows[readable]["username"] == "reader"
        assert rows[readable]["password"] == LOGIN_PASSWORD
        assert rows[lost]["secret_unreadable"] is True and "url" not in rows[lost]

        account = exported["account_id"]
        lifecycle = {"Authorization": "Bearer " + service}
        for step in ("close", "purge"):
            response = await client.post(
                f"/internal/v1/accounts/{account}/{step}", headers=lifecycle
            )
            assert response.status_code == 200
    owners = (await session.scalars(select(AccountCatalog.id))).all()
    assert owners == [bobs]


async def test_export_works_before_the_catalogs_table_exists(
    client_factory, app, session, monkeypatch
):
    real = account_lifecycle.exists

    async def without_catalogs(session, table):
        return table != "account_catalogs" and await real(session, table)

    monkeypatch.setattr(account_lifecycle, "exists", without_catalogs)
    async with catalogs(client_factory) as client:
        response = await client.get("/auth/v1/account/export", headers=await bearer(app))
    assert response.status_code == 200, response.text
    assert response.json()["account_catalogs"] == []


async def test_the_first_start_creates_the_key_and_turns_catalogs_on(
    client_factory, app, session, tmp_path
):
    key_file = tmp_path / "catalog.key"
    async with catalogs(client_factory, catalog_keys="", catalog_key_file=key_file) as client:
        assert app.state.catalog_secrets is None
        async with app.router.lifespan_context(app):
            assert app.state.catalog_key_source == "file" and key_file.exists()
            saved = await client.put(path(uuid4()), headers=await bearer(app), json=CATALOG)
            assert saved.status_code == 200


async def test_a_start_that_cannot_reach_the_database_leaves_catalogs_off(
    client_factory, app, tmp_path, caplog
):
    unreachable = "postgresql+asyncpg://quire:unused@127.0.0.1:9/quire"
    async with catalogs(
        client_factory,
        database_url=unreachable,
        catalog_keys="",
        catalog_key_file=tmp_path / "catalog.key",
    ):
        with caplog.at_level(logging.ERROR):
            async with app.router.lifespan_context(app):
                assert app.state.catalog_secrets is None
                assert app.state.catalog_key_source == "none"
    assert list(tmp_path.iterdir()) == []
    assert "Catalog sync is off: creating the catalog key failed" in caplog.text
    assert "unused" not in caplog.text


async def test_admin_status_counts_catalogs_and_names_none(client_factory, app, session):
    async with catalogs(client_factory, admin_users="native:placeholder") as client:
        admin = await bearer(app, "admin@example.test")
        me = (await client.get("/auth/v1/me", headers=admin)).json()["user"]["id"]
        app.state.admin_users = frozenset({me})
        await client.put(path(uuid4()), headers=admin, json=CATALOG)
        await client.put(path(uuid4()), headers=admin, json=CATALOG)
        status = await client.get("/quire-admin/v1/status", headers=admin)
        assert status.json()["catalogs"] == {"key_source": "environment", "saved": 2}
        page = await client.get("/quire-admin", headers=admin)
        assert "Catalogs saved, all accounts together: 2" in page.text
        for response in (status, page):
            for secret in (ACCOUNT_KEY, LOGIN_PASSWORD, "books.example.org", KEYS[10:30]):
                assert secret not in response.text
