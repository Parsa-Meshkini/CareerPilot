"""JobImportAPI and JobAPI (CD-7)."""
import json

from rest_framework.decorators import api_view
from rest_framework.response import Response

from careerpilot.errors import ValidationFailed

from .analysis_service import JobAnalysisService
from .import_service import JobImportService
from .matching import MatchingService
from .models import JobAnalysis, JobMatch
from .normalizer import parse_deadline
from .repository import JobRepository
from .serializers import analysis_to_dict, batch_to_dict, job_to_dict, match_to_dict

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
    rows = [job_to_dict(j, include_description=False) for j in qs.select_related("match")]
    if p.get("min_score"):
        rows = [r for r in rows if r["score"] is not None and r["score"] >= int(p["min_score"])]
    if p.get("sort") == "score":
        rows.sort(key=lambda r: -1 if r["score"] is None else r["score"], reverse=True)
    return Response(rows)


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
    return Response({**job_to_dict(job), "analysis": analysis_to_dict(JobAnalysis.objects.filter(job=job).first()),
                     "match": match_to_dict(JobMatch.objects.filter(job=job).first())})


@api_view(["POST"])
def analyze_job(request, job_id):
    """F07 + F08: deep analysis, then a deep match with explanation."""
    analysis = JobAnalysisService().analyze_job(request.user, job_id)
    match = MatchingService().match_job(request.user, job_id, deep=True)
    return Response({"analysis": analysis_to_dict(analysis), "match": match_to_dict(match)})


@api_view(["POST"])
def match_job(request, job_id):
    deep = bool(request.data.get("deep", False))
    return Response(match_to_dict(MatchingService().match_job(request.user, job_id, deep=deep)))


@api_view(["POST"])
def score_all(request):
    """Quick-scores every job without spending LLM tokens."""
    ids = list(jobs.list_jobs(request.user).values_list("id", flat=True))
    ranked = MatchingService().rank_jobs(request.user, ids)
    return Response([{"job_id": str(m.job_id), "score": m.score} for m in ranked])


@api_view(["POST"])
def compare_jobs(request):
    ids = request.data.get("job_ids") or []
    if not 2 <= len(ids) <= 4:
        raise ValidationFailed("compare between 2 and 4 jobs")
    out = []
    for jid in ids:
        job = jobs.find_by_id(request.user, jid)
        match = JobMatch.objects.filter(job=job).first() or MatchingService().match_job(request.user, jid)
        out.append({"job": job_to_dict(job, include_description=False), "match": match_to_dict(match)})
    return Response(out)


@api_view(["POST"])
def triage_batch(request, batch_id):
    """Manual trigger for F09 (also used when auto-triage is off)."""
    from agent.serializers import run_to_dict
    from agent.triage import start_triage

    batch = imports.get_batch_status(request.user, batch_id)
    ids = batch.job_ids()
    if len(ids) < 1:
        raise ValidationFailed("this batch has no imported postings")
    return Response(run_to_dict(start_triage(request.user, ids, str(batch.id))), status=201)
