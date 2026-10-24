"""LLMProvider target interface and its adapters (P1 Adapter).

Each vendor API has its own request shape, auth header, JSON-mode switch and
usage fields. Every adapter converts LLMRequest -> vendor call -> LLMResponse,
so nothing outside this module knows which vendor is in use.
"""
import json
import re
from abc import ABC, abstractmethod

import httpx

from .types import LLMProviderError, LLMResponse


class LLMProvider(ABC):
    @abstractmethod
    def name(self) -> str: ...

    @abstractmethod
    def complete(self, request) -> LLMResponse: ...


def _raise_for_status(resp, vendor):
    if resp.status_code < 400:
        return
    retryable = resp.status_code in (408, 429) or resp.status_code >= 500
    # never echo request headers (they carry the key); body excerpt only
    raise LLMProviderError(f"{vendor} HTTP {resp.status_code}: {resp.text[:300]}", retryable=retryable)


class GeminiProvider(LLMProvider):
    """Adapter for the Google Gemini generateContent REST API."""

    BASE = "https://generativelanguage.googleapis.com/v1beta/models"

    def __init__(self, api_key, model="gemini-2.5-flash", timeout=60, http=None):
        if not api_key:
            raise LLMProviderError("LLM_API_KEY is not set for the Gemini provider", retryable=False)
        self.api_key, self.model = api_key, model or "gemini-2.5-flash"
        self.http = http or httpx.Client(timeout=timeout)

    def name(self):
        return "gemini"

    def complete(self, request):
        body = {
            "systemInstruction": {"parts": [{"text": request.system}]},
            "contents": [{"role": "user", "parts": [{"text": request.prompt}]}],
            "generationConfig": {"temperature": request.temperature, "maxOutputTokens": request.max_output_tokens},
        }
        if request.json_mode:
            body["generationConfig"]["responseMimeType"] = "application/json"
        try:
            resp = self.http.post(f"{self.BASE}/{self.model}:generateContent",
                                  headers={"x-goog-api-key": self.api_key}, json=body)
        except httpx.HTTPError as exc:
            raise LLMProviderError(f"Gemini network error: {exc}") from exc
        _raise_for_status(resp, "Gemini")
        payload = resp.json()
        try:
            parts = payload["candidates"][0]["content"]["parts"]
            text = "".join(p.get("text", "") for p in parts)
        except (KeyError, IndexError) as exc:
            raise LLMProviderError(f"Gemini returned no content: {str(payload)[:200]}") from exc
        usage = payload.get("usageMetadata", {})
        return LLMResponse(text=text, model=self.model, input_tokens=usage.get("promptTokenCount", 0),
                           output_tokens=usage.get("candidatesTokenCount", 0))


class OpenAICompatibleProvider(LLMProvider):
    """Adapter for any OpenAI-style /chat/completions endpoint (OpenAI, Groq, OpenRouter...)."""

    def __init__(self, api_key, model, base_url="https://api.openai.com/v1", timeout=60, http=None):
        if not api_key:
            raise LLMProviderError("LLM_API_KEY is not set for the OpenAI-compatible provider", retryable=False)
        if not model:
            raise LLMProviderError("LLM_MODEL must be set for the OpenAI-compatible provider", retryable=False)
        self.api_key, self.model = api_key, model
        self.base_url = (base_url or "https://api.openai.com/v1").rstrip("/")
        self.http = http or httpx.Client(timeout=timeout)

    def name(self):
        return "openai_compatible"

    def complete(self, request):
        body = {
            "model": self.model,
            "temperature": request.temperature,
            "max_tokens": request.max_output_tokens,
            "messages": [{"role": "system", "content": request.system}, {"role": "user", "content": request.prompt}],
        }
        if request.json_mode:
            body["response_format"] = {"type": "json_object"}
        try:
            resp = self.http.post(f"{self.base_url}/chat/completions",
                                  headers={"Authorization": f"Bearer {self.api_key}"}, json=body)
        except httpx.HTTPError as exc:
            raise LLMProviderError(f"LLM network error: {exc}") from exc
        _raise_for_status(resp, "LLM endpoint")
        payload = resp.json()
        try:
            text = payload["choices"][0]["message"]["content"] or ""
        except (KeyError, IndexError) as exc:
            raise LLMProviderError(f"LLM endpoint returned no content: {str(payload)[:200]}") from exc
        usage = payload.get("usage", {})
        return LLMResponse(text=text, model=payload.get("model", self.model),
                           input_tokens=usage.get("prompt_tokens", 0), output_tokens=usage.get("completion_tokens", 0))


class MockLLMProvider(LLMProvider):
    """Offline provider for the test suite and CI (no network, no key).

    Responses come from scripts registered per prompt key. With no script it
    builds the smallest object that satisfies the request's JSON schema, so the
    rest of the pipeline can still be exercised. It is not used for demos.
    """

    def __init__(self):
        self.scripts = {}
        self.calls = []

    def name(self):
        return "mock"

    def script(self, prompt_key, *responses):
        """Queue responses (dict/list -> JSON, str as-is, Exception -> raised) for a prompt key."""
        self.scripts.setdefault(prompt_key, []).extend(responses)

    def complete(self, request):
        self.calls.append(request)
        queue = self.scripts.get(request.prompt_key)
        if queue:
            item = queue.pop(0) if len(queue) > 1 else queue[0]
            if isinstance(item, Exception):
                raise item
            text = item if isinstance(item, str) else json.dumps(item)
        else:
            text = json.dumps(_minimal_from_schema(_schema_in_prompt(request.prompt))) if request.json_mode else "OK"
        return LLMResponse(text=text, model="mock-1", input_tokens=len(request.prompt) // 4,
                           output_tokens=len(text) // 4)


def _schema_in_prompt(prompt):
    m = re.search(r"<schema>(.*?)</schema>", prompt, re.S)
    if not m:
        return {"type": "object"}
    try:
        return json.loads(m.group(1))
    except ValueError:
        return {"type": "object"}


def _minimal_from_schema(schema):
    t = schema.get("type")
    if "enum" in schema:
        return schema["enum"][0]
    if t == "object":
        props = schema.get("properties", {})
        return {k: _minimal_from_schema(props[k]) for k in schema.get("required", []) if k in props}
    if t == "array":
        return []
    if t in ("integer", "number"):
        return schema.get("minimum", 0)
    if t == "boolean":
        return False
    return ""
