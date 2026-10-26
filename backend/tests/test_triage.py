from datetime import date, timedelta

import pytest

from agent.models import AgentRun
from candidates.services import CandidateService
from jobs.models import JobPosting
from tests.factories import make_profile

FULL = "We need Python, Django and SQL. Build REST APIs with Docker. " * 10


def york_payload(n_good=3, n_bad=2, full=True, soon=False):
    records = []
    deadline = (date.today() + timedelta(days=10 if soon else 60)).isoformat()
    for i in range(n_good):
        records.append({"url": f"https://experience.yorku.ca/p/{i}", "detail_text": FULL if full else "",
                        "fields": {"Job ID": f"g{i}", "Job Title": f"Backend Developer Co-op {i}",
                                   "Organization": f"Company {i}", "Work Term": "Summer 2027",
                                   "Application Deadline": deadline}})
    for i in range(n_bad):
        records.append({"url": f"https://experience.yorku.ca/q/{i}", "detail_text": "Sell shoes in store. " * 30,
                        "fields": {"Job ID": f"b{i}", "Job Title": f"Retail Associate {i}",
                                   "Organization": f"Shop {i}", "Work Term": "Fall 2027"}})
    return {"records": records}


@pytest.fixture
def scripted(mock_llm):
    mock_llm.script("job.analyze", {"required_skills": ["python", "django", "sql"], "preferred_skills": [],
                                    "responsibilities": [], "seniority": "intern", "keywords": [], "red_flags": []})
    mock_llm.script("match.explain", {"explanation": "Fits.", "suggestions": []})
    return mock_llm


def test_import_event_triggers_triage_and_shortlists(api, user, scripted):
    make_profile(user)
    r = api.post("/api/jobs/batches/", {"source": "york", "payload": york_payload()}, format="json")
    assert r.data["imported"] == 5
    run = AgentRun.objects.get(user=user, goal_type="TRIAGE_BATCH")
    assert run.status == "COMPLETED" and run.plan_source == "PLAYBOOK"
    assert [a.command_name for a in run.actions.all()] == ["quick_score_jobs", "select_for_deep_analysis",
                                                           "deep_match_jobs", "rank_jobs"]
    shortlisted = JobPosting.objects.filter(user=user, shortlisted=True)
    assert shortlisted.count() == 3 and all("Backend" in j.title for j in shortlisted)
    assert run.summary.startswith("3 of 5 shortlisted")
    assert all(j.match.is_deep_analysis for j in shortlisted)


def test_summary_only_batch_replans_to_quick_ranking(api, user, scripted):
    make_profile(user)
    api.post("/api/jobs/batches/", {"source": "york", "payload": york_payload(full=False)}, format="json")
    run = AgentRun.objects.get(user=user, goal_type="TRIAGE_BATCH")
    assert run.replan_count == 1 and run.status == "COMPLETED"
    assert "REPLAN" in [d["action"] for d in run.decisions]
    assert "summary-only" in run.summary
    assert not [c for c in scripted.calls if c.prompt_key == "job.analyze"]  # no tokens spent


def test_budget_caps_deep_analysis(api, user, scripted, settings):
    settings.LLM_RUN_TOKEN_BUDGET = 4500  # room for exactly one deep job
    make_profile(user)
    api.post("/api/jobs/batches/", {"source": "york", "payload": york_payload()}, format="json")
    run = AgentRun.objects.get(user=user, goal_type="TRIAGE_BATCH")
    select = run.actions.get(command_name="select_for_deep_analysis")
    assert select.observation["data"]["k"] == 1


def test_disabled_auto_triage_and_manual_trigger(api, user, scripted):
    make_profile(user)
    CandidateService().update_profile(user, {"auto_triage": False})
    r = api.post("/api/jobs/batches/", {"source": "york", "payload": york_payload()}, format="json")
    assert not AgentRun.objects.filter(user=user).exists()
    r = api.post(f"/api/jobs/batches/{r.data['id']}/triage/")
    assert r.status_code == 201 and r.data["status"] == "COMPLETED"


def test_no_goal_no_triage(api, user, scripted):
    api.post("/api/jobs/batches/", {"source": "york", "payload": york_payload()}, format="json")
    assert not AgentRun.objects.filter(user=user).exists()
