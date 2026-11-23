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
