import pytest

from agent.commands import (ApprovalPolicy, BuildPackageCommand, RankJobsCommand, SearchJobsCommand, ToolManager,
                            UpdateApplicationStatusCommand)
from agent.memory import MemoryManager
from agent.models import AgentActionRecord, AgentRun
from applications.models import Application
from careerpilot.errors import ValidationFailed
from jobs.matching import MatchingService
from tests.factories import make_job, make_profile


@pytest.fixture
def run(user):
    return AgentRun.objects.create(user=user, goal_text="test", goal_type="FREE_FORM")


def test_catalog_has_no_submit_or_email_tool():
    names = {t["name"] for t in ToolManager().list_tools()}
    assert "search_jobs" in names and not any(w in n for n in names for w in ("submit", "email", "login"))


def test_search_sets_focus_and_reports_ambiguity(user, run):
    make_job(user, title="Backend Developer", company="Acme")
    tm = ToolManager()
    obs, rec = tm.execute(tm.create_command({"tool": "search_jobs", "args": {"query": "acme backend"}}, user, run))
    assert obs.ok and run.context["focus_job_id"] and not obs.candidates
    make_job(user, title="Backend Intern", company="Acme Labs", location="Ottawa")
    obs, _ = tm.execute(SearchJobsCommand(user, {"query": "acme"}, run))
    assert len(obs.candidates) == 2
    assert rec.command_name == "search_jobs" and rec.status == "EXECUTED"


def test_focus_reference_and_argument_validation(user, run):
    tm = ToolManager()
    run.context["focus_job_id"] = "abc"
    cmd = tm.create_command({"tool": "match_job", "args": {"job_id": "@focus_job"}}, user, run)
    assert cmd.args == {"job_id": "abc"}
    with pytest.raises(ValidationFailed):
        tm.create_command({"tool": "match_job", "args": {}}, user, AgentRun(user=user, goal_text="x"))
    with pytest.raises(ValidationFailed):
        tm.create_command({"tool": "submit_application", "args": {}}, user, run)


def test_failed_tool_is_logged_not_raised(user, run):
    tm = ToolManager()
    obs, rec = tm.execute(tm.create_command({"tool": "analyze_job", "args": {"job_id": "nope"}}, user, run))
    assert not obs.ok and rec.status == AgentActionRecord.FAILED


def test_rank_shortlists_and_undo_restores(user, run):
    make_profile(user)
    jobs = [make_job(user), make_job(user, title="Retail Clerk", company="Shop", description="Sell things. " * 40,
                                     work_term="")]
    for j in jobs:
        MatchingService().match_job(user, j.id)
    tm = ToolManager()
    obs, rec = tm.execute(RankJobsCommand(user, {"job_ids": [str(j.id) for j in jobs]}, run))
    assert [s["label"] for s in obs.data["shortlist"]] == ["Backend Developer Co-op @ Acme Corp"]
    jobs[0].refresh_from_db()
    assert jobs[0].shortlisted
    tm.undo(user, rec.id)
    jobs[0].refresh_from_db()
    assert not jobs[0].shortlisted and AgentActionRecord.objects.get(pk=rec.id).status == "UNDONE"


def test_status_command_undo(user, run):
    job = make_job(user)
    tm = ToolManager()
    obs, rec = tm.execute(UpdateApplicationStatusCommand(user, {"job_id": str(job.id), "action": "mark_prepared"}, run))
    assert Application.objects.get(job=job).state_name == "PREPARED"
    assert Application.objects.get(job=job).changes.first().source == "AGENT"
    tm.undo(user, rec.id)
    assert Application.objects.get(job=job).state_name == "SAVED"


def test_approval_policy(user):
    policy = ApprovalPolicy()
    triage = AgentRun(user=user, goal_type="TRIAGE_BATCH", goal_text="")
    free = AgentRun(user=user, goal_type="FREE_FORM", goal_text="")
    assert not policy.requires_approval(SearchJobsCommand(user, {}), free)
    assert policy.requires_approval(BuildPackageCommand(user, {}), free)
    assert policy.requires_approval(BuildPackageCommand(user, {}), triage)
    assert not policy.requires_approval(RankJobsCommand(user, {}), triage)
    assert policy.requires_approval(RankJobsCommand(user, {}), free)


def test_memory_recall_ranks_by_relevance(user):
    mm = MemoryManager()
    mm.remember(user, "EPISODIC", "Prepared package for Acme backend co-op")
    mm.remember(user, "EPISODIC", "Triaged 12 jobs from York")
    mm.remember(user, "PREFERENCE", "Not interested in QA roles")
    hits = [m.content for m in mm.recall(user, "acme backend")]
    assert hits[0].startswith("Prepared package for Acme") and "Not interested in QA roles" in hits
