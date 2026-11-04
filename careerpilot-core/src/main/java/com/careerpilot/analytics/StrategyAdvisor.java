package com.careerpilot.analytics;

import com.careerpilot.candidates.StrategyWeights;
import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.LlmException;
import com.careerpilot.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F19, hybrid part. Rules decide WHAT may change (guard rails: at least 5 applications per group, at most +/-0.10 per weight);
 * the LLM only words the narrative and explanations. A proposal without evidence or outside the bounds never reaches the student.
 */
@Component
public class StrategyAdvisor {
    static final int MIN_GROUP = 5;
    static final double STEP = 0.05, GAP = 0.20;

    /** A proposal before it is stored. */
    public record Proposal(String factor, double oldValue, double newValue, String rationale, Map<String, Object> evidence) {}

    public record Words(String narrative, Map<Integer, String> explanations) {}

    private final LlmClient llm;

    public StrategyAdvisor(LlmClient llm) { this.llm = llm; }

    public List<Proposal> proposeAdjustments(OutcomeStats stats, StrategyWeights weights) {
        List<Proposal> out = new ArrayList<>();
        GroupStats high = merge("score 60+", stats.byScoreBand().stream().filter(g -> g.label().equals("60-79") || g.label().equals("80-100")).toList());
        GroupStats low = merge("score below 60", stats.byScoreBand().stream().filter(g -> g.label().equals("0-39") || g.label().equals("40-59")).toList());
        if (high.applied() >= MIN_GROUP && low.applied() >= MIN_GROUP) {
            double diff = high.interviewRate() - low.interviewRate();
            if (diff >= GAP) add(out, weights, "skill", STEP, "Applications scored 60 or higher reached interviews far more often than lower ones, so skill fit predicts your results.", high, low);
            else if (diff <= -GAP) add(out, weights, "skill", -STEP, "Lower-scored applications did as well as higher-scored ones, so the skill weight may be over-trusted.", high, low);
        }
        for (GroupStats role : stats.byRoleCategory()) {
            if (role.applied() >= MIN_GROUP && stats.overall().applied() - role.applied() >= MIN_GROUP
                    && role.interviewRate() - stats.overall().interviewRate() >= GAP) {
                add(out, weights, "preference", STEP, "Your " + role.label() + " applications reach interviews much more often than average; weighting your stated preferences more would surface similar roles.",
                        role, stats.overall());
                break;
            }
        }
        return out;
    }

    private static void add(List<Proposal> out, StrategyWeights w, String factor, double delta, String rationale, GroupStats a, GroupStats b) {
        double old = w.get(factor), next = Math.round((old + delta) * 1000.0) / 1000.0;
        if (next < 0 || next > 1 || Math.abs(next - old) > StrategyWeights.MAX_STEP + 1e-9) return;  // outside the guard rails
        if (out.stream().anyMatch(p -> p.factor().equals(factor))) return;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("group", a.toMap());
        evidence.put("compared_with", b.toMap());
        out.add(new Proposal(factor, old, next, rationale, evidence));
    }

    private static GroupStats merge(String label, List<GroupStats> parts) {
        return new GroupStats(label, parts.stream().mapToInt(GroupStats::applied).sum(), parts.stream().mapToInt(GroupStats::responded).sum(),
                parts.stream().mapToInt(GroupStats::interviews).sum());
    }

    /** Narrative and per-proposal explanations from the model, or deterministic text when it is unavailable. */
    public Words describe(User user, OutcomeStats stats, List<Proposal> proposals) {
        Map<Integer, String> explanations = new LinkedHashMap<>();
        for (int i = 0; i < proposals.size(); i++) explanations.put(i, proposals.get(i).rationale());
        String fallback = "You have applied to " + stats.applied() + " postings and " + stats.withOutcome() + " have an outcome. Interview rate: "
                + Math.round(stats.overall().interviewRate() * 100) + "%. " + (proposals.isEmpty() && stats.withOutcome() >= 10 ? "Your data does not justify a change right now." : "");
        if (proposals.isEmpty() && stats.withOutcome() < 10) return new Words(fallback, explanations);
        try {
            List<Map<String, Object>> listed = new ArrayList<>();
            for (int i = 0; i < proposals.size(); i++)
                listed.add(Map.of("id", String.valueOf(i), "factor", proposals.get(i).factor(), "change", proposals.get(i).oldValue() + " to " + proposals.get(i).newValue(),
                        "evidence", proposals.get(i).evidence()));
            JsonNode data = llm.generateStructured(user, "analytics.narrative", Map.of("stats", stats.toMap(), "proposals", listed.isEmpty() ? List.of("(none)") : listed)).data();
            for (JsonNode e : data.path("explanations")) {
                try {
                    int id = Integer.parseInt(e.path("id").asText());
                    if (explanations.containsKey(id) && !e.path("text").asText("").isBlank()) explanations.put(id, e.path("text").asText());
                } catch (NumberFormatException ignored) {
                    // an explanation for an id we never proposed is dropped
                }
            }
            String narrative = data.path("narrative").asText("");
            return new Words(narrative.isBlank() ? fallback : narrative, explanations);
        } catch (LlmException e) {
            return new Words(fallback, explanations);
        }
    }
}
