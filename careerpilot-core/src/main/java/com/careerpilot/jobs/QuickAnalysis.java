package com.careerpilot.jobs;

import java.util.List;

/** Analysis-shaped view built from keywords when no JobAnalysis exists yet (quick score, no LLM tokens). */
public record QuickAnalysis(List<String> requiredSkills, String seniority, Integer yearsRequired) implements AnalysisView {
    @Override public List<String> getRequiredSkills() { return requiredSkills; }
    @Override public List<String> getPreferredSkills() { return List.of(); }
    @Override public String getSeniority() { return seniority; }
    @Override public Integer getYearsRequired() { return yearsRequired; }
    @Override public List<String> getRedFlags() { return List.of(); }
}
