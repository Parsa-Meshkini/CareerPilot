import hashlib
import re
import uuid

from django.conf import settings
from django.db import models
from django.utils import timezone

SOURCE_TYPES = [(s, s.title()) for s in ("YORK", "LINKEDIN", "MANUAL", "MOCK")]


class ImportBatch(models.Model):
    RECEIVED, PROCESSING, COMPLETED, PARTIAL_FAILED, FAILED = "RECEIVED", "PROCESSING", "COMPLETED", "PARTIAL_FAILED", "FAILED"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="import_batches")
    source_type = models.CharField(max_length=10, choices=SOURCE_TYPES)
    status = models.CharField(max_length=20, default=RECEIVED)
    total_count = models.IntegerField(default=0)
    imported_count = models.IntegerField(default=0)
    duplicate_count = models.IntegerField(default=0)
    failed_count = models.IntegerField(default=0)
    results = models.JSONField(default=list, blank=True)   # per-record outcome
    payload = models.JSONField(default=dict, blank=True)
    extractor_version = models.CharField(max_length=40, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    completed_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        ordering = ["-created_at"]

    def record_result(self, index, outcome, job_id=None, reason=""):
        self.results.append({"index": index, "outcome": outcome, "job_id": str(job_id) if job_id else None,
                             "reason": reason})
        if outcome == "IMPORTED":
            self.imported_count += 1
        elif outcome == "DUPLICATE":
            self.duplicate_count += 1
        else:
            self.failed_count += 1

    def progress(self):
        done = self.imported_count + self.duplicate_count + self.failed_count
        return 1.0 if not self.total_count else round(done / self.total_count, 3)

    def complete(self):
        if self.failed_count and self.failed_count == self.total_count:
            self.status = self.FAILED
        elif self.failed_count:
            self.status = self.PARTIAL_FAILED
        else:
            self.status = self.COMPLETED
        self.completed_at = timezone.now()

    def job_ids(self):
        return [r["job_id"] for r in self.results if r["job_id"]]


class JobPosting(models.Model):
    FULL, SUMMARY = "FULL", "SUMMARY"
    WORK_MODELS = [(m, m.title()) for m in ("ONSITE", "HYBRID", "REMOTE", "UNKNOWN")]

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="jobs")
    source_type = models.CharField(max_length=10, choices=SOURCE_TYPES)
    external_id = models.CharField(max_length=120, null=True, blank=True)
    source_url = models.URLField(max_length=500, blank=True)
    title = models.CharField(max_length=200)
    company = models.CharField(max_length=200)
    location = models.CharField(max_length=200, blank=True)
    job_type = models.CharField(max_length=60, blank=True)
    work_model = models.CharField(max_length=10, choices=WORK_MODELS, default="UNKNOWN")
    salary_text = models.CharField(max_length=120, blank=True)
    deadline = models.DateField(null=True, blank=True)
    deadline_text = models.CharField(max_length=80, blank=True)
    work_term = models.CharField(max_length=60, blank=True)
    description = models.TextField(blank=True)
    application_url = models.URLField(max_length=500, blank=True)
    contact = models.CharField(max_length=200, blank=True)
    required_documents = models.JSONField(default=list, blank=True)
    completeness = models.CharField(max_length=10, default=SUMMARY)
    fingerprint = models.CharField(max_length=40, db_index=True)
    merged_sources = models.JSONField(default=list, blank=True)
    shortlisted = models.BooleanField(default=False)
    shortlist_reason = models.CharField(max_length=300, blank=True)
    dismissed = models.BooleanField(default=False)
    batch = models.ForeignKey(ImportBatch, null=True, blank=True, on_delete=models.SET_NULL, related_name="jobs")
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-created_at"]
        constraints = [models.UniqueConstraint(fields=["user", "source_type", "external_id"],
                                               condition=models.Q(external_id__isnull=False),
                                               name="unique_external_job_per_user")]
        indexes = [models.Index(fields=["user", "deadline"])]

    def compute_fingerprint(self):
        month = (self.created_at or timezone.now()).strftime("%Y-%m")
        key = "|".join(_norm(x) for x in (self.company, self.title, self.location)) + "|" + month
        self.fingerprint = hashlib.sha1(key.encode()).hexdigest()
        return self.fingerprint

    MERGE_FIELDS = ("source_url", "location", "job_type", "salary_text", "deadline", "deadline_text", "work_term",
                    "description", "application_url", "contact", "external_id")

    def merge_from(self, other):
        """Fuller and newer fields win; returns the names of fields that changed."""
        changed = []
        for f in self.MERGE_FIELDS:
            new, old = getattr(other, f), getattr(self, f)
            if new and (not old or (f == "description" and len(str(new)) > len(str(old)))):
                setattr(self, f, new)
                changed.append(f)
        if other.work_model != "UNKNOWN" and self.work_model == "UNKNOWN":
            self.work_model = other.work_model
            changed.append("work_model")
        if other.required_documents and not self.required_documents:
            self.required_documents = other.required_documents
        if other.completeness == self.FULL:
            self.completeness = self.FULL
        if other.source_type not in self.merged_sources:
            self.merged_sources = sorted(set(self.merged_sources + [self.source_type, other.source_type]))
        return changed


def _norm(text):
    text = re.sub(r"[^a-z0-9 ]", " ", (text or "").lower())
    text = re.sub(r"\b(inc|ltd|llc|corp|corporation|co|the)\b", " ", text)
    return " ".join(text.split())


class JobAnalysis(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
    job = models.OneToOneField(JobPosting, on_delete=models.CASCADE, related_name="analysis")
    required_skills = models.JSONField(default=list)
    preferred_skills = models.JSONField(default=list)
    responsibilities = models.JSONField(default=list)
    seniority = models.CharField(max_length=30, blank=True)
    years_required = models.IntegerField(null=True, blank=True)
    keywords = models.JSONField(default=list)
    red_flags = models.JSONField(default=list)
    summary = models.TextField(blank=True)
    model_used = models.CharField(max_length=80, blank=True)
    is_fallback = models.BooleanField(default=False)
    partial = models.BooleanField(default=False)
    created_at = models.DateTimeField(auto_now=True)


class JobMatch(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
    job = models.OneToOneField(JobPosting, on_delete=models.CASCADE, related_name="match")
    score = models.IntegerField()
    strategy_key = models.CharField(max_length=30)
    breakdown = models.JSONField(default=dict)
    matched_skills = models.JSONField(default=list)
    missing_skills = models.JSONField(default=list)
    transferable = models.JSONField(default=list, blank=True)
    constraint_notes = models.JSONField(default=list, blank=True)
    explanation = models.TextField(blank=True)
    suggestions = models.JSONField(default=list, blank=True)
    is_deep_analysis = models.BooleanField(default=False)
    outdated = models.BooleanField(default=False)
    created_at = models.DateTimeField(auto_now=True)

    def score_band(self):
        if self.score >= 80:
            return "80-100"
        if self.score >= 60:
            return "60-79"
        if self.score >= 40:
            return "40-59"
        return "0-39"
