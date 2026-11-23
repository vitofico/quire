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
    if not enabled:
        return
    dist = dist.resolve()
    # Publication files are fixed when the app starts. Resolve only these
    # trusted candidates so a request never becomes part of a filesystem path.
    candidates = [dist / name for name in PUBLIC_FILES]
    assets = dist / "assets"
    if assets.is_dir() and not assets.is_symlink():
        candidates.extend(assets.rglob("*"))
    public: dict[str, Path] = {}
    for candidate in candidates:
        name = candidate.relative_to(dist).as_posix()
        if (name in PUBLIC_FILES or ASSET.fullmatch(name)) and candidate.is_file():
            resolved = candidate.resolve()
            if resolved.is_relative_to(dist):
                public[name] = resolved
    index = public.get("index.html")
    if index is None:
        return

    @app.api_route("/quire", methods=["GET", "HEAD"], include_in_schema=False)
    async def redirect_web() -> RedirectResponse:
        return RedirectResponse("/quire/")

    @app.api_route("/quire/{path:path}", methods=["GET", "HEAD"], include_in_schema=False)
    async def serve_web(path: str, request: Request) -> FileResponse:
        immutable = False
        file = public.get(path)
        if file is not None:
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
            file = index
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
