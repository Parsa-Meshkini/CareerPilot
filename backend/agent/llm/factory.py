from django.conf import settings

from .providers import GeminiProvider, MockLLMProvider, OpenAICompatibleProvider
from .types import LLMProviderError


class LLMProviderFactory:
    """Simple factory: picks the provider adapter from configuration (not GoF Factory Method)."""

    @staticmethod
    def create(provider=None, api_key=None, model=None, base_url=None):
        provider = (provider or settings.LLM_PROVIDER or "gemini").lower()
        api_key = api_key if api_key is not None else settings.LLM_API_KEY
        model = model if model is not None else settings.LLM_MODEL
        base_url = base_url if base_url is not None else settings.LLM_BASE_URL
        timeout = settings.LLM_TIMEOUT_SECONDS
        if provider == "gemini":
            return GeminiProvider(api_key, model, timeout=timeout)
        if provider in ("openai", "openai_compatible", "groq", "openrouter"):
            return OpenAICompatibleProvider(api_key, model, base_url, timeout=timeout)
        if provider == "mock":
            return MockLLMProvider()
        raise LLMProviderError(f"unknown LLM_PROVIDER '{provider}'", retryable=False)
