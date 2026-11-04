package com.careerpilot.llm;

import com.careerpilot.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The request body must suit the vendor: current OpenAI models want max_completion_tokens, other endpoints max_tokens. */
class OpenAiCompatibleProviderTest {
    HttpServer server;
    final AtomicReference<JsonNode> seen = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            seen.set(Json.read(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class));
            byte[] ok = "{\"model\":\"m\",\"choices\":[{\"message\":{\"content\":\"{}\"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    LlmRequest request() { return new LlmRequest("sys", "hi", true, 0.2, 321, "system.ping"); }

    @Test
    void genericEndpointKeepsMaxTokensAndTemperature() {
        var p = new OpenAiCompatibleProvider("k", "m", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 5);
        LlmResponse r = p.complete(request());
        assertEquals(321, seen.get().path("max_tokens").asInt());
        assertTrue(seen.get().has("temperature"));
        assertFalse(seen.get().has("max_completion_tokens"));
        assertEquals(3, r.inputTokens());
    }

    @Test
    void officialOpenAiUsesMaxCompletionTokensAndNoTemperature() {
        var body = new OpenAiCompatibleProvider("k", "m", "https://api.openai.com/v1", 5).body(request());
        assertEquals(321, body.get("max_completion_tokens"));
        assertFalse(body.containsKey("max_tokens"));
        assertFalse(body.containsKey("temperature"));
        var other = new OpenAiCompatibleProvider("k", "m", "https://api.groq.com/openai/v1", 5).body(request());
        assertEquals(321, other.get("max_tokens"));
    }
}
