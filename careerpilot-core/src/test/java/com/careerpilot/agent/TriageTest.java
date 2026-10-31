package com.careerpilot.agent;

import com.careerpilot.IntegrationTest;
import com.careerpilot.candidates.CandidateService;
import com.careerpilot.jobs.JobImportService;
import com.careerpilot.jobs.JobMatchRepository;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.jobs.JobPostingRepository;
import com.careerpilot.llm.MockLlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TriageTest extends IntegrationTest {
    @Autowired JobImportService imports;
    @Autowired AgentController agent;
    @Autowired AgentRunRepository runs;
    @Autowired AgentActionRepository actions;
    @Autowired JobPostingRepository jobs;
    @Autowired JobMatchRepository matches;
    @Autowired CandidateService candidates;

    static final String FULL = "We need Python, Django and SQL. Build REST APIs with Docker. ".repeat(10);

    @BeforeEach
    void scripted() {
        agent.setRunTokenBudget(40000);
        mock.script("job.analyze", Map.of("required_skills", List.of("python", "django", "sql"), "preferred_skills", List.of(),
                "responsibilities", List.of(), "seniority", "intern", "keywords", List.of(), "red_flags", List.of()));
        mock.script("match.explain", Map.of("explanation", "Fits.", "suggestions", List.of()));
    }

    private Map<String, Object> york(int good, int bad, boolean full) {
        List<Map<String, Object>> records = new ArrayList<>();
        String deadline = LocalDate.now().plusDays(60).toString();
        for (int i = 0; i < good; i++) {
            Map<String, Object> fields = new LinkedHashMap<>(Map.of("Job ID", "g" + i, "Job Title", "Backend Developer Co-op " + i,
                    "Organization", "Company " + i, "Work Term", "Summer 2027", "Application Deadline", deadline));
            Map<String, Object> rec = new LinkedHashMap<>(Map.of("url", "https://experience.yorku.ca/p/" + i, "fields", fields));
            if (full) rec.put("detail_text", FULL);
            records.add(rec);
        }
        for (int i = 0; i < bad; i++) {
            records.add(Map.of("url", "https://experience.yorku.ca/q/" + i, "detail_text", "Sell shoes in store. ".repeat(30),
                    "fields", Map.of("Job ID", "b" + i, "Job Title", "Retail Associate " + i, "Organization", "Shop " + i, "Work Term", "Fall 2027")));
        }
        return Map.of("records", records);
    }

    private AgentRun triageRun() {
        return runs.findByUserOrderByCreatedAtDesc(user).stream().filter(r -> r.getGoalType().equals("TRIAGE_BATCH")).findFirst().orElseThrow();
    }

    @Test
    void importEventTriggersTriageAndShortlists() {
        data.makeProfile(user);
        var batch = imports.createBatch(user, "york", york(3, 2, true));
        assertEquals(5, batch.getImportedCount());
        AgentRun run = triageRun();
        assertEquals(AgentRun.COMPLETED, run.getStatus());
        assertEquals("PLAYBOOK", run.getPlanSource());
        assertEquals(List.of("quick_score_jobs", "select_for_deep_analysis", "deep_match_jobs", "rank_jobs"),
                actions.findByRunOrderByExecutedAtAsc(run).stream().map(AgentActionRecord::getCommandName).toList());
        List<JobPosting> shortlisted = jobs.findByUser(user).stream().filter(JobPosting::isShortlisted).toList();
        assertEquals(3, shortlisted.size());
        assertTrue(shortlisted.stream().allMatch(j -> j.getTitle().contains("Backend")));
        assertTrue(run.getSummary().startsWith("3 of 5 shortlisted"), run.getSummary());
        assertTrue(shortlisted.stream().allMatch(j -> matches.findByJob(j).orElseThrow().isDeepAnalysis()));
    }

    @Test
    void summaryOnlyBatchReplansToQuickRanking() {
        data.makeProfile(user);
        imports.createBatch(user, "york", york(3, 2, false));
        AgentRun run = triageRun();
        assertEquals(1, run.getReplanCount());
        assertEquals(AgentRun.COMPLETED, run.getStatus());
        assertTrue(run.getDecisions().stream().anyMatch(d -> d.get("action").equals("REPLAN")));
        assertTrue(run.getSummary().contains("summary-only"));
        assertTrue(mock.calls.stream().noneMatch(c -> c.promptKey.equals("job.analyze")));  // no tokens spent
    }

    @Test
    void theTokenBudgetCapsDeepAnalysis() {
        agent.setRunTokenBudget(4500);  // room for exactly one deep job
        data.makeProfile(user);
        imports.createBatch(user, "york", york(3, 2, true));
        AgentRun run = triageRun();
        AgentActionRecord select = actions.findByRunOrderByExecutedAtAsc(run).stream().filter(a -> a.getCommandName().equals("select_for_deep_analysis")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) select.getObservation().get("data");
        assertEquals(1, ((Number) d.get("k")).intValue());
    }

    @Test
    void autoTriageCanBeDisabledAndTriggeredManually() {
        data.makeProfile(user);
        candidates.updateProfile(user, Map.of("auto_triage", false));
        var batch = imports.createBatch(user, "york", york(3, 2, true));
        assertTrue(runs.findByUserOrderByCreatedAtDesc(user).isEmpty());
        AgentRun manual = agent.runGoal(user, "Triage 5 newly imported postings", "TRIAGE_BATCH", null,
                Map.of("batch_job_ids", batch.importedJobIds().stream().map(Object::toString).toList()));
        assertEquals(AgentRun.COMPLETED, manual.getStatus());
    }

    @Test
    void noGoalMeansNoAutomaticTriage() {
        imports.createBatch(user, "york", york(3, 2, true));
        assertTrue(runs.findByUserOrderByCreatedAtDesc(user).isEmpty());
        assertNotNull(new MockLlmProvider());
    }
}
