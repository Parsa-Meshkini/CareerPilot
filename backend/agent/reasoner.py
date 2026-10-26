"""Reasoner: decides what to do after each observation. Rules first; LLM reflection only when rules can't decide."""
from dataclasses import dataclass

from agent.llm.client import get_llm_client
from agent.llm.types import LLMError

CONTINUE, RETRY, REPLAN, ASK_USER, ABORT, FINISH = "CONTINUE", "RETRY", "REPLAN", "ASK_USER", "ABORT", "FINISH"
MAX_REPLANS = 2


@dataclass
class Decision:
    action: str
    rationale: str
    source: str = "rules"


class Reasoner:
    def __init__(self, llm=None):
        self._llm = llm

    @property
    def llm(self):
        return self._llm or get_llm_client()

    def evaluate(self, run, step_index, step, obs):
        last = step_index >= len(run.plan) - 1
        if not obs.ok and "budget" in obs.error.lower():
            return Decision(ABORT, f"{obs.error}; stopping with a partial result")
        if not obs.ok:
            if step.get("attempts", 1) < 2 and "not found" not in obs.error:
                return Decision(RETRY, f"tool error, retrying once: {obs.error}")
            if run.replan_count < MAX_REPLANS:
                return Decision(REPLAN, f"step failed ({obs.error}); replanning the remaining steps")
            return Decision(ABORT, f"step failed after retries and replans: {obs.error}")
        if not run.within_budget():
            return Decision(ABORT, "token budget for this run is used up; stopping with a partial result")
        if obs.candidates:
            return Decision(ASK_USER, f"{len(obs.candidates)} objects fit equally well; asking which one you mean")
        if step["tool"] == "search_jobs" and not obs.data.get("jobs"):
            if run.replan_count < MAX_REPLANS:
                return Decision(REPLAN, "no matching posting; replanning (the posting may need to be imported first)")
            return Decision(FINISH, "no matching posting; import it first, then run the goal again")
        if obs.needs_judgement:
            return self.reflect(run, step, obs, last)
        return Decision(FINISH if last else CONTINUE, "plan complete" if last else "step succeeded")

    def reflect(self, run, step, obs, last):
        try:
            data = self.llm.generate_structured(run.user, "agent.reflect", {
                "goal": run.goal_text, "intent": step.get("intent", ""), "observation": obs.to_dict(),
                "remaining": [s["tool"] for s in run.plan[run.current_step + 1:]]}, run=run).data
        except LLMError:
            return Decision(ASK_USER, "could not judge the result automatically; asking you", "rules")
        action = data.get("action", ASK_USER)
        if action not in (CONTINUE, ASK_USER, FINISH):
            action = ASK_USER
        if action == CONTINUE and last:
            action = FINISH
        return Decision(action, data.get("rationale", "")[:300], "llm")
