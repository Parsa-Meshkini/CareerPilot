package com.careerpilot.llm;

import com.careerpilot.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PromptRegistry {
    private final Map<String, PromptTemplate> cache = new ConcurrentHashMap<>();

    public PromptTemplate get(String key) {
        return cache.computeIfAbsent(key, this::load);
    }

    private PromptTemplate load(String key) {
        try (InputStream in = getClass().getResourceAsStream("/prompts/" + key + ".json")) {
            if (in == null) throw new IllegalArgumentException("no prompt template '" + key + "'");
            JsonNode node = Json.MAPPER.readTree(in);
            JsonNode schema = node.has("schema") && !node.get("schema").isNull() ? node.get("schema") : null;
            return new PromptTemplate(node.get("key").asText(), node.get("version").asInt(), node.get("system").asText(),
                    node.get("template").asText(), schema);
        } catch (IOException e) {
            throw new IllegalStateException("cannot load prompt " + key, e);
        }
    }
}
