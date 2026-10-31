package com.careerpilot.agent;

import com.careerpilot.jobs.JobMatch;
import com.careerpilot.users.User;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class QuickScoreJobsCommand extends AgentCommand {
    static final CommandType TYPE = CommandType.of("quick_score_jobs", "Score many postings cheaply with the goal's strategy (keyword analysis, no LLM tokens).",
            "{\"type\":\"object\",\"required\":[\"job_ids\"],\"properties\":{\"job_ids\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}",
            false, false, 0, QuickScoreJobsCommand::new);

    public QuickScoreJobsCommand(AgentServices svc, User user, Map<String, Object> args, AgentRun run) { super(svc, user, args, run); }

    @Override public CommandType type() { return TYPE; }

    @Override
    public Observation execute() {
        List<UUID> ids = ids(args.get("job_ids"));
        List<JobMatch> matches = svc.matching.rankJobs(user, ids, Set.of());
        Map<String, Object> scores = new LinkedHashMap<>();
        matches.forEach(m -> scores.put(m.getJob().getId().toString(), m.getScore()));
        if (run != null) run.getContext().put("quick_scores", scores);
        String top = matches.stream().limit(5).map(m -> String.valueOf(m.getScore())).collect(Collectors.joining(", "));
        return Observation.ok("quick-scored " + matches.size() + " postings (top: " + (top.isEmpty() ? "-" : top) + ")", map("scores", scores));
    }

    static List<UUID> ids(Object value) {
        return value instanceof List<?> l ? l.stream().map(o -> UUID.fromString(String.valueOf(o))).toList() : List.of();
    }
}
