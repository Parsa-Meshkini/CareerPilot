package com.careerpilot.analytics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The student's own outcome statistics. Computed deterministically from applications, status changes and match scores. */
public record OutcomeStats(int applied, int withOutcome, GroupStats overall, List<GroupStats> byScoreBand, List<GroupStats> bySource,
                           List<GroupStats> byRoleCategory, Double medianDaysToResponse) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applied", applied);
        m.put("with_outcome", withOutcome);
        m.put("overall", overall.toMap());
        m.put("by_score_band", byScoreBand.stream().map(GroupStats::toMap).toList());
        m.put("by_source", bySource.stream().map(GroupStats::toMap).toList());
        m.put("by_role_category", byRoleCategory.stream().map(GroupStats::toMap).toList());
        m.put("median_days_to_response", medianDaysToResponse);
        return m;
    }
}
