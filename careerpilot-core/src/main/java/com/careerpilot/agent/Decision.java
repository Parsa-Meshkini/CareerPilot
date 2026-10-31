package com.careerpilot.agent;

public record Decision(String action, String rationale, String source) {
    public Decision(String action, String rationale) { this(action, rationale, "rules"); }
}
