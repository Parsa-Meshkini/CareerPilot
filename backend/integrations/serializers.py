def account_to_dict(a):
    return {"id": str(a.id), "provider": a.provider, "address": a.address, "status": a.status,
            "last_sync_at": a.last_sync_at, "created_at": a.created_at}


def event_to_dict(e):
    app = e.application
    return {"id": str(e.id), "event_type": e.event_type, "confidence": e.confidence,
            "extracted_data": e.extracted_data, "classifier": e.classifier, "review_status": e.review_status,
            "match_confidence": e.match_confidence, "candidates": e.candidates,
            "application_id": str(app.id) if app else None,
            "application_label": f"{app.job.title} at {app.job.company}" if app else None,
            "email": {"sender": e.email.sender, "subject": e.email.subject, "received_at": e.email.received_at,
                      "excerpt": e.email.excerpt},
            "created_at": e.created_at}
