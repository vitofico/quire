"""OpenAI-compatible chat-completions client with structured-output validation.

Works against any provider that speaks the OpenAI chat-completions JSON shape:
OpenAI itself, Ollama (post-0.4), vLLM, llama.cpp's `--api`, OpenRouter,
Anthropic via OpenAI-compat proxies, etc.

Strategy:
1. Send the chat completion with `response_format = {"type": "json_schema", ...}`
   so the provider itself constrains the answer to the Pydantic schema. The
   schema stays out of the prompt on purpose: a small model handed a schema as
   text tends to hand the schema straight back instead of filling it in
   (issue #102).
2. If the provider turns that request down in a way that says it does not know
   the mode, fall back once to `{"type": "json_object"}` with the schema inlined
   in the system prompt, and keep that shape for the rest of this client's life.
   A rejection that reads like a real one (bad key, unknown model, rate limit)
   is never downgraded: it raises ProviderRejected as before.
3. Parse the assistant message as JSON, then validate against the Pydantic
   schema.
4. On ValidationError, retry once with the validation error appended to the
   user message.
5. On second failure or non-JSON output, raise ProviderParseError.
"""

from __future__ import annotations

import json
import logging
from typing import TypeVar

import httpx
from pydantic import BaseModel, ValidationError

logger = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)

# Issue #102: a provider that does not know `json_schema` mode says so with a
# 4xx. These are the codes seen for "I don't understand this parameter": 400
# from OpenAI and most compat shims, 404 from older Ollama routes, 422 from
# servers that validate the body with FastAPI. 401, 403 and 429 are absent on
# purpose; those are answers about the caller, not about the request shape.
_MODE_REJECTION_STATUSES = frozenset({400, 404, 422})

# On top of the status, the body has to name the parameter or complain about an
# unknown one. "model not found" and "invalid api key" match nothing here and
# stay ProviderRejected. A false positive costs one extra request and nothing
# else: the fallback call re-sends in the old shape, and the genuine rejection
# comes back from that one.
_MODE_REJECTION_MARKERS = (
    "response_format",
    "response format",
    "json_schema",
    "json schema",
    "unsupported parameter",
    "unsupported_parameter",
    "unknown parameter",
    "unknown_parameter",
    "unrecognized parameter",
    "unrecognized_keys",
    "invalid parameter",
    "invalid_parameter",
    "extra inputs are not permitted",
    "extra fields not permitted",
)


def _error_count(err: json.JSONDecodeError | ValidationError) -> int:
    """How many things were wrong, without saying what they were."""
    return err.error_count() if isinstance(err, ValidationError) else 1


_MAX_ERROR_KINDS = 5


def _schema_field_names(schema: type[BaseModel]) -> set[str]:
    names: set[str] = set()
    pending: list = [schema.model_json_schema()]
    while pending:
        node = pending.pop()
        if isinstance(node, dict):
            if isinstance(node.get("properties"), dict):
                names.update(node["properties"])
            pending.extend(node.values())
        elif isinstance(node, list):
            pending.extend(node)
    return names


def _error_kinds(err: json.JSONDecodeError | ValidationError, schema: type[BaseModel]) -> str:
    """What was wrong and where, never what the model wrote (issue #102).

    Each entry is an error type at a location: ``string_too_long@analysis``,
    ``missing@author``. A location keeps only the schema's own field names and
    list indexes; any other part (an extra key the model invented, a key of a
    free-form dict) reads ``*``, because it is model text. For invalid JSON the
    parser's reason is kept, ``json_invalid@root[EOF while parsing a string at
    line 1 column 812]``: it quotes nothing and is what tells a cut-off answer
    apart. The error message and the input value are never used.
    """
    if isinstance(err, json.JSONDecodeError):
        return f"json_invalid@root[{err.msg}: line {err.lineno} column {err.colno}]"
    fields = _schema_field_names(schema)
    kinds = []
    for e in err.errors(include_url=False, include_input=False):
        where = ".".join(str(p) if isinstance(p, int) or p in fields else "*" for p in e["loc"])
        kind = f"{e['type']}@{where or 'root'}"
        reason = (e.get("ctx") or {}).get("error") if e["type"] == "json_invalid" else None
        if reason:
            kind += f"[{reason}]"
        kinds.append(kind)
    if len(kinds) > _MAX_ERROR_KINDS:
        kinds[_MAX_ERROR_KINDS:] = [f"+{len(kinds) - _MAX_ERROR_KINDS} more"]
    return ",".join(kinds)


