"""/details/v1: find, tidy, share and draw, against Postgres and a stubbed model."""

import base64
import io
import json

import httpx
import pytest
from PIL import Image
from sqlalchemy import select

from quire_server.core.ai.client import AIClient
from quire_server.core.ai.retrieval import OpenLibraryBook, Retriever
from quire_server.core.ai.service import InsightOrchestrator
from quire_server.db.models import AIGenerationLog, AIUsageDaily, BookDetails

pytestmark = pytest.mark.requires_ai

AI_ENV = {"ai_enabled": True, "ai_base_url": "http://fake", "ai_model": "synthetic"}
DESIGN = {
    "background": "#1F2A44",
    "accent": "#E0A458",
    "ink": "#F4EFE6",
    "motif": "stars",
    "layout": "band",
    "typeface": "sans",
}
DUNE = OpenLibraryBook(
    url="https://openlibrary.org/works/OL893415W",
    title="Dune",
    authors=["Frank Herbert"],
    first_publish_year=1965,
    cover_url=None,
    description="A desert planet and the spice that everyone wants.",
    subjects=["Science fiction"],
)


def creds(user: str) -> dict[str, str]:
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:p".encode()).decode()}


def body(title="dune_frank_herbert_(retail)", authors=("Herbert, Frank",), **book):
    return {
        "identity": {"content_hash": "synthetic-dune"},
        "book": {"title": title, "authors": list(authors), **book},
        "source": "file",
        "cover": None,
    }


def tidy_answer(**kw) -> dict:
    return {
        "title": "Dune",
        "authors": ["Frank Herbert"],
        "series_name": None,
        "series_index": None,
        "description": None,
        "design": DESIGN,
        **kw,
    }


class StubRetriever:
    def __init__(self, book: OpenLibraryBook | None = None):
        self.book = book
        self.lookups = 0

    async def lookup_openlibrary_book(self, **kw):
        self.lookups += 1
        return self.book

    async def lookup_book_language(self, isbn):
        return None


def install(app, answer: dict, *, retriever=None, **orch_kw) -> list[dict]:
    """A real orchestrator whose model answers ``answer``; returns the calls made."""
    calls: list[dict] = []

    def reply(request: httpx.Request) -> httpx.Response:
        calls.append(json.loads(request.content))
        content = json.dumps(answer)
        return httpx.Response(
            200,
            json={
                "id": "x",
                "model": "m",
                "choices": [{"index": 0, "message": {"role": "assistant", "content": content}}],
            },
        )

    ai = AIClient(
        base_url="http://fake/v1",
        api_key=None,
        model="synthetic",
        transport=httpx.MockTransport(reply),
    )
    stub = retriever or StubRetriever()
    app.state.ai_orchestrator = InsightOrchestrator(
        ai=ai,
        retriever_factory=lambda s: stub,
        sources_enabled=("openlibrary",),
        model_id="synthetic",
        prompt_version="t1",
        max_concurrency=4,
        ai_timeout_s=5.0,
        **orch_kw,
    )
    return calls


async def opt_in(client, *users):
    for user in users:
        r = await client.put("/ai/v1/preferences", headers=creds(user), json={"ai_enabled": True})
        assert r.status_code == 200, r.text


