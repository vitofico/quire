import json
import logging

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


@pytest.mark.asyncio
async def test_connect_timeout_is_unreachable_not_slow():
    """A firewall or a wrong host fails the 10 s connect phase (issue #102).
    That is not the model being slow, so it must not point at the timeout."""
    from quire_server.core.ai.client import ProviderUnreachable

    def handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("simulated")

    client = AIClient(
        base_url="http://fake/v1",
        api_key=None,
        model="m",
        transport=httpx.MockTransport(handler),
    )
    with pytest.raises(ProviderUnreachable):
        await client.chat_structured(system="s", user="u", schema=BookInsightPayload, timeout_s=5.0)


def _bodies(requests: list[httpx.Request]) -> list[dict]:
    return [json.loads(r.content) for r in requests]


@pytest.mark.asyncio
async def test_schema_is_enforced_by_the_provider_not_pasted_into_the_prompt():
    """Issue #102: a 1B model handed a schema in the prompt echoed the schema back.

    The schema now travels in ``response_format``, where the provider can
    constrain the tokens, and the system prompt no longer carries it.
    """
    seen: list[httpx.Request] = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req)
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

    body = _bodies(seen)[0]
    fmt = body["response_format"]
    assert fmt["type"] == "json_schema"
    assert fmt["json_schema"]["name"] == "BookInsightPayload"
    assert fmt["json_schema"]["strict"] is True
    assert fmt["json_schema"]["schema"] == BookInsightPayload.model_json_schema()
    system_content = body["messages"][0]["content"]
    assert "properties" not in system_content
    assert "$defs" not in system_content


@pytest.mark.asyncio
async def test_provider_that_cannot_do_json_schema_falls_back_to_json_object():
    seen: list[httpx.Request] = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req)
        if len(seen) == 1:
            return httpx.Response(
                400,
                json={"error": {"message": "response_format.type 'json_schema' is not supported"}},
            )
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
    out = await client.chat_structured(
        system="s", user="u", schema=BookInsightPayload, timeout_s=5.0
    )
    assert isinstance(out, BookInsightPayload)

    bodies = _bodies(seen)
    assert len(bodies) == 2
    assert bodies[0]["response_format"]["type"] == "json_schema"
    assert bodies[1]["response_format"] == {"type": "json_object"}
    assert "properties" in bodies[1]["messages"][0]["content"]


@pytest.mark.asyncio
async def test_the_downgrade_is_remembered_so_the_next_call_skips_the_probe():
    seen: list[httpx.Request] = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req)
        body = json.loads(req.content)
        if body["response_format"]["type"] == "json_schema":
            return httpx.Response(400, json={"error": "unknown parameter: response_format"})
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
    for _ in range(2):
        await client.chat_structured(system="s", user="u", schema=BookInsightPayload, timeout_s=5.0)

    modes = [b["response_format"]["type"] for b in _bodies(seen)]
    assert modes == ["json_schema", "json_object", "json_object"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("status", "payload"),
    [
        (401, {"error": {"message": "invalid api key"}}),
        (404, {"error": {"message": 'model "llama3.2:1b" not found, try pulling it first'}}),
        (429, {"error": "rate limited"}),
    ],
)
async def test_a_real_rejection_is_never_mistaken_for_a_missing_mode(status, payload):
    from quire_server.core.ai.client import ProviderRejected

    seen: list[httpx.Request] = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req)
        return httpx.Response(status, json=payload)

    client = AIClient(
        base_url="http://fake/v1",
        api_key=None,
        model="m",
        transport=httpx.MockTransport(handler),
    )
    with pytest.raises(ProviderRejected) as exc:
        await client.chat_structured(system="s", user="u", schema=BookInsightPayload, timeout_s=5.0)
    assert exc.value.status_code == status
    assert len(seen) == 1


@pytest.mark.asyncio
async def test_malformed_output_retries_in_place_without_downgrading():
    """A provider that accepts the mode but answers badly keeps the retry path."""
    seen: list[httpx.Request] = []
    bad = {"schema_version": 2, "intro": 42}
    good = {"schema_version": 2, "intro": "ok", "confidence": "low"}

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req)
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
    modes = [b["response_format"]["type"] for b in _bodies(seen)]
    assert modes == ["json_schema", "json_schema"]


@pytest.mark.asyncio
async def test_validation_retry_log_carries_no_provider_output(caplog):
    """The operator log gets facts, not the model's answer (issue #102)."""
    marker = "LEAKED-PROVIDER-TEXT"
    bad = {"schema_version": 2, "intro": {"note": marker}}
    good = {"schema_version": 2, "intro": "ok", "confidence": "low"}
    calls = {"n": 0}

    def handler(req: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        content = json.dumps(bad if calls["n"] == 1 else good)
        return httpx.Response(200, json=_make_chat_response(content))

    client = AIClient(
        base_url="http://fake/v1",
        api_key=None,
        model="m",
        transport=httpx.MockTransport(handler),
    )
    with caplog.at_level(logging.INFO, logger="quire_server.core.ai.client"):
        await client.chat_structured(system="s", user="u", schema=BookInsightPayload, timeout_s=5.0)

    logged = "\n".join(r.getMessage() for r in caplog.records)
    assert "ai.client.validation_retry" in logged
    assert "error_class=ValidationError" in logged
    assert "errors=1" in logged
    assert marker not in logged
    assert "input_value" not in logged
