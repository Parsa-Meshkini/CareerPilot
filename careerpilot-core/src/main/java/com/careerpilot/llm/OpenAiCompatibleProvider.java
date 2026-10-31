package com.careerpilot.llm;

import com.careerpilot.common.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapter for any OpenAI-style /chat/completions endpoint (OpenAI, Groq, OpenRouter). */
public class OpenAiCompatibleProvider implements LlmProvider {
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final int timeoutSeconds;
    private final HttpClient http;

    public OpenAiCompatibleProvider(String apiKey, String model, String baseUrl, int timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank())
            throw new LlmProviderException("LLM_API_KEY is not set for the OpenAI-compatible provider", false);
        if (model == null || model.isBlank())
            throw new LlmProviderException("LLM_MODEL must be set for the OpenAI-compatible provider", false);
        this.apiKey = apiKey;
        this.model = model;
        String url = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl;
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.timeoutSeconds = timeoutSeconds;
        this.http = HttpSupport.client(timeoutSeconds);
    }

    @Override public String name() { return "openai_compatible"; }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", request.temperature);
        body.put("max_tokens", request.maxOutputTokens);
        body.put("messages", List.of(Map.of("role", "system", "content", request.system),
                Map.of("role", "user", "content", request.prompt)));
        if (request.jsonMode) body.put("response_format", Map.of("type", "json_object"));

        String raw = HttpSupport.postJson(http, timeoutSeconds, baseUrl + "/chat/completions",
                Map.of("Authorization", "Bearer " + apiKey), Json.write(body), "LLM endpoint");
        JsonNode payload = Json.read(raw, JsonNode.class);
        JsonNode content = payload.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode()) throw new LlmProviderException("LLM endpoint returned no content: " + raw.substring(0, Math.min(200, raw.length())), true);
        JsonNode usage = payload.path("usage");
        return new LlmResponse(content.asText(""), payload.path("model").asText(model),
                usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
    }
}
