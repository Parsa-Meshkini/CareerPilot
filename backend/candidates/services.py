import re

from careerpilot.errors import ValidationFailed

from .models import DEFAULT_WEIGHTS, CareerGoal, ProfileFact
from .repository import CandidateRepository

# required fields first, then optional ones; list fields are noted separately
FACT_FIELDS = {
    ProfileFact.EDUCATION: (["institution", "program"], ["credential", "start_date", "end_date", "gpa"]),
    ProfileFact.EXPERIENCE: (["organization", "title"], ["start_date", "end_date", "location", "bullets"]),
    ProfileFact.PROJECT: (["name"], ["description", "technologies", "url", "bullets"]),
    ProfileFact.SKILL: (["name"], ["category", "proficiency"]),
    ProfileFact.CERTIFICATION: (["name"], ["issuer", "issued_on"]),
}
LIST_FIELDS = {"bullets", "technologies"}
DATE_RE = re.compile(r"^\d{4}(-\d{2}(-\d{2})?)?$")


def validate_fact(fact_type, data):
    """Returns cleaned data or raises ValidationFailed with per-field errors."""
    if fact_type not in FACT_FIELDS:
        raise ValidationFailed(f"unknown fact type '{fact_type}'", fields={"fact_type": "unknown type"})
    required, optional = FACT_FIELDS[fact_type]
    errors, clean = {}, {}
    for name in required + optional:
        value = data.get(name)
        if name in LIST_FIELDS:
            if isinstance(value, str):
                value = [v.strip() for v in value.split("\n" if name == "bullets" else ",") if v.strip()]
            clean[name] = [str(v).strip() for v in (value or []) if str(v).strip()]
            continue
        value = "" if value is None else str(value).strip()
        if name in required and not value:
            errors[name] = "required"
        if value and name.endswith(("_date", "_on")) and not DATE_RE.match(value):
            errors[name] = "use YYYY, YYYY-MM or YYYY-MM-DD"
        clean[name] = value
    start, end = clean.get("start_date"), clean.get("end_date")
    if start and end and DATE_RE.match(start) and DATE_RE.match(end) and end < start:
        errors["end_date"] = "end date is before start date"
    if errors:
        raise ValidationFailed("invalid profile fact", fields=errors)
    return clean


class CandidateService:
    PROFILE_FIELDS = ("full_name", "headline", "summary", "location", "email", "phone", "links")

    def __init__(self, repository=None):
        self.repo = repository or CandidateRepository()

    # ---- F01 profile & facts ----
    def get_profile(self, user):
        return self.repo.find_profile_by_user(user)

    def update_profile(self, user, data):
        profile = self.repo.find_profile_by_user(user)
        for field in self.PROFILE_FIELDS:
            if field in data:
                setattr(profile, field, data[field])
        return self.repo.save_profile(profile)

    def add_fact(self, user, fact_type, data, source=ProfileFact.MANUAL):
        fact = ProfileFact(user=user, fact_type=fact_type, data=validate_fact(fact_type, data), source=source)
        # facts the student types in are verified on save; extracted ones need explicit review
        fact.verified = source == ProfileFact.MANUAL
        self.repo.save_facts([fact])
        return fact

    def update_fact(self, user, fact_id, data):
        fact = self.repo.find_by_id(user, fact_id)
        fact.data = validate_fact(fact.fact_type, {**fact.data, **data})
        if fact.source == ProfileFact.MANUAL:
            fact.verified = True
        return self.repo.save(fact)

    def delete_fact(self, user, fact_id):
        self.repo.delete(user, fact_id)

    def list_facts(self, user, verified=None):
        qs = self.repo.list_by_user(user)
        return qs if verified is None else qs.filter(verified=verified)

    def verify_facts(self, user, fact_ids, edits=None):
        """Marks the given facts verified, applying any per-fact edits first. Returns the count."""
        edits = edits or {}
        facts = self.repo.find_facts(user, fact_ids)
        if len(facts) != len(set(map(str, fact_ids))):
            raise ValidationFailed("some fact ids were not found")
        for fact in facts:
            if str(fact.id) in edits:
                fact.data = validate_fact(fact.fact_type, {**fact.data, **edits[str(fact.id)]})
            fact.verify()
        self.repo.save_facts(facts)
        return len(facts)

    def reject_facts(self, user, fact_ids):
        return self.repo.queryset(user).filter(pk__in=fact_ids, verified=False).delete()[0]

    def completeness_hints(self, user):
        verified = self.list_facts(user, verified=True)
        hints = []
        for fact_type, label in ((ProfileFact.EDUCATION, "education"), (ProfileFact.EXPERIENCE, "experience"),
                                 (ProfileFact.PROJECT, "projects"), (ProfileFact.SKILL, "skills")):
            if not verified.filter(fact_type=fact_type).exists():
                hints.append(f"No verified {label} yet")
        if self.list_facts(user, verified=False).exists():
            hints.append("Some extracted facts are waiting for your review")
        if self.repo.find_goal(user) is None:
            hints.append("Set a career goal so jobs can be scored")
        return hints

    # ---- F03 career goal & strategy ----
    def get_career_goal(self, user):
        return self.repo.find_goal(user)

    def update_career_goal(self, user, data):
        goal_type = data.get("goal_type")
        if goal_type not in CareerGoal.STRATEGY_KEYS:
            raise ValidationFailed(f"unknown goal type '{goal_type}'", fields={"goal_type": "unknown"})
        if goal_type == CareerGoal.COOP and not (data.get("work_term") or "").strip():
            raise ValidationFailed("work term is required for co-op goals", fields={"work_term": "required"})
        score = data.get("min_match_score", 60)
        try:
            score = int(score)
        except (TypeError, ValueError):
            score = -1
        if not 0 <= score <= 100:
            raise ValidationFailed("min_match_score must be 0-100", fields={"min_match_score": "0-100"})

        goal = self.repo.find_goal(user) or CareerGoal(user=user)
        previous_key = None if goal._state.adding else goal.to_strategy_key()
        goal.goal_type = goal_type
        goal.target_roles = _as_list(data.get("target_roles"))
        goal.preferred_locations = _as_list(data.get("preferred_locations"))
        goal.work_term = (data.get("work_term") or "").strip()
        goal.work_model = data.get("work_model") or "ANY"
        goal.min_match_score = score
        strategy_changed = previous_key != goal.to_strategy_key()
        if strategy_changed:
            # a different algorithm starts from its own defaults (Stage 1 F03)
            goal.weights = DEFAULT_WEIGHTS[goal.to_strategy_key()]
        self.repo.save_goal(goal)
        self._mark_matches_outdated(user)
        return goal, strategy_changed

    def _mark_matches_outdated(self, user):
        from django.apps import apps

        if apps.is_installed("jobs"):
            apps.get_model("jobs", "JobMatch").objects.filter(user=user).update(outdated=True)


def _as_list(value):
    if isinstance(value, str):
        return [v.strip() for v in value.split(",") if v.strip()]
    return [str(v).strip() for v in (value or []) if str(v).strip()]
