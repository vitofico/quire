"""Deterministic retrieval from Wikipedia + OpenLibrary, cached in Postgres.

Each public lookup function:
  1. Computes a normalized cache key.
  2. Reads `external_source_cache`. Returns immediately if found and fresh.
  3. Otherwise issues its HTTP calls (each with a strict timeout). On any
     failure returns []; the caller falls through to the AI without retrieval
     grounding. Failures are logged at info: they are not bugs, they are
     normal degraded behavior.
  4. Persists the result and returns. A transient failure (network error,
     429, 5xx, a body that is not JSON) is not persisted, so the next attempt
     retries; "no such page" and "no matching book" are.

URL choices:
  - Wikipedia REST: /api/rest_v1/page/summary/{title}, then
    /w/rest.php/v1/search/page?q=... when the title has no page of its own
  - OpenLibrary: /search.json?isbn=... or ?title=...&author=..., then
    /works/OL...W.json for the matched work's description
"""

from __future__ import annotations

import logging
import re
import unicodedata
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
_WIKI_SEARCH = "https://en.wikipedia.org/w/rest.php/v1/search/page"
_OL_BASE = "https://openlibrary.org"
# Prefix of the wikipedia title keys and the openlibrary keys. Bump it when the
# lookup strategy changes: an empty result cached by the old strategy would
# otherwise keep a book ungrounded for the rest of its 30 days.
_LOOKUP_KEY_VERSION = "v2"
_SNIPPET_CAP = 1200
_OL_SEARCH_FIELDS = "key,title,author_name,first_publish_year"
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


def _lookup_key(title: str, **bits: str | None) -> str:
    """Cache key of a title lookup. The author and the series decide which
    pages are accepted, so they are part of it."""
    parts = [f"title:{_normalize_key(title)}"]
    parts += [f"{name}:{_normalize_key(value)}" for name, value in bits.items() if value]
    return f"{_LOOKUP_KEY_VERSION}:" + "|".join(parts)


class _SourceDown(Exception):
    """A lookup request failed in transit, was rate limited, hit a 5xx, or got
    a body that is not JSON. The lookup stops and caches nothing, so the next
    attempt retries instead of remembering an outage for 30 days."""


# Title cleanup (issue #102). Ebook titles carry volume, edition and exam text
# that no encyclopedia or catalogue entry has: "Spice and Wolf, Vol. 1". A
# marker only counts where it cannot be part of the name itself: as a whole
# segment of the title (after a comma, colon or spaced dash), inside a
# trailing bracket, or, for the unambiguous "Vol. 2" and "#2", at the very
# end. "The Jungle Book 2", "The New Edition" and "The Special Ed Teacher"
# stay whole.
_ORDINAL = (
    r"(?:\d+(?:st|nd|rd|th)|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth"
    r"|tenth|eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth"
    r"|eighteenth|nineteenth|twentieth)"
)
_EDITION_WORD = (
    r"(?:revised|updated|expanded|anniversary|illustrated|annotated|special|deluxe"
    r"|international|definitive|collector'?s|new)"
)
_EDITION = rf"\b(?:(?:{_ORDINAL}|{_EDITION_WORD})\s+)+(?:edition\b|ed\b\.?)"
_NUMBER_WORDS = ("one two three four five six seven eight nine ten eleven twelve").split()
_VOLUME_NUMBER = rf"(?:\s*(?P<d>\d+(?:\.\d+)?)|\s+(?P<w>[ivx]+|{'|'.join(_NUMBER_WORDS)})\b)"
_VOLUME_HASH = r"#\s*(?P<h>\d+(?:\.\d+)?)"
_VOLUME = rf"(?:\b(?:vol(?:ume)?\.?|book|part){_VOLUME_NUMBER}|{_VOLUME_HASH})"
# Segments are separated by a comma, a semicolon, a colon or a spaced dash.
_SEGMENT_SEP = r"(?:[,;]|:(?=\s)|\s[-\u2013\u2014](?=\s))"
_SEGMENT_END = r"(?=\s*(?:$|[,;(\[{]|:\s|[-\u2013\u2014]\s))"
# ", Vol. 1", ": 11th Edition", "Volume 2: ..."
_MARKER_SEGMENT = re.compile(
    rf"(?:^|{_SEGMENT_SEP})\s*(?:{_EDITION}|{_VOLUME}){_SEGMENT_END}", re.IGNORECASE
)
# ": Book One of the Stormlight Archive" names the volume, not the work.
_VOLUME_SUBTITLE = re.compile(
    rf"{_SEGMENT_SEP}\s*{_VOLUME}" r"\s+[^\s,;:()\[\]{}][^,;:()\[\]{}]*", re.IGNORECASE
)
_TRAILING_VOLUME = re.compile(
    rf"\s(?:\bvol(?:ume)?\.?{_VOLUME_NUMBER}|{_VOLUME_HASH})\s*$", re.IGNORECASE
)
_TRAILING_BRACKET = re.compile(r"\s*[(\[{]([^()\[\]{}]*)[)\]}]\s*$")
_VOLUME_ANYWHERE = re.compile(_VOLUME, re.IGNORECASE)
_SUBTITLE_SEP = re.compile(r":\s|\s[-\u2013\u2014]\s")
_ROMAN = {"i": 1, "v": 5, "x": 10}
_STOPWORDS = frozenset("a an and at by for from in of on or the to with".split())


