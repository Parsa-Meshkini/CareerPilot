"""PackageAssembler (F12): one ready-to-submit folder per application, offered as a ZIP.

CareerPilot never submits the package; the student does.
"""
import io
import re
import zipfile

from applications.repository import ApplicationRepository
from applications.services import ApplicationService
from jobs.analysis_service import JobAnalysisService
from jobs.models import JobAnalysis

from .generators import CoverLetterGenerator, ResumeGenerator
from .models import ApplicationPackage, GeneratedDocument
from .renderer import DocumentRenderer
from careerpilot.storage import storage


class PackageAssembler:
    def __init__(self, renderer=None, applications=None, app_service=None):
        self.renderer = renderer or DocumentRenderer()
        self.applications = applications or ApplicationRepository()
        self.app_service = app_service or ApplicationService()

    def build_package(self, user, application_id, regenerate=False, tone="formal", run=None):
        app = self.applications.find_by_id(user, application_id)
        job = app.job
        analysis = JobAnalysis.objects.filter(job=job).first() or JobAnalysisService().analyze_job(user, job.id, run=run)
        resume = self._document(user, app, ResumeGenerator, regenerate, run)
        letter = self._document(user, app, CoverLetterGenerator, regenerate, run, tone=tone)
        docs = [resume, letter]
        needs_review = [d for d in docs if d.verification_status == GeneratedDocument.NEEDS_REVIEW]

        folder = self.folder_name(job)
        files = {
            "Resume.pdf": storage.get(resume.files["pdf"]), "Resume.docx": storage.get(resume.files["docx"]),
            "Cover_Letter.pdf": storage.get(letter.files["pdf"]),
            "Cover_Letter.docx": storage.get(letter.files["docx"]),
            "Job_Description.pdf": self.renderer.render_pdf(self.job_description_blocks(job)),
            "Application_Checklist.pdf": self.renderer.render_pdf(self.build_checklist(job, analysis, needs_review)),
            "Application_Info.txt": self.build_info_text(app, job).encode(),
        }
        data = self.zip(folder, files)
        key = storage.put(storage.new_key(user, "packages", f"{folder}.zip"), data)
        package = ApplicationPackage.objects.create(
            user=user, application=app, folder_name=folder, storage_key=key, manifest=sorted(files),
            needs_review=bool(needs_review), document_ids=[str(d.id) for d in docs])
        if app.state_name == "SAVED":
            self.app_service.transition(user, app.id, "mark_prepared", reason="application package built")
        return package

    def _document(self, user, app, generator_cls, regenerate, run, **options):
        latest = GeneratedDocument.objects.filter(application=app, doc_type=generator_cls.doc_type).first()
        if latest and not latest.stale and not regenerate:
            return latest
        return generator_cls().generate(user, app, run=run, **options)

    def folder_name(self, job):
        term = re.sub(r"(?i)(winter|summer|fall|spring)\s*(20)?(\d\d)", lambda m: f"{m.group(3)}{m.group(1)[0].upper()}",
                      job.work_term or "")
        parts = [job.company, job.title, term]
        name = "_".join(re.sub(r"[^A-Za-z0-9]+", "_", p).strip("_") for p in parts if p)
        return name[:120] or "Application"

    def job_description_blocks(self, job):
        blocks = [{"kind": "title", "text": job.title}, {"kind": "subtitle", "text": f"{job.company} - {job.location}"}]
        meta = [("Deadline", job.deadline_text or (job.deadline.isoformat() if job.deadline else "")),
                ("Work term", job.work_term), ("Work model", job.work_model.title()), ("Salary", job.salary_text),
                ("Source", job.source_url)]
        blocks += [{"kind": "text", "text": f"{k}: {v}"} for k, v in meta if v]
        blocks.append({"kind": "heading", "text": "Description"})
        blocks += [{"kind": "paragraph", "text": para} for para in (job.description or "(no description)").split("\n\n")]
        return blocks

    def build_checklist(self, job, analysis, needs_review):
        items = []
        if needs_review:
            names = ", ".join(d.doc_type.replace("_", " ").title() for d in needs_review)
            items.append(f"Review flagged content in: {names} (some unsupported lines were removed)")
        required = job.required_documents or ["Resume", "Cover Letter"]
        items += [f"Prepare: {d}" for d in required]
        if job.deadline or job.deadline_text:
            items.append(f"Submit before the deadline: {job.deadline_text or job.deadline.isoformat()}")
        if job.application_url or job.source_url:
            items.append(f"Apply at: {job.application_url or job.source_url}")
        if analysis and analysis.keywords:
            items.append("Mirror these keywords where truthful: " + ", ".join(analysis.keywords[:8]))
        if analysis and analysis.red_flags:
            items.append("Double-check concerns: " + "; ".join(analysis.red_flags))
        items += ["Proofread every document", "Submit the application yourself on the employer's site",
                  "Mark the application as submitted in CareerPilot"]
        return [{"kind": "title", "text": "Application Checklist"},
                {"kind": "subtitle", "text": f"{job.title} - {job.company}"}, {"kind": "bullets", "items": items}]

    def build_info_text(self, app, job):
        rows = [("Job", job.title), ("Company", job.company), ("Location", job.location),
                ("Source URL", job.source_url), ("Application URL", job.application_url),
                ("Deadline", job.deadline_text or (job.deadline.isoformat() if job.deadline else "")),
                ("Work term", job.work_term), ("Contact", job.contact),
                ("Required documents", ", ".join(job.required_documents)), ("Status", app.state_name),
                ("Notes", app.notes)]
        lines = [f"{k}: {v}" for k, v in rows if v]
        lines.append("")
        lines.append("CareerPilot prepared this package. It does not submit applications; you do.")
        return "\n".join(lines) + "\n"

    def zip(self, folder, files):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
            for name, data in files.items():
                zf.writestr(f"{folder}/{name}", data)
        return buf.getvalue()
