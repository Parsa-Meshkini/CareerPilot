package com.careerpilot.llm;

import com.careerpilot.common.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A versioned prompt loaded from resources/prompts. $name placeholders keep JSON braces free of escaping. */
public record PromptTemplate(String key, int version, String system, String template, JsonNode schema) {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$(\\$|([A-Za-z_][A-Za-z0-9_]*)|\\{([A-Za-z_][A-Za-z0-9_]*)\\})");

    public String render(Map<String, ?> variables) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement;
            if ("$".equals(m.group(1))) {
                replacement = "$";
            } else {
                String name = m.group(2) != null ? m.group(2) : m.group(3);
                if (!variables.containsKey(name)) throw new IllegalArgumentException("prompt " + key + " needs variable '" + name + "'");
                replacement = text(variables.get(name));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        if (schema != null) {
            out.append("\n\nRespond with a single JSON object only, matching this JSON schema:\n<schema>")
                    .append(Json.write(schema)).append("</schema>");
        }
        return out.toString();
    }

    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof Map || value instanceof Iterable || value instanceof JsonNode) return Json.pretty(value);
        return value.toString();
    }
}
