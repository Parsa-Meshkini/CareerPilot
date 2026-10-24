from dataclasses import dataclass, field


@dataclass
class LLMRequest:
    system: str
    prompt: str
    json_mode: bool = True
    temperature: float = 0.2
    max_output_tokens: int = 2048
    prompt_key: str = ""          # lets MockLLMProvider and logs know which prompt this is


@dataclass
class LLMResponse:
    text: str
    model: str
    input_tokens: int = 0
    output_tokens: int = 0

    @property
    def total_tokens(self):
        return self.input_tokens + self.output_tokens


@dataclass
class LLMResult:
    """What LLMClient hands back to callers."""

    data: object
    text: str
    model: str
    prompt_key: str
    prompt_version: int
    tokens: int = 0
    attempts: int = 1
    meta: dict = field(default_factory=dict)


class LLMError(Exception):
    """Any failure inside the LLM subsystem. Callers catch this to run their fallback."""


class LLMProviderError(LLMError):
    def __init__(self, message, retryable=True):
        super().__init__(message)
        self.retryable = retryable


class LLMValidationError(LLMError):
    pass


class LLMBudgetExceeded(LLMError):
    pass
