def action_to_dict(a):
    return {"id": str(a.id), "step_index": a.step_index, "command": a.command_name, "arguments": a.arguments,
            "description": a.description, "status": a.status, "observation": a.observation_summary,
            "reversible": a.reversible and a.status == "EXECUTED", "executed_at": a.executed_at,
            "undone_at": a.undone_at}


def run_to_dict(run, detail=True):
    data = {"id": str(run.id), "goal": run.goal_text, "goal_type": run.goal_type, "status": run.status,
            "plan_source": run.plan_source, "step_count": run.step_count, "replan_count": run.replan_count,
            "tokens_used": run.tokens_used, "token_budget": run.token_budget, "summary": run.summary,
            "error": run.error, "pending": run.pending, "created_at": run.created_at, "finished_at": run.finished_at}
    if detail:
        data.update(plan=[{k: v for k, v in s.items() if k != "approved"} for s in run.plan],
                    plan_history=run.plan_history, decisions=run.decisions,
                    notes=run.context.get("plan_notes", ""), memories_used=run.context.get("memories_used", []),
                    actions=[action_to_dict(a) for a in run.actions.all()])
    return data


def memory_to_dict(m):
    return {"id": str(m.id), "kind": m.kind, "content": m.content, "importance": m.importance,
            "created_at": m.created_at}
