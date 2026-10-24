"""Domain events and the DomainEventBus (P4 Observer).

Producers (JobImportService, ApplicationService, EmailSyncService,
ReminderService) publish events without knowing who reacts. Observers
implement EventListener.handle() and subscribe per event type. One failing
listener is logged and does not stop the others.
"""
import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime

from django.utils import timezone

log = logging.getLogger(__name__)


@dataclass
class DomainEvent:
    user_id: object
    occurred_at: datetime = field(default_factory=timezone.now, kw_only=True)

    @property
    def name(self):
        return type(self).__name__


@dataclass
class JobsImportedEvent(DomainEvent):
    batch_id: object
    job_ids: list
    source_type: str


@dataclass
class ApplicationStatusChanged(DomainEvent):
    application_id: object
    change_id: object
    from_state: str
    to_state: str
    source: str


@dataclass
class ApplicationEventDetected(DomainEvent):
    detected_event_id: object
    event_type: str
    application_id: object
    confidence: float
    extracted_data: dict


@dataclass
class DeadlineApproachingEvent(DomainEvent):
    job_id: object
    application_id: object
    deadline: object
    days_left: int


class EventListener(ABC):
    @abstractmethod
    def handle(self, event: DomainEvent): ...


class DomainEventBus:
    def __init__(self):
        self._listeners = {}
        self.history = []  # recent events, handy for debugging and tests

    def subscribe(self, event_type, listener):
        bucket = self._listeners.setdefault(event_type, [])
        if listener not in bucket:
            bucket.append(listener)

    def unsubscribe(self, event_type, listener):
        if listener in self._listeners.get(event_type, []):
            self._listeners[event_type].remove(listener)

    def listeners(self, event_type):
        return list(self._listeners.get(event_type, []))

    def publish(self, event):
        self.history = (self.history + [event])[-100:]
        errors = []
        for listener in self.listeners(type(event)):
            try:
                listener.handle(event)
            except Exception as exc:
                log.exception("listener %s failed on %s", type(listener).__name__, event.name)
                errors.append((listener, exc))
        return errors


event_bus = DomainEventBus()
