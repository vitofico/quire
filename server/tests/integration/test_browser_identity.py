import asyncio
import json
import time
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from sqlalchemy import select, text

from quire_server.db.models import Highlight, HighlightSyncState, LibraryItem, NativeUser

ORIGIN = "https://reader.example.test"
SERVICE = "synthetic-account-service-token-00000000"


def configure(monkeypatch, **values):
    monkeypatch.setenv("QUIRE_IDENTITY_PUBLIC_URL", ORIGIN)
    monkeypatch.setenv("QUIRE_IDENTITY_SERVICE_TOKEN", SERVICE)
    for key, value in values.items():
        monkeypatch.setenv("QUIRE_IDENTITY_" + key.upper(), value)


async def signin(client, app, email="synthetic-browser@example.test"):
    await app.state.auth_backend.register(email=email, password="synthetic-password")
    client.base_url = httpx.URL(ORIGIN)
    response = await client.post(
        "/auth/v1/browser/login",
        headers={"Origin": ORIGIN},
        json={"email": email, "password": "synthetic-password"},
    )
    assert response.status_code == 200, response.text
    assert "Secure" in response.headers["set-cookie"]
    me = (await client.get("/auth/v1/me")).json()
    return me, {"Origin": ORIGIN, "X-CSRF-Token": me["csrf_token"]}


async def test_browser_pairing_single_use_revocation_and_csrf(
    client_factory, app, session, monkeypatch
):
    configure(monkeypatch)
    async with client_factory(
        auth_backend="native", ai_enabled=False, progress_enabled=True, skip_auth_overrides=True
    ) as client:
        me, proof = await signin(client, app)
        assert (await client.post("/auth/v1/pairing")).status_code == 403
        ticket = (await client.post("/auth/v1/pairing", headers=proof)).json()["ticket"]
        paired = await client.post(
            "/auth/v1/pairing/exchange", json={"ticket": ticket, "device_name": "Synthetic phone"}
        )
        assert paired.status_code == 200, paired.text
        device = {"Authorization": "Bearer " + paired.json()["token"]}
        assert (await client.get("/auth/v1/me", headers=device)).json()["user"] == me["user"]
        assert (
            await client.post("/auth/v1/pairing/exchange", json={"ticket": ticket})
        ).status_code == 401
        old_ticket = (await client.post("/auth/v1/pairing", headers=proof)).json()["ticket"]
        assert (await client.post("/auth/v1/logout", headers=proof)).status_code == 204
        assert (
            await client.post("/auth/v1/pairing/exchange", json={"ticket": old_ticket})
        ).status_code == 401
        # Logging out the browser does not revoke a separately paired phone.
        assert (await client.get("/auth/v1/me", headers=device)).status_code == 200


@pytest.mark.parametrize("body", [b"[]", b"null", b'"ticket"', b"1", b"{", b"\xff"])
async def test_pairing_rejects_malformed_json_object(client_factory, body):
    async with client_factory(
        auth_backend="native", ai_enabled=False, skip_auth_overrides=True
    ) as client:
        result = await client.post(
            "/auth/v1/pairing/exchange",
            content=body,
            headers={"Content-Type": "application/json"},
        )
        assert result.status_code == 400
        assert result.json() == {"detail": "invalid_pairing"}


