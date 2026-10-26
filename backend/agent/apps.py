from django.apps import AppConfig


class AgentConfig(AppConfig):
    name = "agent"

    def ready(self):
        from notifications.events import JobsImportedEvent, event_bus

        from .triage import TriageTrigger

        event_bus.subscribe(JobsImportedEvent, TriageTrigger())
