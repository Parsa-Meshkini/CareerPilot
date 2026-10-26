"""Agent tools as command objects (P5 Command).

Each AgentCommand captures one tool call with its arguments, can describe
itself for approval prompts, executes against a receiver service, and (when
reversible) can undo itself. ToolManager is the invoker: it validates, runs
and logs every command as an AgentActionRecord. There is deliberately no
command that submits an application, sends email or logs into a portal.
"""
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field

import jsonschema

from careerpilot.errors import DomainError, ValidationFailed

FOCUS_JOB, FOCUS_APP = "@focus_job", "@focus_application"


@dataclass
class Observation:
    ok: bool
    summary: str
    data: dict = field(default_factory=dict)
    candidates: list = field(default_factory=list)   # several plausible objects -> ask the user
    error: str = ""
    needs_judgement: bool = False                     # rules cannot decide; reasoner may reflect
    undo_data: dict = field(default_factory=dict)

    def to_dict(self):
        return {"ok": self.ok, "summary": self.summary, "data": self.data, "candidates": self.candidates,
                "error": self.error, "needs_judgement": self.needs_judgement}


class AgentCommand(ABC):
    name = ""
    description = ""
    args_schema = {"type": "object", "properties": {}}
    side_effecting = False
    reversible = False
    estimated_tokens = 0

    def __init__(self, user, args=None, run=None):
        self.user, self.args, self.run = user, dict(args or {}), run

    @classmethod
    def spec(cls):
        return {"name": cls.name, "description": cls.description, "args_schema": cls.args_schema,
                "side_effecting": cls.side_effecting, "reversible": cls.reversible}

    def describe(self):
        shown = ", ".join(f"{k}={v}" for k, v in self.args.items())
        return f"{self.name}({shown})"

    @abstractmethod
    def execute(self) -> Observation: ...

    def undo(self, undo_data):
        raise DomainError(f"{self.name} cannot be undone")

    # helpers shared by commands that work on a job/application chosen earlier in the run
    def job(self):
        from jobs.repository import JobRepository

        return JobRepository().find_by_id(self.user, self.args["job_id"])

    def set_focus(self, job_id=None, application_id=None):
        if self.run is None:
            return
        if job_id:
            self.run.context["focus_job_id"] = str(job_id)
        if application_id:
            self.run.context["focus_application_id"] = str(application_id)


JOB_ID = {"type": "string", "description": "job id, or @focus_job for the job chosen earlier in this run"}


class SearchJobsCommand(AgentCommand):
    name = "search_jobs"
    description = "Find the student's saved job postings whose title/company/location match a query."
    args_schema = {"type": "object", "required": ["query"],
                   "properties": {"query": {"type": "string", "minLength": 1}}}

    STOP = {"the", "a", "an", "my", "for", "to", "of", "at", "in", "and", "job", "jobs", "posting", "application",
            "prepare", "apply", "help", "me", "get", "ready", "please", "role", "position"}

    def execute(self):
        from django.db.models import Q

        from jobs.repository import JobRepository

        words = [w for w in re.findall(r"[a-z0-9+#.-]+", self.args["query"].lower()) if w not in self.STOP and len(w) > 1]
        base = JobRepository().list_jobs(self.user)
        hits = {}
        for word in words:  # rank postings by how many query words they match
            for job in base.filter(Q(title__icontains=word) | Q(company__icontains=word) | Q(location__icontains=word)):
                hits.setdefault(job.id, [job, 0])[1] += 1
        best = max((n for _, n in hits.values()), default=0)
        jobs = [j for j, n in hits.values() if n == best][:10]
        found = [{"id": str(j.id), "label": f"{j.title} @ {j.company}"} for j in jobs]
        if len(jobs) == 1:
            self.set_focus(job_id=jobs[0].id)
            return Observation(True, f"1 posting found ({found[0]['label']})", {"jobs": found})
        if not jobs:
            return Observation(True, f"no saved postings match '{self.args['query']}'", {"jobs": []})
        return Observation(True, f"{len(jobs)} postings match equally well", {"jobs": found}, candidates=found)


class RetrieveContextCommand(AgentCommand):
    name = "retrieve_context"
    description = "Load the student's verified profile summary, goal and relevant memories."
    args_schema = {"type": "object", "properties": {"topic": {"type": "string"}}}

    def execute(self):
        from .context import ContextBuilder
        from .memory import MemoryManager

        ctx = ContextBuilder().build_candidate_context(self.user, require_goal=False)
        memories = MemoryManager().recall(self.user, self.args.get("topic", "") or (self.run.goal_text if self.run else ""),
                                          limit=5)
        return Observation(True, ctx.summary_text(), {"memories": [m.content for m in memories],
                                                      "verified_facts": len(ctx.facts)})


