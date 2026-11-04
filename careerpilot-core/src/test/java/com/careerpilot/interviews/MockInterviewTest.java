package com.careerpilot.interviews;

import com.careerpilot.IntegrationTest;
import com.careerpilot.agent.AgentMemoryEntry;
import com.careerpilot.agent.MemoryManager;
import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.common.ConflictException;
import com.careerpilot.llm.LlmProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MockInterviewTest extends IntegrationTest {
    @Autowired MockInterviewService interviews;
    @Autowired ApplicationService applications;
    @Autowired MemoryManager memory;
    Application app;

    static final String ANSWER = "At Acme I was asked to build a REST API; I designed the endpoints, wrote the tests and shipped it, which cut report time in half.";

    @BeforeEach
    void setup() {
        app = applications.createApplication(user, data.makeJob(user).getId(), "");
        mock.script("job.analyze", Map.of("required_skills", List.of("python"), "preferred_skills", List.of(), "responsibilities", List.of(),
                "seniority", "intern", "keywords", List.of(), "red_flags", List.of()));
        mock.script("interview.question", Map.of("question", "Tell me about a project you built."));
        mock.script("interview.evaluate", Map.of("scores", Map.of("structure", 4, "relevance", 5, "specificity", 3, "impact", 2, "clarity", 4),
                "feedback", "Good structure. Add a number for the impact.", "follow_up", ""));
        mock.script("interview.summary", Map.of("overall", "A solid start.", "strengths", List.of("Clear structure"), "improvements", List.of("Quantify results"),
                "recurring_weaknesses", List.of("quantifying impact")));
    }

    @Test
    void aFullSessionScoresEachAnswerSummarisesAndStoresFeedbackMemory() {
        MockInterviewSession s = interviews.startSession(user, app.getId());
        assertEquals("Tell me about a project you built.", s.getTurns().get(0).get("question"));
        MockInterviewService.AnswerResult last = null;
        for (int i = 0; i < MockInterviewSession.MAX_QUESTIONS; i++) last = interviews.submitAnswer(user, s.getId(), ANSWER);
        assertTrue(last.scored());
        assertTrue(last.finished());
        MockInterviewSession done = interviews.get(user, s.getId());
        assertEquals(MockInterviewSession.Status.FINISHED, done.getStatus());
        assertEquals(6, done.getTurns().size());
        assertEquals(3.6, (Double) done.getTurns().get(0).get("average"), 1e-9);
        assertEquals("A solid start.", done.getSummary().get("overall"));
        assertTrue(memory.all(user).stream().anyMatch(m -> m.getKind() == AgentMemoryEntry.Kind.FEEDBACK && m.getContent().contains("quantifying impact")));
        assertThrows(ConflictException.class, () -> interviews.submitAnswer(user, s.getId(), ANSWER));
    }

    @Test
    void aTooShortAnswerAsksToElaborateWithoutScoring() {
        MockInterviewSession s = interviews.startSession(user, app.getId());
        var r = interviews.submitAnswer(user, s.getId(), "I did stuff");
        assertFalse(r.scored());
        assertTrue(r.feedback().contains("elaborate"));
        assertEquals(1, interviews.get(user, s.getId()).getTurns().size());
        assertNull(interviews.get(user, s.getId()).getTurns().get(0).get("answer"));
    }

    @Test
    void aModelFailurePausesTheSessionAndTheSameAnswerResumesIt() {
        MockInterviewSession s = interviews.startSession(user, app.getId());
        mock.replace("interview.evaluate", new LlmProviderException("down", false));
        assertThrows(LlmProviderException.class, () -> interviews.submitAnswer(user, s.getId(), ANSWER));
        assertEquals(MockInterviewSession.Status.PAUSED, interviews.get(user, s.getId()).getStatus());
        mock.replace("interview.evaluate", Map.of("scores", Map.of("structure", 4, "relevance", 4, "specificity", 4, "impact", 4, "clarity", 4), "feedback", "Good."));
        var r = interviews.submitAnswer(user, s.getId(), ANSWER);
        assertTrue(r.scored());
        assertEquals(MockInterviewSession.Status.IN_PROGRESS, interviews.get(user, s.getId()).getStatus());
    }

    @Test
    void withoutTheModelQuestionsAndSummaryAreDeterministic() {
        mock.replace("interview.question", new LlmProviderException("down", false));
        mock.replace("interview.summary", new LlmProviderException("down", false));
        MockInterviewSession s = interviews.startSession(user, app.getId());
        assertTrue(s.getTurns().get(0).get("question").toString().startsWith("Tell me about yourself"));
        interviews.submitAnswer(user, s.getId(), ANSWER);
        MockInterviewSession done = interviews.finishSession(user, s.getId());
        assertEquals(1, done.getTurns().size());  // the unanswered trailing question was dropped
        assertTrue(done.getSummary().get("overall").toString().contains("without the AI model"));
        assertEquals(List.of("impact", "specificity"), done.getSummary().get("recurring_weaknesses"));
    }

    @Test
    void abandoningKeepsTheSessionAsIncomplete() {
        MockInterviewSession s = interviews.startSession(user, app.getId());
        assertEquals(MockInterviewSession.Status.ABANDONED, interviews.abandon(user, s.getId()).getStatus());
        assertEquals(1, interviews.listForApplication(user, app.getId()).size());
    }
}
