package com.careerpilot.integrations;

import com.careerpilot.users.User;
import org.springframework.stereotype.Component;

/** The Strategy pattern's context. The classification strategy can be swapped at runtime with {@link #setStrategy}. */
@Component
public class EmailEventDetector {
    private volatile EmailClassificationStrategy strategy;

    public EmailEventDetector(HybridClassifier hybrid) { this.strategy = hybrid; }

    public void setStrategy(EmailClassificationStrategy strategy) { this.strategy = strategy; }

    public EmailClassificationStrategy strategy() { return strategy; }

    /** Returns an unsaved event, or null when the email is not about an application. */
    public DetectedEmailEvent detect(EmailMessage email, User user) {
        Classification result = strategy.classify(email, user);
        if (result.eventType().equals(EventTypes.NOT_RELEVANT)) return null;
        DetectedEmailEvent event = new DetectedEmailEvent();
        event.setUser(email.getUser());
        event.setEmail(email);
        event.setEventType(result.eventType());
        event.setConfidence(result.confidence());
        event.setExtractedData(new java.util.LinkedHashMap<>(result.extractedData()));
        event.setClassifier(result.source());
        return event;
    }
}
