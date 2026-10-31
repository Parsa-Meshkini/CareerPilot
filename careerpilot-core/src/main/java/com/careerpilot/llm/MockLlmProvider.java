package com.careerpilot.llm;

import com.careerpilot.common.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline provider for tests and CI (no network, no key). Responses come from scripts queued per prompt key.
 * With no script it builds the smallest object that satisfies the schema embedded in the prompt, so the rest of
 * the pipeline can still be exercised. It is not used for demos.
 */
public class MockLlmProvider implements LlmProvider {
    private static final Pattern SCHEMA = Pattern.compile("<schema>(.*?)</schema>", Pattern.DOTALL);
    private final Map<String, LinkedList<Object>> scripts = new HashMap<>();
    public final List<LlmRequest> calls = new ArrayList<>();

    @Override public String name() { return "mock"; }

    /** Queue responses for a prompt key: Map/List become JSON, String is returned as is, RuntimeException is thrown. */
    public MockLlmProvider script(String promptKey, Object... responses) {
        LinkedList<Object> queue = scripts.computeIfAbsent(promptKey, k -> new LinkedList<>());
        for (Object r : responses) queue.add(r);
        return this;
    }

    /** Replaces anything already queued for the prompt key. */
    public MockLlmProvider replace(String promptKey, Object... responses) {
        scripts.remove(promptKey);
        return script(promptKey, responses);
    }

    @Override
    public synchronized LlmResponse complete(LlmRequest request) {
        calls.add(request);
        LinkedList<Object> queue = scripts.get(request.promptKey);
        String text;
        if (queue != null && !queue.isEmpty()) {
            Object item = queue.size() > 1 ? queue.removeFirst() : queue.getFirst();
            if (item instanceof RuntimeException e) throw e;
            text = item instanceof String s ? s : Json.write(item);
        } else {
            text = request.jsonMode ? Json.write(minimal(schemaIn(request.prompt))) : "OK";
        }
        return new LlmResponse(text, "mock-1", request.prompt.length() / 4, text.length() / 4);
    }

    private static JsonNode schemaIn(String prompt) {
        Matcher m = SCHEMA.matcher(prompt);
        if (!m.find()) return Json.MAPPER.createObjectNode().put("type", "object");
        try {
            return Json.MAPPER.readTree(m.group(1));
        } catch (Exception e) {
            return Json.MAPPER.createObjectNode().put("type", "object");
        }
    }

    private static Object minimal(JsonNode schema) {
        if (schema.has("enum")) return Json.MAPPER.convertValue(schema.get("enum").get(0), Object.class);
        String type = schema.path("type").asText("");
        switch (type) {
            case "object": {
                Map<String, Object> out = new LinkedHashMap<>();
                JsonNode props = schema.path("properties");
                for (JsonNode req : schema.path("required")) {
                    if (props.has(req.asText())) out.put(req.asText(), minimal(props.get(req.asText())));
                }
                return out;
            }
            case "array": return new ArrayList<>();
            case "integer": case "number": return schema.path("minimum").asInt(0);
            case "boolean": return false;
            default: return "";
        }
    }
}
