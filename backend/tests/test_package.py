import io
import zipfile

import pytest

from agent.context import ContextBuilder
from applications.services import ApplicationService
from documents.models import GeneratedDocument
from tests.factories import make_job, make_profile


@pytest.fixture
def app(user, mock_llm):
    make_profile(user)
    job = make_job(user, required_documents=["Resume", "Transcript"], deadline_text="2026-11-15",
                   application_url="https://acme.example/apply")
    app = ApplicationService().create_application(user, job.id)
    exp_id = str(ContextBuilder().build_candidate_context(user).facts_of("EXPERIENCE")[0].id)
    mock_llm.script("job.analyze", {"required_skills": ["python"], "preferred_skills": [], "responsibilities": [],
                                    "seniority": "intern", "keywords": ["rest", "api"], "red_flags": []})
    mock_llm.script("resume.tailor", {"entries": [{"fact_id": exp_id, "bullets": [
        {"text": "Built a REST API with Docker", "source_fact_ids": [exp_id]}]}], "skills": ["python"]})
    mock_llm.script("cover_letter.tailor", {"greeting": "Dear Hiring Manager,", "closing": "Sincerely,",
                                            "paragraphs": [{"text": "I built a REST API with Docker at Acme.",
                                                            "source_fact_ids": [exp_id]}]})
    return app


def test_package_zip_contents_and_state(api, user, app):
    r = api.post(f"/api/documents/packages/applications/{app.id}/", {}, format="json")
    assert r.status_code == 201
    pkg = r.data
    assert pkg["folder_name"] == "Acme_Corp_Backend_Developer_Co_op_27S" and not pkg["needs_review"]
    z = zipfile.ZipFile(io.BytesIO(api.get(f"/api/documents/packages/{pkg['id']}/download/").content))
    names = sorted(n.split("/", 1)[1] for n in z.namelist())
    assert names == ["Application_Checklist.pdf", "Application_Info.txt", "Cover_Letter.docx", "Cover_Letter.pdf",
                     "Job_Description.pdf", "Resume.docx", "Resume.pdf"]
    info = z.read(f"{pkg['folder_name']}/Application_Info.txt").decode()
    assert "Required documents: Resume, Transcript" in info and "does not submit" in info
    assert api.get(f"/api/applications/{app.id}/").data["state"] == "PREPARED"


def test_package_reuses_documents_unless_regenerate(user, app, mock_llm):
    from documents.package import PackageAssembler

    PackageAssembler().build_package(user, app.id)
    calls = len(mock_llm.calls)
    PackageAssembler().build_package(user, app.id)
    assert len(mock_llm.calls) == calls
    PackageAssembler().build_package(user, app.id, regenerate=True)
    assert GeneratedDocument.objects.filter(application=app, doc_type="RESUME").count() == 2


def test_flagged_document_goes_first_in_checklist(user, app, mock_llm):
    from documents.package import PackageAssembler

    mock_llm.scripts["resume.tailor"] = [{"entries": [], "skills": ["cobol"]}]
    pkg = PackageAssembler().build_package(user, app.id)
    assert pkg.needs_review
    checklist = PackageAssembler().build_checklist(app.job, None, [GeneratedDocument(doc_type="RESUME")])
    assert checklist[2]["items"][0].startswith("Review flagged content in: Resume")


def test_applied_application_keeps_state(user, app):
    from documents.package import PackageAssembler

    ApplicationService().transition(user, app.id, "submit")
    PackageAssembler().build_package(user, app.id)
    app.refresh_from_db()
    assert app.state_name == "APPLIED"