async def test_oidc_callback_issuer_subject_mapping_and_replay(
    client_factory, app, session, monkeypatch
):
    issuer = "https://identity.example.test/realms/quire"
    configure(monkeypatch, oidc_issuer=issuer, oidc_client_id="quire")
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public = jwt.algorithms.RSAAlgorithm.to_jwk(private.public_key(), as_dict=True)
    public.update(kid="synthetic", use="sig", alg="RS256")
    nonce = [None]
    auth_time, subject = [int(time.time())], ["synthetic-exact-subject"]

    def provider(request):
        if request.url.path.endswith("openid-configuration"):
            return httpx.Response(
                200,
                json={
                    "issuer": issuer,
                    "authorization_endpoint": "https://identity.example.test/authorize",
                    "token_endpoint": "https://identity.example.test/token",
                    "jwks_uri": "https://identity.example.test/jwks",
                    "code_challenge_methods_supported": ["S256"],
                },
            )
        if request.url.path == "/jwks":
            return httpx.Response(200, json={"keys": [public]})
        if request.url.path == "/token":
            assert parse_qs(request.content.decode())["code_verifier"]
            token = jwt.encode(
                {
                    "iss": issuer,
                    "aud": "quire",
                    "sub": subject[0],
                    "auth_time": auth_time[0],
                    "nonce": nonce[0],
                    "iat": int(time.time()),
                    "exp": int(time.time()) + 300,
                    "email": "same-email@example.test",
                    "email_verified": True,
                },
                private,
                algorithm="RS256",
                headers={"kid": "synthetic"},
            )
            return httpx.Response(200, json={"id_token": token})
        raise AssertionError("unexpected provider endpoint")

    async with client_factory(
        auth_backend="native", ai_enabled=False, skip_auth_overrides=True
    ) as client:
        client.base_url = httpx.URL(ORIGIN)
        local_id = await app.state.auth_backend.register(
            email="same-email@example.test", password="synthetic-password"
        )
        async with httpx.AsyncClient(transport=httpx.MockTransport(provider)) as provider_client:
            app.state.oidc_client._http = provider_client
            for _ in range(2):
                start = await client.get("/auth/v1/oidc/start")
                assert start.status_code == 303
                query = parse_qs(urlsplit(start.headers["location"]).query)
                nonce[0] = query["nonce"][0]
                callback = "/auth/v1/oidc/callback?code=synthetic&state=" + query["state"][0]
                assert (await client.get(callback)).status_code == 303
                me = (await client.get("/auth/v1/me")).json()
                assert me["user"]["id"] != f"native:{local_id}"
                assert (await client.get(callback)).status_code == 401
            assert (await session.execute(select(NativeUser))).scalars().all().__len__() == 2
            # Fresh deletion reauthentication cannot use stale auth_time or a
            # different IdP subject, even if the browser already has a session.
            for scenario in ("stale", "different", "revoked", "expired", "wrong_cookie", "fresh"):
                auth_time[0] = int(time.time()) - (3600 if scenario == "stale" else 0)
                subject[0] = (
                    "unrelated-subject" if scenario == "different" else "synthetic-exact-subject"
                )
                start = await client.get(
                    "/auth/v1/oidc/start?reauth=account-delete", follow_redirects=False
                )
                query = parse_qs(urlsplit(start.headers["location"]).query)
                assert query["prompt"] == ["login"] and query["max_age"] == ["0"]
                nonce[0] = query["nonce"][0]
                from quire_server.core.native_auth import hash_session_token

                digest = hash_session_token(client.cookies.get("__Host-quire-session"))
                if scenario in {"revoked", "expired"}:
                    field = "revoked_at" if scenario == "revoked" else "expires_at"
                    await session.execute(
                        text(
                            "UPDATE native_sessions SET "
                            + field
                            + "=now()-interval '1 second' WHERE token_hash=:hash"
                        ),
                        {"hash": digest},
                    )
                    await session.commit()
                binding = client.cookies.get("__Host-quire-session-oidc")
                result = await client.get(
                    "/auth/v1/oidc/callback",
                    params={"code": "synthetic", "state": query["state"][0]},
                    # Simulate the cross-site callback withholding the ordinary
                    # application cookie while retaining the Lax OIDC binding.
                    headers={
                        "Cookie": "__Host-quire-session-oidc="
                        + binding
                        + (
                            "; __Host-quire-session=" + "wrong-cookie-token-000000000"
                            if scenario == "wrong_cookie"
                            else ""
                        )
                    },
                    follow_redirects=False,
                )
                assert result.status_code == (303 if scenario == "fresh" else 401), result.text
                if scenario in {"revoked", "expired"}:
                    await session.execute(
                        text(
                            "UPDATE native_sessions SET revoked_at=NULL, "
                            "expires_at=now()+interval '1 hour' WHERE token_hash=:hash"
                        ),
                        {"hash": digest},
                    )
                    await session.commit()
            proof = client.cookies.get("__Host-quire-session-delete")
            assert (await client.get("/auth/v1/me")).json()["capabilities"][
                "deletion_authorized"
            ] is True
            me = (await client.get("/auth/v1/me")).json()
            headers = {"Origin": ORIGIN, "X-CSRF-Token": me["csrf_token"]}
            consume = "/auth/v1/account/delete-authorization/consume"
            assert (
                await client.post(consume, headers=headers, json={"proof": proof})
            ).status_code == 200
            assert (
                await client.post(consume, headers=headers, json={"proof": proof})
            ).status_code == 401


