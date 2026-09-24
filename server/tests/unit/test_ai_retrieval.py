import asyncio
import time
from datetime import UTC, datetime, timedelta

import httpx
import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.core.ai.health_state import AiHealthState
from quire_server.core.ai.retrieval import (
    Retriever,
    _normalize_key,
    _openlibrary_snippet,
    _parse_openlibrary_language,
    _title_candidates,
    _volume_number,
)
from quire_server.db.models import ExternalSourceCacheEntry


def _wiki_summary_response(title: str, extract: str, description: str = "") -> dict:
    return {
        "type": "standard",
        "title": title,
        "description": description,
        "extract": extract,
        "content_urls": {"desktop": {"page": f"https://en.wikipedia.org/wiki/{title}"}},
    }


def _ol_search_response(works: list[dict]) -> dict:
    return {"docs": works}


@pytest.mark.asyncio
async def test_normalize_key_collapses_whitespace_and_lowercases():
    assert _normalize_key("  Isaac   Asimov ") == "isaac asimov"


@pytest.mark.asyncio
async def test_lookup_wikipedia_hits_cache_after_first_call(session: AsyncSession):
    calls: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(str(req.url))
        return httpx.Response(
            200, json=_wiki_summary_response("Foundation_(novel)", "Foundation is a 1951 novel.")
        )

    r = Retriever(
        session=session,
        transport=httpx.MockTransport(handler),
        timeout_s=5.0,
    )
    cites1 = await r.lookup_wikipedia(author="Isaac Asimov", title="Foundation")
    cites2 = await r.lookup_wikipedia(author="Isaac Asimov", title="Foundation")
    assert len(cites1) >= 1
    assert cites1 == cites2
    assert len(calls) == 1  # second call hit cache

    rows = (await session.execute(select(ExternalSourceCacheEntry))).scalars().all()
    assert any(row.source == "wikipedia" for row in rows)


@pytest.mark.asyncio
async def test_lookup_wikipedia_refetches_after_30d(session: AsyncSession):
    # Pre-seed a stale cache row.
    stale = ExternalSourceCacheEntry(
        source="wikipedia",
        key="v2:title:foundation",
        payload={"citations": []},
        fetched_at=datetime.now(UTC) - timedelta(days=31),
    )
    session.add(stale)
    await session.commit()

    fresh_called = False

    def handler(req: httpx.Request) -> httpx.Response:
        nonlocal fresh_called
        fresh_called = True
        return httpx.Response(200, json=_wiki_summary_response("Foundation", "Fresh."))

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    cites = await r.lookup_wikipedia(author=None, title="Foundation")
    assert fresh_called is True
    assert any("Fresh." in c.snippet for c in cites)


def _ol_details_response(bibkey: str, languages: list[str] | None) -> dict:
    details: dict = {}
    if languages is not None:
        details["languages"] = [{"key": f"/languages/{code}"} for code in languages]
    return {bibkey: {"details": details}}


def test_parse_openlibrary_language_extracts_first_code():
    bibkey = "ISBN:9782070360024"
    payload = _ol_details_response(bibkey, ["fre", "eng"])
    assert _parse_openlibrary_language(payload, bibkey) == "fre"


def test_parse_openlibrary_language_handles_missing_pieces():
    bibkey = "ISBN:1"
    assert _parse_openlibrary_language({}, bibkey) is None
    assert _parse_openlibrary_language({bibkey: {}}, bibkey) is None
    assert _parse_openlibrary_language(_ol_details_response(bibkey, []), bibkey) is None
    assert _parse_openlibrary_language(_ol_details_response(bibkey, None), bibkey) is None


@pytest.mark.asyncio
async def test_lookup_book_language_returns_edition_language(session: AsyncSession):
    bibkey = "ISBN:9782070360024"

    def handler(req: httpx.Request) -> httpx.Response:
        # Must hit the edition-level Books API, not /search.json.
        assert "/api/books" in str(req.url)
        assert "jscmd=details" in str(req.url)
        return httpx.Response(200, json=_ol_details_response(bibkey, ["fre"]))

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    assert await r.lookup_book_language("9782070360024") == "fre"


@pytest.mark.asyncio
async def test_lookup_book_language_caches_positive_and_negative(session: AsyncSession):
    calls: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(str(req.url))
        return httpx.Response(200, json=_ol_details_response("ISBN:9780261103573", ["eng"]))

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    assert await r.lookup_book_language("9780261103573") == "eng"
    assert await r.lookup_book_language("9780261103573") == "eng"
    assert len(calls) == 1  # second call hit cache

    rows = (await session.execute(select(ExternalSourceCacheEntry))).scalars().all()
    assert any(row.source == "openlibrary_language" for row in rows)


@pytest.mark.asyncio
async def test_lookup_book_language_negative_result_is_cached(session: AsyncSession):
    calls: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(str(req.url))
        return httpx.Response(200, json=_ol_details_response("ISBN:1", None))

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    assert await r.lookup_book_language("1") is None
    assert await r.lookup_book_language("1") is None
    assert len(calls) == 1  # negative result cached, no second network call


@pytest.mark.asyncio
async def test_lookup_book_language_network_error_not_cached(session: AsyncSession):
    def handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("slow")

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    assert await r.lookup_book_language("9782070360024") is None
    rows = (await session.execute(select(ExternalSourceCacheEntry))).scalars().all()
    # A network failure must not be cached — the next attempt should retry.
    assert not any(row.source == "openlibrary_language" for row in rows)


