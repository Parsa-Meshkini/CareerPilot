package com.careerpilot.agent;

import com.careerpilot.applications.Application;
import com.careerpilot.interviews.InterviewPrepPlan;
import com.careerpilot.users.User;

import java.util.List;
import java.util.Map;

/** Builds the interview preparation plan (F17) for a job's application. Creating a plan has no side effects outside CareerPilot. */
public class PrepareInterviewCommand extends AgentCommand {
    static final CommandType TYPE = CommandType.of("prepare_interview", "Build an interview preparation plan (likely questions, STAR stories from verified facts, checklist) for a job.",
            "{\"type\":\"object\",\"required\":[\"job_id\"],\"properties\":{\"job_id\":" + JOB_ID_SCHEMA + "}}", false, false, 4000, PrepareInterviewCommand::new);

    public PrepareInterviewCommand(AgentServices svc, User user, Map<String, Object> args, AgentRun run) { super(svc, user, args, run); }

    @Override public CommandType type() { return TYPE; }

    @Override
    public Observation execute() {
        Application app = svc.applications.getOrCreateForJob(user, jobId());
        InterviewPrepPlan plan = svc.interviews.preparePlan(user, app.getId());
        setFocus(null, app.getId());
        int questions = ((List<?>) plan.getContent().getOrDefault("questions", List.of())).size();
        int stories = ((List<?>) plan.getContent().getOrDefault("star_stories", List.of())).size();
        return Observation.ok("preparation plan v" + plan.getVersion() + " ready: " + questions + " questions, " + stories + " STAR stories"
                + ("FALLBACK".equals(plan.getSource()) ? " (generic: AI unavailable)" : ""), map("application_id", app.getId().toString(), "source", plan.getSource()));
    }
}
