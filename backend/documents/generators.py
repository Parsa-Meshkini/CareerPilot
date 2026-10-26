"""Document generation (P6 Template Method).

DocumentGenerator.generate() is the fixed pipeline: build grounded context ->
prompt variables (hook) -> structured LLM call -> fabrication verification ->
one regeneration with feedback -> remove anything still unsupported -> render
(hook) -> persist. Subclasses supply only the hooks; they cannot override
generate(), so no generator can skip the FabricationGuard.
"""
from abc import ABC, abstractmethod

from agent.context import ContextBuilder, ProfileIncompleteError
from agent.llm.client import get_llm_client
from careerpilot.storage import storage
from jobs.analysis_service import JobAnalysisService
from jobs.models import JobAnalysis

from .guard import FabricationGuard
from .models import GeneratedDocument
from .renderer import DocumentRenderer


class DocumentGenerator(ABC):
    doc_type = ""
    FORMATS = ("pdf", "docx")

    def __init_subclass__(cls, **kwargs):
        super().__init_subclass__(**kwargs)
        if "generate" in cls.__dict__:
            raise TypeError(f"{cls.__name__} must not override the template method generate()")

    def __init__(self, llm=None, contexts=None, renderer=None):
        self._llm = llm
        self.contexts = contexts or ContextBuilder()
        self.renderer = renderer or DocumentRenderer()

    @property
    def llm(self):
        return self._llm or get_llm_client()

    # ---------------- template method ----------------
    def generate(self, user, application, run=None, **options):
        ctx = self.contexts.build_candidate_context(user, require_goal=False)
        self.check_enough_facts(ctx)
        job = application.job
        analysis = JobAnalysis.objects.filter(job=job).first() or JobAnalysisService().analyze_job(user, job.id, run=run)
        variables = self.build_prompt_variables(ctx, application, analysis, options)
        result = self.llm.generate_structured(user, self.prompt_key(), variables, run=run)
        content, model = result.data, result.model

        guard = FabricationGuard(ctx, job)
        report = self.verify(guard, content)
        regenerated = False
        if not report.passed:
            regenerated = True
            retry = self.llm.generate_structured(
                user, self.prompt_key(), {**variables, "feedback": "Fix these problems; use only the cited facts:\n"
                                          + report.feedback()}, run=run)
            content = retry.data
            report = self.verify(guard, content)
            if not report.passed:
                content = self.remove_claims(content, report.bad_locations())
        status = GeneratedDocument.PASSED if report.passed else GeneratedDocument.NEEDS_REVIEW
        report_dict = {**report.to_dict(), "regenerated": regenerated}
        return self.persist(user, application, ctx, content, status, report_dict, model, result.prompt_version)

    def regenerate_from_edit(self, user, document, content):
        """Student edits are verified and rendered through the same guard and renderer."""
        ctx = self.contexts.build_candidate_context(user, require_goal=False)
        report = self.verify(FabricationGuard(ctx, document.application.job), content)
        status = GeneratedDocument.PASSED if report.passed else GeneratedDocument.NEEDS_REVIEW
        return self.persist(user, document.application, ctx, content, status,
                            {**report.to_dict(), "edited": True}, "student-edit", document.prompt_version)

    # ---------------- concrete steps ----------------
    def verify(self, guard, content):
        report = guard.check(self.claims(content))
        self.extra_checks(content, report)
        return report

    def persist(self, user, application, ctx, content, status, report, model, prompt_version):
        previous = GeneratedDocument.objects.filter(application=application, doc_type=self.doc_type).first()
        doc = GeneratedDocument(user=user, application=application, doc_type=self.doc_type,
                                version=(previous.version + 1) if previous else 1, content=content,
                                verification_status=status, verification_report=report, model_used=model,
                                prompt_version=prompt_version,
                                cited_fact_ids=sorted({i for c in self.claims(content) for i in c.source_fact_ids}))
        blocks = self.render(ctx, application, content)
        base = self.file_stem(application)
        for fmt in self.FORMATS:
            data = getattr(self.renderer, f"render_{fmt}")(blocks)
            doc.files[fmt] = storage.put(storage.new_key(user, "documents", f"{base}_v{doc.version}.{fmt}"), data)
        doc.save()
        return doc

    def check_enough_facts(self, ctx):
        if len(ctx.facts_of("EXPERIENCE")) + len(ctx.facts_of("PROJECT")) == 0:
            raise ProfileIncompleteError("add at least one verified experience or project before generating",
                                         missing="experience")

    def file_stem(self, application):
        return self.doc_type.title().replace("_", "")

    # ---------------- hooks ----------------
    def extra_checks(self, content, report):
        """Optional hook for document-specific rules; it can only add violations."""
    @abstractmethod
    def prompt_key(self): ...

    @abstractmethod
    def build_prompt_variables(self, ctx, application, analysis, options): ...

    @abstractmethod
    def claims(self, content): ...

    @abstractmethod
    def remove_claims(self, content, locations): ...

    @abstractmethod
    def render(self, ctx, application, content): ...


