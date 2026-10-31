package com.careerpilot.agent;

import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.LlmException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Decides what to do after each observation. Rules first; the LLM reflects only when the rules cannot decide. */
@Component
public class Reasoner {
    public static final String CONTINUE = "CONTINUE", RETRY = "RETRY", REPLAN = "REPLAN", ASK_USER = "ASK_USER", ABORT = "ABORT", FINISH = "FINISH";
    public static final int MAX_REPLANS = 2;

    private final LlmClient llm;

    public Reasoner(LlmClient llm) { this.llm = llm; }

    public Decision evaluate(AgentRun run, int stepIndex, Map<String, Object> step, Observation obs) {
        boolean last = stepIndex >= run.getPlan().size() - 1;
        if (!obs.ok && obs.error.toLowerCase().contains("budget"))
            return new Decision(ABORT, obs.error + "; stopping with a partial result");
        if (!obs.ok) {
            int attempts = step.get("attempts") instanceof Number n ? n.intValue() : 1;
            if (attempts < 2 && !obs.error.contains("not found")) return new Decision(RETRY, "tool error, retrying once: " + obs.error);
            if (run.getReplanCount() < MAX_REPLANS) return new Decision(REPLAN, "step failed (" + obs.error + "); replanning the remaining steps");
            return new Decision(ABORT, "step failed after retries and replans: " + obs.error);
        }
        if (!run.withinBudget()) return new Decision(ABORT, "token budget for this run is used up; stopping with a partial result");
        if (!obs.candidates.isEmpty())
            return new Decision(ASK_USER, obs.candidates.size() + " objects fit equally well; asking which one you mean");
        String tool = String.valueOf(step.get("tool"));
        if (tool.equals("search_jobs") && (!(obs.data.get("jobs") instanceof java.util.List<?> l) || l.isEmpty())) {
            if (run.getReplanCount() < MAX_REPLANS) return new Decision(REPLAN, "no matching posting; replanning (the posting may need to be imported first)");
            return new Decision(FINISH, "no matching posting; import it first, then run the goal again");
        }
        if (tool.equals("select_for_deep_analysis") && (!(obs.data.get("selected") instanceof java.util.List<?> l) || l.isEmpty())
                && run.getReplanCount() < MAX_REPLANS)
            return new Decision(REPLAN, "no posting qualifies for deep analysis; replanning to rank on quick scores");
        if (obs.needsJudgement) return reflect(run, step, obs, last);
        return new Decision(last ? FINISH : CONTINUE, last ? "plan complete" : "step succeeded");
    }

    @SuppressWarnings("unchecked")
    Decision reflect(AgentRun run, Map<String, Object> step, Observation obs, boolean last) {
        JsonNode data;
        try {
            java.util.List<Object> remaining = new java.util.ArrayList<>();
            for (int i = run.getCurrentStep() + 1; i < run.getPlan().size(); i++) remaining.add(run.getPlan().get(i).get("tool"));
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("goal", run.getGoalText());
            vars.put("intent", step.getOrDefault("intent", ""));
            vars.put("observation", obs.toMap());
            vars.put("remaining", remaining);
            data = llm.generateStructured(run.getUser(), "agent.reflect", vars, run).data();
        } catch (LlmException e) {
            return new Decision(ASK_USER, "could not judge the result automatically; asking you", "rules");
        }
        String action = data.path("action").asText(ASK_USER);
        if (!java.util.List.of(CONTINUE, ASK_USER, FINISH).contains(action)) action = ASK_USER;
        if (action.equals(CONTINUE) && last) action = FINISH;
        String rationale = data.path("rationale").asText("");
        return new Decision(action, rationale.length() > 300 ? rationale.substring(0, 300) : rationale, "llm");
    }
}
