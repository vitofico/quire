from datetime import UTC, datetime, timedelta

import httpx
import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.core.ai.retrieval import (
    Retriever,
    _normalize_key,
)
from opds_sync.db.models import ExternalSourceCacheEntry


def _wiki_summary_response(title: str, extract: str) -> dict:
    return {
        "type": "standard",
        "title": title,
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
        key="title:foundation",
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
async def test_lookup_openlibrary_uses_isbn_when_present(session: AsyncSession):
    seen_urls: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen_urls.append(str(req.url))
        return httpx.Response(
            200,
            json=_ol_search_response(
                [
                    {
                        "title": "Foundation",
                        "author_name": ["Isaac Asimov"],
                        "key": "/works/OL12345W",
                        "first_publish_year": 1951,
                    }
                ]
            ),
        )

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    cites = await r.lookup_openlibrary(
        author="Isaac Asimov", title="Foundation", isbn="9780553293357"
    )
    assert any("isbn=9780553293357" in u for u in seen_urls)
    assert any(c.url and c.url.startswith("https://openlibrary.org/") for c in cites)


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
