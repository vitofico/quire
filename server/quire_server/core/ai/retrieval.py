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
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from urllib.parse import quote

import httpx
from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.api.ai_schemas import Citation
from quire_server.core.ai.health_state import AiHealthState
from quire_server.db.models import ExternalSourceCacheEntry

logger = logging.getLogger(__name__)

_TTL = timedelta(days=30)
_WIKI_BASE = "https://en.wikipedia.org/api/rest_v1"
_OL_BASE = "https://openlibrary.org"
# pr-β author-bibliography cache TTLs (coordinator §3.7).
_BIBLIO_TTL = timedelta(days=30)
_BIBLIO_NEG_TTL = timedelta(hours=24)
_BIBLIO_429_CAP = timedelta(hours=6)


@dataclass(frozen=True)
class BookRef:
    """One row of an OpenLibrary author bibliography (pr-β)."""

    title: str
    author: str
    work_key: str  # e.g. "/works/OL12345W" — stable across reissues
    source_url: str  # f"https://openlibrary.org{work_key}"


def _normalize_key(s: str) -> str:
    return re.sub(r"\s+", " ", s.strip().lower())


def _parse_openlibrary_works(entries: list[dict], *, default_author: str) -> list[BookRef]:
    """Convert OpenLibrary ``/authors/<key>/works.json`` entries to ``BookRef``.

    ``default_author`` is used when an entry lacks ``authors`` (the works
    endpoint sometimes omits them because the parent author is implicit).
    """
    out: list[BookRef] = []
    for entry in entries:
        title = (entry.get("title") or "").strip()
        work_key = entry.get("key") or ""
        if not title or not work_key.startswith("/works/"):
            continue
        # OpenLibrary may name `authors` as a list of {"author": {"key": ...}}
        # objects; if present and non-empty we leave the name resolution to
        # the OL details endpoint (out of scope here) and just fall back to
        # the queried author. The default is correct for the bibliography
        # use case — we're listing the author's own works.
        author = default_author
        out.append(
            BookRef(
                title=title,
                author=author,
                work_key=work_key,
                source_url=f"{_OL_BASE}{work_key}",
            )
        )
    return out


def _serialize_book_ref(b: BookRef) -> dict:
    return {
        "title": b.title,
        "author": b.author,
        "work_key": b.work_key,
        "source_url": b.source_url,
    }


def _deserialize_book_refs(items: list[dict]) -> list[BookRef]:
    out: list[BookRef] = []
    for it in items or []:
        try:
            out.append(
                BookRef(
                    title=it["title"],
                    author=it["author"],
                    work_key=it["work_key"],
                    source_url=it["source_url"],
                )
            )
        except KeyError:
            continue
    return out


