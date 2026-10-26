import io

import docx
import pytest

from agent.context import ContextBuilder
from applications.services import ApplicationService
from documents.generators import DocumentGenerator, ResumeGenerator
from documents.guard import Claim, FabricationGuard
from documents.models import GeneratedDocument
from tests.factories import make_job, make_profile

ANALYSIS = {"required_skills": ["python", "django"], "preferred_skills": ["aws"], "responsibilities": [],
            "seniority": "intern", "keywords": ["rest"], "red_flags": []}


@pytest.fixture
def setup(user, mock_llm):
    make_profile(user)
    job = make_job(user)
    app = ApplicationService().create_application(user, job.id)
    ctx = ContextBuilder().build_candidate_context(user)
    exp = ctx.facts_of("EXPERIENCE")[0]
    mock_llm.script("job.analyze", ANALYSIS)
    return app, ctx, str(exp.id)


def good_content(exp_id):
    return {"entries": [{"fact_id": exp_id, "bullets": [
        {"text": "Built a REST API with Docker during a developer internship", "source_fact_ids": [exp_id]}]}],
        "skills": ["python", "django"]}


def test_supported_resume_passes_and_renders_files(user, setup, mock_llm, api):
    app, ctx, exp_id = setup
    mock_llm.script("resume.tailor", good_content(exp_id))
    r = api.post(f"/api/documents/applications/{app.id}/resume/")
    assert r.status_code == 201
    assert r.data["verification_status"] == "PASSED" and r.data["formats"] == ["docx", "pdf"]
    pdf = api.get(f"/api/documents/{r.data['id']}/download/pdf/")
    assert pdf.status_code == 200 and pdf.content.startswith(b"%PDF")
    d = docx.Document(io.BytesIO(api.get(f"/api/documents/{r.data['id']}/download/docx/").content))
    text = "\n".join(p.text for p in d.paragraphs)
    assert "Developer Intern, Acme" in text and "Built a REST API with Docker" in text and "York University" in text


def test_fabricated_claims_are_regenerated_then_removed(user, setup, mock_llm):
    app, ctx, exp_id = setup
    bad = {"entries": [{"fact_id": exp_id, "bullets": [
        {"text": "Cut latency by 70% using Kubernetes", "source_fact_ids": [exp_id]},
        {"text": "Built a REST API with Docker", "source_fact_ids": [exp_id]},
        {"text": "Led a team of engineers", "source_fact_ids": []}]}],
        "skills": ["python", "kubernetes"]}
    mock_llm.script("resume.tailor", bad)
    doc = ResumeGenerator().generate(user, app)
    assert doc.verification_status == GeneratedDocument.NEEDS_REVIEW
    assert doc.verification_report["regenerated"] is True
    rules = {v["rule"] for v in doc.verification_report["violations"]}
    assert {"unsupported_number", "unsupported_skill", "no_citation", "unverified_skill"} <= rules
    assert [b["text"] for b in doc.content["entries"][0]["bullets"]] == ["Built a REST API with Docker"]
    assert doc.content["skills"] == ["python"]
    assert "Fix these problems" in mock_llm.calls[-1].prompt


def test_regeneration_that_fixes_problems_passes(user, setup, mock_llm):
    app, ctx, exp_id = setup
    mock_llm.scripts["resume.tailor"] = [
        {"entries": [{"fact_id": exp_id, "bullets": [{"text": "Saved $5000", "source_fact_ids": [exp_id]}]}],
         "skills": []},
        good_content(exp_id)]
    doc = ResumeGenerator().generate(user, app)
    assert doc.verification_status == "PASSED" and doc.version == 1


def test_guard_rejects_other_users_fact_ids(user, other_user, setup):
    app, ctx, _ = setup
    make_profile(other_user)
    foreign = str(ContextBuilder().build_candidate_context(other_user).facts[0].id)
    report = FabricationGuard(ctx).check([Claim("x", "Built a REST API", [foreign])])
    assert report.violations[0].rule == "unknown_fact"


def test_versions_edit_and_stale_marking(user, setup, mock_llm, api):
    app, ctx, exp_id = setup
    mock_llm.script("resume.tailor", good_content(exp_id))
    first = ResumeGenerator().generate(user, app)
    edited = good_content(exp_id)
    edited["entries"][0]["bullets"].append({"text": "Won 3 hackathons", "source_fact_ids": [exp_id]})
    r = api.put(f"/api/documents/{first.id}/", {"content": edited}, format="json")
    assert r.data["version"] == 2 and r.data["verification_status"] == "NEEDS_REVIEW"
    api.delete(f"/api/profile/facts/{exp_id}/")
    assert all(d.stale for d in GeneratedDocument.objects.filter(application=app))


def test_needs_experience_or_project(user, mock_llm):
    from candidates.services import CandidateService

    CandidateService().add_fact(user, "SKILL", {"name": "python"})
    app = ApplicationService().create_application(user, make_job(user).id)
    from agent.context import ProfileIncompleteError

    with pytest.raises(ProfileIncompleteError):
        ResumeGenerator().generate(user, app)


def test_template_method_cannot_be_overridden():
    with pytest.raises(TypeError):
        class Sneaky(DocumentGenerator):  # noqa: F841
            def generate(self, *a, **k):
                return None
