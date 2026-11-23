from fastapi import FastAPI
from fastapi.testclient import TestClient

from quire_server.web import install_web


def test_public_web_delivery(tmp_path):
    (tmp_path / "index.html").write_text("shell")
    (tmp_path / "sw.js").write_text("worker")
    (tmp_path / "assets").mkdir()
    (tmp_path / "assets/app-f3A6DE_U.js").write_text("bundle")
    app = FastAPI()

    @app.get("/quire/v1/bootstrap")
    def bootstrap():
        return {"actual": "api"}

    install_web(app, tmp_path)
    client = TestClient(app)
    nav = {"Accept": "text/html"}
    assert client.get("/quire", follow_redirects=False).headers["location"] == "/quire/"
    assert client.get("/quire/books/123", headers=nav).text == "shell"
    assert client.get("/quire/books/123", headers={"Accept": "application/json"}).status_code == 404
    assert (
        client.get("/quire/books/123", headers={**nav, "Sec-Fetch-Mode": "cors"}).status_code == 404
    )
    assert client.get("/quire/v1/bootstrap", headers=nav).json() == {"actual": "api"}
    for path in (
        "/quire/v1/missing",
        "/quire/assets/missing",
        "/quire/missing.js",
        "/library/v1/missing",
    ):
        assert client.get(path, headers=nav).status_code == 404
    asset = client.get("/quire/assets/app-f3A6DE_U.js")
    assert asset.headers["x-quire-public-asset"] == "1"
    assert "immutable" in asset.headers["cache-control"]
    assert client.head("/quire/sw.js").headers["cache-control"] == "no-cache"

    disabled = FastAPI()
    install_web(disabled, tmp_path, enabled=False)
    assert TestClient(disabled).get("/quire/", headers=nav).status_code == 404


def test_static_files_are_limited_to_the_packaged_allowlist(tmp_path):
    dist = tmp_path / "dist"
    assets = dist / "assets"
    assets.mkdir(parents=True)
    private = tmp_path / "private.txt"
    private.write_text("outside dist")
    (dist / "index.html").write_text("shell")
    (dist / "unlisted.txt").write_text("outside the public manifest")
    (assets / "linked.txt").symlink_to(private)
    (assets / "nested").mkdir()
    (assets / "nested" / "app-f3A6DE_U.js").write_text("bundle")
    app = FastAPI()
    install_web(app, dist)
    client = TestClient(app)
    (assets / "late.txt").write_text("added after startup")

    assert client.get("/quire/assets/nested/app-f3A6DE_U.js").text == "bundle"
    for path in (
        "/quire/assets/linked.txt",
        "/quire/assets/late.txt",
        "/quire/assets/%2e%2e/unlisted.txt",
        "/quire/assets/%2e%2e/%2e%2e/private.txt",
        "/quire/unlisted.txt",
    ):
        response = client.get(path, headers={"Accept": "text/html"})
        assert response.status_code == 404
        assert "x-quire-public-asset" not in response.headers