class Retriever:
    def __init__(
        self,
        *,
        session: AsyncSession,
        transport: httpx.AsyncBaseTransport | None = None,
        timeout_s: float = 8.0,
        health_state: AiHealthState | None = None,
    ) -> None:
        self._session = session
        self._transport = transport
        self._timeout_s = timeout_s
        # When None, retrieval reachability updates are no-ops. Cache hits
        # never touch health regardless — the network wasn't called.
        self._health = health_state

    async def _record_retrieval(self, *, name: str, success: bool) -> None:
        if self._health is not None:
            await self._health.record_retrieval(name=name, success=success)

    # ------------------------------------------------------------------
    # pr-β author-bibliography (OpenLibrary)
    # ------------------------------------------------------------------

    async def author_bibliography(self, name: str) -> list[BookRef]:
        """Return up to 50 published works for ``name`` from OpenLibrary.

        Resilience contract (coordinator §3.7, plan §4.5(c)):

          * Positive cache TTL 30d; on hit, deserialize and return.
          * 404 / empty-search → write negative-cache row (24h) and return [].
          * 429 → respect ``Retry-After`` capped at 6h; negative-cache the
            outcome for that window; return [] without raising.
          * 5xx / timeout / network exception → fall back to ``allow_stale``
            positive-cache read; serve stale if present (emit
            ``retrieval.stale_serve``), otherwise return [].

        Empty list on any unrecoverable failure; never raises. Callers
        invoke sequentially per refresh (closes OQ 11.3 by design — at most
        5 authors × ~8s fits under the 90s profile timeout).
        """
        key = _normalize_key(name)
        positive_source = "openlibrary_bibliography"
        negative_source = "openlibrary_bibliography_negative"
        now = datetime.now(UTC)

        # 1. Positive cache (fresh).
        cached = await self._read_cache(positive_source, key, ttl=_BIBLIO_TTL)
        if cached is not None:
            return _deserialize_book_refs(cached.get("books", []))

        # 2. Negative cache (fresh).
        neg = await self._read_cache(negative_source, key, ttl=_BIBLIO_NEG_TTL)
        if neg is not None:
            return []

        # 3. Network fetch.
        try:
            async with self._http() as http:
                # 3a. Resolve author key.
                author_resp = await http.get(
                    f"{_OL_BASE}/search/authors.json",
                    params={"q": name},
                )
                if author_resp.status_code == 429:
                    await self._biblio_negative_cache_429(negative_source, key, author_resp, now)
                    logger.info(
                        "retrieval.openlibrary_429 author=%s retry_after=%s",
                        name,
                        author_resp.headers.get("Retry-After"),
                    )
                    return []
                if author_resp.status_code >= 500:
                    return await self._biblio_stale_or_empty(positive_source, key, name)
                if author_resp.status_code != 200:
                    # 4xx other than 429: treat as "no such author".
                    await self._write_cache(negative_source, key, {"reason": "no_match"})
                    await self._record_retrieval(name=positive_source, success=True)
                    return []
                docs = (author_resp.json() or {}).get("docs") or []
                if not docs:
                    await self._write_cache(negative_source, key, {"reason": "no_match"})
                    await self._record_retrieval(name=positive_source, success=True)
                    return []
                author_key = docs[0].get("key") or ""
                # Normalize to bare key form ("OL...A"). search returns
                # either "OL...A" or "/authors/OL...A" depending on the
                # release; accept both.
                if author_key.startswith("/authors/"):
                    author_key = author_key.removeprefix("/authors/")
                if not author_key:
                    await self._write_cache(negative_source, key, {"reason": "no_key"})
                    await self._record_retrieval(name=positive_source, success=True)
                    return []

                # 3b. Works fetch.
                works_resp = await http.get(
                    f"{_OL_BASE}/authors/{author_key}/works.json",
                    params={"limit": "50"},
                )
                if works_resp.status_code == 429:
                    await self._biblio_negative_cache_429(negative_source, key, works_resp, now)
                    logger.info(
                        "retrieval.openlibrary_429 author=%s retry_after=%s",
                        name,
                        works_resp.headers.get("Retry-After"),
                    )
                    return []
                if works_resp.status_code >= 500:
                    return await self._biblio_stale_or_empty(positive_source, key, name)
                if works_resp.status_code != 200:
                    await self._write_cache(negative_source, key, {"reason": "no_works"})
                    await self._record_retrieval(name=positive_source, success=True)
                    return []

                entries = (works_resp.json() or {}).get("entries") or []
                refs = _parse_openlibrary_works(entries, default_author=name)
                payload = {"books": [_serialize_book_ref(b) for b in refs]}
                await self._write_cache(positive_source, key, payload)
                await self._record_retrieval(name=positive_source, success=True)
                return refs
        except httpx.HTTPError as e:
            logger.info("retrieval.openlibrary_error author=%s err=%s", name, type(e).__name__)
            return await self._biblio_stale_or_empty(positive_source, key, name)

    async def _biblio_negative_cache_429(
        self,
        negative_source: str,
        key: str,
        response: httpx.Response,
        now: datetime,
    ) -> None:
        """Write a negative-cache row that expires at ``now + retry_after``.

        ``Retry-After`` may be numeric seconds or an HTTP-date. Cap at 6h
        per coordinator §3.7. We don't update health here — 429 means
        OpenLibrary is degraded (rate-limited), not down.
        """
        raw = response.headers.get("Retry-After")
        retry_after: timedelta
        if raw is None:
            retry_after = _BIBLIO_429_CAP
        else:
            try:
                retry_after = timedelta(seconds=int(raw))
            except ValueError:
                # HTTP-date format — be conservative and use the cap.
                retry_after = _BIBLIO_429_CAP
        if retry_after > _BIBLIO_429_CAP:
            retry_after = _BIBLIO_429_CAP
        # Bias the row's fetched_at into the past so the row naturally
        # expires after `retry_after` rather than the default 24h.
        fake_fetched_at = now - (_BIBLIO_NEG_TTL - retry_after)
        stmt = (
            pg_insert(ExternalSourceCacheEntry)
            .values(
                source=negative_source,
                key=key,
                payload={"reason": "rate_limited"},
                fetched_at=fake_fetched_at,
            )
            .on_conflict_do_update(
                index_elements=["source", "key"],
                set_={
                    "payload": {"reason": "rate_limited"},
                    "fetched_at": fake_fetched_at,
                },
            )
        )
        await self._session.execute(stmt)
        await self._session.commit()

    async def _biblio_stale_or_empty(
        self,
        positive_source: str,
        key: str,
        author: str,
    ) -> list[BookRef]:
        """Stale-if-error branch: serve a stale positive-cache row when
        present, otherwise return [] and record the retrieval failure.
        """
        row = await self._read_cache_row(positive_source, key)
        if row is not None:
            logger.info(
                "retrieval.stale_serve source=%s author=%s",
                positive_source,
                author,
            )
            return _deserialize_book_refs((row.payload or {}).get("books", []))
        await self._record_retrieval(name=positive_source, success=False)
        return []

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
                # OpenLibrary responded — reachable regardless of status code.
                await self._record_retrieval(name="openlibrary", success=True)
                if r.status_code != 200:
                    citations = []
                else:
                    citations = self._parse_openlibrary_response(r.json())
        except httpx.HTTPError as e:
            logger.info("ai.retrieval.openlibrary.fail err=%s", e)
            await self._record_retrieval(name="openlibrary", success=False)
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
                # We reached Wikipedia and got a response — regardless of
                # status code (404 for unknown titles is normal). The
                # reachability signal is "did the network call complete?".
                await self._record_retrieval(name="wikipedia", success=True)
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
            await self._record_retrieval(name="wikipedia", success=False)
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
            "headers": {"User-Agent": "quire-server/ai-retrieval"},
        }
        if self._transport is not None:
            kwargs["transport"] = self._transport
        return httpx.AsyncClient(**kwargs)

    async def _read_cache(
        self,
        source: str,
        key: str,
        *,
        ttl: timedelta = _TTL,
        allow_stale: bool = False,
    ) -> dict | None:
        """Return the cached payload, or None when no usable row exists.

        ``allow_stale=False`` (default, matching pre-pr-β behavior): rows
        older than ``ttl`` are treated as absent and the function returns
        ``None``. ``allow_stale=True`` (pr-β stale-if-error branch): rows
        are returned regardless of TTL; the caller decides whether to use
        them. The is-stale signal is implicit (``row.fetched_at`` vs.
        ``ttl``); callers needing the boolean flag use ``_read_cache_row``
        instead.
        """
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
        is_stale = row.fetched_at < datetime.now(UTC) - ttl
        if is_stale and not allow_stale:
            return None
        return row.payload

    async def _read_cache_row(
        self,
        source: str,
        key: str,
    ) -> ExternalSourceCacheEntry | None:
        """Return the raw cache row regardless of TTL (or None if absent).

        pr-β uses this from ``author_bibliography`` to distinguish "no row"
        from "stale row" without paying for a second query in the
        stale-if-error branch.
        """
        return (
            await self._session.execute(
                select(ExternalSourceCacheEntry).where(
                    ExternalSourceCacheEntry.source == source,
                    ExternalSourceCacheEntry.key == key,
                )
            )
        ).scalar_one_or_none()

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