@pytest.mark.asyncio
async def test_lookup_book_language_transient_status_not_cached(session: AsyncSession):
    """A 429/5xx (rate-limit or outage) must NOT be cached as a null —
    otherwise a transient blip suppresses backfill for the whole TTL."""
    calls: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(str(req.url))
        return httpx.Response(503)

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    assert await r.lookup_book_language("9782070360024") is None
    assert await r.lookup_book_language("9782070360024") is None
    assert len(calls) == 2  # no cache write, so the second call retries
    rows = (await session.execute(select(ExternalSourceCacheEntry))).scalars().all()
    assert not any(row.source == "openlibrary_language" for row in rows)


@pytest.mark.asyncio
async def test_lookups_name_a_contact_in_the_user_agent(session: AsyncSession):
    agents: set[str] = set()

    def handler(req: httpx.Request) -> httpx.Response:
        agents.add(req.headers["User-Agent"])
        return httpx.Response(404)

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    await r.lookup_wikipedia(author=None, title="Dune")
    await r.lookup_openlibrary(author=None, title="Dune", isbn=None)

    assert agents == {"quire-server/ai-retrieval (+https://github.com/vitofico/quire)"}


@pytest.mark.asyncio
async def test_lookup_wikipedia_returns_empty_on_404(session: AsyncSession):
    r = Retriever(
        session=session,
        transport=httpx.MockTransport(lambda req: httpx.Response(404)),
        timeout_s=5.0,
    )
    cites = await r.lookup_wikipedia(author=None, title="Definitely Nonexistent Book Xyz")
    assert cites == []


@pytest.mark.asyncio
async def test_lookup_wikipedia_returns_empty_on_timeout(session: AsyncSession):
    def handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("slow")

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=0.5)
    cites = await r.lookup_wikipedia(author=None, title="Anything")
    assert cites == []  # network failure is non-fatal


@pytest.mark.asyncio
async def test_lookup_openlibrary_searches_by_isbn_first_and_quotes_the_description(
    session: AsyncSession,
):
    searches: list[httpx.QueryParams] = []

    def handler(req: httpx.Request) -> httpx.Response:
        if req.url.path == "/search.json":
            searches.append(req.url.params)
            return httpx.Response(
                200,
                json=_ol_search_response(
                    [
                        {
                            "title": "Foundation",
                            "author_name": ["Isaac Asimov"],
                            "key": "/works/OL46125W",
                            "first_publish_year": 1951,
                        }
                    ]
                ),
            )
        assert req.url.path == "/works/OL46125W.json"
        return httpx.Response(
            200,
            json={
                "description": "The Galactic Empire is dying and only Hari Seldon knows it.",
                "subjects": ["Science fiction", "nyt:paperback=2021-05-02", "Galactic empires"],
            },
        )

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    cites = await r.lookup_openlibrary(
        author="Isaac Asimov", title="Foundation", isbn="9780553293357"
    )
    # The ISBN matched, so the title search never ran.
    assert len(searches) == 1
    assert searches[0]["isbn"] == "9780553293357"
    assert "title" not in searches[0]
    assert len(cites) == 1
    assert cites[0].url == "https://openlibrary.org/works/OL46125W"
    assert cites[0].snippet == (
        "Foundation by Isaac Asimov (1951). The Galactic Empire is dying and only Hari "
        "Seldon knows it. Subjects: Science fiction, Galactic empires."
    )


# ---------------------------------------------------------------------------
# Issue #102: finding the book when the ebook title is not the catalogue title
# ---------------------------------------------------------------------------

_SPICE = "Spice and Wolf, Vol. 1"
_COMPTIA = (
    "CompTIA A+ Certification All-in-One Exam Guide, Eleventh Edition "
    "(Exams 220-1101 & 220-1102), 11th Edition"
)
_NASB = "New American Standard Bible - NASB 2020: Holy Bible"


@pytest.mark.parametrize(
    ("title", "candidates"),
    [
        (_SPICE, ["Spice and Wolf"]),
        (_COMPTIA, ["CompTIA A+ Certification All-in-One Exam Guide"]),
        (_NASB, [_NASB, "New American Standard Bible"]),
        ("Dune", ["Dune"]),
        ("Mistborn #3", ["Mistborn"]),
        ("Calculus, 2nd ed.", ["Calculus"]),
        (
            "Harry Potter and the Chamber of Secrets (Harry Potter #2)",
            ["Harry Potter and the Chamber of Secrets"],
        ),
        # A subtitle that starts with a volume marker names the volume, not the work.
        ("The Way of Kings: Book One of the Stormlight Archive", ["The Way of Kings"]),
        (
            "Mistborn: The Final Empire (Mistborn, Book 1)",
            ["Mistborn: The Final Empire", "Mistborn"],
        ),
        ("Naruto, Vol. 1: Uzumaki Naruto", ["Naruto: Uzumaki Naruto", "Naruto"]),
        ("Spice and Wolf Vol. 3", ["Spice and Wolf"]),
        ("Ghost in the Shell #1.5", ["Ghost in the Shell"]),
        ("Dune: Deluxe Edition", ["Dune"]),
        # Markers count only as a whole segment, in a trailing bracket, or ("Vol.
        # 2", "#2") at the very end, so words that belong to the name stay.
        ("The New Edition", ["The New Edition"]),
        ("The First Edition", ["The First Edition"]),
        ("The Tenth Edition", ["The Tenth Edition"]),
        ("The Jungle Book 2", ["The Jungle Book 2"]),
        ("The Special Ed Teacher", ["The Special Ed Teacher"]),
        ("Confessions of a Special Ed Teacher", ["Confessions of a Special Ed Teacher"]),
        ("The Book I Wish I'd Read", ["The Book I Wish I'd Read"]),
        ("Hunger: A Memoir of (My) Body", ["Hunger: A Memoir of (My) Body", "Hunger"]),
        ("The Book Thief", ["The Book Thief"]),
        (
            "The Absolutely True Diary of a Part-Time Indian",
            ["The Absolutely True Diary of a Part-Time Indian"],
        ),
        ("Catch-22", ["Catch-22"]),
        ("Nineteen Eighty-Four", ["Nineteen Eighty-Four"]),
        ("Part of Your World", ["Part of Your World"]),
        ("Book of Mormon", ["Book of Mormon"]),
        ("The Book of Three", ["The Book of Three"]),
        ("Slaughterhouse-Five", ["Slaughterhouse-Five"]),
        ("Fahrenheit 451", ["Fahrenheit 451"]),
        ("#Girlboss", ["#Girlboss"]),
        ("The No. 1 Ladies' Detective Agency", ["The No. 1 Ladies' Detective Agency"]),
        ("2001: A Space Odyssey", ["2001: A Space Odyssey", "2001"]),
        # A candidate that is only "The" would fetch the article about the word.
        ("The: Book 2", []),
    ],
)
def test_title_candidates(title: str, candidates: list[str]):
    assert _title_candidates(title) == candidates


