"""DocumentAPI (CD-7)."""
import mimetypes

from django.http import HttpResponse
from rest_framework.decorators import api_view
from rest_framework.response import Response

from careerpilot.errors import NotFoundError
from careerpilot.storage import storage

from .service import DocumentService

service = DocumentService()


def document_to_dict(d):
    return {"id": str(d.id), "doc_type": d.doc_type, "version": d.version, "content": d.content,
            "formats": sorted(d.files), "verification_status": d.verification_status,
            "verification_report": d.verification_report, "stale": d.stale, "model_used": d.model_used,
            "created_at": d.created_at}


@api_view(["GET"])
def list_documents(request, app_id):
    return Response([document_to_dict(d) for d in service.list_documents(request.user, app_id)])


@api_view(["POST"])
def generate(request, app_id, kind):
    options = {k: v for k, v in request.data.items() if k in ("tone",)}
    return Response(document_to_dict(service.generate(request.user, app_id, kind, **options)), status=201)


@api_view(["GET", "PUT"])
def document_detail(request, doc_id):
    if request.method == "PUT":
        return Response(document_to_dict(service.save_edit(request.user, doc_id, request.data.get("content") or {})),
                        status=201)
    return Response(document_to_dict(service.get_document(request.user, doc_id)))


@api_view(["GET"])
def download(request, doc_id, fmt):
    doc = service.get_document(request.user, doc_id)
    key = doc.files.get(fmt)
    if key is None or not storage.exists(key):
        raise NotFoundError(f"no {fmt} file for this document")
    name = f"{doc.doc_type.title().replace('_', '')}_v{doc.version}.{fmt}"
    resp = HttpResponse(storage.get(key), content_type=mimetypes.guess_type(name)[0] or "application/octet-stream")
    resp["Content-Disposition"] = f'attachment; filename="{name}"'
    return resp
