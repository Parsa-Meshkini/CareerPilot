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


def package_to_dict(p):
    return {"id": str(p.id), "application_id": str(p.application_id), "folder_name": p.folder_name,
            "manifest": p.manifest, "needs_review": p.needs_review, "document_ids": p.document_ids,
            "created_at": p.created_at}


@api_view(["GET", "POST"])
def packages(request, app_id):
    from .models import ApplicationPackage
    from .package import PackageAssembler

    if request.method == "POST":
        pkg = PackageAssembler().build_package(request.user, app_id, regenerate=bool(request.data.get("regenerate")),
                                               tone=request.data.get("tone", "formal"))
        return Response(package_to_dict(pkg), status=201)
    service.applications.find_by_id(request.user, app_id)
    return Response([package_to_dict(p) for p in ApplicationPackage.objects.filter(user=request.user,
                                                                                    application_id=app_id)])


@api_view(["GET"])
def download_package(request, package_id):
    from .models import ApplicationPackage

    pkg = ApplicationPackage.objects.filter(user=request.user, pk=package_id).first()
    if pkg is None or not storage.exists(pkg.storage_key):
        raise NotFoundError("package not found")
    resp = HttpResponse(storage.get(pkg.storage_key), content_type="application/zip")
    resp["Content-Disposition"] = f'attachment; filename="{pkg.folder_name}.zip"'
    return resp
