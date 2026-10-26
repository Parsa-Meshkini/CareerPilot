"""FabricationGuard (F10): deterministic check that generated text is supported by verified facts.

Prompts ask the model to cite fact ids, but prompts are not a guarantee, so
every claim is checked here before a document can be marked PASSED.
"""
import re
from dataclasses import dataclass, field

from jobs.skills import canonical, find_skills

DEGREE_WORDS = re.compile(r"\b(b\.?sc|bachelor'?s?|m\.?sc|master'?s?|ph\.?d|mba|b\.?a|b\.?eng|diploma|doctorate)\b", re.I)
NUMBER = re.compile(r"(?<![\w.])\$?\d[\d,]*(?:\.\d+)?\s?(?:%|k|m|x)?(?![\w])", re.I)


@dataclass
class Claim:
    """One piece of generated text and the fact ids it claims to come from."""

    location: str
    text: str
    source_fact_ids: list
    kind: str = "bullet"      # bullet | skill | paragraph


@dataclass
class Violation:
    location: str
    text: str
    rule: str
    detail: str

    def to_dict(self):
        return {"location": self.location, "text": self.text, "rule": self.rule, "detail": self.detail}


@dataclass
class VerificationReport:
    checked: int = 0
    violations: list = field(default_factory=list)

    @property
    def passed(self):
        return not self.violations

    def bad_locations(self):
        return {v.location for v in self.violations}

    def feedback(self):
        return "\n".join(f"- [{v.location}] {v.rule}: {v.detail}" for v in self.violations)

    def to_dict(self):
        return {"checked": self.checked, "passed": self.passed, "violations": [v.to_dict() for v in self.violations]}


class FabricationGuard:
    def __init__(self, ctx, job=None):
        self.ctx = ctx
        self.facts = ctx.fact_index()             # verified facts of this user only
        self.job_text = f"{job.title} {job.company} {job.description}".lower() if job else ""
        self.verified_skills = {canonical(f.data.get("name", "")) for f in ctx.facts_of("SKILL")}
        self.org_names = {o.lower() for f in ctx.facts for o in (f.data.get("organization"), f.data.get("institution"),
                                                                 f.data.get("issuer")) if o}

    def check(self, claims):
        report = VerificationReport()
        for claim in claims:
            report.checked += 1
            if claim.kind == "skill":
                self._check_skill(claim, report)
            else:
                self._check_text(claim, report)
        return report

    def _check_skill(self, claim, report):
        if canonical(claim.text) not in self.verified_skills and canonical(claim.text) not in self.ctx.skills:
            report.violations.append(Violation(claim.location, claim.text, "unverified_skill",
                                               f"'{claim.text}' is not in your verified skills"))

    def _check_text(self, claim, report):
        ids = [str(i) for i in claim.source_fact_ids or []]
        if claim.kind == "bullet" and not ids:
            report.violations.append(Violation(claim.location, claim.text, "no_citation", "bullet cites no facts"))
            return
        unknown = [i for i in ids if i not in self.facts]
        if unknown:
            report.violations.append(Violation(claim.location, claim.text, "unknown_fact",
                                               f"cites facts that are not your verified facts: {', '.join(unknown)}"))
            return
        cited = [self.facts[i] for i in ids]
        # paragraphs may also use what the posting itself says about the role and company
        support = " ".join(f.to_context_text() + " " + " ".join(f.data.get("technologies", [])) for f in cited).lower()
        if claim.kind == "paragraph":
            support += " " + self.job_text
        text = claim.text.lower()

        for number in NUMBER.findall(claim.text):
            n = number.strip().lower().replace(",", "")
            if n and n not in support.replace(",", ""):
                report.violations.append(Violation(claim.location, claim.text, "unsupported_number",
                                                   f"'{number.strip()}' does not appear in the cited facts"))
        for skill in find_skills(claim.text):
            if skill not in support and skill not in find_skills(support):
                report.violations.append(Violation(claim.location, claim.text, "unsupported_skill",
                                                   f"'{skill}' is not mentioned in the cited facts"))
        for org in self.org_names:
            if re.search(r"\b" + re.escape(org) + r"\b", text) and org not in support:
                report.violations.append(Violation(claim.location, claim.text, "wrong_organization",
                                                   f"'{org}' is not part of the cited facts"))
        for degree in DEGREE_WORDS.findall(claim.text):
            if degree.lower().replace(".", "") not in support.replace(".", ""):
                report.violations.append(Violation(claim.location, claim.text, "unsupported_degree",
                                                   f"'{degree}' is not in the cited facts"))
