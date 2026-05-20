"""OpenAI-compatible chat-completions client with structured-output validation.

Works against any provider that speaks the OpenAI chat-completions JSON shape:
OpenAI itself, Ollama (post-0.4), vLLM, llama.cpp's `--api`, OpenRouter,
Anthropic via OpenAI-compat proxies, etc.

Strategy:
1. Send chat completion with `response_format = {"type": "json_object"}`. We
   don't depend on `json_schema` mode because Ollama/llama.cpp don't all
   support it; we instead inline the schema in the system prompt.
2. Parse the assistant message as JSON, then validate against the Pydantic
   schema.
3. On ValidationError, retry once with the validation error appended to the
   user message.
4. On second failure or non-JSON output, raise ProviderParseError.
"""

from __future__ import annotations

import json
import logging
from typing import TypeVar

import httpx
from pydantic import BaseModel, ValidationError

logger = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)


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

    async def chat_structured(
        self,
        *,
        system: str,
        user: str,
        schema: type[T],
        timeout_s: float,
    ) -> T:
        schema_text = json.dumps(schema.model_json_schema(), indent=2)
        full_system = (
            f"{system}\n\n"
            "You MUST respond with a single JSON object that conforms exactly to "
            "the following JSON Schema. No prose, no markdown, no code fences.\n\n"
            f"```\n{schema_text}\n```"
        )
        messages = [
            {"role": "system", "content": full_system},
            {"role": "user", "content": user},
        ]

        async with self._build_client(timeout_s) as http:
            response_text = await self._do_call(http, messages)
            try:
                return self._parse(response_text, schema)
            except (json.JSONDecodeError, ValidationError) as first_err:
                logger.info("ai.client.validation_retry err=%s", first_err)
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
                retry_text = await self._do_call(http, retry_messages)
                try:
                    return self._parse(retry_text, schema)
                except (json.JSONDecodeError, ValidationError) as second_err:
                    raise ProviderParseError(
                        f"Validation failed twice; first: {first_err}; second: {second_err}"
                    ) from first_err

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

    async def _do_call(self, http: httpx.AsyncClient, messages: list[dict]) -> str:
        body = {
            "model": self._model,
            "messages": messages,
            "response_format": {"type": "json_object"},
            "temperature": 0.2,
            "stream": False,
        }
        try:
            r = await http.post(f"{self._base_url}/chat/completions", json=body)
        except httpx.TimeoutException as e:
            raise ProviderTimeout(str(e)) from e
        except httpx.HTTPError as e:
            raise ProviderUnreachable(str(e)) from e

        if r.status_code >= 500:
            raise ProviderUnreachable(f"provider {r.status_code}: {r.text[:200]}")
        if r.status_code >= 400:
            raise ProviderRejected(r.status_code, f"provider {r.status_code}: {r.text[:200]}")

        data = r.json()
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