class ProviderError(Exception):
    """Base for AI provider failures."""


class ProviderUnreachable(ProviderError):
    pass


class ProviderTimeout(ProviderError):
    pass


class ProviderParseError(ProviderError):
    pass


class ProviderRejected(ProviderError):
    """Provider rejected the request (4xx). Carries status_code for caller logic."""

    def __init__(self, status_code: int, message: str) -> None:
        self.status_code = status_code
        super().__init__(message)


class _ResponseFormatUnsupported(ProviderRejected):
    """Internal: the provider refused `json_schema` mode (issue #102).

    `chat_structured` catches it, downgrades the client and calls again. It
    subclasses ProviderRejected so that if it ever escapes that narrow window
    it still reads as the rejection it is, with the right status code.
    """


def _looks_like_mode_rejection(status_code: int, body: str) -> bool:
    """Does this 4xx mean "I don't know `json_schema`" or a genuine "no"?

    Conservative on purpose: the answer has to carry one of the
    parameter-complaint status codes AND name the parameter. Everything else,
    including every credential and unknown-model failure, stays a real
    rejection.
    """
    if status_code not in _MODE_REJECTION_STATUSES:
        return False
    haystack = body.lower()
    return any(marker in haystack for marker in _MODE_REJECTION_MARKERS)


