"""Issue #102: a provider failure must reach the phone as a status and a
sentence, not as a bare 500.

Drives the real app through ``client_factory`` with the orchestrator's
``AIClient`` swapped for one backed by ``httpx.MockTransport``. The helpers
mirror ``test_ai_health_endpoint.py``; ``_install_fake_ai`` here also takes
an ``api_key`` so a test can prove the key never reaches the response.
"""

from __future__ import annotations

import base64
from datetime import UTC, datetime

import httpx
import pytest

from quire_server.core.ai.client import AIClient
from quire_server.core.ai.health_state import AiHealthState
from quire_server.core.ai.service import InsightOrchestrator
from quire_server.db.models import Document, LibraryItem, Progress

pytestmark = pytest.mark.requires_ai

LOOKUP_BODY = {"identity": {"content_hash": "ch-err"}, "bundle": {"title": "X"}}


def _basic_header(user: str, password: str = "p") -> dict:
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()}


def _chat_response(content: str) -> dict:
    return {
        "id": "x",
        "model": "test-model",
        "choices": [{"index": 0, "message": {"role": "assistant", "content": content}}],
    }


def _install_fake_ai(app, *, fake_handler, api_key: str | None = None) -> InsightOrchestrator:
    ai = AIClient(
        base_url="http://fake/v1",
        api_key=api_key,
        model="test-model",
        transport=httpx.MockTransport(fake_handler),
    )

    class _NoOpRetriever:
        async def lookup_wikipedia(self, **kw):
            return []

        async def lookup_openlibrary(self, **kw):
            return []

    health: AiHealthState = app.state.ai_health
    orch = InsightOrchestrator(
        ai=ai,
        retriever_factory=lambda s: _NoOpRetriever(),
        sources_enabled=(),
        model_id="test-model",
        prompt_version="t1",
        max_concurrency=4,
        ai_timeout_s=5.0,
        health_state=health,
    )
    app.state.ai_orchestrator = orch
    return orch


async def _seed_finished_book(session, *, user_id: str, content_hash: str = "ch-fin") -> None:
    """The minimum reading history that gets ``/profile/refresh`` past its
    low-data short-circuit: one alive library item bridged (user_id +
    content_hash) to a document with a finished progress row. With
    ``finished_count == 0`` the route answers a stats-only payload and never
    calls the provider. Mirrors the ``_seed_*`` helpers in
    ``test_reader_profile.py``.
    """
    now = datetime.now(UTC)
    session.add(
        LibraryItem(
            user_id=user_id,
            metadata_id="m-fin",
            content_hash=content_hash,
            title="Foundation",
            authors=["Isaac Asimov"],
        )
    )
    doc = Document(user_id=user_id, metadata_id="m-fin", content_hash=content_hash)
    session.add(doc)
    await session.flush()
    session.add(
        Progress(
            document_pk=doc.pk,
            locator="{}",
            percent=1.0,
            client_updated_at=now,
            finished_at=now,
        )
    )
    await session.commit()


async def _opted_in_lookup(client_factory, app, *, fake_handler, api_key=None):
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        _install_fake_ai(app, fake_handler=fake_handler, api_key=api_key)
        await client.put(
            "/ai/v1/preferences", headers=_basic_header("alice"), json={"ai_enabled": True}
        )
        return await client.post(
            "/ai/v1/insights/lookup", headers=_basic_header("alice"), json=LOOKUP_BODY
        )


async def test_timeout_returns_504_with_structured_detail(client_factory, app, session):
    def handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("simulated")

    r = await _opted_in_lookup(client_factory, app, fake_handler=handler)
    assert r.status_code == 504
    detail = r.json()["detail"]
    assert detail == {
        "code": "provider_timeout",
        # 120 is the QUIRE_SERVER_AI_TIMEOUT_S default the app was built with;
        # the fake orchestrator's 5.0 is not what the handler reports.
        "message": "The AI provider did not answer within 120 seconds.",
        "hint": "Raise QUIRE_SERVER_AI_TIMEOUT_S for slow local models, or pick a faster model.",
        "provider_status": None,
    }


