from django.apps import AppConfig


class CandidatesConfig(AppConfig):
    name = "candidates"

    def ready(self):
        from . import tasks  # noqa: F401  (registers background tasks)
