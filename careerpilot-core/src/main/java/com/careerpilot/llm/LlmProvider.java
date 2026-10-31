package com.careerpilot.llm;

/** Target interface of the Adapter pattern: one method per vendor-neutral call. */
public interface LlmProvider {
    String name();

    LlmResponse complete(LlmRequest request);
}
