"""LLMClient: the single entry point to the LLM subsystem (P7 Facade).

Every caller (resume parser, job analysis, matching, planner, reasoner,
generators, classifiers, interview services, strategy advisor) uses only
generate_structured()/generate_text(). Behind it: prompt lookup and rendering,
budget check, provider call through the LLMProvider adapter, JSON-schema
validation with retries, and usage accounting.
"""
import logging

from .factory import LLMProviderFactory
from .prompts import PromptRegistry
from .types import LLMBudgetExceeded, LLMProviderError, LLMRequest, LLMResult, LLMValidationError
from .usage import UsageTracker
from .validator import ResponseValidator

log = logging.getLogger(__name__)


class LLMClient:
    MAX_RETRIES = 2

    def __init__(self, provider=None, registry=None, tracker=None, validator=None):
        self._provider = provider
        self.registry = registry or PromptRegistry()
        self.tracker = tracker or UsageTracker()
        self.validator = validator or ResponseValidator()

    @property
    def provider(self):
        if self._provider is None:  # created lazily so a missing key only matters when the LLM is used
            self._provider = LLMProviderFactory.create()
        return self._provider

    def generate_structured(self, user, prompt_key, variables, run=None, temperature=0.2):
        return self._generate(user, prompt_key, variables, run, temperature, json_mode=True)

    def generate_text(self, user, prompt_key, variables, run=None, temperature=0.5):
        return self._generate(user, prompt_key, variables, run, temperature, json_mode=False)

    def _generate(self, user, prompt_key, variables, run, temperature, json_mode):
        prompt = self.registry.get(prompt_key)
        rendered = prompt.render(variables)
        estimate = len(prompt.system + rendered) // 4 + 512
        self.tracker.check_budget(user, estimate)
        if run is not None and not run.within_budget(estimate):
            raise LLMBudgetExceeded("agent run token budget reached")

        request = LLMRequest(system=prompt.system, prompt=rendered, json_mode=json_mode,
                             temperature=temperature, prompt_key=prompt_key)
        tokens, last_error = 0, None
        for attempt in range(1, self.MAX_RETRIES + 2):
            try:
                response = self.provider.complete(request)
            except LLMProviderError as exc:
                self.tracker.record(user, prompt, self.provider, None, success=False)
                last_error = exc
                if not exc.retryable:
                    break
                continue
            tokens += response.total_tokens
            if run is not None:
                run.add_tokens(response.total_tokens)
            try:
                data = self.validator.validate(response.text, prompt.schema) if json_mode else response.text.strip()
            except LLMValidationError as exc:
                self.tracker.record(user, prompt, self.provider, response, success=False)
                last_error = exc
                request.prompt = rendered + f"\n\nYour previous answer was rejected ({exc}). Return valid JSON only."
                continue
            self.tracker.record(user, prompt, self.provider, response, success=True)
            return LLMResult(data=data, text=response.text, model=response.model, prompt_key=prompt.key,
                             prompt_version=prompt.version, tokens=tokens, attempts=attempt)
        log.warning("LLM call %s failed: %s", prompt_key, last_error)
        raise last_error


_default_client = None


def get_llm_client():
    global _default_client
    if _default_client is None:
        _default_client = LLMClient()
    return _default_client


def set_llm_client(client):
    """Used by tests to install a client backed by MockLLMProvider."""
    global _default_client
    _default_client = client