def _tidy_title(s: str) -> str:
    s = re.sub(r"\s+", " ", s)
    s = re.sub(r"\s+([,;:])", r"\1", s)
    s = re.sub(r"([,;:])(?:\s*[,;:])+", r"\1", s)
    return s.strip(" ,;:/-\u2013\u2014")


def _marker_volume(m: re.Match[str]) -> float | None:
    raw = m.group("d") or m.group("h")
    if raw:
        return float(raw)
    word = (m.group("w") or "").lower()
    if not word:
        return None  # an edition phrase
    if word in _NUMBER_WORDS:
        return float(_NUMBER_WORDS.index(word) + 1)
    values = [_ROMAN[c] for c in word]
    return float(sum(-v if v < nxt else v for v, nxt in zip(values, [*values[1:], 0], strict=True)))


def _strip_markers(title: str) -> tuple[str, float | None]:
    """The title without edition and volume markers or trailing brackets, and
    the volume those markers named ("Vol. 3", "Book Two", "#4"), if any."""
    volumes: list[float] = []

    def drop(m: re.Match[str]) -> str:
        if (volume := _marker_volume(m)) is not None:
            volumes.append(volume)
        return ""

    s = title
    while True:
        before = s
        s = _MARKER_SEGMENT.sub(drop, s)
        s = _VOLUME_SUBTITLE.sub(drop, s)
        s = _TRAILING_VOLUME.sub(drop, s)
        if bracket := _TRAILING_BRACKET.search(s):
            if inner := _VOLUME_ANYWHERE.search(bracket.group(1)):
                drop(inner)
            s = s[: bracket.start()]
        if s == before:
            return _tidy_title(s), (volumes[0] if volumes else None)


def _title_candidates(title: str, *, series: str | None = None) -> list[str]:
    """Titles to look a book up under, strongest first.

    The first is the title without edition and volume markers. The second,
    when there is one, is the part before a subtitle separator (": " or
    " - "): a weaker guess that names the main work. It is dropped when it is
    the book's own series name, because a page about the series, or about its
    first book, is not a page about this book. A candidate made only of words
    like "The" is never used.
    """
    cleaned, _ = _strip_markers(title)
    out = [cleaned]
    main = _tidy_title(_SUBTITLE_SEP.split(cleaned, maxsplit=1)[0])
    if main != cleaned and not (series and _match_form(main) == _match_form(series)):
        out.append(main)
    return [c for c in out if not set(_match_form(c).split()) <= _STOPWORDS]


