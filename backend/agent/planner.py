"""Planner and PlaybookLibrary: goal -> validated list of tool calls."""
import re

import jsonschema

from agent.llm.client import get_llm_client
from agent.llm.types import LLMBudgetExceeded, LLMError

from .commands import FOCUS_APP, FOCUS_JOB

MAX_STEPS = 8


class PlanInvalid(Exception):
    pass


def step(tool, intent, **args):
    return {"tool": tool, "args": args, "intent": intent}


class PlaybookLibrary:
    """Hand-written plans per goal type, so the agent always has a correct plan."""

    def get_playbook(self, run):
        job_id = run.context.get("focus_job_id")
        find = [] if job_id else [step("search_jobs", "find the posting named in the goal", query=run.goal_text)]
        if run.goal_type == "PREPARE_APPLICATION":
            return find + [
                step("retrieve_context", "load verified profile and preferences"),
                step("analyze_job", "understand what the posting asks for", job_id=FOCUS_JOB),
                step("match_job", "check fit and skill gaps", job_id=FOCUS_JOB, deep=True),
                step("build_package", "generate tailored documents and the ZIP", job_id=FOCUS_JOB),
            ]
        if run.goal_type == "TRIAGE_BATCH":
            return self.triage_playbook(run)
        return [step("retrieve_context", "load verified profile and preferences", topic=run.goal_text)] + find

    def triage_playbook(self, run):
        return []  # filled in by the triage feature (F09)


class Planner:
    MAX_ATTEMPTS = 2

    def __init__(self, tools, playbooks=None, llm=None):
        self.tools, self.playbooks, self._llm = tools, playbooks or PlaybookLibrary(), llm

    @property
    def llm(self):
        return self._llm or get_llm_client()

    def create_plan(self, run, context_summary, memories):
        """Returns (steps, source). Falls back to the playbook after two invalid LLM plans."""
        if run.goal_type == "TRIAGE_BATCH":
            return self.playbooks.get_playbook(run), "PLAYBOOK"  # fixed algorithm; budget decisions are rule-based
        feedback = ""
        for _ in range(self.MAX_ATTEMPTS):
            try:
                data = self.llm.generate_structured(run.user, "agent.plan", {
                    "goal": run.goal_text, "goal_type": run.goal_type, "context": context_summary,
                    "memories": memories or ["(none)"], "tools": self.tools.list_tools(),
                    "focus": run.context.get("focus_job_id") or "none", "max_steps": MAX_STEPS,
                    "feedback": feedback}, run=run).data
                steps = self.validate_plan(data.get("steps", []), run)
                run.context["plan_notes"] = data.get("notes", "")
                return steps, "LLM"
            except PlanInvalid as exc:
                feedback = f"Your previous plan was invalid: {exc}. Fix it."
            except LLMBudgetExceeded:
                break
            except LLMError:
                break
        return self.playbooks.get_playbook(run), "PLAYBOOK"

    def replan(self, run, observation, completed):
        """Replacement for the remaining steps, or None when no valid replan is possible."""
        try:
            data = self.llm.generate_structured(run.user, "agent.replan", {
                "goal": run.goal_text, "completed": completed, "observation": observation,
                "remaining": run.plan[run.current_step + 1:], "tools": self.tools.list_tools(),
                "budget_left": run.token_budget - run.tokens_used,
                "max_steps": MAX_STEPS - run.step_count}, run=run).data
            steps = data.get("steps", [])
            return self.validate_plan(steps, run, allow_empty=True), data.get("rationale", "")
        except (PlanInvalid, LLMError) as exc:
            return None, str(exc)

    def validate_plan(self, steps, run, allow_empty=False):
        if not isinstance(steps, list) or (not steps and not allow_empty):
            raise PlanInvalid("the plan has no steps")
        if len(steps) > MAX_STEPS:
            raise PlanInvalid(f"the plan has more than {MAX_STEPS} steps")
        clean = []
        for i, s in enumerate(steps):
            tool, args = s.get("tool"), s.get("args") or {}
            cls = self.tools.catalog.get(tool)
            if cls is None:
                raise PlanInvalid(f"step {i + 1} uses unknown tool '{tool}'")
            try:
                jsonschema.validate(args, cls.args_schema)
            except jsonschema.ValidationError as exc:
                raise PlanInvalid(f"step {i + 1} ({tool}) has bad arguments: {exc.message}")
            for key in ("job_id", "application_id"):
                value = args.get(key)
                if value and value not in (FOCUS_JOB, FOCUS_APP) and not self._owned(run.user, key, value):
                    raise PlanInvalid(f"step {i + 1} references a {key} the student does not own")
            for value in args.get("job_ids", []):
                if not self._owned(run.user, "job_id", value):
                    raise PlanInvalid(f"step {i + 1} references a job the student does not own")
            clean.append({"tool": tool, "args": args, "intent": str(s.get("intent", ""))[:200]})
        return clean

    def _owned(self, user, key, value):
        from applications.models import Application
        from jobs.models import JobPosting

        if not re.fullmatch(r"[0-9a-fA-F-]{32,36}", str(value)):
            return False
        model = JobPosting if key == "job_id" else Application
        return model.objects.filter(user=user, pk=value).exists()
