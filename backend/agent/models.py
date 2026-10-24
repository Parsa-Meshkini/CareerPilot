import uuid

from django.conf import settings
from django.db import models


class LLMUsageRecord(models.Model):
    """One LLM call, recorded by UsageTracker for per-user budgets and cost reporting."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="llm_usage")
    prompt_key = models.CharField(max_length=80)
    prompt_version = models.IntegerField(default=1)
    provider = models.CharField(max_length=40)
    model = models.CharField(max_length=80)
    input_tokens = models.IntegerField(default=0)
    output_tokens = models.IntegerField(default=0)
    success = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        indexes = [models.Index(fields=["user", "created_at"])]

    @property
    def total_tokens(self):
        return self.input_tokens + self.output_tokens