def _volume_number(title: str) -> float | None:
    """The volume a title names ("Vol. 3", "Book Two", "Part IV", "#4"), or None."""
    return _strip_markers(title)[1]


def _match_form(s: str) -> str:
    """Compare titles on letters, digits, "+" and "#": case, accents, other
    punctuation and "&" versus "and" never decide whether two titles are the
    same, but "C" and "C++" are different books."""
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = s.casefold().replace("&", " and ")
    return " ".join(re.sub(r"(?:[^\w+#]|_)+", " ", s).split())


# A trailing "(novel)", "(1965 novel)", "(light novel)", "(Asimov novel)" and
# the like. Wikipedia adds one only when the bare name already belongs to
# another topic; "(film)" or "(TV series)" never qualifies.
_WORK_QUALIFIER = re.compile(
    r"\s*\((?P<extra>[^()]*?)\s*\b(?:light novel|(?:novel|book) series|short story|novels?"
    r"|novella|books?|manga|memoir|poem)\)$",
    re.IGNORECASE,
)
# A search hit's short description that names another medium: "Sandbox video
# game", "2012 film", "American television series", "Media franchise".
_OTHER_MEDIUM = re.compile(
    r"\b(?:video game|board game|card game|films?|movie|television|tv|franchise|album|song"
    r"|musical|anime|band|podcast)\b",
    re.IGNORECASE,
)
_YEAR = re.compile(r"\d{4}")
_NAME_SUFFIXES = frozenset({"jr", "sr", "ii", "iii"})


def _surname(author: str) -> str | None:
    """The first author's family name in match form: "asimov" for "Isaac
    Asimov", "Asimov, Isaac" and "Isaac Asimov & Robert Silverberg"."""
    first = re.split(r"[,;&]|\band\b", author, maxsplit=1, flags=re.IGNORECASE)[0]
    words = [w for w in _match_form(first).split() if w not in _NAME_SUFFIXES]
    return words[-1] if words else None


def _by_someone_else(page: dict, qualifier: re.Match[str] | None, surname: str) -> bool:
    """Whether a search hit names another author, in its description ("1941
    short story by Isaac Asimov") or in its qualifier ("(Asimov novel)")."""
    by = re.search(r"\bby\s+(.+)", page.get("description") or "", re.IGNORECASE)
    if by and surname not in _match_form(by.group(1)).split():
        return True
    extra = qualifier.group("extra") if qualifier else ""
    words = [w for w in _match_form(extra).split() if not _YEAR.fullmatch(w)]
    return bool(words) and surname not in words


def _matching_wikipedia_pages(
    pages: list[dict], candidates: list[str], *, tried: str, author: str | None
) -> list[str]:
    """Keys of the search hits that name the same work, best first.

    No source is better than the wrong source: a page about another book
    grounds the model on the wrong book. So a hit is accepted only when its
    title, minus a trailing work qualifier such as "(novel)", equals a title
    candidate. "Spice and Wolf" matches "Spice and Wolf, Vol. 1" and "Emma
    (novel)" matches "Emma"; "CompTIA" never matches the CompTIA exam guide.
    When the author is known, a hit that names another author is rejected.
    A bare page found only through the weaker main title is rejected when it
    is about a game, a film or the like: "Minecraft: The Crash" is not the
    video game. Matches on the stronger candidate come first, then qualified
    pages over bare ones, then Wikipedia's own order. ``tried`` is the page
    the direct summary already fetched.
    """
    wanted = [_match_form(c) for c in candidates]
    surname = _surname(author) if author else None
    ranked: list[tuple[int, bool, int, str]] = []
    for order, page in enumerate(pages):
        title = page.get("title") or ""
        key = page.get("key") or ""
        if not title or not key or title == tried:
            continue
        qualifier = _WORK_QUALIFIER.search(title)
        form = _match_form(title[: qualifier.start()] if qualifier else title)
        if form not in wanted:
            continue
        index = wanted.index(form)
        if surname and _by_someone_else(page, qualifier, surname):
            continue
        if index > 0 and qualifier is None and _OTHER_MEDIUM.search(page.get("description") or ""):
            continue
        ranked.append((index, qualifier is None, order, key))
    return [key for *_, key in sorted(ranked)]


