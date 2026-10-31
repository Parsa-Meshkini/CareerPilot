package com.careerpilot.llm;

import com.careerpilot.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The single entry point to the LLM subsystem (Facade pattern).
 * Every caller (resume parser, job analysis, matching, planner, reasoner, generators, classifiers) uses only
 * {@link #generateStructured} and {@link #generateText}. Behind them: prompt lookup and rendering, budget check,
 * the provider call through the {@link LlmProvider} adapter, JSON-schema validation with retries, and usage accounting.
 */
@Service
public class LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    static final int MAX_RETRIES = 2;
    static final long[] BACKOFF_MILLIS = {1500, 4000};

    /** Pause between retries of a rate-limited provider; replaced in tests. */
    public interface Sleeper { void sleep(long millis); }

    private final LlmProviderFactory factory;
    private final PromptRegistry registry;
    private final UsageTracker tracker;
    private final ResponseValidator validator;
    private volatile LlmProvider provider;
    private Sleeper sleeper = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    public LlmClient(LlmProviderFactory factory, PromptRegistry registry, UsageTracker tracker, ResponseValidator validator) {
        this.factory = factory;
        this.registry = registry;
        this.tracker = tracker;
        this.validator = validator;
    }

    /** Installs a provider (tests install {@link MockLlmProvider}); null restores configuration-based creation. */
    public void useProvider(LlmProvider provider, Sleeper sleeper) {
        this.provider = provider;
        if (sleeper != null) this.sleeper = sleeper;
    }

    private LlmProvider provider() {
        LlmProvider p = provider;
        if (p == null) {  // created lazily so a missing key only matters when the LLM is actually used
            synchronized (this) {
                if (provider == null) provider = factory.create();
                p = provider;
            }
        }
        return p;
    }

    public LlmResult generateStructured(User user, String promptKey, Map<String, ?> variables, TokenBudget run, double temperature) {
        return generate(user, promptKey, variables, run, temperature, true);
    }

    public LlmResult generateStructured(User user, String promptKey, Map<String, ?> variables) {
        return generate(user, promptKey, variables, null, 0.2, true);
    }

    public LlmResult generateStructured(User user, String promptKey, Map<String, ?> variables, TokenBudget run) {
        return generate(user, promptKey, variables, run, 0.2, true);
    }

    public LlmResult generateText(User user, String promptKey, Map<String, ?> variables, TokenBudget run) {
        return generate(user, promptKey, variables, run, 0.5, false);
    }

    private LlmResult generate(User user, String promptKey, Map<String, ?> variables, TokenBudget run, double temperature, boolean jsonMode) {
        PromptTemplate prompt = registry.get(promptKey);
        String rendered = prompt.render(variables);
        int estimate = (prompt.system().length() + rendered.length()) / 4 + 512;
        tracker.checkBudget(user, estimate);
        if (run != null && !run.withinBudget(estimate)) throw new LlmBudgetExceededException("agent run token budget reached");

        LlmRequest request = new LlmRequest(prompt.system(), rendered, jsonMode, temperature, 2048, promptKey);
        LlmProvider active = provider();
        int tokens = 0;
        LlmException lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            LlmResponse response;
            try {
                response = active.complete(request);
            } catch (LlmProviderException e) {
                tracker.record(user, prompt, active, null, false);
                lastError = e;
                if (!e.isRetryable()) break;
                if (attempt <= MAX_RETRIES) sleeper.sleep(BACKOFF_MILLIS[Math.min(attempt, BACKOFF_MILLIS.length) - 1]);
                continue;
            }
            tokens += response.totalTokens();
            if (run != null) run.addTokens(response.totalTokens());
            JsonNode data;
            try {
                data = jsonMode ? validator.validate(response.text(), prompt.schema()) : new TextNode(response.text().trim());
            } catch (LlmValidationException e) {
                tracker.record(user, prompt, active, response, false);
                lastError = e;
                request.prompt = rendered + "\n\nYour previous answer was rejected (" + e.getMessage() + "). Return valid JSON only.";
                continue;
            }
            tracker.record(user, prompt, active, response, true);
            return new LlmResult(data, response.text(), response.model(), prompt.key(), prompt.version(), tokens, attempt);
        }
        log.warn("LLM call {} failed: {}", promptKey, lastError == null ? "?" : lastError.getMessage());
        throw lastError == null ? new LlmException("LLM call failed") : lastError;
    }
}
