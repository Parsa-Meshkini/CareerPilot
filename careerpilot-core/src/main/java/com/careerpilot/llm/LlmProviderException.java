package com.careerpilot.llm;

public class LlmProviderException extends LlmException {
    private final boolean retryable;
    public LlmProviderException(String message, boolean retryable) { super(message); this.retryable = retryable; }
    public LlmProviderException(String message, boolean retryable, Throwable cause) { super(message, cause); this.retryable = retryable; }
    public boolean isRetryable() { return retryable; }
}