def test_title_candidates_drop_the_main_title_when_it_is_the_series():
    assert _title_candidates("Mistborn: The Hero of Ages", series="Mistborn") == [
        "Mistborn: The Hero of Ages"
    ]
    assert _title_candidates("The Hunger Games: Mockingjay", series="the hunger games") == [
        "The Hunger Games: Mockingjay"
    ]
    # The cleaned title itself stays, even when it is the series name.
    assert _title_candidates(_SPICE, series="Spice and Wolf") == ["Spice and Wolf"]


@pytest.mark.parametrize(
    ("title", "volume"),
    [
        (_SPICE, 1),
        ("Spice and Wolf, Volume Twelve", 12),
        ("Dune Messiah, Book Two", 2),
        ("Foundation, Part IV", 4),
        ("Saga, Volume IX", 9),
        # A trailing bracket names the book's place in its series, not a volume.
        ("Harry Potter and the Chamber of Secrets (Harry Potter #2)", None),
        ("Words of Radiance (The Stormlight Archive, Book 2)", None),
        ("The Way of Kings: Book One of the Stormlight Archive", 1),
        ("Ghost in the Shell #1.5", 1.5),
        ("The Book I Wish I'd Read", None),
        ("The Jungle Book 2", None),
        ("Dune", None),
    ],
)
def test_volume_number(title: str, volume: float | None):
    assert _volume_number(title) == volume


def _wiki_router(
    summaries: dict[str, dict], search_pages: list[dict], seen: list[str]
) -> httpx.MockTransport:
    """Summaries by slug (anything else is a 404) and one canned search answer."""

    def handler(req: httpx.Request) -> httpx.Response:
        path = req.url.raw_path.decode()
        seen.append(path)
        if path.startswith("/w/rest.php/v1/search/page"):
            return httpx.Response(200, json={"pages": search_pages})
        slug = path.removeprefix("/api/rest_v1/page/summary/")
        if slug in summaries:
            return httpx.Response(200, json=summaries[slug])
        return httpx.Response(404)

    return httpx.MockTransport(handler)


def _page(title: str, description: str = "") -> dict:
    return {"key": title.replace(" ", "_"), "title": title, "description": description}


@pytest.mark.asyncio
async def test_wikipedia_direct_summary_of_the_cleaned_title(session: AsyncSession):
    seen: list[str] = []
    summaries = {"Spice_and_Wolf": _wiki_summary_response("Spice and Wolf", "A light novel.")}
    r = Retriever(session=session, transport=_wiki_router(summaries, [], seen), timeout_s=5.0)

    cites = await r.lookup_wikipedia(author="Isuna Hasekura", title=_SPICE)

    assert [c.title for c in cites] == ["Spice and Wolf"]
    assert seen == ["/api/rest_v1/page/summary/Spice_and_Wolf"]  # no search needed


@pytest.mark.asyncio
async def test_wikipedia_search_hit_naming_the_main_title_is_accepted(session: AsyncSession):
    seen: list[str] = []
    summaries = {
        "New_American_Standard_Bible": _wiki_summary_response(
            "New American Standard Bible", "An English translation of the Bible."
        )
    }
    pages = [
        _page("New American Standard Bible", "English translation of the Bible"),
        _page("Legacy Standard Bible", "English translation of the Bible"),
    ]
    r = Retriever(session=session, transport=_wiki_router(summaries, pages, seen), timeout_s=5.0)

    cites = await r.lookup_wikipedia(author=None, title=_NASB)

    assert [c.title for c in cites] == ["New American Standard Bible"]
    assert seen[-1] == "/api/rest_v1/page/summary/New_American_Standard_Bible"
    assert not any("Legacy" in p for p in seen)


@pytest.mark.asyncio
async def test_wikipedia_search_hit_for_a_different_work_is_rejected(session: AsyncSession):
    """The exam guide has no page. "CompTIA" (the company) is not the book, so
    the lookup falls back to the author instead of grounding on it."""
    seen: list[str] = []
    summaries = {"CompTIA": _wiki_summary_response("CompTIA", "A trade association.")}
    pages = [_page("CompTIA"), _page("Certification")]
    r = Retriever(session=session, transport=_wiki_router(summaries, pages, seen), timeout_s=5.0)

    cites = await r.lookup_wikipedia(author="Jane Author", title=_COMPTIA)

    assert cites == []
    assert "/api/rest_v1/page/summary/CompTIA" not in seen
    assert seen[-1] == "/api/rest_v1/page/summary/Jane_Author"


