package com.careerpilot.analytics;

import com.careerpilot.IntegrationTest;
import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.candidates.CandidateService;
import com.careerpilot.common.ConflictException;
import com.careerpilot.jobs.JobMatch;
import com.careerpilot.jobs.JobMatchRepository;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.llm.LlmProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyticsTest extends IntegrationTest {
    @Autowired AnalyticsService analytics;
    @Autowired ApplicationService applications;
    @Autowired JobMatchRepository matches;
    @Autowired CandidateService candidates;

    @BeforeEach
    void profile() { data.makeProfile(user); }

    /** Applies to a posting with the given match score and ends it as an interview or a rejection. */
    private void outcome(int index, int score, boolean interview) {
        JobPosting job = data.makeJob(user, "Backend Co-op " + index, "Company " + index, "Summer 2027", "Python. ".repeat(50));
        JobMatch m = new JobMatch();
        m.setUser(user);
        m.setJob(job);
        m.setScore(score);
        matches.save(m);
        Application a = applications.createApplication(user, job.getId(), "");
        applications.transition(user, a.getId(), "submit");
        applications.transition(user, a.getId(), interview ? "schedule_interview" : "reject");
    }

    private void evidence(int highInterviews, int highTotal, int lowInterviews, int lowTotal) {
        for (int i = 0; i < highTotal; i++) outcome(i, 85, i < highInterviews);
        for (int i = 0; i < lowTotal; i++) outcome(100 + i, 30, i < lowInterviews);
    }

    @Test
    void statisticsAreComputedByScoreBandSourceAndRoleWithoutAnyAiCall() {
        evidence(5, 6, 0, 6);
        OutcomeStats s = analytics.getInsights(user).stats();
        assertEquals(12, s.applied());
        assertEquals(12, s.withOutcome());
        assertEquals(5.0 / 12, s.overall().interviewRate(), 0.001);
        assertEquals(6, s.byScoreBand().stream().filter(g -> g.label().equals("80-100")).findFirst().orElseThrow().applied());
        assertEquals(0.0, s.byScoreBand().stream().filter(g -> g.label().equals("0-39")).findFirst().orElseThrow().interviewRate());
        assertEquals("backend", s.byRoleCategory().get(0).label());
        assertNotNull(s.medianDaysToResponse());
    }

    @Test
    void withTooFewOutcomesThereIsNoAdviceAndNothingIsInvented() {
        evidence(2, 3, 0, 3);
        var report = analytics.getInsights(user);
        assertFalse(report.enoughData());
        assertTrue(report.proposals().isEmpty());
        assertTrue(report.narrative().contains("Not enough data"));
        assertTrue(mock.calls.stream().noneMatch(c -> c.promptKey.equals("analytics.narrative")));  // no AI wording without evidence
    }

    @Test
    void strongEvidenceProposesABoundedAdjustmentAcceptingTunesWeightsAndRevertRestoresThem() {
        evidence(5, 6, 0, 6);
        mock.script("analytics.narrative", Map.of("narrative", "Higher-scored applications reach interviews much more often.",
                "explanations", List.of(Map.of("id", "0", "text", "Skill fit has predicted your interviews, so weight it a bit more."))));
        var report = analytics.getInsights(user);
        assertTrue(report.enoughData());
        StrategyAdjustment p = report.proposals().stream().filter(x -> x.getFactor().equals("skill")).findFirst().orElseThrow();
        assertEquals(0.45, p.getOldValue(), 1e-9);
        assertEquals(0.50, p.getNewValue(), 1e-9);
        assertEquals("Skill fit has predicted your interviews, so weight it a bit more.", p.getRationale());
        assertEquals(2, p.getEvidence().size());

        analytics.accept(user, p.getId());
        assertEquals(0.50, candidates.getCareerGoal(user).weights().get("skill"), 1e-9);
        assertThrows(ConflictException.class, () -> analytics.accept(user, p.getId()));
        analytics.revert(user, p.getId());
        assertEquals(0.45, candidates.getCareerGoal(user).weights().get("skill"), 1e-9);
        assertEquals(StrategyAdjustment.Status.REVERTED, analytics.history(user).stream().filter(a -> a.getId().equals(p.getId())).findFirst().orElseThrow().getStatus());
    }

    @Test
    void anAdviceFreeReportStillWorksWhenTheModelIsDown() {
        evidence(5, 6, 0, 6);
        mock.script("analytics.narrative", new LlmProviderException("down", false));
        var report = analytics.getInsights(user);
        assertFalse(report.proposals().isEmpty());
        assertTrue(report.narrative().contains("Interview rate"));
        assertTrue(report.proposals().get(0).getRationale().contains("predicts your results"));
    }

    @Test
    void whenScoresDoNotPredictResultsTheSkillWeightMayBeLoweredAndRejectingChangesNothing() {
        evidence(1, 6, 5, 6);
        var report = analytics.getInsights(user);
        StrategyAdjustment p = report.proposals().stream().filter(x -> x.getFactor().equals("skill")).findFirst().orElseThrow();
        assertEquals(0.40, p.getNewValue(), 1e-9);
        analytics.reject(user, p.getId());
        assertEquals(0.45, candidates.getCareerGoal(user).weights().get("skill"), 1e-9);
        assertThrows(ConflictException.class, () -> analytics.revert(user, p.getId()));
    }

    @Test
    void groupsSmallerThanFiveNeverProduceProposals() {
        evidence(4, 4, 0, 4);
        evidence(0, 0, 0, 0);
        assertTrue(analytics.getInsights(user).proposals().isEmpty());
    }
}
