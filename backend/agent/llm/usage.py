from datetime import timedelta

from django.conf import settings
from django.db.models import F, Sum
from django.utils import timezone

from agent.models import LLMUsageRecord

from .types import LLMBudgetExceeded


class UsageTracker:
    """Enforces the per-user daily token budget and records every call."""

    def __init__(self, daily_budget=None):
        self.daily_budget = daily_budget or settings.LLM_USER_DAILY_TOKEN_BUDGET

    def used_today(self, user):
        since = timezone.now() - timedelta(days=1)
        agg = LLMUsageRecord.objects.filter(user=user, created_at__gte=since).aggregate(
            total=Sum(F("input_tokens") + F("output_tokens")))
        return agg["total"] or 0

    def check_budget(self, user, estimated_tokens=0):
        used = self.used_today(user)
        if used + estimated_tokens > self.daily_budget:
            raise LLMBudgetExceeded(f"daily LLM budget reached ({used}/{self.daily_budget} tokens)")
        return self.daily_budget - used

    def record(self, user, prompt, provider, response=None, success=True):
        return LLMUsageRecord.objects.create(
            user=user, prompt_key=prompt.key, prompt_version=prompt.version, provider=provider.name(),
            model=response.model if response else "", success=success,
            input_tokens=response.input_tokens if response else 0,
            output_tokens=response.output_tokens if response else 0,
        )