async def test_a_file_name_title_is_tidied_and_given_a_cover(client_factory, app, session):
    async with client_factory(**AI_ENV) as client:
        # The model tries to slip in a year, a publisher and an ISBN.
        calls = install(
            app,
            tidy_answer(
                title="Dune (1965)",
                description="Published by Ace in 1965. ISBN 9780441013593. A desert epic.",
            ),
        )
        await opt_in(client, "alice")
        r = await client.post("/details/v1/lookup", headers=creds("alice"), json=body())
        assert r.status_code == 200, r.text
        data = r.json()

    assert len(calls) == 1
    book = data["book"]
    # "1965" is in no input field, so the cleaned title is refused.
    assert book["title"] == "dune_frank_herbert_(retail)"
    assert book["authors"] == ["Frank Herbert"]
    assert book["year"] is None and book["isbn"] is None and book["publisher"] is None
    # No source had a description and Open Library matched nothing.
    assert book["description"] is None
    assert data["origins"] == {"title": "file", "authors": "ai", "cover": "ai"}
    assert data["tidy"] == "done" and data["cached"] is False
    assert data["cover"]["generated"] is True
    assert data["cover"]["url"].startswith(f"/details/v1/covers/{data['key']}?v=")
    stored = await session.get(BookDetails, data["key"])
    assert stored is not None and stored.identity == "content_hash:synthetic-dune"
    for fact in ("1965", "9780441013593", "Ace"):
        assert fact not in json.dumps(stored.record)
    usage = (await session.execute(select(AIUsageDaily))).scalars().one()
    assert (usage.user_id, usage.count, usage.regen_count) == ("alice", 1, 0)
    log = (await session.execute(select(AIGenerationLog))).scalars().one()
    assert (log.kind, log.status, log.subject, log.book_insight_id) == (
        "details",
        "miss",
        "alice",
        None,
    )


async def test_a_clean_title_is_tidied_with_facts_from_open_library_only(
    client_factory, app, session
):
    async with client_factory(**AI_ENV) as client:
        calls = install(
            app,
            tidy_answer(description="A desert planet and its spice. First published in 1963."),
            retriever=StubRetriever(DUNE),
        )
        await opt_in(client, "alice")
        r = await client.post(
            "/details/v1/lookup",
            headers=creds("alice"),
            json=body(title="Dune", authors=[], isbn="9780441013593"),
        )
        data = r.json()

    assert r.status_code == 200, r.text
    assert len(calls) == 1  # matched but no authors: due
    # The model saw the Open Library record.
    assert "desert planet" in calls[0]["messages"][-1]["content"]
    book = data["book"]
    # The author comes from the matched record, so the model may name him.
    assert book["authors"] == ["Frank Herbert"]
    assert data["origins"]["authors"] == "openlibrary"
    assert book["isbn"] == "9780441013593" and data["origins"]["isbn"] == "file"
    assert book["year"] == 1965 and data["origins"]["year"] == "openlibrary"
    assert book["subjects"] == ["Science fiction"]
    # The sentence with the wrong year is gone, and the text, rewritten from
    # Open Library's, is labelled the model's.
    assert book["description"] == "A desert planet and its spice."
    assert data["origins"]["description"] == "ai"
    assert data["openlibrary_url"] == DUNE.url


async def test_extra_fact_keys_in_the_answer_store_nothing(client_factory, app, session):
    async with client_factory(**AI_ENV) as client:
        calls = install(app, tidy_answer(year=1965, isbn="9780441013593", publisher="Ace"))
        await opt_in(client, "alice")
        r = await client.post("/details/v1/lookup", headers=creds("alice"), json=body())
        assert r.status_code == 200, r.text
        data = r.json()
        forced = await client.post("/details/v1/tidy", headers=creds("alice"), json=body())

    assert len(calls) >= 2  # the answer was refused, then retried
    assert data["tidy"] == "failed"
    assert data["book"]["year"] is None and data["book"]["publisher"] is None
    assert data["cover"] == {"source": None, "url": None, "generated": False}
    assert (await session.execute(select(BookDetails))).scalars().all() == []
    assert forced.status_code == 502
    assert forced.json()["detail"]["code"] == "provider_invalid_output"