async def test_unreachable_returns_502(client_factory, app, session):
    def handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="overloaded")

    r = await _opted_in_lookup(client_factory, app, fake_handler=handler)
    assert r.status_code == 502
    detail = r.json()["detail"]
    assert detail["code"] == "provider_unreachable"
    assert detail["message"] == "The server could not reach the AI provider."
    assert "QUIRE_SERVER_AI_BASE_URL" in detail["hint"]
    assert detail["provider_status"] is None
    assert "overloaded" not in r.text


async def test_connect_timeout_returns_502_unreachable(client_factory, app, session):
    """Issue #102, first failure: a firewall fails the 10 s connect phase.
    That is unreachability, and the hint must not send anyone to the timeout."""

    def handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("simulated")

    r = await _opted_in_lookup(client_factory, app, fake_handler=handler)
    assert r.status_code == 502
    detail = r.json()["detail"]
    assert detail["code"] == "provider_unreachable"
    assert "QUIRE_SERVER_AI_BASE_URL" in detail["hint"]
    assert "QUIRE_SERVER_AI_TIMEOUT_S" not in r.text


async def test_rejected_401_hints_api_key_and_never_echoes_it(client_factory, app, session):
    def handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(401, text="invalid key sk-secret-test")

    r = await _opted_in_lookup(client_factory, app, fake_handler=handler, api_key="sk-secret-test")
    assert r.status_code == 502
    detail = r.json()["detail"]
    assert detail["code"] == "provider_rejected"
    assert detail["message"] == "The AI provider rejected the server's credentials."
    assert detail["hint"] == "Check QUIRE_SERVER_AI_API_KEY."
    assert detail["provider_status"] == 401
    assert "sk-secret-test" not in r.text


async def test_rejected_404_hints_model(client_factory, app, session):
    def handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(404, text="model not found")

    r = await _opted_in_lookup(client_factory, app, fake_handler=handler)
    assert r.status_code == 502
    detail = r.json()["detail"]
    assert detail["message"] == "The AI provider does not know the configured model."
    # "m" is the ai_model the app was built with (see client_factory kwargs).
    assert detail["hint"] == "Check QUIRE_SERVER_AI_MODEL; for a local Ollama, run: ollama pull m"
    assert detail["provider_status"] == 404


async def test_unparseable_output_returns_502_invalid_output(client_factory, app, session):
    def handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_chat_response("I am not JSON"))

    r = await _opted_in_lookup(client_factory, app, fake_handler=handler)
    assert r.status_code == 502
    detail = r.json()["detail"]
    assert detail["code"] == "provider_invalid_output"
    assert detail["message"] == (
        "The AI provider answered, but not in the structured format Quire needs."
    )
    assert "gpt-oss:120b-cloud" in detail["hint"]


async def test_error_response_still_carries_request_id(client_factory, app, session):
    """The handler runs inside the middleware stack, so X-Request-ID survives."""

    def handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("simulated")

    r = await _opted_in_lookup(client_factory, app, fake_handler=handler)
    assert r.status_code == 504
    assert r.headers.get("x-request-id")


async def test_profile_refresh_timeout_names_the_profile_variable(client_factory, app, session):
    """``/profile/refresh`` catches ``ProfileGenerationError`` before the
    exception handler can see the provider failure, so the route maps
    ``__cause__`` itself. Same body shape as the insight routes, but the hint
    must name the profile budget rather than the insight one.
    """

    def handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("simulated")

    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        _install_fake_ai(app, fake_handler=handler)
        await _seed_finished_book(session, user_id="alice")
        await client.put(
            "/ai/v1/preferences", headers=_basic_header("alice"), json={"ai_enabled": True}
        )
        r = await client.post("/ai/v1/profile/refresh", headers=_basic_header("alice"))

    assert r.status_code == 504, r.text
    assert r.json()["detail"] == {
        "code": "provider_timeout",
        # 90 is the QUIRE_SERVER_AI_PROFILE_TIMEOUT_S default; the insight
        # tests above see 120 from QUIRE_SERVER_AI_TIMEOUT_S.
        "message": "The AI provider did not answer within 90 seconds.",
        "hint": (
            "Raise QUIRE_SERVER_AI_PROFILE_TIMEOUT_S for slow local models, or pick a faster model."
        ),
        "provider_status": None,
    }