class ResumeGenerator(DocumentGenerator):
    doc_type = GeneratedDocument.RESUME
    MAX_BULLETS_PER_ENTRY = 4

    def prompt_key(self):
        return "resume.tailor"

    def build_prompt_variables(self, ctx, application, analysis, options):
        entries = [{"id": str(f.id), "type": f.fact_type, "heading": f.label(), "text": f.to_context_text()}
                   for f in ctx.facts if f.fact_type in ("EXPERIENCE", "PROJECT")]
        return {"job": f"{application.job.title} at {application.job.company}",
                "required": analysis.required_skills, "preferred": analysis.preferred_skills,
                "keywords": analysis.keywords, "entries": entries, "facts": ctx.facts_for_prompt(),
                "skills": sorted(ctx.skills), "feedback": options.get("feedback", "")}

    def claims(self, content):
        from .guard import Claim

        out = []
        for e_i, entry in enumerate(content.get("entries", [])):
            for b_i, bullet in enumerate(entry.get("bullets", [])):
                ids = bullet.get("source_fact_ids") or []
                out.append(Claim(f"entries.{e_i}.bullets.{b_i}", bullet.get("text", ""), [str(i) for i in ids]))
        for s_i, skill in enumerate(content.get("skills", [])):
            out.append(Claim(f"skills.{s_i}", skill, [], kind="skill"))
        return out

    def remove_claims(self, content, locations):
        entries = []
        for e_i, entry in enumerate(content.get("entries", [])):
            bullets = [b for b_i, b in enumerate(entry.get("bullets", []))
                       if f"entries.{e_i}.bullets.{b_i}" not in locations]
            entries.append({**entry, "bullets": bullets})
        skills = [s for s_i, s in enumerate(content.get("skills", [])) if f"skills.{s_i}" not in locations]
        return {**content, "entries": entries, "skills": skills}

    def render(self, ctx, application, content):
        p, facts = ctx.profile, ctx.fact_index()
        contact = " | ".join(x for x in (p.email, p.phone, p.location, *p.links) if x)
        blocks = [{"kind": "title", "text": p.full_name or ctx.user.display_name or ctx.user.email}]
        if contact:
            blocks.append({"kind": "subtitle", "text": contact})
        education = ctx.facts_of("EDUCATION")
        if education:
            blocks.append({"kind": "heading", "text": "Education"})
            for f in education:
                d = f.data
                blocks.append({"kind": "entry", "text": f"{d.get('credential', '')} {d['program']}, {d['institution']}".strip(),
                               "right": _dates(d)})
        for section, fact_type in (("Experience", "EXPERIENCE"), ("Projects", "PROJECT")):
            rows = [e for e in content.get("entries", []) if facts.get(str(e.get("fact_id"))) is not None
                    and facts[str(e["fact_id"])].fact_type == fact_type and e.get("bullets")]
            if not rows:
                continue
            blocks.append({"kind": "heading", "text": section})
            for e in rows:
                d = facts[str(e["fact_id"])].data
                heading = f"{d['title']}, {d['organization']}" if fact_type == "EXPERIENCE" else d["name"]
                blocks.append({"kind": "entry", "text": heading, "right": _dates(d)})
                blocks.append({"kind": "bullets",
                               "items": [b["text"] for b in e["bullets"][:self.MAX_BULLETS_PER_ENTRY]]})
        if content.get("skills"):
            blocks += [{"kind": "heading", "text": "Skills"}, {"kind": "text", "text": ", ".join(content["skills"])}]
        certs = ctx.facts_of("CERTIFICATION")
        if certs:
            blocks.append({"kind": "heading", "text": "Certifications"})
            blocks.append({"kind": "bullets", "items": [f"{c.data['name']} ({c.data.get('issuer', '')})".replace(" ()", "")
                                                        for c in certs]})
        return blocks

    def file_stem(self, application):
        return "Resume"


