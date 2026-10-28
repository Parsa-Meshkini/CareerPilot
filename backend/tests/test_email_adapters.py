import pytest

from careerpilot.errors import ValidationFailed
from integrations.eml.adapters import EmlFileAdapter, MockEmailAdapter
from integrations.models import EmailAccount, EmailMessage
from integrations.normalizer import EmailNormalizer
from integrations.providers import EmailProviderRegistry
from integrations.repository import EmailAccountRepository

EML = (b"From: Jane Doe <jane@acme-corp.com>\r\nTo: ana@example.com\r\nSubject: Interview  invitation\r\n"
       b"Date: Mon, 02 Nov 2026 09:12:00 -0500\r\nMessage-ID: <abc123@acme-corp.com>\r\n"
       b"Content-Type: text/plain; charset=utf-8\r\n\r\nHello Ana,\r\nWe would like to interview you.\r\n"
       b"> quoted earlier text\r\n")

HTML_EML = (b"From: hr@acme-corp.com\r\nSubject: Offer\r\nDate: Mon, 02 Nov 2026 09:12:00 -0500\r\n"
            b"Content-Type: text/html; charset=utf-8\r\n\r\n<html><style>p{}</style><p>We are <b>pleased</b> "
            b"to offer you &amp; welcome you.</p></html>")


@pytest.fixture
def account(user):
    return EmailAccountRepository().get_or_create(user, "EML")


def test_eml_adapter_parses_headers_and_ids(account):
    adapter = EmlFileAdapter()
    good, bad = adapter.load(account, [("a.eml", EML), ("junk.eml", b"just some text")])
    assert [r.provider_message_id for r in good] == ["abc123@acme-corp.com"]
    assert good[0].headers["Subject"] == "Interview  invitation"
    assert bad == [("junk.eml", "file is not a readable email (no From or Subject header)")]
    assert len(adapter.fetch_new_messages(account)) == 1
    assert adapter.fetch_new_messages(account) == []   # handed over once


def test_normalizer_builds_clean_excerpt(account):
    raw = EmlFileAdapter().load(account, [("a.eml", EML)])[0][0]
    msg = EmailNormalizer().normalize(raw, account)
    assert msg.sender == "Jane Doe <jane@acme-corp.com>" and msg.sender_domain == "acme-corp.com"
    assert msg.subject == "Interview invitation"
    assert "interview you" in msg.excerpt and "quoted earlier" not in msg.excerpt
    assert msg.received_at.year == 2026


def test_normalizer_strips_html_and_caps_excerpt(account):
    raw = EmlFileAdapter().load(account, [("o.eml", HTML_EML)])[0][0]
    assert EmailNormalizer().normalize(raw, account).excerpt == "We are pleased to offer you & welcome you."
    big = EML.replace(b"Hello Ana", b"x" * 10000)
    msg = EmailNormalizer().normalize(EmlFileAdapter().load(account, [("b.eml", big)])[0][0], account)
    assert len(msg.excerpt) == EmailMessage.EXCERPT_LIMIT


def test_mock_adapter_serves_scripted_and_sample_inbox(user):
    account = EmailAccountRepository().get_or_create(user, "MOCK", "demo@example.com")
    sample = MockEmailAdapter().fetch_new_messages(account)
    assert len(sample) == 3 and sample[0].headers["To"] == "demo@example.com"
    scripted = MockEmailAdapter(messages=[{"from": "a@b.com", "subject": "Hi", "body": "Body"}]).fetch_new_messages(account)
    assert scripted[0].provider_message_id == "mock-0"


def test_registry_resolves_by_provider_name():
    registry = EmailProviderRegistry()
    registry.register(MockEmailAdapter())
    assert registry.get_adapter("MOCK").provider_name() == "MOCK"
    with pytest.raises(ValidationFailed):
        registry.get_adapter("GMAIL")


def test_accounts_are_tenant_scoped(user, other_user):
    repo = EmailAccountRepository()
    mine = repo.get_or_create(user, "EML")
    assert repo.get_or_create(user, "EML").id == mine.id
    assert list(repo.list_by_user(other_user)) == []
    assert EmailAccount.objects.count() == 1
