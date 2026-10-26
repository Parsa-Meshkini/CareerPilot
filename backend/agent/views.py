"""AgentAPI (CD-7)."""
from rest_framework.decorators import api_view
from rest_framework.response import Response

from careerpilot.errors import NotFoundError, ValidationFailed

from .controller import AgentController
from .memory import MemoryManager
from .models import AgentMemoryEntry, AgentRun
from .serializers import action_to_dict, memory_to_dict, run_to_dict


def controller():
    return AgentController()


@api_view(["GET", "POST"])
def runs(request):
    if request.method == "POST":
        run = controller().run_goal(request.user, request.data.get("goal", ""), request.data.get("goal_type"),
                                    request.data.get("job_id"))
        return Response(run_to_dict(run), status=201)
    return Response([run_to_dict(r, detail=False) for r in AgentRun.objects.filter(user=request.user)[:30]])


@api_view(["GET"])
def run_detail(request, run_id):
    run = AgentRun.objects.filter(user=request.user, pk=run_id).first()
    if run is None:
        raise NotFoundError("agent run not found")
    return Response(run_to_dict(run))


@api_view(["POST"])
def approve(request, run_id):
    run = controller().resume_run(request.user, run_id, bool(request.data.get("approve", True)),
                                  request.data.get("reason", ""))
    return Response(run_to_dict(run))


@api_view(["POST"])
def answer(request, run_id):
    return Response(run_to_dict(controller().answer(request.user, run_id, str(request.data.get("choice", "")))))


@api_view(["POST"])
def undo_action(request, action_id):
    return Response(action_to_dict(controller().undo_action(request.user, action_id)))


@api_view(["GET"])
def tools(request):
    return Response(controller().tools.list_tools())


@api_view(["GET", "POST"])
def memory(request):
    if request.method == "POST":
        content = (request.data.get("content") or "").strip()
        if not content:
            raise ValidationFailed("content is required")
        entry = MemoryManager().remember(request.user, AgentMemoryEntry.PREFERENCE, content, importance=0.7)
        return Response(memory_to_dict(entry), status=201)
    kind = request.query_params.get("kind")
    qs = AgentMemoryEntry.objects.filter(user=request.user)
    return Response([memory_to_dict(m) for m in (qs.filter(kind=kind) if kind else qs)[:100]])
