package com.careerpilot.llm;

public record LlmResponse(String text, String model, int inputTokens, int outputTokens) {
    public int totalTokens() { return inputTokens + outputTokens; }
}
