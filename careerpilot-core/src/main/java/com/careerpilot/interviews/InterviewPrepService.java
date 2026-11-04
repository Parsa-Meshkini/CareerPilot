package com.careerpilot.interviews;

import com.careerpilot.agent.AgentMemoryEntry;
import com.careerpilot.agent.CandidateContext;
import com.careerpilot.agent.ContextBuilder;
import com.careerpilot.agent.MemoryManager;
import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationRepository;
import com.careerpilot.documents.Claim;
import com.careerpilot.documents.FabricationGuard;
import com.careerpilot.documents.VerificationReport;
import com.careerpilot.jobs.JobAnalysis;
import com.careerpilot.jobs.JobAnalysisRepository;
import com.careerpilot.jobs.JobAnalysisService;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.LlmException;
import com.careerpilot.llm.LlmResult;
import com.careerpilot.users.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F17: interview preparation plan. AI-written questions and STAR stories; every STAR story is checked by the FabricationGuard and
 * removed when it is not supported by the student's verified facts. With no model available a deterministic question bank is used.
 */
@Service
@Transactional
public class InterviewPrepService {
    private final LlmClient llm;
    private final ContextBuilder contexts;
    private final ApplicationRepository applications;
    private final JobAnalysisRepository analyses;
    private final JobAnalysisService analysisService;
    private final InterviewPrepPlanRepository plans;
    private final MemoryManager memory;

    public InterviewPrepService(LlmClient llm, ContextBuilder contexts, ApplicationRepository applications, JobAnalysisRepository analyses,
                                JobAnalysisService analysisService, InterviewPrepPlanRepository plans, MemoryManager memory) {
        this.llm = llm;
        this.contexts = contexts;
        this.applications = applications;
        this.analyses = analyses;
        this.analysisService = analysisService;
        this.plans = plans;
        this.memory = memory;
    }

    public InterviewPrepPlan preparePlan(User user, UUID applicationId) {
        Application app = applications.owned(user, applicationId);
        JobPosting job = app.getJob();
        CandidateContext ctx = contexts.buildCandidateContext(user, false, false);
        JobAnalysis analysis = analyses.findByJob(job).orElseGet(() -> analysisService.analyzeJob(user, job.getId(), null));
        List<String> weak = memory.recall(user, "interview feedback " + job.getTitle(), List.of(AgentMemoryEntry.Kind.FEEDBACK), 5)
                .stream().map(AgentMemoryEntry::getContent).toList();

        Map<String, Object> content;
        String source = "LLM";
        String notes = "";
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("job", job.getTitle() + " at " + job.getCompany());
            vars.put("required", analysis.getRequiredSkills());
            vars.put("responsibilities", analysis.getResponsibilities());
            vars.put("red_flags", analysis.getRedFlags());
            vars.put("posting", job.getDescription().substring(0, Math.min(4000, job.getDescription().length())));
            vars.put("facts", ctx.factsForPrompt(40));
            vars.put("feedback", weak.isEmpty() ? "(none yet)" : weak);
            LlmResult result = llm.generateStructured(user, "interview.plan", vars);
            content = result.asMap();
            Object[] checked = guardStories(ctx, job, content);
            content = castMap(checked[0]);
            notes = (String) checked[1];
        } catch (LlmException e) {
            content = fallbackPlan(job, analysis);
            source = "FALLBACK";
            notes = "The AI model was not available, so this is a generic question bank for the role. Try again later for a tailored plan.";
        }
        InterviewPrepPlan plan = new InterviewPrepPlan();
        plan.setUser(user);
        plan.setApplication(app);
        plan.setVersion(plans.findFirstByApplicationOrderByVersionDesc(app).map(p -> p.getVersion() + 1).orElse(1));
        plan.setSource(source);
        plan.setContent(content);
        plan.setNotes(notes);
        return plans.save(plan);
    }

    @Transactional(readOnly = true)
    public InterviewPrepPlan latest(User user, UUID applicationId) {
        return plans.findFirstByApplicationOrderByVersionDesc(applications.owned(user, applicationId)).orElse(null);
    }

    /** Removes STAR stories the verified facts do not support. Returns {content, notes}. */
    private Object[] guardStories(CandidateContext ctx, JobPosting job, Map<String, Object> content) {
        List<Map<String, Object>> stories = maps(content.get("star_stories"));
        List<Claim> claims = new ArrayList<>();
        for (int i = 0; i < stories.size(); i++) {
            Map<String, Object> s = stories.get(i);
            String text = String.join(" ", str(s.get("situation")), str(s.get("task")), str(s.get("action")), str(s.get("result")));
            List<String> ids = s.get("source_fact_ids") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
            claims.add(new Claim("star." + i, text, ids, ids.isEmpty() ? Claim.Kind.BULLET : Claim.Kind.PARAGRAPH));
        }
        VerificationReport report = new FabricationGuard(ctx, job).check(claims);
        List<Map<String, Object>> kept = new ArrayList<>();
        StringBuilder notes = new StringBuilder();
        for (int i = 0; i < stories.size(); i++) {
            if (report.badLocations().contains("star." + i))
                notes.append("Removed the story \"").append(str(stories.get(i).get("title"))).append("\" because it was not supported by your verified facts. Add more detail to your profile to get it back. ");
            else kept.add(stories.get(i));
        }
        Map<String, Object> out = new LinkedHashMap<>(content);
        out.put("star_stories", kept);
        return new Object[]{out, notes.toString().strip()};
    }

    static Map<String, Object> fallbackPlan(JobPosting job, JobAnalysis analysis) {
        List<Map<String, Object>> q = new ArrayList<>();
        for (String s : new String[]{"Tell me about yourself and why you applied to " + job.getCompany() + ".",
                "Describe a time you worked in a team and something went wrong. What did you do?",
                "Tell me about a project you are proud of and your part in it.",
                "Describe a time you had to learn something quickly."})
            q.add(question("behavioural", s, "A common opening or behavioural question for student roles."));
        analysis.getRequiredSkills().stream().limit(3).forEach(skill ->
                q.add(question("technical", "Walk me through how you have used " + skill + ". What would you do differently now?", "The posting lists " + skill + " as required.")));
        q.add(question("role", "Why " + job.getTitle() + "? What do you expect to do day to day?", "Shows you understand the role."));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("questions", q);
        out.put("star_stories", List.of());
        out.put("checklist", List.of("Re-read the posting and the company's website", "Prepare two stories from your verified experience", "Prepare questions to ask the interviewer",
                "Test your video or phone setup", "Plan to arrive or log in 10 minutes early"));
        return out;
    }

    private static Map<String, Object> question(String category, String text, String rationale) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("category", category);
        m.put("question", text);
        m.put("rationale", rationale);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object o) { return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
}
