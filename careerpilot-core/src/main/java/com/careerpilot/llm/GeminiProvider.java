package com.careerpilot.llm;

import com.careerpilot.common.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapter for the Google Gemini generateContent REST API. */
public class GeminiProvider implements LlmProvider {
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models";
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final HttpClient http;

    public GeminiProvider(String apiKey, String model, int timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank())
            throw new LlmProviderException("LLM_API_KEY is not set for the Gemini provider", false);
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? "gemini-2.5-flash" : model;
        this.timeoutSeconds = timeoutSeconds;
        this.http = HttpSupport.client(timeoutSeconds);
    }

    @Override public String name() { return "gemini"; }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("temperature", request.temperature);
        generation.put("maxOutputTokens", request.maxOutputTokens);
        if (request.jsonMode) generation.put("responseMimeType", "application/json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", request.system))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", request.prompt)))));
        body.put("generationConfig", generation);

        String raw = HttpSupport.postJson(http, timeoutSeconds, BASE + "/" + model + ":generateContent",
                Map.of("x-goog-api-key", apiKey), Json.write(body), "Gemini");
        JsonNode payload = Json.read(raw, JsonNode.class);
        JsonNode parts = payload.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray()) throw new LlmProviderException("Gemini returned no content: " + raw.substring(0, Math.min(200, raw.length())), true);
        StringBuilder text = new StringBuilder();
        parts.forEach(p -> text.append(p.path("text").asText("")));
        JsonNode usage = payload.path("usageMetadata");
        return new LlmResponse(text.toString(), model, usage.path("promptTokenCount").asInt(0), usage.path("candidatesTokenCount").asInt(0));
    }
}
