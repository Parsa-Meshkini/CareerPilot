import uuid

from django.conf import settings
from django.db import models

PROVIDERS = [("GMAIL", "Gmail"), ("EML", "Uploaded .eml files"), ("MOCK", "Mock inbox")]

APPLICATION_RECEIVED, INTERVIEW_INVITATION, INTERVIEW_SCHEDULED = (
    "APPLICATION_RECEIVED", "INTERVIEW_INVITATION", "INTERVIEW_SCHEDULED")
INFO_REQUESTED, REJECTION, OFFER, FOLLOW_UP, NOT_RELEVANT = (
    "INFO_REQUESTED", "REJECTION", "OFFER", "FOLLOW_UP", "NOT_RELEVANT")
EVENT_TYPES = (APPLICATION_RECEIVED, INTERVIEW_INVITATION, INTERVIEW_SCHEDULED, INFO_REQUESTED, REJECTION, OFFER,
               FOLLOW_UP, NOT_RELEVANT)


class EmailAccount(models.Model):
    ACTIVE, REAUTH_REQUIRED, DISCONNECTED = "ACTIVE", "REAUTH_REQUIRED", "DISCONNECTED"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="email_accounts")
    provider = models.CharField(max_length=10, choices=PROVIDERS)
    address = models.CharField(max_length=254, blank=True)
    status = models.CharField(max_length=20, default=ACTIVE)
    encrypted_token = models.BinaryField(null=True, blank=True)   # only Gmail accounts hold a token
    last_sync_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created_at"]
        constraints = [models.UniqueConstraint(fields=["user", "provider", "address"], name="one_account_per_address")]


class EmailMessage(models.Model):
    """A short excerpt of one email. Mail that is not job related keeps only its id (for de-duplication)."""

    EXCERPT_LIMIT = 4096

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
    account = models.ForeignKey(EmailAccount, on_delete=models.CASCADE, related_name="messages")
    provider_message_id = models.CharField(max_length=300)
    thread_id = models.CharField(max_length=300, blank=True)
    sender = models.CharField(max_length=320, blank=True)
    subject = models.CharField(max_length=500, blank=True)
    received_at = models.DateTimeField(null=True, blank=True)
    excerpt = models.TextField(blank=True)
    relevant = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-received_at"]
        constraints = [models.UniqueConstraint(fields=["account", "provider_message_id"], name="one_row_per_message")]

    @property
    def sender_domain(self):
        return self.sender.rsplit("@", 1)[-1].strip("> ").lower() if "@" in self.sender else ""

    def discard_content(self):
        self.excerpt, self.relevant = "", False


class DetectedEmailEvent(models.Model):
    PUBLISHED, NEEDS_REVIEW, CONFIRMED, DISMISSED = "PUBLISHED", "NEEDS_REVIEW", "CONFIRMED", "DISMISSED"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
    email = models.ForeignKey(EmailMessage, on_delete=models.CASCADE, related_name="events")
    event_type = models.CharField(max_length=30)
    confidence = models.FloatField(default=0)
    extracted_data = models.JSONField(default=dict, blank=True)
    classifier = models.CharField(max_length=20, blank=True)      # which strategy decided: rules, llm, rules+llm
    application = models.ForeignKey("applications.Application", null=True, blank=True, on_delete=models.SET_NULL,
                                    related_name="email_events")
    match_confidence = models.FloatField(default=0)
    candidates = models.JSONField(default=list, blank=True)       # [{application_id, label, score}]
    review_status = models.CharField(max_length=20, default=NEEDS_REVIEW, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created_at"]

    def requires_review(self, threshold=0.85):
        return not (self.application_id and self.confidence >= threshold and self.match_confidence >= threshold)
