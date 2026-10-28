"""EmlFileAdapter and MockEmailAdapter: the email pipeline without OAuth (Tier 1)."""
import hashlib
import json
from email import policy
from email.parser import BytesParser
from pathlib import Path

from careerpilot.errors import ValidationFailed
from integrations.providers import EmailProviderAdapter, RawEmail

SAMPLE_INBOX = Path(__file__).with_name("sample_inbox.json")


def raw_from_bytes(data: bytes, fallback_id=None):
    """Parses one RFC 822 message into a RawEmail. Raises ValidationFailed when it is not an email."""
    message = BytesParser(policy=policy.default).parsebytes(data)
    if not message["From"] and not message["Subject"]:
        raise ValidationFailed("file is not a readable email (no From or Subject header)")
    message_id = (message["Message-ID"] or "").strip().strip("<>") or fallback_id or hashlib.sha256(data).hexdigest()[:32]
    headers = {h: str(message[h] or "") for h in ("From", "To", "Subject", "Date")}
    return RawEmail(provider_message_id=message_id, thread_id=str(message["References"] or message_id).split()[0],
                    headers=headers, mime_body=data)


class EmlFileAdapter(EmailProviderAdapter):
    """Reads .eml files the student uploads. Nothing is fetched from a server: files are handed in."""

    def __init__(self):
        self._pending = {}

    def provider_name(self):
        return "EML"

    def load(self, account, files):
        """files: list of (filename, bytes). Returns (RawEmails, [(filename, problem)])."""
        good, bad = [], []
        for name, data in files:
            try:
                good.append(raw_from_bytes(data))
            except ValidationFailed as exc:
                bad.append((name, exc.message))
        self._pending[account.id] = good
        return good, bad

    def fetch_new_messages(self, account, since=None):
        return self._pending.pop(account.id, [])


class MockEmailAdapter(EmailProviderAdapter):
    """A scripted inbox for demos and tests. Reads a JSON list of {from, subject, date, body}."""

    def __init__(self, fixture=SAMPLE_INBOX, messages=None):
        self.fixture, self.messages = fixture, messages

    def provider_name(self):
        return "MOCK"

    def fetch_new_messages(self, account, since=None):
        items = self.messages if self.messages is not None else json.loads(Path(self.fixture).read_text())
        out = []
        for i, item in enumerate(items):
            message_id = item.get("id") or f"mock-{i}"
            lines = [f"From: {item['from']}", f"To: {account.address or 'student@example.com'}",
                     f"Subject: {item['subject']}", f"Date: {item.get('date', 'Mon, 02 Nov 2026 09:00:00 -0500')}",
                     f"Message-ID: <{message_id}>", "Content-Type: text/plain; charset=utf-8", "", item["body"]]
            out.append(raw_from_bytes("\r\n".join(lines).encode(), fallback_id=message_id))
        return out