@pytest.mark.asyncio
async def test_wikipedia_disambiguation_is_skipped_for_the_novel_page(session: AsyncSession):
    seen: list[str] = []
    summaries = {
        "Emma": {"type": "disambiguation", "title": "Emma", "extract": "Emma may refer to:"},
        "Emma_%28novel%29": _wiki_summary_response("Emma (novel)", "An 1815 novel."),
    }
    pages = [_page("Emma"), _page("Emma Frost"), _page("Emma (novel)"), _page("Emma (2020 film)")]
    r = Retriever(session=session, transport=_wiki_router(summaries, pages, seen), timeout_s=5.0)

    cites = await r.lookup_wikipedia(author="Jane Austen", title="Emma")

    assert [c.title for c in cites] == ["Emma (novel)"]
    # The disambiguation page is fetched once, by the direct lookup, not again from search.
    assert seen.count("/api/rest_v1/page/summary/Emma") == 1
    assert "/api/rest_v1/page/summary/Emma_Frost" not in seen


_NIGHTFALL_SUMMARIES = {
    "Nightfall": {"type": "disambiguation", "title": "Nightfall", "extract": "It may refer to:"},
    "Nightfall_%28Asimov_novelette_and_novel%29": _wiki_summary_response(
        "Nightfall (Asimov novelette and novel)", "A world that sees the stars once."
    ),
    "Nightfall_%28Halpern_and_Kujawinski_novel%29": _wiki_summary_response(
        "Nightfall (Halpern and Kujawinski novel)", "An island of long days and nights."
    ),
}
_NIGHTFALL_PAGES = [
    _page("Nightfall"),
    _page("Nightfall (Asimov novelette and novel)", "1941 short story by Isaac Asimov"),
    _page(
        "Nightfall (Halpern and Kujawinski novel)",
        "2015 novel by Jake Halpern and Peter Kujawinski",
    ),
]


@pytest.mark.asyncio
async def test_wikipedia_search_hit_by_another_author_is_rejected(session: AsyncSession):
    """Two novels share the title; the author decides which page is the book,
    and the cached answer for one author is not served to the other."""
    r = Retriever(
        session=session,
        transport=_wiki_router(_NIGHTFALL_SUMMARIES, _NIGHTFALL_PAGES, []),
        timeout_s=5.0,
    )

    halpern = await r.lookup_wikipedia(author="Jake Halpern", title="Nightfall: A Novel")
    asimov = await r.lookup_wikipedia(author="Asimov, Isaac", title="Nightfall: A Novel")
    other = await r.lookup_wikipedia(author="Robert Silverberg", title="Nightfall: A Novel")

    assert [c.title for c in halpern] == ["Nightfall (Halpern and Kujawinski novel)"]
    assert [c.title for c in asimov] == ["Nightfall (Asimov novelette and novel)"]
    assert other == []


@pytest.mark.asyncio
async def test_wikipedia_main_title_hit_about_a_video_game_is_rejected(session: AsyncSession):
    seen: list[str] = []
    summaries = {"Minecraft": _wiki_summary_response("Minecraft", "A sandbox game.")}
    pages = [_page("Minecraft", "Sandbox video game"), _page("Minecraft (film)", "2025 film")]
    r = Retriever(session=session, transport=_wiki_router(summaries, pages, seen), timeout_s=5.0)

    cites = await r.lookup_wikipedia(author="Tracey Baptiste", title="Minecraft: The Crash")

    assert cites == []
    assert "/api/rest_v1/page/summary/Minecraft" not in seen


_SAND = "Hill of loose sand built by aeolian processes or the flow of water"
_FRANCHISE = "American science fiction media franchise"
_SET_INDEX = "Index of articles associated with the same name"


@pytest.mark.asyncio
async def test_wikipedia_direct_page_naming_no_author_of_the_book_is_rejected(
    session: AsyncSession,
):
    """The page named "Dune" is the sand dune. Its description names no Herbert,
    so the lookup searches instead and takes the novel's own page."""
    seen: list[str] = []
    summaries = {
        "Dune": _wiki_summary_response("Dune", "A dune is a landform.", _SAND),
        "Dune_%28novel%29": _wiki_summary_response(
            "Dune (novel)", "Dune is a 1965 novel.", "1965 novel by Frank Herbert"
        ),
    }
    pages = [
        _page("Dune", _SAND),
        _page("Dune (novel)", "1965 novel by Frank Herbert"),
        _page("Dune (franchise)", _FRANCHISE),
        _page("Dune (2021 film)", "2021 film by Denis Villeneuve"),
    ]
    r = Retriever(session=session, transport=_wiki_router(summaries, pages, seen), timeout_s=5.0)

    cites = await r.lookup_wikipedia(author="Frank Herbert", title="Dune")

    assert [c.title for c in cites] == ["Dune (novel)"]
    assert seen.count("/api/rest_v1/page/summary/Dune") == 1


# "Comic" alone is an adjective here, not a written form.
@pytest.mark.parametrize("description", [_FRANCHISE, "2004 comic fantasy film"])
@pytest.mark.asyncio
async def test_wikipedia_direct_page_about_another_medium_is_rejected(
    session: AsyncSession, description: str
):
    seen: list[str] = []
    summaries = {
        "The_Hunger_Games": _wiki_summary_response(
            "The Hunger Games", "Not the book.", description
        ),
        "The_Hunger_Games_%28novel%29": _wiki_summary_response(
            "The Hunger Games (novel)", "A 2008 novel.", "2008 novel by Suzanne Collins"
        ),
    }
    pages = [
        _page("The Hunger Games", description),
        _page("The Hunger Games (novel)", "2008 novel by Suzanne Collins"),
        _page("The Hunger Games (film)", "2012 film by Gary Ross"),
    ]
    r = Retriever(session=session, transport=_wiki_router(summaries, pages, seen), timeout_s=5.0)

    cites = await r.lookup_wikipedia(author=None, title="The Hunger Games")

    assert [c.title for c in cites] == ["The Hunger Games (novel)"]


