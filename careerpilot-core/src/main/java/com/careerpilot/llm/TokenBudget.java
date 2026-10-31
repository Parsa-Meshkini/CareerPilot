package com.careerpilot.llm;

/** A per-run token allowance. AgentRun implements it so LlmClient can stop a run that overspends. */
public interface TokenBudget {
    boolean withinBudget(int estimate);
    void addTokens(int tokens);
}
