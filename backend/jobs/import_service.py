"""JobImportService: batch (extension/file) and manual (paste) import (F04, F05, F06)."""
from django.db import transaction

from careerpilot.errors import ValidationFailed
from careerpilot.tasks import task_queue
from notifications.events import JobsImportedEvent, event_bus

from .adapters import JobSourceRegistry
from .models import ImportBatch
from .normalizer import DuplicateDetector, JobNormalizer
from .repository import ImportBatchRepository, JobRepository

MAX_BATCH = 200


class JobImportService:
    def __init__(self, registry=None, normalizer=None, detector=None, jobs=None, batches=None, bus=None):
        self.registry = registry or JobSourceRegistry()
        self.normalizer = normalizer or JobNormalizer()
        self.detector = detector or DuplicateDetector()
        self.jobs = jobs or JobRepository()
        self.batches = batches or ImportBatchRepository()
        self.bus = bus or event_bus

    def create_batch(self, user, source, payload):
        adapter = self.registry.get_adapter(source)
        records = payload.get("records")
        if records is not None and len(records) > MAX_BATCH:
            raise ValidationFailed(f"a batch may contain at most {MAX_BATCH} postings; split it")
        raw_records = adapter.parse(payload, user)  # parse early so format errors are a 400
        if not raw_records:
            raise ValidationFailed("no postings found in the payload (page layout not recognized?)")
        batch = ImportBatch.objects.create(user=user, source_type=raw_records[0].source_type,
                                           total_count=len(raw_records),
                                           payload={"source": source, **payload},
                                           extractor_version=str(payload.get("extractor_version", ""))[:40])
        task_queue.enqueue("jobs.process_batch", {"batch_id": batch.id})
        batch.refresh_from_db()
        return batch

    def process_batch(self, batch_id):
        batch = ImportBatch.objects.get(pk=batch_id)
        batch.status = ImportBatch.PROCESSING
        batch.save(update_fields=["status"])
        adapter = self.registry.get_adapter(batch.payload.get("source"))
        for index, raw in enumerate(adapter.parse(batch.payload, batch.user)):
            self._import_record(index, raw, batch, adapter)
        batch.complete()
        batch.save()
        imported = [r["job_id"] for r in batch.results if r["outcome"] == "IMPORTED"]
        self.bus.publish(JobsImportedEvent(batch.user_id, batch_id=batch.id, job_ids=imported,
                                           source_type=batch.source_type))
        return batch

    def _import_record(self, index, raw, batch, adapter):
        problems = adapter.validate(raw)
        if problems:
            batch.record_result(index, "FAILED", reason="; ".join(problems))
            return
        try:
            job, outcome, _ = self.import_record(batch.user, raw, batch)
            batch.record_result(index, outcome, job.id)
        except Exception as exc:
            batch.record_result(index, "FAILED", reason=str(exc)[:200])

    @transaction.atomic
    def import_record(self, user, raw, batch=None):
        """Normalize, deduplicate and save one record. Returns (job, IMPORTED|DUPLICATE, warnings)."""
        job, warnings = self.normalizer.normalize(raw, user)
        existing = self.detector.find_duplicate(user, job)
        if existing is not None:
            if existing.merge_from(job):
                existing.save()
            return existing, "DUPLICATE", warnings
        job.batch = batch
        self.jobs.save(job)
        return job, "IMPORTED", warnings

    def import_manual(self, user, payload):
        adapter = self.registry.get_adapter("manual")
        raw = adapter.parse(payload, user)[0]
        problems = adapter.validate(raw)
        if problems:
            # the structuring step could not find the basics: the student fills them in the preview form
            raise ValidationFailed("could not determine " + " and ".join(p.replace("missing ", "") for p in problems),
                                   fields={p.replace("missing ", ""): "required" for p in problems},
                                   draft={"title": raw.title, "company": raw.company, "description": raw.description},
                                   warnings=raw.warnings)
        job, outcome, warnings = self.import_record(user, raw)
        if outcome == "IMPORTED":
            self.bus.publish(JobsImportedEvent(user.id, batch_id=None, job_ids=[str(job.id)], source_type="MANUAL"))
        return job, outcome == "DUPLICATE", warnings

    def get_batch_status(self, user, batch_id):
        return self.batches.find_by_id(user, batch_id)
