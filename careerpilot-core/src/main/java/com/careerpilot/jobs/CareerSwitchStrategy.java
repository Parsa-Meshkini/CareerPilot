package com.careerpilot.jobs;

import com.careerpilot.agent.CandidateContext;
import com.careerpilot.candidates.ProfileFact;
import com.careerpilot.llm.LlmBudgetExceededException;
import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.LlmException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Career switch: direct overlap under-rates switchers, so the LLM maps transferable skills from verified facts onto
 * missing requirements. Every mapping must cite real fact ids and earns partial credit.
 */
public class CareerSwitchStrategy extends MatchScoringStrategy {
    private final LlmClient llm;
    private final boolean useLlm;

    public CareerSwitchStrategy(LlmClient llm, boolean useLlm) {
        this.llm = llm;
        this.useLlm = useLlm;
    }

    @Override public String key() { return "career_switch"; }

    List<Map<String, Object>> mapTransferableSkills(CandidateContext ctx, List<String> missing) {
        if (missing.isEmpty() || !useLlm) return List.of();
        JsonNode data;
        try {
            data = llm.generateStructured(ctx.user, "match.transferable",
                    Map.of("missing_skills", missing, "facts", ctx.factsForPrompt(25))).data();
        } catch (LlmBudgetExceededException e) {
            throw e;
        } catch (LlmException e) {
            return List.of();
        }
        Map<String, ProfileFact> valid = ctx.factIndex();
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode m : data.path("mappings")) {
            String skill = Skills.canonical(m.path("required_skill").asText(""));
            List<String> ids = new ArrayList<>();
            m.path("fact_ids").forEach(i -> { if (valid.containsKey(i.asText())) ids.add(i.asText()); });
            if (missing.contains(skill) && !ids.isEmpty()) {  // only mappings grounded in the student's own facts count
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("skill", skill);
                entry.put("fact_ids", ids);
                String reason = m.path("reason").asText("");
                entry.put("reason", reason.length() > 200 ? reason.substring(0, 200) : reason);
                out.add(entry);
            }
        }
        return out;
    }

    @Override
    public MatchResult score(CandidateContext ctx, JobPosting job, AnalysisView analysis) {
        List<String> missing0 = skillCoverage(ctx, analysis, Map.of()).missing();
        List<Map<String, Object>> transferable = mapTransferableSkills(ctx, missing0);
        Map<String, Double> credit = new LinkedHashMap<>();
        transferable.forEach(t -> credit.put((String) t.get("skill"), 0.5));
        Coverage cov = skillCoverage(ctx, analysis, credit);
        String seniority = analysis.getSeniority() == null ? "" : analysis.getSeniority();
        double experience = List.of("junior", "intern", "").contains(seniority) ? 0.7 : 0.4;
        double pref = preferenceFit(ctx, job);
        double constraint = 1.0 - Math.min(1.0, 0.35 * analysis.getRedFlags().size());
        return new MatchResult(combine(ctx, factors(cov.value(), experience, pref, constraint)), key(),
                breakdown(cov.value(), experience, pref, constraint), cov.matched(), cov.missing(), transferable, new ArrayList<>());
    }
}
