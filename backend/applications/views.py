"""ApplicationAPI (CD-7)."""
from rest_framework.decorators import api_view
from rest_framework.response import Response

from .serializers import application_to_dict, change_to_dict
from .services import ApplicationService

service = ApplicationService()


@api_view(["GET", "POST"])
def applications(request):
    if request.method == "POST":
        app = service.create_application(request.user, request.data.get("job_id"), request.data.get("notes", ""))
        return Response(application_to_dict(app, detail=True), status=201)
    return Response([application_to_dict(a) for a in service.list_applications(request.user,
                                                                               request.query_params.get("state"))])


@api_view(["GET", "PATCH"])
def application_detail(request, app_id):
    if request.method == "PATCH":
        app = service.update_notes(request.user, app_id, request.data.get("notes", ""))
    else:
        app = service.repo.find_by_id(request.user, app_id)
    return Response(application_to_dict(app, detail=True))


@api_view(["POST"])
def transition(request, app_id):
    change = service.transition(request.user, app_id, request.data.get("action", ""),
                                reason=request.data.get("reason", ""),
                                expected_version=request.data.get("version"))
    app = service.repo.find_by_id(request.user, app_id)
    return Response({"change": change_to_dict(change), "application": application_to_dict(app, detail=True)})


@api_view(["POST"])
def undo(request, change_id):
    app = service.undo_transition(request.user, change_id)
    return Response(application_to_dict(app, detail=True))
