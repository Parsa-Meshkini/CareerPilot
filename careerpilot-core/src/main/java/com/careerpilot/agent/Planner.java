package com.careerpilot.agent;

import com.careerpilot.applications.ApplicationRepository;
import com.careerpilot.jobs.JobPostingRepository;
import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.LlmException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.careerpilot.common.Json;
import com.careerpilot.users.User;

/** Turns a goal into a validated list of tool calls: an LLM plan, or the playbook when the LLM cannot produce a valid one. */
@Component
@Transactional
public class Planner {
    public static final int MAX_STEPS = 8;
    static final int MAX_ATTEMPTS = 2;

    public static class PlanInvalidException extends RuntimeException {
        public PlanInvalidException(String message) { super(message); }
    }

    public record Plan(List<Map<String, Object>> steps, String source) {}
    public record Replan(List<Map<String, Object>> steps, String rationale) {}

    private final ToolManager tools;
    private final PlaybookLibrary playbooks;
    private final LlmClient llm;
    private final JobPostingRepository jobs;
    private final ApplicationRepository applications;

    public Planner(ToolManager tools, PlaybookLibrary playbooks, LlmClient llm, JobPostingRepository jobs, ApplicationRepository applications) {
        this.tools = tools;
        this.playbooks = playbooks;
        this.llm = llm;
        this.jobs = jobs;
        this.applications = applications;
    }

    public Plan createPlan(AgentRun run, String contextSummary, List<String> memories) {
        if (run.getGoalType().equals("TRIAGE_BATCH"))
            return new Plan(playbooks.getPlaybook(run), "PLAYBOOK");  // a fixed algorithm; budget decisions are rule-based
        String feedback = "";
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("goal", run.getGoalText());
                vars.put("goal_type", run.getGoalType());
                vars.put("context", contextSummary);
                vars.put("memories", memories.isEmpty() ? List.of("(none)") : memories);
                vars.put("tools", tools.listTools());
                vars.put("focus", run.getContext().get("focus_job_id") == null ? "none" : run.getContext().get("focus_job_id"));
                vars.put("max_steps", MAX_STEPS);
                vars.put("feedback", feedback);
                JsonNode data = llm.generateStructured(run.getUser(), "agent.plan", vars, run).data();
                List<Map<String, Object>> steps = validatePlan(Json.convert(data.path("steps"), List.class), run, false);
                run.getContext().put("plan_notes", data.path("notes").asText(""));
                return new Plan(steps, "LLM");
            } catch (PlanInvalidException e) {
                feedback = "Your previous plan was invalid: " + e.getMessage() + ". Fix it.";
            } catch (LlmException e) {
                break;
            }
        }
        return new Plan(playbooks.getPlaybook(run), "PLAYBOOK");
    }

    /** A replacement for the remaining steps, or null when no valid replan is possible. */
    public Replan replan(AgentRun run, Map<String, Object> observation, List<Map<String, Object>> completed) {
        if (run.getGoalType().equals("TRIAGE_BATCH")) {
            // nothing deserves deep analysis: rank on quick scores instead (no LLM needed)
            return new Replan(List.of(PlaybookLibrary.step("rank_jobs", "rank on quick scores only", "job_ids", AgentCommand.BATCH_JOBS)),
                    "No full-detail posting qualified for deep analysis; ranking on quick scores. Open the top postings and import them again for full detail.");
        }
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("goal", run.getGoalText());
            vars.put("completed", completed);
            vars.put("observation", observation);
            vars.put("remaining", new ArrayList<>(run.getPlan().subList(Math.min(run.getCurrentStep() + 1, run.getPlan().size()), run.getPlan().size())));
            vars.put("tools", tools.listTools());
            vars.put("budget_left", run.getTokenBudget() - run.getTokensUsed());
            vars.put("max_steps", MAX_STEPS - run.getStepCount());
            JsonNode data = llm.generateStructured(run.getUser(), "agent.replan", vars, run).data();
            return new Replan(validatePlan(Json.convert(data.path("steps"), List.class), run, true), data.path("rationale").asText(""));
        } catch (PlanInvalidException | LlmException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> validatePlan(List<?> steps, AgentRun run, boolean allowEmpty) {
        if (steps == null || (steps.isEmpty() && !allowEmpty)) throw new PlanInvalidException("the plan has no steps");
        if (steps.size() > MAX_STEPS) throw new PlanInvalidException("the plan has more than " + MAX_STEPS + " steps");
        List<Map<String, Object>> clean = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> s = (Map<String, Object>) steps.get(i);
            String tool = String.valueOf(s.get("tool"));
            Map<String, Object> args = s.get("args") instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
            if (!tools.catalog().containsKey(tool)) throw new PlanInvalidException("step " + (i + 1) + " uses unknown tool '" + tool + "'");
            String error = tools.schemaError(tool, args);
            if (error != null) throw new PlanInvalidException("step " + (i + 1) + " (" + tool + ") has bad arguments: " + error);
            for (String key : List.of("job_id", "application_id")) {
                Object value = args.get(key);
                if (value != null && !Set.of(AgentCommand.FOCUS_JOB, AgentCommand.FOCUS_APP).contains(value.toString()) && !owned(run.getUser(), key, value.toString()))
                    throw new PlanInvalidException("step " + (i + 1) + " references a " + key + " the student does not own");
            }
            if (args.get("job_ids") instanceof List<?> ids) {
                for (Object id : ids)
                    if (!AgentCommand.BATCH_JOBS.equals(id) && !owned(run.getUser(), "job_id", String.valueOf(id)))
                        throw new PlanInvalidException("step " + (i + 1) + " references a job the student does not own");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tool", tool);
            out.put("args", args);
            String intent = String.valueOf(s.getOrDefault("intent", ""));
            out.put("intent", intent.length() > 200 ? intent.substring(0, 200) : intent);
            clean.add(out);
        }
        return clean;
    }

    private boolean owned(User user, String key, String value) {
        if (!Pattern.matches("[0-9a-fA-F-]{32,36}", value)) return false;
        UUID id = UUID.fromString(value);
        return key.equals("job_id") ? jobs.findByIdAndUser(id, user).isPresent() : applications.findByIdAndUser(id, user).isPresent();
    }
}
