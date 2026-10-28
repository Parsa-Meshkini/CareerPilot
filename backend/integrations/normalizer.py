"""EmailNormalizer: RawEmail -> EmailMessage with a plain-text excerpt of at most 4 KB."""
import re
from email import policy
from email.parser import BytesParser
from email.utils import parseaddr, parsedate_to_datetime
from html import unescape

from django.utils import timezone

from .models import EmailMessage

_TAGS = re.compile(r"<(script|style).*?</\1>|<[^>]+>", re.S | re.I)
_QUOTED = re.compile(r"^(>.*|On .+ wrote:)$", re.M)


class EmailNormalizer:
    def normalize(self, raw, account):
        message = BytesParser(policy=policy.default).parsebytes(raw.mime_body)
        name, address = parseaddr(str(message["From"] or raw.headers.get("From", "")))
        return EmailMessage(
            user=account.user, account=account, provider_message_id=raw.provider_message_id[:300],
            thread_id=raw.thread_id[:300], sender=(f"{name} <{address}>" if name else address)[:320],
            subject=" ".join(str(message["Subject"] or raw.headers.get("Subject", "")).split())[:500],
            received_at=self._date(message["Date"] or raw.headers.get("Date")),
            excerpt=self._excerpt(message)[:EmailMessage.EXCERPT_LIMIT])

    @staticmethod
    def _date(value):
        try:
            when = parsedate_to_datetime(str(value))
        except (TypeError, ValueError):
            return timezone.now()
        return timezone.make_aware(when) if timezone.is_naive(when) else when

    @staticmethod
    def _excerpt(message):
        part = message.get_body(preferencelist=("plain", "html"))
        text = part.get_content() if part is not None else ""
        if part is not None and part.get_content_type() == "text/html":
            text = unescape(_TAGS.sub(" ", text))
        text = _QUOTED.sub("", text)
        return re.sub(r"[ \t]+", " ", re.sub(r"\n\s*\n+", "\n\n", text)).strip()
