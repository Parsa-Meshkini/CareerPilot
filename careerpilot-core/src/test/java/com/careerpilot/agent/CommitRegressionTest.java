package com.careerpilot.agent;

import com.careerpilot.CareerPilotCore;
import com.careerpilot.candidates.CandidateService;
import com.careerpilot.candidates.FactType;
import com.careerpilot.jobs.ImportBatch;
import com.careerpilot.jobs.JobImportService;
import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.MockLlmProvider;
import com.careerpilot.users.AuthService;
import com.careerpilot.users.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Not @Transactional on purpose: the import really commits. Regression for a bug found with a real LLM, where a tool inside the
 * triage run hit the token budget, the exception crossed a transactional service, and the import failed to commit with
 * "Transaction silently rolled back because it has been marked as rollback-only".
 */
@SpringBootTest(classes = CareerPilotCore.class, properties = {"spring.datasource.url=jdbc:h2:mem:commit-regression;DB_CLOSE_DELAY=-1",
        "spring.config.import=", "spring.main.web-application-type=none", "careerpilot.llm.provider=mock", "careerpilot.storage-root=target/test-storage"})
class CommitRegressionTest {
    @Autowired AuthService auth;
    @Autowired CandidateService candidates;
    @Autowired JobImportService imports;
    @Autowired AgentController agent;
    @Autowired AgentRunRepository runs;
    @Autowired LlmClient llm;

    @Test
    void importStillCommitsWhenTheTriageRunHitsItsTokenBudget() {
        MockLlmProvider mock = new MockLlmProvider();
        llm.useProvider(mock, ms -> { });
        mock.script("job.analyze", Map.of("required_skills", List.of("python"), "preferred_skills", List.of(), "responsibilities", List.of(),
                "seniority", "intern", "keywords", List.of(), "red_flags", List.of()));
        User user = auth.register("commit-" + System.nanoTime() + "@yorku.ca", "password123", "Commit");
        candidates.addFact(user, FactType.SKILL, Map.of("name", "python"));
        candidates.updateCareerGoal(user, Map.of("goal_type", "COOP", "work_term", "Summer 2027"));
        // every deep match really costs more than the planner's per-job estimate (4000), so the budget runs out mid-run
        mock.script("match.explain", Map.of("explanation", "Long explanation. ".repeat(350), "suggestions", List.of()));
        agent.setRunTokenBudget(8100);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            rows.add(Map.of("title", "Python Co-op " + i, "company", "Company " + i, "work_term", "Summer 2027", "location", "Toronto",
                    "description", "We need Python and Django. Build REST APIs. ".repeat(270)));
        ImportBatch batch = imports.createBatch(user, "file", Map.of("records", rows));  // used to throw UnexpectedRollbackException

        assertEquals(4, imports.getBatch(user, batch.getId()).getImportedCount());
        assertEquals(4, imports.listJobs(user, null, null, null, false).size());
        AgentRun run = runs.findByUserOrderByCreatedAtDesc(user).get(0);
        assertTrue(run.getStatus().equals(AgentRun.COMPLETED) || run.getStatus().equals(AgentRun.ABORTED), run.getStatus());
        assertTrue(run.getTokensUsed() > 4000, "budget was really spent: " + run.getTokensUsed());
        agent.setRunTokenBudget(40000);
    }
}
