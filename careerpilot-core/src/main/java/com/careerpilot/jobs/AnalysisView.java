package com.careerpilot.jobs;

import java.util.List;

/** What a scoring strategy needs from a job analysis. A stored JobAnalysis or a keyword-based {@link QuickAnalysis}. */
public interface AnalysisView {
    List<String> getRequiredSkills();
    List<String> getPreferredSkills();
    String getSeniority();
    Integer getYearsRequired();
    List<String> getRedFlags();
}
