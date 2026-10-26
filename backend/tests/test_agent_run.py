import pytest

from agent.controller import AgentController
from agent.models import AgentMemoryEntry, AgentRun
from applications.models import Application
from tests.factories import make_job, make_profile

ANALYSIS = {"required_skills": ["python", "django"], "preferred_skills": [], "responsibilities": [],
            "seniority": "intern", "keywords": [], "red_flags": []}


@pytest.fixture
def ready(user, mock_llm):
    make_profile(user)
    job = make_job(user, title="Backend Developer Co-op", company="Acme Corp")
    from agent.context import ContextBuilder

    exp = str(ContextBuilder().build_candidate_context(user).facts_of("EXPERIENCE")[0].id)
    mock_llm.script("job.analyze", ANALYSIS)
    mock_llm.script("resume.tailor", {"entries": [{"fact_id": exp, "bullets": [
        {"text": "Built a REST API with Docker", "source_fact_ids": [exp]}]}], "skills": ["python"]})
    mock_llm.script("cover_letter.tailor", {"greeting": "Dear Hiring Manager,", "closing": "Sincerely,",
                                            "paragraphs": [{"text": "I built a REST API at Acme.", "source_fact_ids": [exp]}]})
    mock_llm.script("match.explain", {"explanation": "Good fit.", "suggestions": []})
    return job


PLAN = {"steps": [
    {"tool": "search_jobs", "args": {"query": "acme backend"}, "intent": "find it"},
    {"tool": "match_job", "args": {"job_id": "@focus_job", "deep": True}, "intent": "check fit"},
    {"tool": "build_package", "args": {"job_id": "@focus_job"}, "intent": "prepare documents"}],
    "notes": "Submission is up to you."}


def test_llm_plan_runs_until_approval_then_completes(user, ready, mock_llm, api):
    mock_llm.script("agent.plan", PLAN)
    r = api.post("/api/agent/runs/", {"goal": "Prepare my application for the Acme backend co-op"}, format="json")
    run = r.data
    assert run["goal_type"] == "PREPARE_APPLICATION" and run["plan_source"] == "LLM"
    assert run["status"] == "AWAITING_APPROVAL" and run["pending"]["description"].startswith("build_package")
    assert [a["command"] for a in run["actions"]] == ["search_jobs", "match_job"]

    r = api.post(f"/api/agent/runs/{run['id']}/approve/", {"approve": True}, format="json")
    assert r.data["status"] == "COMPLETED" and "package" in r.data["summary"]
    assert Application.objects.get(job=ready).state_name == "PREPARED"
    assert AgentMemoryEntry.objects.filter(user=user, kind="EPISODIC").exists()


def test_invalid_llm_plans_fall_back_to_playbook(user, ready, mock_llm):
    mock_llm.scripts["agent.plan"] = [{"steps": [{"tool": "submit_application", "args": {}, "intent": "x"}]},
                                      {"steps": [{"tool": "match_job", "args": {"job_id": "not-mine"}, "intent": "x"}]}]
    run = AgentController().run_goal(user, "Prepare my application for the Acme backend co-op")
    assert run.plan_source == "PLAYBOOK"
    assert [s["tool"] for s in run.plan] == ["search_jobs", "retrieve_context", "analyze_job", "match_job",
                                             "build_package"]
    assert "invalid" in mock_llm.calls[1].prompt


def test_rejecting_a_step_replans_and_stores_preference(user, ready, mock_llm):
    mock_llm.script("agent.plan", PLAN)
    mock_llm.script("agent.replan", {"steps": [], "rationale": "Nothing else to do without the package."})
    run = AgentController().run_goal(user, "Prepare my Acme backend application")
    run = AgentController().resume_run(user, run.id, approve=False, reason="I will write my own")
    assert run.status == "COMPLETED" and run.replan_count == 1
    assert "REJECTED" in [a.status for a in run.actions.all()]
    assert AgentMemoryEntry.objects.filter(user=user, kind="PREFERENCE", content__contains="own").exists()