class AnalyzeJobCommand(AgentCommand):
    name = "analyze_job"
    description = "Run AI analysis of a posting (skills, responsibilities, seniority, red flags)."
    args_schema = {"type": "object", "required": ["job_id"], "properties": {"job_id": JOB_ID}}
    estimated_tokens = 2500

    def execute(self):
        from jobs.analysis_service import JobAnalysisService

        a = JobAnalysisService().analyze_job(self.user, self.args["job_id"], run=self.run)
        note = " (keyword fallback)" if a.is_fallback else ""
        return Observation(True, f"requires {', '.join(a.required_skills[:6]) or 'no clear skills'}{note}",
                           {"required_skills": a.required_skills, "red_flags": a.red_flags, "partial": a.partial})


class MatchJobCommand(AgentCommand):
    name = "match_job"
    description = "Score the fit between the student and a posting using the goal's strategy."
    args_schema = {"type": "object", "required": ["job_id"],
                   "properties": {"job_id": JOB_ID, "deep": {"type": "boolean"}}}
    estimated_tokens = 1500

    def execute(self):
        from jobs.matching import MatchingService

        m = MatchingService().match_job(self.user, self.args["job_id"], deep=self.args.get("deep", True), run=self.run)
        minimum = self.user.career_goal.min_match_score
        obs = Observation(True, f"score {m.score} ({m.strategy_key}); missing: {', '.join(m.missing_skills) or 'none'}",
                          {"score": m.score, "min_match_score": minimum, "missing": m.missing_skills,
                           "notes": m.constraint_notes})
        obs.needs_judgement = m.score < minimum  # weak fit: should the agent keep preparing this one?
        return obs


class RankJobsCommand(AgentCommand):
    name = "rank_jobs"
    description = "Rank jobs by match score and mark the top ones as the shortlist."
    args_schema = {"type": "object", "required": ["job_ids"],
                   "properties": {"job_ids": {"type": "array", "items": {"type": "string"}},
                                  "top_k": {"type": "integer", "minimum": 1, "maximum": 50}}}
    side_effecting = True
    reversible = True

    def execute(self):
        from jobs.models import JobMatch, JobPosting

        top_k = self.args.get("top_k", 10)
        jobs = JobPosting.objects.filter(user=self.user, pk__in=self.args["job_ids"])
        scores = dict(JobMatch.objects.filter(job__in=jobs).values_list("job_id", "score"))
        minimum = self.user.career_goal.min_match_score
        ranked = sorted(jobs, key=lambda j: scores.get(j.id, -1), reverse=True)
        before = {str(j.id): [j.shortlisted, j.shortlist_reason] for j in jobs}
        shortlist = []
        for j in ranked:
            s = scores.get(j.id)
            pick = s is not None and s >= minimum and len(shortlist) < top_k
            j.shortlisted = pick
            j.shortlist_reason = f"score {s} >= your minimum {minimum}" if pick else ""
            if pick:
                shortlist.append({"id": str(j.id), "label": f"{j.title} @ {j.company}", "score": s,
                                  "deadline": j.deadline.isoformat() if j.deadline else None})
            j.save(update_fields=["shortlisted", "shortlist_reason"])
        return Observation(True, f"{len(shortlist)} of {len(ranked)} shortlisted", {"shortlist": shortlist},
                           undo_data={"before": before})

    def undo(self, undo_data):
        from jobs.models import JobPosting

        for jid, (flag, reason) in undo_data.get("before", {}).items():
            JobPosting.objects.filter(user=self.user, pk=jid).update(shortlisted=flag, shortlist_reason=reason)


class BuildPackageCommand(AgentCommand):
    name = "build_package"
    description = "Generate the tailored resume, cover letter and application package ZIP for a job."
    args_schema = {"type": "object", "required": ["job_id"],
                   "properties": {"job_id": JOB_ID, "tone": {"type": "string", "enum": ["formal", "warm"]}}}
    side_effecting = True
    reversible = False   # documents are versioned; older versions remain available
    estimated_tokens = 6000

    def execute(self):
        from applications.services import ApplicationService
        from documents.package import PackageAssembler

        app = ApplicationService().get_or_create_for_job(self.user, self.args["job_id"])
        pkg = PackageAssembler().build_package(self.user, app.id, tone=self.args.get("tone", "formal"), run=self.run)
        self.set_focus(application_id=app.id)
        review = " (some content flagged for review)" if pkg.needs_review else ""
        return Observation(True, f"package {pkg.folder_name}.zip ready{review}",
                           {"package_id": str(pkg.id), "application_id": str(app.id), "needs_review": pkg.needs_review})


