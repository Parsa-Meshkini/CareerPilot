package com.careerpilot.jobs;

import com.careerpilot.agent.CandidateContext;
import com.careerpilot.agent.ContextBuilder;
import com.careerpilot.candidates.CareerGoal;
import com.careerpilot.common.ValidationException;
import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.Events;
import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.LlmException;
import com.careerpilot.llm.TokenBudget;
import com.careerpilot.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F08: candidate-job matching. The context of the Strategy pattern: it selects a {@link MatchScoringStrategy} from the
 * career goal and delegates scoring. Quick scores are deterministic; deep analysis adds an LLM-written explanation.
 */
@Service
@Transactional
public class MatchingService {
    private final LlmClient llm;
    private final ContextBuilder contexts;
    private final JobAnalysisService analysisService;
    private final JobPostingRepository jobs;
    private final JobAnalysisRepository analyses;
    private final JobMatchRepository matches;
    private final DomainEventBus bus;
    private final Map<String, MatchScoringStrategy> strategies = new LinkedHashMap<>();

    public MatchingService(LlmClient llm, ContextBuilder contexts, JobAnalysisService analysisService, JobPostingRepository jobs,
                           JobAnalysisRepository analyses, JobMatchRepository matches, DomainEventBus bus) {
        this.llm = llm;
        this.contexts = contexts;
        this.analysisService = analysisService;
        this.jobs = jobs;
        this.analyses = analyses;
        this.matches = matches;
        this.bus = bus;
        for (MatchScoringStrategy s : List.of(new CoopInternshipStrategy(), new EntryLevelStrategy(), new CareerSwitchStrategy(llm, true)))
            strategies.put(s.key(), s);
    }

    /** Observer: scores computed under an old goal are flagged outdated when the goal changes. */
    @PostConstruct
    void subscribe() {
        bus.subscribe(Events.CareerGoalChanged.class, event -> matches.markOutdated(event.userId()));
    }

    public MatchScoringStrategy selectStrategy(CareerGoal goal, boolean deep) {
        MatchScoringStrategy strategy = strategies.get(goal.strategyKey());
        if (strategy == null) throw new ValidationException("no matching strategy for goal type " + goal.getGoalType());
        if (strategy instanceof CareerSwitchStrategy && !deep) return new CareerSwitchStrategy(llm, false);  // quick scores never spend LLM tokens
        return strategy;
    }

    public JobMatch matchJob(User user, UUID jobId, boolean deep, TokenBudget run, CandidateContext ctxIn) {
        CandidateContext ctx = ctxIn != null ? ctxIn : contexts.buildCandidateContext(user);
        JobPosting job = jobs.owned(user, jobId);
        JobAnalysis analysis = analyses.findByJob(job).orElse(null);
        if (deep && analysis == null) analysis = analysisService.analyzeJob(user, job.getId(), run);
        AnalysisView view = analysis != null ? analysis : quickAnalysis(job);
        MatchResult result = selectStrategy(ctx.goal, deep).score(ctx, job, view);
        JobMatch match = matches.findByJob(job).orElseGet(() -> {
            JobMatch fresh = new JobMatch();
            fresh.setUser(user);
            fresh.setJob(job);
            return fresh;
        });
        match.setScore(result.score());
        match.setStrategyKey(result.strategyKey());
        match.setBreakdown(result.breakdown());
        match.setMatchedSkills(result.matchedSkills());
        match.setMissingSkills(result.missingSkills());
        match.setTransferable(result.transferable());
        match.setConstraintNotes(result.constraintNotes());
        match.setDeepAnalysis(analysis != null);
        match.setOutdated(false);
        match.setCreatedAt(java.time.Instant.now());
        if (deep) explainMatch(match, ctx, run);
        return matches.save(match);
    }

    public JobMatch matchJob(User user, UUID jobId, boolean deep) { return matchJob(user, jobId, deep, null, null); }

    public QuickAnalysis quickAnalysis(JobPosting job) {
        Map<String, Object> data = JobAnalysisService.fallbackKeywordAnalysis(job.getTitle() + " " + job.getDescription());
        @SuppressWarnings("unchecked") List<String> skills = (List<String>) data.get("required_skills");
        return new QuickAnalysis(skills, (String) data.get("seniority"), (Integer) data.get("years_required"));
    }

    void explainMatch(JobMatch match, CandidateContext ctx, TokenBudget run) {
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("job", match.getJob().getTitle() + " at " + match.getJob().getCompany());
            vars.put("score", match.getScore());
            vars.put("strategy", match.getStrategyKey());
            vars.put("breakdown", match.getBreakdown());
            vars.put("matched", match.getMatchedSkills());
            vars.put("missing", match.getMissingSkills());
            vars.put("notes", match.getConstraintNotes());
            vars.put("candidate", ctx.summaryText());
            JsonNode data = llm.generateStructured(ctx.user, "match.explain", vars, run).data();
            match.setExplanation(data.path("explanation").asText(""));
            List<Map<String, Object>> suggestions = new ArrayList<>();
            for (JsonNode s : data.path("suggestions")) {
                if (suggestions.size() >= 6) break;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("skill", s.path("skill").asText(""));
                m.put("suggestion", s.path("suggestion").asText(""));
                suggestions.add(m);
            }
            match.setSuggestions(suggestions);
        } catch (LlmException e) {
            // the score stands on its own without the narrative
        }
    }

    public List<JobMatch> rankJobs(User user, List<UUID> jobIds, Set<UUID> deepIds) {
        CandidateContext ctx = contexts.buildCandidateContext(user);
        return jobIds.stream().map(id -> matchJob(user, id, deepIds.contains(id), null, ctx))
                .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore())).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public JobMatch getMatch(JobPosting job) { return matches.findByJob(job).orElse(null); }
}