@pytest.mark.asyncio
async def test_wikipedia_set_index_page_is_skipped(session: AsyncSession):
    """The page only lists the novel and the two films. Wikipedia does not mark
    it as disambiguation, so its description is what gives it away."""
    seen: list[str] = []
    summaries = {
        "The_Hunger_Games%3A_Mockingjay": _wiki_summary_response(
            "The Hunger Games: Mockingjay", "It may refer to:", _SET_INDEX
        ),
        "Suzanne_Collins": _wiki_summary_response("Suzanne Collins", "An American author."),
    }
    pages = [
        _page("The Hunger Games: Mockingjay", _SET_INDEX),
        _page("The Hunger Games: Mockingjay - Part 1", "2014 film by Francis Lawrence"),
    ]
    r = Retriever(session=session, transport=_wiki_router(summaries, pages, seen), timeout_s=5.0)

    cites = await r.lookup_wikipedia(
        author="Suzanne Collins", title="The Hunger Games: Mockingjay", series="The Hunger Games"
    )

    assert [c.title for c in cites] == ["Suzanne Collins"]
    assert seen.count("/api/rest_v1/page/summary/The_Hunger_Games%3A_Mockingjay") == 1


@pytest.mark.parametrize(
    ("author", "title", "page", "description"),
    [
        ("Isuna Hasekura", _SPICE, "Spice and Wolf", "Japanese light novel series and anime"),
        (None, "Mockingjay", "Mockingjay", "2010 novel by Suzanne Collins"),
    ],
)
@pytest.mark.asyncio
async def test_wikipedia_direct_page_about_the_book_is_kept(
    session: AsyncSession, author: str | None, title: str, page: str, description: str
):
    seen: list[str] = []
    slug = page.replace(" ", "_")
    summaries = {slug: _wiki_summary_response(page, "About the book.", description)}
    r = Retriever(session=session, transport=_wiki_router(summaries, [], seen), timeout_s=5.0)

    cites = await r.lookup_wikipedia(author=author, title=title)

    assert [c.title for c in cites] == [page]
    assert seen == [f"/api/rest_v1/page/summary/{slug}"]  # no search needed


@pytest.mark.asyncio
async def test_wikipedia_outage_is_not_cached(session: AsyncSession):
    calls: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(str(req.url))
        return httpx.Response(503)

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    assert await r.lookup_wikipedia(author="Frank Herbert", title="Dune") == []
    assert await r.lookup_wikipedia(author="Frank Herbert", title="Dune") == []
    # One request per attempt: the outage stops the lookup, and nothing is remembered.
    assert len(calls) == 2
    rows = (await session.execute(select(ExternalSourceCacheEntry))).scalars().all()
    assert rows == []


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [403, 429])
async def test_wikipedia_refused_search_is_not_cached(session: AsyncSession, status: int):
    """A 429 (rate limited) or a 403 (blocked) may pass; unlike the 404 on the
    direct page, it stops the lookup and nothing is remembered."""
    seen: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req.url.path)
        return httpx.Response(status if req.url.path.startswith("/w/rest.php") else 404)

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)

    assert await r.lookup_wikipedia(author="Frank Herbert", title="Dune") == []
    assert seen == ["/api/rest_v1/page/summary/Dune", "/w/rest.php/v1/search/page"]
    rows = (await session.execute(select(ExternalSourceCacheEntry))).scalars().all()
    assert rows == []


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [400, 404])
async def test_wikipedia_no_such_page_is_cached(session: AsyncSession, status: int):
    calls: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(req.url.path)
        return httpx.Response(status)

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)

    assert await r.lookup_wikipedia(author=None, title="Nowhere Book") == []
    assert await r.lookup_wikipedia(author=None, title="Nowhere Book") == []
    assert len(calls) == 2  # the direct page and the search, once
    rows = (await session.execute(select(ExternalSourceCacheEntry))).scalars().all()
    assert [row.key for row in rows] == ["v2:title:nowhere book"]


@pytest.mark.asyncio
async def test_wikipedia_author_cache_hit_still_caches_the_title(session: AsyncSession):
    """The author page is cached, but the title lookups must not repeat on
    every generation of a book that has no page of its own."""
    author_page = {"kind": "wikipedia", "title": "Mike Meyers (author)", "snippet": "Guides."}
    session.add(
        ExternalSourceCacheEntry(
            source="wikipedia", key="author:mike meyers", payload={"citations": [author_page]}
        )
    )
    await session.commit()
    calls: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(req.url.path)
        return httpx.Response(404)

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)

    first = await r.lookup_wikipedia(author="Mike Meyers", title=_COMPTIA)
    second = await r.lookup_wikipedia(author="Mike Meyers", title=_COMPTIA)

    assert [c.title for c in first] == ["Mike Meyers (author)"]
    assert second == first
    assert len(calls) == 2  # the direct page and the search, once; never the author page


@pytest.mark.asyncio
async def test_wikipedia_fetches_at_most_two_hit_summaries(session: AsyncSession):
    seen: list[str] = []
    pages = [
        _page("Nightfall (novel)"),
        _page("Nightfall (1941 novel)"),
        _page("Nightfall (2015 novel)"),
    ]
    r = Retriever(session=session, transport=_wiki_router({}, pages, seen), timeout_s=5.0)

    assert await r.lookup_wikipedia(author=None, title="Nightfall") == []
    assert len([p for p in seen if p.startswith("/api/rest_v1/page/summary/Nightfall_")]) == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("source", ["wikipedia", "openlibrary"])
