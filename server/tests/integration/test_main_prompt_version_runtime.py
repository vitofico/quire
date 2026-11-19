"""A lookup stores the prompt version selected by the real application factory."""

import pytest
from sqlalchemy import select

from quire_server.core.ai.prompts import PROMPT_VERSION
from quire_server.db.models import BookInsight

pytestmark = pytest.mark.requires_ai


@pytest.mark.parametrize("override", [None, "1", "4"])
async def test_lookup_persists_runtime_prompt_version(
    client_factory, app, model_transport, session, monkeypatch, override
):
    monkeypatch.delenv("QUIRE_SERVER_AI_PROMPT_VERSION", raising=False)
    settings = {"ai_prompt_version": override} if override is not None else {}
    async with client_factory(
        ai_enabled=True,
        ai_base_url="http://fixture/v1",
        ai_model="model",
        ai_sources="",
        **settings,
    ) as client:
        app.state.ai_client._transport = model_transport(
            {"schema_version": 2, "intro": "Version fixture", "confidence": "low"}
        )
        prefs = await client.put(
            "/ai/v1/preferences", auth=("alice", "p"), json={"ai_enabled": True}
        )
        assert prefs.status_code == 200, prefs.text
        reply = await client.post(
            "/ai/v1/insights/lookup",
            auth=("alice", "p"),
            json={"identity": {"content_hash": "version-book"}, "bundle": {"title": "Book"}},
        )
        assert reply.status_code == 200, reply.text
    stored = await session.scalar(
        select(BookInsight.prompt_version).where(BookInsight.content_hash == "version-book")
    )
    assert stored == ("4" if override == "4" else PROMPT_VERSION)
