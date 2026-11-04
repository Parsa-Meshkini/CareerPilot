package com.careerpilot.analytics;

import java.util.LinkedHashMap;
import java.util.Map;

/** Counts for one group of applications (a score band, a source, a role category). */
public record GroupStats(String label, int applied, int responded, int interviews) {
    public double responseRate() { return applied == 0 ? 0 : Math.round(1000.0 * responded / applied) / 1000.0; }

    public double interviewRate() { return applied == 0 ? 0 : Math.round(1000.0 * interviews / applied) / 1000.0; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("group", label);
        m.put("n", applied);
        m.put("responded", responded);
        m.put("interviews", interviews);
        m.put("response_rate", responseRate());
        m.put("interview_rate", interviewRate());
        return m;
    }
}