async def test_a_slow_source_is_cut_off_and_not_cached(session: AsyncSession, source: str):
    """Every request answers, only slowly: the lookup stops at its time budget
    (twice the per-request timeout) and caches nothing."""

    async def handler(req: httpx.Request) -> httpx.Response:
        await asyncio.sleep(5)
        return httpx.Response(404)

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=0.05)
    started = time.monotonic()
    if source == "wikipedia":
        cites = await r.lookup_wikipedia(author="Frank Herbert", title="Dune")
    else:
        cites = await r.lookup_openlibrary(author="Frank Herbert", title="Dune", isbn=None)

    assert cites == []
    assert time.monotonic() - started < 2
    rows = (await session.execute(select(ExternalSourceCacheEntry))).scalars().all()
    assert rows == []


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["network", "503", "not_json"])
async def test_openlibrary_outage_is_not_cached(session: AsyncSession, failure: str):
    calls: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(req.url.path)
        if failure == "network":
            raise httpx.ConnectError("down")
        if failure == "503":
            return httpx.Response(503)
        return httpx.Response(200, text="<html>Under maintenance</html>")

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    for _ in range(2):
        cites = await r.lookup_openlibrary(
            author="Frank Herbert", title="Dune", isbn="9780441172719"
        )
        assert cites == []

    # The first request stops each attempt, and nothing is remembered.
    assert calls == ["/search.json", "/search.json"]
    rows = (await session.execute(select(ExternalSourceCacheEntry))).scalars().all()
    assert rows == []


def _ol_router(docs: list[dict], works: dict[str, dict], seen: list[str]) -> httpx.MockTransport:
    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(str(req.url))
        if req.url.path == "/search.json":
            return httpx.Response(200, json=_ol_search_response(docs))
        return httpx.Response(200, json=works.get(req.url.path, {}))

    return httpx.MockTransport(handler)


@pytest.mark.asyncio
async def test_openlibrary_title_match_rejects_other_volumes_and_other_books(
    session: AsyncSession,
):
    seen: list[str] = []
    docs = [
        {"title": "Spice And Wolf 1", "key": "/works/OL1W"},
        {"title": "Spice and Wolf, Vol. 14 (manga)", "key": "/works/OL14W"},
        {"title": "Honey and Spice", "key": "/works/OL2W"},
    ]
    r = Retriever(session=session, transport=_ol_router(docs, {}, seen), timeout_s=5.0)

    cites = await r.lookup_openlibrary(author="Isuna Hasekura", title=_SPICE, isbn=None)

    assert cites == []
    assert len(seen) == 1  # the search only; no work was fetched
    params = httpx.URL(seen[0]).params
    assert params["title"] == "Spice and Wolf"
    assert params["author"] == "Isuna Hasekura"


@pytest.mark.asyncio
async def test_openlibrary_title_match_accepts_the_same_volume(session: AsyncSession):
    seen: list[str] = []
    docs = [
        {"title": "Spice and Wolf, Vol. 14 (manga)", "key": "/works/OL14W"},
        {
            "title": "Spice and Wolf, Vol. 1",
            "key": "/works/OL1W",
            "author_name": ["Isuna Hasekura"],
            "first_publish_year": 2006,
        },
    ]
    works = {"/works/OL1W.json": {"description": {"type": "/type/text", "value": "Holo."}}}
    r = Retriever(session=session, transport=_ol_router(docs, works, seen), timeout_s=5.0)

    cites = await r.lookup_openlibrary(author="Isuna Hasekura", title=_SPICE, isbn=None)

    assert [c.url for c in cites] == ["https://openlibrary.org/works/OL1W"]
    assert cites[0].snippet == "Spice and Wolf, Vol. 1 by Isuna Hasekura (2006). Holo."


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("docs", "expected"),
    [
        # Neither the unnumbered first book nor the manga of volume 14 is the novel.
        (
            [
                {"title": "Spice and Wolf", "key": "/works/OL1W"},
                {"title": "Spice and Wolf, Vol. 14 (manga)", "key": "/works/OL14MW"},
            ],
            [],
        ),
        (
            [
                {"title": "Spice and Wolf, Vol. 14 (manga)", "key": "/works/OL14MW"},
                {"title": "Spice and Wolf, Vol. 14", "key": "/works/OL14W"},
            ],
            ["https://openlibrary.org/works/OL14W"],
        ),
    ],
)
async def test_openlibrary_later_volume_needs_the_same_volume_and_format(
    session: AsyncSession, docs: list[dict], expected: list[str]
):
    r = Retriever(session=session, transport=_ol_router(docs, {}, []), timeout_s=5.0)

    cites = await r.lookup_openlibrary(
        author="Isuna Hasekura", title="Spice and Wolf, Vol. 14", isbn=None
    )

    assert [c.url for c in cites] == expected


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("title", "docs", "expected"),
    [
        (
            "Catching Fire (The Hunger Games, #2)",
            [{"title": "Catching Fire", "key": "/works/OL5735364W"}],
            "/works/OL5735364W",
        ),
        # "Book 2" of the series is the whole novel, not the second half of a split edition.
        (
            "Words of Radiance (The Stormlight Archive, Book 2)",
            [
                {"title": "Words of Radiance, Part Two", "key": "/works/OL2W"},
                {"title": "Words of Radiance", "key": "/works/OL1W"},
            ],
            "/works/OL1W",
        ),
    ],
)
async def test_openlibrary_series_position_in_brackets_is_not_a_volume(
    session: AsyncSession, title: str, docs: list[dict], expected: str
):
    r = Retriever(session=session, transport=_ol_router(docs, {}, []), timeout_s=5.0)

    cites = await r.lookup_openlibrary(author="Someone", title=title, isbn=None)

    assert [c.url for c in cites] == [f"https://openlibrary.org{expected}"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("title", "isbn", "doc_title"),
    [
        (
            "Harry Potter and the Chamber of Secrets (Harry Potter #2)",
            "9780439064873",
            "Harry Potter and the Chamber of Secrets",
        ),
        # The ISBN pins the edition, so a work filed under the series title still counts.
        ("Spice and Wolf, Vol. 2", "9780759531055", "Spice and Wolf"),
    ],
)
async def test_openlibrary_isbn_hit_skips_the_volume_check(
    session: AsyncSession, title: str, isbn: str, doc_title: str
):
    seen: list[str] = []
    docs = [{"title": doc_title, "key": "/works/OL9W"}]
    r = Retriever(session=session, transport=_ol_router(docs, {}, seen), timeout_s=5.0)

    cites = await r.lookup_openlibrary(author=None, title=title, isbn=isbn)

    assert [c.url for c in cites] == ["https://openlibrary.org/works/OL9W"]
    assert httpx.URL(seen[0]).params["isbn"] == isbn
    assert len(seen) == 2  # the ISBN search and the work, no title search


