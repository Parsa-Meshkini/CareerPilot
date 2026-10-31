package com.careerpilot.jobs;

import com.careerpilot.IntegrationTest;
import com.careerpilot.agent.CandidateContext;
import com.careerpilot.agent.ContextBuilder;
import com.careerpilot.agent.ProfileIncompleteException;
import com.careerpilot.candidates.CandidateService;
import com.careerpilot.candidates.FactType;
import com.careerpilot.llm.LlmProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MatchingTest extends IntegrationTest {
    @Autowired MatchingService matching;
    @Autowired JobAnalysisService analysis;
    @Autowired ContextBuilder contexts;
    @Autowired CandidateService candidates;

    private static Map<String, Object> analysisJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("required_skills", List.of("Python", "Django", "AWS"));
        m.put("preferred_skills", List.of("Docker", "Kubernetes"));
        m.put("responsibilities", List.of("Build REST APIs"));
        m.put("seniority", "intern");
        m.put("years_required", null);
        m.put("keywords", List.of("rest"));
        m.put("red_flags", List.of());
        m.put("summary", "Backend co-op.");
        return m;
    }

    @Test
    void llmAnalysisIsSavedWithCanonicalSkills() {
        JobPosting job = data.makeJob(user);
        mock.script("job.analyze", analysisJson());
        JobAnalysis a = analysis.analyzeJob(user, job.getId(), null);
        assertEquals(List.of("python", "django", "aws"), a.getRequiredSkills());
        assertFalse(a.isFallback());
        assertEquals("mock-1", a.getModelUsed());
    }

    @Test
    void keywordFallbackWhenTheLlmFails() {
        JobPosting job = data.makeJob(user, "Backend Developer Co-op", "Acme Corp", "Summer 2027",
                "Unpaid co-op. Must know Java and SQL. 5+ years experience. ".repeat(8));
        mock.script("job.analyze", new LlmProviderException("down", false));
        JobAnalysis a = analysis.analyzeJob(user, job.getId(), null);
        assertTrue(a.isFallback());
        assertTrue(a.getRequiredSkills().containsAll(List.of("java", "sql")));
        assertTrue(a.getRedFlags().contains("Unpaid position"));
        assertTrue(a.getRedFlags().contains("Student role asking for 5+ years"));
    }

    @Test
    void strategyIsSelectedFromTheGoal() {
        data.makeProfile(user, "COOP", "Summer 2027", "backend", "python");
        assertInstanceOf(CoopInternshipStrategy.class, matching.selectStrategy(candidates.getCareerGoal(user), true));
        candidates.updateCareerGoal(user, Map.of("goal_type", "NEW_GRAD"));
        assertInstanceOf(EntryLevelStrategy.class, matching.selectStrategy(candidates.getCareerGoal(user), true));
    }

    @Test
    void deepMatchScoresExplainsAndListsGaps() {
        data.makeProfile(user);
        JobPosting job = data.makeJob(user);
        mock.script("job.analyze", analysisJson());
        mock.script("match.explain", Map.of("explanation", "Strong backend fit.",
                "suggestions", List.of(Map.of("skill", "aws", "suggestion", "Deploy a Django app"))));
        JobMatch m = matching.matchJob(user, job.getId(), true);
        assertEquals("coop_internship", m.getStrategyKey());
        assertTrue(m.isDeepAnalysis());
        assertTrue(m.getMatchedSkills().containsAll(List.of("python", "django", "docker")));
        assertEquals(List.of("aws"), m.getMissingSkills());
        assertTrue(m.getScore() >= 70, "score was " + m.getScore());
        assertEquals("Strong backend fit.", m.getExplanation());
    }

    @Test
    void coopWorkTermMismatchCapsTheScore() {
        data.makeProfile(user);
        JobPosting job = data.makeJob(user, "Backend Developer Co-op", "Acme Corp", "Fall 2027", "We need Python, Django and AWS experience. ".repeat(10));
        mock.script("job.analyze", analysisJson());
        JobMatch m = matching.matchJob(user, job.getId(), true);
        assertTrue(m.getScore() <= 45);
        assertTrue(m.getConstraintNotes().get(0).contains("Work term mismatch"));
    }

    @Test
    void entryLevelPenalizesSeniorRoles() {
        data.makeProfile(user, "NEW_GRAD", "", "backend", "python");
        JobPosting job = data.makeJob(user, "Senior Backend Engineer", "Acme Corp", "", "Python Django. ".repeat(30));
        Map<String, Object> a = analysisJson();
        a.put("seniority", "senior");
        a.put("years_required", 6);
        mock.script("job.analyze", a);
        JobMatch m = matching.matchJob(user, job.getId(), true);
        assertTrue(((Number) m.getBreakdown().get("experience")).doubleValue() <= 0.2);
        assertTrue(m.getConstraintNotes().stream().anyMatch(n -> n.contains("6+ years")));
    }

    @Test
    void careerSwitchCountsOnlyGroundedTransferableSkills() {
        data.makeProfile(user, "CAREER_SWITCH", "", "backend", "excel");
        JobPosting job = data.makeJob(user, "Data Analyst", "Acme Corp", "", "We need Python, Django and AWS experience. ".repeat(10));
        CandidateContext ctx = contexts.buildCandidateContext(user);
        String expId = ctx.factsOf(FactType.EXPERIENCE).get(0).getId().toString();
        Map<String, Object> a = analysisJson();
        a.put("required_skills", List.of("sql", "tableau"));
        a.put("preferred_skills", List.of());
        mock.script("job.analyze", a);
        mock.script("match.transferable", Map.of("mappings", List.of(
                Map.of("required_skill", "sql", "fact_ids", List.of(expId), "reason", "Built a REST API backed by a database"),
                Map.of("required_skill", "tableau", "fact_ids", List.of("made-up-id"), "reason", "invented"))));
        JobMatch m = matching.matchJob(user, job.getId(), true);
        assertEquals("career_switch", m.getStrategyKey());
        assertEquals(List.of("sql"), m.getTransferable().stream().map(t -> t.get("skill")).toList());
        assertEquals(0.25, ((Number) m.getBreakdown().get("skill")).doubleValue(), 1e-9);
    }

    @Test
    void quickMatchUsesNoLlm() {
        data.makeProfile(user, "CAREER_SWITCH", "", "backend", "python");
        JobPosting job = data.makeJob(user);
        JobMatch m = matching.matchJob(user, job.getId(), false);
        assertFalse(m.isDeepAnalysis());
        assertTrue(mock.calls.isEmpty());
    }

    @Test
    void incompleteProfileIsReported() {
        JobPosting job = data.makeJob(user);
        assertThrows(ProfileIncompleteException.class, () -> matching.matchJob(user, job.getId(), false));
    }

    @Test
    void goalChangeMarksMatchesOutdated() {
        data.makeProfile(user);
        JobPosting job = data.makeJob(user);
        matching.matchJob(user, job.getId(), false);
        candidates.updateCareerGoal(user, Map.of("goal_type", "NEW_GRAD"));
        assertTrue(matching.getMatch(job).isOutdated());
    }

    @Test
    void strategiesAreInterchangeable() {
        data.makeProfile(user);
        JobPosting job = data.makeJob(user);
        CandidateContext ctx = contexts.buildCandidateContext(user);
        QuickAnalysis view = matching.quickAnalysis(job);
        Set<String> keys = new java.util.HashSet<>();
        for (MatchScoringStrategy s : List.of(new CoopInternshipStrategy(), new EntryLevelStrategy(), new CareerSwitchStrategy(llm, false))) {
            int score = s.score(ctx, job, view).score();
            assertTrue(score >= 0 && score <= 100);
            keys.add(s.key());
        }
        assertEquals(3, keys.size());
    }
}
