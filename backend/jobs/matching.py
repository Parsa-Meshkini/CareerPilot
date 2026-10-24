"""Candidate-job matching (F08) with interchangeable scoring algorithms (P2 Strategy).

MatchingService is the context: it picks a MatchScoringStrategy from the
student's CareerGoal and delegates scoring to it. Each concrete strategy is a
different algorithm, not just different weights.
"""
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field

from agent.context import ContextBuilder
from agent.llm.client import get_llm_client
from agent.llm.types import LLMBudgetExceeded, LLMError
from careerpilot.errors import ValidationFailed

from .analysis_service import JobAnalysisService
from .models import JobAnalysis, JobMatch
from .repository import JobRepository
from .skills import canonical, find_skills


@dataclass
class MatchResult:
    score: int
    strategy_key: str
    breakdown: dict
    matched_skills: list
    missing_skills: list
    transferable: list = field(default_factory=list)
    constraint_notes: list = field(default_factory=list)


@dataclass
class QuickAnalysis:
    """Analysis-shaped view built from keywords when no JobAnalysis exists (quick score)."""

    required_skills: list
    preferred_skills: list = field(default_factory=list)
    seniority: str = ""
    years_required: int | None = None
    red_flags: list = field(default_factory=list)


class MatchScoringStrategy(ABC):
    @abstractmethod
    def key(self) -> str: ...

    @abstractmethod
    def score(self, ctx, job, analysis) -> MatchResult: ...

    # shared building blocks
    def skill_coverage(self, ctx, analysis, credit=None):
        credit = credit or {}
        required, preferred = analysis.required_skills, analysis.preferred_skills
        matched = [s for s in required + preferred if s in ctx.skills]
        missing = [s for s in required if s not in ctx.skills]
        total = len(required) + 0.5 * len(preferred)
        if total == 0:
            return 0.25, matched, missing  # no detectable skills: fit is unknown, so it earns little
        got = sum(1 for s in required if s in ctx.skills) + 0.5 * sum(1 for s in preferred if s in ctx.skills)
        got += sum(credit.get(s, 0) for s in missing)
        return min(1.0, got / total), matched, missing

    def preference_fit(self, ctx, job):
        goal, parts = ctx.goal, []
        roles = [r.lower() for r in goal.target_roles]
        title = f"{job.title} {job.job_type}".lower()
        parts.append(1.0 if not roles else (1.0 if any(r in title for r in roles) else 0.3))
        locs = [l.lower() for l in goal.preferred_locations]
        parts.append(1.0 if not locs or job.work_model == "REMOTE" or any(l in job.location.lower() for l in locs)
                     else 0.2)
        wm = goal.work_model
        parts.append(1.0 if wm == "ANY" or job.work_model in (wm, "UNKNOWN") else 0.4)
        disliked = [p.lower() for p in ctx.preferences]
        if any(word and word in title for p in disliked for word in re.findall(r"not interested in (\w+)", p)):
            parts.append(0.0)
        return sum(parts) / len(parts)

    def combine(self, ctx, factors):
        w = ctx.weights.normalized()
        return int(round(100 * sum(w[k] * factors[k] for k in w)))


class CoopInternshipStrategy(MatchScoringStrategy):
    """Co-op/internship: the work term is a hard constraint; seniority must suit a student."""

    def key(self):
        return "coop_internship"

    def check_work_term(self, ctx, job):
        want = _term_tokens(ctx.goal.work_term)
        have = _term_tokens(job.work_term or job.description[:1500])
        if not want or not have:
            return None  # unknown -> neutral
        return bool(want & have)

    def score(self, ctx, job, analysis):
        coverage, matched, missing = self.skill_coverage(ctx, analysis)
        notes = []
        term_ok = self.check_work_term(ctx, job)
        constraint = 0.6 if term_ok is None else (1.0 if term_ok else 0.0)
        if term_ok is False:
            notes.append(f"Work term mismatch: you want {ctx.goal.work_term}, posting is {job.work_term or 'different'}")
        student_role = re.search(r"co-?op|intern|student|summer", f"{job.title} {job.job_type}".lower())
        experience = 1.0 if student_role or analysis.seniority in ("intern", "junior", "") else 0.3
        if analysis.seniority == "senior":
            experience = 0.0
            notes.append("Posting is a senior role")
        if analysis.red_flags:
            constraint = max(0.0, constraint - 0.2)
        result = self.combine(ctx, {"skill": coverage, "experience": experience,
                                    "preference": self.preference_fit(ctx, job), "constraint": constraint})
        if term_ok is False:
            result = min(result, 45)  # hard constraint caps the score
        return MatchResult(result, self.key(), _breakdown(coverage, experience, self.preference_fit(ctx, job),
                                                          constraint), matched, missing, constraint_notes=notes)