@pytest.mark.asyncio
async def test_openlibrary_skips_the_main_title_when_it_is_the_series(session: AsyncSession):
    """Searching "The Hunger Games" for "Mockingjay" finds book one."""
    seen: list[str] = []
    docs = [{"title": "The Hunger Games", "key": "/works/OL5735363W"}]
    r = Retriever(session=session, transport=_ol_router(docs, {}, seen), timeout_s=5.0)

    cites = await r.lookup_openlibrary(
        author="Suzanne Collins",
        title="The Hunger Games: Mockingjay",
        isbn=None,
        series="The Hunger Games",
    )

    assert cites == []
    assert [httpx.URL(u).params["title"] for u in seen] == ["The Hunger Games: Mockingjay"]


@pytest.mark.asyncio
async def test_openlibrary_isbn_hit_with_another_title_falls_back_to_the_title(
    session: AsyncSession,
):
    searches: list[httpx.QueryParams] = []

    def handler(req: httpx.Request) -> httpx.Response:
        if req.url.path != "/search.json":
            return httpx.Response(200, json={})
        searches.append(req.url.params)
        if "isbn" in req.url.params:
            return httpx.Response(
                200, json=_ol_search_response([{"title": "Another Book", "key": "/works/OL9W"}])
            )
        return httpx.Response(
            200,
            json=_ol_search_response(
                [{"title": "CompTIA A+ Certification All-in-One Exam Guide", "key": "/works/OL5W"}]
            ),
        )

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    cites = await r.lookup_openlibrary(author=None, title=_COMPTIA, isbn="9781264609956")

    assert [c.url for c in cites] == ["https://openlibrary.org/works/OL5W"]
    assert [sorted(p.keys()) for p in searches] == [
        ["fields", "isbn", "limit"],
        ["fields", "limit", "title"],
    ]


@pytest.mark.asyncio
async def test_openlibrary_tries_the_main_title_when_the_full_title_finds_nothing(
    session: AsyncSession,
):
    titles: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        if req.url.path != "/search.json":
            return httpx.Response(200, json={})
        titles.append(req.url.params["title"])
        docs = [{"title": "Atomic Habits", "key": "/works/OL17930368W"}]
        return httpx.Response(200, json=_ol_search_response(docs if len(titles) > 1 else []))

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    cites = await r.lookup_openlibrary(
        author="James Clear",
        title="Atomic Habits: An Easy & Proven Way to Build Good Habits & Break Bad Ones",
        isbn=None,
    )

    assert [c.title for c in cites] == ["Atomic Habits"]
    assert titles == [
        "Atomic Habits: An Easy & Proven Way to Build Good Habits & Break Bad Ones",
        "Atomic Habits",
    ]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("title", "expected"),
    [("C Primer Plus", "/works/OL2W"), ("C++ Primer Plus", "/works/OL1W")],
)
async def test_openlibrary_keeps_c_and_c_plus_plus_apart(
    session: AsyncSession, title: str, expected: str
):
    docs = [
        {"title": "C++ Primer Plus", "key": "/works/OL1W"},
        {"title": "C Primer Plus", "key": "/works/OL2W"},
    ]
    r = Retriever(session=session, transport=_ol_router(docs, {}, []), timeout_s=5.0)

    cites = await r.lookup_openlibrary(author="Stephen Prata", title=title, isbn=None)

    assert [c.url for c in cites] == [f"https://openlibrary.org{expected}"]


def test_openlibrary_snippet_is_capped():
    doc = {"title": "Dune", "author_name": ["Frank Herbert"], "first_publish_year": 1965}
    snippet = _openlibrary_snippet(doc, {"description": "word " * 1000, "subjects": ["Deserts"]})
    assert snippet.startswith("Dune by Frank Herbert (1965). word word")
    assert len(snippet) == 1200
    assert _openlibrary_snippet(doc, None) == "Dune by Frank Herbert (1965)."


