"""The same Calibre book number on two servers stays two books.

Catalog previews send ``calibre_book_id``, the bare number from a Calibre-Web
download link such as ``/opds/download/5/epub``. Book 5 on one server and book 5
on another are different books, so the server ignores the number and keys a
preview by its host-qualified link hash alone. Old app builds keep sending the
number, so these requests carry it too.
"""

from __future__ import annotations

import base64
import hashlib
import json

import httpx
import pytest

pytestmark = pytest.mark.requires_ai


def _basic_header(user: str) -> dict:
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:p".encode()).decode()}


def _preview(download_url: str) -> dict:
    """Identity a catalog page sends before download, as the apps build it."""
    key = "opds-href:" + hashlib.sha256(download_url.encode()).hexdigest()
    return {"metadata_id": key, "opds_href": key, "calibre_book_id": "5"}


SERVER_A_BOOK_5 = _preview("https://books-a.example/opds/download/5/epub")
SERVER_B_BOOK_5 = _preview("https://books-b.example/opds/download/5/epub")


@pytest.fixture
def titled_model():
    """Fake model whose intro names the book it was asked about."""
    calls: list[str] = []

    async def reply(request: httpx.Request) -> httpx.Response:
        prompt = json.loads(request.content)["messages"][-1]["content"]
        title = next(t for t in ("Dune", "Emma") if t in prompt)
        calls.append(title)
        content = json.dumps(
            {"schema_version": 2, "intro": f"About {title}.", "confidence": "high"}
        )
        return httpx.Response(
            200,
            json={
                "id": "x",
                "model": "test-model",
                "choices": [{"index": 0, "message": {"role": "assistant", "content": content}}],
            },
        )

    return httpx.MockTransport(reply), calls


async def _lookup(client, identity: dict, title: str) -> httpx.Response:
    return await client.post(
        "/ai/v1/insights/lookup",
        headers=_basic_header("calibre-reader"),
        json={"identity": identity, "bundle": {"title": title}},
    )


async def _set_language(client, language: str) -> None:
    r = await client.put(
        "/ai/v1/preferences",
        headers=_basic_header("calibre-reader"),
        json={"ai_enabled": True, "style": {"tone": "neutral", "language": language}},
    )
    assert r.status_code == 200


async def test_same_calibre_number_on_two_servers_stays_two_previews(
    client_factory, configure_ai, app, session, titled_model
):
    """Previewing book 5 on server B after book 5 on server A gets B's own insight."""
    transport, calls = titled_model
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {}).ai._transport = transport
        await _set_language(client, "auto")

        a = await _lookup(client, SERVER_A_BOOK_5, "Dune")
        b = await _lookup(client, SERVER_B_BOOK_5, "Emma")

    assert a.status_code == 200, a.text
    assert b.status_code == 200, b.text
    assert a.json()["payload"]["intro"] == "About Dune."
    assert b.json()["payload"]["intro"] == "About Emma."
    assert calls == ["Dune", "Emma"]


async def test_same_calibre_number_on_two_servers_stays_two_books_after_download(
    client_factory, configure_ai, app, session, titled_model
):
    """Once server A's book 5 is downloaded and known by its real identity,
    server B's book 5 still gets its own insight, not the downloaded book's.
    """
    transport, calls = titled_model
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        configure_ai(app, {}).ai._transport = transport
        await _set_language(client, "auto")

        # Preview server A's book 5, then download and open it (Quire Web sends
        # the real identity plus the catalog link it came from).
        assert (await _lookup(client, SERVER_A_BOOK_5, "Dune")).status_code == 200
        downloaded = {
            "metadata_id": "urn:uuid:dune",
            "content_hash": "sha-dune",
            "opds_href": SERVER_A_BOOK_5["opds_href"],
        }
        assert (await _lookup(client, downloaded, "Dune")).status_code == 200

        # In another insight language, server A's catalog page resolves to the
        # downloaded book and generates its insight under the real identity.
        await _set_language(client, "it")
        again = await _lookup(client, SERVER_A_BOOK_5, "Dune")
        assert again.status_code == 200, again.text
        assert again.json()["payload"]["intro"] == "About Dune."

        b = await _lookup(client, SERVER_B_BOOK_5, "Emma")

    assert b.status_code == 200, b.text
    assert b.json()["payload"]["intro"] == "About Emma."
    assert calls == ["Dune", "Dune", "Dune", "Emma"]
