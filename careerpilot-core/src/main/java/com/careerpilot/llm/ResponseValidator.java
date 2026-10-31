package com.careerpilot.llm;

import com.careerpilot.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/** Parses model output as JSON and checks it against the prompt's schema. */
@Component
public class ResponseValidator {
    private static final Pattern FENCE = Pattern.compile("^```(?:json)?\\s*|\\s*```$", Pattern.CASE_INSENSITIVE);
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    public JsonNode parse(String text) {
        String cleaned = FENCE.matcher(text.trim()).replaceAll("");
        try {
            return Json.MAPPER.readTree(cleaned);
        } catch (Exception first) {
            // models sometimes wrap JSON in prose; take the outermost object
            int start = cleaned.indexOf('{'), end = cleaned.lastIndexOf('}');
            if (start != -1 && end > start) {
                try {
                    return Json.MAPPER.readTree(cleaned.substring(start, end + 1));
                } catch (Exception ignored) {
                    // fall through
                }
            }
            throw new LlmValidationException("model output is not valid JSON");
        }
    }

    public JsonNode validate(String text, JsonNode schema) {
        JsonNode data = parse(text);
        if (schema != null) {
            JsonSchema compiled = factory.getSchema(schema);
            Set<ValidationMessage> errors = compiled.validate(data);
            if (!errors.isEmpty()) {
                ValidationMessage first = errors.iterator().next();
                throw new LlmValidationException("schema violation at " + first.getInstanceLocation() + ": " + first.getMessage());
            }
        }
        return data;
    }
}
