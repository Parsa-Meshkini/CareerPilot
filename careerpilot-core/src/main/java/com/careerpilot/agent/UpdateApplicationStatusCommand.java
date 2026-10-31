package com.careerpilot.agent;

import com.careerpilot.applications.StatusChange;
import com.careerpilot.users.User;

import java.util.Map;
import java.util.UUID;

/** Moves an application through its lifecycle. The action enum deliberately leaves out "submit": the student submits. */
public class UpdateApplicationStatusCommand extends AgentCommand {
    static final CommandType TYPE = CommandType.of("update_application_status",
            "Move an application through its lifecycle (e.g. mark_prepared, withdraw). Never submits it.",
            "{\"type\":\"object\",\"required\":[\"action\"],\"properties\":{\"application_id\":{\"type\":\"string\"},\"job_id\":" + JOB_ID_SCHEMA
                    + ",\"action\":{\"type\":\"string\",\"enum\":[\"mark_prepared\",\"schedule_interview\",\"receive_offer\",\"reject\",\"withdraw\"]}}}",
            true, true, 0, UpdateApplicationStatusCommand::new);

    public UpdateApplicationStatusCommand(AgentServices svc, User user, Map<String, Object> args, AgentRun run) { super(svc, user, args, run); }

    @Override public CommandType type() { return TYPE; }

    @Override
    public Observation execute() {
        UUID appId = args.get("application_id") != null ? UUID.fromString(String.valueOf(args.get("application_id")))
                : svc.applications.getOrCreateForJob(user, jobId()).getId();
        StatusChange change = svc.applications.transition(user, appId, String.valueOf(args.get("action")), StatusChange.AGENT, null,
                run == null ? "agent" : "agent run " + run.getId(), null, null);
        setFocus(null, appId);
        return Observation.ok("application " + change.getFromState() + " -> " + change.getToState(),
                map("application_id", appId.toString(), "to_state", change.getToState()), map("change_id", change.getId().toString()));
    }

    @Override
    public void undo(Map<String, Object> undoData) {
        svc.applications.undoTransition(user, UUID.fromString(String.valueOf(undoData.get("change_id"))));
    }
}
