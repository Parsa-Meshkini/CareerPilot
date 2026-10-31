package com.careerpilot.documents;

import java.util.LinkedHashMap;
import java.util.Map;

public record Violation(String location, String text, String rule, String detail) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("location", location);
        m.put("text", text);
        m.put("rule", rule);
        m.put("detail", detail);
        return m;
    }
}
