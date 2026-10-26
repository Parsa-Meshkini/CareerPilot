import pytest

from agent.llm.client import LLMClient, set_llm_client
from agent.llm.providers import MockLLMProvider


@pytest.fixture(autouse=True)
def mock_llm():
    """Every test runs against MockLLMProvider, so the suite never calls a real LLM or needs a key."""
    provider = MockLLMProvider()
    set_llm_client(LLMClient(provider=provider, sleep=lambda seconds: None))
    yield provider
    set_llm_client(None)
