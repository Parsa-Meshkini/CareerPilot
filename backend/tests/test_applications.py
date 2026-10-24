import pytest

from applications.models import Application
from applications.services import ApplicationService
from applications.states import STATES, InvalidTransitionError
from careerpilot.errors import ConflictError
from notifications.events import ApplicationStatusChanged, event_bus
from tests.factories import make_job


@pytest.fixture
def app(user):
    return ApplicationService().create_application(user, make_job(user).id)


def test_allowed_actions_follow_state_machine():
    allowed = {name: cls().allowed_actions() for name, cls in STATES.items()}
    assert allowed["SAVED"] == ["mark_prepared", "submit", "withdraw"]
    assert allowed["APPLIED"] == ["schedule_interview", "receive_offer", "reject", "withdraw"]
    assert allowed["OFFER"] == ["accept", "decline"]
    assert allowed["WITHDRAWN"] == [] and allowed["ACCEPTED"] == []


def test_happy_path_records_timeline_and_events(user, app):
    events = []

    class Spy:
        def handle(self, e):
            events.append((e.from_state, e.to_state))

    spy = Spy()
    event_bus.subscribe(ApplicationStatusChanged, spy)
    svc = ApplicationService()
    try:
        for action in ("mark_prepared", "submit", "schedule_interview", "receive_offer", "accept"):
            svc.transition(user, app.id, action)
    finally:
        event_bus.unsubscribe(ApplicationStatusChanged, spy)
    app.refresh_from_db()
    assert app.state_name == "ACCEPTED" and app.applied_at is not None and app.version == 6
    assert events[1] == ("PREPARED", "APPLIED") and len(events) == 5
    assert app.changes.count() == 5


def test_illegal_transition_is_rejected(user, app):
    with pytest.raises(InvalidTransitionError) as err:
        ApplicationService().transition(user, app.id, "accept")
    assert err.value.details["allowed_actions"] == ["mark_prepared", "submit", "withdraw"]


def test_illegal_transition_api_returns_409(api, user, app):
    r = api.post(f"/api/applications/{app.id}/transition/", {"action": "receive_offer"}, format="json")
    assert r.status_code == 409 and r.data["error"] == "invalid_transition"


def test_undo_latest_change_only(user, app):
    svc = ApplicationService()
    first = svc.transition(user, app.id, "submit")
    second = svc.transition(user, app.id, "reject")
    with pytest.raises(ConflictError):
        svc.undo_transition(user, first.id)
    svc.undo_transition(user, second.id)
    app.refresh_from_db()
    assert app.state_name == "APPLIED"
    svc.undo_transition(user, first.id)  # now first is the latest live change
    app.refresh_from_db()
    assert app.state_name == "SAVED" and app.applied_at is None


def test_stale_version_conflicts(api, user, app):
    r = api.post(f"/api/applications/{app.id}/transition/", {"action": "submit", "version": 99}, format="json")
    assert r.status_code == 409


def test_api_create_list_detail(api, user):
    job = make_job(user)
    r = api.post("/api/applications/", {"job_id": str(job.id)}, format="json")
    assert r.status_code == 201 and r.data["state"] == "SAVED"
    assert api.post("/api/applications/", {"job_id": str(job.id)}, format="json").status_code == 409
    app_id = r.data["id"]
    r = api.post(f"/api/applications/{app_id}/transition/", {"action": "submit"}, format="json")
    change_id = r.data["application"]["undoable_change_id"]
    assert api.get("/api/applications/?state=applied").data[0]["id"] == app_id
    r = api.post(f"/api/applications/changes/{change_id}/undo/")
    assert r.data["state"] == "SAVED" and r.data["timeline"][0]["action"] == "undo"


def test_interview_in_interview_state_adds_round(user, app):
    svc = ApplicationService()
    svc.transition(user, app.id, "submit")
    svc.transition(user, app.id, "schedule_interview")
    svc.add_interview(user, app.id, {"format": "video"})
    svc.transition(user, app.id, "schedule_interview")
    svc.add_interview(user, app.id, {"format": "onsite"})
    app = Application.objects.get(pk=app.id)
    assert app.state_name == "INTERVIEW" and [i.round for i in app.interviews.all()] == [1, 2]


def test_other_users_application_hidden(api, other_user):
    other_app = ApplicationService().create_application(other_user, make_job(other_user).id)
    assert api.get(f"/api/applications/{other_app.id}/").status_code == 404
