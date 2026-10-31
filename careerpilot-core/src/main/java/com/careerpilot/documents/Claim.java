package com.careerpilot.documents;

import java.util.List;

/** One piece of generated text and the fact ids it claims to come from. */
public record Claim(String location, String text, List<String> sourceFactIds, Kind kind) {
    public enum Kind { BULLET, SKILL, PARAGRAPH }

    public Claim(String location, String text, List<String> sourceFactIds) {
        this(location, text, sourceFactIds, Kind.BULLET);
    }
}
