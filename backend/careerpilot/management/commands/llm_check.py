from django.core.management.base import BaseCommand

from agent.llm.factory import LLMProviderFactory
from agent.llm.prompts import PromptRegistry
from agent.llm.types import LLMRequest


class Command(BaseCommand):
    help = "Sends one tiny request to the configured LLM provider to verify the key and model."

    def handle(self, *args, **options):
        provider = LLMProviderFactory.create()
        prompt = PromptRegistry().get("system.ping")
        resp = provider.complete(LLMRequest(system=prompt.system, prompt=prompt.render({"word": "careerpilot"}),
                                            prompt_key=prompt.key))
        self.stdout.write(self.style.SUCCESS(f"{provider.name()} / {resp.model}: {resp.text.strip()[:200]} "
                                             f"({resp.total_tokens} tokens)"))
