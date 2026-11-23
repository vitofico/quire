"""Serve the public Quire Web build alongside the existing API."""

import re
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from starlette.responses import FileResponse, RedirectResponse

WEB_DIST = Path("/app/web-dist")
PUBLIC_FILES = frozenset(
    {"index.html", "sw.js", "version.json", "manifest.webmanifest", "icon-192.png", "icon-512.png"}
)
ASSET = re.compile(r"assets/(?:[\w.-]+/)*[\w.-]+")
HASHED_NAME = re.compile(r"-[\w-]{8,}\.[\w.]+$")
API_VERSION = re.compile(r"v\d+(?:/|$)", re.IGNORECASE)


def install_web(app: FastAPI, dist: Path = WEB_DIST, *, enabled: bool = True) -> None:
    if not enabled or not (dist / "index.html").is_file():
        return
    dist = dist.resolve()

    @app.api_route("/quire", methods=["GET", "HEAD"], include_in_schema=False)
    async def redirect_web() -> RedirectResponse:
        return RedirectResponse("/quire/")

    @app.api_route("/quire/{path:path}", methods=["GET", "HEAD"], include_in_schema=False)
    async def serve_web(path: str, request: Request) -> FileResponse:
        immutable = False
        if path in PUBLIC_FILES or ASSET.fullmatch(path):
            file = (dist / path).resolve()
            if not file.is_relative_to(dist) or not file.is_file():
                raise HTTPException(status_code=404)
            immutable = path.startswith("assets/") and bool(HASHED_NAME.search(file.name))
        elif (
            path == "assets"
            or path.startswith("assets/")
            or API_VERSION.match(path)
            or any("." in segment for segment in path.split("/"))
            or "text/html" not in request.headers.get("accept", "")
            or request.headers.get("sec-fetch-mode", "navigate") != "navigate"
        ):
            raise HTTPException(status_code=404)
        else:
            file = dist / "index.html"
        headers = {
            "Cache-Control": "public, max-age=31536000, immutable" if immutable else "no-cache",
            "X-Quire-Public-Asset": "1",
            "X-Content-Type-Options": "nosniff",
        }
        media_type = {
            "manifest.webmanifest": "application/manifest+json",
            "sw.js": "application/javascript",
        }.get(path)
        return FileResponse(file, headers=headers, media_type=media_type)
