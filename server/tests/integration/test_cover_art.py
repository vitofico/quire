"""Painted cover artwork end to end: queued off the request, stored once,
shared, budgeted and never charged, against Postgres and a stubbed image
provider."""

import asyncio
import base64
import io
import json

import httpx
import pytest
from PIL import Image
from sqlalchemy import select

from quire_server.core.cover_art import ImageProvider
from quire_server.db.models import AIGenerationLog, AIUsageDaily, BookCoverArt, BookDetails

from .test_book_details import AI_ENV, body, creds, install, opt_in, tidy_answer

pytestmark = pytest.mark.requires_ai

ART_ENV = {
    **AI_ENV,
    "cover_art_enabled": True,
    "cover_art_base_url": "https://art.example.test/v1",
    "cover_art_model": "art-model",
}


def png(colour=(30, 90, 160)) -> bytes:
    out = io.BytesIO()
    Image.new("RGB", (1024, 1536), colour).save(out, format="PNG")
    return out.getvalue()


async def paint_with(
    app, answer=None, *, gate: asyncio.Event | None = None, timeout_s: float = 120.0
) -> list[dict]:
    """Point the app's artist at a stub provider; returns the requests made."""
    calls: list[dict] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        calls.append(json.loads(request.content))
        if gate is not None:
            await gate.wait()
        if answer is not None:
            return answer
        return httpx.Response(200, json={"data": [{"b64_json": base64.b64encode(png()).decode()}]})

    artist = app.state.cover_artist
    await artist.provider.aclose()
    artist.provider = ImageProvider(
        base_url="https://art.example.test/v1",
        api_key="test-key",
        model="art-model",
        timeout_s=timeout_s,
        transport=httpx.MockTransport(handler),
    )
    return calls


def painted_body(choice="paint", **kw):
    return {**body(**kw), "cover_choice": choice}


async def lookup(client, user="alice", choice="paint", **kw):
    r = await client.post(
        "/details/v1/lookup", headers=creds(user), json=painted_body(choice, **kw)
    )
    assert r.status_code == 200, r.text
    return r.json()


async def test_artwork_is_painted_off_the_request_and_then_offered(client_factory, app, session):
    gate = asyncio.Event()
    async with client_factory(**ART_ENV) as client:
        install(app, tidy_answer())
        calls = await paint_with(app, gate=gate)
        await opt_in(client, "alice", "bob")
        first = await lookup(client)
        # The answer came back while the provider was still painting.
        assert first["cover"]["painting"] is True and first["cover"]["painted"] is False
        drawn_url = first["cover"]["url"]
        usage_before = (await session.execute(select(AIUsageDaily.count))).scalars().all()
        gate.set()
        await app.state.cover_artist.drain()

        second = await lookup(client)
        shared = await lookup(client, "bob")
        painted = await client.get(second["cover"]["url"], headers=creds("alice"))
        still_drawn = await client.get(drawn_url, headers=creds("alice"))
        latest = await client.get(drawn_url.split("?")[0], headers=creds("alice"))
        await app.state.cover_artist.drain()

    assert len(calls) == 1
    # Only the book reaches the provider: no reader, no account.
    assert set(calls[0]) == {"model", "prompt", "n", "size"}
    assert "alice" not in json.dumps(calls[0]) and "Dune" in calls[0]["prompt"]

    assert second["cover"]["painted"] is True and second["cover"]["painting"] is False
    assert second["cover"]["generated"] is True and second["cover"]["source"] == "generated"
    assert second["cover"]["url"] != drawn_url
    assert shared["cover"]["url"] == second["cover"]["url"]

    assert painted.status_code == 200 and painted.headers["content-type"] == "image/jpeg"
    assert second["cover"]["url"].endswith("?v=" + painted.headers["ETag"].strip('"'))
    assert Image.open(io.BytesIO(painted.content)).size == (600, 900)
    # An address a client already held keeps its picture.
    assert still_drawn.status_code == 200 and still_drawn.headers["content-type"] == "image/png"
    assert latest.headers["content-type"] == "image/jpeg"
    # Only a versioned address may be kept forever.
    assert "immutable" in painted.headers["cache-control"]
    assert latest.headers["cache-control"] == "private, no-cache"

    status, image = (await session.execute(select(BookCoverArt.status, BookCoverArt.image))).one()
    assert status == "painted" and len(image) <= 400_000
    log = (
        (await session.execute(select(AIGenerationLog).where(AIGenerationLog.kind == "cover_art")))
        .scalars()
        .one()
    )
    assert (log.status, log.subject, log.model_id) == ("miss", "alice", "art-model")
    # The painting is not charged to the reader.
    usage_after = (await session.execute(select(AIUsageDaily.count))).scalars().all()
    assert usage_after == usage_before


