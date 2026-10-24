import uuid

from django.conf import settings
from django.db import models
from django.utils import timezone

from .states import ACTIONS, STATES


class Application(models.Model):
    """State-pattern context. The state object is rebuilt from state_name on load."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="applications")
    job = models.ForeignKey("jobs.JobPosting", on_delete=models.CASCADE, related_name="applications")
    state_name = models.CharField(max_length=20, default="SAVED", db_index=True)
    version = models.IntegerField(default=1)
    applied_at = models.DateTimeField(null=True, blank=True)
    notes = models.TextField(blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-updated_at"]
        constraints = [models.UniqueConstraint(fields=["user", "job"], name="one_application_per_job")]

    @property
    def state(self):
        return STATES[self.state_name]()

    def set_state(self, state):
        self.state_name = state.name
        if state.name == "APPLIED" and self.applied_at is None:
            self.applied_at = timezone.now()

    def allowed_actions(self):
        return self.state.allowed_actions()

    # lifecycle actions delegate to the current state
    def mark_prepared(self):
        self.state.mark_prepared(self)

    def submit(self):
        self.state.submit(self)

    def schedule_interview(self, when=None):
        self.state.schedule_interview(self, when)

    def receive_offer(self):
        self.state.receive_offer(self)

    def reject(self):
        self.state.reject(self)

    def accept(self):
        self.state.accept(self)

    def decline(self):
        self.state.decline(self)

    def withdraw(self):
        self.state.withdraw(self)

    def perform(self, action, **kwargs):
        if action not in ACTIONS:
            raise ValueError(f"unknown action {action}")
        getattr(self, action)(**kwargs)


class StatusChange(models.Model):
    USER, EMAIL_EVENT, AGENT = "USER", "EMAIL_EVENT", "AGENT"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
    application = models.ForeignKey(Application, on_delete=models.CASCADE, related_name="changes")
    from_state = models.CharField(max_length=20)
    to_state = models.CharField(max_length=20)
    action = models.CharField(max_length=30)
    source = models.CharField(max_length=20, default=USER)
    reason = models.CharField(max_length=300, blank=True)
    evidence_id = models.UUIDField(null=True, blank=True)
    previous_applied_at = models.DateTimeField(null=True, blank=True)
    reverted = models.BooleanField(default=False)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created_at"]

    def revert(self):
        self.reverted = True


class Interview(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
    application = models.ForeignKey(Application, on_delete=models.CASCADE, related_name="interviews")
    round = models.IntegerField(default=1)
    scheduled_at = models.DateTimeField(null=True, blank=True)
    format = models.CharField(max_length=60, blank=True)
    details_text = models.TextField(blank=True)
    source_event_id = models.UUIDField(null=True, blank=True, unique=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["round"]
