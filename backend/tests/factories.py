from candidates.services import CandidateService
from jobs.adapters import RawJobRecord
from jobs.import_service import JobImportService


def make_profile(user, goal_type="COOP", work_term="Summer 2027", roles="backend", skills=("python", "django", "sql")):
    svc = CandidateService()
    for s in skills:
        svc.add_fact(user, "SKILL", {"name": s})
    svc.add_fact(user, "EXPERIENCE", {"organization": "Acme", "title": "Developer Intern", "start_date": "2025-05",
                                      "end_date": "2025-08", "bullets": ["Built a REST API with Docker"]})
    svc.add_fact(user, "EDUCATION", {"institution": "York University", "program": "Computer Science",
                                     "credential": "BSc", "start_date": "2023-09"})
    svc.update_career_goal(user, {"goal_type": goal_type, "work_term": work_term, "target_roles": roles})


def make_job(user, title="Backend Developer Co-op", company="Acme Corp", work_term="Summer 2027",
             description="We need Python, Django and AWS experience. Build REST APIs. " * 10, **kw):
    raw = RawJobRecord(source_type="MANUAL", title=title, company=company, work_term=work_term,
                       description=description, is_full_detail=True, location=kw.pop("location", "Toronto"), **kw)
    job, _, _ = JobImportService().import_record(user, raw)
    return job
