package com.careerpilot.agent;

import com.careerpilot.jobs.JobMatch;
import com.careerpilot.users.User;

import java.util.Map;

public class MatchJobCommand extends AgentCommand {
    static final CommandType TYPE = CommandType.of("match_job", "Score the fit between the student and a posting using the goal's strategy.",
            "{\"type\":\"object\",\"required\":[\"job_id\"],\"properties\":{\"job_id\":" + JOB_ID_SCHEMA + ",\"deep\":{\"type\":\"boolean\"}}}",
            false, false, 1500, MatchJobCommand::new);

    public MatchJobCommand(AgentServices svc, User user, Map<String, Object> args, AgentRun run) { super(svc, user, args, run); }

    @Override public CommandType type() { return TYPE; }

    @Override
    public Observation execute() {
        boolean deep = !Boolean.FALSE.equals(args.get("deep"));
        JobMatch m = svc.matching.matchJob(user, jobId(), deep, run, null);
        int minimum = minMatchScore();
        Observation obs = Observation.ok("score " + m.getScore() + " (" + m.getStrategyKey() + "); missing: "
                        + (m.getMissingSkills().isEmpty() ? "none" : String.join(", ", m.getMissingSkills())),
                map("score", m.getScore(), "min_match_score", minimum, "missing", m.getMissingSkills(), "notes", m.getConstraintNotes()));
        obs.needsJudgement = m.getScore() < minimum;  // weak fit: should the agent keep preparing this one?
        return obs;
    }
}