async def test_export_close_purge_isolation_and_shared_cache(
    client_factory, app, configure_ai, session, monkeypatch
):
    configure(monkeypatch)
    async with client_factory(
        auth_backend="native",
        ai_enabled=True,
        ai_base_url="http://fake/v1",
        ai_model="synthetic",
        progress_enabled=True,
        skip_auth_overrides=True,
    ) as client:
        me, _ = await signin(client, app)
        account = me["user"]["id"]
        bob = await app.state.auth_backend.register(
            email="synthetic-bob@example.test", password="synthetic-password"
        )
        session.add_all(
            [
                LibraryItem(user_id=account, content_hash="alice-private", title="Alice private"),
                LibraryItem(
                    user_id=f"native:{bob}", content_hash="bob-private", title="Bob private"
                ),
            ]
        )
        await session.commit()
        configure_ai(app, {"schema_version": 2, "intro": "Shared cache", "confidence": "low"})
        token = (
            await client.post(
                "/auth/v1/login",
                json={"email": "synthetic-browser@example.test", "password": "synthetic-password"},
            )
        ).json()["token"]
        auth = {"Authorization": "Bearer " + token}
        await client.put("/ai/v1/preferences", headers=auth, json={"ai_enabled": True})
        payload = {"identity": {"content_hash": "shared"}, "bundle": {"title": "Shared book"}}
        assert (
            await client.post("/ai/v1/insights/lookup", headers=auth, json=payload)
        ).status_code == 200
        highlights = json.loads(
            (Path(__file__).parents[1] / "fixtures/highlight-sync.json").read_text()
        )["push"]
        assert (
            await client.post("/sync/v1/highlights", headers=auth, json=highlights)
        ).status_code == 200
        bob_token = (
            await client.post(
                "/auth/v1/login",
                json={"email": "synthetic-bob@example.test", "password": "synthetic-password"},
            )
        ).json()["token"]
        assert (
            await client.post(
                "/sync/v1/highlights",
                headers={"Authorization": "Bearer " + bob_token},
                json=highlights,
            )
        ).status_code == 200
        exported_response = await client.get("/auth/v1/account/export", headers=auth)
        assert exported_response.headers["Cache-Control"] == "no-store"
        exported = exported_response.json()
        assert [r["title"] for r in exported["library_items"]] == ["Alice private"]
        assert len(exported["highlights"]) == 1
        assert exported["highlights"][0]["user_id"] == account
        assert exported["highlights"][0]["deleted_at"] is not None
        assert exported["highlight_sync_state"] == [{"user_id": account, "revision": 2}]
        assert "password_hash" not in str(exported) and "token_hash" not in str(exported)
        path = "/internal/v1/accounts/" + account
        assert (await client.post(path + "/close")).status_code == 403
        service = {"Authorization": "Bearer " + SERVICE}
        assert (await client.post(path + "/purge", headers=service)).status_code == 409
        assert (await client.post(path + "/close", headers=service)).status_code == 200
        assert (await client.get("/auth/v1/me", headers=auth)).status_code == 401
        assert (await client.post(path + "/purge", headers=service)).status_code == 200
        assert (await client.post(path + "/purge", headers=service)).status_code == 200
        assert (
            await session.execute(select(LibraryItem).where(LibraryItem.user_id == f"native:{bob}"))
        ).scalar_one().title == "Bob private"
        assert await session.get(HighlightSyncState, account) is None
        assert (
            await session.scalars(select(Highlight).where(Highlight.user_id == account))
        ).all() == []
        assert (
            len(
                (
                    await session.scalars(
                        select(Highlight).where(Highlight.user_id == f"native:{bob}")
                    )
                ).all()
            )
            == 1
        )

        assert (
            await client.post(
                "/ai/v1/insights/get",
                headers={"Authorization": "Bearer " + bob_token},
                json={"identity": payload["identity"]},
            )
        ).status_code == 200
        assert (await client.post(path + "/finalize", headers=service)).status_code == 200


