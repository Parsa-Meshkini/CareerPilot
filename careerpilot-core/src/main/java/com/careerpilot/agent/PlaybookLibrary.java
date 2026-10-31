package com.careerpilot.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hand-written plans per goal type, so the agent always has a correct plan when the LLM cannot supply one. */
@Component
public class PlaybookLibrary {
    static Map<String, Object> step(String tool, String intent, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) args.put((String) kv[i], kv[i + 1]);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("tool", tool);
        s.put("args", args);
        s.put("intent", intent);
        return s;
    }

    public List<Map<String, Object>> getPlaybook(AgentRun run) {
        boolean hasFocus = run.getContext().get("focus_job_id") != null;
        List<Map<String, Object>> find = hasFocus ? List.of() : List.of(step("search_jobs", "find the posting named in the goal", "query", run.getGoalText()));
        List<Map<String, Object>> plan = new ArrayList<>();
        switch (run.getGoalType()) {
            case "PREPARE_APPLICATION" -> {
                plan.addAll(find);
                plan.add(step("retrieve_context", "load verified profile and preferences"));
                plan.add(step("analyze_job", "understand what the posting asks for", "job_id", AgentCommand.FOCUS_JOB));
                plan.add(step("match_job", "check fit and skill gaps", "job_id", AgentCommand.FOCUS_JOB, "deep", true));
                plan.add(step("build_package", "generate tailored documents and the ZIP", "job_id", AgentCommand.FOCUS_JOB));
            }
            case "TRIAGE_BATCH" -> plan.addAll(triagePlaybook());
            default -> {
                plan.add(step("retrieve_context", "load verified profile and preferences", "topic", run.getGoalText()));
                plan.addAll(find);
            }
        }
        return plan;
    }

    public List<Map<String, Object>> triagePlaybook() {
        return List.of(
                step("quick_score_jobs", "score every imported posting cheaply", "job_ids", AgentCommand.BATCH_JOBS),
                step("select_for_deep_analysis", "decide where the LLM budget is worth spending", "job_ids", AgentCommand.BATCH_JOBS),
                step("deep_match_jobs", "analyze and explain the most promising postings", "job_ids", AgentCommand.DEEP_SELECTION),
                step("rank_jobs", "rank the batch and mark the shortlist", "job_ids", AgentCommand.BATCH_JOBS));
    }
}
