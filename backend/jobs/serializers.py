from .normalizer import days_until

JOB_FIELDS = ("title", "company", "location", "job_type", "work_model", "salary_text", "deadline_text", "work_term",
              "description", "application_url", "source_url", "contact", "required_documents", "completeness",
              "source_type", "external_id", "merged_sources", "shortlisted", "shortlist_reason", "dismissed")


def job_to_dict(job, include_description=True):
    data = {f: getattr(job, f) for f in JOB_FIELDS if include_description or f != "description"}
    data.update(id=str(job.id), deadline=job.deadline, days_left=days_until(job.deadline),
                batch_id=str(job.batch_id) if job.batch_id else None, created_at=job.created_at)
    match = getattr(job, "match", None) if _has(job, "match") else None
    data["score"] = match.score if match else None
    data["match_outdated"] = match.outdated if match else None
    return data


def _has(job, rel):
    try:
        getattr(job, rel)
        return True
    except Exception:
        return False


def batch_to_dict(batch):
    return {"id": str(batch.id), "source_type": batch.source_type, "status": batch.status,
            "total": batch.total_count, "imported": batch.imported_count, "duplicates": batch.duplicate_count,
            "failed": batch.failed_count, "progress": batch.progress(), "results": batch.results,
            "created_at": batch.created_at, "completed_at": batch.completed_at}


def analysis_to_dict(a):
    if a is None:
        return None
    return {f: getattr(a, f) for f in ("required_skills", "preferred_skills", "responsibilities", "seniority",
                                        "years_required", "keywords", "red_flags", "summary", "model_used",
                                        "is_fallback", "partial", "created_at")}


def match_to_dict(m):
    if m is None:
        return None
    return {**{f: getattr(m, f) for f in ("score", "strategy_key", "breakdown", "matched_skills", "missing_skills",
                                           "transferable", "constraint_notes", "explanation", "suggestions",
                                           "is_deep_analysis", "outdated", "created_at")},
            "score_band": m.score_band()}
