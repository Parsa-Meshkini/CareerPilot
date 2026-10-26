from django.core.management.base import BaseCommand, CommandError

from agent.llm.factory import LLMProviderFactory
from agent.llm.prompts import PromptRegistry
from agent.llm.types import LLMProviderError, LLMRequest


class Command(BaseCommand):
    help = "Sends one tiny request to the configured LLM provider to verify the key and model."

    def handle(self, *args, **options):
        try:
            provider = LLMProviderFactory.create()
            prompt = PromptRegistry().get("system.ping")
            resp = provider.complete(LLMRequest(system=prompt.system, prompt=prompt.render({"word": "careerpilot"}),
                                                prompt_key=prompt.key))
        except LLMProviderError as exc:
            hint = ("temporary: the provider is busy, try again shortly" if exc.retryable
                    else "check LLM_API_KEY / LLM_MODEL in .env")
            raise CommandError(f"LLM check failed ({hint}): {str(exc)[:240]}")
        self.stdout.write(self.style.SUCCESS(f"{provider.name()} / {resp.model}: {resp.text.strip()[:200]} "
                                             f"({resp.total_tokens} tokens)"))