async def test_a_refusal_is_remembered_and_not_retried(client_factory, app, session):
    async with client_factory(**ART_ENV) as client:
        install(app, tidy_answer())
        refusal = httpx.Response(400, json={"error": {"code": "moderation_blocked"}})
        calls = await paint_with(app, refusal)
        await opt_in(client, "alice")
        await lookup(client)
        await app.state.cover_artist.drain()
        again = await lookup(client)
        forced = await client.post("/details/v1/tidy", headers=creds("alice"), json=painted_body())
        await app.state.cover_artist.drain()

    assert len(calls) == 1
    assert again["cover"]["painted"] is False and again["cover"]["painting"] is False
    assert forced.json()["cover"]["painting"] is False
    art = (
        await session.execute(
            select(BookCoverArt.status, BookCoverArt.error_class, BookCoverArt.image)
        )
    ).one()
    assert tuple(art) == ("failed", "refused", None)
    log = (
        (await session.execute(select(AIGenerationLog).where(AIGenerationLog.kind == "cover_art")))
        .scalars()
        .one()
    )
    assert (log.status, log.error_class) == ("error", "refused")


async def test_the_daily_budget_stops_paintings(client_factory, app, session):
    async with client_factory(**ART_ENV, cover_art_daily_budget=1) as client:
        install(app, tidy_answer())
        calls = await paint_with(app)
        # Other readers, so no reader's pointer serves them the first record.
        await opt_in(client, "alice", "bob", "carol")
        await lookup(client)
        await app.state.cover_artist.drain()
        await lookup(client, "bob", title="another_book_(retail)")
        await app.state.cover_artist.drain()
        third = await lookup(client, "carol", title="a_third_book_(retail)")
        await app.state.cover_artist.drain()

    assert len(calls) == 1
    # Once the server knows the budget is spent it says nothing is coming.
    assert third["cover"]["painting"] is False and third["cover"]["painted"] is False
    assert third["cover"]["source"] == "generated"
    rows = (await session.execute(select(BookCoverArt))).scalars().all()
    assert [r.status for r in rows] == ["painted"]


async def test_off_by_default_nothing_changes(client_factory, app, session):
    async with client_factory(**AI_ENV) as client:
        install(app, tidy_answer())
        await opt_in(client, "alice")
        first = await lookup(client, choice="keep")
        # Artwork stored while the feature was on is no longer offered.
        row = (await session.execute(select(BookDetails))).scalars().one()
        session.add(
            BookCoverArt(
                key=row.key, status="painted", image=b"x", digest="0" * 64, model_id="art-model"
            )
        )
        await session.commit()
        second = await lookup(client, choice="keep")
        cover = await client.get(second["cover"]["url"].split("?")[0], headers=creds("alice"))

    assert app.state.cover_artist is None
    assert first["cover"] == second["cover"]
    assert (first["cover"]["painted"], first["cover"]["painting"]) == (False, False)
    assert cover.headers["content-type"] == "image/png"


async def test_enabled_without_a_provider_paints_nothing(client_factory, app, session):
    async with client_factory(**AI_ENV, cover_art_enabled=True) as client:
        install(app, tidy_answer())
        await opt_in(client, "alice")
        answer = await lookup(client, choice="keep")

    assert app.state.cover_artist is None
    assert answer["cover"]["painting"] is False
    assert any("Cover artwork is enabled without" in w for w in app.state.config_warnings)


