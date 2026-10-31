package com.careerpilot.agent;

import com.careerpilot.IntegrationTest;
import com.careerpilot.applications.ApplicationRepository;
import com.careerpilot.candidates.CandidateService;
import com.careerpilot.candidates.FactType;
import com.careerpilot.jobs.JobPosting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunTest extends IntegrationTest {
    @Autowired AgentController agent;
    @Autowired ToolManager tools;
    @Autowired ApplicationRepository applications;
    @Autowired AgentMemoryRepository memories;
    @Autowired CandidateService candidates;
    @Autowired ContextBuilder contexts;
    @Autowired AgentRunRepository runs;

    private AgentRun newRun() {
        AgentRun run = new AgentRun();
        run.setUser(user);
        run.setGoalText("test");
        return runs.save(run);
    }

    JobPosting job;
    String expId;

    static final Map<String, Object> ANALYSIS = Map.of("required_skills", List.of("python", "django"), "preferred_skills", List.of(),
            "responsibilities", List.of(), "seniority", "intern", "keywords", List.of(), "red_flags", List.of());

    @BeforeEach
    void ready() {
        agent.setRunTokenBudget(40000);
        data.makeProfile(user);
        job = data.makeJob(user, "Backend Developer Co-op", "Acme Corp", "Summer 2027", "We need Python, Django and AWS experience. ".repeat(10));
        expId = contexts.buildCandidateContext(user).factsOf(FactType.EXPERIENCE).get(0).getId().toString();
        mock.script("job.analyze", ANALYSIS);
        mock.script("resume.tailor", Map.of("entries", List.of(Map.of("fact_id", expId, "bullets", List.of(
                Map.of("text", "Built a REST API with Docker", "source_fact_ids", List.of(expId))))), "skills", List.of("python")));
        mock.script("cover_letter.tailor", Map.of("greeting", "Dear Hiring Manager,", "closing", "Sincerely,",
                "paragraphs", List.of(Map.of("text", "I built a REST API at Acme.", "source_fact_ids", List.of(expId)))));
        mock.script("match.explain", Map.of("explanation", "Good fit.", "suggestions", List.of()));
    }

    private Map<String, Object> plan() {
        return Map.of("steps", List.of(
                Map.of("tool", "search_jobs", "args", Map.of("query", "acme backend"), "intent", "find it"),
                Map.of("tool", "match_job", "args", Map.of("job_id", "@focus_job", "deep", true), "intent", "check fit"),
                Map.of("tool", "build_package", "args", Map.of("job_id", "@focus_job"), "intent", "prepare documents")),
                "notes", "Submission is up to you.");
    }

    @Test
    void llmPlanRunsUntilApprovalThenCompletes() {
        mock.script("agent.plan", plan());
        AgentRun run = agent.runGoal(user, "Prepare my application for the Acme backend co-op");
        assertEquals("PREPARE_APPLICATION", run.getGoalType());
        assertEquals("LLM", run.getPlanSource());
        assertEquals(AgentRun.AWAITING_APPROVAL, run.getStatus());
        assertTrue(run.getPending().get("description").toString().startsWith("build_package"));
        assertEquals(List.of("search_jobs", "match_job"), agent.actions(user, run.getId()).stream().map(AgentActionRecord::getCommandName).toList());

        run = agent.resumeRun(user, run.getId(), true, "");
        assertEquals(AgentRun.COMPLETED, run.getStatus());
        assertTrue(run.getSummary().contains("package"));
        assertEquals("PREPARED", applications.findByUserAndJob(user, job).orElseThrow().getStateName());
        assertTrue(memories.findByUserAndKindOrderByCreatedAtDesc(user, AgentMemoryEntry.Kind.EPISODIC).size() > 0);
    }

    @Test
    void invalidLlmPlansFallBackToThePlaybook() {
        mock.script("agent.plan", Map.of("steps", List.of(Map.of("tool", "submit_application", "args", Map.of(), "intent", "x"))),
                Map.of("steps", List.of(Map.of("tool", "match_job", "args", Map.of("job_id", "not-mine"), "intent", "x"))));
        AgentRun run = agent.runGoal(user, "Prepare my application for the Acme backend co-op");
        assertEquals("PLAYBOOK", run.getPlanSource());
        assertEquals(List.of("search_jobs", "retrieve_context", "analyze_job", "match_job", "build_package"),
                run.getPlan().stream().map(s -> s.get("tool")).toList());
        assertTrue(mock.calls.get(1).prompt.contains("invalid"));
    }

    @Test
    void rejectingAStepReplansAndStoresAPreference() {
        mock.script("agent.plan", plan());
        mock.script("agent.replan", Map.of("steps", List.of(), "rationale", "Nothing else to do without the package."));
        AgentRun run = agent.runGoal(user, "Prepare my Acme backend application");
        run = agent.resumeRun(user, run.getId(), false, "I will write my own");
        assertEquals(AgentRun.COMPLETED, run.getStatus());
        assertEquals(1, run.getReplanCount());
        assertTrue(agent.actions(user, run.getId()).stream().anyMatch(a -> a.getStatus().equals("REJECTED")));
        assertTrue(memories.findByUserAndKindOrderByCreatedAtDesc(user, AgentMemoryEntry.Kind.PREFERENCE).stream().anyMatch(m -> m.getContent().contains("own")));
    }

    @Test
    void ambiguousSearchAsksTheStudentWhichJob() {
        data.makeJob(user, "Backend Intern", "Acme Labs", "Summer 2027", "Python. ".repeat(60));
        mock.script("agent.plan", plan());
        AgentRun run = agent.runGoal(user, "Prepare my Acme backend application");
        assertEquals(AgentRun.AWAITING_INPUT, run.getStatus());
        assertEquals(2, ((List<?>) run.getPending().get("options")).size());
        run = agent.answer(user, run.getId(), job.getId().toString());
        assertEquals(AgentRun.AWAITING_APPROVAL, run.getStatus());
        assertEquals(job.getId().toString(), run.getContext().get("focus_job_id"));
    }

    @Test
    void weakMatchTriggersLlmReflection() {
        JobPosting weak = data.makeJob(user, "Sales Lead", "Zeta", "", "Retail sales. ".repeat(40));
        mock.replace("job.analyze", Map.of("required_skills", List.of("salesforce", "negotiation"), "preferred_skills", List.of(),
                "responsibilities", List.of(), "seniority", "senior", "keywords", List.of(), "red_flags", List.of()));
        mock.script("agent.plan", Map.of("steps", List.of(
                Map.of("tool", "match_job", "args", Map.of("job_id", "@focus_job"), "intent", "check fit"),
                Map.of("tool", "build_package", "args", Map.of("job_id", "@focus_job"), "intent", "documents"))));
        mock.script("agent.reflect", Map.of("action", "ASK_USER", "rationale", "This is a poor fit; continue?"));
        AgentRun run = agent.runGoal(user, "Prepare an application for this job", null, weak.getId(), null);
        assertEquals(AgentRun.AWAITING_INPUT, run.getStatus());
        assertEquals("llm", run.getDecisions().get(run.getDecisions().size() - 1).get("source"));
        run = agent.answer(user, run.getId(), "stop");
        assertEquals(AgentRun.COMPLETED, run.getStatus());
        assertTrue(run.getSummary().contains("Stopped"));
    }

    @Test
    void forbiddenRequestsAreExplainedNotExecuted() {
        mock.script("agent.plan", Map.of("steps", List.of(Map.of("tool", "search_jobs", "args", Map.of("query", "acme"), "intent", "find"))));
        AgentRun run = agent.runGoal(user, "Submit my application to Acme for me");
        assertEquals(AgentRun.COMPLETED, run.getStatus());
        assertTrue(run.getSummary().contains("never submits applications"));
    }

    @Test
    void toolFailureRetriesThenReplans() {
        mock.script("agent.plan", Map.of("steps", List.of(Map.of("tool", "match_job", "args", Map.of("job_id", job.getId().toString()), "intent", "check"))));
        mock.script("agent.replan", Map.of("steps", List.of(), "rationale", "set up your goal first"));
        var fresh = auth.register("nogoal-" + java.util.UUID.randomUUID() + "@yorku.ca", "password123", "No Goal");
        candidates.addFact(fresh, FactType.SKILL, Map.of("name", "python"));
        JobPosting theirs = data.makeJob(fresh, "Backend Developer Co-op", "Acme Corp", "Summer 2027", "Python. ".repeat(50));
        mock.replace("agent.plan", Map.of("steps", List.of(Map.of("tool", "match_job", "args", Map.of("job_id", theirs.getId().toString()), "intent", "check"))));
        AgentRun run = agent.runGoal(fresh, "how well do I fit the acme job?", "FREE_FORM", null, null);
        assertEquals(List.of("RETRY", "REPLAN"), run.getDecisions().stream().limit(2).map(d -> d.get("action")).toList());
        assertEquals(AgentRun.COMPLETED, run.getStatus());
    }

    @Test
    void budgetExhaustionAbortsWithAPartialResult() {
        agent.setRunTokenBudget(1);
        AgentRun run = agent.runGoal(user, "Prepare my Acme backend application");
        assertEquals("PLAYBOOK", run.getPlanSource());  // planning itself was over budget
        assertEquals(AgentRun.ABORTED, run.getStatus());
        assertTrue(run.getError().contains("budget"));
        assertEquals(0, run.getTokensUsed());
    }

    @Test
    void agentStatusChangeCanBeUndone() {
        mock.script("agent.plan", Map.of("steps", List.of(Map.of("tool", "update_application_status",
                "args", Map.of("job_id", job.getId().toString(), "action", "mark_prepared"), "intent", "mark prepared"))));
        AgentRun run = agent.runGoal(user, "mark the acme job prepared", "FREE_FORM", null, null);
        run = agent.resumeRun(user, run.getId(), true, "");
        AgentActionRecord action = agent.actions(user, run.getId()).get(0);
        assertTrue(action.isReversible());
        assertEquals("PREPARED", applications.findByUserAndJob(user, job).orElseThrow().getStateName());
        assertEquals("UNDONE", agent.undoAction(user, action.getId()).getStatus());
        assertEquals("SAVED", applications.findByUserAndJob(user, job).orElseThrow().getStateName());
    }

    // ---- tools ----

    @Test
    void catalogHasNoSubmitEmailOrLoginTool() {
        var names = tools.catalog().keySet();
        assertTrue(names.contains("search_jobs"));
        assertTrue(names.stream().noneMatch(n -> n.contains("submit") || n.contains("email") || n.contains("login")));
    }

    @Test
    void focusReferencesAreResolvedAndArgumentsValidated() {
        AgentRun run = new AgentRun();
        run.setUser(user);
        run.setGoalText("x");
        run.getContext().put("focus_job_id", job.getId().toString());
        AgentCommand cmd = tools.createCommand(Map.of("tool", "match_job", "args", Map.of("job_id", "@focus_job")), user, run);
        assertEquals(Map.of("job_id", job.getId().toString()), cmd.arguments());
        assertThrows(com.careerpilot.common.ValidationException.class, () -> tools.createCommand(Map.of("tool", "match_job", "args", Map.of()), user, run));
        assertThrows(com.careerpilot.common.ValidationException.class, () -> tools.createCommand(Map.of("tool", "submit_application", "args", Map.of()), user, run));
    }

    @Test
    void aFailingToolIsLoggedNotThrown() {
        AgentRun run = newRun();
        AgentCommand cmd = tools.createCommand(Map.of("tool", "analyze_job", "args", Map.of("job_id", java.util.UUID.randomUUID().toString())), user, run);
        AgentActionRecord[] rec = new AgentActionRecord[1];
        Observation obs = tools.execute(cmd, 0, rec);
        assertFalse(obs.ok);
        assertEquals(AgentActionRecord.FAILED, rec[0].getStatus());
    }

    @Test
    void rankJobsShortlistsAboveTheMinimumAndUndoRestores() {
        JobPosting retail = data.makeJob(user, "Retail Clerk", "Shop", "", "Sell things. ".repeat(40));
        AgentRun run = newRun();
        List<String> ids = List.of(job.getId().toString(), retail.getId().toString());
        tools.execute(tools.createCommand(Map.of("tool", "quick_score_jobs", "args", Map.of("job_ids", ids)), user, run), 0);
        AgentActionRecord[] rec = new AgentActionRecord[1];
        Observation obs = tools.execute(tools.createCommand(Map.of("tool", "rank_jobs", "args", Map.of("job_ids", ids)), user, run), 1, rec);
        @SuppressWarnings("unchecked") List<Map<String, Object>> shortlist = (List<Map<String, Object>>) obs.data.get("shortlist");
        assertEquals(List.of("Backend Developer Co-op @ Acme Corp"), shortlist.stream().map(s -> s.get("label")).toList());
        assertTrue(job.isShortlisted());
        assertTrue(rec[0].isReversible());
        tools.undo(user, rec[0].getId());
        assertFalse(job.isShortlisted());
    }

    @Test
    void memoryRecallPrefersRelevantAndRecentEntries() {
        MemoryManager memory = new MemoryManager(memories);
        memory.remember(user, AgentMemoryEntry.Kind.EPISODIC, "Prepared the Acme backend application");
        memory.remember(user, AgentMemoryEntry.Kind.EPISODIC, "Imported a batch of retail jobs");
        memory.remember(user, AgentMemoryEntry.Kind.PREFERENCE, "Not interested in sales roles");
        List<AgentMemoryEntry> recalled = memory.recall(user, "acme backend", 2);
        assertEquals("Prepared the Acme backend application", recalled.get(0).getContent());
        assertEquals(2, recalled.size());
    }
}
