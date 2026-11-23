"""Verify public shell delivery and API routing against a running packaged server."""

import json
import re
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def smoke(origin: str, version: str) -> None:
    def get(path: str, accept: str = "*/*"):
        request = Request(origin + path, headers={"Accept": accept})
        with urlopen(request, timeout=10) as response:
            return response.headers, response.read()

    headers, shell = get("/quire/", "text/html")
    assert headers["X-Quire-Public-Asset"] == "1"
    assert headers["Cache-Control"] == "no-cache"
    assert get("/quire/library", "text/html")[1] == shell

    script = re.search(rb'src="(/quire/assets/[^"]+\.js)"', shell)
    assert script is not None
    assert "immutable" in get(script[1].decode())[0]["Cache-Control"]
    assert b"quire-public-shell-" in get("/quire/sw.js")[1]
    assert json.loads(get("/quire/version.json")[1])["version"] == version
    assert json.loads(get("/health")[1])["version"] == version

    assert get("/quire/v1/bootstrap")[0]["Cache-Control"] == "no-store"

    for path in ("/quire/assets/missing.js", "/quire/v1/missing", "/sync/v1/missing"):
        try:
            get(path, "text/html")
        except HTTPError as error:
            assert error.code == 404
        else:
            raise AssertionError(f"Unexpected success for {path}")


if __name__ == "__main__":
    smoke(*sys.argv[1:])
