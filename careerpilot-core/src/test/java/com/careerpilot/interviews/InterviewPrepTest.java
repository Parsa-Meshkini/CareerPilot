package com.careerpilot.interviews;

import com.careerpilot.IntegrationTest;
import com.careerpilot.agent.ContextBuilder;
import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.candidates.FactType;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.llm.LlmProviderException;
import com.careerpilot.notifications.Notification;
import com.careerpilot.notifications.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InterviewPrepTest extends IntegrationTest {
    @Autowired InterviewPrepService prep;
    @Autowired ApplicationService applications;
    @Autowired ContextBuilder contexts;
    @Autowired NotificationService notifications;

    Application app;
    String expId;

    @BeforeEach
    void setup() {
        data.makeProfile(user);
        JobPosting job = data.makeJob(user);
        app = applications.createApplication(user, job.getId(), "");
        expId = contexts.buildCandidateContext(user).factsOf(FactType.EXPERIENCE).get(0).getId().toString();
        mock.script("job.analyze", Map.of("required_skills", List.of("python", "django"), "preferred_skills", List.of(), "responsibilities", List.of("Build REST APIs"),
                "seniority", "intern", "keywords", List.of(), "red_flags", List.of()));
    }

    private Map<String, Object> story(String title, String result, List<String> ids) {
        return Map.of("title", title, "situation", "At Acme I was a developer intern", "task", "Build a REST API", "action", "I built it with Docker", "result", result, "source_fact_ids", ids);
    }

    @Test
    void aTailoredPlanKeepsSupportedStoriesAndDropsFabricatedOnes() {
        mock.script("interview.plan", Map.of(
                "questions", List.of(Map.of("category", "technical", "question", "How have you used Django?", "rationale", "Required skill")),
                "star_stories", List.of(story("REST API", "It shipped", List.of(expId)), story("Big win", "Cut latency by 70%", List.of(expId)), story("No source", "It worked", List.of())),
                "checklist", List.of("Research the company")));
        InterviewPrepPlan plan = prep.preparePlan(user, app.getId());
        assertEquals("LLM", plan.getSource());
        List<?> stories = (List<?>) plan.getContent().get("star_stories");
        assertEquals(1, stories.size());
        assertEquals("REST API", ((Map<?, ?>) stories.get(0)).get("title"));
        assertTrue(plan.getNotes().contains("Big win"));
        assertTrue(plan.getNotes().contains("No source"));
    }

    @Test
    void whenTheModelIsDownADeterministicQuestionBankIsUsed() {
        mock.script("interview.plan", new LlmProviderException("down", false));
        InterviewPrepPlan plan = prep.preparePlan(user, app.getId());
        assertEquals("FALLBACK", plan.getSource());
        List<?> questions = (List<?>) plan.getContent().get("questions");
        assertTrue(questions.size() >= 6);
        assertTrue(questions.stream().anyMatch(q -> ((Map<?, ?>) q).get("question").toString().contains("django")));
        assertEquals(2, prep.preparePlan(user, app.getId()).getVersion());
    }

    @Test
    void reachingTheInterviewStateGeneratesAPlanAndNotifies() {
        mock.script("interview.plan", new LlmProviderException("down", false));
        applications.transition(user, app.getId(), "submit");
        assertNull(prep.latest(user, app.getId()));
        applications.transition(user, app.getId(), "schedule_interview");
        assertNotNull(prep.latest(user, app.getId()));
        assertTrue(notifications.list(user).stream().anyMatch(n -> n.getKind() == Notification.Kind.PREP_READY));
        applications.transition(user, app.getId(), "schedule_interview");  // another round must not create a second plan
        assertEquals(1, prep.latest(user, app.getId()).getVersion());
    }
}
