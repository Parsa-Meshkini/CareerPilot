import pytest

from agent.llm.types import LLMProviderError
from applications.services import ApplicationService
from integrations.classifiers import HybridClassifier, LLMClassifier, RuleBasedClassifier, extract_schedule
from integrations.detector import EmailEventDetector
from integrations.matcher import ApplicationMatcher
from integrations.models import DetectedEmailEvent, EmailAccount, EmailMessage
from integrations.sync_service import EmailSyncService
from notifications.events import ApplicationEventDetected, event_bus
from tests.factories import make_job


def mail(subject="Hello", body="", sender="HR <hr@acme-corp.com>", user=None):
    account = EmailAccount.objects.get_or_create(user=user, provider="MOCK", address="t@x.com")[0]
    return EmailMessage(user=user, account=account, provider_message_id=subject[:20], sender=sender,
                        subject=subject, excerpt=body)


def eml(subject, body, sender="hr@acme-corp.com", mid="m1"):
    return (f"From: {sender}\r\nTo: ana@example.com\r\nSubject: {subject}\r\nDate: Mon, 02 Nov 2026 09:12:00 -0500\r\n"
            f"Message-ID: <{mid}>\r\nContent-Type: text/plain\r\n\r\n{body}").encode()


@pytest.fixture
def acme(user):
    return ApplicationService().create_application(user, make_job(user, company="Acme Corp").id)


@pytest.fixture
def published():
    seen = []

    class Spy:
        def handle(self, e):
            seen.append(e)

    spy = Spy()
    event_bus.subscribe(ApplicationEventDetected, spy)
    yield seen
    event_bus.unsubscribe(ApplicationEventDetected, spy)


@pytest.mark.parametrize("subject,body,expected", [
    ("Update", "Unfortunately we are not moving forward.", "REJECTION"),
    ("Your offer", "We are pleased to offer you the role.", "OFFER"),
    ("Interview invitation", "We would like to schedule an interview.", "INTERVIEW_INVITATION"),
    ("Thanks", "Thank you for applying to Acme.", "APPLICATION_RECEIVED"),
    ("Next step", "Please complete the online assessment.", "INFO_REQUESTED"),
    ("Sale", "30% off shoes", "NOT_RELEVANT"),
])
def test_rule_classifier_event_types(user, subject, body, expected):
    result = RuleBasedClassifier().classify(mail(subject, body, user=user))
    assert result.event_type == expected


def test_rule_classifier_boosts_ats_senders_and_extracts_schedule(user):
    email = mail("Interview invitation", "Join us on November 12, 2026 at 2:30 PM via Zoom.",
                 sender="no-reply@greenhouse.io", user=user)
    result = RuleBasedClassifier().classify(email)
    assert result.confidence == 0.93
    assert result.extracted_data == {"scheduled_at": "2026-11-12T14:30", "format": "zoom"}


def test_extract_schedule_formats():
    assert extract_schedule("on 2026-11-03 09:15 sharp", 2026) == "2026-11-03T09:15"
    assert extract_schedule("Dec 1st at 12 am", 2026) == "2026-12-01T00:00"
    assert extract_schedule("Feb 31 at 3pm", 2026) == ""
    assert extract_schedule("sometime next week", 2026) == ""


def test_hybrid_skips_llm_when_rules_are_confident(user, mock_llm):
    HybridClassifier().classify(mail("Update", "Unfortunately, no.", user=user))
    assert mock_llm.calls == []


def test_hybrid_asks_llm_when_rules_are_unsure_and_falls_back_offline(user, mock_llm):
    mock_llm.script("email.classify", {"event_type": "OFFER", "confidence": 0.95, "company": "Acme Corp"})
    result = HybridClassifier().classify(mail("Re: your application", "Great news inside.", user=user))
    assert (result.event_type, result.confidence, result.source) == ("OFFER", 0.95, "llm")
    assert result.extracted_data == {"company": "Acme Corp"}

    mock_llm.scripts.clear()
    mock_llm.script("email.classify", LLMProviderError("down", retryable=False))
    weak = HybridClassifier().classify(mail("Checking in", "Just checking in on things.", user=user))
    assert (weak.event_type, weak.source) == ("FOLLOW_UP", "rules")


def test_detector_strategy_can_be_swapped(user, mock_llm):
    email = mail("Update", "Unfortunately, no.", user=user)
    detector = EmailEventDetector(RuleBasedClassifier())
    assert detector.detect(email, user).event_type == "REJECTION"
    mock_llm.script("email.classify", {"event_type": "NOT_RELEVANT", "confidence": 0.9})
    detector.set_strategy(LLMClassifier())
    assert detector.detect(email, user) is None


def test_matcher_scores_sender_domain_company_and_title(user, acme):
    other = ApplicationService().create_application(user, make_job(user, company="Globex", title="Analyst").id)
    email = mail("Interview: Backend Developer Co-op at Acme Corp", "Acme Corp would like to meet.", user=user)
    match = ApplicationMatcher().match(None, email)
    assert match.application_id == acme.id and match.confidence >= 0.85 and not match.ambiguous
    assert other.id not in [c["application_id"] for c in match.candidates]