async def test_a_stored_tidy_is_shared_across_accounts_and_free(client_factory, app, session):
    async with client_factory(**AI_ENV) as client:
        calls = install(app, tidy_answer())
        await opt_in(client, "alice", "bob")
        first = (
            await client.post("/details/v1/lookup", headers=creds("alice"), json=body())
        ).json()
        second = await client.post("/details/v1/lookup", headers=creds("bob"), json=body())
        assert second.status_code == 200, second.text
        cover = await client.get(second.json()["cover"]["url"], headers=creds("bob"))

    assert len(calls) == 1
    shared = second.json()
    assert shared["cached"] is True and shared["tidy"] == "done"
    assert shared["key"] == first["key"] and shared["book"] == first["book"]
    usage = {u.user_id: u.count for u in (await session.execute(select(AIUsageDaily))).scalars()}
    assert usage == {"alice": 1}
    logs = (await session.execute(select(AIGenerationLog).order_by(AIGenerationLog.id))).scalars()
    assert [(log.subject, log.status) for log in logs] == [("alice", "miss"), ("bob", "hit")]
    assert cover.status_code == 200


async def test_false_fields_never_reach_a_reader_with_the_true_ones(client_factory, app, session):
    """Cache poisoning: the record is keyed by the fields it was made from."""
    async with client_factory(**AI_ENV) as client:
        install(app, tidy_answer())
        await opt_in(client, "alice", "mallory", "bob")
        honest = (
            await client.post("/details/v1/lookup", headers=creds("alice"), json=body())
        ).json()

        # Mallory sends the same identity with a false title and author, and
        # a model answer that echoes them.
        install(app, tidy_answer(title="Buy Pills Online", authors=["Spam Bot"]))
        lie = body(title="buy_pills_online", authors=["Spam Bot"])
        poisoned = await client.post("/details/v1/tidy", headers=creds("mallory"), json=lie)
        assert poisoned.status_code == 200, poisoned.text
        again = (await client.post("/details/v1/lookup", headers=creds("bob"), json=body())).json()

    assert poisoned.json()["key"] != honest["key"]
    assert again["key"] == honest["key"] and again["cached"] is True
    assert again["book"]["title"] == "Dune"
    assert again["book"]["authors"] == ["Frank Herbert"]
    assert len((await session.execute(select(BookDetails))).scalars().all()) == 2


