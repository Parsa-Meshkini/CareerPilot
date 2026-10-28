"""EmailSyncService (F14): fetch -> normalize -> detect -> match -> publish or queue for review."""
from django.db import transaction
from django.utils import timezone

from applications.repository import ApplicationRepository
from careerpilot.errors import ConflictError, ValidationFailed
from notifications.events import ApplicationEventDetected, event_bus

from .detector import EmailEventDetector
from .eml.adapters import EmlFileAdapter, MockEmailAdapter
from .matcher import ApplicationMatcher
from .models import EVENT_TYPES, NOT_RELEVANT, DetectedEmailEvent, EmailAccount
from .normalizer import EmailNormalizer
from .providers import EmailProviderRegistry
from .repository import DetectedEventRepository, EmailAccountRepository, EmailMessageRepository

MAX_EML_FILES, MAX_EML_BYTES = 50, 2_000_000


def default_registry():
    registry = EmailProviderRegistry()
    registry.register(EmlFileAdapter())
    registry.register(MockEmailAdapter())
    return registry


class EmailSyncService:
    def __init__(self, registry=None, detector=None, matcher=None, bus=None, auto_apply_threshold=0.85):
        self.registry = registry or default_registry()
        self.detector = detector or EmailEventDetector()
        self.matcher = matcher or ApplicationMatcher()
        self.bus = bus or event_bus
        self.threshold = auto_apply_threshold
        self.accounts, self.messages = EmailAccountRepository(), EmailMessageRepository()
        self.events, self.applications = DetectedEventRepository(), ApplicationRepository()
        self.normalizer = EmailNormalizer()

    def connect_mock(self, user):
        return self.accounts.get_or_create(user, "MOCK", "demo@careerpilot.local")

    def sync_account(self, user, account_id):
        account = self.accounts.find_by_id(user, account_id)
        if account.status != EmailAccount.ACTIVE:
            raise ConflictError("this email account needs to be reconnected", status=account.status)
        raw = self.registry.get_adapter(account.provider).fetch_new_messages(account, account.last_sync_at)
        summary = self._process_all(user, account, raw)
        account.last_sync_at = timezone.now()
        account.save(update_fields=["last_sync_at"])
        return summary

    def import_eml_files(self, user, files):
        """files: list of (filename, bytes). Bad files are reported, the rest are processed."""
        if not files:
            raise ValidationFailed("no files were uploaded")
        if len(files) > MAX_EML_FILES:
            raise ValidationFailed(f"at most {MAX_EML_FILES} files per upload")
        if any(len(data) > MAX_EML_BYTES for _, data in files):
            raise ValidationFailed("a file is larger than 2 MB")
        account = self.accounts.get_or_create(user, "EML", "uploaded-files")
        adapter = self.registry.get_adapter("EML")
        raw, bad = adapter.load(account, files)
        summary = self._process_all(user, account, adapter.fetch_new_messages(account))
        summary["rejected_files"] = [{"file": name, "reason": reason} for name, reason in bad]
        return summary

    def confirm_event(self, user, event_id, application_id=None, event_type=None):
        event = self.events.find_by_id(user, event_id)
        if event.review_status not in (DetectedEmailEvent.NEEDS_REVIEW,):
            raise ConflictError("this event was already resolved", review_status=event.review_status)
        if event_type:
            if event_type not in EVENT_TYPES or event_type == NOT_RELEVANT:
                raise ValidationFailed(f"unknown event type {event_type}")
            event.event_type = event_type
        if application_id:
            event.application = self.applications.find_by_id(user, application_id)
        if event.application_id is None:
            raise ValidationFailed("choose which application this email is about")
        event.review_status = DetectedEmailEvent.CONFIRMED
        event.match_confidence = event.confidence = 1.0   # the student has decided
        event.save()
        self._publish(event)
        return event

    def dismiss_event(self, user, event_id):
        event = self.events.find_by_id(user, event_id)
        if event.review_status != DetectedEmailEvent.NEEDS_REVIEW:
            raise ConflictError("this event was already resolved", review_status=event.review_status)
        event.review_status = DetectedEmailEvent.DISMISSED
        event.save(update_fields=["review_status"])
        return event

    def disconnect(self, user, account_id):
        self.accounts.find_by_id(user, account_id).delete()

    # -- pipeline ----------------------------------------------------------------------------
    def _process_all(self, user, account, raw_emails):
        summary = {"fetched": len(raw_emails), "new": 0, "duplicates": 0, "not_relevant": 0, "published": 0,
                   "needs_review": 0, "event_ids": []}
        for raw in raw_emails:
            if self.messages.exists(account, raw.provider_message_id):
                summary["duplicates"] += 1
                continue
            outcome, event = self._process_email(user, account, raw)
            summary["new"] += 1
            summary[outcome] += 1
            if event is not None:
                summary["event_ids"].append(str(event.id))
        return summary

    @transaction.atomic
    def _process_email(self, user, account, raw):
        email = self.normalizer.normalize(raw, account)
        event = self.detector.detect(email, user)
        if event is None:
            email.discard_content()    # not job related: keep only the id so it is not fetched again
            email.subject = email.sender = ""
            email.save()
            return "not_relevant", None
        email.save()
        event.email = email
        match = self.matcher.match(event, email, event.extracted_data)
        event.application_id, event.match_confidence, event.candidates = (
            match.application_id, match.confidence, match.candidates)
        if event.requires_review(self.threshold):
            event.review_status = DetectedEmailEvent.NEEDS_REVIEW
        else:
            event.review_status = DetectedEmailEvent.PUBLISHED
        event.save()
        if event.review_status == DetectedEmailEvent.PUBLISHED:
            self._publish(event)
            return "published", event
        return "needs_review", event

    def _publish(self, event):
        self.bus.publish(ApplicationEventDetected(
            event.user_id, detected_event_id=event.id, event_type=event.event_type,
            application_id=event.application_id, confidence=event.confidence,
            extracted_data=event.extracted_data))
