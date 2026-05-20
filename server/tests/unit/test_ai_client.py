import json

import httpx
import pytest

from quire_server.api.ai_schemas import BookInsightPayload
from quire_server.core.ai.client import AIClient, ProviderParseError, ProviderTimeout


def _make_chat_response(content: str) -> dict:
    return {
        "id": "x",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }
        ],
        "model": "test-model",
    }


@pytest.mark.asyncio
async def test_chat_structured_returns_validated_payload():
    payload = {
        "schema_version": 2,
        "intro": "A foundational sci-fi novel.",
        "confidence": "high",
    }
    handler = httpx.MockTransport(
        lambda req: httpx.Response(200, json=_make_chat_response(json.dumps(payload)))
    )
    client = AIClient(
        base_url="http://fake/v1",
        api_key="k",
        model="test-model",
        transport=handler,
    )
    result = await client.chat_structured(
        system="sys",
        user="usr",
        schema=BookInsightPayload,
        timeout_s=5.0,
    )
    assert isinstance(result, BookInsightPayload)
    assert result.intro == "A foundational sci-fi novel."


@pytest.mark.asyncio
async def test_chat_structured_retries_once_on_validation_error():
    bad = {"schema_version": 2, "intro": 42}  # intro must be str|None
    good = {"schema_version": 2, "intro": "ok", "confidence": "low"}
    seen: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        body = json.loads(req.content)
        seen.append(body["messages"][-1]["content"])  # remember the last user-side message
        content = json.dumps(bad if len(seen) == 1 else good)
        return httpx.Response(200, json=_make_chat_response(content))

    client = AIClient(
        base_url="http://fake/v1",
        api_key=None,
        model="m",
        transport=httpx.MockTransport(handler),
    )
    out = await client.chat_structured(
        system="s", user="u", schema=BookInsightPayload, timeout_s=5.0
    )
    assert out.intro == "ok"
    assert len(seen) == 2
    assert "validation" in seen[1].lower()


@pytest.mark.asyncio
async def test_chat_structured_raises_after_two_validation_failures():
    bad = {"schema_version": 2, "intro": 42}
    handler = httpx.MockTransport(
        lambda req: httpx.Response(200, json=_make_chat_response(json.dumps(bad)))
    )
    client = AIClient(base_url="http://fake/v1", api_key=None, model="m", transport=handler)
    with pytest.raises(ProviderParseError):
        await client.chat_structured(system="s", user="u", schema=BookInsightPayload, timeout_s=5.0)


@pytest.mark.asyncio
async def test_chat_structured_translates_timeout():
    def handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("boom")

    client = AIClient(
        base_url="http://fake/v1",
        api_key=None,
        model="m",
        transport=httpx.MockTransport(handler),
    )
    with pytest.raises(ProviderTimeout):
        await client.chat_structured(system="s", user="u", schema=BookInsightPayload, timeout_s=0.5)


@pytest.mark.asyncio
async def test_authorization_header_sent_when_key_present():
    seen: dict = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen["auth"] = req.headers.get("Authorization")
        return httpx.Response(
            200,
            json=_make_chat_response(json.dumps({"schema_version": 2, "confidence": "low"})),
        )

    client = AIClient(
        base_url="http://fake/v1",
        api_key="sk-abc",
        model="m",
        transport=httpx.MockTransport(handler),
    )
    await client.chat_structured(system="s", user="u", schema=BookInsightPayload, timeout_s=5.0)
    assert seen["auth"] == "Bearer sk-abc"


@pytest.mark.asyncio
async def test_no_auth_header_when_key_absent():
    seen: dict = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen["auth"] = req.headers.get("Authorization")
        return httpx.Response(
            200,
            json=_make_chat_response(json.dumps({"schema_version": 2, "confidence": "low"})),
        )

    client = AIClient(
        base_url="http://fake/v1",
        api_key=None,
        model="m",
        transport=httpx.MockTransport(handler),
    )
    await client.chat_structured(system="s", user="u", schema=BookInsightPayload, timeout_s=5.0)
    assert seen["auth"] is None


@pytest.mark.asyncio
async def test_4xx_raises_provider_rejected_with_status():
    from quire_server.core.ai.client import ProviderRejected

    handler = httpx.MockTransport(lambda req: httpx.Response(429, json={"error": "rate_limited"}))
    client = AIClient(base_url="http://fake/v1", api_key=None, model="m", transport=handler)
    with pytest.raises(ProviderRejected) as exc:
        await client.chat_structured(system="s", user="u", schema=BookInsightPayload, timeout_s=5.0)
    assert exc.value.status_code == 429


@pytest.mark.asyncio
async def test_5xx_raises_provider_unreachable():
    from quire_server.core.ai.client import ProviderUnreachable

    handler = httpx.MockTransport(lambda req: httpx.Response(503, text="upstream down"))
    client = AIClient(base_url="http://fake/v1", api_key=None, model="m", transport=handler)
    with pytest.raises(ProviderUnreachable):
        await client.chat_structured(system="s", user="u", schema=BookInsightPayload, timeout_s=5.0)