class EntryLevelStrategy(MatchScoringStrategy):
    """New grad: skill coverage plus seniority/years-of-experience fit."""

    def key(self):
        return "entry_level"

    def score(self, ctx, job, analysis):
        coverage, matched, missing = self.skill_coverage(ctx, analysis)
        notes = []
        years_have = _years_of_experience(ctx)
        years_need = analysis.years_required or 0
        experience = 1.0 if years_need <= max(years_have, 1) else max(0.0, 1 - (years_need - years_have) / 5)
        if analysis.seniority in ("senior", "lead", "principal"):
            experience = min(experience, 0.2)
            notes.append("Posting is above entry level")
        if years_need > 2:
            notes.append(f"Asks for {years_need}+ years; you have about {years_have}")
        constraint = 1.0 - min(1.0, 0.35 * len(analysis.red_flags))
        pref = self.preference_fit(ctx, job)
        return MatchResult(self.combine(ctx, {"skill": coverage, "experience": experience, "preference": pref,
                                              "constraint": constraint}),
                           self.key(), _breakdown(coverage, experience, pref, constraint), matched, missing,
                           constraint_notes=notes)


class CareerSwitchStrategy(MatchScoringStrategy):
    """Career switch: direct overlap under-rates switchers, so the LLM maps transferable skills
    from verified facts onto missing requirements; each mapping earns partial credit."""

    def __init__(self, llm=None, use_llm=True):
        self.llm, self.use_llm = llm, use_llm

    def key(self):
        return "career_switch"

    def map_transferable_skills(self, ctx, analysis, missing):
        if not missing or not self.use_llm:
            return []
        try:
            data = (self.llm or get_llm_client()).generate_structured(ctx.user, "match.transferable", {
                "missing_skills": missing, "facts": ctx.facts_for_prompt(25)}).data
        except LLMBudgetExceeded:
            raise
        except LLMError:
            return []
        valid_ids = ctx.fact_index()
        out = []
        for m in data.get("mappings", []):
            skill = canonical(m.get("required_skill", ""))
            ids = [i for i in m.get("fact_ids", []) if i in valid_ids]
            if skill in missing and ids:  # only mappings grounded in the student's own facts count
                out.append({"skill": skill, "fact_ids": ids, "reason": m.get("reason", "")[:200]})
        return out

    def score(self, ctx, job, analysis):
        _, _, missing = self.skill_coverage(ctx, analysis)
        transferable = self.map_transferable_skills(ctx, analysis, missing)
        credit = {t["skill"]: 0.5 for t in transferable}
        coverage, matched, missing = self.skill_coverage(ctx, analysis, credit)
        experience = 0.7 if analysis.seniority in ("junior", "intern", "") else 0.4
        pref = self.preference_fit(ctx, job)
        constraint = 1.0 - min(1.0, 0.35 * len(analysis.red_flags))
        return MatchResult(self.combine(ctx, {"skill": coverage, "experience": experience, "preference": pref,
                                              "constraint": constraint}),
                           self.key(), _breakdown(coverage, experience, pref, constraint), matched, missing,
                           transferable=transferable)