def _dates(d):
    start, end = d.get("start_date") or "", d.get("end_date") or ""
    if not start and not end:
        return ""
    return f"{start} - {end or 'Present'}"


class CoverLetterGenerator(DocumentGenerator):
    doc_type = GeneratedDocument.COVER_LETTER
    MAX_WORDS = 350
    TONES = ("formal", "warm")

    def prompt_key(self):
        return "cover_letter.tailor"

    def build_prompt_variables(self, ctx, application, analysis, options):
        tone = options.get("tone", "formal")
        if tone not in self.TONES:
            from careerpilot.errors import ValidationFailed

            raise ValidationFailed("tone must be formal or warm", fields={"tone": "formal|warm"})
        job = application.job
        return {"job_title": job.title, "company": job.company, "posting": job.description[:6000] or "(summary only)",
                "required": analysis.required_skills, "facts": ctx.facts_for_prompt(),
                "name": ctx.profile.full_name or ctx.user.email, "tone": tone,
                "max_words": self.MAX_WORDS, "feedback": options.get("feedback", "")}

    def claims(self, content):
        from .guard import Claim

        return [Claim(f"paragraphs.{i}", p.get("text", ""), [str(x) for x in p.get("source_fact_ids") or []],
                      kind="paragraph") for i, p in enumerate(content.get("paragraphs", []))]

    def extra_checks(self, content, report):
        from .guard import Violation

        words = sum(len(p.get("text", "").split()) for p in content.get("paragraphs", []))
        if words > self.MAX_WORDS:
            report.violations.append(Violation("length", "", "too_long", f"{words} words; the limit is {self.MAX_WORDS}"))

    def remove_claims(self, content, locations):
        paragraphs = [p for i, p in enumerate(content.get("paragraphs", [])) if f"paragraphs.{i}" not in locations]
        if "length" in locations:
            kept, total = [], 0
            for p in paragraphs:
                n = len(p.get("text", "").split())
                if total + n > self.MAX_WORDS:
                    break
                kept.append(p)
                total += n
            paragraphs = kept
        return {**content, "paragraphs": paragraphs}

    def render(self, ctx, application, content):
        from django.utils import timezone

        p, job = ctx.profile, application.job
        blocks = [{"kind": "text", "text": p.full_name or ctx.user.email}]
        contact = " | ".join(x for x in (p.email, p.phone, p.location) if x)
        if contact:
            blocks.append({"kind": "text", "text": contact})
        blocks += [{"kind": "text", "text": timezone.localdate().strftime("%B %d, %Y")},
                   {"kind": "text", "text": f"{job.company}"},
                   {"kind": "paragraph", "text": f"Re: {job.title}"},
                   {"kind": "paragraph", "text": content.get("greeting") or "Dear Hiring Manager,"}]
        blocks += [{"kind": "paragraph", "text": para["text"]} for para in content.get("paragraphs", [])]
        blocks += [{"kind": "paragraph", "text": content.get("closing") or "Sincerely,"},
                   {"kind": "text", "text": p.full_name or ctx.user.email}]
        return blocks

    def file_stem(self, application):
        return "Cover_Letter"


GENERATORS = {"resume": ResumeGenerator, "cover_letter": CoverLetterGenerator}
