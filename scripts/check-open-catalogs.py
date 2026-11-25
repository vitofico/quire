"""Check every catalog in ``catalogs/open-catalogs.json`` against the live internet.

Run by hand before changing the list, never in CI:

    PYTHONPATH=server uv run --project server python scripts/check-open-catalogs.py

Each catalog is fetched the way Quire Web's server fetches a saved catalog
(``core.catalog_proxy``: public addresses only, redirects kept on the catalog's
own origin, no compression, the feed size cap), then walked through its
navigation until a feed offers EPUBs, and one EPUB is downloaded the same way.
A catalog passes when its home answers with an OPDS Atom feed without a login
and a reachable EPUB lives on the same https origin. The Android and Web
parsers are covered by their own tests; this only answers whether the real
catalogs still behave.
"""

from __future__ import annotations

import asyncio
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urljoin, urlsplit

from quire_server.api.catalogs import _DOWNLOAD_TYPES, _FEED_TYPES, FEED_LIMIT
from quire_server.core import catalog_proxy

ATOM = "{http://www.w3.org/2005/Atom}"
ACQUISITION = ("http://opds-spec.org/acquisition", "http://opds-spec.org/acquisition/open-access")
EPUB = ("application/epub+zip", "application/epub")
LIST = Path(__file__).resolve().parent.parent / "catalogs" / "open-catalogs.json"
MAX_FEEDS = 8


def origin(url: str) -> tuple[str, str]:
    parts = urlsplit(url)
    return parts.scheme, parts.netloc


async def fetch(url: str, home: str, limit: int) -> tuple[str, str, bytes]:
    upstream = await catalog_proxy.open_upstream(
        url,
        same_origin=lambda candidate: origin(candidate) == origin(home),
        headers={"accept": "application/atom+xml, application/xml;q=0.9, */*;q=0.8"},
        authorization=None,
        allow_private=False,
    )
    try:
        answer = upstream.response
        if answer.status_code != 200:
            raise ValueError(f"HTTP {answer.status_code}")
        if answer.headers.get("content-encoding", "identity").lower() not in ("", "identity"):
            raise ValueError("compressed answer")
        body = bytearray()
        async for chunk in answer.aiter_raw():
            body += chunk
            if len(body) > limit:
                raise ValueError("larger than the proxy passes on")
        media = answer.headers.get("content-type", "").split(";")[0].strip().lower()
        return upstream.url, media, bytes(body)
    finally:
        await upstream.aclose()


async def check(catalog: dict) -> list[str]:
    home = catalog["url"]
    if origin(home)[0] != "https":
        return ["not https"]
    queue, seen = [home], set()
    while queue and len(seen) < MAX_FEEDS:
        url = queue.pop(0)
        if url in seen:
            continue
        seen.add(url)
        final, media, body = await fetch(url, home, FEED_LIMIT)
        if media not in _FEED_TYPES:
            return [f"{url}: a {media or 'untyped'} answer is not passed on as a feed"]
        if b"<!DOCTYPE" in body[:4096].upper():
            return [f"{url}: Quire Web refuses XML with a DOCTYPE"]
        root = ET.fromstring(body)
        if root.tag != ATOM + "feed":
            return [f"{url}: not an Atom feed"]
        for entry in root.iter(ATOM + "entry"):
            for link in entry.findall(ATOM + "link"):
                href = urljoin(final, link.get("href", ""))
                rel, kind = link.get("rel", ""), link.get("type", "")
                if rel in ACQUISITION and kind.split(";")[0] in EPUB:
                    if origin(href) != origin(home):
                        return [f"EPUB on another origin: {href}"]
                    _, media, book = await fetch(href, home, 200 * 1024 * 1024)
                    if media not in _DOWNLOAD_TYPES or not book.startswith(b"PK"):
                        return [f"{href}: not an EPUB ({media})"]
                    return []
                if kind.startswith("application/atom+xml") and origin(href) == origin(home):
                    queue.append(href)
    return [f"no EPUB within {len(seen)} feeds"]


async def main() -> int:
    failed = 0
    for catalog in json.loads(LIST.read_text())["catalogs"]:
        try:
            problems = await check(catalog)
        except (catalog_proxy.UpstreamError, ValueError, ET.ParseError) as error:
            problems = [repr(error)]
        failed += bool(problems)
        print(("FAIL " if problems else "ok   ") + catalog["id"], *problems, sep="  ")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
