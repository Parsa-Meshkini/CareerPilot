package com.careerpilot.integrations;

import java.util.LinkedHashMap;
import java.util.Map;

/** The result of classifying one email. {@code source} says which strategy decided: rules, llm or rules+llm. */
public record Classification(String eventType, double confidence, Map<String, Object> extractedData, String source) {
    public Classification(String eventType, double confidence, String source) {
        this(eventType, confidence, new LinkedHashMap<>(), source);
    }
}
