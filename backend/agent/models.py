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


class AgentMemoryEntry(models.Model):
    EPISODIC, PREFERENCE, STRATEGY, FEEDBACK = "EPISODIC", "PREFERENCE", "STRATEGY", "FEEDBACK"
    KINDS = [(k, k.title()) for k in (EPISODIC, PREFERENCE, STRATEGY, FEEDBACK)]

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="memories")
    kind = models.CharField(max_length=12, choices=KINDS)
    content = models.TextField()
    related_object_id = models.UUIDField(null=True, blank=True)
    importance = models.FloatField(default=0.5)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created_at"]
        indexes = [models.Index(fields=["user", "kind", "created_at"])]


class AgentRun(models.Model):
    PLANNING, RUNNING, AWAITING_APPROVAL, AWAITING_INPUT = "PLANNING", "RUNNING", "AWAITING_APPROVAL", "AWAITING_INPUT"
    COMPLETED, FAILED, ABORTED = "COMPLETED", "FAILED", "ABORTED"
    GOAL_TYPES = ("TRIAGE_BATCH", "PREPARE_APPLICATION", "PREPARE_INTERVIEW", "FREE_FORM")

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="agent_runs")
    goal_type = models.CharField(max_length=30, default="FREE_FORM")
    goal_text = models.TextField()
    status = models.CharField(max_length=20, default=PLANNING)
    plan = models.JSONField(default=list)          # [{tool, args, intent, status}]
    plan_source = models.CharField(max_length=10, blank=True)   # LLM | PLAYBOOK
    plan_history = models.JSONField(default=list)  # earlier plans kept for the trace
    current_step = models.IntegerField(default=0)
    step_count = models.IntegerField(default=0)
    replan_count = models.IntegerField(default=0)
    token_budget = models.IntegerField(default=40000)
    tokens_used = models.IntegerField(default=0)
    context = models.JSONField(default=dict)       # working memory: focus job/application, batch ids...
    decisions = models.JSONField(default=list)     # reasoner decisions with rationale
    pending = models.JSONField(default=dict, blank=True)  # step awaiting approval or a question for the user
    summary = models.TextField(blank=True)
    error = models.TextField(blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    finished_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        ordering = ["-created_at"]

    def within_budget(self, estimate=0):
        return self.tokens_used + estimate <= self.token_budget

    def add_tokens(self, n):
        self.tokens_used += n

    def record_decision(self, step_index, action, rationale, source="rules"):
        self.decisions.append({"step": step_index, "action": action, "rationale": rationale, "source": source})

    def await_approval(self, step_index, description):
        self.status = self.AWAITING_APPROVAL
        self.pending = {"type": "approval", "step": step_index, "description": description}

    def ask_user(self, step_index, question, options):
        self.status = self.AWAITING_INPUT
        self.pending = {"type": "question", "step": step_index, "question": question, "options": options}

    def complete(self, summary):
        from django.utils import timezone

        self.status, self.summary, self.pending, self.finished_at = self.COMPLETED, summary, {}, timezone.now()

    def fail(self, reason, status=None):
        from django.utils import timezone

        self.status, self.error, self.pending, self.finished_at = status or self.FAILED, reason, {}, timezone.now()

    @property
    def is_active(self):
        return self.status in (self.PLANNING, self.RUNNING, self.AWAITING_APPROVAL, self.AWAITING_INPUT)


class AgentActionRecord(models.Model):
    EXECUTED, FAILED, REJECTED, UNDONE = "EXECUTED", "FAILED", "REJECTED", "UNDONE"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
    run = models.ForeignKey(AgentRun, null=True, blank=True, on_delete=models.CASCADE, related_name="actions")
    step_index = models.IntegerField(default=0)
    command_name = models.CharField(max_length=60)
    arguments = models.JSONField(default=dict)
    description = models.TextField(blank=True)
    status = models.CharField(max_length=10, default=EXECUTED)
    observation_summary = models.TextField(blank=True)
    observation = models.JSONField(default=dict)
    reversible = models.BooleanField(default=False)
    undo_data = models.JSONField(default=dict)
    executed_at = models.DateTimeField(auto_now_add=True)
    undone_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        ordering = ["executed_at"]

    def mark_undone(self):
        from django.utils import timezone

        self.status, self.undone_at = self.UNDONE, timezone.now()
