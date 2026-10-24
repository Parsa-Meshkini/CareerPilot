"""ApplicationService (F13): create, transition through the State objects, undo, interviews."""
from django.db import transaction
from django.db.models import F

from careerpilot.errors import ConflictError
from jobs.repository import JobRepository
from notifications.events import ApplicationStatusChanged, event_bus

from .models import Interview, StatusChange
from .repository import ApplicationRepository
from .states import ACTIONS, InvalidTransitionError


class ApplicationService:
    def __init__(self, repository=None, jobs=None, bus=None):
        self.repo = repository or ApplicationRepository()
        self.jobs = jobs or JobRepository()
        self.bus = bus or event_bus

    def create_application(self, user, job_id, notes=""):
        job = self.jobs.find_by_id(user, job_id)
        existing = self.repo.find_by_job(user, job.id)
        if existing:
            raise ConflictError("an application for this job already exists", application_id=str(existing.id))
        from .models import Application

        return self.repo.save(Application(user=user, job=job, notes=notes))

    def get_or_create_for_job(self, user, job_id):
        return self.repo.find_by_job(user, job_id) or self.create_application(user, job_id)

    def list_applications(self, user, state=None):
        qs = self.repo.list_by_user(user)
        return qs.filter(state_name=state.upper()) if state else qs

    @transaction.atomic
    def transition(self, user, application_id, action, source=StatusChange.USER, evidence_id=None, reason="",
                   expected_version=None, when=None):
        if action not in ACTIONS:
            raise InvalidTransitionError("?", action, list(ACTIONS))
        app = self.repo.find_by_id(user, application_id)
        if expected_version is not None and int(expected_version) != app.version:
            raise ConflictError("the application was changed elsewhere; reload and try again",
                                current_version=app.version)
        before, applied_at = app.state_name, app.applied_at
        kwargs = {"when": when} if action == "schedule_interview" else {}
        app.perform(action, **kwargs)   # raises InvalidTransitionError (409) when illegal
        # optimistic lock: only write if nobody bumped the version in between
        updated = type(app).objects.filter(pk=app.pk, version=app.version).update(
            state_name=app.state_name, applied_at=app.applied_at, version=F("version") + 1)
        if not updated:
            raise ConflictError("the application was changed elsewhere; reload and try again")
        app.refresh_from_db()
        change = self.repo.save_change(StatusChange(
            user=user, application=app, from_state=before, to_state=app.state_name, action=action, source=source,
            reason=reason[:300], evidence_id=evidence_id, previous_applied_at=applied_at))
        if before != app.state_name:
            self._publish(user, app, change)
        return change

    @transaction.atomic
    def undo_transition(self, user, change_id):
        change = self.repo.find_change(user, change_id)
        app = change.application
        latest = self.repo.latest_change(app)
        if change.reverted or latest is None or latest.pk != change.pk:
            raise ConflictError("only the most recent change can be undone")
        app.state_name, app.applied_at = change.from_state, change.previous_applied_at
        app.version += 1
        app.save()
        change.revert()
        change.save()
        undo = self.repo.save_change(StatusChange(
            user=user, application=app, from_state=change.to_state, to_state=change.from_state, action="undo",
            source=StatusChange.USER, reason=f"undo of {change.action}", reverted=True))
        if change.from_state != change.to_state:
            self._publish(user, app, undo)
        return app

    def add_interview(self, user, application_id, details):
        app = self.repo.find_by_id(user, application_id)
        event_id = details.get("source_event_id")
        if event_id and Interview.objects.filter(source_event_id=event_id).exists():
            return Interview.objects.get(source_event_id=event_id)  # idempotent per detected email event
        return Interview.objects.create(
            user=user, application=app, round=app.interviews.count() + 1, scheduled_at=details.get("scheduled_at"),
            format=details.get("format", "")[:60], details_text=details.get("details_text", ""),
            source_event_id=event_id)

    def update_notes(self, user, application_id, notes):
        app = self.repo.find_by_id(user, application_id)
        app.notes = notes
        return self.repo.save(app)

    def _publish(self, user, app, change):
        self.bus.publish(ApplicationStatusChanged(user.id, application_id=app.id, change_id=change.id,
                                                  from_state=change.from_state, to_state=change.to_state,
                                                  source=change.source))
