package com.careerpilot.agent;

import com.careerpilot.common.DomainException;
import com.careerpilot.users.User;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Command pattern. Each agent tool call is a command object that captures its arguments, can describe itself for an
 * approval prompt, executes against a receiver service and, when reversible, can undo itself. {@link ToolManager} is
 * the invoker. There is deliberately no command that submits an application, sends email or logs into a portal.
 */
public abstract class AgentCommand {
    public static final String FOCUS_JOB = "@focus_job", FOCUS_APP = "@focus_application", BATCH_JOBS = "@batch_jobs", DEEP_SELECTION = "@deep_selection";
    protected static final String JOB_ID_SCHEMA = "{\"type\":\"string\",\"description\":\"job id, or @focus_job for the job chosen earlier in this run\"}";

    protected final AgentServices svc;
    protected final User user;
    protected final Map<String, Object> args;
    protected final AgentRun run;

    protected AgentCommand(AgentServices svc, User user, Map<String, Object> args, AgentRun run) {
        this.svc = svc;
        this.user = user;
        this.args = args == null ? new LinkedHashMap<>() : new LinkedHashMap<>(args);
        this.run = run;
    }

    public abstract CommandType type();

    public abstract Observation execute();

    public void undo(Map<String, Object> undoData) { throw new DomainException("not_reversible", 400, type().name() + " cannot be undone"); }

    public String name() { return type().name(); }
    public Map<String, Object> arguments() { return args; }
    public AgentRun run() { return run; }
    public User user() { return user; }

    public String describe() {
        return name() + "(" + args.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ")) + ")";
    }

    // helpers shared by commands that work on a job or application chosen earlier in the run

    protected UUID jobId() { return UUID.fromString(String.valueOf(args.get("job_id"))); }

    protected void setFocus(UUID jobId, UUID applicationId) {
        if (run == null) return;
        if (jobId != null) run.getContext().put("focus_job_id", jobId.toString());
        if (applicationId != null) run.getContext().put("focus_application_id", applicationId.toString());
    }

    protected int minMatchScore() {
        var goal = svc.candidates.getCareerGoal(user);
        return goal == null ? 60 : goal.getMinMatchScore();
    }

    protected static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