# Format words that make a catalogue entry another book than the one with the
# same name: the manga of a light novel, the graphic novel of a novel.
_FORMAT_WORD = re.compile(r"\b(manga|graphic novel|comic)s?\b", re.IGNORECASE)


def _formats(title: str) -> set[str]:
    return {w.lower() for w in _FORMAT_WORD.findall(title)}


def _matching_openlibrary_doc(docs: list[dict], title: str, candidates: list[str]) -> dict | None:
    """The first search result that is the same book, or None.

    The same rule as for Wikipedia: the result's cleaned title must equal a
    title candidate, trying the stronger candidate across all results first.
    The volumes must agree, a title without one counting as the first, so
    "Spice and Wolf, Vol. 1" never borrows the description of "Vol. 14" and
    "Vol. 14" never borrows that of the unnumbered first book. A result that
    names a format the book's title does not, such as "(manga)", is another
    book.
    """
    volume = _volume_number(title) or 1
    formats = _formats(title)
    for wanted in (_match_form(c) for c in candidates):
        for doc in docs:
            doc_title = doc.get("title") or ""
            if not (doc.get("key") or "").startswith("/works/"):
                continue
            doc_cleaned, doc_volume = _strip_markers(doc_title)
            if _match_form(doc_cleaned) != wanted:
                continue
            if (doc_volume or 1) != volume or _formats(doc_title) - formats:
                continue
            return doc
    return None


def _openlibrary_snippet(doc: dict, work: dict | None) -> str:
    """Who wrote it and when, then the work's description and a few subjects."""
    parts = [doc.get("title") or ""]
    authors = doc.get("author_name") or []
    if authors:
        parts.append(f"by {', '.join(authors[:3])}")
    if doc.get("first_publish_year"):
        parts.append(f"({doc['first_publish_year']})")
    snippet = " ".join(parts) + "."
    work = work or {}
    description = work.get("description")
    if isinstance(description, dict):
        description = description.get("value")
    if isinstance(description, str) and description.strip():
        snippet += " " + " ".join(description.split())[:_SNIPPET_CAP]
    # Subjects like "nyt:manga=2010-05-09" or "series:Twilight" are bookkeeping.
    subjects = [
        s
        for s in work.get("subjects") or []
        if isinstance(s, str) and ":" not in s and "=" not in s
    ]
    if subjects:
        snippet += f" Subjects: {', '.join(subjects[:5])}."
    return snippet


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


