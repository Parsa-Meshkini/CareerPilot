from .normalizer import days_until

JOB_FIELDS = ("title", "company", "location", "job_type", "work_model", "salary_text", "deadline_text", "work_term",
              "description", "application_url", "source_url", "contact", "required_documents", "completeness",
              "source_type", "external_id", "merged_sources", "shortlisted", "shortlist_reason", "dismissed")


def job_to_dict(job, include_description=True):
    data = {f: getattr(job, f) for f in JOB_FIELDS if include_description or f != "description"}
    data.update(id=str(job.id), deadline=job.deadline, days_left=days_until(job.deadline),
                batch_id=str(job.batch_id) if job.batch_id else None, created_at=job.created_at)
    return data


def batch_to_dict(batch):
    return {"id": str(batch.id), "source_type": batch.source_type, "status": batch.status,
            "total": batch.total_count, "imported": batch.imported_count, "duplicates": batch.duplicate_count,
            "failed": batch.failed_count, "progress": batch.progress(), "results": batch.results,
            "created_at": batch.created_at, "completed_at": batch.completed_at}
