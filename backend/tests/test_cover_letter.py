import pytest

from agent.context import ContextBuilder
from applications.services import ApplicationService
from careerpilot.errors import ValidationFailed
from documents.generators import CoverLetterGenerator
from tests.factories import make_job, make_profile


@pytest.fixture
def setup(user, mock_llm):
    make_profile(user)
    job = make_job(user)
    app = ApplicationService().create_application(user, job.id)
    exp_id = str(ContextBuilder().build_candidate_context(user).facts_of("EXPERIENCE")[0].id)
    mock_llm.script("job.analyze", {"required_skills": ["python", "django"], "preferred_skills": [],
                                    "responsibilities": [], "seniority": "intern", "keywords": [], "red_flags": []})
    return app, exp_id


def letter(exp_id, extra_words=0):
    return {"greeting": "Dear Hiring Manager,", "closing": "Sincerely,", "paragraphs": [
        {"text": "I am applying for the Backend Developer Co-op at Acme Corp to build REST APIs with Python and Django.",
         "source_fact_ids": []},
        {"text": "As a Developer Intern at Acme I built a REST API with Docker." + " really" * extra_words,
         "source_fact_ids": [exp_id]},
        {"text": "Thank you for your consideration.", "source_fact_ids": []}]}


def test_grounded_letter_passes(user, setup, mock_llm, api):
    app, exp_id = setup
    mock_llm.script("cover_letter.tailor", letter(exp_id))
    r = api.post(f"/api/documents/applications/{app.id}/cover_letter/", {"tone": "warm"}, format="json")
    assert r.status_code == 201 and r.data["verification_status"] == "PASSED"
    assert "warm cover letter" in mock_llm.calls[-1].prompt
    assert api.get(f"/api/documents/{r.data['id']}/download/pdf/").content.startswith(b"%PDF")


def test_invented_company_claim_and_length_are_caught(user, setup, mock_llm):
    app, exp_id = setup
    bad = letter(exp_id, extra_words=400)
    bad["paragraphs"].insert(1, {"text": "Acme Corp serves 2 million customers on Kubernetes.", "source_fact_ids": []})
    mock_llm.script("cover_letter.tailor", bad)
    doc = CoverLetterGenerator().generate(user, app)
    rules = {v["rule"] for v in doc.verification_report["violations"]}
    assert {"unsupported_number", "unsupported_skill", "too_long"} <= rules
    assert doc.verification_status == "NEEDS_REVIEW"
    words = sum(len(p["text"].split()) for p in doc.content["paragraphs"])
    assert words <= 350 and not any("2 million" in p["text"] for p in doc.content["paragraphs"])


def test_bad_tone_rejected(user, setup):
    app, _ = setup
    with pytest.raises(ValidationFailed):
        CoverLetterGenerator().generate(user, app, tone="sarcastic")
