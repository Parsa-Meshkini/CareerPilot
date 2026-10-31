package com.careerpilot.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Decides which commands pause the run for the student's approval. Non side-effecting tools never do. */
@Component
public class ApprovalPolicy {
    /** Reversible, low-risk tools that a goal type may run without asking. */
    private static final Map<String, Set<String>> AUTO_APPROVED = Map.of("TRIAGE_BATCH", Set.of("rank_jobs"));

    public boolean requiresApproval(AgentCommand command, AgentRun run) {
        if (!command.type().sideEffecting()) return false;
        return !AUTO_APPROVED.getOrDefault(run == null ? "" : run.getGoalType(), Set.of()).contains(command.name());
    }
}
