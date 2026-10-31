package com.careerpilot.integrations;

import com.careerpilot.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A scripted inbox for demos and tests. Reads the bundled JSON list of {from, subject, date, body}. */
@Component
public class MockEmailAdapter implements EmailProviderAdapter {
    private List<JsonNode> messages;

    @Override public String providerName() { return "MOCK"; }

    /** Replaces the bundled inbox (tests). */
    public void useMessages(List<JsonNode> messages) { this.messages = messages; }

    private List<JsonNode> items() {
        if (messages != null) return messages;
        try (InputStream in = getClass().getResourceAsStream("/email/sample_inbox.json")) {
            List<JsonNode> out = new ArrayList<>();
            Json.MAPPER.readTree(in).forEach(out::add);
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the sample inbox", e);
        }
    }

    @Override
    public List<RawEmail> fetchNewMessages(EmailAccount account, Instant since) {
        List<RawEmail> out = new ArrayList<>();
        List<JsonNode> items = items();
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            String id = item.has("id") ? item.get("id").asText() : "mock-" + i;
            String to = account.getAddress().isEmpty() ? "student@example.com" : account.getAddress();
            String raw = String.join("\r\n",
                    "From: " + item.get("from").asText(), "To: " + to, "Subject: " + item.get("subject").asText(),
                    "Date: " + item.path("date").asText("Mon, 02 Nov 2026 09:00:00 -0500"), "Message-ID: <" + id + ">",
                    "Content-Type: text/plain; charset=utf-8", "", item.get("body").asText());
            out.add(MimeSupport.rawFromBytes(raw.getBytes(StandardCharsets.UTF_8), id));
        }
        return out;
    }
}
