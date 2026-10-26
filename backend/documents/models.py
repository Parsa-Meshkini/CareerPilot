import uuid

from django.conf import settings
from django.db import models


class GeneratedDocument(models.Model):
    RESUME, COVER_LETTER, CHECKLIST, JOB_DESCRIPTION = "RESUME", "COVER_LETTER", "CHECKLIST", "JOB_DESCRIPTION"
    PASSED, NEEDS_REVIEW = "PASSED", "NEEDS_REVIEW"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
    application = models.ForeignKey("applications.Application", on_delete=models.CASCADE, related_name="documents")
    doc_type = models.CharField(max_length=20)
    version = models.IntegerField(default=1)
    content = models.JSONField(default=dict)               # structured content the files were rendered from
    files = models.JSONField(default=dict)                 # format -> storage key
    verification_status = models.CharField(max_length=20, default=PASSED)
    verification_report = models.JSONField(default=dict)
    cited_fact_ids = models.JSONField(default=list)
    stale = models.BooleanField(default=False)
    model_used = models.CharField(max_length=80, blank=True)
    prompt_version = models.IntegerField(default=1)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created_at"]
