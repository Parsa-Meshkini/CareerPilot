"""ContextBuilder: retrieves the grounded candidate context used by matching, generation and the agent."""
from dataclasses import dataclass, field

from careerpilot.errors import DomainError
from candidates.models import ProfileFact
from jobs.skills import canonical, find_skills


class ProfileIncompleteError(DomainError):
    code = "profile_incomplete"


@dataclass
class CandidateContext:
    user: object
    profile: object
    facts: list
    goal: object
    weights: object
    skills: set = field(default_factory=set)
    preferences: list = field(default_factory=list)

    def facts_of(self, fact_type):
        return [f for f in self.facts if f.fact_type == fact_type]

    def fact_index(self):
        return {str(f.id): f for f in self.facts}

    def facts_for_prompt(self, limit=40):
        return [{"id": str(f.id), "type": f.fact_type, "text": f.to_context_text()} for f in self.facts[:limit]]

    def summary_text(self):
        roles = ", ".join(self.goal.target_roles) if self.goal else "-"
        goal = f"{self.goal.goal_type} ({self.goal.work_term or 'any term'}) targeting {roles}" if self.goal else "none"
        return f"Goal: {goal}. Skills: {', '.join(sorted(self.skills)) or 'none'}. Verified facts: {len(self.facts)}."


class ContextBuilder:
    def build_candidate_context(self, user, require_goal=True, require_facts=True):
        from candidates.services import CandidateService

        svc = CandidateService()
        facts = list(svc.list_facts(user, verified=True))
        goal = svc.get_career_goal(user)
        if require_facts and not facts:
            raise ProfileIncompleteError("add or verify at least one profile fact first", missing="facts")
        if require_goal and goal is None:
            raise ProfileIncompleteError("set a career goal first", missing="goal")
        skills = set()
        for f in facts:
            if f.fact_type == ProfileFact.SKILL:
                skills.add(canonical(f.data.get("name", "")))
            for tech in f.data.get("technologies", []):
                skills.add(canonical(tech))
            text = " ".join(f.data.get("bullets", [])) + " " + f.data.get("description", "")
            skills.update(find_skills(text))
        skills.discard("")
        return CandidateContext(user=user, profile=svc.get_profile(user), facts=facts, goal=goal,
                                weights=goal.weights if goal else None, skills=skills,
                                preferences=self._preferences(user))

    def _preferences(self, user):
        from django.apps import apps

        try:
            memory = apps.get_model("agent", "AgentMemoryEntry")
        except LookupError:
            return []
        return list(memory.objects.filter(user=user, kind="PREFERENCE").order_by("-created_at")
                    .values_list("content", flat=True)[:10])
