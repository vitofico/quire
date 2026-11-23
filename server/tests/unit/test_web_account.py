import httpx

from quire_server.config import get_settings


async def test_bootstrap_and_verified_calibre_session(monkeypatch):
    from quire_server.core import auth_backend
    from quire_server.main import create_app

    async def require_unbound(session, user_id):
        assert user_id == "alice"

    monkeypatch.setattr(auth_backend, "require_unbound_calibre_scope", require_unbound)
    monkeypatch.setenv("QUIRE_SERVER_AUTH_BACKEND", "calibreweb")
    monkeypatch.setenv("QUIRE_SERVER_PROGRESS_ENABLED", "true")
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")
    monkeypatch.setenv("QUIRE_SERVER_WEB_CATALOG_URL", "/opds")
    get_settings.cache_clear()
    try:
        app = create_app()
    finally:
        get_settings.cache_clear()

    async def validate(auth):
        assert auth == "Basic fixture"
        return "alice"

    app.state.auth_validator.validate = validate
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app), base_url="https://test"
    ) as client:
        bootstrap = await client.get("/quire/v1/bootstrap")
        assert bootstrap.headers["cache-control"] == "no-store"
        assert bootstrap.json() == {
            "version": 1,
            "sync": True,
            "ai": False,
            "aiAvailable": False,
            "login": "basic",
            "password": False,
            "oidc": False,
            "catalogUrl": "/opds",
            "accountManagement": None,
        }
        assert (await client.get("/quire/v1/session")).status_code == 401
        headers = {"Authorization": "Basic fixture", "X-Quire-Account": b"bob".hex()}
        assert (await client.get("/quire/v1/session", headers=headers)).status_code == 409
        headers["X-Quire-Account"] = b"alice".hex()
        response = await client.get("/quire/v1/session", headers=headers)
        assert response.headers["cache-control"] == "no-store"
        assert response.json()["user"] == {"id": "alice", "backend": "calibreweb"}


async def test_native_browser_login_must_be_enabled_on_the_quire_origin(monkeypatch):
    from quire_server.main import create_app

    origin = "https://quire.example.test"
    monkeypatch.setenv("QUIRE_SERVER_AUTH_BACKEND", "native")
    monkeypatch.setenv("QUIRE_SERVER_PROGRESS_ENABLED", "true")
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")
    monkeypatch.setenv("QUIRE_IDENTITY_PUBLIC_URL", origin)
    monkeypatch.setenv("QUIRE_SERVER_WEB_NATIVE_AUTH_ENABLED", "false")
    get_settings.cache_clear()
    try:
        disabled = create_app()
        monkeypatch.setenv("QUIRE_SERVER_WEB_NATIVE_AUTH_ENABLED", "true")
        get_settings.cache_clear()
        enabled = create_app()
    finally:
        get_settings.cache_clear()

    for app, base, expected in [
        (disabled, origin, "none"),
        (enabled, "https://calibre.example.test", "none"),
        (enabled, origin, "native"),
    ]:
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url=base) as client:
            response = await client.get("/quire/v1/bootstrap")
            assert response.json()["login"] == expected

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(disabled), base_url=origin
    ) as client:
        response = await client.post(
            "/auth/v1/browser/login",
            headers={"Origin": origin},
            json={
                "email": "fixture@example.test",
                "password": "test",  # pragma: allowlist secret
                "destination": "quire",
            },
        )
        assert response.status_code == 404


async def test_transition_bootstrap_only_advertises_native_on_the_identity_host(monkeypatch):
    from quire_server.main import create_app

    origin = "https://quire.example.test"
    monkeypatch.setenv("QUIRE_SERVER_AUTH_BACKEND", "transition")
    monkeypatch.setenv("QUIRE_SERVER_PROGRESS_ENABLED", "true")
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")
    monkeypatch.setenv("QUIRE_SERVER_WEB_ENABLED", "true")
    monkeypatch.setenv("QUIRE_SERVER_WEB_NATIVE_AUTH_ENABLED", "true")
    monkeypatch.setenv("QUIRE_IDENTITY_PUBLIC_URL", origin)
    get_settings.cache_clear()
    try:
        app = create_app()
    finally:
        get_settings.cache_clear()

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url=origin) as client:
        dedicated = (await client.get("/quire/v1/bootstrap")).json()
        assert dedicated["login"] == "transition"
        assert dedicated["password"] is True
        assert "nativeWebUrl" not in dedicated

        shared = (
            await client.get("/quire/v1/bootstrap", headers={"Host": "books.example.test"})
        ).json()
        assert shared["login"] == "basic"
        assert shared["password"] is False
        assert shared["nativeWebUrl"] == origin + "/quire/"
        for destination in (None, "quire"):
            response = await client.post(
                "/auth/v1/browser/login",
                headers={"Host": "books.example.test", "Origin": origin},
                json={
                    "email": "fixture@example.test",
                    "password": "test",  # pragma: allowlist secret
                    "destination": destination,
                },
            )
            assert response.status_code == 403
            assert "set-cookie" not in response.headers
