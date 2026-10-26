"""AgentController: owns the run lifecycle (plan -> act -> observe -> decide), limits and approvals."""
import re

from django.conf import settings

from careerpilot.errors import ConflictError, NotFoundError, ValidationFailed

from . import reasoner as R
from .commands import ApprovalPolicy, Observation, ToolManager
from .context import ContextBuilder
from .memory import MemoryManager
from .models import AgentActionRecord, AgentMemoryEntry, AgentRun
from .planner import MAX_STEPS, Planner

FORBIDDEN = re.compile(r"\b(submit|send (it|the|an?)|apply (for|on behalf)|log ?in|sign in|email the)\b", re.I)
REFUSAL = ("CareerPilot never submits applications, sends email or logs into portals for you; "
           "it prepared everything else so you can submit it yourself.")


def classify_goal(text):
    t = text.lower()
    if re.search(r"\btriage|shortlist|sort (my|the) (jobs|batch)\b", t):
        return "TRIAGE_BATCH"
    if re.search(r"\b(prepare|package|resume|cover letter|tailor|apply|application)\b", t):
        return "PREPARE_APPLICATION"
    return "FREE_FORM"


class AgentController:
    def __init__(self, tools=None, planner=None, reasoner=None, policy=None, memory=None, contexts=None):
        self.tools = tools or ToolManager()
        self.planner = planner or Planner(self.tools)
        self.reasoner = reasoner or R.Reasoner()
        self.policy = policy or ApprovalPolicy()
        self.memory = memory or MemoryManager()
        self.contexts = contexts or ContextBuilder()

    # ---------------- entry points ----------------
    def run_goal(self, user, goal_text, goal_type=None, job_id=None, context=None):
        goal_text = (goal_text or "").strip()
        if not goal_text:
            raise ValidationFailed("describe your goal", fields={"goal": "required"})
        goal_type = goal_type or classify_goal(goal_text)
        if goal_type not in AgentRun.GOAL_TYPES:
            raise ValidationFailed(f"unknown goal type {goal_type}")
        run = AgentRun.objects.create(user=user, goal_text=goal_text[:2000], goal_type=goal_type,
                                      token_budget=settings.LLM_RUN_TOKEN_BUDGET, context=dict(context or {}))
        if job_id:
            from jobs.repository import JobRepository

            run.context["focus_job_id"] = str(JobRepository().find_by_id(user, job_id).id)
        if FORBIDDEN.search(goal_text):
            run.context["refusal"] = REFUSAL
        ctx = self.contexts.build_candidate_context(user, require_goal=False, require_facts=False)
        memories = [m.content for m in self.memory.recall(user, goal_text, limit=10)]
        run.context["memories_used"] = memories
        run.plan, run.plan_source = self.planner.create_plan(run, ctx.summary_text(), memories)
        run.status = AgentRun.RUNNING
        run.save()
        return self._advance(run)

    def resume_run(self, user, run_id, approve, reason=""):
        run = self._active(user, run_id, AgentRun.AWAITING_APPROVAL)
        index = run.pending["step"]
        step = run.plan[index]
        run.pending, run.status = {}, AgentRun.RUNNING
        if approve:
            step["approved"] = True
            run.save()
            return self._advance(run)
        cmd = self.tools.create_command(step, user, run)
        self.tools.record_rejection(cmd, index, reason)
        step["status"] = "rejected"
        self.memory.remember(user, AgentMemoryEntry.PREFERENCE,
                             f"Rejected agent step {cmd.describe()} for goal '{run.goal_text}'"
                             + (f": {reason}" if reason else ""), importance=0.6)
        run.record_decision(index, R.REPLAN if run.replan_count < R.MAX_REPLANS else R.FINISH,
                            "the student rejected this step")
        if run.replan_count < R.MAX_REPLANS:
            obs = Observation(False, "student rejected the step", error=f"rejected: {reason or 'no reason given'}")
            if self._replan(run, index, obs):
                return self._advance(run)
        return self._finish(run)

    def answer(self, user, run_id, choice):
        run = self._active(user, run_id, AgentRun.AWAITING_INPUT)
        options = {o["id"]: o for o in run.pending.get("options", [])}
        if choice == "stop":
            return self._finish(run, note="Stopped at your request.")
        if choice not in options and choice != "continue":
            raise ValidationFailed("pick one of the offered options")
        if choice in options:
            run.context["focus_job_id"] = choice
        run.plan[run.pending["step"]]["status"] = "done"
        run.current_step = run.pending["step"] + 1
        run.pending, run.status = {}, AgentRun.RUNNING
        run.save()
        return self._advance(run)

    def undo_action(self, user, record_id):
        return self.tools.undo(user, record_id)

    # ---------------- loop ----------------
    def _advance(self, run):
        while run.current_step < len(run.plan):
            index = run.current_step
            step = run.plan[index]
            if run.step_count >= MAX_STEPS:
                return self._abort(run, f"step limit ({MAX_STEPS}) reached")
            try:
                cmd = self.tools.create_command(step, run.user, run)
            except ValidationFailed as exc:
                cmd, obs = None, Observation(False, f"{step['tool']} could not start", error=exc.message)
            else:
                if self.policy.requires_approval(cmd, run) and not step.get("approved"):
                    run.await_approval(index, cmd.describe() + f" - {cmd.description}"
                                       + (f" (~{cmd.estimated_tokens} tokens)" if cmd.estimated_tokens else ""))
                    run.save()
                    return run
                obs, _ = self.tools.execute(cmd, index)
                run.step_count += 1
            step["attempts"] = step.get("attempts", 0) + 1
            step["observation"] = obs.summary if obs.ok else f"{obs.summary}: {obs.error}"
            decision = self.reasoner.evaluate(run, index, step, obs)
            run.record_decision(index, decision.action, decision.rationale, decision.source)
            step["status"] = "done" if obs.ok else "failed"

            if decision.action == R.RETRY:
                run.save()
                continue
            if decision.action == R.CONTINUE:
                run.current_step += 1
            elif decision.action == R.ASK_USER:
                options = obs.candidates or [{"id": "continue", "label": "Continue anyway"},
                                             {"id": "stop", "label": "Stop here"}]
                run.ask_user(index, decision.rationale, options)
                run.save()
                return run
            elif decision.action == R.REPLAN:
                if not self._replan(run, index, obs):
                    return self._finish(run, note="Could not find a better plan; stopping with what was done.")
            elif decision.action == R.ABORT:
                return self._abort(run, decision.rationale)
            else:  # FINISH
                run.current_step = len(run.plan)
            run.save()
        return self._finish(run)

    def _replan(self, run, index, obs):
        completed = [{"tool": s["tool"], "result": s.get("observation", "")} for s in run.plan[:index + 1]]
        steps, rationale = self.planner.replan(run, obs.to_dict(), completed)
        if steps is None:
            return False
        run.replan_count += 1
        run.plan_history.append({"replaced_after_step": index, "old": run.plan[index + 1:], "rationale": rationale})
        run.plan = run.plan[:index + 1] + steps   # completed actions are never re-executed
        run.current_step = index + 1
        run.save()
        return True

    def _abort(self, run, reason):
        run.fail(f"{reason}. Partial result: {self._summary(run)}", status=AgentRun.ABORTED)
        run.save()
        self._remember(run)
        return run

    def _finish(self, run, note=""):
        summary = self._summary(run)
        if note:
            summary = f"{summary} {note}"
        if run.context.get("refusal"):
            summary = f"{summary} {run.context['refusal']}"
        run.complete(summary.strip())
        run.save()
        self._remember(run)
        return run

    def _summary(self, run):
        done = [a for a in run.actions.all() if a.status == AgentActionRecord.EXECUTED]
        if not done:
            return "No actions were taken."
        return "Done: " + "; ".join(f"{a.command_name}: {a.observation_summary}" for a in done) + "."

    def _remember(self, run):
        self.memory.remember(run.user, AgentMemoryEntry.EPISODIC,
                             f"Agent run '{run.goal_text[:120]}' ({run.status.lower()}): {run.summary or run.error}"[:600],
                             related_object_id=run.id, importance=0.4)

    def _active(self, user, run_id, status):
        run = AgentRun.objects.filter(user=user, pk=run_id).first()
        if run is None:
            raise NotFoundError("agent run not found")
        if run.status != status:
            raise ConflictError(f"run is {run.status}, not {status}")
        return run
