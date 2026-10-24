"""JobAnalysisService (F07): LLM analysis with a deterministic keyword fallback."""
import re

from agent.llm.client import get_llm_client
from agent.llm.types import LLMBudgetExceeded, LLMError

from .models import JobAnalysis, JobPosting
from .repository import JobRepository
from .skills import canonical, find_skills

RED_FLAG_PATTERNS = {
    r"\bunpaid\b": "Unpaid position",
    r"\b([5-9]|1[0-9])\+?\s*years\b": "Asks for many years of experience",
    r"commission[- ]only": "Commission-only pay",
    r"pay (a|an)? ?(fee|deposit)": "Asks the applicant to pay",
}


class JobAnalysisService:
    def __init__(self, llm=None, jobs=None):
        self._llm = llm
        self.jobs = jobs or JobRepository()

    @property
    def llm(self):
        return self._llm or get_llm_client()

    def analyze_job(self, user, job_id, run=None):
        job = self.jobs.find_by_id(user, job_id)
        text = f"{job.title}\n{job.company}\n{job.location}\n{job.description}"
        try:
            result = self.llm.generate_structured(user, "job.analyze", {
                "title": job.title, "company": job.company, "work_term": job.work_term or "n/a",
                "description": job.description[:12000] or "(no description: summary only)"}, run=run)
            data, model = result.data, result.model
            fallback = False
        except LLMBudgetExceeded:
            raise
        except LLMError:
            data, model, fallback = self.fallback_keyword_analysis(text), "keyword-fallback", True
        analysis, _ = JobAnalysis.objects.get_or_create(user=user, job=job)
        analysis.required_skills = _unique(canonical(s) for s in data.get("required_skills", []))
        analysis.preferred_skills = [s for s in _unique(canonical(s) for s in data.get("preferred_skills", []))
                                     if s not in analysis.required_skills]
        analysis.responsibilities = data.get("responsibilities", [])[:12]
        analysis.seniority = str(data.get("seniority", "")).lower()[:30]
        analysis.years_required = data.get("years_required")
        analysis.keywords = data.get("keywords", [])[:20]
        analysis.red_flags = _unique(data.get("red_flags", []) + self._rule_red_flags(text))
        analysis.summary = data.get("summary", "")
        analysis.model_used, analysis.is_fallback = model, fallback
        analysis.partial = job.completeness != JobPosting.FULL
        analysis.save()
        return analysis

    def fallback_keyword_analysis(self, text):
        skills = find_skills(text)
        lowered = text.lower()
        seniority = "senior" if re.search(r"\bsenior|lead|principal\b", lowered) else (
            "intern" if re.search(r"\bintern|co-?op|student\b", lowered) else (
                "junior" if re.search(r"\bjunior|entry|new grad|graduate\b", lowered) else ""))
        years = re.search(r"(\d+)\+?\s*years", lowered)
        bullets = [ln.strip("-•* ").strip() for ln in text.splitlines() if ln.strip().startswith(("-", "•", "*"))]
        return {"required_skills": skills, "preferred_skills": [], "responsibilities": bullets[:8],
                "seniority": seniority, "years_required": int(years.group(1)) if years else None,
                "keywords": skills[:10], "red_flags": [], "summary": ""}

    def _rule_red_flags(self, text):
        lowered = text.lower()
        flags = [label for pat, label in RED_FLAG_PATTERNS.items() if re.search(pat, lowered)]
        if re.search(r"\bintern|co-?op\b", lowered) and "Asks for many years of experience" in flags:
            flags = [f if f != "Asks for many years of experience" else "Student role asking for 5+ years"
                     for f in flags]
        return flags


def _unique(items):
    seen, out = set(), []
    for i in items:
        if i and i not in seen:
            seen.add(i)
            out.append(i)
    return out
