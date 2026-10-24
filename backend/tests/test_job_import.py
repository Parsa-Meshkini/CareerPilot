import json
from datetime import date
from pathlib import Path

from jobs.models import JobPosting
from jobs.normalizer import detect_work_model, parse_deadline
from notifications.events import JobsImportedEvent, event_bus

YORK = json.loads((Path(__file__).parent / "fixtures" / "york_list_payload.json").read_text())


def test_york_batch_imports_with_partial_failure_and_event(api, user):
    seen = []

    class Spy:
        def handle(self, event):
            seen.append(event)

    spy = Spy()
    event_bus.subscribe(JobsImportedEvent, spy)
    try:
        r = api.post("/api/jobs/batches/", {"source": "york", "payload": YORK}, format="json")
    finally:
        event_bus.unsubscribe(JobsImportedEvent, spy)
    assert r.status_code == 201
    b = r.data
    assert (b["status"], b["imported"], b["failed"]) == ("PARTIAL_FAILED", 2, 1)
    assert "missing title" in b["results"][2]["reason"]
    job = JobPosting.objects.get(user=user, external_id="1101")
    assert job.deadline == date(2026, 11, 15) and job.work_model == "HYBRID" and job.completeness == "SUMMARY"
    assert len(seen) == 1 and len(seen[0].job_ids) == 2


def test_reimport_is_duplicate_and_full_detail_upgrades_summary(api, user):
    api.post("/api/jobs/batches/", {"source": "york", "payload": YORK}, format="json")
    detail = {"records": [{**YORK["records"][0], "detail_text": "We build APIs in Python and Django. " * 20}]}
    r = api.post("/api/jobs/batches/", {"source": "york", "payload": detail}, format="json")
    assert r.data["duplicates"] == 1 and r.data["imported"] == 0
    job = JobPosting.objects.get(user=user, external_id="1101")
    assert job.completeness == "FULL" and "Django" in job.description
    assert JobPosting.objects.filter(user=user).count() == 2


def test_manual_paste_uses_llm_structuring(api, user, mock_llm):
    mock_llm.script("job.structure_paste", {"title": "Junior DevOps Engineer", "company": "Globex",
                                             "location": "Ottawa, ON", "work_model": "REMOTE",
                                             "deadline_text": "Dec 1, 2026", "required_documents": ["Resume"]})
    text = "Globex is hiring a Junior DevOps Engineer. Remote. Apply by Dec 1, 2026. " * 5
    r = api.post("/api/jobs/import/manual/", {"text": text, "url": "https://globex.example/jobs/9"}, format="json")
    assert r.status_code == 201
    job = r.data["job"]
    assert (job["title"], job["company"], job["work_model"]) == ("Junior DevOps Engineer", "Globex", "REMOTE")
    assert job["deadline"] == date(2026, 12, 1) and job["completeness"] == "FULL"

    r = api.post("/api/jobs/import/manual/", {"text": text}, format="json")
    assert r.status_code == 200 and r.data["duplicate"] is True


def test_manual_paste_falls_back_when_llm_fails(api, mock_llm):
    from agent.llm.types import LLMProviderError

    mock_llm.script("job.structure_paste", LLMProviderError("down"))
    r = api.post("/api/jobs/import/manual/", {"text": "Some posting text without fields"}, format="json")
    assert r.status_code == 400 and set(r.data["fields"]) == {"title", "company"}
    assert r.data["draft"]["description"].startswith("Some posting")
    r = api.post("/api/jobs/import/manual/", {"text": "Some posting text", "title": "Tester", "company": "Initech"},
                 format="json")
    assert r.status_code == 201 and r.data["warnings"]


def test_file_import_csv_and_listing(api):
    from django.core.files.uploadedfile import SimpleUploadedFile

    csv_text = "title,company,location,deadline\nQA Intern,Initech,Toronto,2026-11-20\nSRE Intern,Hooli,Remote,\n"
    r = api.post("/api/jobs/import/file/", {"file": SimpleUploadedFile("jobs.csv", csv_text.encode())},
                 format="multipart")
    assert r.data["imported"] == 2
    listed = api.get("/api/jobs/?deadline_before=2026-11-25").data
    assert [j["title"] for j in listed] == ["QA Intern"]
    assert api.get("/api/jobs/?q=hooli").data[0]["work_model"] == "REMOTE"


def test_linkedin_extraction_disabled(api):
    r = api.post("/api/jobs/batches/", {"source": "linkedin", "payload": YORK}, format="json")
    assert r.status_code == 400 and "paste" in r.data["message"]


def test_batch_limit(api):
    payload = {"records": [{"fields": {"Job Title": f"t{i}", "Organization": "x"}} for i in range(201)]}
    assert api.post("/api/jobs/batches/", {"source": "york", "payload": payload}, format="json").status_code == 400


def test_normalizer_helpers():
    assert parse_deadline("Friday, November 15th, 2026 at 11:59 PM") == date(2026, 11, 15)
    assert parse_deadline("15/11/2026") == date(2026, 11, 15)
    assert parse_deadline("ASAP") is None
    assert detect_work_model("Toronto (on-site)") == "ONSITE"


def test_fingerprint_ignores_company_suffixes(user):
    a = JobPosting(user=user, title="Dev", company="Acme Corp.", location="Toronto")
    b = JobPosting(user=user, title="dev", company="ACME", location="toronto")
    assert a.compute_fingerprint() == b.compute_fingerprint()