class AIClient:
    def __init__(
        self,
        *,
        base_url: str,
        api_key: str | None,
        model: str,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._model = model
        self._transport = transport  # tests inject MockTransport; prod is None
        self._user_agent = "quire-server"
        # Issue #102: goes False the first time a provider turns down
        # `json_schema` mode, and stays there. One AIClient serves the whole
        # process (see main.create_app), so the probe is paid once per deploy,
        # not once per insight.
        self._native_schema = True

    async def chat_structured(
        self,
        *,
        system: str,
        user: str,
        schema: type[T],
        timeout_s: float,
    ) -> T:
        async with self._build_client(timeout_s) as http:
            messages = self._compose_messages(system, user, schema)
            try:
                response_text = await self._do_call(http, messages, schema)
            except _ResponseFormatUnsupported:
                # The provider cannot enforce the schema itself, so go back to
                # asking for any JSON object and paste the schema in the prompt.
                self._native_schema = False
                messages = self._compose_messages(system, user, schema)
                response_text = await self._do_call(http, messages, schema)
            try:
                return self._parse(response_text, schema)
            except (json.JSONDecodeError, ValidationError) as first_err:
                # Facts only. A ValidationError stringifies with
                # `input_value=...`, which is a slice of the provider's answer,
                # and operator logs are no place for it (issue #102). The retry
                # message below still carries the full error, on purpose: the
                # model needs to see what it got wrong.
                first_kinds = _error_kinds(first_err, schema)
                logger.info(
                    "ai.client.validation_retry error_class=%s errors=%d chars=%d "
                    "native_schema=%s kinds=%s",
                    type(first_err).__name__,
                    _error_count(first_err),
                    len(response_text),
                    self._native_schema,
                    first_kinds,
                )
                retry_messages = list(messages)
                retry_messages.append({"role": "assistant", "content": response_text})
                retry_messages.append(
                    {
                        "role": "user",
                        "content": (
                            "The previous response failed validation against the schema. "
                            f"Validation error: {first_err}. Reply again with a valid JSON "
                            "object that conforms exactly to the schema. Output only JSON."
                        ),
                    }
                )
                retry_text = await self._do_call(http, retry_messages, schema)
                try:
                    return self._parse(retry_text, schema)
                except (json.JSONDecodeError, ValidationError) as second_err:
                    second_kinds = _error_kinds(second_err, schema)
                    logger.info(
                        "ai.client.validation_failed error_class=%s errors=%d chars=%d "
                        "native_schema=%s kinds=%s",
                        type(second_err).__name__,
                        _error_count(second_err),
                        len(retry_text),
                        self._native_schema,
                        second_kinds,
                    )
                    # `from None`: a chained ValidationError would print its
                    # input_value in any traceback of this error.
                    raise ProviderParseError(
                        f"Validation failed twice; first: {first_kinds} "
                        f"({len(response_text)} chars); second: {second_kinds} "
                        f"({len(retry_text)} chars)"
                    ) from None

    def _compose_messages(self, system: str, user: str, schema: type[T]) -> list[dict]:
        if self._native_schema:
            # The schema rides in `response_format` (see `_response_format`), so
            # the prompt only has to rule out the wrapping that json_schema mode
            # does not already rule out.
            full_system = (
                f"{system}\n\n"
                "Answer with a single JSON object and nothing else: no prose, "
                "no markdown, no code fences."
            )
        else:
            schema_text = json.dumps(schema.model_json_schema(), indent=2)
            full_system = (
                f"{system}\n\n"
                "You MUST respond with a single JSON object that conforms exactly to "
                "the following JSON Schema. No prose, no markdown, no code fences.\n\n"
                f"```\n{schema_text}\n```"
            )
        return [
            {"role": "system", "content": full_system},
            {"role": "user", "content": user},
        ]

    def _response_format(self, schema: type[T]) -> dict:
        if not self._native_schema:
            return {"type": "json_object"}
        # `strict` is what makes a provider constrain the tokens rather than
        # treat the schema as a hint. A provider that dislikes the schema under
        # strict mode answers 4xx naming `response_format`, which lands on the
        # fallback above, so the feature degrades instead of breaking.
        return {
            "type": "json_schema",
            "json_schema": {
                "name": schema.__name__,
                "schema": schema.model_json_schema(),
                "strict": True,
            },
        }

    def _build_client(self, timeout_s: float) -> httpx.AsyncClient:
        headers = {"User-Agent": self._user_agent, "Content-Type": "application/json"}
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        kwargs: dict = {
            "headers": headers,
            "timeout": httpx.Timeout(timeout_s, connect=min(timeout_s, 10.0)),
        }
        if self._transport is not None:
            kwargs["transport"] = self._transport
        return httpx.AsyncClient(**kwargs)

    async def _do_call(self, http: httpx.AsyncClient, messages: list[dict], schema: type[T]) -> str:
        body = {
            "model": self._model,
            "messages": messages,
            "response_format": self._response_format(schema),
            "temperature": 0.2,
            "stream": False,
        }
        try:
            r = await http.post(f"{self._base_url}/chat/completions", json=body)
        except httpx.ConnectTimeout as e:
            # The connect phase is capped at 10 s (see _build_client). Failing
            # there means the host never answered the handshake (firewall,
            # wrong port, provider down), not that the model is slow.
            # Issue #102.
            raise ProviderUnreachable(str(e)) from e
        except httpx.TimeoutException as e:
            raise ProviderTimeout(str(e)) from e
        except httpx.HTTPError as e:
            raise ProviderUnreachable(str(e)) from e

        if r.status_code >= 500:
            raise ProviderUnreachable(f"provider {r.status_code}: {r.text[:200]}")
        if r.status_code >= 400:
            message = f"provider {r.status_code}: {r.text[:200]}"
            if self._native_schema and _looks_like_mode_rejection(r.status_code, r.text):
                raise _ResponseFormatUnsupported(r.status_code, message)
            raise ProviderRejected(r.status_code, message)

        # Issue #102: a reverse proxy or a plain web server can answer 200 with a
        # page instead of the provider's JSON. Raising the parse error as a
        # ProviderError keeps that failure on the documented 502
        # provider_invalid_output path instead of escaping as a raw 500. The
        # body itself never reaches the message: a retry prompt cannot fix a
        # proxy, so this deliberately bypasses the malformed-output retry above.
        try:
            data = r.json()
        except ValueError as e:
            raise ProviderParseError("provider response body was not JSON") from e
        if not isinstance(data, dict):
            raise ProviderParseError("provider response body was not a JSON object")
        choices = data.get("choices") or []
        if not choices:
            raise ProviderParseError("no choices in provider response")
        message = choices[0].get("message") or {}
        content = message.get("content")
        if not isinstance(content, str):
            raise ProviderParseError("provider returned non-string message content")
        return content

    @staticmethod
    def _parse(text: str, schema: type[T]) -> T:
        return schema.model_validate_json(text.strip())