def test_ambiguous_search_asks_user(user, ready, mock_llm):
    make_job(user, title="Backend Intern", company="Acme Labs", location="Ottawa")
    mock_llm.script("agent.plan", PLAN)
    run = AgentController().run_goal(user, "Prepare my Acme backend application")
    assert run.status == "AWAITING_INPUT" and len(run.pending["options"]) == 2
    run = AgentController().answer(user, run.id, str(ready.id))
    assert run.status == "AWAITING_APPROVAL" and run.context["focus_job_id"] == str(ready.id)


def test_weak_match_triggers_llm_reflection(user, ready, mock_llm):
    weak = make_job(user, title="Sales Lead", company="Zeta", description="Retail sales. " * 40, work_term="")
    mock_llm.scripts["job.analyze"] = [{**ANALYSIS, "required_skills": ["salesforce", "negotiation"],
                                        "seniority": "senior"}]
    mock_llm.script("agent.plan", {"steps": [
        {"tool": "match_job", "args": {"job_id": "@focus_job"}, "intent": "check fit"},
        {"tool": "build_package", "args": {"job_id": "@focus_job"}, "intent": "documents"}]})
    mock_llm.script("agent.reflect", {"action": "ASK_USER", "rationale": "This is a poor fit; continue?"})
    run = AgentController().run_goal(user, "Prepare an application for this job", job_id=weak.id)
    assert run.status == "AWAITING_INPUT" and run.decisions[-1]["source"] == "llm"
    run = AgentController().answer(user, run.id, "stop")
    assert run.status == "COMPLETED" and "Stopped" in run.summary


def test_forbidden_request_is_explained(user, ready, mock_llm):
    mock_llm.script("agent.plan", {"steps": [{"tool": "search_jobs", "args": {"query": "acme"}, "intent": "find"}]})
    run = AgentController().run_goal(user, "Submit my application to Acme for me")
    assert run.status == "COMPLETED" and "never submits applications" in run.summary


def test_tool_failure_retries_then_replans(user, ready, mock_llm):
    mock_llm.script("agent.plan", {"steps": [{"tool": "match_job", "args": {"job_id": str(ready.id)},
                                              "intent": "check"}]})
    mock_llm.script("agent.replan", {"steps": [], "rationale": "set up your goal first"})
    user.career_goal.delete()
    run = AgentController().run_goal(user, "how well do I fit the acme job?", goal_type="FREE_FORM")
    assert [d["action"] for d in run.decisions][:2] == ["RETRY", "REPLAN"]
    assert run.status == "COMPLETED"


def test_budget_exhaustion_aborts(user, ready, mock_llm, settings):
    settings.LLM_RUN_TOKEN_BUDGET = 1
    run = AgentController().run_goal(user, "Prepare my Acme backend application")
    assert run.plan_source == "PLAYBOOK"  # planning itself was over budget
    assert run.status == "ABORTED" and "budget" in run.error
    assert "search_jobs" in run.error  # partial result is reported
    assert AgentRun.objects.get(pk=run.id).tokens_used == 0


def test_undo_agent_status_change_from_api(user, ready, mock_llm, api):
    mock_llm.script("agent.plan", {"steps": [{"tool": "update_application_status",
                                              "args": {"job_id": str(ready.id), "action": "mark_prepared"},
                                              "intent": "mark prepared"}]})
    r = api.post("/api/agent/runs/", {"goal": "mark the acme job prepared", "goal_type": "FREE_FORM"}, format="json")
    r = api.post(f"/api/agent/runs/{r.data['id']}/approve/", {"approve": True}, format="json")
    action = r.data["actions"][-1]
    assert action["reversible"] and Application.objects.get(job=ready).state_name == "PREPARED"
    assert api.post(f"/api/agent/actions/{action['id']}/undo/").data["status"] == "UNDONE"
    assert Application.objects.get(job=ready).state_name == "SAVED"
