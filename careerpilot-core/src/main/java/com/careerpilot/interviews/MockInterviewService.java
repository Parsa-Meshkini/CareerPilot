package com.careerpilot.interviews;

import com.careerpilot.agent.AgentMemoryEntry;
import com.careerpilot.agent.MemoryManager;
import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationRepository;
import com.careerpilot.common.ConflictException;
import com.careerpilot.jobs.JobAnalysis;
import com.careerpilot.jobs.JobAnalysisRepository;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.LlmException;
import com.careerpilot.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F18: text mock interview. Up to six questions; each answer is scored against a rubric (structure, relevance, specificity, impact,
 * clarity) with feedback; a model failure pauses the session so it can be resumed; recurring weaknesses become FEEDBACK memories
 * that the next preparation plan (F17) uses.
 */
@Service
@Transactional(noRollbackFor = LlmException.class)
public class MockInterviewService {
    static final int MIN_ANSWER_CHARS = 25;
    static final List<String> RUBRIC = List.of("structure", "relevance", "specificity", "impact", "clarity");

    private final LlmClient llm;
    private final ApplicationRepository applications;
    private final JobAnalysisRepository analyses;
    private final MockInterviewSessionRepository sessions;
    private final MemoryManager memory;

    public MockInterviewService(LlmClient llm, ApplicationRepository applications, JobAnalysisRepository analyses,
                                MockInterviewSessionRepository sessions, MemoryManager memory) {
        this.llm = llm;
        this.applications = applications;
        this.analyses = analyses;
        this.sessions = sessions;
        this.memory = memory;
    }

    public record AnswerResult(MockInterviewSession session, String feedback, boolean scored, boolean finished) {}

    public MockInterviewSession startSession(User user, UUID applicationId) {
        Application app = applications.owned(user, applicationId);
        MockInterviewSession s = new MockInterviewSession();
        s.setUser(user);
        s.setApplication(app);
        sessions.save(s);
        addQuestion(s, null);
        return sessions.save(s);
    }

    /** Scores the answer to the pending question and asks the next one. Too-short answers get no score and a request to elaborate. */
    public AnswerResult submitAnswer(User user, UUID sessionId, String answer) {
        MockInterviewSession s = sessions.owned(user, sessionId);
        if (s.getStatus() == MockInterviewSession.Status.FINISHED || s.getStatus() == MockInterviewSession.Status.ABANDONED)
            throw new ConflictException("this session is already " + s.getStatus().name().toLowerCase());
        Map<String, Object> turn = s.pendingTurn();
        if (turn == null) {  // resumed after a pause that happened while asking the next question
            addQuestion(s, null);
            sessions.save(s);
            turn = s.pendingTurn();
        }
        String text = answer == null ? "" : answer.strip();
        if (text.length() < MIN_ANSWER_CHARS)
            return new AnswerResult(s, "That answer is very short. Please elaborate: describe the situation, what you did, and the result.", false, false);

        JobPosting job = s.getApplication().getJob();
        JsonNode eval;
        try {
            eval = llm.generateStructured(user, "interview.evaluate", Map.of("job", job.getTitle() + " at " + job.getCompany(),
                    "question", String.valueOf(turn.get("question")), "answer", text)).data();
        } catch (LlmException e) {
            s.setStatus(MockInterviewSession.Status.PAUSED);  // nothing is lost: submit the same answer again to resume
            sessions.save(s);
            throw e;
        }
        s.setStatus(MockInterviewSession.Status.IN_PROGRESS);
        Map<String, Object> scores = new LinkedHashMap<>();
        double sum = 0;
        for (String item : RUBRIC) {
            int v = eval.path("scores").path(item).asInt(3);
            scores.put(item, v);
            sum += v;
        }
        List<Map<String, Object>> turns = new ArrayList<>(s.getTurns());
        Map<String, Object> done = new LinkedHashMap<>(turn);
        done.put("answer", text);
        done.put("scores", scores);
        done.put("average", Math.round(sum / RUBRIC.size() * 10) / 10.0);
        done.put("feedback", eval.path("feedback").asText(""));
        turns.set(turns.size() - 1, done);
        s.setTurns(turns);
        boolean finished = s.isFinishedByLength();
        if (finished) {
            finish(user, s);
        } else {
            String follow = eval.path("follow_up").asText("");
            addQuestion(s, follow.isBlank() ? null : follow);
        }
        sessions.save(s);
        return new AnswerResult(s, done.get("feedback").toString(), true, finished);
    }

