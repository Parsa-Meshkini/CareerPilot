from jobs.normalizer import days_until


def change_to_dict(c):
    return {"id": str(c.id), "from_state": c.from_state, "to_state": c.to_state, "action": c.action,
            "source": c.source, "reason": c.reason, "evidence_id": str(c.evidence_id) if c.evidence_id else None,
            "reverted": c.reverted, "created_at": c.created_at}


def interview_to_dict(i):
    return {"id": str(i.id), "round": i.round, "scheduled_at": i.scheduled_at, "format": i.format,
            "details_text": i.details_text}


def application_to_dict(app, detail=False):
    job = app.job
    data = {"id": str(app.id), "state": app.state_name, "allowed_actions": app.allowed_actions(),
            "version": app.version, "applied_at": app.applied_at, "notes": app.notes, "updated_at": app.updated_at,
            "job": {"id": str(job.id), "title": job.title, "company": job.company, "deadline": job.deadline,
                    "days_left": days_until(job.deadline)}}
    if detail:
        changes = list(app.changes.all())
        latest = next((c for c in changes if not c.reverted), None)
        data["timeline"] = [change_to_dict(c) for c in changes]
        data["undoable_change_id"] = str(latest.id) if latest else None
        data["interviews"] = [interview_to_dict(i) for i in app.interviews.all()]
    return data
