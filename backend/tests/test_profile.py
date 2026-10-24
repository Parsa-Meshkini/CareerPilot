import pytest

from candidates.models import CareerGoal, ProfileFact, StrategyWeights
from candidates.services import CandidateService
from careerpilot.errors import ValidationFailed

EXP = {"organization": "Acme", "title": "Developer Intern", "start_date": "2025-05", "end_date": "2025-08",
       "bullets": ["Built a REST API in Django", "Cut test time by 40%"]}


def test_manual_fact_is_verified_and_listed(api):
    r = api.post("/api/profile/facts/", {"fact_type": "EXPERIENCE", "data": EXP}, format="json")
    assert r.status_code == 201 and r.data["verified"] is True
    body = api.get("/api/profile/").data
    assert body["verified_count"] == 1
    assert "No verified skills yet" in body["hints"]


def test_fact_validation_errors(api):
    bad = {**EXP, "title": "", "end_date": "2024-01"}
    r = api.post("/api/profile/facts/", {"fact_type": "EXPERIENCE", "data": bad}, format="json")
    assert r.status_code == 400
    assert set(r.data["fields"]) == {"title", "end_date"}


def test_extracted_facts_need_explicit_verification(user):
    svc = CandidateService()
    fact = svc.add_fact(user, "SKILL", {"name": "Python"}, source=ProfileFact.RESUME_EXTRACTION)
    assert fact.verified is False
    assert svc.verify_facts(user, [fact.id], {str(fact.id): {"proficiency": "advanced"}}) == 1
    fact.refresh_from_db()
    assert fact.verified and fact.data["proficiency"] == "advanced"


def test_other_users_fact_is_not_found(api, other_user):
    foreign = CandidateService().add_fact(other_user, "SKILL", {"name": "Go"})
    assert api.delete(f"/api/profile/facts/{foreign.id}/").status_code == 404
    assert api.get("/api/profile/facts/").data == []


def test_goal_requires_work_term_for_coop(api):
    r = api.put("/api/profile/goal/", {"goal_type": "COOP", "target_roles": "backend"}, format="json")
    assert r.status_code == 400 and r.data["fields"] == {"work_term": "required"}


def test_goal_selects_strategy_and_resets_weights_on_change(user):
    svc = CandidateService()
    goal, changed = svc.update_career_goal(user, {"goal_type": "COOP", "work_term": "Summer 2027",
                                                  "target_roles": "backend, devops"})
    assert changed and goal.to_strategy_key() == "coop_internship"
    assert goal.target_roles == ["backend", "devops"]
    w = goal.weights
    w.apply_adjustment("skill", w.skill + 0.05)
    goal.weights = w
    goal.save()
    goal, changed = svc.update_career_goal(user, {"goal_type": "NEW_GRAD", "min_match_score": 70})
    assert changed and goal.to_strategy_key() == "entry_level"
    assert goal.weights.skill == 0.50


def test_unknown_goal_type_rejected(user):
    with pytest.raises(ValidationFailed):
        CandidateService().update_career_goal(user, {"goal_type": "ASTRONAUT"})


def test_weight_adjustment_is_bounded():
    w = StrategyWeights()
    with pytest.raises(ValueError):
        w.apply_adjustment("skill", w.skill + 0.2)


def test_context_text():
    f = ProfileFact(fact_type="EXPERIENCE", data=EXP)
    assert f.to_context_text().startswith("Developer Intern at Acme (2025-05 to 2025-08)")
    assert CareerGoal(goal_type="CAREER_SWITCH").to_strategy_key() == "career_switch"
