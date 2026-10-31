package com.careerpilot.agent;

import com.careerpilot.jobs.JobMatch;
import com.careerpilot.llm.LlmBudgetExceededException;
import com.careerpilot.users.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DeepMatchJobsCommand extends AgentCommand {
    static final CommandType TYPE = CommandType.of("deep_match_jobs", "Deep AI analysis + explained match for each selected posting.",
            "{\"type\":\"object\",\"required\":[\"job_ids\"],\"properties\":{\"job_ids\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}",
            false, false, 4000, DeepMatchJobsCommand::new);

    public DeepMatchJobsCommand(AgentServices svc, User user, Map<String, Object> args, AgentRun run) { super(svc, user, args, run); }

    @Override public CommandType type() { return TYPE; }

    @Override
    public Observation execute() {
        List<UUID> ids = QuickScoreJobsCommand.ids(args.get("job_ids"));
        List<Map<String, Object>> done = new ArrayList<>();
        boolean stopped = false;
        for (UUID id : ids) {
            try {
                JobMatch m = svc.matching.matchJob(user, id, true, run, null);
                done.add(map("job_id", id.toString(), "score", m.getScore()));
            } catch (LlmBudgetExceededException e) {
                stopped = true;
                break;
            }
        }
        return Observation.ok("deep-analyzed " + done.size() + " of " + ids.size() + (stopped ? " (stopped early: budget reached)" : ""),
                map("results", done, "stopped_for_budget", stopped));
    }
}
