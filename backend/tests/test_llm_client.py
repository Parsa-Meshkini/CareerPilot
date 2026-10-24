import httpx
import pytest

from agent.llm.client import LLMClient
from agent.llm.providers import GeminiProvider, MockLLMProvider, OpenAICompatibleProvider
from agent.llm.types import LLMBudgetExceeded, LLMProviderError, LLMRequest, LLMValidationError
from agent.llm.usage import UsageTracker
from agent.models import LLMUsageRecord


def test_structured_call_validates_and_records_usage(user):
    provider = MockLLMProvider()
    provider.script("system.ping", {"ok": True, "echo": "hi"})
    result = LLMClient(provider=provider).generate_structured(user, "system.ping", {"word": "hi"})
    assert result.data == {"ok": True, "echo": "hi"}
    assert "<schema>" in provider.calls[0].prompt
    assert LLMUsageRecord.objects.filter(user=user, success=True).count() == 1


def test_invalid_output_is_retried_then_succeeds(user):
    provider = MockLLMProvider()
    provider.scripts["system.ping"] = ["not json", {"ok": "yes"}, {"ok": True, "echo": "x"}]
    result = LLMClient(provider=provider).generate_structured(user, "system.ping", {"word": "x"})
    assert result.attempts == 3
    assert "previous answer was rejected" in provider.calls[-1].prompt


def test_gives_up_after_max_retries(user):
    provider = MockLLMProvider()
    provider.script("system.ping", "garbage")
    with pytest.raises(LLMValidationError):
        LLMClient(provider=provider).generate_structured(user, "system.ping", {"word": "x"})
    assert len(provider.calls) == 3


def test_non_retryable_provider_error_stops_immediately(user):
    provider = MockLLMProvider()
    provider.script("system.ping", LLMProviderError("bad key", retryable=False))
    with pytest.raises(LLMProviderError):
        LLMClient(provider=provider).generate_structured(user, "system.ping", {"word": "x"})
    assert len(provider.calls) == 1


def test_daily_budget_is_enforced(user):
    client = LLMClient(provider=MockLLMProvider(), tracker=UsageTracker(daily_budget=10))
    with pytest.raises(LLMBudgetExceeded):
        client.generate_structured(user, "system.ping", {"word": "x"})


def test_mock_builds_minimal_valid_object_without_script(user):
    result = LLMClient(provider=MockLLMProvider()).generate_structured(user, "system.ping", {"word": "x"})
    assert result.data == {"ok": False, "echo": ""}


def _transport(handler):
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_gemini_adapter_maps_request_and_response():
    seen = {}

    def handler(request):
        seen["url"], seen["key"] = str(request.url), request.headers["x-goog-api-key"]
        seen["body"] = request.read()
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": '{"a":1}'}]}}],
                                         "usageMetadata": {"promptTokenCount": 7, "candidatesTokenCount": 3}})

    resp = GeminiProvider("k-123", "gemini-test", http=_transport(handler)).complete(
        LLMRequest(system="s", prompt="p"))
    assert resp.text == '{"a":1}' and resp.total_tokens == 10
    assert "gemini-test:generateContent" in seen["url"] and "k-123" not in seen["url"]
    assert b"application/json" in seen["body"]


def test_openai_compatible_adapter_and_error_mapping():
    def ok(request):
        assert request.headers["authorization"] == "Bearer k"
        return httpx.Response(200, json={"model": "m", "choices": [{"message": {"content": "hi"}}],
                                         "usage": {"prompt_tokens": 2, "completion_tokens": 1}})

    p = OpenAICompatibleProvider("k", "m", "https://x.test/v1", http=_transport(ok))
    assert p.complete(LLMRequest(system="s", prompt="p")).text == "hi"

    p = OpenAICompatibleProvider("k", "m", "https://x.test/v1", http=_transport(lambda r: httpx.Response(401, text="no")))
    with pytest.raises(LLMProviderError) as err:
        p.complete(LLMRequest(system="s", prompt="p"))
    assert err.value.retryable is False


def test_missing_key_is_a_clear_error():
    with pytest.raises(LLMProviderError, match="LLM_API_KEY"):
        GeminiProvider("", "gemini-2.5-flash")