@pytest.mark.asyncio
async def test_rows_cached_by_the_old_strategy_are_ignored(session: AsyncSession):
    """An empty result cached before the strategy changed must not pin the
    book to "no source" for the rest of its 30 days."""
    for source, key in (
        ("wikipedia", "title:dune"),
        ("openlibrary", "title:dune|author:frank herbert"),
    ):
        session.add(ExternalSourceCacheEntry(source=source, key=key, payload={"citations": []}))
    await session.commit()

    def handler(req: httpx.Request) -> httpx.Response:
        if req.url.host == "en.wikipedia.org":
            return httpx.Response(200, json=_wiki_summary_response("Dune (novel)", "A novel."))
        if req.url.path == "/search.json":
            return httpx.Response(
                200, json=_ol_search_response([{"title": "Dune", "key": "/works/OL893414W"}])
            )
        return httpx.Response(200, json={"description": "Arrakis."})

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    assert await r.lookup_wikipedia(author="Frank Herbert", title="Dune") != []
    assert await r.lookup_openlibrary(author="Frank Herbert", title="Dune", isbn=None) != []


@pytest.mark.asyncio
async def test_lookup_wikipedia_percent_encodes_special_chars(session: AsyncSession):
    """Titles with non-ASCII or URL-special chars must be properly encoded."""
    seen_urls: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen_urls.append(str(req.url))
        return httpx.Response(
            200, json=_wiki_summary_response("Il_nome_della_rosa", "Test extract.")
        )

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    await r.lookup_wikipedia(author=None, title="Il nome della rosa")
    # Spaces → underscores → percent-encoded? Actually underscores stay literal; only
    # non-ASCII / special chars must be encoded. So a simple ASCII title is fine here.
    # The real test: a title with `#` must not break.
    seen_urls.clear()

    def handler_special(req: httpx.Request) -> httpx.Response:
        seen_urls.append(str(req.url))
        return httpx.Response(200, json=_wiki_summary_response("Test", "ok"))

    r2 = Retriever(session=session, transport=httpx.MockTransport(handler_special), timeout_s=5.0)
    await r2.lookup_wikipedia(author=None, title="C#")  # should not produce a fragment-shaped URL
    assert any("C%23" in u or "C%23" in str(u) for u in seen_urls), (
        f"expected percent-encoded C# in URL, got: {seen_urls}"
    )


# ---------------------------------------------------------------------------
# PR5: health-state hooks
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_wikipedia_200_records_reachable_true(session: AsyncSession):
    health = AiHealthState()

    def handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_wiki_summary_response("Foundation", "ext"))

    r = Retriever(
        session=session,
        transport=httpx.MockTransport(handler),
        timeout_s=5.0,
        health_state=health,
    )
    await r.lookup_wikipedia(author=None, title="Foundation")
    snap = await health.snapshot()
    assert snap.retrieval_sources["wikipedia"].reachable is True


@pytest.mark.asyncio
async def test_wikipedia_404_records_reachable_true(session: AsyncSession):
    """404 still means the network reached Wikipedia. Reachability is True."""
    health = AiHealthState()

    def handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={"detail": "missing"})

    r = Retriever(
        session=session,
        transport=httpx.MockTransport(handler),
        timeout_s=5.0,
        health_state=health,
    )
    cites = await r.lookup_wikipedia(author=None, title="DefinitelyNotAPage")
    assert cites == []
    snap = await health.snapshot()
    assert snap.retrieval_sources["wikipedia"].reachable is True


@pytest.mark.asyncio
async def test_wikipedia_timeout_records_reachable_false(session: AsyncSession):
    health = AiHealthState()

    def handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("simulated")

    r = Retriever(
        session=session,
        transport=httpx.MockTransport(handler),
        timeout_s=1.0,
        health_state=health,
    )
    cites = await r.lookup_wikipedia(author=None, title="X")
    assert cites == []
    snap = await health.snapshot()
    assert snap.retrieval_sources["wikipedia"].reachable is False


@pytest.mark.asyncio
async def test_openlibrary_200_records_reachable_true(session: AsyncSession):
    health = AiHealthState()

    def handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_ol_search_response([]))

    r = Retriever(
        session=session,
        transport=httpx.MockTransport(handler),
        timeout_s=5.0,
        health_state=health,
    )
    await r.lookup_openlibrary(author="Asimov", title="Foundation", isbn=None)
    snap = await health.snapshot()
    assert snap.retrieval_sources["openlibrary"].reachable is True


@pytest.mark.asyncio
async def test_openlibrary_timeout_records_reachable_false(session: AsyncSession):
    health = AiHealthState()

    def handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("simulated")

    r = Retriever(
        session=session,
        transport=httpx.MockTransport(handler),
        timeout_s=1.0,
        health_state=health,
    )
    cites = await r.lookup_openlibrary(author="X", title="Y", isbn=None)
    assert cites == []
    snap = await health.snapshot()
    assert snap.retrieval_sources["openlibrary"].reachable is False


@pytest.mark.asyncio
async def test_cache_hit_does_not_update_health(session: AsyncSession):
    """A second lookup that hits the cache must NOT call record_retrieval.

    The network wasn't reached on the second call, so reachability stays as
    it was after the first call.
    """
    health = AiHealthState()
    call_count = 0

    def handler(req: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        return httpx.Response(200, json=_wiki_summary_response("Foundation", "ext"))

    r = Retriever(
        session=session,
        transport=httpx.MockTransport(handler),
        timeout_s=5.0,
        health_state=health,
    )
    await r.lookup_wikipedia(author=None, title="Foundation")
    snap1 = await health.snapshot()
    first_ts = snap1.retrieval_sources["wikipedia"].last_checked_at

    # Second lookup: should hit cache.
    await r.lookup_wikipedia(author=None, title="Foundation")
    assert call_count == 1  # confirm cache hit

    snap2 = await health.snapshot()
    # Timestamp unchanged because the second call didn't touch the network.
    assert snap2.retrieval_sources["wikipedia"].last_checked_at == first_ts
