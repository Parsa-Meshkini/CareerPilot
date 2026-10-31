package com.careerpilot.llm;

import com.careerpilot.common.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** What {@link LlmClient} hands back: validated data plus provenance. */
public record LlmResult(JsonNode data, String text, String model, String promptKey, int promptVersion, int tokens, int attempts) {

    public Map<String, Object> asMap() {
        return Json.MAPPER.convertValue(data, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    public List<Object> asList() {
        return Json.MAPPER.convertValue(data, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }
}
