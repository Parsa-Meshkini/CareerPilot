"""JobImportAPI and JobAPI (CD-7)."""
import json

from rest_framework.decorators import api_view
from rest_framework.response import Response

from careerpilot.errors import ValidationFailed

from .import_service import JobImportService
from .normalizer import parse_deadline
from .repository import JobRepository
from .serializers import batch_to_dict, job_to_dict

imports = JobImportService()
jobs = JobRepository()


@api_view(["POST"])
def create_batch(request):
    batch = imports.create_batch(request.user, request.data.get("source", "york"), request.data.get("payload") or {})
    return Response(batch_to_dict(batch), status=201)


@api_view(["GET"])
def batch_detail(request, batch_id):
    return Response(batch_to_dict(imports.get_batch_status(request.user, batch_id)))


@api_view(["GET"])
def list_batches(request):
    return Response([batch_to_dict(b) for b in imports.batches.list_by_user(request.user)[:20]])


@api_view(["POST"])
def import_manual(request):
    job, duplicate, warnings = imports.import_manual(request.user, request.data)
    return Response({"job": job_to_dict(job), "duplicate": duplicate, "warnings": warnings},
                    status=200 if duplicate else 201)


@api_view(["POST"])
def import_file(request):
    """JSON list or CSV of postings, as an upload ('file') or inline ('records')."""
    upload = request.FILES.get("file")
    if upload is not None:
        text = upload.read().decode("utf-8", errors="replace")
        if upload.name.lower().endswith(".csv"):
            payload = {"csv": text}
        else:
            try:
                data = json.loads(text)
            except ValueError:
                raise ValidationFailed("file is not valid JSON")
            payload = {"records": data if isinstance(data, list) else data.get("records")}
    else:
        payload = {"records": request.data.get("records")}
    batch = imports.create_batch(request.user, "file", payload)
    return Response(batch_to_dict(batch), status=201)


@api_view(["GET"])
def list_jobs(request):
    p = request.query_params
    qs = jobs.list_jobs(request.user, q=p.get("q"), deadline_before=parse_deadline(p.get("deadline_before", "")),
                        shortlisted=(p["shortlisted"] == "true") if "shortlisted" in p else None,
                        include_dismissed=p.get("include_dismissed") == "true")
    return Response([job_to_dict(j, include_description=False) for j in qs])


EDITABLE = ("title", "company", "location", "job_type", "salary_text", "deadline_text", "work_term", "description",
            "application_url", "contact", "required_documents", "dismissed")


@api_view(["GET", "PATCH", "DELETE"])
def job_detail(request, job_id):
    job = jobs.find_by_id(request.user, job_id)
    if request.method == "DELETE":
        job.delete()
        return Response(status=204)
    if request.method == "PATCH":
        for f in EDITABLE:
            if f in request.data:
                setattr(job, f, request.data[f])
        if "deadline_text" in request.data:
            job.deadline = parse_deadline(job.deadline_text)
        job.save()
    return Response(job_to_dict(job))
