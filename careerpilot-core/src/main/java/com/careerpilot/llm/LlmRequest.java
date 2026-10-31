package com.careerpilot.llm;

/** Provider-neutral request. Mutable only in prompt, so a retry can append the validation error. */
public class LlmRequest {
    public final String system;
    public String prompt;
    public final boolean jsonMode;
    public final double temperature;
    public final int maxOutputTokens;
    public final String promptKey;

    public LlmRequest(String system, String prompt, boolean jsonMode, double temperature, int maxOutputTokens, String promptKey) {
        this.system = system;
        this.prompt = prompt;
        this.jsonMode = jsonMode;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.promptKey = promptKey;
    }
}