def test_matcher_flags_two_applications_at_the_same_company(user, acme):
    ApplicationService().create_application(user, make_job(user, company="Acme Corp", title="Data Intern").id)
    match = ApplicationMatcher().match(None, mail("Update from Acme Corp", "Acme Corp: hello.", user=user))
    assert match.ambiguous and match.confidence <= 0.6 and len(match.candidates) == 2


def test_matcher_ignores_terminal_applications(user, acme):
    svc = ApplicationService()
    svc.transition(user, acme.id, "withdraw")
    assert ApplicationMatcher().match(None, mail("Acme Corp interview", "Acme Corp", user=user)).application_id is None


def test_confident_event_is_published_to_the_event_bus(user, acme, published):
    summary = EmailSyncService().import_eml_files(user, [("a.eml", eml(
        "Interview invitation: Backend Developer Co-op at Acme Corp", "Acme Corp: we would like to schedule an interview."))])
    assert (summary["new"], summary["published"], summary["needs_review"]) == (1, 1, 0)
    event = DetectedEmailEvent.objects.get()
    assert event.review_status == "PUBLISHED" and event.application_id == acme.id
    assert [(e.event_type, e.application_id) for e in published] == [("INTERVIEW_INVITATION", acme.id)]


def test_uncertain_event_goes_to_review_then_confirm_publishes(user, acme, published):
    svc = EmailSyncService()
    summary = svc.import_eml_files(user, [("a.eml", eml("Update", "Unfortunately we are not moving forward.",
                                                          sender="jobs@example.org"))])
    assert summary["needs_review"] == 1 and published == []
    event = DetectedEmailEvent.objects.get()
    assert event.review_status == "NEEDS_REVIEW" and event.application_id is None
    with pytest.raises(Exception, match="choose which application"):
        svc.confirm_event(user, event.id)
    confirmed = svc.confirm_event(user, event.id, application_id=acme.id, event_type="REJECTION")
    assert confirmed.review_status == "CONFIRMED" and len(published) == 1 and published[0].application_id == acme.id
    with pytest.raises(Exception, match="already resolved"):
        svc.dismiss_event(user, event.id)


def test_not_relevant_mail_keeps_only_its_id_and_is_not_reprocessed(user):
    svc = EmailSyncService()
    files = [("s.eml", eml("Big sale", "Shoes 30% off", sender="promo@shop.example", mid="promo1"))]
    first = svc.import_eml_files(user, files)
    again = svc.import_eml_files(user, files)
    assert first["not_relevant"] == 1 and again["duplicates"] == 1 and again["new"] == 0
    msg = EmailMessage.objects.get()
    assert msg.excerpt == "" and msg.subject == "" and msg.relevant is False
    assert DetectedEmailEvent.objects.count() == 0


def test_mock_inbox_sync_end_to_end(user):
    svc = EmailSyncService()
    account = svc.connect_mock(user)
    summary = svc.sync_account(user, account.id)
    assert summary["fetched"] == 3 and summary["not_relevant"] == 1
    assert summary["needs_review"] == 2    # no applications are tracked yet, so both are unmatched
    account.refresh_from_db()
    assert account.last_sync_at is not None
    assert svc.sync_account(user, account.id)["duplicates"] == 3


def test_reauth_required_account_is_not_synced(user):
    svc = EmailSyncService()
    account = svc.connect_mock(user)
    EmailAccount.objects.filter(pk=account.pk).update(status="REAUTH_REQUIRED")
    with pytest.raises(Exception, match="reconnected"):
        svc.sync_account(user, account.id)


def test_upload_validation(user):
    svc = EmailSyncService()
    with pytest.raises(Exception, match="no files"):
        svc.import_eml_files(user, [])
    out = svc.import_eml_files(user, [("bad.eml", b"not an email")])
    assert out["new"] == 0 and out["rejected_files"][0]["file"] == "bad.eml"


def test_email_api_flow_and_tenant_isolation(api, user, other_user, acme):
    from rest_framework.test import APIClient
    from django.core.files.uploadedfile import SimpleUploadedFile

    upload = SimpleUploadedFile("a.eml", eml("Update", "Unfortunately, no.", sender="x@example.org"))
    res = api.post("/api/email/upload/", {"files": [upload]}, format="multipart")
    assert res.status_code == 201 and res.data["needs_review"] == 1
    queue = api.get("/api/email/events/?status=NEEDS_REVIEW").data
    assert len(queue) == 1 and queue[0]["email"]["subject"] == "Update"

    stranger = APIClient()
    stranger.force_authenticate(user=other_user)
    assert stranger.get("/api/email/events/").data == []
    assert stranger.post(f"/api/email/events/{queue[0]['id']}/dismiss/").status_code == 404

    done = api.post(f"/api/email/events/{queue[0]['id']}/confirm/", {"application_id": str(acme.id)}, format="json")
    assert done.status_code == 200 and done.data["review_status"] == "CONFIRMED"
    assert done.data["application_label"] == "Backend Developer Co-op at Acme Corp"
    assert api.post(f"/api/email/events/{queue[0]['id']}/dismiss/").status_code == 409

    account = api.post("/api/email/accounts/mock/").data
    assert api.post(f"/api/email/accounts/{account['id']}/sync/").data["fetched"] == 3
    assert api.delete(f"/api/email/accounts/{account['id']}/").status_code == 204