class UpdateApplicationStatusCommand(AgentCommand):
    name = "update_application_status"
    description = "Move an application through its lifecycle (e.g. mark_prepared, withdraw). Never submits it."
    args_schema = {"type": "object", "required": ["action"],
                   "properties": {"application_id": {"type": "string"}, "job_id": JOB_ID,
                                  "action": {"type": "string", "enum": ["mark_prepared", "schedule_interview",
                                                                        "receive_offer", "reject", "withdraw"]}}}
    side_effecting = True
    reversible = True

    def execute(self):
        from applications.models import StatusChange
        from applications.services import ApplicationService

        svc = ApplicationService()
        app_id = self.args.get("application_id") or svc.get_or_create_for_job(self.user, self.args["job_id"]).id
        change = svc.transition(self.user, app_id, self.args["action"], source=StatusChange.AGENT,
                                reason=f"agent run {self.run.id}" if self.run else "agent")
        self.set_focus(application_id=app_id)
        return Observation(True, f"application {change.from_state} -> {change.to_state}",
                           {"application_id": str(app_id), "to_state": change.to_state},
                           undo_data={"change_id": str(change.id)})

    def undo(self, undo_data):
        from applications.services import ApplicationService

        ApplicationService().undo_transition(self.user, undo_data["change_id"])


class ToolManager:
    """Invoker: catalog, argument validation, execution with logging, undo."""

    def __init__(self, commands=None):
        classes = commands or [SearchJobsCommand, RetrieveContextCommand, AnalyzeJobCommand, MatchJobCommand,
                               RankJobsCommand, BuildPackageCommand, UpdateApplicationStatusCommand]
        self.catalog = {c.name: c for c in classes}

    def register(self, command_cls):
        self.catalog[command_cls.name] = command_cls

    def list_tools(self):
        return [c.spec() for c in self.catalog.values()]

    def resolve_args(self, args, run):
        ctx = run.context if run else {}
        out = {}
        for k, v in (args or {}).items():
            if v == FOCUS_JOB:
                v = ctx.get("focus_job_id")
            elif v == FOCUS_APP:
                v = ctx.get("focus_application_id")
            if v is not None:
                out[k] = v
        return out

    def validate_args(self, tool, args):
        cls = self.catalog.get(tool)
        if cls is None:
            raise ValidationFailed(f"unknown tool '{tool}'")
        try:
            jsonschema.validate(args, cls.args_schema)
        except jsonschema.ValidationError as exc:
            raise ValidationFailed(f"bad arguments for {tool}: {exc.message}")
        return cls

    def create_command(self, step, user, run=None):
        args = self.resolve_args(step.get("args"), run)
        cls = self.validate_args(step["tool"], args)
        return cls(user, args, run)

    def execute(self, command, step_index=0):
        from .models import AgentActionRecord

        try:
            obs = command.execute()
        except DomainError as exc:
            obs = Observation(False, f"{command.name} failed", error=exc.message)
        except Exception as exc:  # tool bugs must not crash the run; the reasoner decides what to do
            obs = Observation(False, f"{command.name} failed", error=str(exc)[:300])
        record = AgentActionRecord.objects.create(
            user=command.user, run=command.run, step_index=step_index, command_name=command.name,
            arguments=command.args, description=command.describe(), observation_summary=obs.summary,
            observation=obs.to_dict(), reversible=command.reversible and obs.ok, undo_data=obs.undo_data,
            status=AgentActionRecord.EXECUTED if obs.ok else AgentActionRecord.FAILED)
        return obs, record

    def record_rejection(self, command, step_index, reason=""):
        from .models import AgentActionRecord

        return AgentActionRecord.objects.create(
            user=command.user, run=command.run, step_index=step_index, command_name=command.name,
            arguments=command.args, description=command.describe(), status=AgentActionRecord.REJECTED,
            observation_summary=f"rejected by the student{': ' + reason if reason else ''}")

    def undo(self, user, record_id):
        from .models import AgentActionRecord

        record = AgentActionRecord.objects.filter(user=user, pk=record_id).first()
        if record is None:
            from careerpilot.errors import NotFoundError

            raise NotFoundError("action not found")
        if not record.reversible or record.status != AgentActionRecord.EXECUTED:
            raise DomainError("this action cannot be undone")
        cls = self.catalog[record.command_name]
        cls(user, record.arguments, record.run).undo(record.undo_data)
        record.mark_undone()
        record.save()
        return record


class ApprovalPolicy:
    """Decides which commands pause the run for the student's approval."""

    # reversible, low-risk tools that a goal type may run without asking
    AUTO_APPROVED = {"TRIAGE_BATCH": {"rank_jobs"}}

    def requires_approval(self, command, run):
        if not command.side_effecting:
            return False
        return command.name not in self.AUTO_APPROVED.get(run.goal_type if run else "", set())
