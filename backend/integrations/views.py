"""EmailAPI (CD-8): accounts, sync, .eml upload and the review queue."""
from rest_framework.decorators import api_view, parser_classes
from rest_framework.parsers import FormParser, JSONParser, MultiPartParser
from rest_framework.response import Response

from .repository import DetectedEventRepository, EmailAccountRepository
from .serializers import account_to_dict, event_to_dict
from .sync_service import EmailSyncService

service = EmailSyncService()
accounts, events = EmailAccountRepository(), DetectedEventRepository()


@api_view(["GET"])
def list_accounts(request):
    return Response([account_to_dict(a) for a in accounts.list_by_user(request.user)])


@api_view(["POST"])
def connect_mock(request):
    """Connects the demo inbox (Tier 1, no OAuth)."""
    return Response(account_to_dict(service.connect_mock(request.user)), status=201)


@api_view(["DELETE"])
def disconnect(request, account_id):
    service.disconnect(request.user, account_id)
    return Response(status=204)


@api_view(["POST"])
def sync(request, account_id):
    return Response(service.sync_account(request.user, account_id))


@api_view(["POST"])
@parser_classes([MultiPartParser, FormParser, JSONParser])
def upload_eml(request):
    files = [(f.name, f.read()) for f in request.FILES.getlist("files")]
    return Response(service.import_eml_files(request.user, files), status=201)


@api_view(["GET"])
def list_events(request):
    return Response([event_to_dict(e) for e in events.list_events(request.user, request.query_params.get("status"))[:200]])


@api_view(["POST"])
def confirm_event(request, event_id):
    event = service.confirm_event(request.user, event_id, request.data.get("application_id"),
                                  request.data.get("event_type"))
    return Response(event_to_dict(events.list_events(request.user).get(pk=event.pk)))


@api_view(["POST"])
def dismiss_event(request, event_id):
    service.dismiss_event(request.user, event_id)
    return Response(event_to_dict(events.list_events(request.user).get(pk=event_id)))
