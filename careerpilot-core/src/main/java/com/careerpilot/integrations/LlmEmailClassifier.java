package com.careerpilot.integrations;

import com.careerpilot.llm.LlmClient;
import com.careerpilot.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Asks the model (prompt email.classify). Budget and provider errors propagate as LlmException. */
@Component
public class LlmEmailClassifier implements EmailClassificationStrategy {
    private final LlmClient llm;

    public LlmEmailClassifier(LlmClient llm) { this.llm = llm; }

    @Override
    public Classification classify(EmailMessage email, User user) {
        JsonNode data = llm.generateStructured(user != null ? user : email.getUser(), "email.classify",
                Map.of("sender", email.getSender(), "subject", email.getSubject(), "body", email.getExcerpt())).data();
        String type = data.get("event_type").asText();
        if (!EventTypes.ALL.contains(type)) type = EventTypes.NOT_RELEVANT;
        Map<String, Object> details = new LinkedHashMap<>();
        for (String key : new String[]{"company", "job_title", "scheduled_at", "format"})
            if (data.hasNonNull(key) && !data.get(key).asText().isBlank()) details.put(key, data.get(key).asText().strip());
        return new Classification(type, Math.round(data.get("confidence").asDouble() * 100) / 100.0, details, "llm");
    }
}