async def test_a_same_title_by_another_author_finds_nothing(client_factory, app, session):
    def open_library(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/search.json":
            docs = [
                {
                    "title": "Shadow",
                    "key": "/works/OL1W",
                    "author_name": ["Karen Miller"],
                    "first_publish_year": 2007,
                    "cover_i": 7,
                }
            ]
            return httpx.Response(200, json={"docs": docs})
        return httpx.Response(200, json={"description": "Another book entirely."})

    async with client_factory(**AI_ENV) as client:
        calls = install(app, tidy_answer(title="Shadow", authors=["Will Elliott"]))
        orch = app.state.ai_orchestrator
        orch.retriever_factory = lambda s: Retriever(
            session=s, transport=httpx.MockTransport(open_library), timeout_s=5.0
        )
        await opt_in(client, "alice")
        r = await client.post(
            "/details/v1/lookup",
            headers=creds("alice"),
            json=body(title="Shadow", authors=["Will Elliott"], description="A pilgrim."),
        )
        data = r.json()

    assert r.status_code == 200, r.text
    assert data["openlibrary_url"] is None
    assert data["book"]["year"] is None and data["book"]["subjects"] == []
    assert data["cover"]["source"] == "generated"  # not Karen Miller's cover
    assert "Another book" not in calls[0]["messages"][-1]["content"]


async def test_nothing_due_means_no_model_call(client_factory, app, session):
    found = OpenLibraryBook(**{**DUNE.__dict__, "cover_url": "https://covers.example/1.jpg"})
    async with client_factory(**AI_ENV) as client:
        calls = install(app, tidy_answer(), retriever=StubRetriever(found))
        await opt_in(client, "alice")
        r = await client.post(
            "/details/v1/lookup",
            headers=creds("alice"),
            json=body(title="Dune", authors=["Frank Herbert"]),
        )
        data = r.json()

    assert calls == []
    assert data["tidy"] == "not_needed"
    assert data["cover"] == {"source": "openlibrary", "url": found.cover_url, "generated": False}
    assert data["book"]["description"] == DUNE.description
    assert (await session.execute(select(AIUsageDaily))).scalars().all() == []


async def test_the_allowance_counts_tidies_and_forced_regenerations(client_factory, app, session):
    async with client_factory(**AI_ENV) as client:
        install(app, tidy_answer(), daily_budget=2, regen_daily_limit=1)
        await opt_in(client, "alice")
        first = await client.post("/details/v1/lookup", headers=creds("alice"), json=body())
        regen = await client.post("/details/v1/tidy", headers=creds("alice"), json=body())
        other = {**body(title="dune_messiah_(retail)"), "identity": {"content_hash": "messiah"}}
        spent = await client.post("/details/v1/lookup", headers=creds("alice"), json=other)
        refused = await client.post("/details/v1/tidy", headers=creds("alice"), json=other)
        hit = await client.post("/details/v1/lookup", headers=creds("alice"), json=body())

    assert first.json()["tidy"] == "done" and regen.json()["tidy"] == "done"
    assert spent.status_code == 200 and spent.json()["tidy"] == "quota_exceeded"
    assert refused.status_code == 429 and "Retry-After" in refused.headers
    assert hit.json()["cached"] is True  # a stored tidy is free even with nothing left
    usage = (await session.execute(select(AIUsageDaily))).scalars().one()
    assert (usage.count, usage.regen_count) == (2, 1)


async def test_the_cover_is_cached_rate_limited_and_marked_generated(client_factory, app, session):
    async with client_factory(**AI_ENV, details_cover_rate_per_min=1) as client:
        install(app, tidy_answer())
        await opt_in(client, "alice")
        url = (await client.post("/details/v1/lookup", headers=creds("alice"), json=body())).json()[
            "cover"
        ]["url"]
        r = await client.get(url, headers=creds("alice"))
        not_modified = await client.get(
            url, headers={**creds("alice"), "If-None-Match": r.headers["ETag"]}
        )
        limited = await client.get(url, headers=creds("alice"))
        missing = await client.get("/details/v1/covers/" + "0" * 64, headers=creds("alice"))
        anonymous = await client.get(url)

    assert r.status_code == 200
    assert r.headers["content-type"] == "image/png"
    assert r.headers["x-quire-cover"] == "generated"
    assert "immutable" in r.headers["cache-control"]
    assert url.endswith("?v=" + r.headers["ETag"].strip('"'))
    assert Image.open(io.BytesIO(r.content)).size == (600, 900)
    assert not_modified.status_code == 304
    assert limited.status_code == 429 and int(limited.headers["Retry-After"]) >= 1
    assert missing.status_code == 404 and missing.json()["detail"] == "cover_not_found"
    assert anonymous.status_code == 401


async def test_details_need_the_opt_in_and_an_identity(client_factory, app, session):
    async with client_factory(**AI_ENV) as client:
        install(app, tidy_answer())
        not_opted = await client.post("/details/v1/lookup", headers=creds("alice"), json=body())
        await opt_in(client, "alice")
        nameless = await client.post(
            "/details/v1/lookup",
            headers=creds("alice"),
            json={**body(), "identity": {"opds_href": "/opds/unknown"}},
        )
        extra = await client.post(
            "/details/v1/lookup", headers=creds("alice"), json={**body(), "rating": 5}
        )

    assert not_opted.status_code == 409 and not_opted.json()["detail"] == "ai_not_opted_in"
    assert nameless.status_code == 422
    assert extra.status_code == 422


@pytest.mark.parametrize("env", [{**AI_ENV, "details_enabled": False}, {"ai_enabled": False}])
async def test_the_feature_off_answers_404(client_factory, app, session, env):
    async with client_factory(**env) as client:
        lookup = await client.post("/details/v1/lookup", headers=creds("alice"), json=body())
        forced = await client.post("/details/v1/tidy", headers=creds("alice"), json=body())
        cover = await client.get("/details/v1/covers/" + "0" * 64, headers=creds("alice"))
        config = await client.get("/ai/v1/config", headers=creds("alice"))

    assert (lookup.status_code, forced.status_code, cover.status_code) == (404, 404, 404)
    if env.get("ai_enabled"):
        assert config.json()["details_supported"] is False


async def test_config_announces_the_feature(client_factory, app, session):
    async with client_factory(**AI_ENV) as client:
        config = await client.get("/ai/v1/config", headers=creds("alice"))
    assert config.json()["details_supported"] is True
    assert app.state.hosting_capabilities["book_details"] is True


async def test_unconfigured_ai_answers_503(client_factory, app, session):
    async with client_factory(ai_enabled=True) as client:
        r = await client.post("/details/v1/lookup", headers=creds("alice"), json=body())
    assert r.status_code == 503 and r.json()["detail"] == "ai_disabled"


async def test_lookups_are_rate_limited_before_open_library(client_factory, app, session):
    stub = StubRetriever(DUNE)
    async with client_factory(**AI_ENV, details_lookup_rate_per_min=2) as client:
        install(app, tidy_answer(), retriever=stub)
        await opt_in(client, "alice", "bob")
        first = await client.post("/details/v1/lookup", headers=creds("alice"), json=body())
        forced = await client.post("/details/v1/tidy", headers=creds("alice"), json=body())
        lookups = stub.lookups
        limited = await client.post(
            "/details/v1/lookup", headers=creds("alice"), json=body(title="other_book")
        )
        limited_tidy = await client.post("/details/v1/tidy", headers=creds("alice"), json=body())
        other_reader = await client.post("/details/v1/lookup", headers=creds("bob"), json=body())

    assert first.status_code == 200 and forced.status_code == 200
    for r in (limited, limited_tidy):
        assert r.status_code == 429, r.text
        assert r.json()["detail"] == "details_rate_limited"
        assert int(r.headers["Retry-After"]) >= 1
    assert stub.lookups == lookups  # refused before Open Library was asked
    assert other_reader.status_code == 200  # the limit is per reader


async def test_a_stored_record_is_served_without_asking_open_library(client_factory, app, session):
    stub = StubRetriever(DUNE)
    async with client_factory(**AI_ENV) as client:
        calls = install(app, tidy_answer(), retriever=stub)
        await opt_in(client, "alice", "bob")
        first = (
            await client.post("/details/v1/lookup", headers=creds("alice"), json=body())
        ).json()
        lookups = stub.lookups
        again = (await client.post("/details/v1/lookup", headers=creds("bob"), json=body())).json()

    assert lookups >= 1 and stub.lookups == lookups
    assert len(calls) == 1
    assert again["cached"] is True
    # The facts found when it was tidied are served with it.
    assert again["book"] == first["book"] and again["book"]["year"] == 1965
    assert again["openlibrary_url"] == DUNE.url


async def test_a_failed_tidy_is_not_paid_for_again_by_lookups(client_factory, app, session):
    async with client_factory(**AI_ENV) as client:
        calls = install(app, tidy_answer(year=1965))  # refused as off-schema every time
        await opt_in(client, "alice", "bob")
        first = await client.post("/details/v1/lookup", headers=creds("alice"), json=body())
        tried = len(calls)
        again = await client.post("/details/v1/lookup", headers=creds("bob"), json=body())
        after_lookup = len(calls)
        forced = await client.post("/details/v1/tidy", headers=creds("alice"), json=body())

    assert first.json()["tidy"] == "failed" and tried >= 1
    assert again.status_code == 200 and again.json()["tidy"] == "failed"
    assert after_lookup == tried  # no model call for the remembered failure
    assert forced.status_code == 502 and len(calls) > after_lookup  # asked for, so tried


async def test_a_cover_address_from_before_a_redraw_is_gone(client_factory, app, session):
    async with client_factory(**AI_ENV) as client:
        install(app, tidy_answer())
        await opt_in(client, "alice")
        url = (await client.post("/details/v1/lookup", headers=creds("alice"), json=body())).json()[
            "cover"
        ]["url"]
        install(app, tidy_answer(design={**DESIGN, "motif": "waves"}))
        redrawn = (
            await client.post("/details/v1/tidy", headers=creds("alice"), json=body())
        ).json()["cover"]["url"]
        old = await client.get(url, headers=creds("alice"))
        new = await client.get(redrawn, headers=creds("alice"))

    assert redrawn != url
    assert old.status_code == 404 and old.json()["detail"] == "cover_not_found"
    assert new.status_code == 200


async def test_covers_need_the_opt_in(client_factory, app, session):
    async with client_factory(**AI_ENV) as client:
        install(app, tidy_answer())
        await opt_in(client, "alice")
        url = (await client.post("/details/v1/lookup", headers=creds("alice"), json=body())).json()[
            "cover"
        ]["url"]
        r = await client.get(url, headers=creds("bob"))

    assert r.status_code == 409 and r.json()["detail"] == "ai_not_opted_in"


async def test_another_device_of_the_same_reader_is_served_the_same_record(
    client_factory, app, session
):
    """The phone sent the file's fields; the web sends the catalog's, so the
    shared key differs, yet the reader paid once for this book."""
    web = {
        **body(title="Dune", authors=["Frank Herbert"], description="Their own blurb."),
        "source": "catalog",
        "cover": "catalog",
    }
    async with client_factory(**AI_ENV) as client:
        calls = install(app, tidy_answer())
        await opt_in(client, "alice", "bob")
        phone = (
            await client.post("/details/v1/lookup", headers=creds("alice"), json=body())
        ).json()
        other_device = await client.post("/details/v1/lookup", headers=creds("alice"), json=web)
        assert len(calls) == 1
        # Another reader with the web's fields, and no cover, has no pointer
        # and pays.
        bob = (
            await client.post(
                "/details/v1/lookup", headers=creds("bob"), json={**web, "cover": None}
            )
        ).json()
        assert len(calls) == 2
        # A forced tidy moves alice's pointer to the record for the fields it sent.
        moved = (await client.post("/details/v1/tidy", headers=creds("alice"), json=web)).json()
        back = (await client.post("/details/v1/lookup", headers=creds("alice"), json=body())).json()

    data = other_device.json()
    assert other_device.status_code == 200, other_device.text
    assert data["cached"] is True and data["tidy"] == "done"
    assert data["key"] == phone["key"]
    assert data["book"]["title"] == "Dune"
    # The device that shows its own cover keeps it.
    assert data["cover"] == {"source": "catalog", "url": None, "generated": False}
    assert phone["cover"]["generated"] is True
    assert bob["key"] != phone["key"] and bob["cached"] is False
    assert moved["key"] == bob["key"] != phone["key"]
    assert back["key"] == moved["key"] and back["cached"] is True
    assert back["cover"]["generated"] is True  # the phone has no cover of its own
    assert len(calls) == 3


async def test_a_pointer_to_a_record_from_an_older_prompt_is_ignored(client_factory, app, session):
    async with client_factory(**AI_ENV) as client:
        calls = install(app, tidy_answer())
        await opt_in(client, "alice")
        phone = (
            await client.post("/details/v1/lookup", headers=creds("alice"), json=body())
        ).json()
        row = await session.get(BookDetails, phone["key"])
        row.prompt_version = "details-0"
        await session.commit()
        web = await client.post(
            "/details/v1/lookup",
            headers=creds("alice"),
            json=body(title="Dune", authors=["Frank Herbert"]),
        )

    assert web.json()["key"] != phone["key"] and web.json()["cached"] is False
    assert len(calls) == 2