async def test_artwork_is_painted_from_the_shared_record_not_a_readers_own_fields(
    client_factory, app, session
):
    """The second device reaches the record through the reader's pointer
    with a description of its own; the artwork every reader of the record
    sees must not be painted from it."""
    web = {
        **body(title="Dune", authors=["Frank Herbert"], description="Their own private blurb."),
        "source": "catalog",
        "cover_choice": "paint",
    }
    async with client_factory(**ART_ENV) as client:
        install(app, tidy_answer())
        calls = await paint_with(app)
        await opt_in(client, "alice")
        artist = app.state.cover_artist
        # The record is made from the phone's fields, which have no description.
        phone = await lookup(client, choice="draw")
        r = await client.post("/details/v1/lookup", headers=creds("alice"), json=web)
        other_device = r.json()
        await artist.drain()

    assert other_device["key"] == phone["key"] and other_device["cover"]["painting"] is True
    # This reader still sees their own description...
    assert other_device["book"]["description"] == "Their own private blurb."
    (call,) = calls
    # ...but the shared artwork is painted from the record, which has none.
    assert "Their own private blurb" not in call["prompt"] and "About:" not in call["prompt"]
    assert "Title: Dune" in call["prompt"]


async def test_a_painting_that_runs_too_long_is_a_timeout(client_factory, app, session):
    async with client_factory(**ART_ENV) as client:
        install(app, tidy_answer())
        await paint_with(app, gate=asyncio.Event(), timeout_s=0.05)
        await opt_in(client, "alice")
        await lookup(client)
        await app.state.cover_artist.drain()

    status, error = (
        await session.execute(select(BookCoverArt.status, BookCoverArt.error_class))
    ).one()
    assert (status, error) == ("failed", "timeout")


async def test_a_painting_in_flight_or_lost_counts_against_the_budget(client_factory, app, session):
    async with client_factory(**ART_ENV, cover_art_daily_budget=1) as client:
        install(app, tidy_answer())
        calls = await paint_with(app)
        await opt_in(client, "alice", "bob")
        artist = app.state.cover_artist
        await lookup(client, choice="draw")
        # A server that crashed mid-painting left this row today.
        row = (await session.execute(select(BookDetails))).scalars().one()
        session.add(BookCoverArt(key=row.key, status="pending", model_id="art-model"))
        await session.commit()
        await lookup(client, "bob", title="another_book_(retail)")
        await artist.drain()

    assert calls == []


async def test_only_a_reader_who_asks_gets_a_painting(client_factory, app, session):
    async with client_factory(**ART_ENV) as client:
        install(app, tidy_answer())
        calls = await paint_with(app)
        await opt_in(client, "alice", "bob")
        config = (await client.get("/ai/v1/config", headers=creds("alice"))).json()
        kept = await lookup(client, choice="keep")
        drawn = await lookup(client, choice="draw")
        await app.state.cover_artist.drain()
        assert calls == []
        asked = await lookup(client)
        await app.state.cover_artist.drain()
        painted = await lookup(client)
        # The artwork is the record's, but a reader who did not ask keeps the drawn cover.
        bob_drawn = await lookup(client, "bob", choice="draw")
        bob_kept = await lookup(client, "bob", choice="keep")

    assert config["cover_choice_supported"] is True and config["cover_painting_supported"] is True
    assert kept["cover"]["url"] == drawn["cover"]["url"]
    assert (kept["cover"]["painting"], drawn["cover"]["painting"]) == (False, False)
    assert asked["cover"]["painting"] is True and len(calls) == 1
    assert painted["cover"]["painted"] is True and painted["cover"]["url"] != drawn["cover"]["url"]
    for answer in (bob_drawn, bob_kept):
        assert answer["cover"]["url"] == drawn["cover"]["url"]
        assert answer["cover"]["painted"] is False and answer["cover"]["painting"] is False


async def test_a_painted_cover_may_replace_the_books_own(client_factory, app, session):
    own = {
        **painted_body(title="Dune", authors=["Frank Herbert"], description="A desert planet."),
        "cover": "file",
    }
    async with client_factory(**ART_ENV) as client:
        install(app, tidy_answer(description="A desert planet."))
        calls = await paint_with(app)
        await opt_in(client, "alice")
        r = await client.post("/details/v1/tidy", headers=creds("alice"), json=own)
        await app.state.cover_artist.drain()

    cover = r.json()["cover"]
    assert cover["source"] == "generated" and cover["painting"] is True
    assert r.json()["tidy"] == "not_needed"
    assert len(calls) == 1
