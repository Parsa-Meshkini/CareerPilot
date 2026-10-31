package com.careerpilot.agent;

import com.careerpilot.jobs.JobMatch;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.users.User;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reversible: remembers each posting's previous shortlist flag so the whole step can be undone. */
public class RankJobsCommand extends AgentCommand {
    static final CommandType TYPE = CommandType.of("rank_jobs", "Rank jobs by match score and mark the top ones as the shortlist.",
            "{\"type\":\"object\",\"required\":[\"job_ids\"],\"properties\":{\"job_ids\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                    + "\"top_k\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":50}}}", true, true, 0, RankJobsCommand::new);

    public RankJobsCommand(AgentServices svc, User user, Map<String, Object> args, AgentRun run) { super(svc, user, args, run); }

    @Override public CommandType type() { return TYPE; }

    @Override
    public Observation execute() {
        int topK = args.get("top_k") instanceof Number n ? n.intValue() : 10;
        List<JobPosting> jobs = svc.jobs.findByUserAndIdIn(user, QuickScoreJobsCommand.ids(args.get("job_ids")));
        Map<UUID, Integer> scores = new HashMap<>();
        for (JobMatch m : svc.matches.findByUser(user)) scores.put(m.getJob().getId(), m.getScore());
        int minimum = minMatchScore();
        List<JobPosting> ranked = new ArrayList<>(jobs);
        ranked.sort(Comparator.comparingInt((JobPosting j) -> scores.getOrDefault(j.getId(), -1)).reversed());
        Map<String, Object> before = new LinkedHashMap<>();
        for (JobPosting j : jobs) before.put(j.getId().toString(), List.of(j.isShortlisted(), j.getShortlistReason()));
        List<Map<String, Object>> shortlist = new ArrayList<>();
        for (JobPosting j : ranked) {
            Integer s = scores.get(j.getId());
            boolean pick = s != null && s >= minimum && shortlist.size() < topK;
            j.setShortlisted(pick);
            j.setShortlistReason(pick ? "score " + s + " >= your minimum " + minimum : "");
            if (pick) shortlist.add(map("id", j.getId().toString(), "label", j.getTitle() + " @ " + j.getCompany(), "score", s,
                    "deadline", j.getDeadline() == null ? null : j.getDeadline().toString()));
            svc.jobs.save(j);
        }
        return Observation.ok(shortlist.size() + " of " + ranked.size() + " shortlisted", map("shortlist", shortlist), map("before", before));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void undo(Map<String, Object> undoData) {
        Map<String, Object> before = (Map<String, Object>) undoData.getOrDefault("before", Map.of());
        for (Map.Entry<String, Object> e : before.entrySet()) {
            List<Object> state = (List<Object>) e.getValue();
            svc.jobs.findByIdAndUser(UUID.fromString(e.getKey()), user).ifPresent(j -> {
                j.setShortlisted(Boolean.TRUE.equals(state.get(0)));
                j.setShortlistReason(String.valueOf(state.get(1)));
                svc.jobs.save(j);
            });
        }
    }
}
