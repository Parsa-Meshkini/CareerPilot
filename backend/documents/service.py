from applications.repository import ApplicationRepository
from careerpilot.errors import NotFoundError, ValidationFailed

from .generators import GENERATORS
from .models import GeneratedDocument


class DocumentService:
    def __init__(self, applications=None):
        self.applications = applications or ApplicationRepository()

    def generate(self, user, application_id, kind, **options):
        cls = GENERATORS.get(kind)
        if cls is None:
            raise ValidationFailed(f"unknown document type '{kind}'")
        app = self.applications.find_by_id(user, application_id)
        return cls().generate(user, app, **options)

    def list_documents(self, user, application_id):
        app = self.applications.find_by_id(user, application_id)
        return GeneratedDocument.objects.filter(application=app)

    def get_document(self, user, document_id):
        doc = GeneratedDocument.objects.filter(user=user, pk=document_id).select_related("application__job").first()
        if doc is None:
            raise NotFoundError("document not found")
        return doc

    def save_edit(self, user, document_id, content):
        doc = self.get_document(user, document_id)
        kind = {v.doc_type: k for k, v in GENERATORS.items()}.get(doc.doc_type)
        if kind is None:
            raise ValidationFailed("this document type cannot be edited")
        return GENERATORS[kind]().regenerate_from_edit(user, doc, content)


def mark_documents_citing_stale(user, fact_id):
    """Called when a fact is deleted: documents that cite it must be regenerated."""
    fid = str(fact_id)
    for doc in GeneratedDocument.objects.filter(user=user, stale=False):
        if fid in doc.cited_fact_ids:
            doc.stale = True
            doc.save(update_fields=["stale"])