    /** Ends the session early (or after the last answer) and stores the summary and recurring weaknesses in memory. */
    public MockInterviewSession finishSession(User user, UUID sessionId) {
        MockInterviewSession s = sessions.owned(user, sessionId);
        if (s.getStatus() == MockInterviewSession.Status.FINISHED) return s;
        finish(user, s);
        return sessions.save(s);
    }

    public MockInterviewSession abandon(User user, UUID sessionId) {
        MockInterviewSession s = sessions.owned(user, sessionId);
        s.setStatus(MockInterviewSession.Status.ABANDONED);  // saved as incomplete
        return sessions.save(s);
    }

    @Transactional(readOnly = true)
    public List<MockInterviewSession> listForApplication(User user, UUID applicationId) {
        return sessions.findByApplicationOrderByCreatedAtDesc(applications.owned(user, applicationId));
    }

    @Transactional(readOnly = true)
    public MockInterviewSession get(User user, UUID id) { return sessions.owned(user, id); }

    // ---- internals ----

    private void addQuestion(MockInterviewSession s, String followUp) {
        String question = followUp;
        if (question == null) {
            JobPosting job = s.getApplication().getJob();
            List<String> asked = s.getTurns().stream().map(t -> String.valueOf(t.get("question"))).toList();
            JobAnalysis analysis = analyses.findByJob(job).orElse(null);
            List<String> required = analysis == null ? List.of() : analysis.getRequiredSkills();
            try {
                question = llm.generateStructured(s.getUser(), "interview.question", Map.of("job", job.getTitle() + " at " + job.getCompany(), "required", required,
                        "asked", asked.isEmpty() ? List.of("(none)") : asked, "number", asked.size() + 1, "total", MockInterviewSession.MAX_QUESTIONS)).data().path("question").asText("");
            } catch (LlmException e) {
                question = "";
            }
            if (question.isBlank()) question = fallbackQuestion(asked.size(), job, required);
        }
        List<Map<String, Object>> turns = new ArrayList<>(s.getTurns());
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("n", turns.size() + 1);
        t.put("question", question);
        turns.add(t);
        s.setTurns(turns);
    }

    static String fallbackQuestion(int index, JobPosting job, List<String> required) {
        List<String> bank = new ArrayList<>(List.of("Tell me about yourself and why you applied to " + job.getCompany() + ".",
                "Describe a time you worked in a team and something went wrong. What did you do?", "Tell me about a project you are proud of and your part in it."));
        required.stream().limit(2).forEach(r -> bank.add("Walk me through how you have used " + r + " and what you would do differently now."));
        bank.add("Describe a time you had to learn something quickly.");
        bank.add("Why " + job.getTitle() + ", and what do you expect to do day to day?");
        return bank.get(index % bank.size());
    }

    private void finish(User user, MockInterviewSession s) {
        List<Map<String, Object>> answered = s.getTurns().stream().filter(t -> t.get("answer") != null).toList();
        // drop an unanswered trailing question
        s.setTurns(new ArrayList<>(answered));
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Double> averages = new LinkedHashMap<>();
        for (String item : RUBRIC)
            averages.put(item, Math.round(answered.stream().mapToInt(t -> ((Number) ((Map<?, ?>) t.get("scores")).get(item)).intValue()).average().orElse(0) * 10) / 10.0);
        summary.put("averages", averages);
        summary.put("overall_score", Math.round(averages.values().stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0);
        List<String> weaknesses = new ArrayList<>();
        try {
            JobPosting job = s.getApplication().getJob();
            JsonNode data = llm.generateStructured(user, "interview.summary", Map.of("job", job.getTitle() + " at " + job.getCompany(), "turns", answered)).data();
            summary.put("overall", data.path("overall").asText(""));
            summary.put("strengths", strings(data.path("strengths")));
            summary.put("improvements", strings(data.path("improvements")));
            weaknesses.addAll(strings(data.path("recurring_weaknesses")));
        } catch (LlmException e) {
            // deterministic summary: the lowest-scoring rubric items are the weaknesses
            summary.put("overall", "Summary computed without the AI model: your average score was " + summary.get("overall_score") + " out of 5.");
            averages.entrySet().stream().sorted(Map.Entry.comparingByValue()).limit(2).forEach(en -> weaknesses.add(en.getKey()));
            summary.put("strengths", List.of());
            summary.put("improvements", weaknesses.stream().map(w -> "Practise " + w).toList());
        }
        summary.put("recurring_weaknesses", weaknesses);
        s.setSummary(summary);
        s.setStatus(MockInterviewSession.Status.FINISHED);
        for (String w : weaknesses)
            memory.remember(user, AgentMemoryEntry.Kind.FEEDBACK, "Mock interview for " + s.getApplication().getJob().getTitle() + ": work on " + w, s.getId(), 0.7);
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }
}
