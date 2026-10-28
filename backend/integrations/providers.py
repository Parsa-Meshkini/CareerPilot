"""Email provider adapters (P1 Adapter): turn a mailbox into a list of RawEmail, whatever the source."""
from abc import ABC, abstractmethod
from dataclasses import dataclass, field

from careerpilot.errors import ValidationFailed


@dataclass
class RawEmail:
    provider_message_id: str
    thread_id: str = ""
    headers: dict = field(default_factory=dict)   # From, To, Subject, Date (as sent)
    mime_body: bytes = b""                          # the full RFC 822 message


class EmailProviderAdapter(ABC):
    @abstractmethod
    def provider_name(self) -> str: ...

    @abstractmethod
    def fetch_new_messages(self, account, since=None) -> list: ...


class EmailProviderRegistry:
    """Simple factory: provider name -> adapter."""

    def __init__(self):
        self._adapters = {}

    def register(self, adapter):
        self._adapters[adapter.provider_name()] = adapter

    def get_adapter(self, provider):
        try:
            return self._adapters[provider]
        except KeyError:
            raise ValidationFailed(f"email provider '{provider}' is not available",
                                   available=sorted(self._adapters)) from None