def _parse_openlibrary_language(payload: dict, bibkey: str) -> str | None:
    """Pull the first edition language code from a Books-API ``jscmd=details``
    response.

    Shape: ``{<bibkey>: {"details": {"languages": [{"key":
    "/languages/fre"}]}}}``. Returns the bare code (``"fre"``) or ``None`` when
    the record, the details block, or the languages array is missing/empty.
    """
    rec = (payload or {}).get(bibkey)
    if not isinstance(rec, dict):
        return None
    details = rec.get("details")
    if not isinstance(details, dict):
        return None
    languages = details.get("languages")
    if not isinstance(languages, list) or not languages:
        return None
    first = languages[0]
    if not isinstance(first, dict):
        return None
    code = (first.get("key") or "").rsplit("/", 1)[-1].strip().lower()
    return code or None


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

    async def lookup_wikipedia(
        self, *, author: str | None, title: str, series: str | None = None
    ) -> list[Citation]:
        key = _lookup_key(title, author=author, series=series)
        cached = await self._read_cache("wikipedia", key)
        if cached is not None:
            return [Citation.model_validate(c) for c in cached.get("citations", [])]

        try:
            async with self._http() as http:
                found = await self._wikipedia_for_title(http, title, author=author, series=series)
                citations = [found] if found is not None else []
                # Fallback to author summary if title returned nothing and we have an author.
                if not citations and author:
                    author_key = f"author:{_normalize_key(author)}"
                    cached_author = await self._read_cache("wikipedia", author_key)
                    if cached_author is not None:
                        return [
                            Citation.model_validate(c) for c in cached_author.get("citations", [])
                        ]
                    found = await self._wikipedia_summary(http, author)
                    citations = [found] if found is not None else []
                    await self._write_cache(
                        "wikipedia",
                        author_key,
                        {"citations": [c.model_dump() for c in citations]},
                    )
        except _SourceDown:
            return []

        await self._write_cache(
            "wikipedia",
            key,
            {"citations": [c.model_dump() for c in citations]},
        )
        return citations

    async def lookup_openlibrary(
        self, *, author: str | None, title: str, isbn: str | None, series: str | None = None
    ) -> list[Citation]:
        key = _lookup_key(title, author=author, isbn=isbn, series=series)

        cached = await self._read_cache("openlibrary", key)
        if cached is not None:
            return [Citation.model_validate(c) for c in cached.get("citations", [])]

        # The ISBN names the edition exactly; the title searches catch the
        # books whose ISBN Open Library does not know. A title search needs
        # every word to match, so a subtitle Open Library does not store hides
        # the book until the main title is tried on its own.
        candidates = _title_candidates(title, series=series)
        searches: list[dict[str, str]] = []
        if isbn:
            searches.append({"isbn": isbn})
        for candidate in candidates:
            by_title = {"title": candidate}
            if author:
                by_title["author"] = author
            searches.append(by_title)

        citations: list[Citation] = []
        try:
            async with self._http() as http:
                for params in searches:
                    data = await self._get_json(
                        http,
                        "openlibrary",
                        f"{_OL_BASE}/search.json",
                        params={**params, "limit": "5", "fields": _OL_SEARCH_FIELDS},
                    )
                    docs = (data or {}).get("docs") or []
                    doc = _matching_openlibrary_doc(docs, title, candidates)
                    if doc is None:
                        continue
                    work = await self._get_json(http, "openlibrary", f"{_OL_BASE}{doc['key']}.json")
                    citations = [
                        Citation(
                            kind="openlibrary",
                            title=doc.get("title") or title,
                            url=f"{_OL_BASE}{doc['key']}",
                            snippet=_openlibrary_snippet(doc, work),
                        )
                    ]
                    break
        except _SourceDown:
            return []

        await self._write_cache(
            "openlibrary",
            key,
            {"citations": [c.model_dump() for c in citations]},
        )
        return citations

    async def lookup_book_language(self, isbn: str) -> str | None:
        """Best-effort edition-level language for an ISBN as an OpenLibrary
        language code (e.g. ``"eng"``, ``"fre"``), or ``None``.

        Uses the Books API ``jscmd=details`` view, whose ``details.languages``
        is edition-specific. We deliberately do NOT reuse ``/search.json``'s
        ``language`` field for this: that one is work-level and aggregates
        every translation a work was ever published in (a French novel comes
        back tagged ger/eng/ita/…/fre), so it can't identify the scanned
        edition. Cached positively AND negatively in ``external_source_cache``
        like the other lookups; never raises.
        """
        norm_isbn = _normalize_key(isbn)
        if not norm_isbn:
            return None
        key = f"isbn:{norm_isbn}"
        cached = await self._read_cache("openlibrary_language", key)
        if cached is not None:
            return cached.get("language")

        bibkey = f"ISBN:{norm_isbn}"
        try:
            async with self._http() as http:
                r = await http.get(
                    f"{_OL_BASE}/api/books",
                    params={"bibkeys": bibkey, "format": "json", "jscmd": "details"},
                )
                # OpenLibrary responded — reachable regardless of status code.
                await self._record_retrieval(name="openlibrary", success=True)
                if r.status_code != 200:
                    # Transient (429 / 5xx) or unexpected status. Do NOT cache:
                    # pinning a null here would suppress backfill for the full
                    # TTL after a temporary OL outage or rate-limit. Return None
                    # and let the next attempt retry.
                    logger.info("ai.retrieval.openlibrary_language.status status=%s", r.status_code)
                    return None
                language = _parse_openlibrary_language(r.json(), bibkey)
        except httpx.HTTPError as e:
            logger.info("ai.retrieval.openlibrary_language.fail err=%s", e)
            await self._record_retrieval(name="openlibrary", success=False)
            # Don't cache a network failure — let the next attempt retry.
            return None

        # Only confirmed 200 responses reach here — cache the positive code or
        # the confirmed "edition has no language" (null) result.
        await self._write_cache("openlibrary_language", key, {"language": language})
        return language

    async def _wikipedia_for_title(
        self, http: httpx.AsyncClient, title: str, *, author: str | None, series: str | None
    ) -> Citation | None:
        """The page for this book: the direct summary of the cleaned title, else
        a search hit that names the same work (see ``_matching_wikipedia_pages``)."""
        candidates = _title_candidates(title, series=series)
        if not candidates:
            return None
        found = await self._wikipedia_summary(http, candidates[0])
        if found is not None:
            return found
        data = await self._get_json(
            http, "wikipedia", _WIKI_SEARCH, params={"q": candidates[0], "limit": "5"}
        )
        pages = (data or {}).get("pages") or []
        for page in _matching_wikipedia_pages(
            pages, candidates, tried=candidates[0], author=author
        ):
            found = await self._wikipedia_summary(http, page)
            if found is not None:
                return found
        return None

    async def _wikipedia_summary(self, http: httpx.AsyncClient, page: str) -> Citation | None:
        # Wikipedia's REST API takes a slug; URL-encode + replace spaces.
        slug = quote(page.strip().replace(" ", "_"), safe="")
        data = await self._get_json(http, "wikipedia", f"{_WIKI_BASE}/page/summary/{slug}")
        if data is None or data.get("type") == "disambiguation":
            return None  # skip ambiguous results to avoid grounding on the wrong entity
        extract = data.get("extract") or ""
        if not extract:
            return None
        url = data.get("content_urls", {}).get("desktop", {}).get("page")
        return Citation(
            kind="wikipedia",
            title=data.get("title") or page,
            url=url,
            snippet=extract[:_SNIPPET_CAP],
        )

    async def _get_json(
        self,
        http: httpx.AsyncClient,
        source: str,
        url: str,
        *,
        params: dict[str, str] | None = None,
    ) -> dict | None:
        """GET one JSON object from a lookup source.

        None means there is nothing there: a 404, or another status that
        retrying will not change. Anything transient raises ``_SourceDown``.
        """
        try:
            r = await http.get(url, params=params)
        except httpx.HTTPError as e:
            logger.info("ai.retrieval.%s.fail err=%s url=%s", source, e, url)
            await self._record_retrieval(name=source, success=False)
            raise _SourceDown from e
        # We reached the source and got a response, so it is reachable whatever
        # the status (404 for an unknown title is normal). The reachability
        # signal is "did the network call complete?".
        await self._record_retrieval(name=source, success=True)
        if r.status_code == 404:
            return None
        if r.status_code != 200:
            logger.info("ai.retrieval.%s.status status=%s url=%s", source, r.status_code, url)
            if r.status_code == 429 or r.status_code >= 500:
                raise _SourceDown
            return None
        try:
            data = r.json()
        except ValueError as e:
            logger.info("ai.retrieval.%s.not_json url=%s", source, url)
            raise _SourceDown from e
        return data if isinstance(data, dict) else None

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
