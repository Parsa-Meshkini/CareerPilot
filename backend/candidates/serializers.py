def fact_to_dict(fact):
    return {"id": str(fact.id), "fact_type": fact.fact_type, "data": fact.data, "source": fact.source,
            "verified": fact.verified, "needs_review": fact.needs_review, "review_note": fact.review_note,
            "label": fact.label(), "created_at": fact.created_at}


def profile_to_dict(profile):
    return {k: getattr(profile, k) for k in ("full_name", "headline", "summary", "location", "email", "phone", "links", "auto_triage")}


def goal_to_dict(goal):
    if goal is None:
        return None
    return {"goal_type": goal.goal_type, "target_roles": goal.target_roles,
            "preferred_locations": goal.preferred_locations, "work_term": goal.work_term,
            "work_model": goal.work_model, "min_match_score": goal.min_match_score,
            "strategy_key": goal.to_strategy_key(), "weights": goal.weights.to_dict()}


def resume_to_dict(resume):
    return {"id": str(resume.id), "original_name": resume.original_name, "parse_status": resume.parse_status,
            "status_message": resume.status_message, "extracted_count": resume.extracted_count,
            "created_at": resume.created_at}
