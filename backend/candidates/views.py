"""ProfileAPI (CD-7): thin HTTP layer over CandidateService."""
from rest_framework.decorators import api_view
from rest_framework.response import Response

from .serializers import fact_to_dict, goal_to_dict, profile_to_dict, resume_to_dict
from .services import CandidateService

service = CandidateService()


@api_view(["GET", "PUT"])
def profile(request):
    if request.method == "PUT":
        service.update_profile(request.user, request.data)
    p = service.get_profile(request.user)
    facts = service.list_facts(request.user)
    return Response({
        "profile": profile_to_dict(p),
        "facts": [fact_to_dict(f) for f in facts],
        "verified_count": facts.filter(verified=True).count(),
        "goal": goal_to_dict(service.get_career_goal(request.user)),
        "hints": service.completeness_hints(request.user),
    })


@api_view(["GET", "POST"])
def facts(request):
    if request.method == "POST":
        fact = service.add_fact(request.user, request.data.get("fact_type"), request.data.get("data") or {})
        return Response(fact_to_dict(fact), status=201)
    verified = request.query_params.get("verified")
    qs = service.list_facts(request.user, None if verified is None else verified == "true")
    return Response([fact_to_dict(f) for f in qs])


@api_view(["PATCH", "DELETE"])
def fact_detail(request, fact_id):
    if request.method == "DELETE":
        service.delete_fact(request.user, fact_id)
        return Response(status=204)
    return Response(fact_to_dict(service.update_fact(request.user, fact_id, request.data.get("data") or {})))


@api_view(["POST"])
def verify_facts(request):
    count = service.verify_facts(request.user, request.data.get("fact_ids") or [], request.data.get("edits") or {})
    return Response({"verified": count})


@api_view(["POST"])
def reject_facts(request):
    return Response({"rejected": service.reject_facts(request.user, request.data.get("fact_ids") or [])})


@api_view(["GET", "PUT"])
def goal(request):
    if request.method == "PUT":
        g, changed = service.update_career_goal(request.user, request.data)
        return Response({**goal_to_dict(g), "strategy_changed": changed})
    return Response(goal_to_dict(service.get_career_goal(request.user)))


@api_view(["POST"])
def upload_resume(request):
    upload = request.FILES.get("file")
    if upload is None:
        return Response({"error": "validation_failed", "message": "attach the resume as 'file'"}, status=400)
    resume = service.import_resume(request.user, upload.name, upload.read())
    return Response(resume_to_dict(resume), status=201)


@api_view(["GET"])
def resume_detail(request, resume_id):
    return Response(resume_to_dict(service.get_resume(request.user, resume_id)))


@api_view(["POST"])
def resume_retry(request, resume_id):
    resume = service.get_resume(request.user, resume_id)
    return Response(resume_to_dict(service.parse_resume(resume.id)))
