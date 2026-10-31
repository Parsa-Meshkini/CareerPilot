package com.careerpilot.llm;

import com.careerpilot.common.AppProperties;
import org.springframework.stereotype.Component;

/** Simple factory: picks the provider adapter from configuration (not GoF Factory Method). */
@Component
public class LlmProviderFactory {
    private final AppProperties.Llm config;

    public LlmProviderFactory(AppProperties props) { this.config = props.getLlm(); }

    public LlmProvider create() {
        String provider = config.getProvider() == null || config.getProvider().isBlank() ? "gemini" : config.getProvider().toLowerCase();
        return switch (provider) {
            case "gemini" -> new GeminiProvider(config.getApiKey(), config.getModel(), config.getTimeoutSeconds());
            case "openai", "openai_compatible", "groq", "openrouter" ->
                    new OpenAiCompatibleProvider(config.getApiKey(), config.getModel(), config.getBaseUrl(), config.getTimeoutSeconds());
            case "mock" -> new MockLlmProvider();
            default -> throw new LlmProviderException("unknown LLM_PROVIDER '" + provider + "'", false);
        };
    }
}
