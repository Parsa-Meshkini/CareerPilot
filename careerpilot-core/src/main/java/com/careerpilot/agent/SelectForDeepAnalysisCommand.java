package com.careerpilot.agent;

import com.careerpilot.jobs.JobPosting;
import com.careerpilot.users.User;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Decision step: which postings deserve expensive deep analysis within the remaining token budget. */
public class SelectForDeepAnalysisCommand extends AgentCommand {
    static final CommandType TYPE = CommandType.of("select_for_deep_analysis",
            "Pick the postings worth a deep AI analysis: quick score >= goal minimum, full detail, deadline-boosted, capped by the remaining token budget.",
            "{\"type\":\"object\",\"required\":[\"job_ids\"],\"properties\":{\"job_ids\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                    + "\"max_jobs\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":20}}}", false, false, 0, SelectForDeepAnalysisCommand::new);
    static final int COST_PER_JOB = AnalyzeJobCommand.TYPE.estimatedTokens() + MatchJobCommand.TYPE.estimatedTokens();
    static final int DEADLINE_BOOST = 10, BOOST_DAYS = 21;

    public SelectForDeepAnalysisCommand(AgentServices svc, User user, Map<String, Object> args, AgentRun run) { super(svc, user, args, run); }

    @Override public CommandType type() { return TYPE; }

    @Override
    @SuppressWarnings("unchecked")
    public Observation execute() {
        Map<String, Object> scores = run != null && run.getContext().get("quick_scores") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        int minimum = minMatchScore();
        int budgetLeft = run == null ? 1_000_000 : run.getTokenBudget() - run.getTokensUsed();
        int maxJobs = args.get("max_jobs") instanceof Number n ? n.intValue() : 10;
        int k = Math.min(maxJobs, budgetLeft / COST_PER_JOB);
        record Eligible(int score, JobPosting job) {}
        List<Eligible> eligible = new ArrayList<>();
        int summaryOnly = 0;
        for (JobPosting j : svc.jobs.findByUserAndIdIn(user, QuickScoreJobsCommand.ids(args.get("job_ids")))) {
            Object s = scores.get(j.getId().toString());
            if (!(s instanceof Number) || ((Number) s).intValue() < minimum) continue;
            if (j.getCompleteness() != JobPosting.Completeness.FULL) { summaryOnly++; continue; }
            long days = j.getDeadline() == null ? -1 : ChronoUnit.DAYS.between(LocalDate.now(), j.getDeadline());
            int boost = j.getDeadline() != null && days >= 0 && days <= BOOST_DAYS ? DEADLINE_BOOST : 0;
            eligible.add(new Eligible(((Number) s).intValue() + boost, j));
        }
        eligible.sort(Comparator.comparingInt(Eligible::score).reversed());
        List<String> chosen = eligible.stream().limit(Math.max(k, 0)).map(e -> e.job().getId().toString()).toList();
        if (run != null) {
            run.getContext().put("deep_ids", chosen);
            run.getContext().put("summary_only_candidates", summaryOnly);
        }
        return Observation.ok(chosen.size() + " selected for deep analysis (k=" + k + " from budget " + budgetLeft + " tokens, "
                + eligible.size() + " eligible, " + summaryOnly + " promising but summary-only)",
                map("selected", chosen, "k", k, "summary_only", summaryOnly));
    }
}
