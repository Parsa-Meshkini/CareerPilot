package com.careerpilot.documents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class VerificationReport {
    public int checked;
    public final List<Violation> violations = new ArrayList<>();

    public boolean passed() { return violations.isEmpty(); }

    public Set<String> badLocations() { return violations.stream().map(Violation::location).collect(Collectors.toSet()); }

    public String feedback() {
        return violations.stream().map(v -> "- [" + v.location() + "] " + v.rule() + ": " + v.detail()).collect(Collectors.joining("\n"));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checked", checked);
        m.put("passed", passed());
        m.put("violations", violations.stream().map(Violation::toMap).toList());
        return m;
    }
}