class MatchingService:
    def __init__(self, llm=None, context_builder=None, analysis_service=None, jobs=None, strategies=None):
        self._llm = llm
        self.contexts = context_builder or ContextBuilder()
        self.analysis = analysis_service or JobAnalysisService(llm)
        self.jobs = jobs or JobRepository()
        self.strategies = strategies or {s.key(): s for s in (CoopInternshipStrategy(), EntryLevelStrategy(),
                                                              CareerSwitchStrategy(llm))}

    @property
    def llm(self):
        return self._llm or get_llm_client()

    def select_strategy(self, goal, deep=True):
        strategy = self.strategies.get(goal.to_strategy_key())
        if strategy is None:
            raise ValidationFailed(f"no matching strategy for goal type {goal.goal_type}")
        if isinstance(strategy, CareerSwitchStrategy) and not deep:
            return CareerSwitchStrategy(use_llm=False)  # quick scores never spend LLM tokens
        return strategy

    def match_job(self, user, job_id, deep=False, run=None, ctx=None):
        ctx = ctx or self.contexts.build_candidate_context(user)
        job = self.jobs.find_by_id(user, job_id)
        analysis = JobAnalysis.objects.filter(job=job).first()
        if deep and analysis is None:
            analysis = self.analysis.analyze_job(user, job.id, run=run)
        view = analysis or self.quick_analysis(job)
        result = self.select_strategy(ctx.goal, deep=deep).score(ctx, job, view)
        match, _ = JobMatch.objects.get_or_create(user=user, job=job, defaults={"score": 0})
        for attr in ("score", "strategy_key", "breakdown", "matched_skills", "missing_skills", "transferable",
                     "constraint_notes"):
            setattr(match, attr, getattr(result, attr))
        match.is_deep_analysis = analysis is not None
        match.outdated = False
        if deep:
            self.explain_match(match, view, ctx, run)
        match.save()
        return match

    def quick_analysis(self, job):
        text = f"{job.title} {job.description}"
        data = JobAnalysisService.fallback_keyword_analysis(None, text)
        return QuickAnalysis(required_skills=data["required_skills"], seniority=data["seniority"],
                             years_required=data["years_required"])

    def explain_match(self, match, analysis, ctx, run=None):
        try:
            data = self.llm.generate_structured(ctx.user, "match.explain", {
                "job": f"{match.job.title} at {match.job.company}", "score": match.score,
                "strategy": match.strategy_key, "breakdown": match.breakdown, "matched": match.matched_skills,
                "missing": match.missing_skills, "notes": match.constraint_notes,
                "candidate": ctx.summary_text()}, run=run).data
        except LLMError:
            return match  # the score stands on its own without the narrative
        match.explanation = data.get("explanation", "")
        match.suggestions = data.get("suggestions", [])[:6]
        return match

    def rank_jobs(self, user, job_ids, deep_ids=()):
        ctx = self.contexts.build_candidate_context(user)
        matches = [self.match_job(user, jid, deep=str(jid) in set(map(str, deep_ids)), ctx=ctx) for jid in job_ids]
        return sorted(matches, key=lambda m: m.score, reverse=True)


def _breakdown(skill, experience, preference, constraint):
    return {"skill": round(skill, 2), "experience": round(experience, 2), "preference": round(preference, 2),
            "constraint": round(constraint, 2)}


def _term_tokens(text):
    text = (text or "").lower()
    seasons = set(re.findall(r"\b(winter|summer|fall|autumn|spring)\b", text))
    seasons = {"fall" if s == "autumn" else s for s in seasons}
    years = set(re.findall(r"\b(20\d\d)\b", text))
    return {f"{s}-{y}" for s in seasons for y in years} if seasons and years else set()


def _years_of_experience(ctx):
    months = 0
    for f in ctx.facts_of("EXPERIENCE"):
        start, end = f.data.get("start_date", ""), f.data.get("end_date", "")
        try:
            sy, sm = int(start[:4]), int(start[5:7] or 1) if len(start) >= 7 else 1
            if end:
                ey, em = int(end[:4]), int(end[5:7]) if len(end) >= 7 else 12
            else:
                from datetime import date

                ey, em = date.today().year, date.today().month
            months += max(0, (ey - sy) * 12 + em - sm)
        except ValueError:
            continue
    return round(months / 12)


__all__ = ["MatchingService", "MatchScoringStrategy", "CoopInternshipStrategy", "EntryLevelStrategy",
           "CareerSwitchStrategy", "find_skills"]