async def test_closure_waits_for_inflight_private_write(client_factory, app, session, monkeypatch):
    configure(monkeypatch)
    entered, release = asyncio.Event(), asyncio.Event()
    async with client_factory(
        auth_backend="native", ai_enabled=False, skip_auth_overrides=True
    ) as client:
        me, proof = await signin(client, app)
        account = me["user"]["id"]

        @app._real.post("/library/v1/synthetic-slow-write")
        async def slow_write():
            entered.set()
            await release.wait()
            async with app.state.identity_factory() as write:
                write.add(LibraryItem(user_id=account, content_hash="inflight", title="Synthetic"))
                await write.commit()
            return {"saved": True}

        writing = asyncio.create_task(
            client.post("/library/v1/synthetic-slow-write", headers=proof)
        )
        await asyncio.wait_for(entered.wait(), 2)
        closing = asyncio.create_task(
            client.post(
                "/internal/v1/accounts/" + account + "/close",
                headers={"Authorization": "Bearer " + SERVICE},
            )
        )
        with pytest.raises(TimeoutError):
            await asyncio.wait_for(asyncio.shield(closing), 0.05)
        release.set()
        assert (await writing).status_code == 200
        assert (await closing).status_code == 200
        assert (
            await client.post(
                "/internal/v1/accounts/" + account + "/purge",
                headers={"Authorization": "Bearer " + SERVICE},
            )
        ).status_code == 200
        assert (
            not (await session.execute(select(LibraryItem).where(LibraryItem.user_id == account)))
            .scalars()
            .all()
        )


async def test_deletion_proof_requires_password_and_same_live_session_once(
    client_factory, app, session, monkeypatch
):
    from sqlalchemy import text

    configure(monkeypatch)
    async with client_factory(
        auth_backend="native", ai_enabled=False, skip_auth_overrides=True
    ) as client:
        me, csrf = await signin(client, app)
        endpoint = "/auth/v1/account/delete-authorization"
        assert (
            await client.post(endpoint, json={"password": "synthetic-password"})
        ).status_code == 403
        assert (
            await client.post(endpoint, headers=csrf, json={"password": "wrong"})
        ).status_code == 401
        result = await client.post(endpoint, headers=csrf, json={"password": "synthetic-password"})
        proof = result.json()["proof"]
        device, _ = await app.state.auth_backend.login(
            email="synthetic-browser@example.test", password="synthetic-password"
        )
        consumed = endpoint + "/consume"
        assert (
            await client.post(
                consumed, headers={"Authorization": "Bearer " + device}, json={"proof": proof}
            )
        ).status_code == 401
        assert (await client.post(consumed, headers=csrf, json={"proof": proof})).json() == {
            "account_id": me["user"]["id"],
            "status": "authorized",
        }
        assert (await client.post(consumed, headers=csrf, json={"proof": proof})).status_code == 401
        proof = (
            await client.post(endpoint, headers=csrf, json={"password": "synthetic-password"})
        ).json()["proof"]
        await session.execute(
            text("UPDATE account_delete_authorizations SET expires_at=now()-interval '1 second'")
        )
        await session.commit()
        assert (await client.post(consumed, headers=csrf, json={"proof": proof})).status_code == 401


@pytest.mark.parametrize("endpoint", ["/quire-admin/probe", "/quire-admin/settings"])
async def test_native_admin_forms_require_same_origin_csrf(
    client_factory, app, monkeypatch, session, endpoint
):
    import re

    configure(monkeypatch)
    async with client_factory(
        auth_backend="native", ai_enabled=True, admin_users="placeholder", skip_auth_overrides=True
    ) as client:
        me, _ = await signin(client, app)
        app.state.admin_users = {me["user"]["id"].lower()}
        page = await client.get("/quire-admin")
        assert page.status_code == 200
        fields = re.findall(r'name="csrf_token" value="([^"]+)"', page.text)
        # Probe, save, and the separate reset form all carry the browser proof.
        assert fields == [me["csrf_token"]] * 3
        for origin, data in [
            (ORIGIN, {}),
            (ORIGIN, {"csrf_token": "wrong"}),
            (ORIGIN, {"csrf_token": "é"}),
            ("https://untrusted.example.test", {"csrf_token": fields[0]}),
        ]:
            rejected = await client.post(endpoint, headers={"Origin": origin}, data=data)
            assert rejected.status_code == 403
        accepted = await client.post(
            endpoint, headers={"Origin": ORIGIN}, data={"csrf_token": fields[0]}
        )
        assert accepted.status_code == 200, accepted.text
