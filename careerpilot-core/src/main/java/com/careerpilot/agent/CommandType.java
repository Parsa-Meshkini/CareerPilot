package com.careerpilot.agent;

import com.careerpilot.common.Json;
import com.careerpilot.users.User;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Static description of a tool plus the factory that builds a command for one call. Shown to the planner as the catalog. */
public record CommandType(String name, String description, JsonNode argsSchema, boolean sideEffecting, boolean reversible,
                          int estimatedTokens, Factory factory) {
    @FunctionalInterface
    public interface Factory {
        AgentCommand create(AgentServices services, User user, Map<String, Object> args, AgentRun run);
    }

    public static CommandType of(String name, String description, String schemaJson, boolean sideEffecting, boolean reversible,
                                 int estimatedTokens, Factory factory) {
        return new CommandType(name, description, Json.read(schemaJson, JsonNode.class), sideEffecting, reversible, estimatedTokens, factory);
    }

    public Map<String, Object> spec() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("args_schema", argsSchema);
        m.put("side_effecting", sideEffecting);
        m.put("reversible", reversible);
        return m;
    }
}
