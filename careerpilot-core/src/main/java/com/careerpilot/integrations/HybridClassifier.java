package com.careerpilot.integrations;

import com.careerpilot.llm.LlmException;
import com.careerpilot.users.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Rules first; the LLM is only called when the rules are below the threshold (Stage 1: 0.85). */
@Component
public class HybridClassifier implements EmailClassificationStrategy {
    private final RuleBasedClassifier rules;
    private final LlmEmailClassifier llm;
    private final double threshold;

    @Autowired
    public HybridClassifier(RuleBasedClassifier rules, LlmEmailClassifier llm) {
        this(rules, llm, 0.85);
    }

    public HybridClassifier(RuleBasedClassifier rules, LlmEmailClassifier llm, double threshold) {
        this.rules = rules;
        this.llm = llm;
        this.threshold = threshold;
    }

    @Override
    public Classification classify(EmailMessage email, User user) {
        Classification first = rules.classify(email, user);
        if (first.confidence() >= threshold) return first;
        Classification second;
        try {
            second = llm.classify(email, user);
        } catch (LlmException e) {
            return first;  // offline, over budget or provider down: the weaker rule result still goes to review
        }
        if (second.eventType().equals(first.eventType())) {
            Map<String, Object> merged = new LinkedHashMap<>(second.extractedData());
            merged.putAll(first.extractedData());
            return new Classification(first.eventType(), Math.max(first.confidence(), second.confidence()), merged, "rules+llm");
        }
        return second;
    }
}
