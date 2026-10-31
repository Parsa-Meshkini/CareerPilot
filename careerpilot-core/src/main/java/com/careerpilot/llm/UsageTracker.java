package com.careerpilot.llm;

import com.careerpilot.common.AppProperties;
import com.careerpilot.users.User;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Enforces the per-user daily token budget and records every call. */
@Component
public class UsageTracker {
    private final LlmUsageRepository repository;
    private final int dailyBudget;

    public UsageTracker(LlmUsageRepository repository, AppProperties props) {
        this.repository = repository;
        this.dailyBudget = props.getLlm().getUserDailyTokenBudget();
    }

    public long usedToday(User user) {
        return repository.tokensSince(user, Instant.now().minus(Duration.ofDays(1)));
    }

    public long checkBudget(User user, int estimatedTokens) {
        long used = usedToday(user);
        if (used + estimatedTokens > dailyBudget)
            throw new LlmBudgetExceededException("daily LLM budget reached (" + used + "/" + dailyBudget + " tokens)");
        return dailyBudget - used;
    }

    public void record(User user, PromptTemplate prompt, LlmProvider provider, LlmResponse response, boolean success) {
        LlmUsageRecord r = new LlmUsageRecord();
        r.setUser(user);
        r.setPromptKey(prompt.key());
        r.setPromptVersion(prompt.version());
        r.setProvider(provider.name());
        r.setModel(response == null ? "" : response.model());
        r.setInputTokens(response == null ? 0 : response.inputTokens());
        r.setOutputTokens(response == null ? 0 : response.outputTokens());
        r.setSuccess(success);
        repository.save(r);
    }
}
