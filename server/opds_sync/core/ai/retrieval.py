"""Deterministic retrieval from Wikipedia + OpenLibrary, cached in Postgres.

Each public lookup function:
  1. Computes a normalized cache key.
  2. Reads `external_source_cache`. Returns immediately if found and fresh.
  3. Otherwise issues an HTTP call (with a strict timeout). On any failure
     (timeout, non-2xx, JSON parse) returns []; the caller falls through to
     the AI without retrieval grounding. Failures are logged at info — they
     are not bugs, they are normal degraded behavior.
  4. Persists the result and returns.

URL choices:
  - Wikipedia REST: /api/rest_v1/page/summary/{title}
  - OpenLibrary search: /search.json?title=...&author=...&isbn=...&limit=3
"""

from __future__ import annotations

import logging
import re
from datetime import UTC, datetime, timedelta
from urllib.parse import quote

import httpx
from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.api.ai_schemas import Citation
from opds_sync.db.models import ExternalSourceCacheEntry

logger = logging.getLogger(__name__)

_TTL = timedelta(days=30)
_WIKI_BASE = "https://en.wikipedia.org/api/rest_v1"
_OL_BASE = "https://openlibrary.org"


def _normalize_key(s: str) -> str:
    return re.sub(r"\s+", " ", s.strip().lower())


class Retriever:
    def __init__(
        self,
        *,
        session: AsyncSession,
        transport: httpx.AsyncBaseTransport | None = None,
        timeout_s: float = 8.0,
    ) -> None:
        self._session = session
        self._transport = transport
        self._timeout_s = timeout_s

    async def lookup_wikipedia(self, *, author: str | None, title: str) -> list[Citation]:
        key = f"title:{_normalize_key(title)}"
        cached = await self._read_cache("wikipedia", key)
        if cached is not None:
            return [Citation.model_validate(c) for c in cached.get("citations", [])]

        citations = await self._fetch_wikipedia(title)
        # Fallback to author summary if title returned nothing and we have an author.
        if not citations and author:
            author_key = f"author:{_normalize_key(author)}"
            cached_author = await self._read_cache("wikipedia", author_key)
            if cached_author is not None:
                return [Citation.model_validate(c) for c in cached_author.get("citations", [])]
            citations = await self._fetch_wikipedia(author)
            await self._write_cache(
                "wikipedia",
                author_key,
                {"citations": [c.model_dump() for c in citations]},
            )

        await self._write_cache(
            "wikipedia",
            key,
            {"citations": [c.model_dump() for c in citations]},
        )
        return citations

    async def lookup_openlibrary(
        self, *, author: str | None, title: str, isbn: str | None
    ) -> list[Citation]:
        key_bits = [f"title:{_normalize_key(title)}"]
        if author:
            key_bits.append(f"author:{_normalize_key(author)}")
        if isbn:
            key_bits.append(f"isbn:{_normalize_key(isbn)}")
        key = "|".join(key_bits)

        cached = await self._read_cache("openlibrary", key)
        if cached is not None:
            return [Citation.model_validate(c) for c in cached.get("citations", [])]

        params = {"title": title, "limit": "3"}
        if author:
            params["author"] = author
        if isbn:
            params["isbn"] = isbn

        try:
            async with self._http() as http:
                r = await http.get(f"{_OL_BASE}/search.json", params=params)
                if r.status_code != 200:
                    citations = []
                else:
                    citations = self._parse_openlibrary_response(r.json())
        except httpx.HTTPError as e:
            logger.info("ai.retrieval.openlibrary.fail err=%s", e)
            citations = []

        await self._write_cache(
            "openlibrary",
            key,
            {"citations": [c.model_dump() for c in citations]},
        )
        return citations

    async def _fetch_wikipedia(self, term: str) -> list[Citation]:
        try:
            async with self._http() as http:
                # Wikipedia's REST API takes a slug; URL-encode + replace spaces.
                slug = quote(term.strip().replace(" ", "_"), safe="")
                r = await http.get(f"{_WIKI_BASE}/page/summary/{slug}")
                if r.status_code == 404:
                    return []
                if r.status_code != 200:
                    logger.info(
                        "ai.retrieval.wikipedia.status status=%s term=%s", r.status_code, term
                    )
                    return []
                data = r.json()
        except httpx.HTTPError as e:
            logger.info("ai.retrieval.wikipedia.fail err=%s term=%s", e, term)
            return []

        if data.get("type") == "disambiguation":
            return []  # skip ambiguous results to avoid grounding on the wrong entity

        extract = data.get("extract") or ""
        if not extract:
            return []
        url = data.get("content_urls", {}).get("desktop", {}).get("page")
        title = data.get("title") or term
        return [Citation(kind="wikipedia", title=title, url=url, snippet=extract[:1200])]

    @staticmethod
    def _parse_openlibrary_response(payload: dict) -> list[Citation]:
        out: list[Citation] = []
        for doc in (payload.get("docs") or [])[:3]:
            title = doc.get("title") or ""
            authors = doc.get("author_name") or []
            year = doc.get("first_publish_year")
            key = doc.get("key") or ""
            if not title:
                continue
            url = f"https://openlibrary.org{key}" if key.startswith("/") else None
            snippet_bits = [title]
            if authors:
                snippet_bits.append(f"by {', '.join(authors[:3])}")
            if year:
                snippet_bits.append(f"({year})")
            out.append(
                Citation(
                    kind="openlibrary",
                    title=title,
                    url=url,
                    snippet=" — ".join(snippet_bits),
                )
            )
        return out

    def _http(self) -> httpx.AsyncClient:
        kwargs: dict = {
            "timeout": httpx.Timeout(self._timeout_s, connect=min(self._timeout_s, 5.0)),
            "headers": {"User-Agent": "opds-sync/ai-retrieval"},
        }
        if self._transport is not None:
            kwargs["transport"] = self._transport
        return httpx.AsyncClient(**kwargs)

    async def _read_cache(self, source: str, key: str) -> dict | None:
        row = (
            await self._session.execute(
                select(ExternalSourceCacheEntry).where(
                    ExternalSourceCacheEntry.source == source,
                    ExternalSourceCacheEntry.key == key,
                )
            )
        ).scalar_one_or_none()
        if row is None:
            return None
        if row.fetched_at < datetime.now(UTC) - _TTL:
            return None
        return row.payload

    async def _write_cache(self, source: str, key: str, payload: dict) -> None:
        stmt = (
            pg_insert(ExternalSourceCacheEntry)
            .values(
                source=source,
                key=key,
                payload=payload,
                fetched_at=datetime.now(UTC),
            )
            .on_conflict_do_update(
                index_elements=["source", "key"],
                set_={"payload": payload, "fetched_at": datetime.now(UTC)},
            )
        )
        await self._session.execute(stmt)
        await self._session.commit()
