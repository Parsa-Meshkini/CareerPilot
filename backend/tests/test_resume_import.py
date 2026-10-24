import io

import docx
import pytest
from reportlab.pdfgen import canvas

from agent.llm.types import LLMProviderError
from candidates.models import ProfileFact, UploadedResume


@pytest.fixture(autouse=True)
def tmp_storage(settings, tmp_path):
    settings.STORAGE_ROOT = tmp_path
    from careerpilot import storage as st

    st.storage.root = tmp_path


def make_docx():
    d = docx.Document()
    for line in ["Ana Lee", "York University - BSc Computer Science, 2023-09 to 2027-04",
                 "Developer Intern, Acme Corp, 2025-05 to 2025-08", "Built a REST API in Django",
                 "Skills: Python, SQL, Docker"]:
        d.add_paragraph(line)
    buf = io.BytesIO()
    d.save(buf)
    return buf.getvalue()


def make_pdf(text_lines):
    buf = io.BytesIO()
    c = canvas.Canvas(buf)
    for i, line in enumerate(text_lines):
        c.drawString(72, 750 - 16 * i, line)
    c.save()
    return buf.getvalue()


EXTRACTED = {"facts": [
    {"fact_type": "EDUCATION", "data": {"institution": "York University", "program": "Computer Science",
                                         "credential": "BSc", "start_date": "2023-09", "end_date": "2027-04"}},
    {"fact_type": "EXPERIENCE", "data": {"organization": "Acme Corp", "title": "Developer Intern",
                                          "start_date": "2025-08", "end_date": "2025-05",
                                          "bullets": ["Built a REST API in Django"]}},
    {"fact_type": "SKILL", "data": {"name": "Python"}},
    {"fact_type": "SKILL", "data": {}},
]}


def upload(api, name, data):
    from django.core.files.uploadedfile import SimpleUploadedFile

    return api.post("/api/profile/resume/", {"file": SimpleUploadedFile(name, data)}, format="multipart")


def test_docx_resume_is_parsed_into_unverified_facts(api, user, mock_llm):
    mock_llm.script("resume.extract_facts", EXTRACTED)
    r = upload(api, "resume.docx", make_docx())
    assert r.status_code == 201 and r.data["parse_status"] == "PARSED"
    assert r.data["extracted_count"] == 3
    assert "Developer Intern, Acme Corp" in mock_llm.calls[0].prompt
    facts = ProfileFact.objects.filter(user=user)
    assert all(not f.verified and f.source == "RESUME_EXTRACTION" for f in facts)
    flagged = facts.get(fact_type="EXPERIENCE")
    assert flagged.needs_review and "end_date" in flagged.review_note


def test_pdf_text_extraction(api, mock_llm):
    mock_llm.script("resume.extract_facts", {"facts": [{"fact_type": "SKILL", "data": {"name": "Java"}}]})
    pdf = make_pdf(["Ana Lee - Software developer", "Experience: Tutor at York University 2024", "Skills: Java"])
    r = upload(api, "cv.pdf", pdf)
    assert r.data["parse_status"] == "PARSED"
    assert "Tutor at York University" in mock_llm.calls[0].prompt


def test_scanned_pdf_without_text_fails_cleanly(api):
    r = upload(api, "scan.pdf", make_pdf([]))
    assert r.data["parse_status"] == "FAILED" and "manually" in r.data["status_message"]


def test_llm_outage_is_retryable(api, mock_llm):
    mock_llm.script("resume.extract_facts", LLMProviderError("503"))
    r = upload(api, "resume.docx", make_docx())
    assert r.data["parse_status"] == "FAILED_RETRYABLE"
    mock_llm.scripts["resume.extract_facts"] = [{"facts": [{"fact_type": "SKILL", "data": {"name": "SQL"}}]}]
    r = api.post(f"/api/profile/resume/{r.data['id']}/retry/")
    assert r.data["parse_status"] == "PARSED" and r.data["extracted_count"] == 1


def test_wrong_type_rejected(api):
    r = upload(api, "photo.png", b"\x89PNG....")
    assert r.status_code == 400 and UploadedResume.objects.count() == 0
