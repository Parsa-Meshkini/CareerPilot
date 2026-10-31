package com.careerpilot.jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record MatchResult(int score, String strategyKey, Map<String, Object> breakdown, List<String> matchedSkills,
                          List<String> missingSkills, List<Map<String, Object>> transferable, List<String> constraintNotes) {
    public MatchResult(int score, String strategyKey, Map<String, Object> breakdown, List<String> matched, List<String> missing,
                       List<String> notes) {
        this(score, strategyKey, breakdown, matched, missing, new ArrayList<>(), notes);
    }
}
