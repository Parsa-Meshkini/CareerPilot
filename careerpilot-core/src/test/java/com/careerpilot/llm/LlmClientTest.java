package com.careerpilot.llm;

import com.careerpilot.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmClientTest extends IntegrationTest {
    @Autowired UsageTracker tracker;

    @Test
    void structuredCallValidatesAgainstSchemaAndRecordsUsage() {
        mock.script("system.ping", Map.of("ok", true, "echo", "hello"));
        LlmResult result = llm.generateStructured(user, "system.ping", Map.of("word", "hello"));
        assertTrue(result.data().get("ok").asBoolean());
        assertEquals(1, result.attempts());
        assertTrue(tracker.usedToday(user) > 0);
    }

    @Test
    void invalidJsonIsRetriedWithTheErrorAppended() {
        mock.script("system.ping", "not json at all", Map.of("ok", true, "echo", "x"));
        LlmResult result = llm.generateStructured(user, "system.ping", Map.of("word", "x"));
        assertEquals(2, result.attempts());
        assertTrue(mock.calls.get(1).prompt.contains("previous answer was rejected"));
    }

    @Test
    void schemaViolationExhaustsRetriesAndThrows() {
        mock.script("system.ping", Map.of("ok", "yes"));
        assertThrows(LlmValidationException.class, () -> llm.generateStructured(user, "system.ping", Map.of("word", "x")));
        assertEquals(3, mock.calls.size());
    }

    @Test
    void retryableProviderErrorsAreRetriedAndNonRetryableAreNot() {
        mock.script("system.ping", new LlmProviderException("HTTP 503", true), Map.of("ok", true, "echo", "x"));
        assertEquals(2, llm.generateStructured(user, "system.ping", Map.of("word", "x")).attempts());

        mock = new MockLlmProvider();
        llm.useProvider(mock, millis -> { });
        mock.script("system.ping", new LlmProviderException("HTTP 401", false));
        assertThrows(LlmProviderException.class, () -> llm.generateStructured(user, "system.ping", Map.of("word", "x")));
        assertEquals(1, mock.calls.size());
    }

    @Test
    void runBudgetStopsOverspendingAgents() {
        TokenBudget exhausted = new TokenBudget() {
            public boolean withinBudget(int estimate) { return false; }
            public void addTokens(int tokens) { }
        };
        assertThrows(LlmBudgetExceededException.class,
                () -> llm.generateStructured(user, "system.ping", Map.of("word", "x"), exhausted));
    }

    @Test
    void codeFencedJsonIsAccepted() {
        mock.script("system.ping", "```json\n{\"ok\": true, \"echo\": \"x\"}\n```");
        assertTrue(llm.generateStructured(user, "system.ping", Map.of("word", "x")).data().get("ok").asBoolean());
    }

    @Test
    void vendorErrorBodiesBecomeOneReadableLine() {
        String body = "{\n  \"error\": {\n    \"code\": 429,\n    \"message\": \"You exceeded your current quota.\\nPlease check your plan.\",\n    \"status\": \"RESOURCE_EXHAUSTED\"\n  }\n}";
        assertEquals("You exceeded your current quota. Please check your plan.", HttpSupport.errorText(body));
        assertEquals("plain text error", HttpSupport.errorText("plain\n   text   error"));
    }
}
